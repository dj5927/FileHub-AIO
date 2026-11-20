using System.Collections.Concurrent;
using System.Diagnostics;

namespace NASMount.LockBridge;

/// <summary>
/// Windows Explorer/PE icon extraction asks GetSecurityByName many times for
/// the same executable. A passthrough-of-a-WinFsp mount turns each request
/// into another synchronous rclone metadata operation. Cache the EXACT
/// attributes and optional ACL already returned by the lower filesystem for
/// three seconds; never synthesize permissions or apply to Office documents.
/// </summary>
internal sealed class ExecutableSecurityMetadataCache
{
    private sealed record Entry(uint Attributes, byte[]? Descriptor, long ExpiresAt);
    private readonly ConcurrentDictionary<string, Entry> _items =
        new(StringComparer.OrdinalIgnoreCase);
    private static readonly long LifetimeTicks = 3 * Stopwatch.Frequency;

    internal bool TryGet(string path, bool needAcl,
        out uint attributes, out byte[]? descriptor)
    {
        attributes = 0;
        descriptor = null;
        if (!_items.TryGetValue(path, out var e)) return false;
        if (Stopwatch.GetTimestamp() >= e.ExpiresAt)
        {
            _items.TryRemove(path, out _);
            return false;
        }
        if (needAcl && e.Descriptor is null) return false;
        attributes = e.Attributes;
        descriptor = e.Descriptor is null ? null : (byte[])e.Descriptor.Clone();
        return true;
    }

    internal void Store(string path, uint attributes, byte[]? descriptor)
    {
        if (_items.Count >= 2048) _items.Clear();
        // A descriptor-free re-query must never erase an earlier ACL.
        if (descriptor is null && _items.TryGetValue(path, out var previous) &&
            Stopwatch.GetTimestamp() < previous.ExpiresAt)
            descriptor = previous.Descriptor;
        _items[path] = new Entry(attributes,
            descriptor is null ? null : (byte[])descriptor.Clone(),
            Stopwatch.GetTimestamp() + LifetimeTicks);
    }

    internal void Invalidate(string path) => _items.TryRemove(path, out _);

    internal static void PolicyTest()
    {
        var c = new ExecutableSecurityMetadataCache();
        const string path = @"\samples\MultiDownloader.exe";
        if (c.TryGet(path, false, out _, out _))
            throw new InvalidOperationException("Executable metadata cache starts nonempty");
        c.Store(path, 123, null);
        if (!c.TryGet(path.ToUpperInvariant(), false, out var attr, out _) || attr != 123)
            throw new InvalidOperationException("Cached executable attributes unavailable");
        if (c.TryGet(path, true, out _, out _))
            throw new InvalidOperationException("A missing ACL was invented");
        byte[] acl = [1, 2, 3];
        c.Store(path, 234, acl);
        acl[0] = 42;
        if (!c.TryGet(path, true, out attr, out var copied) ||
            attr != 234 || copied is null || copied[0] != 1)
            throw new InvalidOperationException("Executable ACL cache lost immutability");
        copied[0] = 88;
        if (!c.TryGet(path, true, out _, out copied) || copied![0] != 1)
            throw new InvalidOperationException("Executable ACL cache leaked mutable data");
        c.Store(path, 234, null);
        if (!c.TryGet(path, true, out _, out copied) || copied![0] != 1)
            throw new InvalidOperationException("Attribute refresh erased cached ACL");
        c.Invalidate(path);
        if (c.TryGet(path, false, out _, out _))
            throw new InvalidOperationException("Executable cache invalidation failed");
        Console.WriteLine("WINFSP_EXECUTABLE_SECURITY_METADATA_COALESCED_PASS");
    }
}
