using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace NASMount;

/// <summary>
/// Direct Google API token refresh for FileHub's publisher-owned Desktop OAuth
/// client. Uses the isolated encrypted native token store and never runs
/// rclone or imports another application's credentials.
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
            // Token renewal runs on the Explorer path when a saved grant
            // expires. Do not read unbounded proxy/error response bodies.
            using var response = await _http.SendAsync(request,
                HttpCompletionOption.ResponseHeadersRead,
                cancellationToken).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode)
                throw new IOException("Google Desktop OAuth refresh HTTP " +
                    (int)response.StatusCode);
            await response.Content.LoadIntoBufferAsync(256 * 1024,
                cancellationToken).ConfigureAwait(false);
            using var data = await JsonDocument.ParseAsync(
                await response.Content.ReadAsStreamAsync(cancellationToken),
                cancellationToken: cancellationToken).ConfigureAwait(false);
            var body = data.RootElement;
            string? fresh = body.ValueKind == JsonValueKind.Object &&
                body.TryGetProperty("access_token", out var access) &&
                access.ValueKind == JsonValueKind.String
                    ? access.GetString() : null;
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
            catch (HttpRequestException ex)
            {
                throw new IOException("Google OAuth refresh network unavailable", ex);
            }
            catch (TaskCanceledException ex) when (!cancellationToken.IsCancellationRequested)
            {
                throw new IOException("Google OAuth refresh network timed out", ex);
            }
            catch (JsonException ex)
            {
                throw new IOException("Google OAuth refresh reply was malformed", ex);
            }
            catch (Exception ex) when (ex is KeyNotFoundException or
                InvalidOperationException)
            {
                throw new IOException("Google OAuth refresh data was invalid", ex);
            }
        }
        finally { _refreshGate.Release(); }
    }

    // A network mount can remain idle beyond Google's access-token lifetime.
    // Renew the already approved grant before the next Explorer callback,
    // without a second Chrome window or a refresh on every file operation.
    internal async Task KeepTokenWarmAsync(CancellationToken cancellationToken,
        TimeSpan? checkInterval = null)
    {
        var interval = checkInterval ?? TimeSpan.FromMinutes(1);
        if (interval <= TimeSpan.Zero)
            throw new ArgumentOutOfRangeException(nameof(checkInterval));
        using var ticker = new PeriodicTimer(interval);
        try
        {
            while (await ticker.WaitForNextTickAsync(cancellationToken)
                .ConfigureAwait(false))
            {
                try
                {
                    // Cached tokens return without network traffic. Only
                    // the last two minutes of lifetime trigger one renewal.
                    _ = await AccessTokenAsync(cancellationToken)
                        .ConfigureAwait(false);
                }
                catch (Exception ex) when (ex is IOException or
                    UnauthorizedAccessException or
                    System.Security.Cryptography.CryptographicException)
                {
                    // A brief outage must not kill the mounted filesystem;
                    // an unavailable credential store must not do so either.
                    // A subsequent tick can retry. No tokens are logged.
                }
            }
        }
        catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
        {
            // Expected clean WinFsp disconnect.
        }
    }
}
