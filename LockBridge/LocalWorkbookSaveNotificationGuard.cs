using System.Collections.Concurrent;

namespace NASMount.LockBridge;

/// <summary>
/// Associates a successful Excel temp-to-original rename with the exact bytes
/// produced by this WinFsp mount. Only a verified matching WebDAV revision may
/// be suppressed from remote-change notifications. An unrelated remote write,
/// a failed verification, or an untracked path must still reach Windows.
/// </summary>
internal sealed class LocalWorkbookSaveNotificationGuard
{
    internal sealed record Receipt(long Length, string Sha256, DateTimeOffset CreatedUtc);
    private readonly ConcurrentDictionary<string, Receipt> _pending =
        new(StringComparer.OrdinalIgnoreCase);

    private static string Key(string fileName) =>
        fileName.Replace('\\', '/').TrimStart('/');

    internal void Record(string fileName, long length, string sha256)
    {
        string key = Key(fileName);
        if (length < 0 || key.Length == 0 ||
            !WriteIntent.IsDocument("\\" + key) ||
            sha256.Length != 64 ||
            !sha256.All(Uri.IsHexDigit))
            throw new ArgumentException("Expected an authenticated local Office payload");
        _pending[key] = new Receipt(length, sha256.ToUpperInvariant(),
            DateTimeOffset.UtcNow);
    }

    internal bool MayBeOurSave(string fileName, string? oldVersion,
        string? currentVersion, out Receipt receipt)
    {
        string key = Key(fileName);
        receipt = null!;
        if (!_pending.TryGetValue(key, out var candidate)) return false;
        // Old receipts must never mask an unrelated edit made much later.
        if (DateTimeOffset.UtcNow - candidate.CreatedUtc > TimeSpan.FromMinutes(2))
        {
            _pending.TryRemove(new KeyValuePair<string, Receipt>(key, candidate));
            return false;
        }
        // Only a MODIFIED, same-path, regular-file revision is eligible.
        // Remote REMOVED/ADDED/REPLACED or unknown length always propagates.
        if (oldVersion is null || currentVersion is null ||
            oldVersion == currentVersion ||
            !TryGetRegularFileLength(oldVersion, out _) ||
            !TryGetRegularFileLength(currentVersion, out long actualLength) ||
            actualLength != candidate.Length)
            return false;
        receipt = candidate;
        return true;
    }

    internal bool Acknowledge(string fileName, Receipt receipt) =>
        _pending.TryRemove(new KeyValuePair<string, Receipt>(Key(fileName), receipt));

    internal static bool TryGetRegularFileLength(string? signature, out long length)
    {
        length = -1;
        if (signature is null || !signature.StartsWith("F|", StringComparison.Ordinal))
            return false;
        // PROPFIND snapshot: F|ETag|ContentLength|LastModified.
        // ETags may contain arbitrary quoted strings, but not '|' on the
        // supported NAS WebDAV backend. Never guess when ambiguous.
        string[] parts = signature.Split('|', 4);
        return parts.Length == 4 &&
            long.TryParse(parts[2], out length) && length >= 0;
    }

    internal static void PolicyTest()
    {
        const string document = @"\folder\11월매출.xlsx";
        const string before = "F|\"old\"|1000|Mon, 17 Nov 2026 06:01:00 GMT";
        const string after = "F|\"mine\"|1234|Mon, 17 Nov 2026 06:02:00 GMT";
        var guard = new LocalWorkbookSaveNotificationGuard();
        string hash = new string('A', 64);
        if (guard.MayBeOurSave(document, before, after, out _))
            throw new Exception("Unregistered remote edit was suppressed");
        guard.Record(document, 1234, hash);
        if (!guard.MayBeOurSave(@"\FOLDER\11월매출.xlsx", before, after,
                out var ticket) || ticket.Sha256 != hash ||
            guard.MayBeOurSave(document, before, "F|\"other\"|1235|now", out _) ||
            guard.MayBeOurSave(document, before, "D|\"mine\"|1234|now", out _) ||
            guard.MayBeOurSave(document, before, null, out _) ||
            guard.MayBeOurSave(@"\folder\other.xlsx", before, after, out _))
            throw new Exception("Local workbook notification provenance policy failed");
        // A hash mismatch is never acknowledged. Only a successful remote
        // SHA-256 verification is allowed to call Acknowledge.
        if (!guard.MayBeOurSave(document, before, after, out _) ||
            !guard.Acknowledge(document, ticket) ||
            guard.MayBeOurSave(document, after,
                "F|\"remote-new\"|1234|now", out _))
            throw new Exception("Local workbook notification was hidden twice");
        // If the user saves again before the first upload arrives, the older
        // acknowledgement must not consume the newly saved revision.
        guard.Record(document, 2234, hash);
        guard.MayBeOurSave(document, before,
            "F|\"new\"|2234|now", out var oldTicket);
        guard.Record(document, 3333, hash);
        if (guard.Acknowledge(document, oldTicket) ||
            !guard.MayBeOurSave(document, before,
                "F|\"newer\"|3333|now", out _))
            throw new Exception("Newer local save receipt was lost");
        Console.WriteLine("LOCAL_EXCEL_SAVE_NOTIFICATION_PROVENANCE_PASS");
    }
}
