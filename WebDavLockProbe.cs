using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Xml.Linq;

namespace NASMount;

/// <summary>
/// Tests *server-side* RFC 4918 exclusive write locks on a unique disposable
/// resource. No existing user document is opened, modified or deleted.
/// A second HTTP session with the same credentials tries to write without a
/// token; the server must refuse it with 423 for a positive result.
/// </summary>
internal static class WebDavLockProbe
{
    internal sealed record Result(
        bool Supported,
        bool Enforced,
        bool AuthorizedWrite,
        bool Released,
        bool CleanupSucceeded,
        string Detail)
    {
        public bool Success =>
            Supported && Enforced && AuthorizedWrite && Released && CleanupSucceeded;
    }

    private const string LockXml =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<d:lockinfo xmlns:d=\"DAV:\"><d:lockscope><d:exclusive/></d:lockscope>" +
        "<d:locktype><d:write/></d:locktype><d:owner>" +
        "<d:href>NASMount-RFC4918-capability-test</d:href></d:owner></d:lockinfo>";

    internal static async Task<Result> TestProfile(NasProfile profile, string password)
    {
        if (profile.Protocol != "WebDAV")
            return new(false, false, false, false, true, "WebDAV 연결만 검사할 수 있습니다.");
        using var first = CreateClient(profile, profile.IsAnonymous ? null : profile.Username, password);
        using var second = CreateClient(profile, profile.IsAnonymous ? null : profile.Username, password);
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        return await Test(first, second, WebDavSaveProbe.RootUri(profile), deadline.Token);
    }

    private static HttpClient CreateClient(NasProfile profile, string? username, string password)
    {
        var client = WebDavTlsPolicy.CreateClient(profile,
            TimeSpan.FromSeconds(18));
        if (username is not null)
            client.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue(
                "Basic", Convert.ToBase64String(Encoding.UTF8.GetBytes(username + ":" + password)));
        return client;
    }

    internal static async Task<Result> Test(HttpClient first, HttpClient second, Uri folder,
        CancellationToken cancellationToken = default)
    {
        if (!folder.IsAbsoluteUri || folder.Scheme is not ("https" or "http"))
            return new(false, false, false, false, true, "WebDAV 경로가 유효하지 않습니다.");
        string marker = Guid.NewGuid().ToString("N");
        var uri = new Uri(folder, ".nasmount_probe_" + marker + ".xlsx");
        var original = Encoding.UTF8.GetBytes("NASMount lock probe " + marker);
        bool fileCreated = false, lockGranted = false;
        bool supported = false, enforced = false, authorizedWrite = false, released = false;
        bool cleaned = true;
        string? token = null;
        var notes = new List<string>();
        try
        {
            using (var request = new HttpRequestMessage(HttpMethod.Put, uri))
            {
                request.Content = new ByteArrayContent(original);
                using var put = await first.SendAsync(request, cancellationToken);
                put.EnsureSuccessStatusCode();
                fileCreated = true;
            }
            using (var request = new HttpRequestMessage(new HttpMethod("LOCK"), uri))
            {
                request.Headers.TryAddWithoutValidation("Depth", "0");
                request.Headers.TryAddWithoutValidation("Timeout", "Second-120");
                request.Content = new StringContent(LockXml, Encoding.UTF8, "application/xml");
                using var response = await first.SendAsync(request, cancellationToken);
                if (!response.IsSuccessStatusCode)
                {
                    notes.Add("서버 LOCK 미지원/거부: HTTP " + (int)response.StatusCode);
                }
                else
                {
                    lockGranted = true;
                    token = await ExtractToken(response, cancellationToken);
                    if (string.IsNullOrWhiteSpace(token))
                    {
                        notes.Add("LOCK 응답은 성공했지만 Lock-Token을 찾지 못했습니다.");
                        notes.Add("서버의 잠금 시간이 만료될 때까지 임시 파일이 남을 수 있습니다.");
                    }
                    else supported = true;
                }
            }
            if (supported)
            {
                using var request = new HttpRequestMessage(HttpMethod.Put, uri);
                request.Content = new ByteArrayContent(
                    Encoding.UTF8.GetBytes("unauthorized second writer"));
                using var attempt = await second.SendAsync(request, cancellationToken);
                enforced = attempt.StatusCode == (HttpStatusCode)423;
                notes.Add(enforced
                    ? "두 번째 연결의 잠금 토큰 없는 쓰기를 HTTP 423으로 차단함"
                    : "두 번째 연결의 쓰기 응답 HTTP " + (int)attempt.StatusCode +
                      " — 표준 LOCK 강제 여부를 확인하지 못함");
                using var current = await first.GetAsync(uri, cancellationToken);
                current.EnsureSuccessStatusCode();
                byte[] actual = await current.Content.ReadAsByteArrayAsync(cancellationToken);
                if (!actual.AsSpan().SequenceEqual(original))
                {
                    enforced = false;
                    notes.Add("검사 파일 내용이 잠금 중 변경됐습니다.");
                }
                if (enforced && token is not null)
                {
                    byte[] authorized = Encoding.UTF8.GetBytes(
                        "NASMount lock token authorized write " + marker);
                    using var authorizedRequest = new HttpRequestMessage(HttpMethod.Put, uri);
                    authorizedRequest.Headers.TryAddWithoutValidation("If", "(" + token + ")");
                    authorizedRequest.Content = new ByteArrayContent(authorized);
                    using var authorizedResponse = await first.SendAsync(authorizedRequest,
                        cancellationToken);
                    if (authorizedResponse.IsSuccessStatusCode)
                    {
                        using var verified = await first.GetAsync(uri, cancellationToken);
                        verified.EnsureSuccessStatusCode();
                        authorizedWrite = (await verified.Content.ReadAsByteArrayAsync(cancellationToken))
                            .AsSpan().SequenceEqual(authorized);
                        notes.Add(authorizedWrite
                            ? "잠금 토큰을 포함한 소유자의 PUT과 내용 검증 성공"
                            : "토큰을 이용한 PUT 후 데이터가 일치하지 않습니다.");
                    }
                    else notes.Add("잠금 토큰을 포함한 PUT 실패: HTTP " +
                        (int)authorizedResponse.StatusCode);
                }
            }
            // A server that accepts a tokenless write MUST NOT be marked as
            // safe, even if it also supports LOCK/UNLOCK methods.
        }
        catch (Exception ex)
        {
            notes.Add(ex is OperationCanceledException
                ? "LOCK 검사 제한시간(30초) 초과: 서버 기본 잠금을 사용하지 않습니다."
                : "LOCK 검사 중 오류: " + ex.Message);
        }
        finally
        {
            if (token is not null)
            {
                try
                {
                    using var unlock = new HttpRequestMessage(new HttpMethod("UNLOCK"), uri);
                    unlock.Headers.TryAddWithoutValidation("Lock-Token", token);
                    // Cleanup is independently bounded even when the main
                    // capability deadline has expired.
                    using var cleanupDeadline = new CancellationTokenSource(
                        TimeSpan.FromSeconds(6));
                    using var response = await first.SendAsync(unlock,
                        cleanupDeadline.Token);
                    released = response.IsSuccessStatusCode;
                    if (!released)
                        notes.Add("UNLOCK 실패: HTTP " + (int)response.StatusCode);
                }
                catch (Exception ex)
                {
                    notes.Add("UNLOCK 예외: " + ex.Message);
                }
            }
            if (fileCreated && (!lockGranted || released))
            {
                try
                {
                    using var request = new HttpRequestMessage(HttpMethod.Delete, uri);
                    using var cleanupDeadline = new CancellationTokenSource(
                        TimeSpan.FromSeconds(6));
                    using var response = await first.SendAsync(request,
                        cleanupDeadline.Token);
                    cleaned = response.IsSuccessStatusCode ||
                        response.StatusCode == HttpStatusCode.NotFound;
                    if (!cleaned)
                        notes.Add("검사 파일 삭제 실패: HTTP " + (int)response.StatusCode);
                }
                catch (Exception ex)
                {
                    cleaned = false;
                    notes.Add("검사 파일 삭제 예외: " + ex.Message);
                }
            }
            else if (fileCreated)
            {
                cleaned = false;
                notes.Add("잠금을 안전하게 해제하지 못해 테스트 파일을 삭제하지 않았습니다.");
            }
        }
        if (supported) notes.Insert(0, "WebDAV RFC4918 LOCK 토큰 발급 확인.");
        notes.Add(enforced
            ? "참고: LOCK 강제 확인은 이 NAS의 WebDAV 프로토콜만 증명합니다. " +
              "현재 FileHub AIO/rclone의 자동 Office 잠금 연동을 의미하지 않습니다."
            : "WebDAV 교차 편집 안전성을 증명하지 못했습니다. 실제 공동 편집에 사용하지 마세요.");
        return new(supported, enforced, authorizedWrite, released, cleaned,
            string.Join("\n", notes));
    }

    private static async Task<string?> ExtractToken(HttpResponseMessage response,
        CancellationToken cancellationToken)
    {
        if (response.Headers.TryGetValues("Lock-Token", out var values))
        {
            string? header = values.FirstOrDefault()?.Trim();
            if (!string.IsNullOrEmpty(header))
                return header.StartsWith('<') && header.EndsWith('>')
                    ? header : "<" + header.Trim('<', '>') + ">";
        }
        try
        {
            string xml = await response.Content.ReadAsStringAsync(cancellationToken);
            var doc = XDocument.Parse(xml);
            XNamespace dav = "DAV:";
            string? href = doc.Descendants(dav + "locktoken")
                .Elements(dav + "href").FirstOrDefault()?.Value;
            if (string.IsNullOrWhiteSpace(href)) return null;
            return "<" + href.Trim('<', '>') + ">";
        }
        catch { return null; }
    }
}
