using System.Net.Http;
using System.Text;
using System.Text.Json;

namespace NASMount.LockBridge;

/// <summary>Authenticated per-file WebDAV lock control over loopback.</summary>
internal sealed class NativeWebDavLockClient : IDisposable
{
    private readonly HttpClient http;
    private readonly Uri controlBase;
    private readonly string secret;

    internal NativeWebDavLockClient(string url, string secret)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var endpoint) ||
            endpoint.Scheme != "http" || !endpoint.IsLoopback ||
            endpoint.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries)
                is not { Length: 2 } parts ||
            parts[0].Length < 24 || parts[1] != "__native_lock_control" ||
            secret.Length != 64 || secret.Any(ch => !Uri.IsHexDigit(ch)))
            throw new IOException("Unsafe native WebDAV lock control endpoint");
        controlBase = endpoint;
        this.secret = secret;
        http = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
            { Timeout = TimeSpan.FromSeconds(18) };
    }

    internal string Acquire(string relative) => Command("acquire", relative, "").Id;
    internal void Release(string relative, string id) =>
        _ = Command("release", relative, id);

    private ControlResponse Command(string action, string path, string id)
    {
        if (path.Length is 0 or > 2048 || path.Contains("..", StringComparison.Ordinal) ||
            path.IndexOfAny(['\r','\n','\0']) >= 0 || path.StartsWith('/'))
            throw new IOException("Unsafe document path for native WebDAV lease");
        using var request = new HttpRequestMessage(HttpMethod.Post,
            new Uri(controlBase, action));
        request.Headers.TryAddWithoutValidation("X-NASMount-Control", secret);
        request.Content = new StringContent(
            JsonSerializer.Serialize(new { Path = path, Id = id }),
            Encoding.UTF8, "application/json");
        using var reply = http.Send(request);
        if (!reply.IsSuccessStatusCode)
            throw new IOException("Native WebDAV lock " + action + " denied HTTP " +
                (int)reply.StatusCode + "; document write blocked");
        var response = JsonSerializer.Deserialize<ControlResponse>(
            reply.Content.ReadAsStringAsync().GetAwaiter().GetResult());
        if (response is not { Success: true } || response.Id.Length != 32)
            throw new IOException("Invalid native WebDAV lock control response");
        if (action == "release" && response.Id != id)
            throw new IOException("Native lock release owner mismatch");
        return response;
    }

    private sealed record ControlResponse(string Id, DateTimeOffset ExpiresUtc, bool Success);
    public void Dispose() => http.Dispose();
}
