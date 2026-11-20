using System.Net;
using System.Text;
using System.Text.Json;

namespace NASMount;

/// <summary>
/// Real localhost browser callback and fake token endpoint. No Google login,
/// external network request, OAuth credentials or user Drive data involved.
/// </summary>
internal static class GoogleDirectBrowserOAuthPolicyLab
{
    private sealed class FakeTokenEndpoint : HttpMessageHandler
    {
        internal readonly TaskCompletionSource<bool> Received =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal readonly TaskCompletionSource<bool> Release =
            new(TaskCreationOptions.RunContinuationsAsynchronously);
        internal bool Deny;
        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancel)
        {
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
            await Release.Task.WaitAsync(cancel);
            return new HttpResponseMessage(Deny
                ? HttpStatusCode.BadRequest : HttpStatusCode.OK)
            {
                Content = new StringContent(Deny ?
                    """{"error":"invalid_grant"}""" :
                    """{"access_token":"OFFLINE_TEST","refresh_token":"OFFLINE_REFRESH","expires_in":3600}""",
                    Encoding.UTF8, "application/json")
            };
        }
    }

    private static string Query(string uri, string key)
    {
        var query = new Uri(uri).Query.TrimStart('?').Split('&');
        var pair = query.Single(x => x.StartsWith(key + "=",
            StringComparison.Ordinal)).Split('=', 2);
        return Uri.UnescapeDataString(pair[1]);
    }

    private static async Task ScenarioAsync(bool deny)
    {
        using var endpoint = new FakeTokenEndpoint { Deny = deny };
        using var tokenClient = new HttpClient(endpoint);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(12));
        Task<HttpResponseMessage>? callback = null;
        var authorize = GoogleDirectBrowserAuth.AuthorizeAsync(
            "123456789012-test-desktop.apps.googleusercontent.com",
            "OFFLINE_SECRET", _ => { },
            authorizationUrl =>
            {
                string destination = Query(authorizationUrl, "redirect_uri");
                string state = Query(authorizationUrl, "state");
                callback = Task.Run(async () =>
                {
                    using var browser = new HttpClient();
                    return await browser.GetAsync(destination +
                        "?code=FAKE_CODE&state=" + Uri.EscapeDataString(state),
                        timeout.Token);
                });
            }, timeout.Token, tokenClient);
        await endpoint.Received.Task.WaitAsync(timeout.Token);
        if (callback is null || callback.IsCompleted)
            throw new InvalidOperationException(
                "Browser was told login finished before token verification");
        endpoint.Release.TrySetResult(true);
        if (deny)
        {
            bool rejected = false;
            try { await authorize.WaitAsync(timeout.Token); }
            catch (IOException) { rejected = true; }
            if (!rejected)
                throw new InvalidOperationException("Rejected Google grant was accepted");
        }
        else
        {
            using var token = JsonDocument.Parse(
                await authorize.WaitAsync(timeout.Token));
            if (token.RootElement.GetProperty("access_token").GetString() !=
                "OFFLINE_TEST")
                throw new InvalidOperationException("OAuth grant had wrong access token");
        }
        using var response = await callback.WaitAsync(timeout.Token);
        if (response.IsSuccessStatusCode == deny)
            throw new InvalidOperationException("Browser callback displayed incorrect status");
        string body = await response.Content.ReadAsStringAsync(timeout.Token);
        if (body.Contains("로그인 완료", StringComparison.Ordinal) == deny)
            throw new InvalidOperationException("Browser completion text did not match token grant");
    }

    internal static async Task RunAsync()
    {
        await ScenarioAsync(false);
        await ScenarioAsync(true);
        Console.WriteLine("GOOGLE_BROWSER_LOOPBACK_TOKEN_EXCHANGE_BEFORE_SUCCESS_PASS");
    }
}
