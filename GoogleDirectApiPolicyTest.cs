using System.Net;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>Offline integration contract for the rclone-free Google REST layer.
/// Never touches a user account, real Drive files or OAuth tokens.</summary>
internal static class GoogleDirectApiPolicyTest
{
    private sealed class FakeGoogleServer : HttpMessageHandler
    {
        internal int Lists;
        internal int Creates;
        internal int Renames;
        internal bool Limit;

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.Headers.Authorization?.Scheme != "Bearer" ||
                request.Headers.Authorization.Parameter != "TEST_TOKEN")
                throw new InvalidOperationException("Direct client did not send OAuth bearer");
            if (request.RequestUri?.Host != "127.0.0.1")
                throw new InvalidOperationException("Direct API test attempted real network");

            if (Limit)
                return Response(HttpStatusCode.Forbidden,
                    """{"error":{"status":"PERMISSION_DENIED","errors":[{"reason":"rateLimitExceeded"}]}}""");
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.AbsolutePath.EndsWith("/files"))
            {
                Interlocked.Increment(ref Lists);
                return Response(HttpStatusCode.OK,
                    """{"files":[{"id":"fileA_1","name":"보고서.xlsx","mimeType":"application/octet-stream","size":"1024","version":"12"}]}""");
            }
            if (request.Method == HttpMethod.Post &&
                request.RequestUri.AbsolutePath.EndsWith("/files"))
            {
                using var body = JsonDocument.Parse(await request.Content!
                    .ReadAsStringAsync(cancellationToken));
                if (body.RootElement.GetProperty("mimeType").GetString() !=
                    GoogleDirectApiClient.FolderMime ||
                    body.RootElement.GetProperty("parents")[0].GetString() != "root")
                    throw new InvalidOperationException("Incorrect Google folder POST");
                Interlocked.Increment(ref Creates);
                return Response(HttpStatusCode.OK,
                    """{"id":"folder_1","name":"새 폴더","mimeType":"application/vnd.google-apps.folder","version":"1"}""");
            }
            if (request.Method == HttpMethod.Patch &&
                request.RequestUri.AbsolutePath.EndsWith("/files/folder_1"))
            {
                Interlocked.Increment(ref Renames);
                return Response(HttpStatusCode.OK,
                    """{"id":"folder_1","name":"새 이름","mimeType":"application/vnd.google-apps.folder","version":"2"}""");
            }
            throw new InvalidOperationException("Unexpected direct API request");
        }

        private static HttpResponseMessage Response(HttpStatusCode code, string json) =>
            new(code)
            {
                Content = new StringContent(json, Encoding.UTF8, "application/json")
            };
    }

    private sealed class FakeOAuthServer : HttpMessageHandler
    {
        internal int RefreshRequests;
        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.Method != HttpMethod.Post ||
                request.RequestUri?.Host != "127.0.0.1")
                throw new InvalidOperationException("OAuth test left loopback");
            string form = await request.Content!
                .ReadAsStringAsync(cancellationToken);
            if (!form.Contains("grant_type=refresh_token") ||
                !form.Contains("client_id=TEST_CLIENT"))
                throw new InvalidOperationException("Incorrect OAuth refresh form");
            Interlocked.Increment(ref RefreshRequests);
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    """{"access_token":"REFRESHED_TOKEN","expires_in":3600,"token_type":"Bearer"}""",
                    Encoding.UTF8, "application/json")
            };
        }
    }

    internal static async Task RunAsync()
    {
        using var fake = new FakeGoogleServer();
        using var http = new HttpClient(fake);
        var api = new GoogleDirectApiClient(http,
            _ => Task.FromResult("TEST_TOKEN"),
            new Uri("http://127.0.0.1:18220/drive/v3/"),
            cacheTtl: TimeSpan.FromMinutes(10), maxRetries: 0);

        var requests = Enumerable.Range(0, 5)
            .Select(_ => api.ListChildrenAsync("root"));
        var parallel = await Task.WhenAll(requests);
        if (fake.Lists != 1 || parallel.Any(list =>
                list.Count != 1 || list[0].Name != "보고서.xlsx"))
            throw new InvalidOperationException(
                "Direct API first folder enumeration did not share one request");
        await api.ListChildrenAsync("root");
        if (fake.Lists != 1)
            throw new InvalidOperationException("Direct Google metadata cache not reused");
        Console.WriteLine("GOOGLE_DIRECT_API_HTTPCLIENT_AND_FOLDER_CACHE_PASS");

        var folder = await api.CreateFolderAsync("root", "새 폴더");
        if (folder.Id != "folder_1" || fake.Creates != 1)
            throw new InvalidOperationException("Google direct folder create failed");
        await api.ListChildrenAsync("root");
        if (fake.Lists != 2)
            throw new InvalidOperationException("Create did not invalidate folder cache");
        var rename = await api.RenameAsync("folder_1", "새 이름", "root");
        if (rename.Name != "새 이름" || fake.Renames != 1)
            throw new InvalidOperationException("Google direct folder rename failed");
        await api.ListChildrenAsync("root");
        if (fake.Lists != 3)
            throw new InvalidOperationException("Rename did not invalidate folder cache");
        Console.WriteLine("GOOGLE_DIRECT_API_FOLDER_CREATE_RENAME_INVALIDATE_PASS");

        fake.Limit = true;
        api.InvalidateFolder("root");
        try
        {
            await api.ListChildrenAsync("root");
            throw new InvalidOperationException("403 rate limit was silently accepted");
        }
        catch (GoogleDirectApiClient.GoogleDirectApiException error)
        {
            if (error.HttpStatus != 403 || !error.IsRateLimited ||
                error.Reason != "rateLimitExceeded")
                throw new InvalidOperationException("Google quota reason lost");
        }
        Console.WriteLine("GOOGLE_DIRECT_API_SHARED_OAUTH_403_RATE_LIMIT_CLASSIFIED_PASS");

        using var fakeOAuth = new FakeOAuthServer();
        using var oauthHttp = new HttpClient(fakeOAuth);
        var session = new GoogleDirectOAuthSession(
            oauthHttp, "TEST_CLIENT", "TEST_SECRET", "TEST_REFRESH", "",
            DateTimeOffset.MinValue, new Uri("http://127.0.0.1:18221/token"));
        var refreshed = await Task.WhenAll(Enumerable.Range(0, 6)
            .Select(_ => session.AccessTokenAsync()));
        if (refreshed.Any(x => x != "REFRESHED_TOKEN") ||
            fakeOAuth.RefreshRequests != 1)
            throw new InvalidOperationException(
                "Google direct OAuth refresh was not reused across callers");
        Console.WriteLine("GOOGLE_DIRECT_CUSTOM_OAUTH_SINGLE_REFRESH_NO_RCLONE_PASS");
    }
}
