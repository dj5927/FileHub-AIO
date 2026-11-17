using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace NASMount;

/// <summary>
/// Interactive, first-party TeraBox web login. The app never sees or stores
/// the TeraBox account ID/password: users enter them only on the provider's
/// HTTPS login page. Only the ndus cookie created in this dedicated WebView2
/// profile is returned after explicit use of the 'Import' workflow.
/// Never inspect Chrome/Edge's default cookie database.
/// </summary>
internal sealed class TeraBoxWebLogin : Form
{
    private const string LoginUrl = "https://www.terabox.com/korean/login";
    private static readonly string[] CookieUris = [
        "https://www.terabox.com/",
        "https://www.terabox.com/korean/",
        "https://www.terabox.app/",
        "https://terabox.app/"
    ];
    private readonly WebView2 browser = new();
    private readonly Label address = new();
    private readonly Label status = new();
    private readonly Button import = new();
    private readonly System.Windows.Forms.Timer captureTimer = new() { Interval = 1700 };
    private bool checking, initialized, closing;
    internal string? ImportedCookie { get; private set; }

    internal TeraBoxWebLogin()
    {
        Text = "TeraBox 로그인 · ndus 자동 불러오기";
        ClientSize = new Size(970, 740);
        MinimumSize = new Size(750, 550);
        StartPosition = FormStartPosition.CenterParent;
        ShowInTaskbar = false;
        BackColor = Color.FromArgb(31, 34, 39);
        ForeColor = Color.White;
        Font = new Font("Malgun Gothic", 9.0f);
        AutoScaleMode = AutoScaleMode.Dpi;
        ModernTheme.EnableDarkTitleBar(this);

        var heading = new Label {
            Dock = DockStyle.Top, Height = 51,
            Padding = new Padding(14, 12, 14, 0),
            Text = "TeraBox 공식 웹사이트에서 로그인하세요. 계정 암호는 FileHub AIO에 저장되지 않습니다.",
            ForeColor = Color.FromArgb(220, 230, 238)
        };
        var top = new Panel {
            Dock = DockStyle.Top, Height = 24, Padding = new Padding(14, 2, 0, 2),
            BackColor = Color.FromArgb(31, 34, 39)
        };
        address.Dock = DockStyle.Fill;
        address.Text = "접속 준비 중 · https://www.terabox.com";
        top.Controls.Add(address);
        var bottom = new Panel {
            Dock = DockStyle.Bottom, Height = 68,
            BackColor = Color.FromArgb(38, 42, 48)
        };
        status.Dock = DockStyle.Fill;
        status.Padding = new Padding(12, 11, 8, 0);
        status.Text = "로그인·보안 인증이 끝나면 ndus를 자동으로 확인합니다.";
        status.ForeColor = Color.FromArgb(188, 205, 221);
        import.Text = "로그인 확인";
        import.Width = 100;
        import.Height = 32;
        import.Anchor = AnchorStyles.Right | AnchorStyles.Bottom;
        import.BackColor = Color.FromArgb(27, 130, 224);
        import.FlatStyle = FlatStyle.Flat;
        import.FlatAppearance.BorderSize = 0;
        import.ForeColor = Color.White;
        import.Click += async (_, _) => await TryCaptureAsync();
        var cancel = new Button {
            Text = "취소", Width = 78, Height = 32,
            Anchor = AnchorStyles.Right | AnchorStyles.Bottom,
            BackColor = Color.FromArgb(75, 78, 84),
            FlatStyle = FlatStyle.Flat, ForeColor = Color.White
        };
        cancel.Click += (_, _) => Close();
        void PositionButtons()
        {
            cancel.Location = new Point(bottom.ClientSize.Width - 92, 18);
            import.Location = new Point(bottom.ClientSize.Width - 203, 18);
            status.Padding = new Padding(12, 8,
                Math.Max(215, bottom.ClientSize.Width / 3), 0);
        }
        bottom.Resize += (_, _) => PositionButtons();
        bottom.Controls.Add(status);
        bottom.Controls.Add(cancel);
        bottom.Controls.Add(import);
        cancel.BringToFront();
        import.BringToFront();
        PositionButtons();
        browser.Dock = DockStyle.Fill;
        browser.DefaultBackgroundColor = Color.White;
        Controls.Add(browser);
        Controls.Add(bottom);
        Controls.Add(top);
        Controls.Add(heading);
        captureTimer.Tick += async (_, _) => await TryCaptureAsync();
        Shown += async (_, _) => await OpenBrowserAsync();
        FormClosing += (_, _) => {
            closing = true;
            captureTimer.Stop();
        };
        FormClosed += (_, _) => {
            captureTimer.Dispose();
            browser.Dispose();
        };
    }

    private async Task OpenBrowserAsync()
    {
        if (initialized || closing) return;
        initialized = true;
        try
        {
            // Dedicated per-app browser profile, NEVER the system Chrome/
            // Edge profile or the user's personal browser cookie store.
            // Cookies remain in the current Windows user's local app profile
            // for convenient future re-authentication, not in config.json.
            // LocalAppData (not roaming AppData) prevents website session
            // cache from being unintentionally copied by roaming profiles.
            string folder = Path.Combine(
                Environment.GetFolderPath(
                    Environment.SpecialFolder.LocalApplicationData),
                "NASMount", "TeraboxLoginWebView2");
            Directory.CreateDirectory(folder);
            var environment = await CoreWebView2Environment.CreateAsync(
                userDataFolder: folder);
            if (closing) return;
            await browser.EnsureCoreWebView2Async(environment);
            if (closing) return;
            browser.CoreWebView2.Settings.AreDevToolsEnabled = false;
            browser.CoreWebView2.Settings.AreDefaultContextMenusEnabled = false;
            browser.CoreWebView2.Settings.IsPasswordAutosaveEnabled = false;
            browser.CoreWebView2.Settings.IsGeneralAutofillEnabled = false;
            browser.CoreWebView2.NavigationCompleted += async (_, _) => {
                UpdateAddress();
                await TryCaptureAsync();
            };
            browser.CoreWebView2.SourceChanged += (_, _) => UpdateAddress();
            browser.CoreWebView2.Navigate(LoginUrl);
            captureTimer.Start();
        }
        catch (Exception ex)
        {
            captureTimer.Stop();
            status.Text = "로그인 창을 실행할 수 없습니다. Edge WebView2 Runtime 설치를 확인하세요.";
            AppStore.Log("TERABOX_WEB_LOGIN_UI_START_FAILED " + ex.GetType().Name);
            MessageBox.Show(this, "TeraBox 로그인 창 실행에 실패했습니다.\n" +
                "Microsoft Edge WebView2 Runtime이 필요합니다.\n\n" +
                ex.Message, "TeraBox 로그인", MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
        }
    }

    private void UpdateAddress()
    {
        try
        {
            string url = browser.CoreWebView2?.Source ?? LoginUrl;
            if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
            {
                address.Text = "현재 사이트: " + uri.Host;
                address.ForeColor = uri.Scheme == Uri.UriSchemeHttps
                    ? Color.FromArgb(133, 204, 240)
                    : Color.FromArgb(255, 168, 91);
            }
        }
        catch { }
    }

    private async Task TryCaptureAsync()
    {
        if (checking || closing || browser.CoreWebView2 is null) return;
        checking = true;
        try
        {
            var candidates = new List<CookieCandidate>();
            foreach (var uri in CookieUris)
            {
                var list = await browser.CoreWebView2.CookieManager.GetCookiesAsync(uri);
                if (closing) return;
                foreach (var cookie in list)
                    if (cookie.Name.Equals("ndus", StringComparison.OrdinalIgnoreCase))
                        candidates.Add(new(cookie.Name, cookie.Domain, cookie.Value,
                            cookie.Expires, cookie.IsSession));
            }
            string? result = SelectValidNdus(candidates, DateTimeOffset.UtcNow);
            if (result is null) return; // Login/captcha still in progress.
            ImportedCookie = "ndus=" + result;
            status.Text = "ndus를 자동으로 가져왔습니다. FileHub AIO에서 저장하면 됩니다.";
            DialogResult = DialogResult.OK;
            Close();
        }
        catch (Exception ex)
        {
            if (!closing)
            {
                status.Text = "로그인 완료 후 [로그인 확인]을 눌러 다시 시도하세요.";
                AppStore.Log("TERABOX_WEB_COOKIE_CHECK_FAILED " + ex.GetType().Name);
            }
        }
        finally { checking = false; }
    }

    internal sealed record CookieCandidate(string Name, string Domain,
        string Value, DateTime Expires, bool IsSession);

    internal static string? SelectValidNdus(IEnumerable<CookieCandidate> cookies,
        DateTimeOffset now)
    {
        foreach (var cookie in cookies)
        {
            string domain = cookie.Domain.TrimStart('.');
            if (!cookie.Name.Equals("ndus", StringComparison.OrdinalIgnoreCase) ||
                !IsOfficialDomain(domain) ||
                string.IsNullOrWhiteSpace(cookie.Value) ||
                cookie.Value.Length > 4096 ||
                cookie.Value.Any(char.IsWhiteSpace) ||
                cookie.Value.Any(char.IsControl) ||
                cookie.Value.Contains(';') ||
                (!cookie.IsSession &&
                    cookie.Expires.ToUniversalTime() <= now.UtcDateTime))
                continue;
            if (TeraBoxBackend.HasCookie("ndus=" + cookie.Value))
                return cookie.Value;
        }
        return null;
    }

    internal static bool IsOfficialDomain(string host) =>
        host.Equals("terabox.com", StringComparison.OrdinalIgnoreCase) ||
        host.EndsWith(".terabox.com", StringComparison.OrdinalIgnoreCase) ||
        host.Equals("terabox.app", StringComparison.OrdinalIgnoreCase) ||
        host.EndsWith(".terabox.app", StringComparison.OrdinalIgnoreCase);

    /// <summary>
    /// Real WebView2 Runtime integration test with an isolated temporary
    /// browser profile and a FAKE token. No website login or personal cookies.
    /// </summary>
    internal static void RuntimeLab()
    {
        string temp = Path.Combine(Path.GetTempPath(),
            "NASMount_WebView2_QA_" + Guid.NewGuid().ToString("N"));
        Exception? failure = null;
        bool finished = false;
        using var window = new Form {
            ClientSize = new Size(670, 420), Text = "NASMount WebView2 offline QA",
            ShowInTaskbar = false, StartPosition = FormStartPosition.CenterScreen
        };
        using var testBrowser = new WebView2 { Dock = DockStyle.Fill };
        window.Controls.Add(testBrowser);
        using var watchdog = new System.Windows.Forms.Timer { Interval = 18000 };
        watchdog.Tick += (_, _) =>
        {
            watchdog.Stop();
            failure ??= new TimeoutException("WebView2 offline QA timed out");
            if (!window.IsDisposed) window.Close();
        };
        window.Shown += async (_, _) =>
        {
            try
            {
                var env = await CoreWebView2Environment.CreateAsync(
                    userDataFolder: temp);
                await testBrowser.EnsureCoreWebView2Async(env);
                testBrowser.CoreWebView2.NavigateToString(
                    "<html><body>FileHub AIO isolated TeraBox cookie QA</body></html>");
                var manager = testBrowser.CoreWebView2.CookieManager;
                var synthetic = manager.CreateCookie(
                    "ndus", "fake-offline-token-123456", ".terabox.com", "/");
                synthetic.Expires = DateTime.UtcNow.AddHours(1);
                manager.AddOrUpdateCookie(synthetic);
                var actual = await manager.GetCookiesAsync(
                    "https://www.terabox.com/");
                var found = SelectValidNdus(actual.Select(c => new CookieCandidate(
                    c.Name, c.Domain, c.Value, c.Expires, c.IsSession)),
                    DateTimeOffset.UtcNow);
                if (found != "fake-offline-token-123456")
                    throw new InvalidOperationException(
                        "WebView2 failed to return isolated official-domain cookie");
                finished = true;
            }
            catch (Exception ex) { failure = ex; }
            finally { if (!window.IsDisposed) window.Close(); }
        };
        watchdog.Start();
        Application.Run(window);
        if (!finished || failure is not null)
            throw failure ?? new InvalidOperationException(
                "WebView2 offline cookie QA did not complete");
        Console.WriteLine("TERABOX_WEBVIEW2_ISOLATED_RUNTIME_COOKIE_READ_PASS");
        try { Directory.Delete(temp, recursive: true); }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    internal static void PolicyTest()
    {
        DateTimeOffset now = DateTimeOffset.UtcNow;
        var legit = new CookieCandidate("ndus", ".terabox.com", "test-session-12345",
            now.UtcDateTime.AddHours(2), false);
        if (SelectValidNdus([legit], now) != legit.Value ||
            SelectValidNdus([legit with { Domain = "evil-terabox.com" }], now) is not null ||
            SelectValidNdus([legit with { Domain = "terabox.com.evil.example" }], now) is not null ||
            SelectValidNdus([legit with { Name = "password" }], now) is not null ||
            SelectValidNdus([legit with {
                Expires = now.UtcDateTime.AddSeconds(-1) }], now) is not null ||
            SelectValidNdus([legit with { Value = "has spaces" }], now) is not null ||
            !IsOfficialDomain("www.terabox.app") ||
            IsOfficialDomain("evil.example"))
            throw new InvalidOperationException("TeraBox ndus login cookie selection policy failed");
        Console.WriteLine("TERABOX_WEBVIEW2_NDUS_COOKIE_DOMAIN_EXPIRY_POLICY_PASS");
    }
}
