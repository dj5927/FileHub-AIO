using System.Diagnostics;
using System.IO.Pipes;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Collections.Concurrent;
using System.Runtime.InteropServices;

namespace NASMount;

// Remote protocols use rclone/WinFsp for the filesystem. This DOES NOT enforce
// cross-machine Office document locks; a lock-aware filesystem layer is still needed.
internal static class RemoteMount
{
    private sealed record Instance(Process Process, int RcPort, string RcUser,
        string RcPassword, Process? Bridge, MyBoxBetaGateway? MyBoxGateway = null,
        LegacyCharsetGateway? LegacyGateway = null,
        NativeWebDavGateway? NativeGateway = null);
    private static readonly ConcurrentDictionary<Guid,string> IntegrityAlerts = new();
    internal static string? GetIntegrityAlert(Guid id) =>
        IntegrityAlerts.TryGetValue(id, out var message) ? message : null;
    internal sealed record UploadState(int Queued, int Uploading, int Errors, bool OutOfSpace)
    {
        public bool IsSafeToDisconnect => Queued == 0 && Uploading == 0 &&
            Errors == 0 && !OutOfSpace;
        public string Summary => $"업로드 대기 {Queued}개 / 전송 중 {Uploading}개 / 실패 {Errors}개" +
            (OutOfSpace ? " / 캐시 공간 부족" : "");
    }
    private static readonly Dictionary<Guid, Instance> Running = [];
    private static readonly object Guard = new();
    // An emergency operation is an explicit user request. It blocks an
    // in-flight connect from launching another backend after an emergency stop.
    private static readonly ConcurrentDictionary<Guid, byte> EmergencyRequested = new();
    internal static void RequestEmergency(Guid id) => EmergencyRequested[id] = 1;
    internal static void ClearEmergency(Guid id) => EmergencyRequested.TryRemove(id, out _);
    internal static bool EmergencyPending(Guid id) => EmergencyRequested.ContainsKey(id);
    // A mountpoint's existence alone does not prove Explorer can open it.
    private static readonly HashSet<Guid> BrowseVerified = [];
    // Never-usable startup processes may be terminated immediately.
    // Once a mount passed directory/capacity verification it may contain
    // pending writes even if it later becomes unhealthy.
    private static readonly HashSet<Guid> EverBrowseVerified = [];
    internal static bool FailedBeforeFirstMount(NasProfile p)
    {
        lock (Guard)
            return Running.ContainsKey(p.Id) && !EverBrowseVerified.Contains(p.Id);
    }
    internal static async Task<OperationResult> StopFailedBeforeFirstMountAsync(NasProfile p)
    {
        if (!FailedBeforeFirstMount(p))
            return OperationResult.Fail("기존 정상 연결은 실패한 신규 연결로 취급하지 않습니다.");
        bool manualCancel = EmergencyPending(p.Id);
        AppStore.Log("FAILED_CONNECT_IMMEDIATE_CLEANUP id=" + p.Id +
            " never_mounted=true");
        var stopped = await EmergencyStopOwnedAsync(p);
        if (stopped.Success)
        {
            try
            {
                var cleaned = await Task.Run(() =>
                    NetworkDriveResidueCleaner.AfterSafeStop(p))
                    .WaitAsync(TimeSpan.FromSeconds(3));
                AppStore.Log("FAILED_CONNECT_SHELL_CLEANUP id=" + p.Id +
                    " ok=" + cleaned.Success);
            }
            catch (Exception ex)
            {
                AppStore.Log("FAILED_CONNECT_SHELL_CLEANUP_DEFERRED id=" +
                    p.Id + " error=" + ex.GetType().Name);
            }
            // Automated startup failures may retry after the normal backoff.
            // A user-initiated Disconnect/Exit keeps its cancellation flag.
            if (!manualCancel) ClearEmergency(p.Id);
        }
        return stopped;
    }
    // Live health probes never run inside WinForms paint/card refresh.
    // Keep exactly one outstanding probe for each mounted connection,
    // including while an OS filesystem callback is hung.
    private static readonly ConcurrentDictionary<Guid, Task<OperationResult>>
        ActiveHealthChecks = new();
    private static readonly ConcurrentDictionary<Guid, DateTime> LastHealthy = new();
    internal static DateTime? LastConfirmedHealthy(Guid id) =>
        LastHealthy.TryGetValue(id, out var at) ? at : null;

    internal static async Task<OperationResult> CheckMountedHealthAsync(
        NasProfile profile)
    {
        if (!IsRunning(profile))
            return OperationResult.Fail("FileHub가 관리하는 연결 프로세스가 종료됐습니다.");
        Task<OperationResult> probe = ActiveHealthChecks.GetOrAdd(profile.Id,
            _ => VerifyExplorerAccess(profile));
        try
        {
            // Even if a hung kernel callback outlives this timeout, the
            // same task remains registered; do not spawn one every tick.
            return await probe.WaitAsync(TimeSpan.FromSeconds(6));
        }
        catch (TimeoutException)
        {
            lock (Guard) BrowseVerified.Remove(profile.Id);
            AppStore.Log("MOUNT_HEALTH_TIMEOUT id=" + profile.Id +
                " protocol=" + profile.Protocol + " owned_backend_preserved=true");
            return OperationResult.Fail("탐색기 드라이브가 6초 내 응답하지 않았습니다.");
        }
        finally
        {
            if (probe.IsCompleted)
                ActiveHealthChecks.TryRemove(
                    new KeyValuePair<Guid, Task<OperationResult>>(profile.Id, probe));
        }
    }

    private static async Task<OperationResult> VerifyExplorerAccess(NasProfile profile)
    {
        string root = profile.Letter.ToUpperInvariant() + @":\";
        var overall = Stopwatch.StartNew();
        string lastStage = "exists";
        try
        {
            var phases = await Task.Run(() =>
            {
                var step = Stopwatch.StartNew();
                if (!Directory.Exists(root)) throw new DirectoryNotFoundException(root);
                long existsMs = step.ElapsedMilliseconds;
                lastStage = "enumerate";
                using var enumerator = Directory.EnumerateFileSystemEntries(root).GetEnumerator();
                _ = enumerator.MoveNext(); // Forces WinFsp ReadDirectory; empty is valid.
                long enumerateMs = step.ElapsedMilliseconds - existsMs;
                lastStage = "capacity";
                _ = new DriveInfo(root).TotalSize; // Forces WinFsp GetVolumeInfo.
                return (existsMs, enumerateMs,
                    capacityMs: step.ElapsedMilliseconds - existsMs - enumerateMs);
            }).WaitAsync(GoogleDrivePreflightPolicy.ExplorerBrowseTimeout(profile.Protocol));
            AppStore.Log("CONNECT_ROOT_PHASE_TIMING id=" + profile.Id +
                " protocol=" + profile.Protocol +
                " existsMs=" + phases.existsMs +
                " enumerateMs=" + phases.enumerateMs +
                " capacityMs=" + phases.capacityMs +
                " totalMs=" + overall.ElapsedMilliseconds +
                " success=true");
            lock (Guard) BrowseVerified.Add(profile.Id);
            lock (Guard) EverBrowseVerified.Add(profile.Id);
            LastHealthy[profile.Id] = DateTime.UtcNow;
            return OperationResult.Ok("Explorer directory and capacity verified");
        }
        catch (Exception ex)
        {
            lock (Guard) BrowseVerified.Remove(profile.Id);
            string detail = ex is TimeoutException
                ? $"{(int)GoogleDrivePreflightPolicy.ExplorerBrowseTimeout(profile.Protocol).TotalSeconds} second Explorer browse timeout"
                : ex.Message;
            AppStore.Log("EXPLORER_ROOT_VERIFY_FAILED " + profile.Id +
                " protocol=" + profile.Protocol +
                " stage=" + lastStage + " totalMs=" + overall.ElapsedMilliseconds +
                " " + detail);
            return OperationResult.Fail("탐색기에서 드라이브 폴더를 열 수 없습니다. " + detail +
                "\n드라이브는 강제로 종료하지 않았습니다. 업로드 상태를 확인하고 안전하게 연결 해제하세요.");
        }
    }
    private static async Task<OperationResult> VerifiedThenOk(NasProfile profile,
        string successMessage)
    {
        var browseTimer = Stopwatch.StartNew();
        var checkedRoot = await VerifyExplorerAccess(profile);
        AppStore.Log("CONNECT_ROOT_BROWSE_TIMING id=" + profile.Id +
            " elapsedMs=" + browseTimer.ElapsedMilliseconds +
            " ok=" + checkedRoot.Success);
        return checkedRoot.Success ? OperationResult.Ok(successMessage) : checkedRoot;
    }
    internal static bool MatchingProcessPid(string responseJson, int processId)
    {
        using var root = JsonDocument.Parse(responseJson);
        return root.RootElement.TryGetProperty("pid", out var pid) &&
            pid.TryGetInt32(out int observed) && observed == processId;
    }

    internal static async Task<OperationResult> CheckPersistentMount(NasProfile profile)
    {
        Instance? instance;
        lock (Guard) Running.TryGetValue(profile.Id, out instance);
        if (instance is null)
            return OperationResult.Fail("이 앱이 시작한 마운트가 없습니다. 이전 실행 파일의 드라이브는 별도로 확인해야 합니다.");
        if (instance.Process.HasExited)
            return OperationResult.Fail("rclone 프로세스가 종료됐습니다. 종료 코드=" +
                instance.Process.ExitCode + ". 앱 로그에서 종료 시점을 확인하세요.");
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
            http.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue(
                    "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(
                        instance.RcUser + ":" + instance.RcPassword)));
            var target = $"http://127.0.0.1:{instance.RcPort}/core/pid";
            using var reply = await http.PostAsync(target, new StringContent("{}"));
            reply.EnsureSuccessStatusCode();
            bool same = MatchingProcessPid(await reply.Content.ReadAsStringAsync(),
                instance.Process.Id);
            if (!same)
                return OperationResult.Fail(
                    "현재 앱이 관리하는 rclone 프로세스와 RC 응답의 PID가 다릅니다. " +
                    "이 연결을 정상으로 판단할 수 없습니다.");
            var uptime = DateTime.Now - instance.Process.StartTime;
            var transfer = await GetTransferStatus(profile);
            bool liveNetworkMode = instance.Process.StartInfo.ArgumentList.Contains("--network-mode");
            bool configuredNetworkMode = UsesNetworkDriveMode(profile);
            string presentation = profile.UseLockBridge ? "브리지 시험 모드" :
                liveNetworkMode ? "가상 네트워크 공유 (\\\\server\\NASMount-...)" :
                "고정 디스크 표시";
            string report =
                $"rclone 프로세스: 살아 있음 (PID {instance.Process.Id})\n" +
                $"실행 유지 시간: {uptime:dd\\.hh\\:mm\\:ss}\n" +
                $"RC 응답: 동일 프로세스 PID 확인\n" +
                $"실행 중인 실제 마운트 표시: {presentation}\n" +
                (liveNetworkMode == configuredNetworkMode ? "" :
                    "주의: 저장된 프로필과 실행 중인 마운트 모드가 다릅니다. 안전 해제 후 재연결이 필요합니다.\n") +
                $"디렉터리 정보 캐시: {(profile.OfficeReadTuning ? "20초" :
                    profile.FastReadOnly ? "10초" : "1초")}\n\n" +
                "현재 조회 때마다 마운트 프로세스를 새로 실행하는 상황은 아닙니다.\n" +
                "다만 파일 읽기 중 WebDAV 서버에 HEAD/PROPFIND/GET 요청이 발생하는지는 " +
                "별도의 읽기 진단으로 측정해야 합니다.\n\n" +
                "업로드 상태: " + transfer.Message;
            return OperationResult.Ok(report);
        }
        catch (Exception ex)
        {
            return OperationResult.Fail("rclone 프로세스는 실행 중이지만 로컬 RC 응답 확인 실패: " +
                ex.Message + "\n프로세스 PID=" + instance.Process.Id);
        }
    }

    internal static async Task<OperationResult> RefreshRootCache(NasProfile profile)
    {
        Instance? instance;
        lock (Guard) Running.TryGetValue(profile.Id, out instance);
        if (instance is null || instance.Process.HasExited)
            return OperationResult.Fail("해당 프로필로 실행 중인 rclone 마운트가 없습니다.");
        if (profile.Protocol != "WebDAV")
            return OperationResult.Fail("WebDAV 프로필에서만 폴더 목록을 미리 조회합니다.");
        try
        {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(25) };
            http.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue(
                    "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(
                        instance.RcUser + ":" + instance.RcPassword)));
            // Nonrecursive: never issue an unbounded directory traversal.
            // This is a read-only VFS metadata cache warmup, not file prefetch.
            using var result = await http.PostAsync(
                $"http://127.0.0.1:{instance.RcPort}/vfs/refresh",
                new StringContent("{\"recursive\":false}", Encoding.UTF8, "application/json"));
            string response = await result.Content.ReadAsStringAsync();
            if (!result.IsSuccessStatusCode)
                return OperationResult.Fail("폴더 조회 실패, HTTP " +
                    (int)result.StatusCode + ": " + response[..Math.Min(200, response.Length)]);
            return OperationResult.Ok(
                "NAS 공유의 최상위 폴더 목록을 rclone 메모리 캐시에 미리 조회했습니다.\n" +
                "하위 폴더 또는 Excel 내용 전체를 다운로드한 것은 아닙니다.\n" +
                "다른 PC의 변경 사항은 프로필의 디렉터리 캐시 만료 후 반영될 수 있습니다.");
        }
        catch (Exception ex)
        {
            return OperationResult.Fail("폴더 캐시 예열 실패: " + ex.Message);
        }
    }
    internal static string? RclonePath
    {
        get
        {
            string adjacent = Path.Combine(AppContext.BaseDirectory, "tools", "rclone.exe");
            if (File.Exists(adjacent)) return adjacent;
            string root = Path.Combine(AppContext.BaseDirectory, "rclone.exe");
            if (File.Exists(root)) return root;
            string? path = Environment.GetEnvironmentVariable("PATH");
            foreach (var dir in (path ?? "").Split(Path.PathSeparator))
            {
                try
                {
                    string candidate = Path.Combine(dir.Trim('"'), "rclone.exe");
                    if (File.Exists(candidate)) return candidate;
                }
                catch { }
            }
            return null;
        }
    }
    internal static string? BackendExecutable(NasProfile profile) =>
        profile.Protocol == "TeraBox"
            ? (File.Exists(Path.Combine(AppContext.BaseDirectory, "tools", "rclone-terabox.exe"))
                ? Path.Combine(AppContext.BaseDirectory, "tools", "rclone-terabox.exe") : null)
            : RclonePath;

    internal static bool HasWinFsp => new[]
    {
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles)
    }.Any(folder => File.Exists(Path.Combine(folder, "WinFsp", "bin", "winfsp-x64.dll")));
    internal static string InstallerPath => Path.Combine(AppContext.BaseDirectory, "tools",
        WinFspUpgradePolicy.InstallerFileName);

    public static bool IsMounted(NasProfile p)
    {
        // Never call Directory.Exists on a WinFsp mount from the WinForms UI.
        // With a stalled server/native LOCK callback, that kernel operation
        // can block the entire window, including Close and the tray menu.
        lock (Guard)
        {
            if (!Running.TryGetValue(p.Id, out var instance)) return false;
            if (instance.Process.HasExited || (instance.Bridge is not null && instance.Bridge.HasExited) ||
                !BrowseVerified.Contains(p.Id))
            {
                return false;
            }
        }
        return true; // Root was already verified asynchronously.
    }

    // One outstanding Windows filesystem probe per connection attempt.
    // A blocked WinFsp callback must NOT spawn another blocked thread every
    // 250ms and must never pin the UI synchronization context.
    private sealed class MountRootProbe(string root, Func<bool>? check = null)
    {
        private Task<bool>? pending;
        internal int Started { get; private set; }
        internal async Task<bool> CheckAsync()
        {
            if (pending is null)
            {
                Started++;
                pending = Task.Run(check ?? (() => Directory.Exists(root)));
            }
            try
            {
                bool exists = await pending.WaitAsync(TimeSpan.FromMilliseconds(650));
                pending = null; // false is normal while mount initializes.
                return exists;
            }
            catch (TimeoutException)
            {
                // Keep ONE blocked probe rather than starting a new one.
                AppStore.Log("WINFSP_ROOT_PROBE_TIMEOUT root=" +
                    (root.Length > 1 ? root[..2] : "private"));
                return false;
            }
        }
    }

    internal static async Task EmergencyPolicyTestAsync()
    {
        // No NAS/drive/process is touched by this regression test.
        Guid id = Guid.NewGuid();
        RequestEmergency(id);
        if (!EmergencyPending(id))
            throw new InvalidOperationException("Emergency request flag missing");
        var result = await EmergencyStopOwnedAsync(new NasProfile { Id = id });
        if (result.Success || !EmergencyPending(id))
            throw new InvalidOperationException(
                "An unowned process may never be terminated during emergency shutdown");
        ClearEmergency(id);
        if (EmergencyPending(id))
            throw new InvalidOperationException("Manual retry must clear emergency flag");

        var probe = new MountRootProbe("X:", () =>
        {
            Thread.Sleep(2250); // Simulated stalled kernel callback.
            return false;
        });
        var sw = Stopwatch.StartNew();
        if (await probe.CheckAsync() || await probe.CheckAsync() ||
            sw.Elapsed > TimeSpan.FromSeconds(1.9) || probe.Started != 1)
            throw new InvalidOperationException(
                "WinFsp root probe blocked UI or spawned duplicate stuck workers");

        // Prove that ONLY the exact child registered in our in-memory
        // ownership record can be terminated, without touching any NAS.
        using var child = new Process();
        string executable = Environment.ProcessPath ??
            throw new InvalidOperationException("No test executable path");
        child.StartInfo = new ProcessStartInfo(executable)
        {
            UseShellExecute = false,
            CreateNoWindow = true
        };
        if (Path.GetFileNameWithoutExtension(executable)
            .Equals("dotnet", StringComparison.OrdinalIgnoreCase))
            child.StartInfo.ArgumentList.Add(
                System.Reflection.Assembly.GetExecutingAssembly().Location);
        child.StartInfo.ArgumentList.Add("--emergency-child-wait");
        var childId = Guid.NewGuid();
        try
        {
            if (!child.Start())
                throw new InvalidOperationException("Cannot start isolated test child");
            lock (Guard) Running[childId] = new Instance(child, 0, "", "", null);
            var fake = new NasProfile { Id = childId, Protocol = "WebDAV" };
            if (!FailedBeforeFirstMount(fake))
                throw new InvalidOperationException(
                    "A never-mounted backend must be eligible for immediate stop");
            lock (Guard) EverBrowseVerified.Add(childId);
            if (FailedBeforeFirstMount(fake))
                throw new InvalidOperationException(
                    "Previously browsable backend may have unsynced changes");
            lock (Guard) EverBrowseVerified.Remove(childId);
            var stopped = await EmergencyStopOwnedAsync(
                fake);
            if (!stopped.Success || !child.HasExited)
                throw new InvalidOperationException("Owned test child did not stop");
        }
        finally
        {
            if (!child.HasExited)
            {
                try { child.Kill(); } catch { }
            }
            lock (Guard) Running.Remove(childId);
            lock (Guard) EverBrowseVerified.Remove(childId);
            ClearEmergency(childId);
        }
        Console.WriteLine("EMERGENCY_OWNED_PID_ONLY_AND_BOUNDED_ROOT_PROBE_PASS");
    }

    public static bool IsRunning(NasProfile p)
    {
        lock (Guard)
            return Running.TryGetValue(p.Id, out var instance) && !instance.Process.HasExited &&
                   (instance.Bridge is null || !instance.Bridge.HasExited);
    }

    // Unlike IsRunning, also include half-shutdown sessions where the rclone
    // process died but the WinFsp bridge is still mounted. Safe exit must
    // never silently skip those live handles.
    internal static bool HasManagedProcess(NasProfile p)
    {
        lock (Guard)
            return Running.TryGetValue(p.Id, out var instance) &&
                   (!instance.Process.HasExited ||
                    (instance.Bridge is not null && !instance.Bridge.HasExited));
    }

    // Includes crashed/completed children whose mount record still needs
    // Windows-drive verification and our shell-icon cleanup before exit.
    internal static bool HasManagedRecord(NasProfile p)
    {
        lock (Guard) return Running.ContainsKey(p.Id);
    }

    internal static bool DriveLetterExists(string letter)
    {
        if (letter.Length != 1 || !char.IsLetter(letter[0])) return false;
        string prefix = letter.ToUpperInvariant() + ":\\";
        try
        {
            return Environment.GetLogicalDrives().Any(d =>
                d.Equals(prefix, StringComparison.OrdinalIgnoreCase));
        }
        catch (Exception ex)
        {
            // Fail closed. A drive is not considered released unless we can
            // successfully confirm that Windows no longer enumerates it.
            AppStore.Log("DRIVE_ENUMERATE_ERROR " + letter + " " + ex.Message);
            return true;
        }
    }

    [DllImport("mpr.dll", EntryPoint = "WNetGetConnectionW", CharSet = CharSet.Unicode)]
    private static extern int WNetGetConnection(string localName,
        StringBuilder remoteName, ref int length);

    internal static bool IsOurNetworkShare(NasProfile profile, string? remotePath)
    {
        if (profile.Protocol == "SMB" || string.IsNullOrWhiteSpace(remotePath)) return false;
        return System.Text.RegularExpressions.Regex.IsMatch(remotePath,
            @"\A\\\\NASMount-" + profile.Id.ToString("N")[..8] +
            @"(?:-[a-fA-F0-9]{8})?\\",
            System.Text.RegularExpressions.RegexOptions.CultureInvariant |
            System.Text.RegularExpressions.RegexOptions.IgnoreCase);
    }

    internal static bool HasVerifiedOtherVendorMapping(NasProfile profile)
    {
        if (profile.Protocol == "SMB" || !DriveLetterExists(profile.Letter)) return false;
        try
        {
            int size = 2048;
            var remote = new StringBuilder(size);
            int code = WNetGetConnection(profile.Letter.ToUpperInvariant() + ":",
                remote, ref size);
            // Only a SUCCESSFUL network-provider query can establish that
            // the drive letter is owned by something other than FileHub AIO.
            // A WinFsp share that Windows cannot resolve stays untouched.
            return code == 0 && !IsOurNetworkShare(profile, remote.ToString());
        }
        catch { return false; }
    }

    internal static void ShutdownOwnershipSelfTest()
    {
        var profile = new NasProfile {
            Id = Guid.Parse("12345678-90ab-cdef-1234-567890abcdef"),
            Protocol = "WebDAV", Letter = "S", AutoConnect = true
        };
        void Require(bool condition, string label)
        {
            if (!condition) throw new InvalidOperationException("SHUTDOWN_POLICY_FAIL " + label);
        }
        Require(IsOurNetworkShare(profile, @"\\NASMount-12345678\Wintec"),
            "own GUID prefix");
        Require(IsOurNetworkShare(profile,
            @"\\NASMount-12345678-a1b2c3d4\Google Drive"),
            "Google session GUID prefix");
        Require(!IsOurNetworkShare(profile, @"\\NASMount-87654321\Wintec"),
            "another profile GUID");
        Require(!IsOurNetworkShare(profile, @"\\RaiDrive-UserK\WebDAV"),
            "RaiDrive share");
        Require(!IsOurNetworkShare(profile, @"\\NASMount-123456789\Other"),
            "exact eight digit prefix");
        Require(!IsOurNetworkShare(profile, null), "null path");
        profile.Protocol = "SMB";
        Require(!IsOurNetworkShare(profile, @"\\NASMount-12345678\Wintec"),
            "never cancel SMB");
        Require(!DriveLetterExists(""), "invalid empty letter");
        Require(!DriveLetterExists("1"), "invalid numeric letter");
        Require(new UploadState(0,0,0,false).IsSafeToDisconnect, "no dirty VFS");
        Require(!new UploadState(1,0,0,false).IsSafeToDisconnect, "queued upload");
        Require(!new UploadState(0,1,0,false).IsSafeToDisconnect, "active upload");
        Require(!new UploadState(0,0,1,false).IsSafeToDisconnect, "upload errors");
        Require(!new UploadState(0,0,0,true).IsSafeToDisconnect, "cache out of space");
        Console.WriteLine("SAFE_EXIT_OWNERSHIP_AND_UPLOAD_POLICY_PASS");
    }

    private static async Task<bool> WaitForLetterRelease(string letter)
    {
        // Poll more frequently but keep the same 7.5 second safety bound.
        for (int n = 0; n < 75; n++)
        {
            if (!DriveLetterExists(letter)) return true;
            await Task.Delay(100);
        }
        return !DriveLetterExists(letter);
    }

    private static async Task<UploadState> ReadUploadState(Instance instance,
        CancellationToken cancellationToken = default, int attempts = 1)
    {
        // rclone's local control channel sometimes fails to respond while
        // its VFS is busy serving Explorer. Do not confuse a SINGLE 6-second
        // probe timeout with proof that uploads are clear or the volume is
        // safe to force unmount. Safe shutdown may make ONE bounded retry.
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                using var client = new HttpClient {
                    Timeout = TimeSpan.FromSeconds(6)
                };
                var rawAuth = Encoding.UTF8.GetBytes(
                    instance.RcUser + ":" + instance.RcPassword);
                client.DefaultRequestHeaders.Authorization =
                    new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",
                        Convert.ToBase64String(rawAuth));
                using var response = await client.PostAsync(
                    $"http://127.0.0.1:{instance.RcPort}/vfs/stats",
                    new StringContent("{}"), cancellationToken);
                response.EnsureSuccessStatusCode();
                return ParseUploadStatus(
                    await response.Content.ReadAsStringAsync(cancellationToken));
            }
            catch (OperationCanceledException e) when
                (!cancellationToken.IsCancellationRequested && attempt < attempts)
            {
                AppStore.Log("LOCAL_RC_UPLOAD_CHECK_RETRY port=" +
                    instance.RcPort + " attempt=" + attempt +
                    " type=timeout message=" + e.GetType().Name);
                await Task.Delay(350, cancellationToken);
            }
            catch (HttpRequestException e) when (attempt < attempts)
            {
                AppStore.Log("LOCAL_RC_UPLOAD_CHECK_RETRY port=" +
                    instance.RcPort + " attempt=" + attempt +
                    " type=network message=" + e.GetType().Name);
                await Task.Delay(350, cancellationToken);
            }
        }
    }

    internal static UploadState ParseUploadStatus(string json)
    {
        using var document = JsonDocument.Parse(json);
        if (!document.RootElement.TryGetProperty("diskCache", out var cache))
            throw new IOException("VFS 디스크 캐시 상태가 제공되지 않습니다. 안전한 연결 해제를 보장할 수 없습니다.");
        int Value(string name) =>
            cache.TryGetProperty(name, out var value) && value.TryGetInt32(out var number)
                ? number : throw new IOException("VFS 상태에 " + name + " 값이 없습니다.");
        return new UploadState(Value("uploadsQueued"), Value("uploadsInProgress"),
            Value("erroredFiles"),
            cache.TryGetProperty("outOfSpace", out var outOfSpace) && outOfSpace.GetBoolean());
    }

    internal static async Task<OperationResult> GetTransferStatus(NasProfile profile)
    {
        Instance? instance;
        lock (Guard) Running.TryGetValue(profile.Id, out instance);
        if (instance is null || instance.Process.HasExited)
            return OperationResult.Fail("현재 이 프로그램이 관리하는 연결이 없습니다.");
        try
        {
            var state = await ReadUploadState(instance);
            string? critical = GetIntegrityAlert(profile.Id);
            if (critical is not null)
                return OperationResult.Fail(critical + "\n" + state.Summary +
                    "\n원본·TMP·로컬 캐시를 보존하고 DSM의 실제 파일을 확인하세요.");
            return state.IsSafeToDisconnect
                ? OperationResult.Ok(state.Summary)
                : OperationResult.Fail(state.Summary + "\n로컬 캐시 또는 원격 업로드가 아직 완료되지 않았습니다.");
        }
        catch (Exception ex)
        {
            return OperationResult.Fail("업로드 상태를 확인할 수 없습니다: " + ex.Message);
        }
    }

    public static bool TryValidate(NasProfile p, out string reason)
    {
        reason = "";
        if (p.Protocol == "SMB") return WindowsShare.TryValidate(p, out reason);
        if (p.Protocol == "FTP" && !CharsetSupport.IsSupported("FTP", p.FtpCharset))
        {
            reason = CharsetSupport.DescribeUnsupported("FTP", p.FtpCharset);
            return false;
        }
        if (p.Protocol == "SFTP" && !CharsetSupport.IsSupported("SFTP", p.SftpCharset))
        {
            reason = CharsetSupport.DescribeUnsupported("SFTP", p.SftpCharset);
            return false;
        }
        if (p.Protocol is not ("WebDAV" or "SFTP" or "FTP" or "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox"))
            reason = "지원하지 않는 연결 방식입니다.";
        else if (p.Letter.Length != 1 || !char.IsLetter(p.Letter[0]))
            reason = "드라이브 문자를 선택하세요.";
        else if ((p.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox") && (p.UseLockBridge || p.Host.Length > 0 || p.Share.Length > 0))
            reason = "클라우드 드라이브에는 NAS 주소나 파일잠금 브리지가 필요하지 않습니다.";
        else if (p.Protocol is not ("GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox") && p.Port is < 0 or > 65535)
            reason = "포트는 1~65535 범위여야 합니다.";
        else if (p.Protocol is not ("GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox") && (p.Host.Length == 0 || p.Host.IndexOfAny(['\\','/','@',' ', '\r','\n']) >= 0 || p.Host.Contains(':')))
            reason = "주소는 IP 또는 호스트 이름만 입력하세요. 경로/포트/URL은 별도 항목입니다.";
        else if (p.Protocol is not ("GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox") && (p.Share.Contains('\r') || p.Share.Contains('\n')))
            reason = "공유 경로에 줄바꿈을 입력할 수 없습니다.";
        else if (p.UseNativeWebDavLocks &&
                 (p.Protocol != "WebDAV" || !p.UseLockBridge ||
                  p.AllowUnprotectedWrites))
            reason = "WebDAV 서버 기본 잠금은 WebDAV 파일잠금 브리지 모드에서만 켤 수 있습니다.";
        return reason.Length == 0;
    }

    private static string RemoteName(NasProfile p) => "nm_" + p.Id.ToString("N");
    private static string RemoteRoot(NasProfile p) => RemoteName(p) + ":" +
        (CharsetSupport.RequiresGateway(p) ? "" : p.Share.Trim().Replace('\\','/'));

    // WinFsp supports native Windows network drives. RaiDrive 2026.10
    // added WinFsp as an available filesystem driver too, but its private
    // implementation isn't accessible. Make FileHub AIO explicitly manage
    // the arrival/removal Shell lifecycle instead of assuming that changing
    // the mount presentation to a local disk is a satisfactory substitute.
    // Users who prefer the old no-UNC cloud presentation can still opt into
    // fixed mode per cloud profile in connection settings.
    internal static bool UsesNetworkDriveMode(NasProfile p) =>
        p.Protocol != "SMB" &&
        !p.UseLockBridge && !p.UseFixedDriveMode;

    internal static bool UsesStableCloudFixedMode(NasProfile p) =>
        p.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox" &&
        !UsesNetworkDriveMode(p);

    // Use the exact same argument builder in production and policy tests.
    // A --volname that begins with "\\" implicitly enables network mode.
    // For cloud network profiles emit BOTH a correctly owned UNC and the
    // network flag. For fixed fallback emit neither, so no ghost server.
    internal static IReadOnlyList<string> WinFspPresentationArgs(NasProfile p)
    {
        var output = new List<string>();
        bool network = UsesNetworkDriveMode(p);
        if (network) output.Add("--network-mode");
        output.Add("--volname");
        output.Add(p.UseLockBridge
            ? "NASMount-private-" + p.Id.ToString("N")[..8]
            : network
                ? MountPresentation.RcloneNetworkVolume(p)
                : MountPresentation.SafeLabel(p.DisplayName));
        return output;
    }

    private static async Task<string> ObscurePassword(string exe, string password)
    {
        // STDIN avoids exposing passwords on the command line or in config files.
        using var p = new Process
        {
            StartInfo = new ProcessStartInfo(exe)
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardInput = true, RedirectStandardOutput = true,
                RedirectStandardError = true
            }
        };
        p.StartInfo.ArgumentList.Add("obscure");
        p.StartInfo.ArgumentList.Add("-");
        p.Start();
        await p.StandardInput.WriteLineAsync(password);
        p.StandardInput.Close();
        var output = await p.StandardOutput.ReadToEndAsync();
        var error = await p.StandardError.ReadToEndAsync();
        await p.WaitForExitAsync();
        if (p.ExitCode != 0 || string.IsNullOrWhiteSpace(output))
            throw new IOException("암호 변환 실패: " + error);
        return output.Trim();
    }

    private static async Task<ProcessStartInfo> BuildStartInfo(NasProfile p,
        string? password, string executable, string? myBoxLoopbackUrl = null,
        string? legacyLoopbackUrl = null, string? nativeLoopbackUrl = null)
    {
        var psi = new ProcessStartInfo(executable)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardError = true, RedirectStandardOutput = true
        };
        if (p.Protocol == "WebDAV" && !p.WebDavUseHttp)
            psi.ArgumentList.Add("--no-check-certificate");
        string envPrefix = "RCLONE_CONFIG_" + RemoteName(p).ToUpperInvariant() + "_";
        psi.Environment[envPrefix + "TYPE"] = p.Protocol == "GoogleDrive" ? "drive" :
            p.Protocol == "MyBox" || CharsetSupport.RequiresGateway(p) ?
                "webdav" : p.Protocol.ToLowerInvariant();
        if (CharsetSupport.RequiresGateway(p))
        {
            if (legacyLoopbackUrl is null)
                throw new InvalidOperationException("레거시 문자셋 게이트웨이가 준비되지 않았습니다.");
            psi.Environment[envPrefix + "URL"] = legacyLoopbackUrl;
            psi.Environment[envPrefix + "VENDOR"] = "other";
            return psi;
        }
        if (p.Protocol == "MyBox")
        {
            if (myBoxLoopbackUrl is null)
                throw new InvalidOperationException("MYBOX 로컬 연결 게이트웨이가 없습니다.");
            psi.Environment[envPrefix + "URL"] = myBoxLoopbackUrl;
            psi.Environment[envPrefix + "VENDOR"] = "other";
            return psi;
        }
        if (p.Protocol is "GoogleDrive" or "OneDrive") return psi;
        if (p.Protocol == "TeraBox")
        {
            psi.Environment[envPrefix + "COOKIE"] = TeraBoxBackend.NormalizeCookie(password);
            return psi;
        }
        // FTP anonymous uses its conventional identity. WebDAV sends no
        // credentials. SFTP anonymous only works when SSH server allows it.
        psi.Environment[envPrefix + "USER"] = RemoteUsername(p);
        string port = (p.Port <= 0 ? (p.Protocol == "WebDAV" ? 5006 : p.Protocol == "SFTP" ? 22 : 21) : p.Port).ToString();
        if (p.Protocol == "WebDAV")
        {
            psi.Environment[envPrefix + "URL"] = nativeLoopbackUrl ??
                (p.WebDavUseHttp ? "http://" : "https://") + p.Host + ":" + port + "/";
            psi.Environment[envPrefix + "VENDOR"] = "other";
        }
        else
        {
            psi.Environment[envPrefix + "HOST"] = p.Host;
            psi.Environment[envPrefix + "PORT"] = port;
            if (p.Protocol == "FTP")
                foreach (var setting in FtpEnvironment(p))
                    psi.Environment[envPrefix + setting.Key] = setting.Value;
            if (p.Protocol == "SFTP")
            {
                // The 1.3.42-and-earlier builds inherited known_hosts_file
                // and aborted at first use with "knownhosts: key is unknown".
                // A MACHINE-WIDE rclone env var can also override a modern
                // per-profile HOST_KEYS pin. Isolate this named remote.
                foreach (string inherited in new[] {
                    "RCLONE_SFTP_KNOWN_HOSTS_FILE",
                    "RCLONE_SFTP_HOST_KEYS",
                    "RCLONE_SFTP_PIN_HOST_KEY",
                    envPrefix + "KNOWN_HOSTS_FILE",
                    envPrefix + "PIN_HOST_KEY"
                }) psi.Environment.Remove(inherited);
                // Previous versions pointed every SFTP connection at the
                // global OpenSSH known_hosts even for a first-time server.
                // That fails with "knownhosts: key is unknown". Instead,
                // rclone verifies this profile's explicitly approved key.
                // Never set known_hosts_file concurrently: it overrides
                // HOST_KEYS and makes the pin ineffective.
                if (!SftpHostKeyTrust.HasTrustedKey(p))
                    throw new InvalidOperationException(
                        "SFTP 서버 SSH 호스트 키가 아직 승인되지 않았습니다.\n" +
                        "FileHub AIO에서 [연결]을 눌러 서버 지문을 확인하고 승인하세요.");
                psi.Environment[envPrefix + "HOST_KEYS"] = p.SftpPinnedHostKey;
                AppStore.Log("SFTP_HOST_KEY_POLICY version=1.3.45 profile=" +
                    p.Id + " host=" + p.Host + " port=" + port +
                    " fingerprint=" + p.SftpHostFingerprint +
                    " source=APPROVED_PIN (not global known_hosts)");
            }
        }
        string? actualSecret = RemoteSecret(p, password);
        if (!string.IsNullOrEmpty(actualSecret))
            psi.Environment[envPrefix + "PASS"] = await ObscurePassword(executable, actualSecret);
        return psi;
    }

    internal static string RemoteUsername(NasProfile p) =>
        p.IsAnonymous && p.Protocol is ("FTP" or "SFTP") ? "anonymous" :
        p.IsAnonymous ? "" : p.Username;

    internal static string? RemoteSecret(NasProfile p, string? password) =>
        p.IsAnonymous ? (p.Protocol == "FTP" ? "anonymous@" : null) : password;

    internal static void WebDavEndpointPolicyTest()
    {
        var profile = new NasProfile {
            Id = Guid.NewGuid(), Protocol = "WebDAV",
            Host = "custom.storage.example", Port = 5006,
            IsAnonymous = true, WebDavUseHttp = true
        };
        string prefix = "RCLONE_CONFIG_NM_" +
            profile.Id.ToString("N").ToUpperInvariant() + "_";
        var http = BuildStartInfo(profile, null, "rclone.exe")
            .GetAwaiter().GetResult();
        if (http.Environment[prefix + "URL"] !=
                "http://custom.storage.example:5006/" ||
            http.ArgumentList.Contains("--no-check-certificate"))
            throw new InvalidOperationException(
                "Custom WebDAV HTTP address/port must be used as entered");
        profile.WebDavUseHttp = false;
        var https = BuildStartInfo(profile, null, "rclone.exe")
            .GetAwaiter().GetResult();
        if (https.Environment[prefix + "URL"] !=
                "https://custom.storage.example:5006/" ||
            !https.ArgumentList.Contains("--no-check-certificate"))
            throw new InvalidOperationException(
                "Custom WebDAV HTTPS address/port must not be rewritten or blocked");
        Console.WriteLine("USER_CONFIGURED_WEBDAV_ENDPOINT_NO_BRAND_GUESS_PASS");
    }

    internal static Dictionary<string, string> FtpEnvironment(NasProfile p) => new()
    {
        ["EXPLICIT_TLS"] = p.ExplicitTls && !p.FtpImplicitTls ? "true" : "false",
        ["TLS"] = p.FtpImplicitTls ? "true" : "false",
        ["DISABLE_EPSV"] = p.FtpPreferPasv ? "true" : "false",
        ["DISABLE_UTF8"] = p.FtpDisableUtf8 ? "true" : "false",
        ["DISABLE_TLS13"] = p.FtpTls12Only ? "true" : "false"
    };

    internal static void SftpIsolationPolicySelfTest()
    {
        var p = new NasProfile { Id = Guid.NewGuid(), Protocol = "SFTP",
            Host = "test-sftp.example.invalid", Port = 62522,
            Letter = "Q", Username = "qa" };
        byte[] key = SftpHostKeyTrust.TestPublicKey("ssh-ed25519");
        string sha = "SHA256:" + Convert.ToBase64String(
            System.Security.Cryptography.SHA256.HashData(key)).TrimEnd('=');
        SftpHostKeyTrust.Approve(p, new SftpHostKeyTrust.Observation(
            p.Host, p.Port, "ssh-ed25519", Convert.ToBase64String(key), sha));
        string legacy = "RCLONE_SFTP_KNOWN_HOSTS_FILE";
        string named = "RCLONE_CONFIG_NM_" + p.Id.ToString("N").ToUpperInvariant() +
            "_KNOWN_HOSTS_FILE";
        string? oldGlobal = Environment.GetEnvironmentVariable(legacy);
        string? oldNamed = Environment.GetEnvironmentVariable(named);
        try
        {
            Environment.SetEnvironmentVariable(legacy, @"C:\legacy\known_hosts");
            Environment.SetEnvironmentVariable(named, @"C:\legacy\known_hosts");
            var psi = BuildStartInfo(p, null, "rclone.exe")
                .GetAwaiter().GetResult();
            if (psi.Environment.ContainsKey(legacy) ||
                psi.Environment.ContainsKey(named) ||
                psi.Environment["RCLONE_CONFIG_NM_" +
                    p.Id.ToString("N").ToUpperInvariant() + "_HOST_KEYS"] !=
                    p.SftpPinnedHostKey)
                throw new InvalidOperationException(
                    "SFTP inherited known_hosts was not isolated from profile pin");
            Console.WriteLine("SFTP_OLD_KNOWN_HOSTS_ENV_ISOLATED_PASS");
        }
        finally
        {
            Environment.SetEnvironmentVariable(legacy, oldGlobal);
            Environment.SetEnvironmentVariable(named, oldNamed);
        }
    }

    // Read-only preflight independent of WinFsp; checks network, credentials and folder listing.
    public static async Task<OperationResult> Diagnose(NasProfile p, string? password)
    {
        if (!TryValidate(p, out var reason)) return OperationResult.Fail(reason);
        if (CharsetSupport.RequiresGateway(p))
        {
            if (p.Protocol == "SFTP" && !SftpHostKeyTrust.HasTrustedKey(p))
                return OperationResult.Fail("SFTP 서버 호스트키를 승인한 후 재시도하세요.");
            try
            {
                using var gateway = await LegacyCharsetGateway.StartAsync(p, password);
                return OperationResult.Ok(p.Protocol + " " +
                    (p.Protocol == "FTP" ? p.FtpCharset : p.SftpCharset) +
                    " 코드페이지 변환·실제 폴더 목록 조회 성공.");
            }
            catch (Exception ex)
            { return OperationResult.Fail("문자셋 변환 연결 진단 실패: " + ex.Message); }
        }
        if (p.Protocol == "MyBox")
        {
            if (!MyBoxBetaGateway.Api.ValidToken(password))
                return OperationResult.Fail("MYBOX 개인 액세스 토큰(PAT)이 필요합니다.");
            try
            {
                using var gateway = await MyBoxBetaGateway.StartAsync(password!, p.AllowUnprotectedWrites);
                return OperationResult.Ok("MYBOX 토큰 인증 및 최상위 폴더 목록 성공. " +
                    (p.AllowUnprotectedWrites ? "쓰기 허용 모드입니다." : "읽기 전용입니다."));
            }
            catch (Exception e)
            {
                return OperationResult.Fail("MYBOX API 인증/목록 조회 실패: " + e.Message);
            }
        }
        if (p.Protocol == "SFTP" && !SftpHostKeyTrust.HasTrustedKey(p))
            return OperationResult.Fail(
                "SFTP(SSH) 서버 호스트 키가 아직 승인되지 않았습니다. " +
                "FileHub AIO에서 이 연결의 [연결] 버튼을 눌러 서버의 " +
                "SHA256 공개키 지문을 확인·승인한 후 다시 진단하세요.\n" +
                "인증되지 않은 SSH 서버에는 비밀번호를 전달하지 않습니다.");
        if (p.Protocol == "SMB") return OperationResult.Fail("SMB 연결은 Windows 공유 방식으로 검사하세요.");
        string? exe = BackendExecutable(p);
        if (exe is null) return OperationResult.Fail(p.Protocol == "TeraBox" ? "테라박스 백엔드가 없습니다. tools\\rclone-terabox.exe를 확인하세요." : "rclone.exe를 찾을 수 없습니다. tools 폴더를 확인하세요.");
        var binaryPreflight = await RcloneExecutableInspector.VerifyAsync(exe);
        if (!binaryPreflight.Success) return binaryPreflight;
        if (p.Protocol == "TeraBox" && !TeraBoxBackend.HasCookie(password))
            return OperationResult.Fail("테라박스 로그인 쿠키(ndus)가 없습니다. 연결 설정에서 쿠키를 등록하세요.");
        if (p.Protocol == "GoogleDrive" && !GoogleDriveAuth.IsAuthorized(p))
            return OperationResult.Fail("Google 계정 인증이 필요합니다. 연결을 눌러 브라우저에서 승인하세요.");
        if (p.Protocol == "OneDrive" && !OneDriveAuth.IsAuthorized(p))
            return OperationResult.Fail("Microsoft OneDrive 인증이 필요합니다. 연결을 눌러 브라우저에서 승인하세요.");
        int port = p.Port == 0 ? (p.Protocol == "WebDAV" ? 5006 : p.Protocol == "SFTP" ? 22 : 21) : p.Port;
        if (p.Protocol is not ("GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox"))
        using (var tcp = new TcpClient())
        using (var cts = new CancellationTokenSource(TimeSpan.FromSeconds(7)))
        {
            try { await tcp.ConnectAsync(p.Host, port, cts.Token); }
            catch (Exception ex)
            {
                return OperationResult.Fail($"서버 연결 실패: {p.Host}:{port}\n" +
                    "NAS 주소, VPN, 방화벽, 포트 설정을 확인하세요.\n" + ex.Message);
            }
        }
        if (p.Protocol == "FTP")
        {
            var actualService = await FtpServiceDetector.CheckAsync(p.Host, port);
            if (actualService is FtpServiceKind.HttpWebDav or FtpServiceKind.HttpOther)
                return OperationResult.Fail(FtpServiceDetector.Explain(actualService, p.Host, port));
        }
        try
        {
            int maxAttempts = GoogleDrivePreflightPolicy.AttemptCount(p.Protocol);
            for (int attempt = 1; attempt <= maxAttempts; attempt++)
            {
                if (p.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive")
                    AppStore.Log("CLOUD_BACKEND_READINESS_CHECK profile=" + p.Id +
                        " type=" + p.Protocol + " attempt=" + attempt + "/" + maxAttempts);
                var psi = await BuildStartInfo(p, password, exe);
                psi.ArgumentList.Add("lsf");
                psi.ArgumentList.Add(RemoteRoot(p));
                psi.ArgumentList.Add("--max-depth");
                psi.ArgumentList.Add("1");
                psi.ArgumentList.Add("--config");
                psi.ArgumentList.Add(p.Protocol switch {
                    "GoogleDrive" => GoogleDriveAuth.ConfigPath(p),
                    "OneDrive" => OneDriveAuth.ConfigPath(p),
                    _ => Path.Combine(AppStore.DirectoryPath, "no-global-rclone.conf")
                });
                psi.ArgumentList.Add("--log-level");
                psi.ArgumentList.Add("ERROR");
                using var process = new Process { StartInfo = psi };
                process.Start();
                TimeSpan limit = GoogleDrivePreflightPolicy.Timeout(p.Protocol, attempt);
                using var cts = new CancellationTokenSource(limit);
                var stdout = process.StandardOutput.ReadToEndAsync();
                var stderr = process.StandardError.ReadToEndAsync();
                bool timedOut = false;
                try { await process.WaitForExitAsync(cts.Token); }
                catch (OperationCanceledException)
                {
                    timedOut = true;
                    // This lsf is a temporary read-only check, NOT an active mount.
                    try { if (!process.HasExited) process.Kill(entireProcessTree: true); }
                    catch (InvalidOperationException) { /* Raced with normal exit. */ }
                    try { await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3)); }
                    catch (TimeoutException) { /* A locked-down process can resist shutdown. */ }
                }
                string output = await stdout;
                string error = await stderr;
                if (!timedOut && process.ExitCode == 0)
                    return OperationResult.Ok(
                        $"{p.Protocol} 서버 인증·폴더 목록 조회 성공.\n" +
                        (output.Length > 0 ? "첫 항목: " + output.Split('\n')[0].Trim() : "폴더 비어 있음"));
                bool retry = attempt < maxAttempts &&
                    GoogleDrivePreflightPolicy.IsTransient(p.Protocol, timedOut, error);
                if (retry)
                {
                    AppStore.Log($"CLOUD_BACKEND_NOT_READY profile={p.Id} protocol={p.Protocol} " +
                        $"attempt={attempt}/{maxAttempts} reason=" +
                        (timedOut ? $"timeout_{(int)limit.TotalSeconds}s" : "transient_status"));
                    await Task.Delay(GoogleDrivePreflightPolicy.RetryDelay(p.Protocol, attempt));
                    continue;
                }
                if (timedOut)
                    return OperationResult.Fail(p.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive"
                        ? $"{p.Protocol} 초기 서버 준비가 완료되지 않았습니다. " +
                          $"{maxAttempts}회 폴더 조회를 기다렸지만 마지막 시도가 {(int)limit.TotalSeconds}초를 초과했습니다. " +
                          "인증 정보는 유지되며, 네트워크와 로그를 확인하세요."
                        : $"서버 응답 시간이 초과됐습니다({(int)limit.TotalSeconds}초). 연결 설정과 네트워크를 확인하세요.");
                error = error.Length > 1100 ? error[^1100..] : error;
                if (p.Protocol == "WebDAV" &&
                    (error.Contains("401 Unauthorized", StringComparison.OrdinalIgnoreCase) ||
                     error.Contains("403 Forbidden", StringComparison.OrdinalIgnoreCase)))
                {
                    AppStore.Log("WEBDAV_REMOTE_AUTH_REJECTED profile=" + p.Id +
                        " status=" + (error.Contains("401") ? "401" : "403") +
                        " secret_logged=false");
                    return OperationResult.Fail(
                        "WebDAV 서버가 계정 인증을 거부했습니다 (HTTP " +
                        (error.Contains("401") ? "401" : "403") + ").\n" +
                        "Windows에 저장된 이 프로필의 사용자명/비밀번호와 " +
                        "NAS WebDAV 계정 권한을 확인하세요. " +
                        "서버가 인증을 거부한 상태에서는 드라이브를 연결된 것으로 " +
                        "처리하지 않습니다.\n" + error);
                }
                if (p.Protocol == "SFTP" &&
                    error.Contains("knownhosts: key is unknown",
                        StringComparison.OrdinalIgnoreCase))
                    return OperationResult.Fail(
                        "SFTP 서버 키 확인 실패: Windows/OpenSSH의 기존 known_hosts 설정을" +
                        " 사용하는 이전 FileHub AIO 또는 rclone 환경이 실행된 것으로 보입니다.\n" +
                        "현재 상단바 버전을 확인하고 v1.3.45 전체 ZIP에서 실행하세요.\n" +
                        "정상 버전은 최초 연결 시 서버 SHA256 지문 승인창을 표시합니다.\n" +
                        "실행 파일: " + Environment.ProcessPath + "\n" + error);
                return OperationResult.Fail($"NAS에 접속했지만 목록 조회 실패 (코드 {process.ExitCode}).\n" +
                    "계정, 공유 경로, 권한, TLS/SSH 키를 확인하세요.\n" + error);
            }
            return OperationResult.Fail("Google Drive 연결을 준비하지 못했습니다.");
        }
        catch (Exception ex) { return OperationResult.Fail("연결 진단 오류: " + ex.Message); }
    }

    public static async Task<OperationResult> Connect(NasProfile p, string? password)
    {
        if (EmergencyPending(p.Id))
            return OperationResult.Fail("사용자가 이 연결의 비상 중단을 요청했습니다. 다시 연결을 눌러 재시도하세요.");
        if (!TryValidate(p, out var reason)) return OperationResult.Fail(reason);
        if (IsRunning(p))
        {
            if (IsMounted(p)) return OperationResult.Ok("Already connected and browsable");
            // An existing rclone cache/bridge is kept alive, not duplicated.
            // Retry its Explorer health check rather than remounting it.
            return await VerifyExplorerAccess(p);
        }
        lock (Guard)
            if (Running.TryGetValue(p.Id, out var stale) && !stale.Process.HasExited)
                return OperationResult.Fail("이전 백엔드가 아직 실행 중입니다. 중복 실행을 중단했습니다.");
        // An earlier GUI crash may leave a healthy lock bridge owning this
        // profile's named pipe. Detect that before allocating any new rclone
        // mount. Do not terminate it: its backend may contain dirty uploads.
        if (p.UseLockBridge && p.Protocol != "SMB")
        {
            BridgeProbe existing = await BridgeOwnership.ProbeAsync(p.Id);
            if (existing.IsOccupied)
            {
                AppStore.Log("BRIDGE_OWNER_COLLISION " + p.Id + " " + existing.Detail);
                return OperationResult.Fail(BridgeOwnership.UserMessage(existing));
            }
        }
        if (Environment.GetLogicalDrives().Any(d =>
                d.StartsWith(p.Letter.ToUpperInvariant() + ":", StringComparison.OrdinalIgnoreCase)))
            return OperationResult.Fail(p.Letter + ": 드라이브가 다른 프로그램에서 사용 중입니다.");
        string? exe = BackendExecutable(p);
        if (exe == null)
            return OperationResult.Fail("rclone.exe가 없습니다. 프로그램 폴더의 tools\\rclone.exe에 배치하세요.");
        if (!HasWinFsp)
            return OperationResult.Fail(
                "WinFsp 드라이버가 설치되지 않았습니다.\n" +
                "FileHub AIO의 [WinFsp 설치] 버튼으로 공식 서명된 드라이버를 설치한 뒤 다시 연결하세요.");
        var preflightTimer = Stopwatch.StartNew();
        // Do not spawn a disposable rclone lsf, list the remote root, and
        // then launch ANOTHER rclone that repeats the same listing. The
        // actual mount has mandatory WinFsp root-enumeration verification.
        // Explicit Diagnostics still uses the full remote lsf preflight.
        var endpoint = FastConnectPolicy.RequiresFullPreflightForConnect(p)
            ? await Diagnose(p, password)
            : await FastConnectPolicy.ValidateLocalAsync(p, password, exe);
        AppStore.Log("CONNECT_FAST_LOCAL_PREFLIGHT_TIMING id=" + p.Id +
            " elapsedMs=" + preflightTimer.ElapsedMilliseconds +
            " ok=" + endpoint.Success +
            " duplicateRemoteLsf=false");
        if (!endpoint.Success) return endpoint;
        if (EmergencyPending(p.Id))
            return OperationResult.Fail("연결 취소됨: 비상 중단 요청");
        // Do not create rclone/WinFsp/LockBridge until the configured NAS
        // host can present a valid certificate for that EXACT hostname.
        // This prevents the "T:\\ I/O device error" from an otherwise
        // mountable but inaccessible Synology WebDAV HTTPS share.
        if (p.UseNativeWebDavLocks)
        {
            // Experimental RFC4918 mode must first prove actual server LOCK
            // enforcement and cleanup. Do not spawn WinFsp when unsupported.
            AppStore.Log("NATIVE_DAV_CAPABILITY_PREFLIGHT_BEGIN id=" + p.Id);
            var nativeCheck = await WebDavLockProbe.TestProfile(p, password ?? "");
            AppStore.Log("NATIVE_DAV_CAPABILITY_PREFLIGHT_RESULT id=" + p.Id +
                " supported=" + nativeCheck.Supported +
                " enforced=" + nativeCheck.Enforced +
                " cleanup=" + nativeCheck.CleanupSucceeded);
            if (!nativeCheck.Success)
                return OperationResult.Fail(
                    "서버 기본 잠금 모드를 사용할 수 없습니다.\n" +
                    nativeCheck.Detail + "\n\n" +
                    "연결을 시작하지 않았습니다. '서버 기본 잠금'을 끄고 " +
                    "일반 파일잠금 브리지로 연결하세요.");
        }
        string? bridgeExe = null;
        string? backend = null;
        if (p.UseLockBridge)
        {
            bridgeExe = Path.Combine(AppContext.BaseDirectory, "tools", "NASMountLockBridge.exe");
            if (!File.Exists(bridgeExe))
                return OperationResult.Fail("잠금 브리지 파일이 없습니다. 전체 배포 ZIP을 사용하세요.");
            if (!p.IsAnonymous && string.IsNullOrEmpty(password) && !p.RememberPassword)
                return OperationResult.Fail("잠금 브리지는 NAS 비밀번호가 필요합니다.");
            // A fixed WinFsp volume supports a non-existent directory under
            // an EXISTING parent. Do not create the leaf before mounting.
            // Private backends no longer occupy U:/T:/etc drive letters.
            // Existing old-version mounts are never modified or killed here.
            backend = MountPresentation.NewBackendMountpoint(p);

        }

        Process? process = null;
        MyBoxBetaGateway? myboxGateway = null;
        LegacyCharsetGateway? legacyGateway = null;
        NativeWebDavGateway? nativeGateway = null;
        try
        {
            if (p.Protocol == "MyBox")
                myboxGateway = await MyBoxBetaGateway.StartAsync(password ??
                    throw new InvalidOperationException("MYBOX PAT이 등록되지 않았습니다."),
                    p.AllowUnprotectedWrites);
            if (CharsetSupport.RequiresGateway(p))
                legacyGateway = await LegacyCharsetGateway.StartAsync(p, password);
            if (p.UseNativeWebDavLocks)
                nativeGateway = NativeWebDavGateway.Start(p, password);
            MountPresentation.BeginNetworkMountAttempt(p);
            var psi = await BuildStartInfo(p, password, exe, myboxGateway?.Url,
                legacyGateway?.Url, nativeGateway?.Url);
            using var portChooser = new TcpListener(IPAddress.Loopback, 0);
            portChooser.Start();
            int port = ((IPEndPoint)portChooser.LocalEndpoint).Port;
            portChooser.Stop();
            var user = "nm" + Random.Shared.Next(100000, 999999);
            var secret = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(24));
            var args = psi.ArgumentList;
            args.Add("mount");
            args.Add(RemoteRoot(p));
            args.Add(backend ?? p.Letter.ToUpperInvariant() + ":");
            // A network-mode mount is reported as \\server\NASMount-* by
            // Windows; Office may show "Connecting" to that UNC path.
            // An opt-in fixed-disk mount keeps X: but removes the artificial
            // network UNC. The actual WebDAV connection still uses the NAS.
            foreach (string presentationArg in WinFspPresentationArgs(p))
                args.Add(presentationArg);
            // Limit Google's initial API burst: repeated cold starts on this
            // PC have hit rateLimitExceeded and 10-36 second backoffs.
            // This applies only to Google, not other providers/WinFsp.
            var googlePacerArgs = new List<string>();
            GoogleMountPacingPolicy.AppendArgs(googlePacerArgs, p);
            foreach (string argument in googlePacerArgs)
                args.Add(argument);
            if (!p.AllowUnprotectedWrites && !p.UseLockBridge)
                args.Add("--read-only");
            // Read-only fast mode trades cross-client metadata freshness and
            // persistent local read caching for fewer metadata requests and
            // direct reads. Never enable it for writable/lock-bridge volumes.
            bool fastRead = p.FastReadOnly && !p.AllowUnprotectedWrites && !p.UseLockBridge;
            bool officeRead = p.Protocol == "WebDAV" && p.OfficeReadTuning &&
                !fastRead && !p.UseLockBridge;
            args.Add("--vfs-cache-mode");
            args.Add(fastRead ? "writes" : "full");
            args.Add("--vfs-write-back");
            // Office creates a temp file, closes it and renames it into place.
            // Starting the VFS upload immediately at close can race the rename.
            bool officeWriteSafety = (p.Protocol == "WebDAV" &&
                (p.AllowUnprotectedWrites || p.UseLockBridge)) ||
                (p.Protocol is "FTP" or "SFTP" && p.UseLockBridge);
            // Excel writes temp files and renames them into place. Applying
            // the same deferred-upload safety gate to FTP/SFTP lock bridge
            // prevents the VFS from uploading an incomplete temp immediately
            // before Office's final rename. Upload completion is still
            // verified before the cooperative lease is released.
            args.Add(officeWriteSafety ? "15s" : "0s");
            if (officeWriteSafety)
            {
                args.Add("--vfs-handle-caching");
                args.Add("0s");
                args.Add("--transfers");
                args.Add("1");
            }
            string directoryCacheTime = MountResponsivenessPolicy.DirectoryCacheTime(
                p.Protocol, fastRead, officeRead);
            args.Add("--dir-cache-time");
            args.Add(directoryCacheTime);
            args.Add("--poll-interval");
            args.Add("0");
            args.Add("--cache-dir");
            args.Add(Path.Combine(AppStore.DirectoryPath, "cache", p.Id.ToString("N")));
            if (fastRead)
            {
                // Documented VFS behavior: in 'writes' mode, files opened for
                // reading do not go through rclone's on-disk full VFS cache.
                // Keep buffer modest; high buffers per-file multiply RAM use.
                args.Add("--buffer-size");
                args.Add("16Mi");
            }
            if (officeRead)
            {
                // XLSX is a ZIP file: Excel can seek between data and its
                // central directory. Bound remote range requests and issue
                // a modest local read-ahead, preserving full VFS write cache.
                args.Add("--buffer-size");
                args.Add("8Mi");
                args.Add("--vfs-read-ahead");
                args.Add("8Mi");
                args.Add("--vfs-read-chunk-size");
                args.Add("8Mi");
                args.Add("--vfs-read-chunk-size-limit");
                args.Add("32Mi");
                args.Add("--vfs-cache-max-age");
                args.Add("6h");
            }
            args.Add("--rc");
            args.Add("--rc-addr");
            args.Add("127.0.0.1:" + port);
            args.Add("--rc-user"); args.Add(user);
            // NOTE: a random local RC password is visible to local OS process inspection.
            args.Add("--rc-pass"); args.Add(secret);
            bool googleTraceEnabled = p.Protocol == "GoogleDrive" &&
                GoogleStartupTrace.Enabled;
            args.Add("--log-level");
            args.Add(googleTraceEnabled ? "DEBUG" :
                officeWriteSafety ? "INFO" : "ERROR");
            // Isolate from global rclone config.
            args.Add("--config"); args.Add(p.Protocol switch {
                "GoogleDrive" => GoogleDriveAuth.ConfigPath(p),
                "OneDrive" => OneDriveAuth.ConfigPath(p),
                _ => Path.Combine(AppStore.DirectoryPath, "no-global-rclone.conf")
            });
            // Only log effective safe settings, never the remote password,
            // credential environment, or local RC authentication token.
            AppStore.Log(
                "MOUNT_SETTINGS profile=" + p.Id +
                " protocol=" + p.Protocol +
                " presentation=" + (p.UseLockBridge ? "bridge" :
                    UsesNetworkDriveMode(p) ? "network-unc" : "fixed-disk") +
                " vfs-cache=" + (fastRead ? "writes" : "full") +
                " dir-cache=" + directoryCacheTime +
                " office-read-tuning=" + officeRead +
                " write-back=" + (officeWriteSafety ? "15s" : "0s") +
                " readonly=" + (!p.AllowUnprotectedWrites && !p.UseLockBridge));
            if (p.Protocol == "GoogleDrive")
                AppStore.Log("GOOGLE_MOUNT_API_PACING minSleep=" +
                    GoogleMountPacingPolicy.MinSleep +
                    " burst=" + GoogleMountPacingPolicy.Burst +
                    " enginePersistent=false rootBrowseRequired=true");
            if (p.Protocol == "GoogleDrive")
                AppStore.Log("GOOGLE_STORED_ACCESS_TOKEN_BEFORE_START expired=" +
                    (GoogleDriveAuth.IsStoredAccessTokenExpired(p)?.ToString() ?? "unknown"));
            process = new Process { StartInfo = psi, EnableRaisingEvents = true };
            var logId = p.Id;
            var startupErrors = new ConcurrentQueue<string>();
            var googleTrace = googleTraceEnabled ? new GoogleStartupTrace(p.Id) : null;
            static void RememberError(ConcurrentQueue<string> errors, string line)
            {
                if (!line.Contains("error", StringComparison.OrdinalIgnoreCase) &&
                    !line.Contains("fatal", StringComparison.OrdinalIgnoreCase) &&
                    !line.Contains("failed", StringComparison.OrdinalIgnoreCase) &&
                    !line.Contains("exception", StringComparison.OrdinalIgnoreCase)) return;
                if (line.Contains("password=", StringComparison.OrdinalIgnoreCase) ||
                    line.Contains("rc-pass", StringComparison.OrdinalIgnoreCase) ||
                    line.Contains("secret=", StringComparison.OrdinalIgnoreCase)) return;
                errors.Enqueue(line.Length > 250 ? line[..250] : line);
                while (errors.Count > 4) errors.TryDequeue(out _);
            }
            IntegrityAlerts.TryRemove(p.Id, out _);
            process.ErrorDataReceived += (_, eventArgs) =>
            {
                if (!string.IsNullOrEmpty(eventArgs.Data))
                {
                    string? critical = RcloneIntegrityMonitor.Classify(eventArgs.Data);
                    if (critical is not null)
                        IntegrityAlerts.TryAdd(logId, critical);
                    if (googleTrace is not null)
                    {
                        googleTrace.Observe(eventArgs.Data);
                        // DEBUG streams can reveal token material and remote
                        // names. Persist only fixed, safe category labels.
                        if (eventArgs.Data.Contains("error",
                                StringComparison.OrdinalIgnoreCase) ||
                            eventArgs.Data.Contains("fatal",
                                StringComparison.OrdinalIgnoreCase))
                            RememberError(startupErrors,
                                "Google backend reported an error (see sanitized diagnostic)");
                    }
                    else
                    {
                        RememberError(startupErrors, eventArgs.Data);
                        AppStore.Log("RCLONE " + logId + " " + eventArgs.Data);
                    }
                }
            };
            if (EmergencyPending(p.Id))
            {
                myboxGateway?.Dispose();
                legacyGateway?.Dispose();
                nativeGateway?.Dispose();
                return OperationResult.Fail("연결 취소됨: 비상 중단 요청");
            }
            process.Start();
            googleTrace?.Milestone("process_started");
            process.BeginErrorReadLine();
            // stdout is drained to prevent deadlock.
            process.OutputDataReceived += (_, _) => { };
            process.BeginOutputReadLine();
            lock (Guard)
            {
                EverBrowseVerified.Remove(p.Id);
                BrowseVerified.Remove(p.Id);
                Running[p.Id] = new Instance(process, port, user, secret, null,
                    myboxGateway, legacyGateway, nativeGateway);
            }
            // The first OAuth cloud mount can take >20s to initialize on a
            // cold Windows session (observed: Google Drive started after
            // 35 seconds even though the prior 20s check already failed).
            // Wait for an actual mount and verified Explorer root; never
            // claim success because the child process merely exists.
            int maxWaitSteps = (int)Math.Ceiling(
                StartupReadinessPolicy.MountWait(p.Protocol).TotalMilliseconds / 250d);
            var mountProbe = new MountRootProbe(p.Letter.ToUpperInvariant() + @":\");
            var backendProbe = backend is null ? null : new MountRootProbe(backend);
            for (int i = 0; i < maxWaitSteps; i++)
            {
                if (EmergencyPending(p.Id))
                    return OperationResult.Fail("마운트 시작 취소됨: 비상 중단 요청");
                if (process.HasExited)
                {
                    myboxGateway?.Dispose();
                    legacyGateway?.Dispose();
                    nativeGateway?.Dispose();
                    lock (Guard) Running.Remove(p.Id);
                    return OperationResult.Fail("rclone 종료 코드 " + process.ExitCode + ".\n" +
                        (startupErrors.IsEmpty ? "추가 오류 메시지는 로그 폴더에서 확인하세요." :
                            string.Join("\n", startupErrors.ToArray())));
                }
                if (!p.UseLockBridge && await mountProbe.CheckAsync())
                {
                    googleTrace?.Milestone("drive_letter_ready");
                    var verified = await VerifiedThenOk(p,
                        $"{p.Protocol} 드라이브 연결: {p.Letter}: " +
                        (!UsesNetworkDriveMode(p) ? "[고정 디스크 표시] " : "[네트워크 드라이브 표시] ") +
                        (p.AllowUnprotectedWrites
                            ? "쓰기 테스트 모드 (다중 사용자 파일 잠금 미지원)"
                            : (fastRead
                                ? "빠른 읽기 모드 (폴더 변경 반영 최대 약 10초 지연)"
                                : "읽기 전용 호환 모드")));
                    if (p.Protocol == "GoogleDrive")
                        AppStore.Log("GOOGLE_STORED_ACCESS_TOKEN_AFTER_CONNECT expired=" +
                            (GoogleDriveAuth.IsStoredAccessTokenExpired(p)?.ToString() ?? "unknown") +
                            " rootVerified=" + verified.Success);
                    return verified;
                }
                if (p.UseLockBridge && backend is not null && bridgeExe is not null)
                {
                    bool vfsReady = false;
                    try
                    {
                        using var client = new HttpClient { Timeout = TimeSpan.FromMilliseconds(350) };
                        string basic = Convert.ToBase64String(Encoding.UTF8.GetBytes(user+":"+secret));
                        client.DefaultRequestHeaders.Authorization =
                            new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",basic);
                        using var response = await client.PostAsync(
                            $"http://127.0.0.1:{port}/vfs/stats", new StringContent("{}"));
                        vfsReady = response.IsSuccessStatusCode;
                    }
                    catch { }
                    if (vfsReady && backendProbe is not null &&
                        await backendProbe.CheckAsync())
                    {
                        var bi = new ProcessStartInfo(bridgeExe)
                        {
                            UseShellExecute = false, CreateNoWindow = true,
                            RedirectStandardError = true, RedirectStandardOutput = true
                        };
                        foreach(string argument in new[] { "--profile",p.Id.ToString("D"),
                            "--source",backend,"--target",p.Letter.ToUpperInvariant()+":" })
                            bi.ArgumentList.Add(argument);
                        bi.Environment["NASMOUNT_BRIDGE_PASSWORD"] =
                            password ?? WindowsCredentials.Read(p.Id) ?? "";
                        bi.Environment["NASMOUNT_RC_PORT"] = port.ToString();
                        bi.Environment["NASMOUNT_RC_USER"] = user;
                        bi.Environment["NASMOUNT_RC_PASS"] = secret;
                        if (nativeGateway is not null)
                        {
                            bi.Environment["NASMOUNT_NATIVE_LOCK_CONTROL_URL"] =
                                nativeGateway.ControlUrl;
                            bi.Environment["NASMOUNT_NATIVE_LOCK_CONTROL_SECRET"] =
                                nativeGateway.ControlSecret;
                        }
                        var bridge = new Process { StartInfo = bi, EnableRaisingEvents = true };
                        var bridgeErrors = new ConcurrentQueue<string>();
                        bridge.Start();
                        bridge.OutputDataReceived += (_, e) => {
                            if (e.Data is not null) AppStore.Log("LOCKBRIDGE " + e.Data);
                        };
                        bridge.ErrorDataReceived += (_, e) => {
                            if (e.Data is not null)
                            {
                                AppStore.Log("LOCKBRIDGE ERR " + e.Data);
                                RememberError(bridgeErrors, e.Data);
                            }
                        };
                        bridge.BeginOutputReadLine(); bridge.BeginErrorReadLine();
                        lock (Guard) Running[p.Id] = new Instance(process,port,user,secret,bridge,
                            myboxGateway, legacyGateway, nativeGateway);
                        for (int n = 0; n < 48; n++)
                        {
                            if (bridge.HasExited)
                            {
                                await AbortEmptyBackend(process,port,user,secret,p.Id);
                                return OperationResult.Fail("파일 잠금 브리지 시작 실패. 코드=" +
                                    bridge.ExitCode + "\n" +
                                    (bridgeErrors.IsEmpty ? "추가 원인은 FileHub AIO 로그를 확인하세요." :
                                        string.Join("\n", bridgeErrors.ToArray())));
                            }
                            if (EmergencyPending(p.Id))
                                return OperationResult.Fail("잠금 브리지 시작 취소됨: 비상 중단 요청");
                            if (await mountProbe.CheckAsync())
                                return await VerifiedThenOk(p, 
                                    $"{p.Letter}: 잠금 브리지 마운트 시작. 여러 PC 실기 검증이 필요합니다.");
                            await Task.Delay(250);
                        }
                        return OperationResult.Fail("잠금 브리지 준비시간 초과. 열린 핸들이 있을 수 있어 " +
                            "백엔드를 강제로 종료하지 않았습니다. 로그를 확인하세요.");
                    }
                }
                await Task.Delay(250);
            }
            AppStore.Log("MOUNT_STARTUP_TIMEOUT_BACKEND_PRESERVED id=" + p.Id +
                " protocol=" + p.Protocol + " waitSeconds=" +
                StartupReadinessPolicy.MountWait(p.Protocol).TotalSeconds +
                " backendAlive=" + (process is not null && !process.HasExited));
            return OperationResult.Fail("마운트 준비가 지연되고 있습니다 (" +
                (int)StartupReadinessPolicy.MountWait(p.Protocol).TotalSeconds +
                "초). 실행 중인 백엔드는 강제로 종료하지 않았습니다. " +
                "자동 재확인을 진행합니다. 계속되면 로그를 확인하세요.");
        }
        catch (Exception ex)
        {
            if (process is not null && !process.HasExited)
                AppStore.Log("RCLONE start exception; process may still be alive: " + process.Id);
            else
            {
                myboxGateway?.Dispose();
                legacyGateway?.Dispose();
                nativeGateway?.Dispose();
            }
            return OperationResult.Fail("마운트 오류: " + ex.Message);
        }
    }

    private static async Task AbortEmptyBackend(Process process,int port,
        string user,string secret,Guid id)
    {
        void ReleaseCompletedBackend()
        {
            Instance? completed;
            lock (Guard)
            {
                Running.TryGetValue(id, out completed);
                Running.Remove(id);
            }
            completed?.MyBoxGateway?.Dispose();
            completed?.LegacyGateway?.Dispose();
            completed?.NativeGateway?.Dispose();
        }
        if (process.HasExited)
        {
            ReleaseCompletedBackend();
            return;
        }
        try
        {
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(5) };
            client.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Basic",
                    Convert.ToBase64String(Encoding.UTF8.GetBytes(user+":"+secret)));
            using var response = await client.PostAsync(
                $"http://127.0.0.1:{port}/core/quit",new StringContent("{}"));
            response.EnsureSuccessStatusCode();
            await process.WaitForExitAsync(
                new CancellationTokenSource(TimeSpan.FromSeconds(8)).Token);
            ReleaseCompletedBackend();
        }
        catch(Exception ex)
        {
            AppStore.Log("BACKEND STARTUP CLEANUP FAILED "+id+" "+ex.Message);
        }
    }

    public static async Task<OperationResult> Disconnect(NasProfile p)
    {
        if (EmergencyPending(p.Id))
            return OperationResult.Fail("비상 중단이 요청됐으므로 일반 안전 해제를 취소합니다.");
        var stopTimer = Stopwatch.StartNew();
        void StopPhase(string phase) =>
            AppStore.Log("SAFE_DISCONNECT_PHASE profile=" + p.Id +
                " phase=" + phase + " elapsedMs=" + stopTimer.ElapsedMilliseconds);
        Instance? instance;
        lock (Guard) Running.TryGetValue(p.Id, out instance);
        if (instance is null)
            return OperationResult.Fail(
                "이 프로그램이 시작한 마운트 기록이 없습니다. 이전 프로그램의 드라이브나" +
                " 잠금 브리지를 강제로 제거하지 않습니다. 실행 중인 이전 버전과 트레이 아이콘을 확인하세요.");
        if (instance.Process.HasExited)
        {
            if (instance.Bridge is not null && !instance.Bridge.HasExited)
                return OperationResult.Fail(
                    "rclone은 종료됐지만 WinFsp 파일잠금 브리지가 아직 실행 중입니다." +
                    " 열린 파일/잠금이 남아 있을 수 있어 자동 강제 종료하지 않습니다.");
            var stale = NetworkDriveResidueCleaner.AfterSafeStop(p);
            if (!stale.Success) return stale;
            if (!await WaitForLetterRelease(p.Letter))
                return OperationResult.Fail(
                    p.Letter + ": 드라이브 문자가 Windows에 남아 있습니다." +
                    " 다른 프로그램이 차지했거나 WinFsp 연결 정리가 끝나지 않았습니다." +
                    " 안전 확인 전 종료 성공으로 처리하지 않습니다.");
            lock (Guard) { Running.Remove(p.Id); BrowseVerified.Remove(p.Id); }
            instance.MyBoxGateway?.Dispose();
            instance.LegacyGateway?.Dispose();
            instance.NativeGateway?.Dispose();
            return OperationResult.Ok("종료된 마운트의 잔여 실행 기록과 드라이브 해제를 확인했습니다.");
        }
        if (p.UseLockBridge && instance.Bridge is not null && instance.Bridge.HasExited)
            return OperationResult.Fail(
                "잠금 브리지가 비정상 종료됐습니다. rclone 백엔드와 업로드 대기 파일을 자동 종료하지 않습니다.\n" +
                "로그와 캐시를 확인한 뒤 수동 복구가 필요합니다.");
        if (GetIntegrityAlert(p.Id) is { } critical)
            return OperationResult.Fail(
                critical + "\n데이터 안전 확인 전 자동 연결 해제를 중단했습니다. " +
                "원본·TMP·VFS 캐시를 보존하고 로그를 확인하세요.");
        try
        {
            // Excel can close before the VFS has finished uploading. Keep
            // dirty data mounted until uploads and retry queues are empty.
            UploadState? last = null;
            for (int attempt = 0; attempt < 24; attempt++)
            {
                if (EmergencyPending(p.Id))
                    return OperationResult.Fail("비상 중단 요청으로 업로드 확인 대기를 취소했습니다.");
                last = await ReadUploadState(instance, attempts: 2);
                if (last.IsSafeToDisconnect) break;
                if (last.Errors > 0 || last.OutOfSpace)
                    return OperationResult.Fail("전송 오류로 안전한 연결 해제를 중단했습니다.\n" +
                        last.Summary + "\n캐시 파일을 삭제하지 말고 로그를 확인하세요.");
                AppStore.Log("WAIT_UPLOAD " + p.Id + " " + last.Summary);
                await Task.Delay(TimeSpan.FromSeconds(3));
            }
            if (last is null || !last.IsSafeToDisconnect)
                return OperationResult.Fail("NAS 업로드가 아직 완료되지 않아 연결을 유지합니다.\n" +
                    (last?.Summary ?? "") + "\n잠시 후 다시 시도하세요.");
            StopPhase("uploads_confirmed");
            if (EmergencyPending(p.Id))
                return OperationResult.Fail("비상 중단 요청으로 안전 해제를 취소했습니다.");
            if (instance.Bridge is not null && !instance.Bridge.HasExited)
            {
                using var pipe = new NamedPipeClientStream(".", "NASMountBridge_" +
                    p.Id.ToString("N"), PipeDirection.InOut);
                await pipe.ConnectAsync(5000);
                using var reader = new StreamReader(pipe, leaveOpen:true);
                using var writer = new StreamWriter(pipe, leaveOpen:true) { AutoFlush=true };
                await writer.WriteLineAsync("QUIT");
                string reply = await reader.ReadLineAsync() ?? "";
                if (reply != "OK")
                    return OperationResult.Fail("열린 문서 또는 업로드 대기 잠금이 있습니다. "+reply);
                await instance.Bridge.WaitForExitAsync(
                    new CancellationTokenSource(TimeSpan.FromSeconds(10)).Token);
                // The bridge has now exited through its own verified QUIT
                // path, not via a crash. If rclone core/quit fails below,
                // a subsequent safe exit must be allowed to retry rather
                // than mislabeling this intentional bridge exit as a crash.
                lock (Guard)
                {
                    if (Running.TryGetValue(p.Id, out var current) &&
                        ReferenceEquals(current.Process, instance.Process))
                    {
                        instance = instance with { Bridge = null };
                        Running[p.Id] = instance;
                    }
                }
                AppStore.Log("BRIDGE_GRACEFUL_QUIT_CONFIRMED " + p.Id);
                StopPhase("bridge_quit_confirmed");
            }
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(10) };
            var token = Convert.ToBase64String(Encoding.UTF8.GetBytes(instance.RcUser + ":" + instance.RcPassword));
            client.DefaultRequestHeaders.Authorization =
                new System.Net.Http.Headers.AuthenticationHeaderValue("Basic", token);
            using var content = new StringContent("{}");
            using var response = await client.PostAsync($"http://127.0.0.1:{instance.RcPort}/core/quit", content);
            response.EnsureSuccessStatusCode();
            await instance.Process.WaitForExitAsync(new CancellationTokenSource(TimeSpan.FromSeconds(12)).Token);
            StopPhase("rclone_graceful_exit_confirmed");
            // A dead rclone leaves Windows network-provider remembered maps
            // and MountPoints2 shells even when the drive is no longer
            // returned by GetLogicalDrives(). Do not make the old mistake of
            // skipping WNet CONNECT_UPDATE_PROFILE in that state.
            var cleanup = NetworkDriveResidueCleaner.AfterSafeStop(p);
            StopPhase("network_provider_history_cleanup");
            if (!cleanup.Success) return cleanup;
            if (!await WaitForLetterRelease(p.Letter))
            {
                AppStore.Log("DRIVE_RELEASE_NOT_CONFIRMED " + p.Id + " letter=" + p.Letter);
                return OperationResult.Fail(
                    p.Letter + ": 드라이브가 Windows 탐색기/WinFsp에서 아직 해제되지 않았습니다." +
                    "\nrclone과 잠금 브리지는 정상 종료됐지만 드라이브 문자 해제가 확인되지 않아" +
                    " 프로그램을 닫지 않습니다. 열린 탐색기 창을 닫고 다시 확인하세요.");
            }
            lock (Guard) { Running.Remove(p.Id); BrowseVerified.Remove(p.Id); }
            instance.MyBoxGateway?.Dispose();
            instance.LegacyGateway?.Dispose();
            instance.NativeGateway?.Dispose();
            StopPhase("drive_letter_released");
            return OperationResult.Ok("마운트와 WinFsp 드라이브 문자 해제 확인 완료.");
        }
        catch (Exception e)
        {
            return OperationResult.Fail("안전한 마운트 종료 실패. 강제 종료하지 않았습니다: " + e.Message);
        }
    }

    /// <summary>
    /// Explicit user emergency action only. It can lose unflushed VFS data.
    /// Stop ONLY processes started by this in-memory FileHub instance.
    /// Never target a named PID from another app, RaiDrive, or stale logs.
    /// </summary>
    internal static async Task<OperationResult> EmergencyStopOwnedAsync(NasProfile p)
    {
        RequestEmergency(p.Id);
        Instance? instance;
        lock (Guard) Running.TryGetValue(p.Id, out instance);
        if (instance is null)
            return OperationResult.Fail(
                "현재 FileHub 실행이 소유한 백엔드가 없습니다. " +
                "다른 FileHub 또는 RaiDrive 프로세스는 변경하지 않았습니다.");

        static async Task<bool> StopOwnedProcessAsync(Process process,
            Guid id, string kind)
        {
            try
            {
                if (process.HasExited) return true;
                // WinFsp callbacks can stall even Process.Kill(). Never
                // block the UI and always bound the wait.
                await Task.Run(() =>
                {
                    if (!process.HasExited)
                        process.Kill(entireProcessTree: false);
                }).WaitAsync(TimeSpan.FromSeconds(3));
                await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(3));
                return process.HasExited;
            }
            catch (Exception ex)
            {
                AppStore.Log("EMERGENCY_STOP_INCOMPLETE id=" + id +
                    " kind=" + kind + " error=" + ex.GetType().Name);
                return false;
            }
        }

        AppStore.Log("EMERGENCY_STOP id=" + p.Id +
            " owned_children_only=true cache_preserved=true");
        bool bridgeStopped = instance.Bridge is null ||
            await StopOwnedProcessAsync(instance.Bridge, p.Id, "bridge");
        bool backendStopped = await StopOwnedProcessAsync(instance.Process, p.Id, "rclone");
        if (!bridgeStopped || !backendStopped)
            return OperationResult.Fail(
                "일부 프로세스 종료를 확인하지 못했습니다. 캐시와 서버 파일은 " +
                "삭제하지 않았고 RaiDrive에도 접근하지 않았습니다.\n" +
                "Windows 작업 관리자에서 해당 FileHub 프로세스 상태를 확인하세요.");
        lock (Guard)
        {
            if (Running.TryGetValue(p.Id, out var current) &&
                ReferenceEquals(current.Process, instance.Process))
            {
                Running.Remove(p.Id);
                BrowseVerified.Remove(p.Id);
            }
        }
        // Do not UNLOCK a remote file before verifying a completed upload.
        // The server may retain a lease until expiry. Preserve all cache data.
        try { instance.NativeGateway?.Dispose(); }
        catch (Exception e) { AppStore.Log("EMERGENCY_GATEWAY " + e.GetType().Name); }
        try { instance.LegacyGateway?.Dispose(); }
        catch (Exception e) { AppStore.Log("EMERGENCY_LEGACY " + e.GetType().Name); }
        try { instance.MyBoxGateway?.Dispose(); }
        catch (Exception e) { AppStore.Log("EMERGENCY_MYBOX " + e.GetType().Name); }
        return OperationResult.Ok(
            "FileHub 소유의 잠금 브리지와 rclone 프로세스를 중단했습니다.\n" +
            "미전송 파일은 유실될 수 있으며 VFS 캐시·로그는 삭제하지 않았습니다.\n" +
            "WinFsp 드라이브 문자가 남으면 재부팅 전에 파일 상태를 확인하세요.");
    }
}
