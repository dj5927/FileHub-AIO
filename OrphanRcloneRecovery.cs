using System.Diagnostics;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Recover a previous FileHub process whose GUI exited before its rclone
/// child. Never kill a process. A graceful RC quit is permitted ONLY after
/// exact profile, drive letter, executable, RC PID, and empty upload/in-use
/// state have all been verified. Unknown or foreign state is preserved.
/// </summary>
internal static class OrphanRcloneRecovery
{
    private sealed record Entry(int Pid, string Name, string Path, string CommandLine);
    private static readonly Regex MountCommand = new(
        @"(?i)(?:^|\s)mount\s+nm_([a-f0-9]{32}):\s+([a-z]):(?:\s|$)",
        RegexOptions.Compiled | RegexOptions.CultureInvariant);

    internal static bool IsExactOwnedMountCommand(string command, NasProfile p)
    {
        var found = MountCommand.Match(command);
        return found.Success &&
            found.Groups[1].Value.Equals(p.Id.ToString("N"),
                StringComparison.OrdinalIgnoreCase) &&
            found.Groups[2].Value.Equals(p.Letter,
                StringComparison.OrdinalIgnoreCase) &&
            command.Contains("--cache-dir", StringComparison.OrdinalIgnoreCase) &&
            (command.Contains(Path.Combine(LocalCachePolicy.DefaultRoot,
                    p.Id.ToString("N")), StringComparison.OrdinalIgnoreCase) ||
             MatchesConfiguredCache(command, p)) &&
            command.Contains("--rc-addr 127.0.0.1:",
                StringComparison.OrdinalIgnoreCase);
    }

    private static bool MatchesConfiguredCache(string command, NasProfile p)
    {
        try { return command.Contains(LocalCachePolicy.ProfileRoot(p.Id,
            LocalCachePolicy.Current()), StringComparison.OrdinalIgnoreCase); }
        catch { return false; }
    }

    private static string? OwnedMountedLetter(string command, NasProfile p)
    {
        var found = MountCommand.Match(command);
        if (!found.Success || !found.Groups[1].Value.Equals(p.Id.ToString("N"),
                StringComparison.OrdinalIgnoreCase))
            return null;
        string letter = found.Groups[2].Value.ToUpperInvariant();
        var previous = p.Clone();
        previous.Letter = letter;
        return IsExactOwnedMountCommand(command, previous) ? letter : null;
    }

    internal static void OwnershipPolicyTest()
    {
        var p = new NasProfile
        {
            Id = Guid.Parse("f15d6c50-b7a4-45d5-bfe1-ecf3047add01"),
            Protocol = "GoogleDrive", Letter = "W"
        };
        string command = "rclone.exe mount nm_" + p.Id.ToString("N") +
            ": W: --network-mode --cache-dir " +
            Path.Combine(AppStore.DirectoryPath, "cache", p.Id.ToString("N")) +
            " --rc-addr 127.0.0.1:11216 --rc-user nm123456 --rc-pass abc";
        if (!IsExactOwnedMountCommand(command, p) ||
            IsExactOwnedMountCommand(command.Replace(" W:", " X:"), p) ||
            OwnedMountedLetter(command.Replace(" W:", " V:"), p) != "V" ||
            IsExactOwnedMountCommand(command.Replace(
                p.Id.ToString("N"), Guid.NewGuid().ToString("N")), p) ||
            IsExactOwnedMountCommand("rclone.exe mount remote: W:", p))
            throw new InvalidOperationException(
                "Orphan recovery must only match an exact FileHub profile");
        Console.WriteLine("ORPHAN_RCLONE_EXACT_PROFILE_POLICY_PASS");
    }

    private static async Task<Entry[]?> ProcessSnapshot()
    {
        using var child = new Process
        {
            StartInfo = new ProcessStartInfo("powershell.exe")
            {
                UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8
            }
        };
        child.StartInfo.ArgumentList.Add("-NoProfile");
        child.StartInfo.ArgumentList.Add("-NonInteractive");
        child.StartInfo.ArgumentList.Add("-Command");
        child.StartInfo.ArgumentList.Add(
            "$ErrorActionPreference='Stop';" +
            "$rows=@(Get-CimInstance Win32_Process -Filter " +
            "\"Name='rclone.exe' OR Name='rclone-terabox.exe' OR Name='FileHubAIO.exe'\"" +
            " | Select-Object ProcessId,Name,ExecutablePath,CommandLine);" +
            "ConvertTo-Json -InputObject $rows -Compress -Depth 3");
        child.Start();
        Task<string> output = child.StandardOutput.ReadToEndAsync();
        Task<string> errors = child.StandardError.ReadToEndAsync();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(9));
        try { await child.WaitForExitAsync(timeout.Token); }
        catch (OperationCanceledException)
        {
            try { if (!child.HasExited) child.Kill(); } catch { }
            return null;
        }
        string json = await output;
        _ = await errors; // The output (possibly containing RC secrets) is NEVER logged.
        if (child.ExitCode != 0) return null;
        using var document = JsonDocument.Parse(json);
        if (document.RootElement.ValueKind != JsonValueKind.Array) return null;
        return document.RootElement.EnumerateArray().Select(item => new Entry(
            item.GetProperty("ProcessId").GetInt32(),
            item.GetProperty("Name").GetString() ?? "",
            item.TryGetProperty("ExecutablePath", out var path)
                ? path.GetString() ?? "" : "",
            item.TryGetProperty("CommandLine", out var command)
                ? command.GetString() ?? "" : "")).ToArray();
    }

    private static string? Option(string command, string name, string pattern)
    {
        var match = Regex.Match(command, @"(?i)(?:^|\s)--" +
            Regex.Escape(name) + @"\s+(" + pattern + @")(?:\s|$)");
        return match.Success ? match.Groups[1].Value : null;
    }

    internal static async Task<OperationResult?> TryRecoverAsync(NasProfile p)
    {
        if (p.Protocol is not ("GoogleDrive" or "TeraBox") ||
            RemoteMount.HasManagedRecord(p))
            return null;
        try
        {
            Entry[]? entries = await ProcessSnapshot();
            if (entries is null)
                return OperationResult.Fail("이전 연결 프로세스의 소유권을 확인할 수 없습니다.");
            // Other GUIs may own a live rclone. Never take it from them.
            if (entries.Any(e => e.Name.Equals("FileHubAIO.exe",
                    StringComparison.OrdinalIgnoreCase) &&
                    e.Pid != Environment.ProcessId))
                return OperationResult.Fail("다른 FileHub AIO가 실행 중이므로 이전 연결을 유지합니다.");
            var matches = entries.Where(e =>
                e.Name.Equals(p.Protocol == "TeraBox"
                        ? "rclone-terabox.exe" : "rclone.exe",
                    StringComparison.OrdinalIgnoreCase) &&
                OwnedMountedLetter(e.CommandLine, p) is not null).ToArray();
            if (matches.Length == 0) return null;
            if (matches.Length != 1)
                return OperationResult.Fail("동일 프로필 백엔드가 여러 개 실행 중입니다.");
            Entry owner = matches[0];
            string formerLetter = OwnedMountedLetter(owner.CommandLine, p)!;
            var previous = p.Clone();
            previous.Letter = formerLetter;
            if (!RemoteMount.DriveLetterExists(formerLetter) ||
                RemoteMount.HasVerifiedOtherVendorMapping(previous))
                return OperationResult.Fail(
                    "이전 마운트 문자에 다른 연결이 존재하거나 연결 상태가 불명확합니다.");
            if (!Regex.IsMatch(owner.Path,
                    p.Protocol == "TeraBox"
                        ? @"(?i)\\tools\\rclone-terabox\.exe\z"
                        : @"(?i)\\tools\\rclone\.exe\z") ||
                !owner.CommandLine.Contains(
                    p.Protocol == "TeraBox"
                        ? Path.Combine(AppStore.DirectoryPath, "no-global-rclone.conf")
                        : Path.Combine(AppStore.DirectoryPath, "google-auth",
                            p.Id.ToString("N") + ".conf"),
                    StringComparison.OrdinalIgnoreCase))
                return OperationResult.Fail("백엔드 실행 경로 또는 인증 설정 소유권이 다릅니다.");
            string? portText = Option(owner.CommandLine, "rc-addr",
                @"127\.0\.0\.1:[0-9]{2,5}");
            string? user = Option(owner.CommandLine, "rc-user", @"nm[0-9]{6}");
            string? pass = Option(owner.CommandLine, "rc-pass", @"[0-9a-fA-F]{48}");
            if (portText is null || user is null || pass is null ||
                !int.TryParse(portText.Split(':')[1], out int port) ||
                port is < 1024 or > 65535)
                return OperationResult.Fail("이전 백엔드 RC 인증을 검증할 수 없습니다.");
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
                "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(
                    user + ":" + pass)));
            string url = "http://127.0.0.1:" + port;
            using var pidResponse = await http.PostAsync(url + "/core/pid",
                new StringContent("{}"));
            pidResponse.EnsureSuccessStatusCode();
            if (!RemoteMount.MatchingProcessPid(
                    await pidResponse.Content.ReadAsStringAsync(), owner.Pid))
                return OperationResult.Fail("RC 응답의 PID가 이전 rclone과 다릅니다.");
            using var statsResponse = await http.PostAsync(url + "/vfs/stats",
                new StringContent("{}"));
            statsResponse.EnsureSuccessStatusCode();
            string json = await statsResponse.Content.ReadAsStringAsync();
            var upload = RemoteMount.ParseUploadStatus(json);
            using var document = JsonDocument.Parse(json);
            // An otherwise idle WinFsp mount commonly holds one VFS root
            // reference; a clean graceful quit can release this reference.
            if (!document.RootElement.TryGetProperty("inUse", out var inUse) ||
                !inUse.TryGetInt32(out int open) || open > 1 ||
                !upload.IsSafeToDisconnect)
            {
                AppStore.Log("ORPHAN_RCLONE_UPLOAD_OR_HANDLE_PRESERVED id=" + p.Id);
                return OperationResult.Fail(
                    "이전 마운트에 열린 핸들 또는 미완료 업로드가 있어 유지합니다.");
            }
            AppStore.Log("ORPHAN_RCLONE_SAFE_RC_QUIT_BEGIN id=" + p.Id +
                " pid=" + owner.Pid + " letter=" + formerLetter +
                " vfsInUse=" + open);
            using var process = Process.GetProcessById(owner.Pid);
            using var quit = await http.PostAsync(url + "/core/quit",
                new StringContent("{}"));
            quit.EnsureSuccessStatusCode();
            using var wait = new CancellationTokenSource(TimeSpan.FromSeconds(13));
            await process.WaitForExitAsync(wait.Token);
            var cleanup = NetworkDriveResidueCleaner.AfterSafeStop(previous);
            AppStore.Log("ORPHAN_RCLONE_SAFE_RC_QUIT_FINISHED id=" + p.Id +
                " cleanup=" + cleanup.Success);
            if (!cleanup.Success) return cleanup;
            for (int n = 0; n < 40; n++)
            {
                if (!RemoteMount.DriveLetterExists(formerLetter))
                    return cleanup;
                await Task.Delay(200);
            }
            return OperationResult.Fail(
                "이전 rclone은 정상 종료됐지만 Windows 드라이브 문자 해제를 기다리는 중입니다.");
        }
        catch (Exception ex)
        {
            AppStore.Log("ORPHAN_RCLONE_RECOVERY_PRESERVED id=" + p.Id +
                " reason=" + ex.GetType().Name);
            return OperationResult.Fail(
                "이전 Google Drive의 안전 종료를 확인하지 못해 연결을 유지합니다.");
        }
    }
}
