namespace NASMount;

/// <summary>
/// Starting a second read-only rclone process to list the root BEFORE
/// mounting is the dominant cold-start cost for remote clouds. A live mount
/// must itself enumerate its root and validate WinFsp before it can be marked
/// Connected, so the extra remote round trip is redundant on ordinary
/// Connect. The detailed rclone lsf preflight remains available in the
/// explicit "connection diagnostic" action.
///
/// Keep all LOCAL safeguards, especially SFTP pinned host keys, auth
/// presence, binary integrity and the exclusive profile bridge.
/// </summary>
internal static class FastConnectPolicy
{
    internal static bool RequiresFullPreflightForConnect(NasProfile p) => false;

    internal static async Task<OperationResult> ValidateLocalAsync(
        NasProfile p, string? password, string executable)
    {
        if (!RemoteMount.TryValidate(p, out string reason))
            return OperationResult.Fail(reason);
        if (p.Protocol == "SFTP" && !SftpHostKeyTrust.HasTrustedKey(p))
            return OperationResult.Fail(
                "SFTP 서버의 공개키가 승인되지 않았습니다. [연결]에서 SSH SHA256 지문을 먼저 승인하세요.");
        if (p.Protocol == "TeraBox" && !TeraBoxBackend.HasCookie(password))
            return OperationResult.Fail(
                "테라박스 인증 쿠키(ndus)가 없습니다. 설정에서 유효한 쿠키를 입력하세요.");
        if (p.Protocol == "GoogleDrive" && !GoogleDriveAuth.IsAuthorized(p))
            return OperationResult.Fail(
                "Google Drive 인증이 필요합니다. 연결을 눌러 브라우저에서 승인하세요.");
        if (p.Protocol == "OneDrive" && !OneDriveAuth.IsAuthorized(p))
            return OperationResult.Fail(
                "OneDrive 인증이 필요합니다. 연결을 눌러 브라우저에서 승인하세요.");
        if (p.Protocol == "MyBox" && !MyBoxBetaGateway.Api.ValidToken(password))
            return OperationResult.Fail(
                "MYBOX는 유효한 개인 액세스 토큰(PAT)이 필요합니다.");
        return await RcloneExecutableInspector.VerifyAsync(executable);
    }

    internal static void PolicyTest()
    {
        var protectedSftp = new NasProfile {
            Protocol = "SFTP", Host = "no-network.invalid", Letter = "Q",
            UseLockBridge = true
        };
        if (RequiresFullPreflightForConnect(protectedSftp) ||
            RequiresFullPreflightForConnect(new NasProfile {
                Protocol = "GoogleDrive", Letter = "Q"
            }) ||
            RequiresFullPreflightForConnect(new NasProfile {
                Protocol = "OneDrive", Letter = "Q"
            }))
            throw new InvalidOperationException("Duplicate read-only lsf fast path enabled");
        // No network call or binary is reached on a missing SFTP pin.
        var blocked = ValidateLocalAsync(
            protectedSftp, "fake-test-password", "missing-rclone.exe")
            .GetAwaiter().GetResult();
        if (blocked.Success ||
            !blocked.Message.Contains("SSH", StringComparison.Ordinal))
            throw new InvalidOperationException("SFTP pin policy bypassed by fast connect");
        var cloud = new NasProfile {
            Protocol = "OneDrive", Host = "", Share = "", Letter = "Q",
            UseLockBridge = false
        };
        blocked = ValidateLocalAsync(cloud, null, "missing-rclone.exe")
            .GetAwaiter().GetResult();
        if (blocked.Success ||
            !blocked.Message.Contains("OneDrive", StringComparison.Ordinal))
            throw new InvalidOperationException("OneDrive OAuth bypassed by fast connect");
        Console.WriteLine("FAST_CONNECT_NO_DUPLICATE_REMOTE_LIST_LOCAL_SAFETY_PASS");
    }
}
