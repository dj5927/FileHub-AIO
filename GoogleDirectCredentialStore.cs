using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// FileHub-owned, Windows-DPAPI encrypted Google OAuth store.
/// Neither the native browser login nor the native WinFsp engine needs a
/// rclone executable or .conf file. Per-profile encryption is tied to
/// the current Windows user and can be restored from an encrypted backup.
/// </summary>
internal static class GoogleDirectCredentialStore
{
    internal sealed record Token(string ClientId, string ClientSecret,
        string RefreshToken, string AccessToken, DateTimeOffset Expiry,
        string? Provider = null);

    private static string StoreDirectory =>
        Path.Combine(AppStore.DirectoryPath, "google-direct-auth");
    private static string PathFor(NasProfile profile) =>
        Path.Combine(StoreDirectory, profile.Id.ToString("N") + ".token");
    private static string BackupFor(NasProfile profile) => PathFor(profile) + ".previous";
    private static byte[] Entropy(NasProfile profile) =>
        SHA256.HashData(Encoding.UTF8.GetBytes(
            "FileHubAIO-GoogleDirectOAuth-v1:" + profile.Id.ToString("N")));

    internal static bool HasNative(NasProfile profile) =>
        profile.Protocol == "GoogleDrive" && File.Exists(PathFor(profile));
    internal static bool HasBackup(NasProfile profile) =>
        profile.Protocol == "GoogleDrive" && File.Exists(BackupFor(profile));

    // A copied, damaged or no-longer-decryptable token is not a usable
    // authorization. Leave every byte in place so a verified browser grant
    // can replace it with the normal encrypted backup transaction.
    internal static bool TryLoad(NasProfile profile, out Token token)
    {
        token = null!;
        if (!HasNative(profile)) return false;
        try
        {
            token = Load(profile);
            return true;
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or
            JsonException or ArgumentException)
        {
            return false;
        }
    }

    // A native-format file may still contain a legacy broker grant. Only
    // FileHub's publisher-issued Desktop grant can drive the normal engine.
    // Older direct grants omitted Provider, so null remains compatible.
    internal static bool IsPublisherGrant(Token token)
    {
        if (token.Provider is not (null or "FileHub") ||
            string.IsNullOrWhiteSpace(token.ClientId) ||
            string.IsNullOrWhiteSpace(token.ClientSecret) ||
            string.IsNullOrWhiteSpace(token.RefreshToken)) return false;
        try
        {
            GoogleOAuthClientFile.ValidateDesktopCredentials(
                token.ClientId, token.ClientSecret);
            return true;
        }
        catch (ArgumentException) { return false; }
    }

    internal static bool TryLoadPublisher(NasProfile profile, out Token token)
    {
        if (TryLoad(profile, out var saved) && IsPublisherGrant(saved))
        {
            token = saved;
            return true;
        }
        token = null!;
        return false;
    }

    internal static Token Load(NasProfile profile)
    {
        if (!HasNative(profile))
            throw new FileNotFoundException("Native Google authorization not found");
        byte[] protectedBlob = File.ReadAllBytes(PathFor(profile));
        try
        {
            byte[] plain = ProtectedData.Unprotect(protectedBlob,
                Entropy(profile), DataProtectionScope.CurrentUser);
            try
            {
                var token = JsonSerializer.Deserialize<Token>(plain) ??
                    throw new InvalidDataException("Empty native Google authorization");
                if (string.IsNullOrWhiteSpace(token.ClientId) ||
                    string.IsNullOrWhiteSpace(token.RefreshToken))
                    throw new InvalidDataException("Invalid native Google OAuth credentials");
                return token;
            }
            finally { CryptographicOperations.ZeroMemory(plain); }
        }
        catch (CryptographicException)
        {
            // Never disclose ciphertext or tokens in logs/error messages.
            throw new IOException(
                "Google authorization cannot be decrypted by this Windows user");
        }
    }

    private static void Save(NasProfile profile, Token token,
        bool preservePreviousAuthorization)
    {
        if (profile.Protocol != "GoogleDrive" ||
            string.IsNullOrWhiteSpace(token.ClientId) ||
            string.IsNullOrWhiteSpace(token.RefreshToken))
            throw new ArgumentException("Invalid direct Google OAuth session");
        Directory.CreateDirectory(StoreDirectory);
        byte[] json = JsonSerializer.SerializeToUtf8Bytes(token);
        byte[] encrypted;
        try
        {
            encrypted = ProtectedData.Protect(json, Entropy(profile),
                DataProtectionScope.CurrentUser);
        }
        finally { CryptographicOperations.ZeroMemory(json); }
        string target = PathFor(profile);
        string temporary = target + "." + Guid.NewGuid().ToString("N") + ".next";
        try
        {
            using (var file = new FileStream(temporary, FileMode.CreateNew,
                FileAccess.Write, FileShare.None, 8192, FileOptions.WriteThrough))
            {
                file.Write(encrypted);
                file.Flush(flushToDisk: true);
            }
            if (preservePreviousAuthorization && File.Exists(target))
                File.Copy(target, BackupFor(profile), overwrite: true);
            File.Move(temporary, target, overwrite: true);
        }
        finally
        {
            CryptographicOperations.ZeroMemory(encrypted);
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    internal static void SaveBrowserGrant(NasProfile profile, string jsonToken,
        string clientId, string clientSecret)
    {
        using var json = JsonDocument.Parse(jsonToken);
        var root = json.RootElement;
        string access = root.GetProperty("access_token").GetString() ?? "";
        string refresh = root.GetProperty("refresh_token").GetString() ?? "";
        int ttl = root.TryGetProperty("expires_in", out var expires) &&
            expires.TryGetInt32(out var seconds) ? seconds : 3600;
        if (string.IsNullOrEmpty(access) || string.IsNullOrEmpty(refresh) ||
            ttl < 120)
            throw new InvalidDataException("Google OAuth token response incomplete");
        Save(profile, new Token(clientId, clientSecret, refresh, access,
            DateTimeOffset.UtcNow.AddSeconds(ttl), "FileHub"),
            preservePreviousAuthorization: true);
    }

    // Legacy-format fixture used only by migration policy tests. Normal
    // authentication never calls this or acquires a public broker grant.
    internal static void SaveOpenListGrant(NasProfile profile, string jsonToken)
    {
        using var json = JsonDocument.Parse(jsonToken);
        var root = json.RootElement;
        string access = root.GetProperty("access_token").GetString() ?? "";
        string refresh = root.GetProperty("refresh_token").GetString() ?? "";
        if (string.IsNullOrWhiteSpace(access) ||
            string.IsNullOrWhiteSpace(refresh))
            throw new InvalidDataException("OpenList Google authorization missing offline grant");
        Save(profile, new Token("openlist-public-broker", "", refresh, access,
            DateTimeOffset.UtcNow.AddMinutes(55), "OpenList"),
            preservePreviousAuthorization: true);
    }

    internal static void SaveRefresh(NasProfile profile, string access,
        string refresh, DateTimeOffset expiry)
    {
        var current = Load(profile);
        Save(profile, current with
        {
            AccessToken = access, RefreshToken = refresh, Expiry = expiry
        }, preservePreviousAuthorization: false);
    }

    internal static void RestoreBackup(NasProfile profile)
    {
        if (!HasBackup(profile))
            throw new FileNotFoundException("Google native OAuth backup not found");
        // Test decryptability BEFORE touching the current credential.
        byte[] content = File.ReadAllBytes(BackupFor(profile));
        byte[] plain = ProtectedData.Unprotect(content,
            Entropy(profile), DataProtectionScope.CurrentUser);
        CryptographicOperations.ZeroMemory(plain);
        string current = PathFor(profile);
        string swap = current + "." + Guid.NewGuid().ToString("N") + ".swap";
        File.Copy(BackupFor(profile), swap);
        try
        {
            if (File.Exists(current))
                File.Copy(current, current + ".before-restore", overwrite: true);
            File.Move(swap, current, overwrite: true);
        }
        finally { if (File.Exists(swap)) File.Delete(swap); }
    }

    internal static void SelfTest()
    {
        var profile = new NasProfile
        {
            Id = Guid.NewGuid(), Protocol = "GoogleDrive"
        };
        string token = """{"access_token":"OFFLINE_TEST","refresh_token":"OFFLINE_REFRESH","expires_in":3600}""";
        try
        {
            SaveBrowserGrant(profile, token,
                "test.apps.googleusercontent.com", "");
            SaveBrowserGrant(profile,
                """{"access_token":"NEW_OFFLINE_TEST","refresh_token":"NEW_OFFLINE_REFRESH","expires_in":3600}""",
                "new-test.apps.googleusercontent.com", "");
            var restored = Load(profile);
            if (restored.AccessToken != "NEW_OFFLINE_TEST" ||
                restored.RefreshToken != "NEW_OFFLINE_REFRESH")
                throw new InvalidOperationException("DPAPI round-trip failed");
            SaveRefresh(profile, "REFRESHED_TEST", "NEW_OFFLINE_REFRESH",
                DateTimeOffset.UtcNow.AddHours(1));
            if (Load(profile).AccessToken != "REFRESHED_TEST")
                throw new InvalidOperationException("DPAPI refresh save failed");
            RestoreBackup(profile);
            if (Load(profile).AccessToken != "OFFLINE_TEST")
                throw new InvalidOperationException("DPAPI backup restore failed");
            SaveOpenListGrant(profile, token);
            if (Load(profile).Provider != "OpenList" ||
                Load(profile).RefreshToken != "OFFLINE_REFRESH")
                throw new InvalidOperationException("OpenList grant DPAPI protection failed");
            Console.WriteLine("GOOGLE_DIRECT_DPAPI_OAUTH_ROUNDTRIP_AND_BACKUP_PASS");
        }
        finally
        {
            foreach (string suffix in new[] { "", ".previous", ".before-restore" })
            {
                try { File.Delete(PathFor(profile) + suffix); }
                catch (IOException) { }
            }
        }
    }
}
