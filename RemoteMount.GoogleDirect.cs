using System.Diagnostics;
using System.IO.Pipes;
using System.Text;

namespace NASMount;

internal static partial class RemoteMount
{
    internal enum GoogleEngineRoute
    {
        OtherProtocol,
        NativeGoogleNeedsBrowserGrant,
        NativeGoogleReady
    }

    // Google is NOT permitted to choose a legacy rclone backend. The route
    // is decided before looking up any executable or VFS arguments.
    internal static GoogleEngineRoute GoogleRouteFor(NasProfile p) =>
        p.Protocol != "GoogleDrive" ? GoogleEngineRoute.OtherProtocol :
        GoogleDirectCredentialStore.HasNative(p) ?
            GoogleEngineRoute.NativeGoogleReady :
            GoogleEngineRoute.NativeGoogleNeedsBrowserGrant;

    internal static void DirectOnlyRoutingPolicyTest()
    {
        var google = new NasProfile
        {
            Id = Guid.NewGuid(), Protocol = "GoogleDrive"
        };
        var webdav = new NasProfile
        {
            Id = Guid.NewGuid(), Protocol = "WebDAV"
        };
        if (GoogleRouteFor(google) !=
                GoogleEngineRoute.NativeGoogleNeedsBrowserGrant ||
            GoogleRouteFor(webdav) != GoogleEngineRoute.OtherProtocol)
            throw new InvalidOperationException("Google mount silently fell back to rclone");
        Console.WriteLine("GOOGLE_NATIVE_ONLY_NO_RCLONE_FALLBACK_POLICY_PASS");
    }

    private static string? PersistedGoogleRecoveryAlert(Guid id)
    {
        try
        {
            // A running WinFsp writer normally has a local .json stage.
            // Do not confuse ordinary in-progress writes with a crash
            // recovery warning on the quiet dashboard.
            lock (Guard)
                if (Running.TryGetValue(id, out var active) &&
                    active.DirectGoogle && !active.Process.HasExited)
                    return null;
            int pending = GoogleDirectRecovery.PendingCount(id);
            return pending == 0 ? null :
                "Google 직접 연결에 복구가 필요한 로컬 저장 기록 " +
                pending + "개가 남아 있습니다. 프로그램 데이터의 " +
                "google-direct-stages 폴더를 보관하고 확인하세요. " +
                "임의로 삭제하지 마세요.";
        }
        catch (IOException) { return null; }
        catch (UnauthorizedAccessException) { return null; }
    }

    // Enable direct WinFsp ONLY after the user completes FileHub's native
    // browser OAuth flow and has its DPAPI credential store. A historical
    // custom-client rclone .conf is NOT an opt-in to the native mount.
    // It must not silently switch an existing profile with working Office
    // writes to a new backend with different semantics.
    private static bool UsesDirectGoogle(NasProfile p) =>
        p.Protocol == "GoogleDrive" &&
        GoogleDirectCredentialStore.HasNative(p);

    private static async Task<string> DirectGoogleCommandAsync(Guid id, string command,
        CancellationToken cancellationToken = default)
    {
        using var pipe = new NamedPipeClientStream(".",
            "NASMountDirectGooglePilot_" + id.ToString("N"), PipeDirection.InOut,
            PipeOptions.Asynchronous);
        await pipe.ConnectAsync(3500, cancellationToken);
        using var reader = new StreamReader(pipe, leaveOpen: true);
        using var writer = new StreamWriter(pipe, leaveOpen: true) { AutoFlush = true };
        await writer.WriteLineAsync(command);
        return await reader.ReadLineAsync(cancellationToken) ?? "";
    }

    private static async Task<OperationResult> ConnectDirectGoogleAsync(NasProfile p)
    {
        if (!HasWinFsp)
            return OperationResult.Fail("Google 직접 연결에는 WinFsp 설치가 필요합니다.");
        if (!UsesDirectGoogle(p))
            return OperationResult.Fail(
                "Google 전용 인증이 없습니다. Google 계정을 다시 승인해 주세요.");
        string bridge = Path.Combine(AppContext.BaseDirectory,
            "tools", "NASMountLockBridge.exe");
        if (!File.Exists(bridge))
            return OperationResult.Fail("Google 직접 WinFsp 엔진이 설치되지 않았습니다.");
        // The UI process owns the session name. Pass the exact prefix to
        // the child; otherwise its separate MountPresentation static state
        // would generate a different server and Explorer labels would drift.
        MountPresentation.BeginNetworkMountAttempt(p);
        string prefix = MountPresentation.WinFspPrefix(p);
        var psi = new ProcessStartInfo(bridge)
        {
            UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true
        };
        foreach (string argument in new[]
        {
            "--direct-google-profile-folder-pilot", p.Id.ToString("D"),
            p.Letter.ToUpperInvariant() + ":", prefix
        }) psi.ArgumentList.Add(argument);

        var process = new Process { StartInfo = psi, EnableRaisingEvents = true };
        bool started = false;
        var ready = new TaskCompletionSource<bool>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        try
        {
            if (!process.Start())
            {
                MountPresentation.ForgetNetworkMountAttempt(p);
                return OperationResult.Fail("Google 직접 WinFsp 엔진 실행에 실패했습니다.");
            }
            started = true;
            process.OutputDataReceived += (_, e) =>
            {
                if (e.Data?.StartsWith("DIRECT_GOOGLE_WINFSP_PILOT_READY ",
                    StringComparison.Ordinal) == true)
                    ready.TrySetResult(true);
            };
            process.ErrorDataReceived += (_, e) =>
            {
                if (e.Data is null) return;
                // No filenames, Drive IDs, OAuth secrets or API URLs in logs.
                string operation = System.Text.RegularExpressions.Regex.Match(
                    e.Data, @"\boperation=([A-Za-z]+)\b").Groups[1].Value;
                string category = System.Text.RegularExpressions.Regex.Match(
                    e.Data, @"\bcategory=([A-Za-z0-9_]+)\b").Groups[1].Value;
                if (category.Length > 0)
                {
                    AppStore.Log("DIRECT_GOOGLE_ERROR profile=" + p.Id +
                        " op=" + operation + " category=" + category);
                    IntegrityAlerts[p.Id] = "Google 직접 연결의 파일 처리 오류가 발생했습니다. " +
                        "로컬 복구 파일이 남아 있을 수 있으므로 확인해 주세요.";
                }
            };
            process.BeginOutputReadLine();
            process.BeginErrorReadLine();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(18));
            try
            {
                while (!ready.Task.IsCompleted && !timeout.IsCancellationRequested)
                {
                    if (process.HasExited || EmergencyPending(p.Id)) break;
                    await Task.Delay(120, timeout.Token);
                }
            }
            catch (OperationCanceledException) { }
            if (!ready.Task.IsCompleted)
            {
                if (!process.HasExited)
                {
                    // Keep the running backend if WinFsp mounted late;
                    // never launch a second writer on a retry.
                    lock (Guard)
                        Running[p.Id] = new Instance(process, 0, "", "", null,
                            DirectProfileId: p.Id, DirectGoogle: true);
                    return OperationResult.Fail(
                        "Google 직접 엔진 준비가 18초를 초과했습니다. " +
                        "중복 연결을 방지하기 위해 기존 프로세스를 유지합니다.");
                }
                int exit = process.ExitCode;
                process.Dispose();
                MountPresentation.ForgetNetworkMountAttempt(p);
                return OperationResult.Fail("Google 직접 엔진 시작 실패 (" +
                    exit + "). Google 인증·API 응답을 확인하세요.");
            }
            lock (Guard)
                Running[p.Id] = new Instance(process, 0, "", "", null,
                    DirectProfileId: p.Id, DirectGoogle: true);
            AppStore.Log("GOOGLE_DIRECT_NO_RCLONE_MOUNT_READY profile=" + p.Id);
            return await VerifiedThenOk(p, "Google 직접 API WinFsp 연결 완료(rclone 미사용).");
        }
        catch (Exception ex)
        {
            if (!started || process.HasExited)
                MountPresentation.ForgetNetworkMountAttempt(p);
            AppStore.Log("GOOGLE_DIRECT_CONNECT_FAILED profile=" + p.Id +
                " type=" + ex.GetType().Name);
            return OperationResult.Fail("Google 직접 연결 실패: " +
                ex.GetType().Name);
        }
    }

    private static async Task<OperationResult> DisconnectDirectGoogleAsync(
        NasProfile p, Instance instance)
    {
        if (!instance.Process.HasExited)
        {
            try
            {
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
                string reply = await DirectGoogleCommandAsync(p.Id, "QUIT", timeout.Token);
                if (reply != "OK")
                    return OperationResult.Fail(
                        "Google 파일이 열려 있거나 복구 기록이 남아 있어 안전한 연결 해제를 보류합니다: " +
                        reply);
                await instance.Process.WaitForExitAsync()
                    .WaitAsync(TimeSpan.FromSeconds(12));
            }
            catch (Exception ex)
            {
                return OperationResult.Fail(
                    "Google 직접 엔진의 안전한 해제를 확인할 수 없습니다: " +
                    ex.GetType().Name);
            }
        }
        if (!await WaitForLetterRelease(p.Letter))
            return OperationResult.Fail(
                "Google 직접 드라이브를 Windows에서 아직 사용하고 있습니다.");
        lock (Guard)
        {
            Running.Remove(p.Id);
            BrowseVerified.Remove(p.Id);
        }
        instance.Process.Dispose();
        MountPresentation.ForgetNetworkMountAttempt(p);
        return OperationResult.Ok("Google 직접 WinFsp 드라이브가 안전하게 해제됐습니다.");
    }
}
