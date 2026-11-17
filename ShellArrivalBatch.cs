namespace NASMount;

/// <summary>
/// A connected WinFsp drive is usable immediately. Only its cosmetic Windows
/// Shell arrival notifications are gathered during a bounded parallel connect.
/// The deadline prevents a slow cloud service from holding all other icons.
/// </summary>
internal sealed class ShellArrivalBatch : IDisposable
{
    private readonly System.Windows.Forms.Timer deadline = new() { Interval = 9000 };
    private readonly System.Windows.Forms.Timer settled = new() { Interval = 650 };
    private readonly Action<IReadOnlyList<NasProfile>, string> dispatch;
    private readonly HashSet<Guid> expected = [];
    private readonly Dictionary<Guid, NasProfile> pending = [];
    private bool active;
    internal bool Active => active;
    internal int PendingCount => pending.Count;
    internal int DispatchCount { get; private set; }

    internal ShellArrivalBatch(Action<IReadOnlyList<NasProfile>, string> callback)
    {
        dispatch = callback;
        deadline.Tick += (_, _) => DispatchNow("deadline");
        settled.Tick += (_, _) => DispatchNow("complete");
    }

    internal bool Begin(IEnumerable<NasProfile> profiles)
    {
        if (active) return false;
        expected.Clear();
        pending.Clear();
        foreach (var p in profiles)
            if (p.Protocol != "SMB") expected.Add(p.Id);
        if (expected.Count == 0) return false;
        active = true;
        deadline.Start();
        return true;
    }

    internal bool Queue(NasProfile profile)
    {
        if (!active || !expected.Contains(profile.Id)) return false;
        pending[profile.Id] = profile;
        return true;
    }

    internal void Completed()
    {
        if (!active) return;
        // Some network UNC MountPoints2 names arrive a fraction of a second
        // after the verified volume. Allow that label registration to settle
        // before publishing the whole set to the Windows Shell once.
        settled.Stop();
        settled.Start();
    }

    internal void DispatchNow(string reason)
    {
        if (!active) return;
        deadline.Stop();
        settled.Stop();
        active = false;
        var profiles = pending.Values.ToArray();
        pending.Clear();
        expected.Clear();
        if (profiles.Length == 0) return;
        DispatchCount++;
        dispatch(profiles, reason);
    }

    public void Dispose()
    {
        deadline.Dispose();
        settled.Dispose();
    }

    internal static void PolicyTest()
    {
        var events = new List<(int Count, string Cause)>();
        using var test = new ShellArrivalBatch((p, reason) =>
            events.Add((p.Count, reason)));
        var a = new NasProfile { Protocol = "WebDAV" };
        var b = new NasProfile { Protocol = "GoogleDrive" };
        var unrelated = new NasProfile { Protocol = "OneDrive" };
        if (!test.Begin([a, b]) || !test.Queue(a) || !test.Queue(b) ||
            test.Queue(unrelated) || test.PendingCount != 2 ||
            test.DispatchCount != 0)
            throw new InvalidOperationException("Shell batch failed to collect two mounts");
        test.Queue(a);
        test.Completed();
        test.DispatchNow("complete");
        if (events.Count != 1 || events[0] != (2, "complete") ||
            test.Active || test.PendingCount != 0 || test.Queue(a))
            throw new InvalidOperationException("Shell batch emitted per-mount events");
        if (!test.Begin([a, b]) || !test.Queue(a))
            throw new InvalidOperationException("Second Shell batch not accepted");
        test.DispatchNow("deadline");
        if (events.Count != 2 || events[1] != (1, "deadline"))
            throw new InvalidOperationException("Shell batch deadline lost completed mounts");
        Console.WriteLine("SHELL_BATCH_ARRIVAL_ONLY_ONCE_AND_DEADLINE_POLICY_PASS");
    }
}
