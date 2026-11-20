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
    internal sealed record Receipt(long Length, string Sha256,
        DateTimeOffset CreatedUtc, long Sequence);
    private sealed class History
    {
        internal readonly object Gate = new();
        internal readonly List<Receipt> Pending = [];
        internal readonly List<Receipt> Verified = [];
        internal DateTimeOffset LastSaveUtc;
        internal long NextSequence;
        internal long LatestConfirmedSequence;
    }
    private readonly ConcurrentDictionary<string, History> _histories =
        new(StringComparer.OrdinalIgnoreCase);
    private readonly Func<DateTimeOffset> _clock;
    // Synology/WebDAV may re-announce an unchanged workbook with a fresh
    // ETag well after rclone's delayed upload. Keep a bounded history while
    // an Office document is in use, so even a later server echo can be
    // checked by SHA-256 against our own saved bytes. This does NOT grant a
    // blanket ignore window: every differing server revision must be GETed
    // and hashed before an event can be suppressed.
    private static readonly TimeSpan ReceiptLifetime = TimeSpan.FromHours(2);
    private static readonly TimeSpan EchoWindow = TimeSpan.FromSeconds(20);
    private const int MaxReceipts = 24;

    internal LocalWorkbookSaveNotificationGuard(Func<DateTimeOffset>? clock = null) =>
        _clock = clock ?? (() => DateTimeOffset.UtcNow);

    private static void Prune(History history, DateTimeOffset now)
    {
        history.Pending.RemoveAll(x => now - x.CreatedUtc > ReceiptLifetime);
        history.Verified.RemoveAll(x => now - x.CreatedUtc > ReceiptLifetime);
        if (history.Pending.Count > MaxReceipts)
            history.Pending.RemoveRange(0, history.Pending.Count - MaxReceipts);
        if (history.Verified.Count > MaxReceipts)
            history.Verified.RemoveRange(0, history.Verified.Count - MaxReceipts);
    }

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
        var history = _histories.GetOrAdd(key, _ => new History());
        lock (history.Gate)
        {
            var now = _clock();
            Prune(history, now);
            history.Pending.Add(new Receipt(length, sha256.ToUpperInvariant(),
                now, ++history.NextSequence));
            history.LastSaveUtc = now;
            Prune(history, now);
        }
    }

    /// <summary>Every size-compatible save, including a previously verified
    /// revision re-announced under a new server ETag. The caller MUST GET
    /// the remote bytes and SHA-check them before ignoring a notification.</summary>
    internal IReadOnlyList<Receipt> Candidates(string fileName,
        string? oldVersion, string? currentVersion)
    {
        string key = Key(fileName);
        if (!_histories.TryGetValue(key, out var history) ||
            oldVersion == currentVersion ||
            !TryGetRegularFileLength(currentVersion, out long actualLength))
            return [];
        lock (history.Gate)
        {
            Prune(history, _clock());
            return history.Pending.Concat(history.Verified)
                .Where(x => x.Length == actualLength)
                .OrderByDescending(x => x.Sequence)
                .ToArray();
        }
    }

    internal bool MayBeOurSave(string fileName, string? oldVersion,
        string? currentVersion, out Receipt receipt)
    {
        receipt = Candidates(fileName, oldVersion, currentVersion).FirstOrDefault()!;
        return receipt is not null;
    }

    /// <summary>Bounded protection while our own upload settles. A genuine
    /// remote edit/deletion will be emitted after at most 20 seconds unless
    /// it matches a locally saved SHA-256.</summary>
    internal bool ShouldDeferUnverified(string fileName)
    {
        if (!_histories.TryGetValue(Key(fileName), out var history)) return false;
        lock (history.Gate)
        {
            var now = _clock();
            Prune(history, now);
            return now - history.LastSaveUtc <= EchoWindow &&
                (history.Pending.Count > 0 || history.Verified.Count > 0);
        }
    }

    internal bool Acknowledge(string fileName, Receipt receipt)
    {
        if (!_histories.TryGetValue(Key(fileName), out var history)) return false;
        lock (history.Gate)
        {
            Prune(history, _clock());
            // An OLD upload arriving after a NEWER upload was already
            // confirmed is a real server rollback, not a harmless local
            // ETag echo. Keep it visible to Excel so data loss is not hidden.
            if (receipt.Sequence < history.LatestConfirmedSequence)
                return false;
            if (history.Pending.Remove(receipt))
            {
                history.Verified.Add(receipt);
                history.LatestConfirmedSequence = receipt.Sequence;
                Prune(history, _clock());
                return true;
            }
            if (!history.Verified.Contains(receipt)) return false;
            history.LatestConfirmedSequence = receipt.Sequence;
            return true;
        }
    }

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
        DateTimeOffset now = new(2026, 11, 20, 0, 0, 0, TimeSpan.Zero);
        var guard = new LocalWorkbookSaveNotificationGuard(() => now);
        string hash = new string('A', 64);
        string otherHash = new string('B', 64);
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
        // A, B can have the SAME size but different content. They must both
        // stay eligible for independent SHA-256 verification.
        guard.Record(document, 1234, otherHash);
        var receipts = guard.Candidates(document, before, after);
        if (receipts.Count != 2 || receipts[0].Sha256 != otherHash ||
            receipts[1].Sha256 != hash || !guard.Acknowledge(document, ticket))
            throw new Exception("Older successful Excel Save was forgotten");
        if (guard.Candidates(document, after,
                "F|\"server-etag-changed-again\"|1234|now").Count != 2 ||
            !guard.ShouldDeferUnverified(document) ||
            !guard.Acknowledge(document, receipts[0]))
            throw new Exception("Repeated server echo not accounted for");
        // Six more Ctrl+S operations before the NAS exposes any of them.
        // A delayed first/second upload must remain identifiable without
        // replacing the third/fourth/fifth/sixth save's provenance.
        for (int n = 0; n < 6; n++)
        {
            now += TimeSpan.FromMilliseconds(50);
            guard.Record(document, 1234, new string((char)('0' + n), 64));
        }
        var rapid = guard.Candidates(document, before, after);
        if (rapid.Count != 8 ||
            rapid[0].Sha256 != new string('5', 64) ||
            rapid[^1].Sha256 != hash)
            throw new Exception("Rapid repeated saves overwrote previous receipts");
        // Simulated server publishes 0..5 in order after A and B. Reject a
        // rollback to B once the newest (5) was already confirmed.
        foreach (var receipt in rapid.Where(x => x.Sequence > receipts[0].Sequence)
                     .OrderBy(x => x.Sequence))
            if (!guard.Acknowledge(document, receipt))
                throw new Exception("Newer Excel save acknowledgement was lost");
        if (guard.Acknowledge(document, ticket) ||
            guard.Acknowledge(document, receipts[0]))
            throw new Exception("Older remote workbook rollback was falsely suppressed");
        // Polling during the remote original-to-backup interval must retain
        // the stable baseline for the affected workbook only. Other files'
        // remote changes must remain visible to Windows.
        var stable = new Dictionary<string, string>(
            StringComparer.OrdinalIgnoreCase) {
            ["11월매출.xlsx"] = before, ["another.xlsx"] = before
        };
        var remoteMidSave = new Dictionary<string, string>(
            StringComparer.OrdinalIgnoreCase) {
            ["another.xlsx"] = after
        };
        RemoteDirectoryChangeMonitor.PreserveBaselineForDeferredChange(
            remoteMidSave, stable, "11월매출.xlsx");
        if (remoteMidSave["11월매출.xlsx"] != before ||
            remoteMidSave["another.xlsx"] != after ||
            RemoteDirectoryChangeMonitor.Diff("folder", stable, remoteMidSave)
                .Length != 1)
            throw new Exception("Transient own-save removal reset the stable directory baseline");
        var originallyAbsent = new Dictionary<string, string>(
            StringComparer.OrdinalIgnoreCase);
        var remoteNew = new Dictionary<string, string>(
            StringComparer.OrdinalIgnoreCase) { ["11월매출.xlsx"] = after };
        RemoteDirectoryChangeMonitor.PreserveBaselineForDeferredChange(
            remoteNew, originallyAbsent, "11월매출.xlsx");
        if (remoteNew.ContainsKey("11월매출.xlsx"))
            throw new Exception("Transient add was wrongly exposed as an external file");
        // A real remote change must never remain hidden indefinitely.
        now += TimeSpan.FromSeconds(21);
        if (guard.ShouldDeferUnverified(document))
            throw new Exception("External edit was deferred beyond safe echo window");
        // Content-matched verified receipts may still suppress an ETag echo,
        // but a DIFFERENT SHA can NEVER be identified as local by the
        // independent remote SHA comparison done by the monitor.
        now += TimeSpan.FromHours(2).Add(TimeSpan.FromMinutes(1));
        if (guard.Candidates(document, before, after).Count != 0 ||
            guard.Acknowledge(document, ticket))
            throw new Exception("Expired local receipts were reused");
        Console.WriteLine("LOCAL_EXCEL_REPEAT_SAVE_ECHO_POLICY_PASS");
        Console.WriteLine("LOCAL_EXCEL_SAVE_NOTIFICATION_PROVENANCE_PASS");
    }
}
