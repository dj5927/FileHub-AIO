using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Direct Google API token refresh using the user's own Desktop OAuth
/// client. Does not run rclone. Existing per-profile .conf credentials may
/// be IMPORTED, read-only, only if they already contain a custom client_id.
/// Shared rclone OAuth must never be used for the direct engine: real user
/// logs demonstrated HTTP 403 rateLimitExceeded on that client.
///
/// A native FileHub OAuth onboarding UI and isolated token store are still
/// required before switching normal Google profiles from rclone.
/// </summary>
internal sealed class GoogleDirectOAuthSession
{
    private readonly HttpClient _http;
    private readonly Uri _tokenEndpoint;
    private readonly string _clientId;
    private readonly string _clientSecret;
    private string _refreshToken;
    private string _accessToken;
    private DateTimeOffset _expiry;
    private readonly SemaphoreSlim _refreshGate = new(1, 1);
    private readonly Action<string, string, DateTimeOffset>? _persist;

    internal GoogleDirectOAuthSession(HttpClient http, string clientId,
        string clientSecret, string refreshToken, string accessToken,
        DateTimeOffset expiry, Uri? tokenEndpoint = null,
        Action<string, string, DateTimeOffset>? persist = null)
    {
        if (string.IsNullOrWhiteSpace(clientId) ||
            string.IsNullOrWhiteSpace(refreshToken))
            throw new InvalidOperationException(
                "Direct Google API requires dedicated Desktop OAuth credentials");
        _http = http;
        _clientId = clientId;
        _clientSecret = clientSecret;
        _refreshToken = refreshToken;
        _accessToken = accessToken;
        _expiry = expiry;
        _tokenEndpoint = tokenEndpoint ??
            new Uri("https://oauth2.googleapis.com/token");
        _persist = persist;
        if (_tokenEndpoint.Scheme != Uri.UriSchemeHttps &&
            !(_tokenEndpoint.IsLoopback && _tokenEndpoint.Scheme == Uri.UriSchemeHttp))
            throw new ArgumentException("OAuth token URI must use HTTPS");
    }

    internal static GoogleDirectOAuthSession ImportCustomProfile(
        HttpClient http, NasProfile profile)
    {
        if (profile.Protocol != "GoogleDrive")
            throw new InvalidOperationException("Not a Google Drive profile");
        if (GoogleDirectCredentialStore.HasNative(profile))
        {
            var native = GoogleDirectCredentialStore.Load(profile);
            return new GoogleDirectOAuthSession(http, native.ClientId,
                native.ClientSecret, native.RefreshToken, native.AccessToken,
                native.Expiry, persist: (access, refresh, expiration) =>
                    GoogleDirectCredentialStore.SaveRefresh(
                        profile, access, refresh, expiration));
        }
        // Never silently borrow any rclone .conf or its shared OAuth client.
        // A browser grant issued to the FileHub Desktop OAuth client is
        // required. Legacy account files remain untouched for recovery.
        throw new InvalidOperationException(
            "Google 직접 API 인증이 없습니다. Chrome에서 FileHub 전용 OAuth 승인이 필요합니다.");
    }

    internal async Task<string> AccessTokenAsync(
        CancellationToken cancellationToken = default)
    {
        if (!string.IsNullOrWhiteSpace(_accessToken) &&
            DateTimeOffset.UtcNow + TimeSpan.FromMinutes(2) < _expiry)
            return _accessToken;
        await _refreshGate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (!string.IsNullOrWhiteSpace(_accessToken) &&
                DateTimeOffset.UtcNow + TimeSpan.FromMinutes(2) < _expiry)
                return _accessToken;
            using var form = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["client_id"] = _clientId,
                ["client_secret"] = _clientSecret,
                ["refresh_token"] = _refreshToken,
                ["grant_type"] = "refresh_token"
            });
            using var request = new HttpRequestMessage(HttpMethod.Post, _tokenEndpoint)
            {
                Content = form
            };
            using var response = await _http.SendAsync(request,
                cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                throw new IOException("Google Desktop OAuth refresh HTTP " +
                    (int)response.StatusCode);
            using var data = await JsonDocument.ParseAsync(
                await response.Content.ReadAsStreamAsync(cancellationToken),
                cancellationToken: cancellationToken).ConfigureAwait(false);
            var body = data.RootElement;
            string? fresh = body.GetProperty("access_token").GetString();
            if (string.IsNullOrWhiteSpace(fresh))
                throw new IOException("Google OAuth refresh returned no access token");
            int seconds = body.TryGetProperty("expires_in", out var duration) &&
                duration.TryGetInt32(out int ttl) ? ttl : 3600;
            if (seconds < 120)
                throw new IOException("Google OAuth returned an unexpectedly short expiry");
            DateTimeOffset expires = DateTimeOffset.UtcNow.AddSeconds(seconds);
            string nextRefresh = body.TryGetProperty("refresh_token", out var rotated) &&
                !string.IsNullOrWhiteSpace(rotated.GetString())
                    ? rotated.GetString()! : _refreshToken;
            if (nextRefresh != _refreshToken && _persist is null)
                throw new IOException(
                    "Google OAuth rotated refresh token; durable store required");
            // Persist first. If saving fails, do not pretend the token
            // rotation was durable across an application restart.
            _persist?.Invoke(fresh, nextRefresh, expires);
            _accessToken = fresh;
            _refreshToken = nextRefresh;
            _expiry = expires;
            return _accessToken;
        }
        finally { _refreshGate.Release(); }
    }
}
