using System.Security.Cryptography;

namespace NASMount;

/// <summary>
/// The upstream rclone.exe stays untouched and is used for every other
/// backend. This separately built rclone v1.76.0 has an opt-in asynchronous
/// VFS About cache so Windows Explorer never waits for Google's quota API.
/// SHA-256 verification prevents silently enabling About on an old or
/// unexpected executable where it could block Explorer again.
/// </summary>
internal static class GoogleAsyncQuotaEngine
{
    internal const string Sha256 =
        "C6B2742C62E6DE240AE8ECF6DE4384103CD49D9A013B792493C00AB743CBBE21";
    internal static string ExecutablePath => Path.Combine(
        AppContext.BaseDirectory, "tools", "rclone-filehub-google.exe");

    private static readonly Lazy<bool> Verified = new(() =>
    {
        try
        {
            if (!File.Exists(ExecutablePath)) return false;
            using var stream = File.OpenRead(ExecutablePath);
            string hash = Convert.ToHexString(SHA256.HashData(stream));
            bool valid = string.Equals(hash, Sha256,
                StringComparison.OrdinalIgnoreCase);
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
        if (Sha256.Length != 64 || !Sha256.All(Uri.IsHexDigit) ||
            !ExecutablePath.EndsWith("rclone-filehub-google.exe",
                StringComparison.OrdinalIgnoreCase))
            throw new InvalidOperationException("Google async quota engine identity invalid");
        Console.WriteLine("GOOGLE_ASYNC_QUOTA_ENGINE_HASH_POLICY_PASS");
    }
}
