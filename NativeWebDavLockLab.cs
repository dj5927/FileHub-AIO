using System.Net;
using System.Net.Http;
using System.Text;

namespace NASMount;

/// <summary>
/// Deterministic protocol fixture. HTTP calls are intercepted in memory:
/// no production WebDAV service, mount, documents or account is touched.
/// </summary>
internal static class NativeWebDavLockLab
{
    private static int _checks;

    internal static async Task RunAsync()
    {
        _checks = 0;
        var server = new FakeDavHandler();
        using var clientA = new HttpClient(server, disposeHandler: false);
        using var clientB = new HttpClient(server, disposeHandler: false);
        var doc = new Uri("http://127.0.0.1:22481/lab/report.xlsx");
        var stage = new Uri("http://127.0.0.1:22481/lab/report.tmp");
        var lease = await NativeWebDavLockProtocol.AcquireAsync(clientA, doc);
        Check(server.Locked && server.LockCount == 1, "initial RFC4918 LOCK");
        Check(lease.Token.StartsWith("<opaquelocktoken:", StringComparison.Ordinal),
            "server-supplied opaque lock token");

        await ExpectLocked(async () =>
            await NativeWebDavLockProtocol.AcquireAsync(clientB, doc));
        Check(server.LockCount == 1, "second client conflict");

        byte[] initial = server.Document.ToArray();
        using (var request = new HttpRequestMessage(HttpMethod.Put, doc))
        {
            request.Content = new ByteArrayContent("UNSAFE"u8.ToArray());
            using var result = await clientB.SendAsync(request);
            Check(result.StatusCode == HttpStatusCode.Locked,
                "unlocked PUT blocked by the test server");
        }
        Check(server.Document.SequenceEqual(initial), "failed writer did not modify file");

        var expired = lease with {
            ExpiresUtc = DateTimeOffset.UtcNow.AddSeconds(3)
        };
        await ExpectLocked(async () =>
        {
            await using var unsafePayload = new MemoryStream("EXPIRED"u8.ToArray());
            await NativeWebDavLockProtocol.PutOwnedAsync(
                clientA, expired, unsafePayload);
        });
        await ExpectLocked(async () =>
            await NativeWebDavLockProtocol.MoveIntoLockedDestinationAsync(
                clientA, stage, expired));
        Check(server.Document.SequenceEqual(initial),
            "almost expired lease cannot change original");

        await using (var payload = new MemoryStream("Edited by A"u8.ToArray()))
            await NativeWebDavLockProtocol.PutOwnedAsync(clientA, lease, payload);
        Check(Encoding.UTF8.GetString(server.Document) == "Edited by A",
            "token-protected PUT succeeded");

        lease = await NativeWebDavLockProtocol.RefreshAsync(clientA, lease);
        Check(server.RefreshCount == 1 && lease.ExpiresUtc > DateTimeOffset.UtcNow,
            "bodyless LOCK refresh with If token");

        using (var request = new HttpRequestMessage(HttpMethod.Put, stage))
        {
            request.Content = new ByteArrayContent("Office rename result"u8.ToArray());
            using var response = await clientA.SendAsync(request);
            Check(response.IsSuccessStatusCode, "disposable Excel staging upload");
        }
        using (var request = new HttpRequestMessage(new HttpMethod("MOVE"), stage))
        {
            request.Headers.TryAddWithoutValidation("Destination", doc.AbsoluteUri);
            request.Headers.TryAddWithoutValidation("Overwrite", "T");
            using var response = await clientB.SendAsync(request);
            Check(response.StatusCode is HttpStatusCode.Locked or
                HttpStatusCode.PreconditionFailed, "unowned MOVE blocked");
        }
        Check(Encoding.UTF8.GetString(server.Document) == "Edited by A",
            "rejected rename preserved original");
        await NativeWebDavLockProtocol.MoveIntoLockedDestinationAsync(
            clientA, stage, lease);
        Check(Encoding.UTF8.GetString(server.Document) == "Office rename result",
            "tagged destination If token allows Excel-style MOVE");

        await NativeWebDavLockProtocol.ReleaseAsync(clientA, lease);
        Check(!server.Locked && server.UnlockCount == 1,
            "owner UNLOCK without any server directory deletion");
        var next = await NativeWebDavLockProtocol.AcquireAsync(clientB, doc);
        Check(server.Locked && server.LockCount == 2,
            "second PC can acquire only after release");
        await NativeWebDavLockProtocol.ReleaseAsync(clientB, next);
        Check(!server.Locked, "second client clean release");
        Check(server.SidecarCalls == 0, "never create lock folders or sidecar files");

        var unsupported = new FakeDavHandler { SupportsLocks = false };
        using (var rejectedClient = new HttpClient(unsupported))
        {
            await ExpectLocked(async () =>
                await NativeWebDavLockProtocol.AcquireAsync(rejectedClient, doc));
            Check(!unsupported.Locked && unsupported.Document.SequenceEqual(
                "Existing workbook"u8.ToArray()),
                "unsupported LOCK fails closed and preserves original");
        }
        var malformed = new FakeDavHandler { ReturnInvalidToken = true };
        using (var rejectedClient = new HttpClient(malformed))
        {
            await ExpectLocked(async () =>
                await NativeWebDavLockProtocol.AcquireAsync(rejectedClient, doc));
            Check(malformed.SidecarCalls == 0,
                "malformed tokens cannot trigger a sidecar fallback");
        }
        Console.WriteLine("NATIVE_WEBDAV_ZERO_SIDECAR_PROTOCOL_LAB_PASS " +
            "checks=" + _checks + " no_user_files_touched=true " +
            "production_integration=false");
    }

    private static async Task ExpectLocked(Func<Task> action)
    {
        try { await action(); }
        catch (IOException) { _checks++; return; }
        throw new InvalidOperationException("An unsafe request did not fail closed");
    }

    private static void Check(bool condition, string message)
    {
        if (!condition) throw new InvalidOperationException("LAB FAIL: " + message);
        _checks++;
    }

    private sealed class FakeDavHandler : HttpMessageHandler
    {
        internal bool SupportsLocks = true;
        internal bool ReturnInvalidToken;
        internal bool Locked;
        internal int LockCount;
        internal int RefreshCount;
        internal int UnlockCount;
        internal int SidecarCalls;
        internal byte[] Document = "Existing workbook"u8.ToArray();
        private byte[]? _staged;
        private string? _token;

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            string method = request.Method.Method.ToUpperInvariant();
            string path = request.RequestUri?.AbsolutePath ?? "";
            if (path.Contains(".nasmount.lock", StringComparison.OrdinalIgnoreCase) ||
                method == "MKCOL" || path.EndsWith("/owner.json", StringComparison.OrdinalIgnoreCase))
            {
                SidecarCalls++;
                return new HttpResponseMessage(HttpStatusCode.Forbidden);
            }
            bool supplied = Header(request, "If").Contains(
                _token ?? "", StringComparison.Ordinal);
            if (method == "LOCK")
            {
                if (!SupportsLocks) return Reply(HttpStatusCode.MethodNotAllowed);
                if (request.Content is null)
                {
                    if (!Locked || !supplied)
                        return Reply(HttpStatusCode.PreconditionFailed);
                    RefreshCount++;
                    return NewLeaseResponse();
                }
                if (Locked) return Reply(HttpStatusCode.Locked);
                Locked = true;
                LockCount++;
                _token = "<opaquelocktoken:" + Guid.NewGuid().ToString("N") + ">";
                return NewLeaseResponse();
            }
            if (method == "UNLOCK")
            {
                if (!Locked || Header(request, "Lock-Token") != _token)
                    return Reply(HttpStatusCode.Conflict);
                Locked = false;
                _token = null;
                UnlockCount++;
                return Reply(HttpStatusCode.NoContent);
            }
            if (method == "PUT" && path.EndsWith("/report.xlsx"))
            {
                if (Locked && !supplied) return Reply(HttpStatusCode.Locked);
                Document = request.Content is null ? [] :
                    await request.Content.ReadAsByteArrayAsync(cancellationToken);
                return Reply(HttpStatusCode.NoContent);
            }
            if (method == "PUT" && path.EndsWith("/report.tmp"))
            {
                _staged = request.Content is null ? [] :
                    await request.Content.ReadAsByteArrayAsync(cancellationToken);
                return Reply(HttpStatusCode.Created);
            }
            if (method == "MOVE" && path.EndsWith("/report.tmp"))
            {
                string tagged = "<http://127.0.0.1:22481/lab/report.xlsx> (" +
                    _token + ")";
                if (Locked && !Header(request, "If").Contains(
                    tagged, StringComparison.Ordinal))
                    return Reply(HttpStatusCode.Locked);
                if (Header(request, "Destination") !=
                    "http://127.0.0.1:22481/lab/report.xlsx" || _staged is null)
                    return Reply(HttpStatusCode.BadRequest);
                Document = _staged;
                _staged = null;
                return Reply(HttpStatusCode.NoContent);
            }
            return Reply(HttpStatusCode.NotImplemented);
        }

        private HttpResponseMessage NewLeaseResponse()
        {
            var response = Reply(HttpStatusCode.OK);
            response.Headers.TryAddWithoutValidation("Lock-Token",
                ReturnInvalidToken ? "<bad\r\ninjected>" : _token);
            response.Headers.TryAddWithoutValidation("Timeout", "Second-600");
            return response;
        }

        private static string Header(HttpRequestMessage request, string key) =>
            request.Headers.TryGetValues(key, out var values) ?
                values.FirstOrDefault() ?? "" : "";

        private static HttpResponseMessage Reply(HttpStatusCode code) => new(code);
    }
}
