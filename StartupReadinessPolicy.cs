namespace NASMount;

/// <summary>
/// Local startup dependency gate. No remote account, network drive or
/// WinFsp mount is touched until all required local engines are executable.
/// An independent cloud backend's delayed mount is handled separately.
/// </summary>
internal static class StartupReadinessPolicy
{
    internal static TimeSpan MountWait(string protocol) =>
        protocol is "GoogleDrive" or "OneDrive" ? TimeSpan.FromSeconds(75) :
        protocol is "MyBox" or "TeraBox" ? TimeSpan.FromSeconds(55) :
        TimeSpan.FromSeconds(25);

    internal static async Task<OperationResult> VerifyLocalAsync(
        IEnumerable<NasProfile> profiles, bool winFspReady)
    {
        var enabled = profiles.Where(p => p.AutoConnect).ToArray();
        var nonSmb = enabled.Where(p => p.Protocol != "SMB").ToArray();
        if (nonSmb.Length == 0)
            return OperationResult.Ok("No local rclone engine required");
        if (!winFspReady)
            return OperationResult.Fail("WinFsp 드라이버가 아직 준비되지 않았습니다.");
        string folder = Path.Combine(AppContext.BaseDirectory, "tools");
        if (nonSmb.Any(p => p.UseLockBridge) &&
            !File.Exists(Path.Combine(folder, "NASMountLockBridge.exe")))
            return OperationResult.Fail("NASMountLockBridge.exe 파일이 없습니다.");
        var executables = new List<string>();
        if (nonSmb.Any(p => p.Protocol != "TeraBox"))
            executables.Add(Path.Combine(folder, "rclone.exe"));
        if (nonSmb.Any(p => p.Protocol == "TeraBox"))
            executables.Add(Path.Combine(folder, "rclone-terabox.exe"));
        var outcomes = await Task.WhenAll(executables.Select(
            RcloneExecutableInspector.VerifyAsync));
        for (int i = 0; i < outcomes.Length; ++i)
            if (!outcomes[i].Success)
                return OperationResult.Fail(Path.GetFileName(executables[i]) +
                    " 준비 실패: " + outcomes[i].Message);
        return OperationResult.Ok("Local rclone/WinFsp/bridge dependencies ready");
    }

    internal static void SelfTest()
    {
        if (MountWait("GoogleDrive").TotalSeconds < 60 ||
            MountWait("OneDrive").TotalSeconds < 60 ||
            MountWait("TeraBox").TotalSeconds <= 20 ||
            MountWait("WebDAV").TotalSeconds < 20)
            throw new InvalidOperationException("STARTUP_MOUNT_WAIT_POLICY_INVALID");
        Console.WriteLine("STARTUP_MOUNT_READY_GATED_TIMEOUT_POLICY_PASS");
    }
}
