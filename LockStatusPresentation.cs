namespace NASMount;

/// <summary>
/// Distinguishes a browseable drive from one which actually has the cooperative
/// bridge configured; no amount of Explorer visibility proves a remote lease.
/// </summary>
internal static class LockStatusPresentation
{
    // The displayed readonly tag must follow the saved connection mode,
    // not an always-on protocol description. SMB read/write permissions are
    // administered on the Windows/NAS server; local mode flags do not enforce
    // SMB read-only and must not claim that they do.
    internal static bool IsReadOnlyMode(NasProfile profile) =>
        profile.Protocol != "SMB" &&
        !profile.UseLockBridge &&
        !profile.AllowUnprotectedWrites;

    internal static string CardStatus(NasProfile profile, bool online, bool runningNotBrowsable,
        bool pausedByManualDisconnect = false, bool driveLetterOccupied = false)
    {
        if (!online && pausedByManualDisconnect)
            return runningNotBrowsable
                ? "○ 수동 해제 요청 · 자동 재연결 중지"
                : "○ 수동 연결 해제 · 자동 재연결 중지";
        if (runningNotBrowsable)
            return "● 연결 준비 중 · 마운트 확인 중";
        if (!online && driveLetterOccupied && profile.Protocol != "SMB")
            return "○ 드라이브 문자 사용 중 · 이전 연결 확인";
        if (!online)
            return "○ 연결 대기 · 자동 재연결 " + (profile.AutoConnect ? "켜짐" : "꺼짐");
        if (IsReadOnlyMode(profile))
            return "● 연결됨 · 읽기 전용";
        if (profile.Protocol == "SMB")
            return "● 연결됨 · 서버 공유 권한에 따름";
        if (profile.Protocol != "WebDAV")
            return "● 연결됨 · 탐색기 접근 확인";
        if (profile.UseLockBridge && profile.UseNativeWebDavLocks)
            return "● 연결됨 · WebDAV 서버 기본 잠금 (폴더 없음)";
        if (profile.UseLockBridge)
            return "● 연결됨 · 파일잠금 브리지 사용";
        if (profile.AllowUnprotectedWrites)
            return "● 연결됨 · 파일잠금 꺼짐 (동시 편집 위험)";
        return "● 연결됨 · 탐색기 접근 확인";
    }
}
