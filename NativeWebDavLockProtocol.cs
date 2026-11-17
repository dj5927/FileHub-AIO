using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;

namespace NASMount;

/// <summary>
/// Experimental RFC 4918 native-lock protocol client. No directory or sidecar
/// is created. NOT wired to rclone/WinFsp: the standard rclone WebDAV backend
/// does not submit per-file If tokens with its PUT and MOVE requests.
///
/// This class is isolated until a token-aware upload path passes real-server
/// two-client and Office save tests. It never silently falls back to unlocked
/// writes or the old .nasmount.lock scheme.
/// </summary>
internal static class NativeWebDavLockProtocol
{
    internal sealed record Lease(Uri Resource, string Token, DateTimeOffset ExpiresUtc);

    private const string Body =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
        "<D:lockinfo xmlns:D=\"DAV:\">" +
        "<D:lockscope><D:exclusive/></D:lockscope>" +
        "<D:locktype><D:write/></D:locktype>" +
        "<D:owner><D:href>NASMount</D:href></D:owner>" +
        "</D:lockinfo>";

    internal static async Task<Lease> AcquireAsync(HttpClient client, Uri resource,
        CancellationToken cancellation = default)
    {
        ValidateResource(resource);
        using var request = new HttpRequestMessage(new HttpMethod("LOCK"), resource);
        request.Headers.TryAddWithoutValidation("Depth", "0");
        request.Headers.TryAddWithoutValidation("Timeout", "Second-600");
        request.Content = new StringContent(Body, Encoding.UTF8, "application/xml");
        using var response = await client.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellation);
        if (response.StatusCode is not (HttpStatusCode.OK or HttpStatusCode.Created))
            throw new IOException("Native WebDAV LOCK rejected: HTTP " +
                                  (int)response.StatusCode + ". Write not permitted.");
        if (!response.Headers.TryGetValues("Lock-Token", out var tokens))
            throw new IOException("WebDAV server LOCK reply missing Lock-Token; fail closed.");
        string token = ValidateToken(tokens.Single());
        return new Lease(resource, token, Expiry(response));
    }

    internal static async Task<Lease> RefreshAsync(HttpClient client, Lease lease,
        CancellationToken cancellation = default)
    {
        ValidateResource(lease.Resource);
        using var request = new HttpRequestMessage(
            new HttpMethod("LOCK"), lease.Resource);
        request.Headers.TryAddWithoutValidation("If", "(" + ValidateToken(lease.Token) + ")");
        request.Headers.TryAddWithoutValidation("Timeout", "Second-600");
        // Refresh is a LOCK with no body: a second body would create a new lock.
        using var response = await client.SendAsync(
            request, HttpCompletionOption.ResponseHeadersRead, cancellation);
        if (!response.IsSuccessStatusCode)
            throw new IOException("WebDAV LOCK refresh failed: HTTP " +
                (int)response.StatusCode + ". Existing lease must be treated as unsafe.");
        return lease with { ExpiresUtc = Expiry(response) };
    }

    internal static async Task PutOwnedAsync(HttpClient client, Lease lease,
        Stream content, CancellationToken cancellation = default)
    {
        ValidateResource(lease.Resource);
        RequireLiveLease(lease);
        if (!content.CanRead) throw new ArgumentException("Unreadable upload stream");
        using var request = new HttpRequestMessage(HttpMethod.Put, lease.Resource);
        request.Headers.TryAddWithoutValidation("If", "(" + ValidateToken(lease.Token) + ")");
        request.Content = new StreamContent(content);
        using var response = await client.SendAsync(request, cancellation);
        if (!response.IsSuccessStatusCode)
            throw new IOException("WebDAV token-protected PUT failed: HTTP " +
                (int)response.StatusCode + ". The lock is NOT released.");
    }

    internal static async Task MoveIntoLockedDestinationAsync(
        HttpClient client, Uri source, Lease destination,
        CancellationToken cancellation = default)
    {
        ValidateResource(source);
        ValidateResource(destination.Resource);
        RequireLiveLease(destination);
        if (source == destination.Resource || source.GetLeftPart(UriPartial.Authority) !=
            destination.Resource.GetLeftPart(UriPartial.Authority))
            throw new ArgumentException("MOVE must remain on the same WebDAV server");
        using var request = new HttpRequestMessage(new HttpMethod("MOVE"), source);
        request.Headers.TryAddWithoutValidation(
            "Destination", destination.Resource.AbsoluteUri);
        request.Headers.TryAddWithoutValidation("Overwrite", "T");
        // A token for the locked DESTINATION needs a tagged If list. An
        // untagged token on the staging SOURCE may yield HTTP 412.
        request.Headers.TryAddWithoutValidation("If",
            "<" + destination.Resource.AbsoluteUri + "> (" +
            ValidateToken(destination.Token) + ")");
        using var response = await client.SendAsync(request, cancellation);
        if (response.StatusCode is not (HttpStatusCode.Created or HttpStatusCode.NoContent))
            throw new IOException("WebDAV token-protected MOVE failed: HTTP " +
                (int)response.StatusCode + ". Original retained by protocol client.");
    }

    internal static async Task ReleaseAsync(HttpClient client, Lease lease,
        CancellationToken cancellation = default)
    {
        ValidateResource(lease.Resource);
        using var request = new HttpRequestMessage(new HttpMethod("UNLOCK"),
            lease.Resource);
        request.Headers.TryAddWithoutValidation("Lock-Token", ValidateToken(lease.Token));
        using var response = await client.SendAsync(request, cancellation);
        if (!response.IsSuccessStatusCode)
            throw new IOException("WebDAV UNLOCK failed: HTTP " +
                (int)response.StatusCode + ". Never delete user documents or tokens.");
    }

    private static void ValidateResource(Uri resource)
    {
        if (!resource.IsAbsoluteUri || resource.Scheme is not ("http" or "https") ||
            resource.UserInfo.Length > 0 || resource.Fragment.Length > 0)
            throw new ArgumentException("Expected an absolute WebDAV HTTP(S) URL");
    }

    private static string ValidateToken(string value)
    {
        string token = value.Trim();
        if (token.Length < 5 || token.Length > 512 ||
            token[0] != '<' || token[^1] != '>' ||
            token[1..^1].Any(ch => !(char.IsLetterOrDigit(ch) ||
                ch is ':' or '/' or '-' or '_' or '.' or '%' or '+')))
            throw new IOException("Unsafe/malformed WebDAV lock token; write blocked.");
        return token;
    }

    private static void RequireLiveLease(Lease lease)
    {
        // Clock skew and network transfer latency can make a nearly-expired
        // token fail midway through PUT/MOVE. Stop BEFORE upload and require
        // a LOCK refresh rather than risk an unprotected overwrite.
        if (lease.ExpiresUtc <= DateTimeOffset.UtcNow.AddSeconds(10))
            throw new IOException("WebDAV lock expires too soon; refresh required before write.");
    }

    private static DateTimeOffset Expiry(HttpResponseMessage response)
    {
        // A server may shorten our requested lease. An unknown timeout
        // is deliberately treated as short-lived, requiring prompt refresh.
        if (response.Headers.TryGetValues("Timeout", out var values))
        {
            string raw = values.FirstOrDefault() ?? "";
            if (raw.StartsWith("Second-", StringComparison.OrdinalIgnoreCase) &&
                int.TryParse(raw[7..], out int seconds) && seconds > 0)
                return DateTimeOffset.UtcNow.AddSeconds(Math.Min(seconds, 600));
        }
        return DateTimeOffset.UtcNow.AddSeconds(30);
    }
}
