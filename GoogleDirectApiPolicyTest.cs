using System.Net;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>Offline integration contract for the rclone-free Google REST layer.
/// Never touches a user account, real Drive files or OAuth tokens.</summary>
internal static class GoogleDirectApiPolicyTest
{
    private sealed class SlowFakeGoogleServer : HttpMessageHandler
    {
        internal int Active;
        internal int Peak;
        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            int now = Interlocked.Increment(ref Active);
            int peak;
            do { peak = Peak; }
            while (now > peak && Interlocked.CompareExchange(ref Peak, now, peak) != peak);
            try
            {
                await Task.Delay(75, cancellationToken);
                return new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(
                        """{"id":"file_1","name":"sample","mimeType":"text/plain","size":"1"}""",
                        Encoding.UTF8, "application/json")
                };
            }
            finally { Interlocked.Decrement(ref Active); }
        }
    }

    private sealed class OfflineFakeGoogleServer : HttpMessageHandler
    {
        internal int Requests;
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Interlocked.Increment(ref Requests);
            throw new HttpRequestException("OFFLINE_FAKE_TEST");
        }
    }
    private sealed class InvalidMetadataFakeServer : HttpMessageHandler
    {
        internal int Mode;
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var response = Mode switch
            {
                0 => new HttpResponseMessage(HttpStatusCode.BadGateway)
                {
                    Content = new StringContent("<html>proxy error</html>",
                        Encoding.UTF8, "text/html")
                },
                1 => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("not-json",
                        Encoding.UTF8, "text/html")
                },
                2 => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new ByteArrayContent(new byte[8 * 1024 * 1024 + 1])
                },
                3 => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(
                        """{"files":[{"id":"missing-name"}]}""",
                        Encoding.UTF8, "application/json")
                },
                4 => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"unexpected":true}""",
                        Encoding.UTF8, "application/json")
                },
                5 => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent("""{"files":{}}""",
                        Encoding.UTF8, "application/json")
                },
                _ => new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(
                        """{"files":[],"nextPageToken":123}""",
                        Encoding.UTF8, "application/json")
                }
            };
            return Task.FromResult(response);
        }
    }

    private sealed class FakeGoogleServer : HttpMessageHandler
    {
        internal int Lists;
        internal int Creates;
        internal int Renames;
        internal int Reads;
        internal int Uploads;
        internal int UploadStarts;
        internal int GeneratedIds;
        internal bool ExistingRevisionPinned;
        internal bool ExistingRevisionUpdated;
        internal bool Limit;
        internal bool Offline;
        internal bool PermissionDenied;

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.Headers.Authorization?.Scheme != "Bearer" ||
                request.Headers.Authorization.Parameter != "TEST_TOKEN")
                throw new InvalidOperationException("Direct client did not send OAuth bearer");
            if (request.RequestUri?.Host != "127.0.0.1")
                throw new InvalidOperationException("Direct API test attempted real network");

            if (Offline)
                throw new HttpRequestException("OFFLINE_DIRECTORY_TEST");
            if (PermissionDenied)
                return Response(HttpStatusCode.Forbidden,
                    """{"error":{"errors":[{"reason":"insufficientFilePermissions"}]}}""");

            if (Limit)
                return Response(HttpStatusCode.Forbidden,
                    """{"error":{"status":"PERMISSION_DENIED","errors":[{"reason":"rateLimitExceeded"}]}}""");
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.AbsolutePath.EndsWith("/files/range_ignored") &&
                request.RequestUri.Query.Contains("alt=media"))
            {
                // A broken proxy might return the whole multi-GB file
                // with HTTP 200 even when Range: bytes=0-3 was requested.
                return new HttpResponseMessage(HttpStatusCode.OK)
                { Content = new ByteArrayContent(new byte[100]) };
            }
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.Query.Contains("alt=media") &&
                request.RequestUri.AbsolutePath.EndsWith("/files/range_truncated"))
            {
                var partial = new HttpResponseMessage(HttpStatusCode.PartialContent)
                { Content = new ByteArrayContent(Encoding.ASCII.GetBytes("ABC")) };
                partial.Content.Headers.ContentRange =
                    new System.Net.Http.Headers.ContentRangeHeaderValue(0, 2, 3);
                return partial;
            }
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.Query.Contains("alt=media") &&
                request.RequestUri.AbsolutePath.EndsWith("/files/range_shortbody"))
            {
                var partial = new HttpResponseMessage(HttpStatusCode.PartialContent)
                { Content = new ByteArrayContent(Encoding.ASCII.GetBytes("AB")) };
                partial.Content.Headers.ContentRange =
                    new System.Net.Http.Headers.ContentRangeHeaderValue(0, 3, 4);
                return partial;
            }
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.Query.Contains("alt=media") &&
                request.RequestUri.AbsolutePath.EndsWith("/files/range_stale"))
                return Response(HttpStatusCode.RequestedRangeNotSatisfiable, "{}");
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.AbsolutePath.EndsWith("/files/fileA_1") &&
                !request.RequestUri.Query.Contains("alt=media"))
            {
                string md5 = ExistingRevisionUpdated
                    ? Convert.ToHexString(System.Security.Cryptography.MD5.HashData(
                        Encoding.ASCII.GetBytes("EDITED"))).ToLowerInvariant()
                    : Convert.ToHexString(System.Security.Cryptography.MD5.HashData(
                        Encoding.ASCII.GetBytes("BASE"))).ToLowerInvariant();
                return Response(HttpStatusCode.OK, JsonSerializer.Serialize(new
                {
                    id = "fileA_1", name = "sample.xlsx",
                    mimeType = "application/octet-stream",
                    size = ExistingRevisionUpdated ? "6" : "4",
                    version = ExistingRevisionUpdated ? "13" : "12",
                    headRevisionId = ExistingRevisionUpdated ? "rev_new" : "rev_old",
                    md5Checksum = md5
                }));
            }
            if (request.Method == HttpMethod.Patch &&
                request.RequestUri.AbsolutePath.EndsWith("/files/fileA_1/revisions/rev_old"))
            {
                ExistingRevisionPinned = true;
                return Response(HttpStatusCode.OK,
                    """{"id":"rev_old","keepForever":true}""");
            }
            if (request.Method == HttpMethod.Patch &&
                request.RequestUri.AbsolutePath.EndsWith("/upload/drive/v3/files/fileA_1"))
            {
                if (!ExistingRevisionPinned)
                    throw new InvalidOperationException(
                        "Google existing-file edit started without preserving old revision");
                var answer = Response(HttpStatusCode.OK, "{}");
                answer.Headers.Location = new Uri("http://127.0.0.1:18220/session/edit");
                return answer;
            }
            if (request.Method == HttpMethod.Put &&
                request.RequestUri.AbsolutePath == "/session/edit")
            {
                byte[] bytes = await request.Content!
                    .ReadAsByteArrayAsync(cancellationToken);
                if (Encoding.ASCII.GetString(bytes) != "EDITED")
                    throw new InvalidOperationException(
                        "Existing Drive revision upload content corrupted");
                ExistingRevisionUpdated = true;
                return Response(HttpStatusCode.OK,
                    JsonSerializer.Serialize(new
                    {
                        id = "fileA_1", name = "sample.xlsx",
                        mimeType = "application/octet-stream", size = "6",
                        version = "13", headRevisionId = "rev_new",
                        md5Checksum = Convert.ToHexString(
                            System.Security.Cryptography.MD5.HashData(bytes))
                            .ToLowerInvariant()
                    }));
            }
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.AbsolutePath.EndsWith("/files/generateIds"))
            {
                Interlocked.Increment(ref GeneratedIds);
                return Response(HttpStatusCode.OK, """{"ids":["pre_new_1"]}""");
            }
            if (request.Method == HttpMethod.Get &&
                request.RequestUri.AbsolutePath.EndsWith("/files/fileA_1") &&
                request.RequestUri.Query.Contains("alt=media"))
            {
                if (request.Headers.Range is not { } range ||
                    range.Ranges.First().From != 2 ||
                    range.Ranges.First().To != 5)
                    throw new InvalidOperationException("Direct API sent an incorrect byte range");
                Interlocked.Increment(ref Reads);
                var response = new HttpResponseMessage(HttpStatusCode.PartialContent)
                { Content = new ByteArrayContent(Encoding.ASCII.GetBytes("CDEF")) };
                response.Content.Headers.ContentRange =
                    new System.Net.Http.Headers.ContentRangeHeaderValue(2, 5, 10);
                return response;
            }
            if (request.Method == HttpMethod.Post &&
                request.RequestUri.AbsolutePath.EndsWith("/upload/drive/v3/files"))
            {
                using var json = JsonDocument.Parse(await request.Content!
                    .ReadAsStringAsync(cancellationToken));
                if (json.RootElement.GetProperty("name").GetString() != "new.txt" ||
                    json.RootElement.GetProperty("id").GetString() != "pre_new_1" ||
                    json.RootElement.GetProperty("parents")[0].GetString() != "root")
                    throw new InvalidOperationException("Direct new file upload targeted the wrong object");
                Interlocked.Increment(ref UploadStarts);
                var response = Response(HttpStatusCode.OK, "{}");
                response.Headers.Location = new Uri("http://127.0.0.1:18220/session/1");
                return response;
            }
            if (request.Method == HttpMethod.Put &&
                request.RequestUri.AbsolutePath == "/session/1")
            {
                string content = await request.Content!.ReadAsStringAsync(cancellationToken);
                var range = request.Content.Headers.ContentRange;
                if (content != "DATA1234" || range?.From != 0 ||
                    range.To != 7 || range.Length != 8)
                    throw new InvalidOperationException("Direct Google upload byte range or content mismatch");
                Interlocked.Increment(ref Uploads);
                return Response(HttpStatusCode.OK,
                    """{"id":"pre_new_1","name":"new.txt","mimeType":"application/octet-stream","size":"8","version":"1","md5Checksum":"e44f9e348e41cb272efa87387728571b"}""");
            }
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
        internal bool FailFirst;
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
            int requestCount = Interlocked.Increment(ref RefreshRequests);
            if (FailFirst && requestCount == 1)
                throw new IOException("SYNTHETIC_OAUTH_TRANSIENT_FAILURE");
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    """{"access_token":"REFRESHED_TOKEN","expires_in":3600,"token_type":"Bearer"}""",
                    Encoding.UTF8, "application/json")
            };
        }
    }

    private sealed class RepeatedPagesFakeServer : HttpMessageHandler
    {
        internal int Requests;
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.RequestUri?.Host != "127.0.0.1" ||
                request.Method != HttpMethod.Get)
                throw new InvalidOperationException("Synthetic paging request escaped loopback");
            Interlocked.Increment(ref Requests);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    """{"files":[],"nextPageToken":"REPEAT_TOKEN"}""",
                    Encoding.UTF8, "application/json")
            });
        }
    }

    private sealed class OpenListRefreshFakeServer : HttpMessageHandler
    {
        internal int Requests;
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (request.Method != HttpMethod.Get ||
                request.RequestUri?.Host != "127.0.0.1" ||
                !request.RequestUri.Query.Contains("server_use=true") ||
                !request.RequestUri.Query.Contains("driver_txt=googleui_go") ||
                !request.RequestUri.Query.Contains("refresh_ui=TEST_REFRESH"))
                throw new IOException("OpenList test refresh request is not scoped to its provider");
            Interlocked.Increment(ref Requests);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK)
            {
                Content = new StringContent(
                    """{"access_token":"OPENLIST_REFRESHED","refresh_token":"TEST_REFRESH"}""",
                    Encoding.UTF8, "application/json")
            });
        }
    }

    private sealed class InvalidOAuthRefreshServer : HttpMessageHandler
    {
        internal int Mode;
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
        {
            if (Mode == 3)
                throw new HttpRequestException("FAKE_OAUTH_OFFLINE");
            if (Mode == 4)
                throw new TaskCanceledException("FAKE_OAUTH_TIMEOUT");
            var result = Mode switch
            {
                0 => new HttpResponseMessage(HttpStatusCode.BadGateway)
                { Content = new StringContent("<html>gateway</html>") },
                1 => new HttpResponseMessage(HttpStatusCode.OK)
                { Content = new StringContent("not-json") },
                2 => new HttpResponseMessage(HttpStatusCode.OK)
                { Content = new StringContent("""{"expires_in":3600}""") },
                _ => new HttpResponseMessage(HttpStatusCode.OK)
                { Content = new ByteArrayContent(new byte[256 * 1024 + 1]) }
            };
            return Task.FromResult(result);
        }
    }

    internal static async Task RunAsync()
    {
        await GoogleDirectBrowserOAuthPolicyLab.RunAsync();
        await GoogleOpenListChromeAuthLab.RunAsync();
        using (var brokerServer = new OpenListRefreshFakeServer())
        using (var brokerHttp = new HttpClient(brokerServer))
        {
            var brokerSession = new GoogleDirectOAuthSession(
                brokerHttp, "openlist-public-broker", "", "TEST_REFRESH", "",
                DateTimeOffset.MinValue,
                new Uri("http://127.0.0.1:18222/googleui/renewapi"),
                openListBroker: true);
            var tokens = await Task.WhenAll(Enumerable.Range(0, 4)
                .Select(_ => brokerSession.AccessTokenAsync()));
            if (brokerServer.Requests != 1 ||
                tokens.Any(value => value != "OPENLIST_REFRESHED"))
                throw new InvalidOperationException(
                    "Public OpenList approved grant failed to renew once without rclone");
        }
        Console.WriteLine("GOOGLE_OPENLIST_EXPLICIT_BROKER_REFRESH_NO_RCLONE_PASS");
        using (var repeated = new RepeatedPagesFakeServer())
        using (var pagedHttp = new HttpClient(repeated))
        {
            var pagedApi = new GoogleDirectApiClient(pagedHttp,
                _ => Task.FromResult("TEST_TOKEN"),
                new Uri("http://127.0.0.1:18220/drive/v3/"),
                maxRetries: 0);
            bool rejected = false;
            try { await pagedApi.ListChildrenAsync("root"); }
            catch (IOException ex) when (ex.Message.Contains("pagination",
                StringComparison.Ordinal)) { rejected = true; }
            if (!rejected || repeated.Requests != 2)
                throw new InvalidOperationException(
                    "Repeated Google folder page token caused a WinFsp infinite request loop");
        }
        Console.WriteLine("GOOGLE_NATIVE_REPEATED_PAGINATION_LOOP_PREVENTION_PASS");
        using (var brokenOAuth = new InvalidOAuthRefreshServer())
        using (var brokenHttp = new HttpClient(brokenOAuth))
        {
            for (int mode = 0; mode <= 5; mode++)
            {
                brokenOAuth.Mode = mode;
                var brokenSession = new GoogleDirectOAuthSession(
                    brokenHttp, "TEST_CLIENT", "TEST_SECRET", "TEST_REFRESH", "",
                    DateTimeOffset.MinValue,
                    new Uri("http://127.0.0.1:18221/token"));
                try
                {
                    await brokenSession.AccessTokenAsync();
                    throw new InvalidOperationException(
                        "Malformed Google OAuth response was treated as a token");
                }
                catch (IOException) { }
            }
        }
        Console.WriteLine("GOOGLE_NATIVE_OAUTH_REFRESH_INVALID_RESPONSE_FAIL_CLOSED_PASS");
        using (var bad = new InvalidMetadataFakeServer())
        using (var badHttp = new HttpClient(bad))
        {
            var client = new GoogleDirectApiClient(badHttp,
                _ => Task.FromResult("TEST_TOKEN"),
                new Uri("http://127.0.0.1:18220/drive/v3/"),
                cacheTtl: TimeSpan.Zero, maxRetries: 0);
            for (int mode = 0; mode <= 6; mode++)
            {
                bad.Mode = mode;
                try
                {
                    await client.ListChildrenAsync("root");
                    throw new InvalidOperationException(
                        "Google invalid metadata was accepted by WinFsp transport");
                }
                catch (IOException ex)
                {
                    if (mode == 0 &&
                        (ex is not GoogleDirectApiClient.GoogleDirectApiException gatewayError ||
                         gatewayError.HttpStatus != 502))
                        throw new InvalidOperationException(
                            "HTML gateway error lost HTTP status", ex);
                }
            }
        }
        Console.WriteLine("GOOGLE_NATIVE_INVALID_HTTP_JSON_AND_OVERSIZED_METADATA_FAIL_CLOSED_PASS");
        using (var slow = new SlowFakeGoogleServer())
        using (var boundedHttp = new HttpClient(slow))
        {
            var bounded = new GoogleDirectApiClient(boundedHttp,
                _ => Task.FromResult("TEST_TOKEN"),
                new Uri("http://127.0.0.1:18220/drive/v3/"), maxRetries: 0);
            var parallelFiles = await Task.WhenAll(
                Enumerable.Range(0, 24).Select(index =>
                    bounded.GetFileAsync("file_" + index)));
            if (parallelFiles.Any(file => file?.Name != "sample") || slow.Peak > 8)
                throw new InvalidOperationException(
                    "Google Drive API did not bound concurrent Explorer requests");
        }
        using (var offline = new OfflineFakeGoogleServer())
        using (var offlineHttp = new HttpClient(offline))
        {
            var client = new GoogleDirectApiClient(offlineHttp,
                _ => Task.FromResult("TEST_TOKEN"),
                new Uri("http://127.0.0.1:18220/drive/v3/"), maxRetries: 0);
            for (int i = 0; i < 4; i++)
            {
                try
                {
                    await client.GetFileAsync("file_1");
                    throw new InvalidOperationException("Offline Google API did not fail");
                }
                catch (IOException) { }
            }
            if (offline.Requests != 3)
                throw new InvalidOperationException(
                    "Offline Google API continued network requests during fail-fast window");
        }
        Console.WriteLine("GOOGLE_NATIVE_EXPLORER_REQUEST_CAP_AND_FAST_FAIL_PASS");

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

        using (var outageFake = new FakeGoogleServer())
        using (var outageHttp = new HttpClient(outageFake))
        {
            var resilient = new GoogleDirectApiClient(outageHttp,
                _ => Task.FromResult("TEST_TOKEN"),
                new Uri("http://127.0.0.1:18220/drive/v3/"),
                cacheTtl: TimeSpan.FromMilliseconds(10), maxRetries: 0);
            var known = await resilient.ListChildrenAsync("root");
            await Task.Delay(25);
            outageFake.Offline = true;
            var duringOutage = await resilient.ListChildrenAsync("root");
            if (known.Count == 0 || duringOutage.Count != known.Count ||
                duringOutage[0].Name != known[0].Name || outageFake.Lists != 1)
                throw new IOException("Offline directory lost last verified entries");
            outageFake.Offline = false;
            outageFake.PermissionDenied = true;
            try
            {
                await resilient.ListChildrenAsync("root");
                throw new IOException("Google permission failure was masked by stale metadata");
            }
            catch (GoogleDirectApiClient.GoogleDirectApiException ex) when
                (ex.HttpStatus == 403 && !ex.IsRateLimited) { }
        }
        Console.WriteLine("GOOGLE_NATIVE_STALE_DIRECTORY_OFFLINE_VISIBLE_PASS");

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

        var ranged = await api.ReadRangeAsync("fileA_1", 2, 4);
        if (fake.Reads != 1 || Encoding.ASCII.GetString(ranged) != "CDEF")
            throw new InvalidOperationException("Google direct ranged content read failed");
        bool oversizedRejected = false;
        try { await api.ReadRangeAsync("range_ignored", 0, 4); }
        catch (IOException ex) when (ex.Message.Contains("excess data",
            StringComparison.Ordinal)) { oversizedRejected = true; }
        if (!oversizedRejected)
            throw new InvalidOperationException("Google ignored Range and consumed excess bytes");
        foreach (string caseName in new[]
            { "range_truncated", "range_shortbody", "range_stale" })
        {
            bool failedClosed = false;
            try { await api.ReadRangeAsync(caseName, 0, 4); }
            catch (IOException) { failedClosed = true; }
            if (!failedClosed)
                throw new InvalidOperationException(
                    "Google short/changed file was returned as valid content");
        }
        Console.WriteLine("GOOGLE_NATIVE_BOUNDED_RANGE_READ_NO_FULL_FILE_BUFFER_PASS");
        Console.WriteLine("GOOGLE_NATIVE_SHORT_RANGE_AND_STALE_FILE_FAIL_CLOSED_PASS");
        using var uploadBody = new MemoryStream(Encoding.ASCII.GetBytes("DATA1234"));
        var uploaded = await api.UploadNewAsync("root", "new.txt", uploadBody);
        if (uploaded.Id != "pre_new_1" || fake.UploadStarts != 1 ||
            fake.Uploads != 1 || fake.GeneratedIds != 1)
            throw new InvalidOperationException("Direct Google new-file upload failed");
        Console.WriteLine("GOOGLE_DIRECT_API_RANGE_READ_AND_NEW_RESUMABLE_UPLOAD_PASS");

        string originalMd5 = Convert.ToHexString(
            System.Security.Cryptography.MD5.HashData(
                Encoding.ASCII.GetBytes("BASE"))).ToLowerInvariant();
        var pinned = await api.PinCurrentBinaryRevisionAsync(
            "fileA_1", "rev_old", originalMd5);
        if (!fake.ExistingRevisionPinned ||
            pinned.HeadRevisionId != "rev_old")
            throw new InvalidOperationException("Existing Google binary was not backed up");
        using var edited = new MemoryStream(Encoding.ASCII.GetBytes("EDITED"));
        var revised = await api.UploadExistingAsync(
            "fileA_1", originalMd5, "rev_old", edited);
        if (!fake.ExistingRevisionUpdated ||
            revised.HeadRevisionId != "rev_new" || revised.Id != "fileA_1")
            throw new InvalidOperationException("Existing Google revision was not updated");
        try
        {
            await api.PinCurrentBinaryRevisionAsync(
                "fileA_1", "rev_old", originalMd5);
            throw new InvalidOperationException("Stale Google edit was allowed");
        }
        catch (IOException ex) when (ex.Message.Contains(
            "Google existing file changed", StringComparison.Ordinal)) { }
        Console.WriteLine(
            "GOOGLE_DIRECT_EXISTING_BINARY_PIN_OLD_REVISION_AND_CONFLICT_POLICY_PASS");

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
        // After a Drive is mounted, a one-hour idle period must NOT make
        // the very next Explorer read perform a synchronous OAuth refresh.
        // Run the real periodic logic with a short fake interval and a
        // loopback-only token endpoint; no browser or Drive account involved.
        using var idleOAuth = new FakeOAuthServer();
        using var idleHttp = new HttpClient(idleOAuth);
        var idleSession = new GoogleDirectOAuthSession(
            idleHttp, "TEST_CLIENT", "TEST_SECRET", "TEST_REFRESH",
            "STILL_VALID", DateTimeOffset.UtcNow.AddHours(1),
            new Uri("http://127.0.0.1:18221/token"));
        using (var idleCancel = new CancellationTokenSource())
        {
            Task idleWarmup = idleSession.KeepTokenWarmAsync(
                idleCancel.Token, TimeSpan.FromMilliseconds(20));
            await Task.Delay(90);
            idleCancel.Cancel();
            await idleWarmup;
            if (idleOAuth.RefreshRequests != 0 ||
                await idleSession.AccessTokenAsync() != "STILL_VALID")
                throw new InvalidOperationException(
                    "Google idle keepalive performed an unnecessary token renewal");
        }
        var expiringSession = new GoogleDirectOAuthSession(
            idleHttp, "TEST_CLIENT", "TEST_SECRET", "TEST_REFRESH",
            "EXPIRING", DateTimeOffset.UtcNow.AddMinutes(1),
            new Uri("http://127.0.0.1:18221/token"));
        using (var warmupCancel = new CancellationTokenSource())
        {
            Task keepWarm = expiringSession.KeepTokenWarmAsync(
                warmupCancel.Token, TimeSpan.FromMilliseconds(20));
            for (int spin = 0; spin < 100 && idleOAuth.RefreshRequests == 0; spin++)
                await Task.Delay(10);
            if (idleOAuth.RefreshRequests != 1)
                throw new InvalidOperationException(
                    "Google background refresh did not start before Explorer request");
            // The scheduled request may have begun but not yet committed
            // its token. Wait through the per-session gate BEFORE canceling
            // to avoid a timing-dependent false failure in Windows CI.
            string afterWarmup = await expiringSession.AccessTokenAsync();
            warmupCancel.Cancel();
            await keepWarm;
            if (idleOAuth.RefreshRequests != 1 ||
                afterWarmup != "REFRESHED_TOKEN")
                throw new InvalidOperationException(
                    "Google idle token was not pre-refreshed or was refreshed repeatedly");
        }
        Console.WriteLine("GOOGLE_NATIVE_IDLE_PREEMPTIVE_OAUTH_REFRESH_NO_SECOND_LOGIN_PASS");
        using var flakyOauth = new FakeOAuthServer { FailFirst = true };
        using var flakyHttp = new HttpClient(flakyOauth);
        var flakySession = new GoogleDirectOAuthSession(
            flakyHttp, "TEST_CLIENT", "TEST_SECRET", "TEST_REFRESH",
            "EXPIRING", DateTimeOffset.UtcNow.AddMinutes(1),
            new Uri("http://127.0.0.1:18221/token"));
        using (var flakyCancel = new CancellationTokenSource())
        {
            Task keepWarm = flakySession.KeepTokenWarmAsync(
                flakyCancel.Token, TimeSpan.FromMilliseconds(20));
            for (int spin = 0; spin < 100 && flakyOauth.RefreshRequests < 2; spin++)
                await Task.Delay(10);
            if (flakyOauth.RefreshRequests < 2)
                throw new InvalidOperationException(
                    "Google idle token refresh worker died after a brief offline failure");
            string recovered = await flakySession.AccessTokenAsync();
            flakyCancel.Cancel();
            await keepWarm;
            if (recovered != "REFRESHED_TOKEN" || flakyOauth.RefreshRequests != 2)
                throw new InvalidOperationException(
                    "Google OAuth background refresh did not recover without a second login");
        }
        Console.WriteLine("GOOGLE_NATIVE_IDLE_OAUTH_REFRESH_TRANSIENT_RETRY_PASS");
    }
}
