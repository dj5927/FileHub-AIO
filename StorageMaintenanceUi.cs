using System.Diagnostics;
using System.Drawing;
using System.Windows.Forms;

namespace NASMount;

internal sealed partial class MainForm
{
    private async Task RunUpdateCleanupAsync()
    {
        if (updateCleanupBusy || updateCleanupComplete || shutdownRequested) return;
        updateCleanupBusy = true;
        try
        {
            // Normally the update helper exits before the new window appears.
            // Poll briefly when it is still finishing so cleanup is immediate
            // after a successful OTA, not deferred to the next launch.
            for (int i = 0; i < 5 && !shutdownRequested; i++)
            {
                if (await Task.Run(PcUpdateCleanup.RunAfterNormalStartup))
                {
                    updateCleanupComplete = true;
                    AppStore.Log("PC_UPDATE_OLD_PACKAGES_AND_BACKUPS_CLEANED");
                    break;
                }
                await Task.Delay(1000);
            }
        }
        catch (Exception ex)
        { AppStore.Log("PC_UPDATE_CLEANUP_RETRY " + ex.GetType().Name); }
        finally { updateCleanupBusy = false; }
    }

    private bool CacheOfflineReady() =>
        !shutdownRequested && !cacheMaintenanceBusy && !connectBatchRunning &&
        preparingConnections.Count == 0 &&
        !cfg.Profiles.Any(RemoteMount.HasManagedRecord) &&
        LocalCachePolicy.NoBackendProcesses() && !LocalCachePolicy.HasOtherGui();

    private async Task RunInitialCacheCleanupAsync()
    {
        if (DateTime.UtcNow < nextCacheSweepUtc || !CacheOfflineReady()) return;
        nextCacheSweepUtc = DateTime.UtcNow.AddMinutes(5);
        cacheMaintenanceBusy = true;
        try
        {
            await Task.Run(() => LocalCachePolicy.CleanOffline(cfg, false));
        }
        catch (Exception ex)
        { AppStore.Log("CACHE_STARTUP_CLEANUP_DEFERRED " + ex.GetType().Name); }
        finally { cacheMaintenanceBusy = false; }
    }

    internal Form CreateCacheSettingsForm()
    {
        var window = new Form
        {
            Text = "FileHub AIO · 캐시 관리",
            ClientSize = new Size(550, 302),
            StartPosition = FormStartPosition.CenterParent,
            FormBorderStyle = FormBorderStyle.FixedDialog,
            MaximizeBox = false,
            MinimizeBox = false,
            ShowInTaskbar = false,
            AutoScaleMode = AutoScaleMode.Dpi,
            BackColor = ModernTheme.Background,
            ForeColor = ModernTheme.Text,
            Font = new Font("Malgun Gothic", 9f)
        };
        ModernTheme.EnableDarkTitleBar(window);
        string parent = cfg.CacheParentDirectory;
        var title = ModernTheme.Label("캐시 관리", 16, true);
        title.SetBounds(20, 12, 510, 34);
        window.Controls.Add(title);
        var pathLabel = ModernTheme.Label("저장 경로", 9, true);
        pathLabel.SetBounds(20, 53, 100, 24);
        window.Controls.Add(pathLabel);
        var path = new TextBox { ReadOnly = true, BackColor = ModernTheme.Field,
            ForeColor = ModernTheme.Text, BorderStyle = BorderStyle.FixedSingle };
        path.SetBounds(20, 81, 342, 25);
        window.Controls.Add(path);
        void DisplayPath()
        {
            try { path.Text = LocalCachePolicy.Root(new AppConfig { CacheParentDirectory = parent }); }
            catch { path.Text = parent; }
        }
        DisplayPath();
        void Button(string text, int x, int y, int width, Action action, bool accent = false)
        {
            var button = ModernTheme.Button(text, width, action, accent: accent, height: 30);
            button.SetBounds(x, y, width, 30);
            window.Controls.Add(button);
        }
        Button("변경", 370, 78, 75, () =>
        {
            using var folder = new FolderBrowserDialog { Description = "캐시를 저장할 로컬 디스크의 폴더 선택" };
            if (folder.ShowDialog(window) != DialogResult.OK) return;
            try
            {
                _ = LocalCachePolicy.Root(new AppConfig { CacheParentDirectory = folder.SelectedPath });
                parent = folder.SelectedPath;
                DisplayPath();
            }
            catch (Exception ex) { MessageBox.Show(window, ex.Message, "캐시 경로"); }
        });
        Button("기본", 453, 78, 77, () => { parent = ""; DisplayPath(); });
        var ageLabel = ModernTheme.Label("보존 시간 (읽기 / 전송 완료)", 9, true);
        ageLabel.SetBounds(20, 123, 242, 24); window.Controls.Add(ageLabel);
        var age = new NumericUpDown { Minimum = 5, Maximum = 1440,
            Value = LocalCachePolicy.Minutes(cfg), BackColor = ModernTheme.Field,
            ForeColor = ModernTheme.Text };
        age.SetBounds(20, 151, 107, 25); window.Controls.Add(age);
        var minuteLabel = ModernTheme.Label("분", 9);
        minuteLabel.SetBounds(133, 148, 45, 28); window.Controls.Add(minuteLabel);
        var capLabel = ModernTheme.Label("연결별 최대 캐시 (권장 한도)", 9, true);
        capLabel.SetBounds(282, 123, 248, 24); window.Controls.Add(capLabel);
        var cap = new NumericUpDown { Minimum = 1, Maximum = 200,
            Value = LocalCachePolicy.GiB(cfg), BackColor = ModernTheme.Field,
            ForeColor = ModernTheme.Text };
        cap.SetBounds(282, 151, 107, 25); window.Controls.Add(cap);
        var gbLabel = ModernTheme.Label("GiB", 9);
        gbLabel.SetBounds(395, 148, 55, 28); window.Controls.Add(gbLabel);

        var usage = ModernTheme.Label("캐시 파일 크기: 확인 중", 9, true);
        usage.SetBounds(20, 187, 510, 26); window.Controls.Add(usage);
        async Task RefreshUsage()
        {
            try
            {
                string active = LocalCachePolicy.Root(cfg);
                long bytes = await Task.Run(() => LocalCachePolicy.Bytes(active));
                if (!window.IsDisposed) usage.Text = "캐시 파일 크기 합계: " +
                    (bytes / 1073741824.0).ToString("0.00") + " GiB";
            }
            catch { if (!window.IsDisposed) usage.Text = "캐시 경로를 확인할 수 없습니다."; }
        }
        window.Shown += async (_, _) => await RefreshUsage();
        Button("완료 캐시 삭제", 20, 219, 143, async () =>
        {
            if (!CacheOfflineReady())
            {
                MessageBox.Show(window, "모든 드라이브를 연결 해제한 후 실행하세요. 미전송 파일과 복구 기록은 삭제하지 않습니다.", "캐시 삭제");
                return;
            }
            if (MessageBox.Show(window, "다시 다운로드할 수 있는 완료 캐시만 삭제합니다.", "캐시 삭제",
                MessageBoxButtons.YesNo, MessageBoxIcon.Question) != DialogResult.Yes) return;
            cacheMaintenanceBusy = true;
            window.Enabled = false;
            try { await Task.Run(() => LocalCachePolicy.CleanOffline(cfg, true)); }
            catch (Exception ex) { MessageBox.Show(window, ex.Message, "캐시 삭제"); }
            finally { cacheMaintenanceBusy = false; if (!window.IsDisposed) window.Enabled = true; }
            await RefreshUsage();
        });
        Button("폴더 열기", 171, 219, 100, () =>
        {
            try
            {
                string root = LocalCachePolicy.Root(cfg);
                Directory.CreateDirectory(root);
                Process.Start(new ProcessStartInfo("explorer.exe", "\"" + root + "\"") { UseShellExecute = true });
            }
            catch (Exception ex) { MessageBox.Show(window, ex.Message, "캐시 폴더"); }
        });
        Button("저장", 420, 219, 110, async () =>
        {
            var next = new AppConfig { CacheParentDirectory = parent };
            string previousParent = cfg.CacheParentDirectory;
            int previousAge = cfg.CacheRetentionMinutes, previousCap = cfg.CacheMaxGiB;
            try
            {
                string oldRoot = LocalCachePolicy.Root(cfg), newRoot = LocalCachePolicy.Root(next);
                if (!oldRoot.Equals(newRoot, StringComparison.OrdinalIgnoreCase))
                {
                    if (!CacheOfflineReady())
                        throw new IOException("경로 변경 전에 모든 드라이브를 연결 해제하세요.");
                    cacheMaintenanceBusy = true;
                    window.Enabled = false;
                    bool empty = await Task.Run(() =>
                    {
                        LocalCachePolicy.CleanOffline(cfg, true);
                        return LocalCachePolicy.NoBackendProcesses() && !LocalCachePolicy.HasOtherGui() &&
                            LocalCachePolicy.PlainTree(oldRoot) && !LocalCachePolicy.Files(oldRoot).Any();
                    });
                    if (!empty)
                        throw new IOException("기존 캐시에 미전송 또는 확인되지 않은 파일이 남아 있습니다. 현재 경로를 유지합니다.");
                }
                cfg.CacheParentDirectory = parent;
                cfg.CacheRetentionMinutes = (int)age.Value;
                cfg.CacheMaxGiB = (int)cap.Value;
                AppStore.Save(cfg);
                window.DialogResult = DialogResult.OK;
                window.Close();
            }
            catch (Exception ex)
            {
                cfg.CacheParentDirectory = previousParent;
                cfg.CacheRetentionMinutes = previousAge;
                cfg.CacheMaxGiB = previousCap;
                MessageBox.Show(window, ex.Message, "캐시 설정");
            }
            finally { cacheMaintenanceBusy = false; if (!window.IsDisposed) window.Enabled = true; }
        }, true);
        var note = ModernTheme.Label("변경값은 다음 연결부터 적용 · 미전송 / 복구 파일은 항상 보호", 8.5f, color: ModernTheme.Muted);
        note.SetBounds(20, 263, 510, 26); window.Controls.Add(note);
        return window;
    }
}
