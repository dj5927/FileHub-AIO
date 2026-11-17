using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;

namespace NASMount.LockBridge;

/// <summary>
/// A verified WebDAV staging transaction. Never deletes the old target before
/// the new payload is on the server. This helper is NOT yet wired to WinFsp.
/// </summary>
internal static class WebDavAtomicReplace
{
    internal static async Task ReplaceAsync(HttpClient client, Uri directory,
        string name, Stream payload, CancellationToken ct = default)
    {
        if (!directory.IsAbsoluteUri || directory.Scheme is not ("http" or "https") ||
            name is "" or "." or ".." || name.IndexOfAny(new[] { '/', '\\', '\r', '\n' }) >= 0 ||
            !payload.CanRead || !payload.CanSeek)
            throw new ArgumentException("Invalid WebDAV atomic-replace parameters");

        byte[] hash;
        long start = payload.Position;
        long length = payload.Length - start;
        hash = await SHA256.HashDataAsync(payload, ct);
        payload.Position = start;

        string stageName = ".nasmount-stage-" + Guid.NewGuid().ToString("N");
        Uri stage = new(directory, Uri.EscapeDataString(stageName));
        Uri target = new(directory, Uri.EscapeDataString(name));
        bool committed = false;
        try
        {
            using (var put = new HttpRequestMessage(HttpMethod.Put, stage))
            {
                put.Content = new StreamContent(payload);
                using var result = await client.SendAsync(put, HttpCompletionOption.ResponseHeadersRead, ct);
                result.EnsureSuccessStatusCode();
            }

            // Verify that the staged object is complete before modifying target.
            using (var get = await client.GetAsync(stage, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                get.EnsureSuccessStatusCode();
                if (get.Content.Headers.ContentLength is long remoteLength && remoteLength != length)
                    throw new IOException("Staged WebDAV payload length mismatch");
                await using var stream = await get.Content.ReadAsStreamAsync(ct);
                byte[] remoteHash = await SHA256.HashDataAsync(stream, ct);
                if (!CryptographicOperations.FixedTimeEquals(hash, remoteHash))
                    throw new IOException("Staged WebDAV checksum mismatch");
            }

            // RFC 4918 MOVE with Overwrite=T is the only operation that replaces
            // the visible original; no DELETE of the original is sent.
            using (var move = new HttpRequestMessage(new HttpMethod("MOVE"), stage))
            {
                move.Headers.TryAddWithoutValidation("Destination", target.AbsoluteUri);
                move.Headers.TryAddWithoutValidation("Overwrite", "T");
                using var result = await client.SendAsync(move, ct);
                if (result.StatusCode is not (HttpStatusCode.Created or HttpStatusCode.NoContent))
                    throw new IOException("Atomic WebDAV MOVE rejected: HTTP " + (int)result.StatusCode);
            }
            committed = true;

            // Confirm the target's contents, not just its existence.
            using (var final = await client.GetAsync(target, HttpCompletionOption.ResponseHeadersRead, ct))
            {
                final.EnsureSuccessStatusCode();
                await using var body = await final.Content.ReadAsStreamAsync(ct);
                byte[] finalHash = await SHA256.HashDataAsync(body, ct);
                if (!CryptographicOperations.FixedTimeEquals(hash, finalHash))
                    throw new IOException("Post-commit WebDAV checksum mismatch: investigate before unlocking");
            }
        }
        finally
        {
            // Only unique staging object may be cleaned; never delete an
            // existing user document as part of error recovery.
            try
            {
                using var result = await client.DeleteAsync(stage, CancellationToken.None);
                if (result.StatusCode != HttpStatusCode.NotFound && !result.IsSuccessStatusCode)
                    NASMount.AppStore.Log("ATOMIC_STAGE_CLEANUP_FAILED HTTP=" + (int)result.StatusCode);
            }
            catch (Exception e) { NASMount.AppStore.Log("ATOMIC_STAGE_CLEANUP_FAILED " + e.Message); }
        }
    }
}
