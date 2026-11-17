using System.Net;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;

namespace NASMount;

/// <summary>
/// Non-destructive WebDAV PUT -> MOVE -> GET -> DELETE test for Excel-style
/// atomic saving. Uses random probe filenames only; no Office documents touched.
/// The test verifies WebDAV server semantics, not WinFsp VFS cache correctness.
/// </summary>
internal static class WebDavSaveProbe
{
    public static Uri RootUri(NasProfile p)
    {
        if (p.Protocol != "WebDAV")
            throw new ArgumentException("WebDAV 프로필만 검사할 수 있습니다.");
        int port = p.Port is > 0 ? p.Port : 5006;
        string path = string.Join("/", p.Share.Replace('\\', '/').Split('/',
            StringSplitOptions.RemoveEmptyEntries).Select(Uri.EscapeDataString));
        return new Uri($"{(p.WebDavUseHttp ? "http" : "https")}://{p.Host}:{port}/{(path.Length > 0 ? path + "/" : "")}");
    }

    public static async Task<OperationResult> TestProfile(NasProfile p, string password)
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
        if (!p.IsAnonymous)
            http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
                "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(p.Username + ":" + password)));
        return await Test(http, RootUri(p));
    }

    // Also callable with a private localhost WebDAV fixture for automated tests.
    public static async Task<OperationResult> Test(HttpClient client, Uri folder)
    {
        if (!folder.IsAbsoluteUri || folder.Scheme is not ("https" or "http"))
            return OperationResult.Fail("WebDAV URL이 올바르지 않습니다.");

        string identifier = Guid.NewGuid().ToString("N");
        var temp = new Uri(folder, ".nasmount_probe_" + identifier + ".tmp");
        var final = new Uri(folder, ".nasmount_probe_" + identifier + ".xlsx");
        byte[] payload = Encoding.UTF8.GetBytes("NASMount WebDAV save probe " + identifier);
        var cleanupErrors = new List<string>();
        string? error = null;
        try
        {
            using (var put = new HttpRequestMessage(HttpMethod.Put, temp))
            {
                put.Content = new ByteArrayContent(payload);
                using var response = await client.SendAsync(put);
                if (!response.IsSuccessStatusCode)
                    throw new IOException($"임시 파일 PUT 실패: HTTP {(int)response.StatusCode} {response.ReasonPhrase}");
            }

            using (var move = new HttpRequestMessage(new HttpMethod("MOVE"), temp))
            {
                move.Headers.TryAddWithoutValidation("Destination", final.AbsoluteUri);
                move.Headers.TryAddWithoutValidation("Overwrite", "F");
                using var response = await client.SendAsync(move);
                if (!response.IsSuccessStatusCode)
                    throw new IOException($"WebDAV MOVE 실패: HTTP {(int)response.StatusCode} {response.ReasonPhrase}");
            }

            using (var read = await client.GetAsync(final))
            {
                read.EnsureSuccessStatusCode();
                byte[] actual = await read.Content.ReadAsByteArrayAsync();
                if (!CryptographicOperations.FixedTimeEquals(
                    SHA256.HashData(payload), SHA256.HashData(actual)))
                    throw new IOException("최종 파일이 존재하지만 내용이 원본과 다릅니다.");
            }
        }
        catch (Exception ex)
        {
            error = ex.Message;
        }
        finally
        {
            // Only our unique probe names are eligible for deletion; never
            // delete or rename pre-existing remote documents.
            foreach (var uri in new[] { temp, final })
            {
                // A request may succeed server-side even if the HTTP response
                // was lost. Always try to clean both random probe paths.
                try
                {
                    using var delete = new HttpRequestMessage(HttpMethod.Delete, uri);
                    using var response = await client.SendAsync(delete);
                    if (!response.IsSuccessStatusCode && response.StatusCode != HttpStatusCode.NotFound)
                        cleanupErrors.Add(uri.Segments.Last() + " HTTP " + (int)response.StatusCode);
                }
                catch (Exception ex) { cleanupErrors.Add(uri.Segments.Last() + ": " + ex.Message); }
            }
        }

        if (cleanupErrors.Count > 0)
            return OperationResult.Fail("임시 검사 파일 정리에 실패했습니다. 관리자 확인이 필요합니다.\n" +
                string.Join("\n", cleanupErrors) + (error is null ? "" : "\n" + error));
        return error is null
            ? OperationResult.Ok("WebDAV PUT → MOVE(최종 파일명) → GET(내용 비교) → 정리까지 통과.\n" +
                "이 검사는 NAS의 MOVE 동작만 확인합니다. Excel/WinFsp 전체 저장은 별도 테스트가 필요합니다.")
            : OperationResult.Fail("WebDAV 파일 저장 호환성 검사 실패: " + error +
                "\n검사 파일을 정리했습니다. 로그와 NAS 권한을 확인하세요.");
    }
}
