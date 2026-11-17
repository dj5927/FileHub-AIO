namespace NASMount;

/// <summary>
/// Coalesces expensive desktop-wide SHCNE_ASSOCCHANGED and Explorer F5.
/// Per-drive mount/arrival messages still fire immediately, so each drive
/// stays discoverable and independent. Only best-effort Shell presentation
/// is delayed (no backend, VFS upload or file lock is ever delayed).
/// </summary>
internal sealed class ExplorerRefreshCoalescer : IDisposable
{
    private readonly System.Windows.Forms.Timer _timer = new()
    {
        Interval = 900
    };
    private readonly Action<string> _flush;
    private int _pendingCount;
    private string _cause = "";
    internal int Requests { get; private set; }
    internal int Flushes { get; private set; }
    internal bool Pending => _timer.Enabled;

    internal ExplorerRefreshCoalescer(Action<string> flush)
    {
        _flush = flush;
        _timer.Tick += (_, _) => Flush();
    }
    internal void Request(string cause)
    {
        Requests++;
        _pendingCount++;
        _cause = cause;
        _timer.Stop();
        _timer.Start();
    }
    internal void Flush()
    {
        if (_pendingCount == 0) return;
        _timer.Stop();
        string cause = _cause + " combined=" + _pendingCount;
        _pendingCount = 0;
        Flushes++;
        try { _flush(cause); }
        catch (Exception ex)
        {
            // Explorer is cosmetic; a COM/Shell failure must never crash
            // the NAS app or interfere with safe uploads/disconnect.
            AppStore.Log("EXPLORER_BURST_REFRESH_WARNING " +
                ex.GetType().Name);
        }
    }
    public void Dispose() => _timer.Dispose();

    internal static void PolicyTest()
    {
        var messages = new List<string>();
        using var test = new ExplorerRefreshCoalescer(messages.Add);
        test.Request("first");
        test.Request("second");
        test.Request("third");
        if (!test.Pending || test.Requests != 3 || test.Flushes != 0)
            throw new InvalidOperationException("Shell refresh burst was not deferred");
        test.Flush();
        if (test.Pending || test.Flushes != 1 || messages.Count != 1 ||
            !messages[0].Contains("combined=3"))
            throw new InvalidOperationException("Shell refresh burst did not merge");
        test.Request("next");
        test.Flush();
        if (test.Requests != 4 || test.Flushes != 2)
            throw new InvalidOperationException("Subsequent Shell event was lost");
        Console.WriteLine("EXPLORER_GLOBAL_REFRESH_COALESCING_POLICY_PASS");
    }
}
