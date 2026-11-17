using System.Net;
using System.Text;
using System.Net.Http.Headers;

namespace NASMount;

/// <summary>
/// A separate human-readable lease indicator. NEVER create the exact
/// ~$Workbook.xlsx path: that path belongs exclusively to Microsoft Office,
/// and claiming it can break Excel's save/open behavior.
/// </summary>
internal static class OfficeUseMarker
{
    internal static bool AppliesTo(string path) =>
        !IsStatusMarker(path) && Path.GetExtension(path).ToLowerInvariant() is
            ".xls" or ".xlsx" or ".xlsm" or ".xlsb" or
            ".doc" or ".docx" or ".docm" or ".ppt" or ".pptx" or ".pptm";

    internal static bool IsExcel(string path) =>
        !IsStatusMarker(path) && Path.GetExtension(path).ToLowerInvariant() is
            ".xls" or ".xlsx" or ".xlsm" or ".xlsb";

    internal static bool IsExcelMarker(string marker) =>
        Path.GetFileName(marker).StartsWith("NASMount_사용중_", StringComparison.OrdinalIgnoreCase) &&
        marker.EndsWith(".xlsx", StringComparison.OrdinalIgnoreCase);

    internal static bool IsStatusMarker(string relativeFile) =>
        Path.GetFileName(relativeFile.Replace('\\', '/'))
            .StartsWith("NASMount_사용중_", StringComparison.OrdinalIgnoreCase);

    internal static string RelativePath(string relativeFile)
    {
        string path = relativeFile.Replace('\\', '/').TrimStart('/');
        if (path.Length == 0 || path.Contains("..", StringComparison.Ordinal) ||
            path.Contains('\r') || path.Contains('\n') || path.EndsWith('/'))
            throw new ArgumentException("Invalid owner marker relative path");
        int slash = path.LastIndexOf('/');
        string folder = slash < 0 ? "" : path[..(slash + 1)];
        string file = slash < 0 ? path : path[(slash + 1)..];
        // Keep the suffix ASCII so both Synology WebDAV and Windows rclone
        // normalize it consistently regardless of server filename encoding.
        bool excel = IsExcel(path);
        string suffix = excel ? ".xlsx" : ".IN_USE.txt";
        string displayFile = excel && file.EndsWith(".xlsx", StringComparison.OrdinalIgnoreCase)
            ? file[..^5] : file;
        string name = "NASMount_사용중_" + displayFile + suffix;
        if (name.Length > 210)
        {
            string fingerprint = Convert.ToHexString(
                System.Security.Cryptography.SHA256.HashData(Encoding.UTF8.GetBytes(file)))[..12];
            name = "NASMount_사용중_" + displayFile[..Math.Min(displayFile.Length, 140)] +
                "_" + fingerprint + suffix;
        }
        return folder + name;
    }

    internal static string Text(CooperativeLocks.Lease lease) =>
        "FileHub AIO - 다른 사용자가 파일을 편집 중입니다.\r\n" +
        "파일: " + lease.Resource + "\r\n" +
        "사용자/PC: " + lease.Owner + "\r\n" +
        "잠금 시작(UTC): " + lease.CreatedUtc.ToString("u") + "\r\n" +
        "잠금 ID: " + lease.Id + "\r\n" +
        "\r\n이 파일은 Microsoft Excel 소유자 파일이 아닌 FileHub AIO 상태 표시입니다.\r\n" +
        "파일을 닫고 업로드가 끝나면 잠금과 함께 제거됩니다.\r\n";

    // Shared by WebDAV, FTP and SFTP. The .xlsx status document is genuine
    // OOXML and never uses Microsoft's reserved ~$ owner filename.
    internal static byte[] ContentBytes(CooperativeLocks.Lease lease) =>
        IsExcelMarker(RelativePath(lease.Resource))
            ? OfficeMarkerWorkbook.Create(lease)
            : Encoding.UTF8.GetBytes(Text(lease));

    internal static bool ContentBelongsTo(byte[] content,
        CooperativeLocks.Lease lease) =>
        IsExcelMarker(lease.VisibleMarker ?? RelativePath(lease.Resource))
            ? string.Equals(OfficeMarkerWorkbook.ReadLeaseId(content),
                lease.Id, StringComparison.Ordinal)
            : Encoding.UTF8.GetString(content).Contains(
                "잠금 ID: " + lease.Id + "\r\n", StringComparison.Ordinal);

    /// <returns>True only if this lease successfully created the marker.</returns>
    internal static bool Create(HttpClient client, string url,
        CooperativeLocks.Lease lease)
    {
        try
        {
            // A stale marker may survive a crash after the actual lock
            // folder was manually cleared. Do not overwrite an existing
            // remote file even when a WebDAV backend ignores If-None-Match.
            using (var head = client.Send(new HttpRequestMessage(HttpMethod.Head, url)))
            {
                if (head.IsSuccessStatusCode)
                {
                    AppStore.Log("VISIBLE_OWNER_MARKER_ALREADY_EXISTS " + lease.Resource);
                    return false;
                }
                if (head.StatusCode != HttpStatusCode.NotFound &&
                    head.StatusCode != HttpStatusCode.Gone)
                {
                    AppStore.Log("VISIBLE_OWNER_MARKER_HEAD_FAILED HTTP=" +
                        (int)head.StatusCode + " file=" + lease.Resource);
                    return false;
                }
            }
            using var put = new HttpRequestMessage(HttpMethod.Put, url);
            put.Headers.TryAddWithoutValidation("If-None-Match", "*");
            if (IsExcelMarker(RelativePath(lease.Resource)))
            {
                put.Content = new ByteArrayContent(ContentBytes(lease));
                put.Content.Headers.ContentType =
                    new MediaTypeHeaderValue("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            }
            else
                put.Content = new StringContent(Text(lease), Encoding.UTF8, "text/plain");
            using var result = client.Send(put);
            if (result.StatusCode is HttpStatusCode.Created or HttpStatusCode.NoContent)
                return true;
            AppStore.Log("VISIBLE_OWNER_MARKER_CREATE_FAILED HTTP=" +
                (int)result.StatusCode + " file=" + lease.Resource);
        }
        catch (Exception ex)
        {
            AppStore.Log("VISIBLE_OWNER_MARKER_CREATE_FAILED " + lease.Resource +
                " " + ex.Message);
        }
        return false;
    }

    internal static void DeleteIfOwned(HttpClient client, string url,
        CooperativeLocks.Lease lease)
    {
        using var get = client.GetAsync(url).GetAwaiter().GetResult();
        if (get.StatusCode == HttpStatusCode.NotFound) return;
        get.EnsureSuccessStatusCode();
        byte[] content = get.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult();
        bool owned = ContentBelongsTo(content, lease);
        if (!owned)
        {
            AppStore.Log("VISIBLE_OWNER_MARKER_NOT_OURS " + lease.Resource);
            return;
        }
        using var delete = client.DeleteAsync(url).GetAwaiter().GetResult();
        if (!delete.IsSuccessStatusCode && delete.StatusCode != HttpStatusCode.NotFound)
            throw new IOException("Visible marker DELETE HTTP " + (int)delete.StatusCode);
    }
}
