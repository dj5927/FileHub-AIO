using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Google Cloud downloads Desktop OAuth client credentials in an "installed"
/// JSON object. Parse locally without persisting or logging the downloaded
/// client_secret, and reject Web Application / Service Account JSON.
/// A desktop OAuth application must still be created by its owner in Google
/// Cloud. FileHub never invents or borrows another application's client.
/// </summary>
internal static class GoogleOAuthClientFile
{
    internal const string GoogleCloudClientsUrl =
        "https://console.cloud.google.com/auth/clients";
    internal const string GoogleSetupUrl =
        "https://console.cloud.google.com/auth/clients";
    internal const int MaxFileSize = 256 * 1024;

    // Publisher-owned Google Desktop OAuth credentials are bundled ONCE.
    // End users should only see the normal Chrome account picker, never a
    // Client ID/secret field. Desktop clients are public OAuth clients:
    // this JSON is configuration, not an authentication password.
    internal static string PackagedClientPath => Path.Combine(
        AppContext.BaseDirectory, "tools", "google-oauth-desktop.json");

    internal static bool TryReadPackagedClient(out string clientId,
        out string clientSecret)
    {
        clientId = "";
        clientSecret = "";
        if (!File.Exists(PackagedClientPath)) return false;
        var pair = ReadDesktopFile(PackagedClientPath);
        clientId = pair.ClientId;
        clientSecret = pair.ClientSecret;
        return true;
    }

    internal static (string ClientId, string ClientSecret) ReadDesktopFile(
        string path)
    {
        var info = new FileInfo(path);
        if (!info.Exists || info.Length == 0 || info.Length > MaxFileSize)
            throw new InvalidDataException(
                "Google OAuth JSON 파일이 없거나 크기가 올바르지 않습니다.");
        return ParseDesktopJson(File.ReadAllText(path, Encoding.UTF8));
    }

    internal static (string ClientId, string ClientSecret) ParseDesktopJson(
        string json)
    {
        if (Encoding.UTF8.GetByteCount(json) > MaxFileSize)
            throw new InvalidDataException("Google OAuth JSON 파일이 너무 큽니다.");
        try
        {
            using var document = JsonDocument.Parse(json);
            JsonElement root = document.RootElement;
            if (root.ValueKind != JsonValueKind.Object ||
                !root.TryGetProperty("installed", out var installed) ||
                installed.ValueKind != JsonValueKind.Object)
                throw new InvalidDataException(
                    "데스크톱 앱용 OAuth JSON이 아닙니다. Google Cloud에서 '데스크톱 앱'을 선택하세요.");

            if (!installed.TryGetProperty("client_id", out var id) ||
                id.ValueKind != JsonValueKind.String ||
                !installed.TryGetProperty("client_secret", out var secret) ||
                secret.ValueKind != JsonValueKind.String)
                throw new InvalidDataException(
                    "OAuth JSON에 Client ID 또는 Client Secret이 없습니다.");

            string clientId = id.GetString()!.Trim();
            string clientSecret = secret.GetString()!.Trim();
            ValidateDesktopCredentials(clientId, clientSecret);
            return (clientId, clientSecret);
        }
        catch (JsonException ex)
        {
            throw new InvalidDataException(
                "Google OAuth JSON 파일 형식이 올바르지 않습니다.", ex);
        }
    }

    internal static void ValidateDesktopCredentials(
        string clientId, string clientSecret)
    {
        if (clientId.Length is < 25 or > 512 ||
            !clientId.EndsWith(".apps.googleusercontent.com",
                StringComparison.OrdinalIgnoreCase) ||
            clientId.Any(char.IsWhiteSpace) ||
            clientSecret.Length is < 6 or > 2048 ||
            clientSecret.Any(char.IsControl))
            throw new ArgumentException(
                "Google 데스크톱 OAuth Client ID 또는 Client Secret 형식을 확인하세요.");
    }

    internal static void SelfTest()
    {
        const string clientId =
            "123456789012-test-desktop.apps.googleusercontent.com";
        const string clientSecret = "GOCSPX-example-not-real";
        string desktop = JsonSerializer.Serialize(new
        {
            installed = new
            {
                client_id = clientId, client_secret = clientSecret,
                redirect_uris = new[] { "http://localhost" }
            }
        });
        var pair = ParseDesktopJson(desktop);
        if (pair.ClientId != clientId || pair.ClientSecret != clientSecret)
            throw new InvalidOperationException("Desktop OAuth import mismatch");
        string web = JsonSerializer.Serialize(new
        {
            web = new { client_id = clientId, client_secret = clientSecret }
        });
        foreach (string invalid in new[]
        {
            web, "{\"type\":\"service_account\",\"private_key\":\"secret\"}",
            "{\"installed\":{\"client_id\":\"invalid\",\"client_secret\":\"example\"}}",
            "{\"installed\":{\"client_id\":\"" + clientId +
                "\",\"client_secret\":\"\"}}",
            "{\"installed\":"
        })
        {
            try
            {
                ParseDesktopJson(invalid);
                throw new InvalidOperationException(
                    "Non-desktop or malformed OAuth file accepted");
            }
            catch (InvalidDataException) { }
            catch (ArgumentException) { }
        }
        Console.WriteLine("GOOGLE_DESKTOP_OAUTH_JSON_IMPORT_POLICY_PASS");
    }
}
