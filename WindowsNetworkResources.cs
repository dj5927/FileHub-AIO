using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Enumerates the Windows network-provider database, not only registry
/// HKCU\Network and WNetGetConnection(letter). Disconnected/persistent
/// resources may exist in RESOURCE_REMEMBERED without a local drive letter.
/// Enumeration is read-only and scoped to the caller's logon session.
/// </summary>
internal static class WindowsNetworkResources
{
    internal const int Connected = 1;
    internal const int Remembered = 3;
    private const int Disk = 1;
    private const int NoMoreItems = 259;
    private const int MoreData = 234;

    private static readonly Regex Ours = new(
        @"^\\\\NASMount-([a-f0-9]{8})(?:-[a-f0-9]{8})?\\",
        RegexOptions.IgnoreCase | RegexOptions.Compiled |
        RegexOptions.CultureInvariant);

    [StructLayout(LayoutKind.Sequential)]
    private struct NetResource
    {
        public int Scope;
        public int Type;
        public int DisplayType;
        public int Usage;
        public IntPtr LocalName;
        public IntPtr RemoteName;
        public IntPtr Comment;
        public IntPtr Provider;
    }

    [DllImport("mpr.dll", EntryPoint = "WNetOpenEnumW",
        CharSet = CharSet.Unicode)]
    private static extern int OpenEnum(int scope, int type, int usage,
        IntPtr resource, out IntPtr handle);

    [DllImport("mpr.dll", EntryPoint = "WNetEnumResourceW",
        CharSet = CharSet.Unicode)]
    private static extern int EnumResource(IntPtr handle, ref int count,
        IntPtr entries, ref int bufferSize);

    [DllImport("mpr.dll", EntryPoint = "WNetCloseEnum")]
    private static extern int CloseEnum(IntPtr handle);

    internal sealed record Entry(int Scope, string LocalName,
        string RemoteName, string Provider)
    {
        internal string? NasMountId
        {
            get
            {
                var match = Ours.Match(RemoteName);
                return match.Success ? match.Groups[1].Value.ToLowerInvariant() : null;
            }
        }
    }

    internal static IReadOnlyList<Entry> Enumerate(int scope)
    {
        if (scope is not (Connected or Remembered))
            throw new ArgumentOutOfRangeException(nameof(scope));
        int result = OpenEnum(scope, Disk, 0, IntPtr.Zero, out IntPtr handle);
        if (result != 0)
            throw new Win32Exception(result, "WNetOpenEnum scope=" + scope);
        var output = new List<Entry>();
        const int limit = 1024;
        IntPtr buffer = IntPtr.Zero;
        int capacity = 32768;
        try
        {
            buffer = Marshal.AllocHGlobal(capacity);
            for (int batch = 0; batch < 200; batch++)
            {
                int count = -1;
                int size = capacity;
                result = EnumResource(handle, ref count, buffer, ref size);
                if (result == NoMoreItems) break;
                if (result == MoreData)
                {
                    if (size > 1024 * 1024)
                        throw new IOException("WNet resource buffer exceeded safety limit");
                    capacity = Math.Max(capacity * 2, size);
                    buffer = Marshal.ReAllocHGlobal(buffer, (IntPtr)capacity);
                    continue;
                }
                if (result != 0)
                    throw new Win32Exception(result, "WNetEnumResource scope=" + scope);
                if (count < 0 || count > limit)
                    throw new IOException("Windows provider returned invalid resource count");
                int structSize = Marshal.SizeOf<NetResource>();
                for (int i = 0; i < count; i++)
                {
                    var row = Marshal.PtrToStructure<NetResource>(
                        IntPtr.Add(buffer, i * structSize));
                    output.Add(new Entry(scope,
                        Marshal.PtrToStringUni(row.LocalName) ?? "",
                        Marshal.PtrToStringUni(row.RemoteName) ?? "",
                        Marshal.PtrToStringUni(row.Provider) ?? ""));
                }
                if (output.Count >= 4096)
                    throw new IOException("Windows provider resource count limit");
            }
            return output;
        }
        finally
        {
            if (buffer != IntPtr.Zero) Marshal.FreeHGlobal(buffer);
            CloseEnum(handle);
        }
    }

    internal static IReadOnlyList<Entry>? TryEnumerate(int scope)
    {
        try { return Enumerate(scope); }
        catch (Exception ex)
        {
            AppStore.Log("WINDOWS_NETRESOURCE_ENUM_ERROR scope=" + scope +
                " " + ex.Message);
            return null;
        }
    }

    internal static void PolicyTest()
    {
        var allowed = new Entry(Remembered, "", @"\\NASMount-af7212e7\OldCloud",
            "WinFsp");
        var session = new Entry(Remembered, "",
            @"\\NASMount-af7212e7-937c6f96\OldCloud", "WinFsp");
        var other = new Entry(Remembered, "", @"\\OtherProduct\OldCloud",
            "Other");
        if (allowed.NasMountId != "af7212e7" ||
            session.NasMountId != "af7212e7" ||
            other.NasMountId is not null)
            throw new InvalidOperationException(
                "RESOURCE_REMEMBERED must match exactly our NASMount UNC");
        foreach (int scope in new[] { Connected, Remembered })
        {
            // A missing or locked-down network provider is not a fatal error
            // for the rest of the app; diagnosis reports whether it responded.
            var list = TryEnumerate(scope);
            Console.WriteLine("WINDOWS_PROVIDER_ENUM scope=" + scope +
                " result=" + (list is null ? "unavailable" : list.Count));
        }
        Console.WriteLine("WINDOWS_NETWORK_RESOURCE_POLICY_PASS");
    }
}
