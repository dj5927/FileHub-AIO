using System.Diagnostics;
using System.Reflection;
using System.Runtime.Loader;
using System.Security.Cryptography;

namespace NASMount.LockBridge;

/// <summary>Keep 2.1 installations mountable without replacing the shared
/// system driver. Use the 2.2 managed SDK on 2.2 and newer native drivers.</summary>
internal static class WinFspBindingPolicy
{
    private const string LegacyHash =
        "6BB1C36F899A5DE0CED020A026120CE9D0B88B140B3C7BD6CD079175F76529BC";
    private const string BindingIdentity =
        "winfsp-msil, Version=2.0.0.0, Culture=neutral, PublicKeyToken=b099876d8fa9b1f3";

    internal static bool NeedsLegacy(Version? version) =>
        version is not null && version.Major == 2 && version.Minor < 2;

    private static Version? Installed()
    {
        foreach (var root in new[] {
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles) })
        {
            var dll = Path.Combine(root, "WinFsp", "bin", "winfsp-x64.dll");
            if (!File.Exists(dll)) continue;
            var v = FileVersionInfo.GetVersionInfo(dll);
            return new Version(v.FileMajorPart, v.FileMinorPart, v.FileBuildPart);
        }
        return null;
    }

    private static string VerifiedLegacyPath()
    {
        var path = Path.GetFullPath(Path.Combine(AppContext.BaseDirectory,
            "winfsp-legacy", "winfsp-msil.dll"));
        using var file = File.OpenRead(path);
        if (Convert.ToHexString(SHA256.HashData(file)) != LegacyHash ||
            AssemblyName.GetAssemblyName(path).FullName != BindingIdentity)
            throw new InvalidDataException("WinFsp 2.1 compatibility assembly integrity failure");
        return path;
    }

    internal static bool UseLegacyDriver() => NeedsLegacy(Installed());

    private sealed class LegacyContext(string bindingPath) : AssemblyLoadContext("FileHub-WinFsp-2.1")
    {
        protected override Assembly? Load(AssemblyName name) =>
            name.Name == "winfsp-msil" ? LoadFromAssemblyPath(bindingPath) : null;
    }

    // The default load context prefers the runtime's .deps.json entry even
    // for explicit LoadFromAssemblyPath. Use an isolated managed context for
    // the bridge itself and its older WinFsp assembly, sharing the remaining
    // .NET runtime and ordinary dependency assemblies with the default.
    internal static int RunLegacy(string[] args)
    {
        var path = VerifiedLegacyPath();
        var context = new LegacyContext(path);
        var bridge = context.LoadFromAssemblyPath(typeof(WinFspBindingPolicy).Assembly.Location);
        var binding = context.LoadFromAssemblyName(new AssemblyName(BindingIdentity));
        VerifyLoadedLegacy(binding);
        Console.WriteLine("WINFSP_LEGACY_MANAGED_BINDING_SELECTED");
        var run = bridge.GetType("NASMount.LockBridge.Program", throwOnError: true)!
            .GetMethod("Run", BindingFlags.Static | BindingFlags.NonPublic)!;
        return (int)run.Invoke(null, new object[] { args })!;
    }

    internal static void VerifyLoadedLegacy(Assembly assembly)
    {
        if (!string.Equals(assembly.Location, VerifiedLegacyPath(), StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("WinFsp legacy context did not load the pinned assembly");
        Console.WriteLine("WINFSP_LEGACY_BINDING_IDENTITY_PASS");
    }

    internal static void PolicyTest()
    {
        if (!NeedsLegacy(new Version(2, 1, 25156)) ||
            NeedsLegacy(null) || NeedsLegacy(new Version(2, 2, 26215)) ||
            NeedsLegacy(new Version(2, 2, 26323)) ||
            NeedsLegacy(new Version(2, 3, 0)))
            throw new InvalidOperationException("WinFsp compatibility selection failed");
        var path = VerifiedLegacyPath();
        var stable = Path.Combine(AppContext.BaseDirectory, "winfsp-msil.dll");
        if (AssemblyName.GetAssemblyName(stable).FullName != BindingIdentity ||
            FileVersionInfo.GetVersionInfo(stable).FileBuildPart != 26323)
            throw new InvalidOperationException("WinFsp 2.2 managed binding mismatch");
        var context = new LegacyContext(path);
        var asm = context.LoadFromAssemblyName(new AssemblyName(BindingIdentity));
        VerifyLoadedLegacy(asm);
        Console.WriteLine("WINFSP_DUAL_MANAGED_BINDING_POLICY_PASS");
    }
}
