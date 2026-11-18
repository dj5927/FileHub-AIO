using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using System.Text;
using System.Windows.Forms;

namespace NASMount;

/// <summary>
/// Permanent component inventory embedded in NASMount.exe and also shipped as
/// a readable README. Live exe versions are queried from short-lived 'version'
/// processes only (never from live mounts, credentials or network accounts).
/// </summary>
internal static class ComponentInventory
{
    internal const string ReadmeName = "README_COMPONENTS_1378.md";
    internal const string StandardVersion = "v1.76.0";
    internal const string StandardSha256 =
        "ACD80683E23151E93BBE0D6FFA74D26E542EC510332F630BEECB9944F8D1A623";
    internal const string StandardReleaseZipSha256 =
        "8B4FD2737ADE4E70C057EF4A49DEB7CFFE94B0FB9D7587C189D149823F21A1DC";
    internal const string ExtraVersion = "v1.76.0-extra";
    internal const string ExtraSha256 =
        "A72B8D123517696C4CC996FD17BD7509DDD7850B5E13893EE970F8C8A2B8390C";
    internal const string OriginalReleaseZipSha256 =
        "A9BB4C6F30E7C6F73ABA05D9EA3355C0C540E7ADD2E10480A7C976516DCD2D37";

    internal static string ReadEmbeddedManifest()
    {
        using var source = Assembly.GetExecutingAssembly()
            .GetManifestResourceStream("NASMount.ComponentsManifest")
            ?? throw new FileNotFoundException(
                "FileHub AIO 내장 구성 요소 README를 찾을 수 없습니다.");
        using var reader = new StreamReader(source, Encoding.UTF8);
        return reader.ReadToEnd();
    }

    private static async Task<string> ExecutableVersionAsync(string file)
    {
        if (!File.Exists(file))
            return "실행 파일 없음";
        try
        {
            var start = new ProcessStartInfo(file)
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                StandardOutputEncoding = Encoding.UTF8,
                StandardErrorEncoding = Encoding.UTF8
            };
            start.ArgumentList.Add("version");
            using var process = new Process { StartInfo = start };
            if (!process.Start()) return "버전 실행 실패";
            Task<string> output = process.StandardOutput.ReadToEndAsync();
            Task<string> errors = process.StandardError.ReadToEndAsync();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(8));
            try { await process.WaitForExitAsync(timeout.Token); }
            catch (OperationCanceledException)
            {
                if (!process.HasExited) process.Kill(entireProcessTree: true);
                return "버전 조회 시간 초과";
            }
            string text = await output;
            _ = await errors;
            string line = text.Split(new[] { '\r', '\n' },
                StringSplitOptions.RemoveEmptyEntries).FirstOrDefault() ?? "";
            return process.ExitCode == 0 && line.StartsWith("rclone v",
                StringComparison.OrdinalIgnoreCase) ? line : "실행 버전 확인 실패";
        }
        catch (Exception ex)
        {
            return "버전 확인 실패: " + ex.GetType().Name;
        }
    }

    private static async Task<string> ReadFileDigestAsync(string file)
    {
        if (!File.Exists(file)) return "파일 없음";
        try
        {
            await using var stream = new FileStream(file, FileMode.Open,
                FileAccess.Read, FileShare.Read, 128 * 1024, useAsync: true);
            return Convert.ToHexString(await SHA256.HashDataAsync(stream));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
        {
            return "해시 확인 실패: " + ex.GetType().Name;
        }
    }

    internal static async Task<string> BuildLiveReportAsync()
    {
        string root = AppContext.BaseDirectory;
        string standardPath = Path.Combine(root, "tools", "rclone.exe");
        string extraPath = Path.Combine(root, "tools", "rclone-terabox.exe");
        Task<string> standard = ExecutableVersionAsync(standardPath);
        Task<string> extra = ExecutableVersionAsync(extraPath);
        Task<string> standardHashTask = ReadFileDigestAsync(standardPath);
        Task<string> extraHashTask = ReadFileDigestAsync(extraPath);
        string mainVersion = await standard;
        string extraVersion = await extra;
        string standardDigest = await standardHashTask;
        string extraDigest = await extraHashTask;
        string standardState = standardDigest == StandardSha256 ? "공식 검증본과 일치" :
            "주의: v1.3.78 배포 검증본과 다름 / 재검증 필요";
        string extraState = extraDigest == ExtraSha256 ? "공식 검증본과 일치" :
            "주의: v1.3.78 배포 검증본과 다름 / 재검증 필요";
        return "\r\n\r\n==================================================\r\n" +
            "[이 PC의 실행 파일 검사 — 계정/네트워크 접속 없음]\r\n" +
            "일반 rclone: " + mainVersion + "\r\n" +
            "일반 rclone SHA-256: " + standardDigest + "\r\n" +
            "일반 rclone 무결성: " + standardState + "\r\n" +
            "TeraBox 확장 rclone: " + extraVersion + "\r\n" +
            "TeraBox SHA-256: " + extraDigest + "\r\n" +
            "TeraBox 무결성: " + extraState + "\r\n" +
            "현재 .NET 런타임: " + Environment.Version + "\r\n" +
            "WinFsp 드라이버 상태: " +
            (RemoteMount.HasWinFsp ? "설치 감지" : "설치 미감지") + "\r\n" +
            "실제 설치된 WinFsp 파일 버전: " +
            (WinFspUpgradePolicy.InstalledVersion()?.ToString() ?? "확인 불가") + "\r\n" +
            "포함된 WinFsp 설치 파일 버전: " +
            WinFspUpgradePolicy.PackageVersion + "\r\n" +
            "WinFsp 교체 가능 여부: " +
            (WinFspUpgradePolicy.UpgradeAvailable(WinFspUpgradePolicy.InstalledVersion())
                ? "새 배포본 있음 (드라이브 해제 후 수동 업그레이드)"
                : "현재 포함 버전 이상 설치됨") + "\r\n";
    }

    internal static void ManifestSelfTest()
    {
        string readme = ReadEmbeddedManifest();
        foreach (var required in new[] {
            // Historical embedded upstream inventory retains its original
            // title for integrity checks; this is not the current UI brand.
            "NAS Mount v1.3.78", "rclone-extra", "v1.76.0-extra",
            StandardVersion, "WinFsp", "2.2.26286",
            "FluentFTP", "55.1.0", "SSH.NET", "2026.0.1",
            "WebView2", "1.0.4314.35", "MYBOX", "SMB",
            "https://github.com/gulp79/rclone-extra",
            "https://github.com/rclone/rclone/releases/tag/v1.76.0",
            StandardSha256, StandardReleaseZipSha256,
            ExtraSha256, OriginalReleaseZipSha256
        })
            if (!readme.Contains(required, StringComparison.Ordinal))
                throw new InvalidOperationException(
                    "COMPONENT_MANIFEST_REQUIRED_FIELD_MISSING " + required);
        Console.WriteLine("COMPONENT_INVENTORY_EMBEDDED_README_PASS");
    }

    internal static async Task BundleSelfTestAsync()
    {
        ManifestSelfTest();
        string onDisk = Path.Combine(AppContext.BaseDirectory, ReadmeName);
        if (!File.Exists(onDisk) ||
            File.ReadAllText(onDisk, Encoding.UTF8) != ReadEmbeddedManifest())
            throw new InvalidOperationException(
                "COMPONENT_README_MISSING_OR_OUT_OF_SYNC");
        string report = await BuildLiveReportAsync();
        if (!report.Contains("일반 rclone: rclone v1.76.0\r\n", StringComparison.Ordinal) ||
            !report.Contains("rclone v1.76.0-extra", StringComparison.Ordinal) ||
            !report.Contains("일반 rclone 무결성: 공식 검증본과 일치", StringComparison.Ordinal) ||
            !report.Contains("TeraBox 무결성: 공식 검증본과 일치", StringComparison.Ordinal))
            throw new InvalidOperationException(
                "COMPONENT_INSTALLED_EXECUTABLE_VERSION_OR_SHA_MISMATCH\n" +
                report);
        Console.WriteLine("COMPONENT_INVENTORY_DISK_README_EXE_VERSION_SHA_PASS");
    }
}

internal sealed class ComponentInventoryDialog : Form
{
    private readonly RichTextBox text = new();

    internal ComponentInventoryDialog()
    {
        Text = "FileHub AIO · 사용 엔진 / 구성 요소 / 버전";
        ClientSize = new Size(785, 620);
        MinimumSize = new Size(650, 480);
        StartPosition = FormStartPosition.CenterParent;
        ShowInTaskbar = false;
        AutoScaleMode = AutoScaleMode.Dpi;
        Font = new Font("Malgun Gothic", 9f);
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        ModernTheme.EnableDarkTitleBar(this);

        var heading = new Label {
            Dock = DockStyle.Top, Height = 57,
            Text = "사용 엔진 · 프로그램 · 확장 백엔드",
            Font = new Font("Malgun Gothic", 15, FontStyle.Bold),
            ForeColor = ModernTheme.Text,
            Padding = new Padding(18, 14, 0, 0)
        };
        var bottom = new Panel {
            Dock = DockStyle.Bottom, Height = 61,
            BackColor = ModernTheme.Background
        };
        text.Dock = DockStyle.Fill;
        text.ReadOnly = true;
        text.WordWrap = false;
        text.ScrollBars = RichTextBoxScrollBars.Both;
        text.BorderStyle = BorderStyle.None;
        text.BackColor = ModernTheme.Field;
        text.ForeColor = ModernTheme.Text;
        text.Font = new Font("Consolas", 9.0f);
        text.Text = ComponentInventory.ReadEmbeddedManifest() +
            "\r\n\r\n[현재 실행 파일 버전 확인 중...]";
        text.DetectUrls = true;
        text.LinkClicked += (_, e) =>
        {
            if (Uri.TryCreate(e.LinkText, UriKind.Absolute, out var link) &&
                link.Scheme == Uri.UriSchemeHttps &&
                link.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase))
                System.Diagnostics.Process.Start(new ProcessStartInfo {
                    FileName = link.AbsoluteUri, UseShellExecute = true
                });
        };
        Controls.Add(text);
        Controls.Add(bottom);
        Controls.Add(heading);

        var open = ModernTheme.Button("README 파일 열기", 145, () => {
            try {
                string path = Path.Combine(AppContext.BaseDirectory,
                    ComponentInventory.ReadmeName);
                if (!File.Exists(path))
                {
                    MessageBox.Show(this, "배포 폴더에 README가 없습니다.\n" +
                        "내장 문서는 위 창에서 계속 확인할 수 있습니다.");
                    return;
                }
                var info = new ProcessStartInfo("notepad.exe") {
                    UseShellExecute = true
                };
                info.ArgumentList.Add(path);
                Process.Start(info);
            }
            catch (Exception ex) {
                MessageBox.Show(this, "README 열기 실패: " + ex.Message);
            }
        }, height: 34);
        var copy = ModernTheme.Button("전체 복사", 98, () => {
            try { Clipboard.SetText(text.Text); }
            catch (Exception ex) { MessageBox.Show(this, ex.Message); }
        }, height: 34);
        var close = ModernTheme.Button("닫기", 95,
            () => Close(), accent: true, height: 34);
        bottom.Controls.Add(open);
        bottom.Controls.Add(copy);
        bottom.Controls.Add(close);
        void Position()
        {
            open.SetBounds(17, 14, 145, 34);
            copy.SetBounds(171, 14, 98, 34);
            close.SetBounds(bottom.ClientSize.Width - 112, 14, 95, 34);
        }
        bottom.Resize += (_, _) => Position();
        Position();
        Shown += async (_, _) =>
        {
            try
            {
                string report = await ComponentInventory.BuildLiveReportAsync();
                if (IsDisposed) return;
                text.Text = ComponentInventory.ReadEmbeddedManifest() + report;
                text.SelectionStart = 0;
                text.ScrollToCaret();
            }
            catch (Exception ex)
            {
                if (!IsDisposed) text.AppendText(
                    "\r\n실행 파일 버전 확인 실패: " + ex.GetType().Name);
            }
        };
    }
}
