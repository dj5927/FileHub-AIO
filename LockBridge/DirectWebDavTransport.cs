using System.Net.Http.Headers;

namespace NASMount.LockBridge;

/// <summary>
/// Convert network failures into recoverable filesystem IO errors. Only
/// read-only requests may be repeated on a dropped HTTP connection; never
/// blindly replay PUT, MOVE, DELETE or MKCOL after an uncertain response.
/// </summary>
internal static class DirectWebDavTransport
{
    internal static bool IsIoFailure(Exception ex) =>
        ex is IOException or HttpRequestException or OperationCanceledException or TimeoutException;

    internal static HttpResponseMessage Send(HttpClient client, HttpRequestMessage request,
        HttpCompletionOption completion = HttpCompletionOption.ResponseContentRead)
    {
        bool readOnly = request.Method == HttpMethod.Get ||
            request.Method == HttpMethod.Head ||
            request.Method.Method.Equals("PROPFIND", StringComparison.OrdinalIgnoreCase);
        try
        {
            try { return client.Send(request, completion); }
            catch (HttpRequestException ex) when (readOnly && ex.StatusCode is null &&
                ex.HttpRequestError is HttpRequestError.ConnectionError or
                    HttpRequestError.ResponseEnded or HttpRequestError.NameResolutionError)
            {
                // Safe metadata/read retry on a fresh connection. Preserve
                // the original conditional and Range headers exactly.
                using var retry = new HttpRequestMessage(request.Method, request.RequestUri)
                {
                    Version = request.Version, VersionPolicy = request.VersionPolicy
                };
                foreach (var header in request.Headers)
                    retry.Headers.TryAddWithoutValidation(header.Key, header.Value);
                retry.Headers.ConnectionClose = true;
                if (request.Content is { } content)
                {
                    retry.Content = new ByteArrayContent(content.ReadAsByteArrayAsync()
                        .GetAwaiter().GetResult());
                    foreach (var header in content.Headers)
                        retry.Content.Headers.TryAddWithoutValidation(header.Key, header.Value);
                }
                Console.WriteLine("DIRECT_WEBDAV_READ_RECONNECT_RETRY method=" +
                    request.Method.Method + " reason=" + ex.HttpRequestError);
                Thread.Sleep(150);
                return client.Send(retry, completion);
            }
        }
        catch (Exception ex) when (ex is HttpRequestException or OperationCanceledException
            or TimeoutException)
        {
            Console.Error.WriteLine("DIRECT_WEBDAV_TRANSPORT_UNAVAILABLE method=" +
                request.Method.Method + " type=" + ex.GetType().Name +
                " mountRetained=true");
            throw new IOException("WebDAV transport unavailable; reconnect on next request; " +
                "unverified writes retained locally", ex);
        }
    }
}
