using System.Security.Cryptography;
using System.Text;

namespace NASMount;

/// <summary>
/// The upstream rclone.exe stays untouched and is used for every other
/// backend. This separately built rclone v1.76.0 has an opt-in asynchronous
/// VFS About cache so Windows Explorer never waits for Google's quota API.
/// The patched engine is verified against its per-release SHA-256 manifest
/// and the patched VFS marker, rather than assuming Go builds performed on
/// different Windows runners produce byte-identical executables.
/// </summary>
internal static class GoogleAsyncQuotaEngine
{
    private const string OriginalRcloneSha256 =
        "ACD80683E23151E93BBE0D6FFA74D26E542EC510332F630BEECB9944F8D1A623";
    private const string TeraBoxRcloneSha256 =
        "A72B8D123517696C4CC996FD17BD7509DDD7850B5E13893EE970F8C8A2B8390C";
    private const string PatchedMarker = "FILEHUB_QUOTA_CACHED";
    internal static string ExecutablePath => Path.Combine(
        AppContext.BaseDirectory, "tools", "rclone-filehub-google.exe");
    internal static string ManifestPath => Path.Combine(
        AppContext.BaseDirectory, "tools", "rclone-filehub-google.sha256");

    // Only the Google engine is allowed to use this opt-in VFS path.
    // Reject either upstream engine even if its manifest was copied too.
    internal static bool MatchesPatchManifest(string executable, string hash)
    {
        string manifest = Path.Combine(Path.GetDirectoryName(executable)!,
            "rclone-filehub-google.sha256");
        if (!File.Exists(manifest)) return false;
        string expected = File.ReadAllText(manifest).Trim();
        if (expected.Length != 64 || !expected.All(Uri.IsHexDigit) ||
            hash.Length != 64 || !hash.All(Uri.IsHexDigit) ||
            string.Equals(hash, OriginalRcloneSha256,
                StringComparison.OrdinalIgnoreCase) ||
            string.Equals(hash, TeraBoxRcloneSha256,
                StringComparison.OrdinalIgnoreCase) ||
            !string.Equals(hash, expected, StringComparison.OrdinalIgnoreCase))
            return false;
        return ContainsPatchedMarker(executable);
    }

    private static bool ContainsPatchedMarker(string executable)
    {
        byte[] marker = Encoding.ASCII.GetBytes(PatchedMarker);
        byte[] buffer = new byte[64 * 1024];
        using var stream = File.OpenRead(executable);
        int matched = 0;
        int length;
        while ((length = stream.Read(buffer)) > 0)
        {
            for (int i = 0; i < length; i++)
            {
                if (buffer[i] == marker[matched])
                {
                    if (++matched == marker.Length) return true;
                }
                else
                    matched = buffer[i] == marker[0] ? 1 : 0;
            }
        }
        return false;
    }

    private static readonly Lazy<bool> Verified = new(() =>
    {
        try
        {
            if (!File.Exists(ExecutablePath) ||
                !File.Exists(ManifestPath) ||
                new FileInfo(ExecutablePath).Length < 10_000_000) return false;
            using var stream = File.OpenRead(ExecutablePath);
            string hash = Convert.ToHexString(SHA256.HashData(stream));
            bool valid = MatchesPatchManifest(ExecutablePath, hash);
            AppStore.Log("GOOGLE_ASYNC_QUOTA_ENGINE_VERIFIED=" + valid);
            return valid;
        }
        catch (Exception ex)
        {
            AppStore.Log("GOOGLE_ASYNC_QUOTA_ENGINE_CHECK_FAILED " +
                ex.GetType().Name);
            return false;
        }
    });

    internal static bool IsAvailable => Verified.Value;

    internal static void SelfTest()
    {
        if (!ExecutablePath.EndsWith("rclone-filehub-google.exe",
                StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Google async quota engine identity invalid");
        string scratch = Path.Combine(Path.GetTempPath(),
            "FileHubGoogleManifestQA_" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(scratch);
        try
        {
            string candidate = Path.Combine(scratch, "rclone-filehub-google.exe");
            string manifest = Path.Combine(scratch, "rclone-filehub-google.sha256");
            File.WriteAllText(candidate, "QA " + PatchedMarker, Encoding.ASCII);
            string digest = Convert.ToHexString(SHA256.HashData(
                File.ReadAllBytes(candidate)));
            File.WriteAllText(manifest, digest);
            if (!MatchesPatchManifest(candidate, digest))
                throw new InvalidOperationException("Valid isolated manifest rejected");
            File.WriteAllText(manifest, OriginalRcloneSha256);
            if (MatchesPatchManifest(candidate, digest) ||
                MatchesPatchManifest(candidate, OriginalRcloneSha256))
                throw new InvalidOperationException("Wrong engine manifest accepted");
            File.WriteAllText(manifest, digest);
            File.WriteAllText(candidate, "QA without marker", Encoding.ASCII);
            if (MatchesPatchManifest(candidate, digest))
                throw new InvalidOperationException("Unpatched engine accepted");
        }
        finally { Directory.Delete(scratch, recursive: true); }
        Console.WriteLine("GOOGLE_ASYNC_QUOTA_PATCH_MARKER_AND_MANIFEST_POLICY_PASS");
        Console.WriteLine("GOOGLE_ASYNC_QUOTA_ENGINE_HASH_POLICY_PASS");
    }
}
