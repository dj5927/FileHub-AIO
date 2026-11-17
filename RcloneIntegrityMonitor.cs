namespace NASMount;

/// <summary>
/// Classify VFS errors that must never be silently treated as a successful
/// Office save. Notifications deliberately contain no filenames or secrets.
/// </summary>
internal static class RcloneIntegrityMonitor
{
    internal static string? Classify(string line)
    {
        if (line.Contains("File.Rename failed in Cache", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("failed to rename in cache", StringComparison.OrdinalIgnoreCase))
            return "위험: rclone VFS 임시 파일 이름 변경 실패. 저장 성공처럼 보여도 수정 내용이 유실될 수 있습니다.";
        if (line.Contains("vfs cache: upload failed", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("vfs cache: error uploading", StringComparison.OrdinalIgnoreCase) ||
            line.Contains("vfs cache: upload error", StringComparison.OrdinalIgnoreCase))
            return "위험: NAS 업로드 실패. 로컬 VFS 캐시를 보존하고 NAS 파일을 확인하세요.";
        if (line.Contains("out of space", StringComparison.OrdinalIgnoreCase) &&
            line.Contains("vfs", StringComparison.OrdinalIgnoreCase))
            return "위험: VFS 캐시 저장 공간 부족. 캐시를 삭제하지 말고 디스크 공간을 확인하세요.";
        return null;
    }
}
