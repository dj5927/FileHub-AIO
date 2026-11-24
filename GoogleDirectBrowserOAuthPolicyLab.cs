using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Real loopback callbacks and fake Google HTTP endpoints. No external request,
/// browser profile, real credentials, account or Drive data is used.
/// </summary>
internal static class GoogleDirectBrowserOAuthPolicyLab
{
    private const string ClientId =
        "123456789012-test-desktop.apps.googleusercontent.com";
    private const string ClientSecret = "OFFLINE_SECRET";
    private const string PrivatePayload = "SYNTHETIC_PRIVATE_ERROR_PAYLOAD";

    private sealed class FakeTokenEndpoint : HttpMessageHandler
    {
        internal readonly TaskCompletionSource<bool> Received =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal readonly TaskCompletionSource<bool> Release =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal bool Deny;
        internal bool Block = true;
        internal int Requests;
        internal int AccountRequests;
        internal bool AccountVerified = true;
        internal Func<HttpResponseMessage>? Reply;

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancel)
        {
            if (request.Method == HttpMethod.Get &&
                request.RequestUri?.AbsoluteUri ==
                    "https://www.googleapis.com/oauth2/v2/userinfo?fields=email,verified_email")
            {
                Interlocked.Increment(ref AccountRequests);
                if (request.Headers.Authorization?.Scheme != "Bearer" ||
                    request.Headers.Authorization.Parameter != "OFFLINE_TEST")
                    throw new IOException("Account check did not use the approved token");
                return JsonResponse(AccountVerified
                    ? """{"email":"test@example.invalid","verified_email":true}"""
                    : """{"email":"test@example.invalid","verified_email":false}""");
            }
            Interlocked.Increment(ref Requests);
            if (request.Method != HttpMethod.Post ||
                request.RequestUri?.AbsoluteUri !=
                    "https://oauth2.googleapis.com/token")
                throw new IOException("OAuth lab unexpectedly sent a request");
            string form = await request.Content!.ReadAsStringAsync(cancel);
            if (!form.Contains("code=FAKE_CODE", StringComparison.Ordinal) ||
                !form.Contains("code_verifier=", StringComparison.Ordinal) ||
                !form.Contains("grant_type=authorization_code", StringComparison.Ordinal))
                throw new IOException("OAuth lab did not exchange the approved PKCE code");
            Received.TrySetResult(true);
            if (Block) await Release.Task.WaitAsync(cancel);
            return Reply?.Invoke() ?? JsonResponse(Deny
                ? """{"error":"invalid_grant"}"""
                : """{"access_token":"OFFLINE_TEST","refresh_token":"OFFLINE_REFRESH","expires_in":3600,"scope":"https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/drive"}""",
                Deny ? HttpStatusCode.BadRequest : HttpStatusCode.OK);
        }
    }

    private sealed class FakeReadinessEndpoint(
        Func<HttpRequestMessage, int, HttpResponseMessage> reply) : HttpMessageHandler
    {
        internal int Requests;
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancel)
        {
            cancel.ThrowIfCancellationRequested();
            if (request.Method != HttpMethod.Get ||
                request.RequestUri?.Host != "accounts.google.com" ||
                request.RequestUri.Scheme != "https" ||
                request.RequestUri.Query.Contains("client_secret", StringComparison.Ordinal) ||
                request.RequestUri.Query.Contains(ClientSecret, StringComparison.Ordinal) ||
                request.Headers.Authorization is not null ||
                request.Headers.Contains("Cookie"))
                throw new InvalidOperationException("Publisher probe sent private credentials");
            return Task.FromResult(reply(request, ++Requests));
        }
    }

    private sealed class UnmeasuredStream(byte[] bytes) : MemoryStream(bytes)
    {
        public override bool CanSeek => false;
    }

    private static HttpResponseMessage JsonResponse(string body,
        HttpStatusCode status = HttpStatusCode.OK) => new(status)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json")
    };

    private static HttpResponseMessage Redirect(string target)
    {
        var response = new HttpResponseMessage(HttpStatusCode.Found);
        response.Headers.Location = new Uri(target, UriKind.RelativeOrAbsolute);
        return response;
    }

    private static string Query(string uri, string key)
    {
        var pair = new Uri(uri).Query.TrimStart('?').Split('&')
            .Single(x => x.StartsWith(key + "=", StringComparison.Ordinal)).Split('=', 2);
        return Uri.UnescapeDataString(pair[1]);
    }

    private static async Task ObserveAsync(Task? task)
    {
        if (task is null) return;
        try { await task; } catch { }
    }

    private static async Task CheckBrowserAsync(Task<HttpResponseMessage> callback,
        bool success, CancellationToken cancel)
    {
        using var response = await callback.WaitAsync(cancel);
        string body = await response.Content.ReadAsStringAsync(cancel);
        if (response.IsSuccessStatusCode != success ||
            body.Contains("로그인 완료", StringComparison.Ordinal) != success ||
            body.Contains(PrivatePayload, StringComparison.Ordinal))
            throw new InvalidOperationException("Browser callback showed incorrect or private output");
    }

    private static async Task ScenarioAsync(bool deny, bool failPersistence = false)
    {
        using var endpoint = new FakeTokenEndpoint { Deny = deny };
        using var tokenClient = new HttpClient(endpoint);
        using var browser = new HttpClient();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        Task<HttpResponseMessage>? callback = null;
        bool encryptedGrantSaved = false;
        var authorize = GoogleDirectBrowserAuth.AuthorizeAsync(
            ClientId, ClientSecret, _ => { },
            authorizationUrl =>
            {
                string destination = Query(authorizationUrl, "redirect_uri");
                string state = Query(authorizationUrl, "state");
                if (Query(authorizationUrl, "prompt") != "select_account consent")
                    throw new InvalidOperationException("Account choice and consent were not requested");
                if (Query(authorizationUrl, "scope") != GoogleDirectBrowserAuth.RequestedScopes)
                    throw new InvalidOperationException("Minimal granular Drive permission was not requested");
                callback = browser.GetAsync(destination +
                    "?code=FAKE_CODE&state=" + Uri.EscapeDataString(state), timeout.Token);
            }, timeout.Token, tokenClient,
            finalizeGrant: (_, _) =>
            {
                if (failPersistence)
                    throw new IOException("FAKE_DPAPI_SAVE_FAILURE");
                encryptedGrantSaved = true;
                return Task.CompletedTask;
            });
        try
        {
            await endpoint.Received.Task.WaitAsync(timeout.Token);
            if (callback is null || callback.IsCompleted)
                throw new InvalidOperationException(
                    "Browser was told login finished before token verification");
            endpoint.Release.TrySetResult(true);
            if (deny || failPersistence)
            {
                bool rejected = false;
                try { await authorize.WaitAsync(timeout.Token); }
                catch (IOException) { rejected = true; }
                if (!rejected || encryptedGrantSaved)
                    throw new InvalidOperationException("Rejected Google grant was accepted");
            }
            else
            {
                using var token = JsonDocument.Parse(await authorize.WaitAsync(timeout.Token));
                if (token.RootElement.GetProperty("access_token").GetString() !=
                    "OFFLINE_TEST" || !encryptedGrantSaved || endpoint.AccountRequests != 1)
                    throw new InvalidOperationException("OAuth grant was not committed");
            }
            await CheckBrowserAsync(callback!, !(deny || failPersistence), timeout.Token);
        }
        finally
        {
            timeout.Cancel();
            await ObserveAsync(authorize);
            await ObserveAsync(callback);
        }
    }

    private static async Task DeniedCallbackAsync()
    {
        using var endpoint = new FakeTokenEndpoint();
        using var client = new HttpClient(endpoint);
        using var browser = new HttpClient();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        Task<HttpResponseMessage>? callback = null;
        bool saved = false;
        var authorize = GoogleDirectBrowserAuth.AuthorizeAsync(
            ClientId, ClientSecret, _ => { },
            url => callback = browser.GetAsync(Query(url, "redirect_uri") +
                "?error=access_denied&state=" + Uri.EscapeDataString(Query(url, "state")) +
                "&error_description=" + PrivatePayload, timeout.Token),
            timeout.Token, client, (_, _) => { saved = true; return Task.CompletedTask; });
        try
        {
            bool rejected = false;
            try { await authorize.WaitAsync(timeout.Token); }
            catch (GoogleDirectBrowserAuth.AuthorizationException ex)
            {
                rejected = ex.ErrorCode == "access_denied" &&
                    !ex.Message.Contains(PrivatePayload, StringComparison.Ordinal);
            }
            if (!rejected || saved || endpoint.Requests != 0)
                throw new InvalidOperationException("Browser denial did not stop safely before exchange");
            await CheckBrowserAsync(callback!, false, timeout.Token);
        }
        finally
        {
            timeout.Cancel();
            await ObserveAsync(authorize);
            await ObserveAsync(callback);
        }
    }

    private static async Task SendMalformedLineAsync(Uri destination, CancellationToken cancel)
    {
        using var socket = new TcpClient();
        await socket.ConnectAsync(IPAddress.Loopback, destination.Port, cancel);
        using var stream = socket.GetStream();
        await stream.WriteAsync(Encoding.ASCII.GetBytes("GET \r\n\r\n"), cancel);
        using var reader = new StreamReader(stream, Encoding.ASCII);
        string? first = await reader.ReadLineAsync(cancel);
        if (first != "HTTP/1.1 400 Bad Request")
            throw new InvalidOperationException("Malformed request was not rejected");
    }

    private static async Task MalformedBeforeApprovalAsync()
    {
        using var endpoint = new FakeTokenEndpoint { Block = false };
        using var client = new HttpClient(endpoint);
        using var browser = new HttpClient();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        Task<HttpResponseMessage>? callback = null;
        async Task<HttpResponseMessage> SendCallbacks(string url)
        {
            string destination = Query(url, "redirect_uri");
            string state = Uri.EscapeDataString(Query(url, "state"));
            await SendMalformedLineAsync(new Uri(destination), timeout.Token);
            foreach (string badQuery in new[]
            {
                "?code=FAKE_CODE&state=" + state + "&state=" + state,
                "?code=FAKE_CODE&state=" + state + "&code=FAKE_CODE",
                "?error=access_denied&state=WRONG",
                "?code=FAKE_CODE&error=access_denied&state=" + state,
                "?code=%FF&state=" + state
            })
            {
                using var response = await browser.GetAsync(destination + badQuery, timeout.Token);
                if (response.StatusCode != HttpStatusCode.BadRequest || endpoint.Requests != 0)
                    throw new InvalidOperationException("Malformed callback reached token exchange");
            }
            return await browser.GetAsync(destination + "?code=FAKE_CODE&state=" +
                state, timeout.Token);
        }
        var authorize = GoogleDirectBrowserAuth.AuthorizeAsync(ClientId,
            ClientSecret, _ => { }, url => callback = SendCallbacks(url),
            timeout.Token, client);
        try
        {
            using var token = JsonDocument.Parse(await authorize.WaitAsync(timeout.Token));
            if (endpoint.Requests != 1)
                throw new InvalidOperationException("Callback retry made duplicate token requests");
            await CheckBrowserAsync(callback!, true, timeout.Token);
        }
        finally
        {
            timeout.Cancel();
            await ObserveAsync(authorize);
            await ObserveAsync(callback);
        }
    }

    private static async Task InvalidTokenReplyAsync(
        Func<HttpResponseMessage> reply, string expectedCode)
    {
        using var endpoint = new FakeTokenEndpoint { Block = false, Reply = reply };
        using var client = new HttpClient(endpoint);
        using var browser = new HttpClient();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        Task<HttpResponseMessage>? callback = null;
        bool saved = false;
        var authorize = GoogleDirectBrowserAuth.AuthorizeAsync(
            ClientId, ClientSecret, _ => { },
            url => callback = browser.GetAsync(Query(url, "redirect_uri") +
                "?code=FAKE_CODE&state=" + Uri.EscapeDataString(Query(url, "state")),
                timeout.Token), timeout.Token, client,
            (_, _) => { saved = true; return Task.CompletedTask; });
        try
        {
            bool rejected = false;
            try { await authorize.WaitAsync(timeout.Token); }
            catch (GoogleDirectBrowserAuth.AuthorizationException ex)
            {
                rejected = ex.ErrorCode == expectedCode &&
                    !ex.Message.Contains(PrivatePayload, StringComparison.Ordinal);
            }
            if (!rejected || saved || endpoint.Requests != 1)
                throw new InvalidOperationException("Invalid token reply was not rejected safely");
            await CheckBrowserAsync(callback!, false, timeout.Token);
        }
        finally
        {
            timeout.Cancel();
            await ObserveAsync(authorize);
            await ObserveAsync(callback);
        }
    }

    private static async Task PublisherReadinessAsync()
    {
        using (var handler = new FakeReadinessEndpoint((request, count) =>
        {
            if (count == 1)
            {
                if (request.RequestUri!.AbsolutePath != "/o/oauth2/v2/auth" ||
                    Query(request.RequestUri.AbsoluteUri, "prompt") != "select_account consent" ||
                    Query(request.RequestUri.AbsoluteUri, "code_challenge_method") != "S256")
                    throw new InvalidOperationException("Probe did not check the actual PKCE authorization");
                return Redirect("/v3/signin/identifier");
            }
            return new HttpResponseMessage(HttpStatusCode.OK)
            {
                // Its body must not be buffered merely to recognize sign-in.
                Content = new StringContent(new string('x', 300000))
            };
        }))
        using (var client = new HttpClient(handler))
        {
            await GoogleDirectBrowserAuth.CheckPublisherAsync(
                ClientId, ClientSecret, probeClient: client);
            if (handler.Requests != 2)
                throw new InvalidOperationException("Probe did not follow the Google login redirect");
        }
        string encodedError = Convert.ToBase64String(Encoding.UTF8.GetBytes(
            "deleted_client:" + PrivatePayload)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        foreach (var item in new[]
        {
            (Target: "/signin/oauth/error?authError=" + encodedError, Code: "deleted_client"),
            (Target: "/signin/oauth/error?error=access_denied&error_description=" + PrivatePayload,
                Code: "access_denied"),
            (Target: "https://unrelated.invalid/oauth", Code: "provider_unavailable"),
            (Target: "http://127.0.0.1:49152/callback?code=FAKE", Code: "provider_unavailable")
        })
        {
            using var handler = new FakeReadinessEndpoint((_, _) => Redirect(item.Target));
            using var client = new HttpClient(handler);
            bool rejected = false;
            try
            {
                await GoogleDirectBrowserAuth.CheckPublisherAsync(
                    ClientId, ClientSecret, probeClient: client);
            }
            catch (GoogleDirectBrowserAuth.AuthorizationException ex)
            {
                rejected = ex.ErrorCode == item.Code &&
                    !ex.Message.Contains(PrivatePayload, StringComparison.Ordinal);
            }
            if (!rejected || handler.Requests != 1)
                throw new InvalidOperationException("Probe followed an unsafe or rejected authorization");
        }
        using (var handler = new FakeReadinessEndpoint((_, _) =>
            JsonResponse("""{"error":"deleted_client","error_description":"SYNTHETIC_PRIVATE_ERROR_PAYLOAD"}""",
                HttpStatusCode.Unauthorized)))
        using (var client = new HttpClient(handler))
        {
            bool rejected = false;
            try
            {
                await GoogleDirectBrowserAuth.CheckPublisherAsync(
                    ClientId, ClientSecret, probeClient: client);
            }
            catch (GoogleDirectBrowserAuth.AuthorizationException ex)
            { rejected = ex.ErrorCode == "deleted_client" && !ex.Message.Contains(PrivatePayload); }
            if (!rejected) throw new InvalidOperationException("Probe accepted a deleted Google client");
        }
        using (var handler = new FakeReadinessEndpoint((_, _) =>
            JsonResponse("{}", HttpStatusCode.OK)))
        using (var client = new HttpClient(handler))
        {
            bool rejected = false;
            try
            {
                await GoogleDirectBrowserAuth.CheckPublisherAsync(
                    ClientId, ClientSecret, probeClient: client);
            }
            catch (GoogleDirectBrowserAuth.AuthorizationException ex)
            { rejected = ex.ErrorCode == "provider_unavailable"; }
            if (!rejected) throw new InvalidOperationException("Probe accepted HTTP 200 without Google sign-in");
        }
    }

    private static async Task PartialGrantAsync(string scopes,
        string expectedCode, bool verifiedEmail, int expectedAccountCalls)
    {
        using var endpoint = new FakeTokenEndpoint
        {
            Block = false,
            AccountVerified = verifiedEmail,
            Reply = () => JsonResponse(JsonSerializer.Serialize(new
            {
                access_token = "OFFLINE_TEST",
                refresh_token = "OFFLINE_REFRESH",
                scope = scopes
            }))
        };
        using var client = new HttpClient(endpoint);
        using var browser = new HttpClient();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
        Task<HttpResponseMessage>? callback = null;
        bool persisted = false;
        var authorize = GoogleDirectBrowserAuth.AuthorizeAsync(ClientId, ClientSecret,
            _ => { }, url => callback = browser.GetAsync(Query(url, "redirect_uri") +
                "?code=FAKE_CODE&state=" + Uri.EscapeDataString(Query(url, "state")),
                timeout.Token), timeout.Token, client,
            (_, _) => { persisted = true; return Task.CompletedTask; });
        try
        {
            bool rejected = false;
            try { await authorize.WaitAsync(timeout.Token); }
            catch (GoogleDirectBrowserAuth.AuthorizationException ex)
            { rejected = ex.ErrorCode == expectedCode; }
            if (!rejected || persisted || endpoint.Requests != 1 ||
                endpoint.AccountRequests != expectedAccountCalls)
                throw new InvalidOperationException("Partial grant was not handled safely");
            using var response = await callback!.WaitAsync(timeout.Token);
            string body = await response.Content.ReadAsStringAsync(timeout.Token);
            if (response.IsSuccessStatusCode ||
                body.Contains("로그인 완료", StringComparison.Ordinal) ||
                body.Contains("OFFLINE_TEST", StringComparison.Ordinal))
                throw new InvalidOperationException("Partial grant falsely reported success");
            if (expectedCode == "drive_scope_not_granted" &&
                (!body.Contains("test@example.invalid", StringComparison.Ordinal) ||
                 !body.Contains("연결하지 않았습니다", StringComparison.Ordinal)))
                throw new InvalidOperationException("Partial grant did not explain account and Drive denial");
        }
        finally
        {
            timeout.Cancel();
            await ObserveAsync(authorize);
            await ObserveAsync(callback);
        }
    }

    private static async Task CompletionPageRetryAsync()
    {
        using var endpoint = new FakeTokenEndpoint { Block = false };
        using var client = new HttpClient(endpoint);
        using var browser = new HttpClient();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        using var authLifetime = CancellationTokenSource.CreateLinkedTokenSource(timeout.Token);
        string? callbackUrl = null;
        int saves = 0;
        var ready = new TaskCompletionSource<string>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var authorize = GoogleDirectBrowserAuth.AuthorizeAsync(ClientId, ClientSecret,
            _ => { }, url => ready.SetResult(Query(url, "redirect_uri") +
                "?code=FAKE_CODE&state=" + Uri.EscapeDataString(Query(url, "state"))),
            authLifetime.Token, client, (_, _) =>
            { Interlocked.Increment(ref saves); return Task.CompletedTask; });
        try
        {
            callbackUrl = await ready.Task.WaitAsync(timeout.Token);
            var uri = new Uri(callbackUrl);
            using var socket = new TcpClient();
            await socket.ConnectAsync(IPAddress.Loopback, uri.Port, timeout.Token);
            using var stream = socket.GetStream();
            // Real browser requests can arrive as several TCP segments,
            // including a header much larger than a single network read.
            string first = "GET " + uri.PathAndQuery + " HTTP/1.1\r\nHost: " +
                uri.Authority + "\r\n";
            await stream.WriteAsync(Encoding.ASCII.GetBytes(first), timeout.Token);
            await Task.Delay(100, timeout.Token);
            if (endpoint.Requests != 0)
                throw new InvalidOperationException("Exchange began before full headers arrived");
            await stream.WriteAsync(Encoding.ASCII.GetBytes("X-Browser-Test: " +
                new string('x', 12000) + "\r\nConnection: close\r\n\r\n"), timeout.Token);
            using var reader = new StreamReader(stream, Encoding.UTF8);
            string response = await reader.ReadToEndAsync(timeout.Token);
            await authorize.WaitAsync(timeout.Token);
            if (!response.StartsWith("HTTP/1.1 200 OK", StringComparison.Ordinal) ||
                !response.Contains("Google Drive access authorized", StringComparison.Ordinal) ||
                !response.Contains("history.replaceState", StringComparison.Ordinal) ||
                !response.Contains("Content-Security-Policy:", StringComparison.Ordinal) ||
                response.Contains("FAKE_CODE", StringComparison.Ordinal) ||
                response.Contains("OFFLINE_TEST", StringComparison.Ordinal) ||
                response.Contains("OFFLINE_REFRESH", StringComparison.Ordinal))
                throw new InvalidOperationException("Initial completion page incorrect");
            var match = System.Text.RegularExpressions.Regex.Match(response,
                @"/complete/[A-Za-z0-9_-]{32}");
            if (!match.Success)
                throw new InvalidOperationException("Completion URL not found");
            // The dialog is now gone, but the local result remains available
            // to the browser without reusing an authorization code.
            authLifetime.Cancel();
            string cleanUrl = uri.GetLeftPart(UriPartial.Authority) + match.Value;
            foreach (string retryUrl in new[] { callbackUrl, cleanUrl, cleanUrl })
            {
                using var retry = await browser.GetAsync(retryUrl, timeout.Token);
                string body = await retry.Content.ReadAsStringAsync(timeout.Token);
                if (!retry.IsSuccessStatusCode ||
                    !body.Contains("Google Drive access authorized", StringComparison.Ordinal) ||
                    body.Contains("FAKE_CODE", StringComparison.Ordinal))
                    throw new InvalidOperationException("Completion page retry failed");
            }
            using (var wrong = await browser.GetAsync(uri.GetLeftPart(UriPartial.Authority) +
                "/callback?code=WRONG&state=" +
                Uri.EscapeDataString(Query(callbackUrl, "state")), timeout.Token))
            {
                string body = await wrong.Content.ReadAsStringAsync(timeout.Token);
                if (wrong.IsSuccessStatusCode ||
                    body.Contains("test@example.invalid", StringComparison.Ordinal))
                    throw new InvalidOperationException("Wrong callback replay exposed the result");
            }
            using (var wrong = await browser.GetAsync(uri.GetLeftPart(UriPartial.Authority) +
                "/complete/not-authorized", timeout.Token))
            {
                string body = await wrong.Content.ReadAsStringAsync(timeout.Token);
                if (wrong.IsSuccessStatusCode ||
                    body.Contains("test@example.invalid", StringComparison.Ordinal))
                    throw new InvalidOperationException("Guessed completion path exposed the result");
            }
            if (endpoint.Requests != 1 || endpoint.AccountRequests != 1 || saves != 1)
                throw new InvalidOperationException("Completion retries caused repeat authorization");
        }
        finally
        {
            authLifetime.Cancel();
            await ObserveAsync(authorize);
        }
    }

    internal static async Task RunAsync()
    {
        await ScenarioAsync(false);
        await ScenarioAsync(true);
        await ScenarioAsync(false, failPersistence: true);
        Console.WriteLine("GOOGLE_BROWSER_LOOPBACK_TOKEN_EXCHANGE_BEFORE_SUCCESS_PASS");
        Console.WriteLine("GOOGLE_BROWSER_GRANT_DPAPI_SAVE_BEFORE_SUCCESS_PASS");
        await DeniedCallbackAsync();
        Console.WriteLine("GOOGLE_BROWSER_DENIAL_TERMINATES_WITHOUT_TOKEN_PASS");
        await MalformedBeforeApprovalAsync();
        Console.WriteLine("GOOGLE_BROWSER_MALFORMED_CALLBACK_RETRY_PASS");
        await InvalidTokenReplyAsync(() => JsonResponse("{"),
            "invalid_token_response");
        await InvalidTokenReplyAsync(() => JsonResponse(
            """{"access_token":12,"refresh_token":"OFFLINE_REFRESH"}"""),
            "invalid_token_response");
        await InvalidTokenReplyAsync(() => JsonResponse(
            """{"access_token":"OFFLINE_TEST"}"""), "invalid_token_response");
        await InvalidTokenReplyAsync(() => JsonResponse(
            """{"access_token":"OFFLINE_TEST","scope":"https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/drive"}"""),
            "invalid_token_response");
        await InvalidTokenReplyAsync(() => JsonResponse(new string('x', 300000)),
            "invalid_token_response");
        await InvalidTokenReplyAsync(() => new HttpResponseMessage(HttpStatusCode.OK)
        {
            Content = new StreamContent(new UnmeasuredStream(new byte[300000]))
        }, "invalid_token_response");
        await InvalidTokenReplyAsync(() => JsonResponse(
            """{"error":"deleted_client","error_description":"SYNTHETIC_PRIVATE_ERROR_PAYLOAD"}""",
            HttpStatusCode.Unauthorized), "deleted_client");
        Console.WriteLine("GOOGLE_BROWSER_TOKEN_RESPONSE_BOUNDS_PASS");
        await CompletionPageRetryAsync();
        Console.WriteLine("GOOGLE_BROWSER_COMPLETE_HEADERS_AND_RELOAD_WITHOUT_REEXCHANGE_PASS");
        await PartialGrantAsync(GoogleDirectBrowserAuth.EmailScope,
            "drive_scope_not_granted", true, 1);
        await PartialGrantAsync(GoogleDirectBrowserAuth.EmailScope + " " +
            GoogleDirectBrowserAuth.DriveScope + ".readonly",
            "drive_scope_not_granted", true, 1);
        await PartialGrantAsync(GoogleDirectBrowserAuth.DriveScope,
            "account_scope_not_granted", true, 0);
        await PartialGrantAsync(GoogleDirectBrowserAuth.RequestedScopes,
            "account_identity_unavailable", false, 1);
        Console.WriteLine("GOOGLE_GRANULAR_SCOPE_DENIAL_AND_ACCOUNT_CHECK_PASS");
        await PublisherReadinessAsync();
        Console.WriteLine("GOOGLE_PUBLISHER_ANONYMOUS_READINESS_POLICY_PASS");
        var launcher = new GoogleDirectBrowserAuth.AuthorizationException("browser_launch_failed");
        var unknown = new GoogleDirectBrowserAuth.AuthorizationException(PrivatePayload);
        if (launcher.ErrorCode != "browser_launch_failed" ||
            unknown.ErrorCode != "authorization_error" || unknown.Message.Contains(PrivatePayload))
            throw new InvalidOperationException("Authorization error text leaked an unknown payload");
        Console.WriteLine("GOOGLE_BROWSER_SANITIZED_ERROR_POLICY_PASS");
    }
}
