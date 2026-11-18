using System.Text;
using System.Threading;
using System.Windows.Forms;

namespace NASMount;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        // Private, isolated crash-recovery test child. This branch never
        // opens app settings, network sessions, WinFsp, or a window.
        if (args.Length == 1 &&
            args[0].Equals("--emergency-child-wait", StringComparison.Ordinal))
        {
            Thread.Sleep(TimeSpan.FromSeconds(25));
            return;
        }
        if (args.Contains("--mount-lifetime-policy-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try { MountProcessLifetime.PolicyTest(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("MOUNT_LIFETIME_POLICY_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--mount-lifetime-attach-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                MountProcessLifetime.AttachGuiBeforeMounts();
                Console.WriteLine("MOUNT_GUI_JOB_ATTACHED_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("MOUNT_GUI_JOB_ATTACH_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        Application.SetHighDpiMode(HighDpiMode.PerMonitorV2);
        Application.EnableVisualStyles();
        Application.SetCompatibleTextRenderingDefault(false);
        // Explorer may launch this dialog while the main app owns its mutex.
        if (args.Length == 2 &&
            args[0].Equals("--synology-share", StringComparison.OrdinalIgnoreCase))
        {
            if (!Path.IsPathFullyQualified(args[1]) ||
                args[1].Length < 3 || args[1][1] != ':')
            {
                MessageBox.Show("FileHub AIO 드라이브 파일을 선택하세요.",
                    "Synology 공유 링크", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            Application.Run(new SynologyQuickLinkForm(AppStore.Load(), args[1]));
            return;
        }
        if (args.Contains("--synology-share-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try { SynologySharing.PolicyTest(); }
            catch (Exception ex) {
                Console.Error.WriteLine("SYNOLOGY_SHARING_POLICY_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Length == 2 &&
            args[0].Equals("--apply-pc-update", StringComparison.OrdinalIgnoreCase))
        {
            Environment.ExitCode = PcUpdateApply.RunHelper(args[1]);
            return;
        }
        if (args.Contains("--pc-updater-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                WinFspUpgradePolicy.PolicyTest();
                PcUpdateService.FeedPolicyTest();
                PcUpdateApply.LocalPolicyTest();
                RemoteMount.EmergencyPolicyTestAsync().GetAwaiter().GetResult();
                MountPresentation.GoogleSessionPolicyTest();
                DriveLetterAllocator.GoogleLetterPolicyTest();
                RemoteMount.WebDavEndpointPolicyTest();
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("PC_UPDATE_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--native-webdav-lock-lab-test",
                StringComparer.OrdinalIgnoreCase))
        {
            // Deliberately disconnected from live connections and files.
            // No rclone mount, registry writes or user NAS calls.
            try { NativeWebDavLockLab.RunAsync().GetAwaiter().GetResult(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("NATIVE_WEBDAV_LOCK_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--native-webdav-gateway-lab-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try { NativeWebDavGatewayLab.RunAsync().GetAwaiter().GetResult(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("NATIVE_WEBDAV_GATEWAY_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--google-cold-start-policy-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                GoogleMountPacingPolicy.SelfTest();
                GoogleExplorerStabilityPolicy.SelfTest();
                GoogleDriveAuth.ValidationPolicyTest();
                RemoteMount.GoogleUiTransferPolicyTest();
                GoogleVfsReadPolicy.SelfTest();
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("GOOGLE_COLD_START_POLICY_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--shell-arrival-batch-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                ShellArrivalBatch.PolicyTest();
                GoogleStartupTrace.PolicyTest();
                int expected = ExplorerDriveIcon.VolumeUnitMask("R") |
                    ExplorerDriveIcon.VolumeUnitMask("S") |
                    ExplorerDriveIcon.VolumeUnitMask("Z");
                if (ExplorerDriveIcon.BatchVolumeUnitMask(
                        ["R", "s", "Z", "R"]) != expected)
                    throw new InvalidOperationException(
                        "Combined arrival mask lost or duplicated drive letters");
                Console.WriteLine("SHELL_BATCH_UNIT_MASK_AND_SANITIZED_GOOGLE_DIAG_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SHELL_BATCH_QA_FAILED " +
                    ex.GetType().Name + ": " + ex.Message);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--startup-readiness-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                StartupReadinessPolicy.SelfTest();
                var pending = LockStatusPresentation.CardStatus(new NasProfile {
                    Protocol = "GoogleDrive", AutoConnect = true }, false, true);
                if (pending.Contains("실패") || !pending.Contains("준비 중"))
                    throw new InvalidOperationException(
                        "Pending mount misrepresented as an access failure");
                var smbOnly = StartupReadinessPolicy.VerifyLocalAsync(
                    [new NasProfile { Protocol = "SMB", AutoConnect = true }],
                    false).GetAwaiter().GetResult();
                var missingDriver = StartupReadinessPolicy.VerifyLocalAsync(
                    [new NasProfile { Protocol = "GoogleDrive", AutoConnect = true }],
                    false).GetAwaiter().GetResult();
                if (!smbOnly.Success || missingDriver.Success)
                    throw new InvalidOperationException("Startup gate fail-close failed");
                var allEngines = StartupReadinessPolicy.VerifyLocalAsync(
                    [new NasProfile { Protocol = "GoogleDrive", AutoConnect = true },
                     new NasProfile { Protocol = "TeraBox", AutoConnect = true }],
                    true).GetAwaiter().GetResult();
                if (!allEngines.Success)
                    throw new InvalidOperationException(allEngines.Message);
                Console.WriteLine("STARTUP_READY_BEFORE_AUTOCONNECT_ENGINES_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("STARTUP_READINESS_QA_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--component-inventory-test", StringComparer.OrdinalIgnoreCase))
        {
            try { ComponentInventory.BundleSelfTestAsync().GetAwaiter().GetResult(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("COMPONENT_INVENTORY_TEST_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--profile-delete-policy-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try { ProfileDeletionPolicy.SelfTest(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("PROFILE_DELETE_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--terabox-webview2-offline-lab",
                StringComparer.OrdinalIgnoreCase))
        {
            try { TeraBoxWebLogin.RuntimeLab(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("TERABOX_WEBVIEW2_QA_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--explorer-label-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try { ExplorerDriveIcon.DisplayNamePolicyTest(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("EXPLORER_LABEL_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--charset-gateway-lab-test", StringComparer.OrdinalIgnoreCase))
        {
            try { LegacyCharsetLab.RunAsync(RemoteMount.RclonePath).GetAwaiter().GetResult(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("LEGACY_CHARSET_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--ghost-shell-probe-child",
                StringComparer.OrdinalIgnoreCase))
        {
            GhostSystemDiagnostics.PrintShellFolderProbe();
            return;
        }
        if (args.Contains("--ghost-report-build-child",
                StringComparer.OrdinalIgnoreCase))
        {
            Console.Write(GhostSystemDiagnostics.BuildReport());
            return;
        }

        if (args.Contains("--profile-backup-lab", StringComparer.OrdinalIgnoreCase))
        {
            try { ProfileBackupLab.Run(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FILEHUB_PROFILE_BACKUP_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
        {
            SelfTest.Run();
            return;
        }
        if (args.Contains("--fast-connect-speed-test",
                StringComparer.OrdinalIgnoreCase))
        {
            // No NAS, cloud, drive letter or file-cache access. Compare
            // first immutable binary verification with subsequent cached
            // verification within the SAME process.
            try
            {
                FastConnectPolicy.PolicyTest();
                string exe = RemoteMount.RclonePath ??
                    throw new FileNotFoundException("Bundled rclone not found");
                var firstTimer = System.Diagnostics.Stopwatch.StartNew();
                var firstResult = RcloneExecutableInspector.VerifyAsync(exe)
                    .GetAwaiter().GetResult();
                firstTimer.Stop();
                var cachedTimer = System.Diagnostics.Stopwatch.StartNew();
                var secondResult = RcloneExecutableInspector.VerifyAsync(exe)
                    .GetAwaiter().GetResult();
                cachedTimer.Stop();
                if (!firstResult.Success || !secondResult.Success)
                    throw new InvalidOperationException(
                        "Rclone binary integrity verification failed");
                Console.WriteLine("FAST_CONNECT_LOCAL_VERIFY_FIRST_MS=" +
                    firstTimer.ElapsedMilliseconds);
                Console.WriteLine("FAST_CONNECT_LOCAL_VERIFY_CACHED_MS=" +
                    cachedTimer.ElapsedMilliseconds);
                Console.WriteLine("FAST_CONNECT_BINARY_VERIFIED_ONCE_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FAST_CONNECT_SPEED_TEST_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--manual-reconnect-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                SessionReconnectPause.SelfTest();
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SESSION_MANUAL_RECONNECT_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--ftp-sftp-lock-nonexclusive-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try { FtpSftpLockLab.RunNonExclusiveServerRejection(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("NONEXCLUSIVE_LOCK_POLICY_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--ftp-sftp-lock-lab-test", StringComparer.OrdinalIgnoreCase))
        {
            try { FtpSftpLockLab.Run(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("FTP_SFTP_LOCK_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--sftp-knownhosts-live-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                if (Environment.GetEnvironmentVariable("NASMOUNT_SFTP_KNOWNHOSTS_LAB") != "1")
                    throw new InvalidOperationException("SFTP test server not opted in");
                var p = new NasProfile {
                    Protocol = "SFTP", Host = "127.0.0.1", Port = 21945,
                    Username = "naslab", Letter = "Q", Share = ""
                };
                var observed = SftpHostKeyTrust.ObserveAsync(p)
                    .GetAwaiter().GetResult();
                SftpHostKeyTrust.Approve(p, observed);
                string key = "RCLONE_SFTP_KNOWN_HOSTS_FILE";
                string? previous = Environment.GetEnvironmentVariable(key);
                try
                {
                    Environment.SetEnvironmentVariable(key,
                        @"C:\legacy-does-not-exist\known_hosts");
                    var result = RemoteMount.Diagnose(p, "naslab-pass")
                        .GetAwaiter().GetResult();
                    Console.WriteLine("SFTP_CONTAMINATED_ENV_LIVE_RESULT " +
                        result.Success + " " +
                        result.Message.Replace('\n', ' ').Replace('\r', ' '));
                    if (!result.Success)
                        throw new InvalidOperationException(
                            "SFTP real backend still used old global known_hosts");
                }
                finally { Environment.SetEnvironmentVariable(key, previous); }
                Console.WriteLine("SFTP_PINNED_BACKEND_OVER_OLD_ENV_LIVE_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SFTP_KNOWNHOSTS_LIVE_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--sftp-env-isolation-test", StringComparer.OrdinalIgnoreCase))
        {
            try { RemoteMount.SftpIsolationPolicySelfTest(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SFTP_ENV_ISOLATION_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--sftp-host-key-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try { SftpHostKeyTrust.SelfTest(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SFTP_HOST_KEY_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--sftp-local-lab-test", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                if (Environment.GetEnvironmentVariable("NASMOUNT_SFTP_LOCAL_LAB") != "1")
                    throw new InvalidOperationException("Local SFTP lab not explicitly enabled.");
                var p = new NasProfile {
                    Protocol = "SFTP", Host = "127.0.0.1", Port = 21743,
                    Username = "nas-lab", Letter = "Q", Share = "", AutoConnect = false
                };
                var observed = SftpHostKeyTrust.ObserveAsync(p)
                    .GetAwaiter().GetResult();
                SftpHostKeyTrust.Approve(p, observed);
                Console.WriteLine("SFTP_LAB_PIN_SHA256=" + observed.Sha256Fingerprint);
                var connected = RemoteMount.Diagnose(p, "nas-lab-pass")
                    .GetAwaiter().GetResult();
                Console.WriteLine("SFTP_LAB_DIAGNOSE=" + connected.Success +
                    " " + connected.Message.Replace('\r', ' ').Replace('\n', ' '));
                if (!connected.Success)
                    throw new InvalidOperationException(
                        "Real local rclone SFTP preflight failed.");
                Console.WriteLine("SFTP_LAB_REAL_CONNECT_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SFTP_LAB_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Length == 2 &&
            args[0].Equals("--shell-notification-q-lab",
                StringComparison.OrdinalIgnoreCase))
        {
            try { ExplorerDriveIcon.TestOnlyNotifyQ(
                args[1].Equals("arrive", StringComparison.OrdinalIgnoreCase)); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("Q_SHELL_NOTIFY_LAB_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--explorer-thispc-refresh-lab-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                if (Environment.GetEnvironmentVariable("NASMOUNT_EXPLORER_F5_LAB") != "1")
                    throw new InvalidOperationException("Explorer refresh lab not enabled.");
                ExplorerLiveViewRefresh.PolicyTest();
                var result = ExplorerLiveViewRefresh.RefreshThisPc("ISOLATED_TEST");
                Console.WriteLine("EXPLORER_LIVE_F5_LAB matched=" + result.Matched +
                    " refreshed=" + result.Refreshed + " failed=" + result.Failed);
                if (!result.AnySucceeded)
                    throw new InvalidOperationException(
                        "No existing This PC view responded to direct Refresh()");
                Console.WriteLine("EXPLORER_THIS_PC_WINDOW_REFRESH_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("EXPLORER_THIS_PC_WINDOW_REFRESH_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--windows-provider-policy-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                WindowsNetworkResources.PolicyTest();
                ExplorerShellRestart.ReadOnlyPolicyTest();
                RetiredMountHistory.PolicyTest();
                ExplorerLiveViewRefresh.PolicyTest();
                DriveIconLease.PolicyTest();
                MountResponsivenessPolicy.PolicyTest();
                FastConnectPolicy.PolicyTest();
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("WINDOWS_PROVIDER_POLICY_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--mybox-write-lab-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                string backend = RemoteMount.RclonePath ??
                    throw new FileNotFoundException("Bundled rclone missing");
                MyBoxWriteLab.RunAsync(backend).GetAwaiter().GetResult();
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("MYBOX_WRITE_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--mybox-beta-dav-lab-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                string backend = RemoteMount.RclonePath ??
                    throw new FileNotFoundException("Bundled rclone missing");
                MyBoxBetaGateway.LocalDavLabAsync(backend)
                    .GetAwaiter().GetResult();
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("MYBOX_BETA_DAV_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--mybox-beta-offline-test",
                StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                MyBoxBetaGateway.PolicyTest();
                var profile = new NasProfile {
                    Protocol = "MyBox", DisplayName = "네이버 MYBOX",
                    Letter = "Q", Host = "", Share = "",
                    UseLockBridge = false, AllowUnprotectedWrites = false
                };
                if (!RemoteMount.TryValidate(profile, out var reason))
                    throw new InvalidOperationException("MYBOX profile invalid: " + reason);
                profile.AllowUnprotectedWrites = true;
                if (!RemoteMount.TryValidate(profile, out _))
                    throw new InvalidOperationException("MYBOX explicitly enabled beta write rejected");
                profile.AllowUnprotectedWrites = false;
                ModernProfileDialog.ApplyPerformanceMode(profile, 0);
                if (!profile.AllowUnprotectedWrites || profile.UseLockBridge)
                    throw new InvalidOperationException("MYBOX beta writable option unavailable");
                ModernProfileDialog.ApplyPerformanceMode(profile, 1);
                if (profile.AllowUnprotectedWrites || profile.UseLockBridge)
                    throw new InvalidOperationException("MYBOX read-only option not preserved");
                using var icon = ProtocolIconFactory.Create("MyBox");
                if (icon is not Bitmap bitmap ||
                    bitmap.GetPixel(0, 0).A != 0 ||
                    bitmap.GetPixel(28, 28).A == 0)
                    throw new InvalidOperationException(
                        "MYBOX green N icon must have transparent outer background");
                Console.WriteLine("MYBOX_EXPLICIT_RW_AND_READONLY_TRANSPARENT_ICON_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("MYBOX_BETA_OFFLINE_POLICY_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--ghost-diagnose", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                string path = GhostSystemDiagnostics.SaveReport();
                Console.WriteLine("GHOST_DIAG_READONLY_REPORT=" + path);
                Console.WriteLine("GHOST_DIAG_READONLY_REPORT_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("GHOST_DIAG_READONLY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--legacy-ghost-lab-test", StringComparer.OrdinalIgnoreCase))
        {
            // Isolated WinFsp/registry QA for an OLD profile GUID no longer
            // present in any user config. The harness owns its Q: lab mount
            // and exact 13451345 test GUID; never run against a user drive.
            try
            {
                if (Environment.GetEnvironmentVariable("NASMOUNT_LEGACY_GHOST_LAB") != "1")
                    throw new InvalidOperationException("Legacy lab not opted in");
                bool expectActive = Environment.GetEnvironmentVariable(
                    "NASMOUNT_LEGACY_GHOST_EXPECT_ACTIVE") == "1";
                var result = LegacyNetworkGhostCleaner.Cleanup(
                    Array.Empty<NasProfile>(), "13451345");
                Console.WriteLine("LEGACY_GHOST_LAB " +
                    result.Korean.Replace('\n', ' ').Replace('\r', ' '));
                using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                    @"Software\Microsoft\Windows\CurrentVersion\Explorer\MountPoints2\" +
                    "##NASMount-13451345#OldGone");
                if (expectActive &&
                    (result.Preserved < 1 || key is null))
                    throw new InvalidOperationException(
                        "Live legacy Q: mapping not preserved");
                if (!expectActive &&
                    (result.RemovedRecords < 1 || key is not null ||
                    Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                        @"Network\Q") is not null))
                    throw new InvalidOperationException(
                        "Old disconnected GUID still present");
                Console.WriteLine(expectActive ?
                    "ACTIVE_LEGACY_WIN_FSP_MAPPING_PRESERVED_PASS" :
                    "ORPHAN_LEGACY_MAPPING_SHELL_HISTORY_REMOVED_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("LEGACY_GHOST_LAB_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--network-ghost-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                LegacyNetworkGhostCleaner.SessionNamePolicyTest();
                OrphanRcloneRecovery.OwnershipPolicyTest();
                NetworkDriveResidueCleaner.PolicySelfTest();
                NetworkDriveResidueCleaner.RegistryIsolationSelfTest();
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("NETWORK_GHOST_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--network-ghost-lab-cleanup", StringComparer.OrdinalIgnoreCase))
        {
            // Only an explicitly opted-in local lab, using the fixed GUID
            // and Q: drive that we created for this test. Never invoked by
            // the production UI or a normal --self-test.
            if (Environment.GetEnvironmentVariable("NASMOUNT_Q_GHOST_LAB") != "1")
            {
                Environment.ExitCode = 2;
                return;
            }
            var profile = new NasProfile {
                Id = Guid.Parse("13421342-0000-0000-0000-000000000001"),
                Protocol = "TeraBox", Letter = "Q", DisplayName = "Lab"
            };
            if (RemoteMount.DriveLetterExists("Q"))
            {
                Console.Error.WriteLine("LAB_Q_DRIVE_OCCUPIED");
                Environment.ExitCode = 3;
                return;
            }
            var result = NetworkDriveResidueCleaner.AfterSafeStop(profile);
            Console.WriteLine("GHOST_LAB_CLEANUP " + result.Success + " " + result.Message);
            if (!result.Success) Environment.ExitCode = 1;
            return;
        }
        if (args.Contains("--safe-shutdown-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try { RemoteMount.ShutdownOwnershipSelfTest(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("SAFE_SHUTDOWN_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--google-auth-test", StringComparer.OrdinalIgnoreCase))
        {
            // Fully offline: fake token only, no Google requests, no user auth files.
            string result = Path.Combine(AppContext.BaseDirectory, "google-auth-qa.txt");
            try
            {
                var profile = new NasProfile { Protocol = "GoogleDrive", DisplayName = "My Google Drive",
                    Letter = "W", Host = "", Share = "", UseLockBridge = false, IsAnonymous = true };
                if (!RemoteMount.TryValidate(profile, out _))
                    throw new Exception("GoogleDrive profile not validated");
                var token = "{\"access_token\":\"fake-test-only\",\"refresh_token\":\"fake-refresh\",\"token_type\":\"Bearer\"}";
                string formatted = GoogleDriveAuth.BuildConfig(profile, token, "fake-client-id", "fake-client-secret");
                if (!formatted.Contains("type = drive") ||
                    !formatted.Contains("client_id = fake-client-id") ||
                    !formatted.Contains("refresh_token") ||
                    !formatted.Contains("[nm_" + profile.Id.ToString("N") + "]"))
                    throw new Exception("Google Drive rclone config invalid");
                string parsed = GoogleDriveAuth.ParseAuthorizedToken("header\nPaste -->\n" + token + "\n<-- END");
                if (parsed != token) throw new Exception("Google OAuth token parser failed");
                if (!GoogleDriveAuth.TryParseAuthUrl(
                    "NOTICE: Please go to http://127.0.0.1:53682/auth?state=abc123", out _))
                    throw new Exception("Google OAuth callback parser rejected localhost");
                if (GoogleDriveAuth.TryParseAuthUrl(
                    "NOTICE: Please go to http://malicious.example/auth?state=abc123", out _))
                    throw new Exception("OAuth browser URL security check failed");
                ModernProfileDialog.ApplyPerformanceMode(profile, 0);
                if (!profile.AllowUnprotectedWrites || profile.UseLockBridge)
                    throw new Exception("Google Drive must never enter NAS lock bridge");
                ModernProfileDialog.ApplyPerformanceMode(profile, 1);
                if (profile.AllowUnprotectedWrites || profile.UseLockBridge)
                    throw new Exception("Google Drive readonly mode not honored");
                File.WriteAllText(result, "GOOGLE_AUTH_OFFLINE_TEST_PASS; no user token written");
            }
            catch (Exception ex)
            {
                File.WriteAllText(result, "GOOGLE_AUTH_OFFLINE_TEST_FAIL: " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--onedrive-offline-test",
                StringComparer.OrdinalIgnoreCase))
        {
            // Offline only: fake OAuth token, no browser or Microsoft calls.
            try { OneDriveAuth.OfflinePolicyTest(); }
            catch (Exception ex)
            {
                Console.Error.WriteLine("ONEDRIVE_OFFLINE_POLICY_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--terabox-offline-test", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                TeraBoxWebLogin.PolicyTest();
                var p = new NasProfile { Protocol = "TeraBox", DisplayName = "TeraBox",
                    Host = "", Share = "", Port = 0, Letter = "W", IsAnonymous = false };
                ModernProfileDialog.ApplyPerformanceMode(p, 1);
                if (!RemoteMount.TryValidate(p, out _) || p.UseLockBridge ||
                    p.AllowUnprotectedWrites || !TeraBoxBackend.HasCookie("ndus=dummy-test"))
                    throw new Exception("TeraBox profile safety check failed");
                if (TeraBoxBackend.NormalizeCookie(" Cookie: ndus=dummy-test; foo=bar ") !=
                    "ndus=dummy-test; foo=bar")
                    throw new Exception("Cookie normalization failed");
                if (TeraBoxBackend.HasCookie("unrelated=value") ||
                    TeraBoxBackend.HasCookie("Cookie: missing=1"))
                    throw new Exception("Invalid cookie accepted");
                if (RemoteMount.BackendExecutable(p)?.EndsWith("rclone-terabox.exe") != true)
                    throw new Exception("Dedicated backend missing");
                using var profile = new ModernProfileDialog(p, false);
                profile.Show();
                Application.DoEvents();
                profile.SelectProtocol("MyBox");
                Application.DoEvents();
                profile.VerifyFieldLayout();
                if (profile.SelectedProtocol != "MyBox" ||
                    !profile.ProtocolRowAligned || profile.FtpSettingsVisible ||
                    profile.SftpSettingsVisible || profile.WebDavSettingsVisible ||
                    profile.SelectedMode != 0 ||
                    !profile.MyBoxPatHelpVisible ||
                    !profile.MyBoxPatHelpAligned ||
                    !ModernProfileDialog.MyBoxPatHelpText.Contains("계정 및 개인 액세스 토큰 관리") ||
                    !ModernProfileDialog.MyBoxPatHelpText.Contains("토큰 생성") ||
                    !profile.CloudNetworkOptionVisible ||
                    !profile.CloudNetworkSelected)
                    throw new InvalidOperationException(
                        "MYBOX PAT/writable default/help settings are invalid");
                using (var myboxShot = new Bitmap(profile.Width, profile.Height))
                    profile.DrawToBitmap(myboxShot,
                        new Rectangle(Point.Empty, myboxShot.Size));
                profile.SelectProtocol("TeraBox");
                Application.DoEvents();
                profile.VerifyFieldLayout();
                if (!profile.ProtocolRowAligned || profile.SelectedProtocol != "TeraBox" ||
                    profile.CheckedModeCount != 1 || profile.SelectedMode != 0 ||
                    !profile.TeraCookieHelpVisible || !profile.TeraCookieHelpAligned ||
                    !profile.TeraAutoImportVisible || !profile.TeraAutoImportAligned ||
                    ModernProfileDialog.TeraAutoImportLabel != "자동 불러오기" ||
                    !ModernProfileDialog.TeraCookieHelpText.Contains("Application") ||
                    !ModernProfileDialog.TeraCookieHelpText.Contains("ndus"))
                    throw new Exception("TeraBox UI settings invalid");
                using var priorReadonly = new ModernProfileDialog(p, true);
                priorReadonly.Show();
                Application.DoEvents();
                if (priorReadonly.SelectedMode != 1 || !priorReadonly.TeraCookieHelpVisible)
                    throw new Exception("Existing read-only mode must stay unchanged");
                priorReadonly.Hide();
                profile.Hide();
                var pWritable = p.Clone();
                ModernProfileDialog.ApplyPerformanceMode(pWritable, 0);
                if (!pWritable.AllowUnprotectedWrites || pWritable.UseLockBridge)
                    throw new Exception("TeraBox read/write mode not correctly configured");
                Console.WriteLine("TERABOX_OFFLINE_TEST_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("TERABOX_OFFLINE_TEST_FAIL: " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Length == 5 && args[0] == "--explorer-brand-lab" &&
            Environment.GetEnvironmentVariable("NASMOUNT_BRANDING_LAB") == "1")
        {
            // Only run against a disposable local WinFsp test mount.
            var profile = new NasProfile {
                Id = Guid.Parse(args[2]), Protocol = "WebDAV", Host = "127.0.0.1",
                DisplayName = args[4], Letter = args[3]
            };
            if (args[1] == "restore") ExplorerDriveIcon.Restore(profile);
            else if (args[1] != "apply" || !ExplorerDriveIcon.Apply(profile))
                Environment.ExitCode = 1;
            return;
        }
        if (args.Contains("--bridge-preflight-test", StringComparer.OrdinalIgnoreCase))
        {
            string qaFile = Path.Combine(AppContext.BaseDirectory, "pipe-qa-result.txt");
            try
            {
                BridgePreflightLab.RunAsync().GetAwaiter().GetResult();
                File.WriteAllText(qaFile, "BRIDGE_PREFLIGHT_LAB_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("BRIDGE_PREFLIGHT_LAB_FAIL: " + ex);
                File.WriteAllText(qaFile, "BRIDGE_PREFLIGHT_LAB_FAIL: " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--ftp-protocol-test", StringComparer.OrdinalIgnoreCase))
        {
            string file = Path.Combine(AppContext.BaseDirectory, "ftp-protocol-qa.txt");
            try
            {
                FtpServiceDetectorLab.RunAsync().GetAwaiter().GetResult();
                File.WriteAllText(file, "FTP_PROTOCOL_TEST_PASS ftp/webdav-http/http-other");
            }
            catch (Exception ex)
            {
                File.WriteAllText(file, "FTP_PROTOCOL_TEST_FAIL: " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--rclone-health-test", StringComparer.OrdinalIgnoreCase))
        {
            string resultPath = Path.Combine(AppContext.BaseDirectory, "rclone-health-qa.txt");
            try
            {
                string executable = RemoteMount.RclonePath ??
                    throw new FileNotFoundException("Bundled rclone missing");
                var verified = RcloneExecutableInspector.VerifyAsync(executable)
                    .GetAwaiter().GetResult();
                if (!verified.Success)
                    throw new InvalidOperationException(verified.Message);
                string corrupt = Path.Combine(Path.GetTempPath(),
                    "NASMount_corrupt_rclone_" + Guid.NewGuid().ToString("N") + ".exe");
                try
                {
                    File.WriteAllBytes(corrupt, [0x4D, 0x5A, 0x00, 0x00]);
                    var rejected = RcloneExecutableInspector.VerifyAsync(corrupt)
                        .GetAwaiter().GetResult();
                    if (rejected.Success || !rejected.Message.Contains("크기"))
                        throw new InvalidOperationException("Corrupted executable was not rejected");
                }
                finally { if (File.Exists(corrupt)) File.Delete(corrupt); }
                File.WriteAllText(resultPath, "RCLONE_HEALTH_TEST_PASS " + verified.Message);
            }
            catch (Exception ex)
            {
                File.WriteAllText(resultPath, "RCLONE_HEALTH_TEST_FAIL: " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--local-webdav-http-test", StringComparer.OrdinalIgnoreCase) ||
            args.Contains("--local-webdav-http-anon-test", StringComparer.OrdinalIgnoreCase))
        {
            bool anonymous = args.Contains("--local-webdav-http-anon-test", StringComparer.OrdinalIgnoreCase);
            string file = Path.Combine(AppContext.BaseDirectory,
                anonymous ? "webdav-http-anon-qa.txt" : "webdav-http-qa.txt");
            try
            {
                var local = new NasProfile
                {
                    DisplayName = "Loopback WebDAV HTTP fixture", Protocol = "WebDAV",
                    Host = "127.0.0.1", Port = int.Parse(
                        Environment.GetEnvironmentVariable("NASMOUNT_WEBDAV_HTTP_TEST_PORT") ?? "21443"),
                    Username = anonymous ? "" : "testuser", WebDavUseHttp = true,
                    IsAnonymous = anonymous, Letter = "Y"
                };
                var result = RemoteMount.Diagnose(local, anonymous ? null : "testpass_local_only")
                    .GetAwaiter().GetResult();
                File.WriteAllText(file, (result.Success ? "PASS " : "FAIL ") + result.Message);
                if (!result.Success) Environment.ExitCode = 1;
            }
            catch (Exception ex)
            {
                File.WriteAllText(file, "FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--google-preflight-policy-test", StringComparer.OrdinalIgnoreCase))
        {
            try
            {
                if (GoogleDrivePreflightPolicy.AttemptCount("GoogleDrive") != 3 ||
                    GoogleDrivePreflightPolicy.AttemptCount("TeraBox") != 3 ||
                    GoogleDrivePreflightPolicy.AttemptCount("WebDAV") != 1 ||
                    GoogleDrivePreflightPolicy.Timeout("GoogleDrive", 1).TotalSeconds <= 14 ||
                    GoogleDrivePreflightPolicy.Timeout("GoogleDrive", 3).TotalSeconds < 50 ||
                    GoogleDrivePreflightPolicy.Timeout("TeraBox", 1).TotalSeconds < 20 ||
                    GoogleDrivePreflightPolicy.Timeout("TeraBox", 3).TotalSeconds < 35 ||
                    GoogleDrivePreflightPolicy.Timeout("WebDAV", 1).TotalSeconds != 14 ||
                    GoogleDrivePreflightPolicy.ExplorerBrowseTimeout("GoogleDrive").TotalSeconds != 45 ||
                    GoogleDrivePreflightPolicy.ExplorerBrowseTimeout("TeraBox").TotalSeconds != 35 ||
                    GoogleDrivePreflightPolicy.ExplorerBrowseTimeout("WebDAV").TotalSeconds != 12 ||
                    !GoogleDrivePreflightPolicy.IsTransient("GoogleDrive", true, "") ||
                    !GoogleDrivePreflightPolicy.IsTransient("GoogleDrive", false, "HTTP 503") ||
                    !GoogleDrivePreflightPolicy.IsTransient("GoogleDrive", false, "429 too many requests") ||
                    GoogleDrivePreflightPolicy.IsTransient("GoogleDrive", false, "401 Unauthorized") ||
                    GoogleDrivePreflightPolicy.IsTransient("GoogleDrive", false, "invalid_grant") ||
                    !GoogleDrivePreflightPolicy.IsTransient("TeraBox", true, "") ||
                    !GoogleDrivePreflightPolicy.IsTransient("TeraBox", false, "504 gateway timeout") ||
                    GoogleDrivePreflightPolicy.IsTransient("TeraBox", false, "403 forbidden") ||
                    GoogleDrivePreflightPolicy.RetryDelay("GoogleDrive", 2).TotalSeconds <= 0 ||
                    GoogleDrivePreflightPolicy.IsTransient("WebDAV", true, ""))
                    throw new Exception("Cloud readiness timeout/retry policy invalid");
                Console.WriteLine("CLOUD_PREFLIGHT_POLICY_TEST_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("GOOGLE_PREFLIGHT_POLICY_TEST_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--ui-smoke-test", StringComparer.OrdinalIgnoreCase))
        {
            ProtocolIconFactory.VerifyProtocolDistinctnessForUiTest();
            // No NAS is contacted. The form receives a synthetic configuration
            // and the auto-connect Shown handler is explicitly disabled.
            Environment.SetEnvironmentVariable("NASMOUNT_UI_SMOKE", "1");
            string directory = Path.Combine(AppContext.BaseDirectory, "ui-qa");
            Directory.CreateDirectory(directory);
            try
            {
                var sample = new AppConfig
                {
                    Profiles =
                    [
                        new NasProfile { DisplayName = "원격 NAS", Protocol = "WebDAV", Host = "nas.example.invalid", Port = 5006, Letter = "Z", UseLockBridge = true },
                        new NasProfile { DisplayName = "FTP 테스트", Protocol = "FTP", Host = "ftp.example.invalid", Port = 21, Letter = "Y", UseLockBridge = true },
                        new NasProfile { DisplayName = "백업 저장소", Protocol = "SFTP", Host = "backup.example.invalid", Port = 22, Letter = "X", UseLockBridge = true },
                        new NasProfile { DisplayName = "사내 파일공유", Protocol = "SMB", Host = "office.example.invalid", Share = "team", Letter = "W" },
                        new NasProfile { DisplayName = "Google Drive", Protocol = "GoogleDrive", Letter = "V" },
                        new NasProfile { DisplayName = "OneDrive", Protocol = "OneDrive", Letter = "T" },
                        new NasProfile { DisplayName = "TeraBox", Protocol = "TeraBox", Letter = "U" },
                        new NasProfile { DisplayName = "네이버 MYBOX", Protocol = "MyBox", Letter = "S" }
                    ]
                };
                using var dashboard = new MainForm(false, sample);
                dashboard.Show();
                Application.DoEvents();
                dashboard.VerifyCardMenuLifetimeForUiTest();
                dashboard.VerifyProtocolIconsForUiTest();
                dashboard.VerifyNoFlickerCardRefreshForUiTest();
                dashboard.VerifyManualReconnectPauseForUiTest();
                dashboard.VerifyParallelGatesForUiTest();
                dashboard.VerifyShutdownGuardForUiTest();
                dashboard.VerifySafeExitNoKeepAliveForUiTest();
                dashboard.VerifyCloseToTrayPolicyForUiTest();
                dashboard.VerifyTransferFooterForUiTest();
                if (dashboard.ClientSize.Width < 755 || dashboard.ClientSize.Width > 785 ||
                    dashboard.ClientSize.Height < 550 || dashboard.ClientSize.Height > 582)
                    throw new InvalidOperationException("Compact window dimensions invalid");
                Application.DoEvents();
                using (var branding = new Bitmap(dashboard.Width, dashboard.Height))
                {
                    dashboard.DrawToBitmap(branding, new Rectangle(Point.Empty, branding.Size));
                    branding.Save(Path.Combine(directory, "dashboard-all-protocol-icons.png"));
                }
                Application.DoEvents();
                void AssertModernButtons(Control root)
                {
                    foreach (Control control in root.Controls)
                    {
                        if (control is ModernButton modern && modern.Region is not null)
                            throw new InvalidOperationException("Clipped modern button Region: " + control.Text);
                        AssertModernButtons(control);
                    }
                }
                AssertModernButtons(dashboard);
                var tree = new StringBuilder();
                void Describe(Control c, int depth)
                {
                    tree.Append(' ', depth * 2).Append(c.GetType().Name)
                        .Append(" visible=").Append(c.Visible)
                        .Append(" bounds=").Append(c.Bounds)
                        .Append(" text=").Append(c.Text).AppendLine();
                    if (c is ScrollableControl sc)
                        tree.Append(' ', depth * 2).Append("scrollH=")
                            .Append(sc.HorizontalScroll.Visible).Append(" scrollV=")
                            .Append(sc.VerticalScroll.Visible).AppendLine();
                    if (depth >= 7) return;
                    foreach (Control child in c.Controls) Describe(child, depth + 1);
                }
                Describe(dashboard, 0);
                File.WriteAllText(Path.Combine(directory, "dashboard-tree.txt"), tree.ToString());
                using (var bitmap = new Bitmap(dashboard.Width, dashboard.Height))
                {
                    dashboard.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "dashboard.png"));
                }
                dashboard.Size = dashboard.Size;
                Application.DoEvents();
                tree.Clear();
                Describe(dashboard, 0);
                File.WriteAllText(Path.Combine(directory, "dashboard-compact-tree.txt"), tree.ToString());
                using (var bitmap = new Bitmap(dashboard.Width, dashboard.Height))
                {
                    dashboard.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "dashboard-compact.png"));
                }
                dashboard.OpenSettingsSlideForTest();
                Application.DoEvents();
                if (!dashboard.SlidingPanelOpenForTest || !dashboard.SlidingPanelHidesStatusForTest)
                    throw new InvalidOperationException("Settings did not open as a right-side full-height panel");
                dashboard.VerifySlideChromeForTest(profile: false);
                dashboard.CaptureSlidePanelForTest(Path.Combine(directory, "settings-panel-content.png"));
                using (var slideShot = new Bitmap(dashboard.Width, dashboard.Height))
                {
                    dashboard.DrawToBitmap(slideShot, new Rectangle(Point.Empty, slideShot.Size));
                    slideShot.Save(Path.Combine(directory, "dashboard-settings-slide.png"));
                }
                dashboard.CloseSlideForTest();
                Application.DoEvents();
                if (dashboard.SlidingPanelOpenForTest)
                    throw new InvalidOperationException("Settings slide did not close");
                dashboard.PressFirstCardGearForTest();
                Application.DoEvents();
                if (!dashboard.SlidingPanelOpenForTest || !dashboard.SlidingPanelHidesStatusForTest)
                    throw new InvalidOperationException("Gear did not open in-window profile editor");
                dashboard.VerifySlideChromeForTest(profile: true);
                dashboard.CaptureSlidePanelForTest(Path.Combine(directory, "profile-panel-content.png"));
                using (var profileShot = new Bitmap(dashboard.Width, dashboard.Height))
                {
                    dashboard.DrawToBitmap(profileShot, new Rectangle(Point.Empty, profileShot.Size));
                    profileShot.Save(Path.Combine(directory, "dashboard-profile-slide.png"));
                }
                dashboard.CloseSlideForTest();
                Application.DoEvents();
                if (dashboard.SlidingPanelOpenForTest)
                    throw new InvalidOperationException("Profile slide did not close");
                dashboard.VerifySlideAnimationForTest();
                using var profile = new ModernProfileDialog(sample.Profiles[0].Clone(), true);
                profile.Show(dashboard);
                Application.DoEvents();
                AssertModernButtons(profile);
                if (!profile.OptionsScrollDisabled)
                    throw new InvalidOperationException("Profile settings unexpectedly scroll");
                profile.VerifyFieldLayout();
                for (int mode = 0; mode < 3; mode++)
                {
                    profile.SelectMode(mode);
                    if (profile.SelectedMode != mode || profile.CheckedModeCount != 1)
                        throw new InvalidOperationException("Exclusive checkbox failure: " + mode);
                }
                profile.SelectMode(0);
                profile.VerifyFieldLayout();
                if (profile.PortHasSpinner)
                    throw new InvalidOperationException("Port field unexpectedly has a spinner");
                // A protocol TILE switches only the active draft. It must
                // never replicate WebDAV host, user or name into other types.
                profile.SelectProtocol("WebDAV");
                profile.SetHostTextForTest("webdav-draft-only.example.invalid");
                profile.SetNameTextForTest("윈텍");
                profile.SelectProtocol("SFTP");
                if (!string.IsNullOrEmpty(profile.HostText) || profile.NameText == "윈텍")
                    throw new InvalidOperationException("Protocol switching copied WebDAV fields to SFTP");
                profile.SetHostTextForTest("sftp-draft-only.example.invalid");
                profile.SelectProtocol("WebDAV");
                if (profile.HostText != "webdav-draft-only.example.invalid" ||
                    profile.NameText != "윈텍")
                    throw new InvalidOperationException("WebDAV draft did not restore");
                profile.SelectProtocol("SFTP");
                if (profile.HostText != "sftp-draft-only.example.invalid")
                    throw new InvalidOperationException("SFTP draft did not restore");
                profile.SelectProtocol("WebDAV");
                if (!profile.ProtocolRowAligned || profile.CharsetOptionCount < 30)
                    throw new InvalidOperationException("Protocol row alignment or charset catalogue failed");
                // Exercise the actual protocol-selection UI and verify that
                // All five protocol tiles can switch in one fixed-size dialog.
                foreach (string protocol in new[] { "WebDAV", "SFTP", "FTP", "SMB" })
                {
                    profile.SelectProtocol(protocol);
                    Application.DoEvents();
                    profile.VerifyFieldLayout();
                    if (profile.SelectedProtocol != protocol)
                        throw new InvalidOperationException("Protocol tile switch failed: " + protocol);
                    if (profile.CharsetControlVisible != (protocol is "FTP" or "SFTP"))
                        throw new InvalidOperationException("Charset field not switched for: " + protocol);
                    profile.SetAnonymousForTest(true);
                    if (!profile.AnonymousSelected || !profile.CredentialsDisabled)
                        throw new InvalidOperationException("Anonymous checkbox does not disable credentials: " + protocol);
                    profile.VerifyFieldLayout();
                    if (protocol == "FTP")
                    {
                        using var anonShot = new Bitmap(profile.Width, profile.Height);
                        profile.DrawToBitmap(anonShot, new Rectangle(Point.Empty, anonShot.Size));
                        anonShot.Save(Path.Combine(directory, "profile-ftp-anonymous.png"));
                    }
                    profile.SetAnonymousForTest(false);
                    if (profile.CredentialsDisabled)
                        throw new InvalidOperationException("Anonymous toggle failed to restore inputs: " + protocol);
                    if (profile.FtpSettingsVisible != (protocol == "FTP") ||
                        profile.SftpSettingsVisible != (protocol == "SFTP") ||
                        profile.WebDavSettingsVisible != (protocol == "WebDAV"))
                        throw new InvalidOperationException("Protocol-specific options are incorrect: " + protocol);
                    if (profile.ModeZEnabled)
                        throw new InvalidOperationException("Unsupported FTP Mode Z was enabled");
                    if (protocol == "FTP")
                    {
                        using var ftpShot = new Bitmap(profile.Width, profile.Height);
                        profile.DrawToBitmap(ftpShot, new Rectangle(Point.Empty, ftpShot.Size));
                        ftpShot.Save(Path.Combine(directory, "profile-ftp.png"));
                    }
                    if (protocol == "SMB")
                    {
                        using var smbShot = new Bitmap(profile.Width, profile.Height);
                        profile.DrawToBitmap(smbShot, new Rectangle(Point.Empty, smbShot.Size));
                        smbShot.Save(Path.Combine(directory, "profile-smb.png"));
                    }
                }
                profile.SelectProtocol("GoogleDrive");
                Application.DoEvents();
                profile.VerifyFieldLayout();
                if (profile.SelectedProtocol != "GoogleDrive" ||
                    !profile.ProtocolRowAligned || profile.WebDavSettingsVisible ||
                    profile.SftpSettingsVisible || profile.FtpSettingsVisible ||
                    !profile.CloudNetworkOptionVisible ||
                    !profile.CloudNetworkSelected)
                    throw new InvalidOperationException("Google Drive tile or conditional options failed");
                profile.SetCloudNetworkForTest(false);
                if (profile.CloudNetworkSelected)
                    throw new InvalidOperationException("Fixed cloud fallback checkbox does not toggle");
                using (var googleShot = new Bitmap(profile.Width, profile.Height))
                {
                    profile.DrawToBitmap(googleShot, new Rectangle(Point.Empty, googleShot.Size));
                    googleShot.Save(Path.Combine(directory, "profile-google.png"));
                }
                profile.SelectProtocol("OneDrive");
                Application.DoEvents();
                profile.VerifyFieldLayout();
                if (profile.SelectedProtocol != "OneDrive" ||
                    !profile.ProtocolRowAligned || profile.FtpSettingsVisible ||
                    profile.SftpSettingsVisible || profile.WebDavSettingsVisible ||
                    !profile.CloudNetworkOptionVisible ||
                    !profile.CloudNetworkSelected)
                    throw new InvalidOperationException("OneDrive tab/options invalid");
                using (var oneShot = new Bitmap(profile.Width, profile.Height))
                {
                    profile.DrawToBitmap(oneShot, new Rectangle(Point.Empty, oneShot.Size));
                    oneShot.Save(Path.Combine(directory, "profile-onedrive.png"));
                }
                profile.SelectProtocol("TeraBox");
                Application.DoEvents();
                profile.VerifyFieldLayout();
                if (profile.SelectedProtocol != "TeraBox" || profile.SelectedMode != 0 ||
                    !profile.ProtocolRowAligned || profile.FtpSettingsVisible ||
                    profile.SftpSettingsVisible || profile.WebDavSettingsVisible ||
                    !profile.TeraCookieHelpVisible || !profile.TeraCookieHelpAligned ||
                    !profile.CloudNetworkOptionVisible ||
                    !profile.CloudNetworkSelected)
                    throw new InvalidOperationException("TeraBox connection tile / read-write default / cookie help invalid");
                using (var teraShot = new Bitmap(profile.Width, profile.Height))
                {
                    profile.DrawToBitmap(teraShot, new Rectangle(Point.Empty, teraShot.Size));
                    teraShot.Save(Path.Combine(directory, "profile-terabox.png"));
                }
                profile.SelectProtocol("MyBox");
                Application.DoEvents();
                profile.VerifyFieldLayout();
                if (profile.SelectedMode != 0 || profile.CheckedModeCount != 1 ||
                    !profile.MyBoxPatFieldVisible ||
                    !profile.MyBoxPatHelpVisible || !profile.MyBoxPatHelpAligned ||
                    !ModernProfileDialog.MyBoxPatHelpText.Contains("화면 하단") ||
                    profile.SidebarIconCount != 8 || !profile.ProtocolRowAligned)
                    throw new InvalidOperationException("MYBOX writable default/PAT help/sidebar failed");
                using (var myShot = new Bitmap(profile.Width, profile.Height))
                {
                    profile.DrawToBitmap(myShot, new Rectangle(Point.Empty, myShot.Size));
                    myShot.Save(Path.Combine(directory, "profile-mybox-default-write.png"));
                }
                profile.SelectMode(1);
                Application.DoEvents();
                profile.VerifyFieldLayout();
                if (profile.SelectedMode != 1 || profile.CheckedModeCount != 1)
                    throw new InvalidOperationException("MYBOX read-only checkbox failed");
                using (var myWriteShot = new Bitmap(profile.Width, profile.Height))
                {
                    profile.DrawToBitmap(myWriteShot, new Rectangle(Point.Empty, myWriteShot.Size));
                    myWriteShot.Save(Path.Combine(directory, "profile-mybox-readonly.png"));
                }
                profile.SelectProtocol("WebDAV");
                if (profile.TeraCookieHelpVisible || profile.CloudNetworkOptionVisible)
                    throw new InvalidOperationException("Cloud-only settings leaked into WebDAV");
                profile.SelectProtocol("GoogleDrive");
                if (profile.CloudNetworkSelected)
                    throw new InvalidOperationException("Google Drive mode draft was not preserved");
                profile.SelectProtocol("WebDAV");
                profile.SelectMode(0);
                using (var adding = new ModernProfileDialog(new NasProfile
                    { DisplayName = "New profile", Protocol = "WebDAV", Host = "localhost",
                      Port = 5006, Letter = "Y", UseLockBridge = true }, false))
                {
                    adding.Show(dashboard);
                    Application.DoEvents();
                    adding.VerifyFieldLayout();
                    if (adding.PortHasSpinner || adding.PortText != "5006")
                        throw new InvalidOperationException("New WebDAV port input incorrect");
                    adding.SelectProtocol("FTP");
                    Application.DoEvents();
                    adding.VerifyFieldLayout();
                    if (adding.PortText != "21" || !adding.FtpSettingsVisible)
                        throw new InvalidOperationException("New FTP default port or options incorrect");
                    using var newShot = new Bitmap(adding.Width, adding.Height);
                    adding.DrawToBitmap(newShot, new Rectangle(Point.Empty, newShot.Size));
                    newShot.Save(Path.Combine(directory, "profile-new-ftp.png"));
                    adding.Hide();
                }
                using (var httpProfile = new ModernProfileDialog(new NasProfile
                    { Protocol = "WebDAV", DisplayName = "HTTP-only test", Host = "localhost",
                      Port = 5000, WebDavUseHttp = true, Letter = "X" }, true))
                {
                    httpProfile.Show(dashboard);
                    Application.DoEvents();
                    httpProfile.VerifyFieldLayout();
                    if (!httpProfile.HttpTransportSelected)
                        throw new InvalidOperationException("HTTP transport toggle not restored");
                    using var picture = new Bitmap(httpProfile.Width, httpProfile.Height);
                    httpProfile.DrawToBitmap(picture, new Rectangle(Point.Empty, picture.Size));
                    picture.Save(Path.Combine(directory, "profile-webdav-http.png"));
                    httpProfile.Hide();
                }
                using (var bitmap = new Bitmap(profile.Width, profile.Height))
                {
                    profile.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "profile.png"));
                }
                profile.Hide();
                using (var sftp = new ModernProfileDialog(new NasProfile
                    { Protocol = "SFTP", DisplayName = "SFTP 테스트", Host = "localhost",
                      Port = 22, UseLockBridge = true, Letter = "X" }, true))
                {
                    sftp.Show(dashboard);
                    Application.DoEvents();
                    if (!sftp.OptionsScrollDisabled || sftp.CheckedModeCount != 1)
                        throw new InvalidOperationException("SFTP profile layout check failed");
                    sftp.VerifyFieldLayout();
                    using var bitmap = new Bitmap(sftp.Width, sftp.Height);
                    sftp.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "profile-sftp.png"));
                    sftp.Hide();
                }
                using (var settings = dashboard.CreateCompactSettingsForm())
                {
                    settings.Show(dashboard);
                    Application.DoEvents();
                    AssertModernButtons(settings);
                    var inventoryButton = settings.Controls.OfType<Button>()
                        .SingleOrDefault(x => x.Text == "엔진 · 버전 · README");
                    var closeButton = settings.Controls.OfType<Button>()
                        .SingleOrDefault(x => x.Text == "닫기");
                    var exportButton = settings.Controls.OfType<Button>()
                        .SingleOrDefault(x => x.Text == "설정 내보내기");
                    var importButton = settings.Controls.OfType<Button>()
                        .SingleOrDefault(x => x.Text == "설정 가져오기");
                    if (exportButton is null || importButton is null ||
                        !exportButton.Visible || !importButton.Visible ||
                        exportButton.Bounds.IntersectsWith(importButton.Bounds) ||
                        exportButton.Bounds.IntersectsWith(closeButton?.Bounds ?? Rectangle.Empty) ||
                        importButton.Bounds.IntersectsWith(closeButton?.Bounds ?? Rectangle.Empty) ||
                        exportButton.Bottom > settings.ClientSize.Height ||
                        importButton.Bottom > settings.ClientSize.Height)
                        throw new InvalidOperationException(
                            "FileHub AIO backup/restore buttons clipped or missing");
                    var startupToggle = settings.Controls.OfType<CheckBox>()
                        .SingleOrDefault(x => x.Text.StartsWith(
                            "Windows 로그인 시", StringComparison.Ordinal));
                    var nativeProbeButton = settings.Controls.OfType<Button>()
                        .SingleOrDefault(x => x.Text == "WebDAV 서버 LOCK 지원 검사");
                    if (nativeProbeButton is null || !nativeProbeButton.Visible ||
                        nativeProbeButton.Right > settings.ClientSize.Width ||
                        nativeProbeButton.Bottom > settings.ClientSize.Height)
                        throw new InvalidOperationException(
                            "WebDAV native lock capability test button missing or clipped");
                    if (inventoryButton is null || closeButton is null ||
                        inventoryButton.Bounds.IntersectsWith(closeButton.Bounds) ||
                        inventoryButton.Bottom > settings.ClientSize.Height ||
                        !inventoryButton.Visible)
                        throw new InvalidOperationException(
                            "Component inventory README button missing or overlaps settings");
                    if (startupToggle is null || !startupToggle.Visible ||
                        settings.Controls.OfType<CheckBox>().Count() != 1 ||
                        settings.Controls.OfType<LinkLabel>().Any() ||
                        settings.Controls.OfType<Button>().Any(b =>
                            b.Text.Contains("끊어진 연결 기록 정리") ||
                            b.Text.Contains("탐색기만 다시 시작")))
                        throw new InvalidOperationException(
                            "Retired settings controls visible or Windows autostart missing");
                    var settingsButtons = settings.Controls.OfType<Button>().ToArray();
                    if (settingsButtons.Any(b => b.Left < 0 || b.Top < 0 ||
                            b.Right > settings.ClientSize.Width ||
                            b.Bottom > settings.ClientSize.Height) ||
                        settingsButtons.SelectMany((b, i) =>
                            settingsButtons.Skip(i + 1).Select(other =>
                                b.Bounds.IntersectsWith(other.Bounds))).Any(overlap => overlap))
                        throw new InvalidOperationException(
                            "Compact settings button overlaps or clips at 100% DPI");
                    ComponentInventory.ManifestSelfTest();
                    var updateButton = settings.Controls.OfType<Button>()
                        .SingleOrDefault(x => x.Text == "PC 업데이트 확인");
                    if (updateButton is null || !updateButton.Visible ||
                        updateButton.Bottom > settings.ClientSize.Height ||
                        updateButton.Bounds.IntersectsWith(inventoryButton.Bounds) ||
                        updateButton.Bounds.IntersectsWith(closeButton.Bounds))
                        throw new InvalidOperationException(
                            "PC update button missing or overlapping compact settings");
                    var shareButton = settings.Controls.OfType<Button>()
                        .SingleOrDefault(x => x.Text == "Synology 공유 설정");
                    if (shareButton is null || !shareButton.Visible ||
                        shareButton.Bounds.IntersectsWith(updateButton.Bounds) ||
                        shareButton.Bottom > settings.ClientSize.Height)
                        throw new InvalidOperationException(
                            "Synology link management button missing or overlapping settings");
                    if (settings.AutoScroll || settings.ClientSize.Height > 438)
                        throw new InvalidOperationException("Global settings layout too tall");
                    dashboard.VerifyUpdateNoticeForUiTest();
                    using var bitmap = new Bitmap(settings.Width, settings.Height);
                    settings.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "settings.png"));
                    settings.Hide();
                }
                using (var synology = new SynologySettingsForm(new AppConfig {
                    Profiles = [new NasProfile {
                        Protocol = "WebDAV", DisplayName = "테스트 NAS",
                        Letter = "X", Host = "nas.test", Share = "/video"
                    }]
                }))
                {
                    synology.Show(dashboard);
                    Application.DoEvents();
                    var buttons = synology.Controls.OfType<Button>().ToArray();
                    if (!buttons.Any(b => b.Text == "설정 저장") ||
                        !buttons.Any(b => b.Text == "DSM 연결 확인") ||
                        !buttons.Any(b => b.Text == "목록 불러오기 · 다중선택") ||
                        buttons.Any(b => b.Text == "공유 링크 생성" ||
                            b.Text == "선택 링크 해제") ||
                        !synology.Controls.OfType<CheckBox>().Any(x =>
                            x.Text.Contains("이 PC 신뢰") && x.Visible &&
                            x.Right <= synology.ClientSize.Width) ||
                        !synology.Controls.OfType<ComboBox>().Any(x =>
                            x.Items.Cast<object>().Any(v => v?.ToString() == "무제한")) ||
                        !buttons.Any(b => b.Text.Contains("탐색기") &&
                            b.Text.Contains("메뉴")) ||
                        buttons.Any(b => b.Bottom > synology.ClientSize.Height ||
                            b.Right > synology.ClientSize.Width ||
                            b.Left < 0 || b.Top < 0) ||
                        buttons.Any(b => synology.Controls.OfType<TextBox>().Any(
                            tb => b.Bounds.IntersectsWith(tb.Bounds))))
                        throw new InvalidOperationException(
                            "Synology multi-share controls missing, overlapping or clipped");
                    using var bitmap = new Bitmap(synology.Width, synology.Height);
                    synology.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "synology-sharing.png"));
                    synology.Hide();
                }
                using (var quick = new SynologyQuickLinkForm(new AppConfig(),
                    @"X:\테스트.xlsx", previewForUiTest: true))
                {
                    quick.Show(dashboard);
                    Application.DoEvents();
                    if (!quick.HasLink ||
                        quick.ClientSize.Height > 245 ||
                        !quick.Controls.OfType<Button>().Any(b =>
                            b.Text == "링크 복사" && b.Enabled) ||
                        quick.Controls.OfType<TextBox>().Count() != 1 ||
                        quick.Controls.OfType<Button>().Any(b =>
                            b.Bottom > quick.ClientSize.Height ||
                            b.Right > quick.ClientSize.Width))
                        throw new InvalidOperationException(
                            "Synology quick-link copy dialog regression");
                    using var bitmap = new Bitmap(quick.Width, quick.Height);
                    quick.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "synology-quick-link.png"));
                    quick.Hide();
                }
                using (var selector = new SynologyShareSelectorDialog([
                    new SynologyFileEntry("01자료", "/01자료", true),
                    new SynologyFileEntry("video", "/video", true),
                    new SynologyFileEntry("Public", "/Public", true)
                ], ["/video", "/Public"]))
                {
                    selector.Show(dashboard);
                    Application.DoEvents();
                    if (!selector.SelectedRoots.SequenceEqual(["/video", "/Public"]) ||
                        !selector.Controls.OfType<CheckedListBox>().Any(l =>
                            l.Items.Count == 3 && l.CheckedItems.Count == 2) ||
                        selector.Controls.OfType<Button>().Any(b =>
                            b.Bottom > selector.ClientSize.Height ||
                            b.Right > selector.ClientSize.Width))
                        throw new InvalidOperationException(
                            "Synology multi-share checkbox dialog regression");
                    using var bitmap = new Bitmap(selector.Width, selector.Height);
                    selector.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size));
                    bitmap.Save(Path.Combine(directory, "synology-share-selection.png"));
                    selector.Hide();
                }
                using (var components = new ComponentInventoryDialog())
                {
                    components.Show(dashboard);
                    Application.DoEvents();
                    AssertModernButtons(components);
                    var readme = components.Controls.OfType<RichTextBox>()
                        .SingleOrDefault();
                    if (readme is null || !readme.ReadOnly ||
                        !readme.Text.Contains("공식 rclone", StringComparison.Ordinal) ||
                        !readme.Text.Contains("v1.76.0-extra", StringComparison.Ordinal) ||
                        !readme.Text.Contains("ACD80683E23151E93BBE0D6", StringComparison.Ordinal))
                        throw new InvalidOperationException(
                            "Component inventory dialog missing recorded versions");
                    using var shot = new Bitmap(components.Width, components.Height);
                    components.DrawToBitmap(shot, new Rectangle(Point.Empty, shot.Size));
                    shot.Save(Path.Combine(directory, "components-version-readme.png"));
                    components.Hide();
                }
                dashboard.Hide();
                File.WriteAllText(Path.Combine(directory, "result.txt"),
                    "UI_SMOKE_PASS: dashboard, profile and compact settings rendered; menus reused twice; exclusive checkboxes and no-scroll verified; all painted buttons have no clipping Region");
            }
            catch (Exception ex)
            {
                File.WriteAllText(Path.Combine(directory, "result.txt"), "UI_SMOKE_FAILED " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--local-visible-owner-marker-test", StringComparer.OrdinalIgnoreCase))
        {
            // Entirely isolated loopback fixture. No production NAS, user
            // token, Office file or drive letter is accessed.
            int port = int.Parse(Environment.GetEnvironmentVariable(
                "NASMOUNT_VISIBLE_MARKER_TEST_PORT") ?? "21562");
            if (port < 1024 || port > 65535) throw new ArgumentOutOfRangeException(nameof(port));
            var profile = new NasProfile { Protocol = "WebDAV",
                Host = "127.0.0.1", Port = port, WebDavUseHttp = true,
                IsAnonymous = true, Letter = "R" };
            var name = Environment.GetEnvironmentVariable("NASMOUNT_VISIBLE_MARKER_KOREAN") == "1"
                ? "월매출_검사_" + Guid.NewGuid().ToString("N")[..8] + ".xls"
                : ".nasmount_probe_" + Guid.NewGuid().ToString("N") + ".xlsx";
            CooperativeLocks.Lease? lease = null;
            try
            {
                string marker = OfficeUseMarker.RelativePath(name);
                if (marker != "NASMount_사용중_" + (name.EndsWith(".xlsx")
                        ? name[..^5] : name) + ".xlsx" ||
                    !OfficeUseMarker.AppliesTo(name) ||
                    OfficeUseMarker.AppliesTo("data.txt") ||
                    OfficeUseMarker.AppliesTo(marker) ||
                    OfficeUseMarker.RelativePath("folder/매출.xlsx") !=
                        "folder/NASMount_사용중_매출.xlsx")
                    throw new InvalidOperationException("Visible Office marker naming incorrect");
                lease = CooperativeLocks.Acquire(profile, "", name, useWebDavNativeLock: false);
                if (lease.VisibleMarker != marker)
                    throw new IOException("WebDAV server did not create the visible marker");
                using var secondClient = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
                string markerUrl = $"http://127.0.0.1:{port}/" + Uri.EscapeDataString(marker);
                byte[] contents = secondClient.GetByteArrayAsync(markerUrl).GetAwaiter().GetResult();
                if (OfficeMarkerWorkbook.ReadLeaseId(contents) != lease.Id ||
                    contents.Length < 400 || contents[0] != (byte)'P' || contents[1] != (byte)'K')
                    throw new IOException("Second WebDAV client did not see valid Excel owner metadata");
                string realExcelOwner = $"http://127.0.0.1:{port}/" +
                    Uri.EscapeDataString("~$" + name);
                using (var office = secondClient.GetAsync(realExcelOwner).GetAwaiter().GetResult())
                    if (office.StatusCode != System.Net.HttpStatusCode.NotFound)
                        throw new IOException("Office's exclusive ~$ file was touched");
                // A second client may not overwrite a status marker; this
                // test server handles MKCOL non-atomically, so lock collision
                // is covered separately by the strict WebDAV lock fixture.
                var unrelatedLease = lease with { Id = Guid.NewGuid().ToString("N") };
                if (OfficeUseMarker.Create(secondClient, markerUrl, unrelatedLease))
                    throw new IOException("Existing visible marker was overwritten");
                byte[] stillOriginal = secondClient.GetByteArrayAsync(markerUrl)
                    .GetAwaiter().GetResult();
                if (OfficeMarkerWorkbook.ReadLeaseId(stillOriginal) != lease.Id)
                    throw new IOException("Owner marker contents were changed by another client");
                CooperativeLocks.Release(profile, "", lease);
                lease = null;
                using var after = secondClient.GetAsync(markerUrl).GetAwaiter().GetResult();
                if (after.StatusCode != System.Net.HttpStatusCode.NotFound)
                    throw new IOException("Visible marker remains after proper lock release");
                Console.WriteLine("VISIBLE_OWNER_MARKER_TWO_CLIENT_PASS");
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("VISIBLE_OWNER_MARKER_TWO_CLIENT_FAIL " + ex);
                Environment.ExitCode = 1;
            }
            finally
            {
                if (lease is not null)
                {
                    try { CooperativeLocks.Release(profile, "", lease); }
                    catch (Exception ex) { Console.Error.WriteLine("TEST_CLEANUP_WARNING " + ex.Message); }
                }
            }
            return;
        }
        if (args.Contains("--local-webdav-lock-test", StringComparer.OrdinalIgnoreCase))
        {
            // Intentionally loopback only. The fixture creates a disposable
            // .nasmount.lock directory and verifies two-client collision.
            int port = int.Parse(Environment.GetEnvironmentVariable("NASMOUNT_WEBDAV_LOCK_TEST_PORT") ?? "21442");
            if (port < 1024 || port > 65535) throw new ArgumentOutOfRangeException(nameof(port));
            var profile = new NasProfile
            {
                Protocol = "WebDAV", Host = "127.0.0.1", Port = port,
                WebDavUseHttp = true, Username = "local-test", Letter = "R"
            };
            try
            {
                string report = CooperativeLocks.Test(profile, "fixture-only");
                Console.WriteLine("LOCAL_WEBDAV_LOCK_COLLISION_PASS " + report);
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine("LOCAL_WEBDAV_LOCK_COLLISION_FAIL " + ex.Message);
                Environment.ExitCode = 1;
            }
            return;
        }
        if (args.Contains("--local-ftp-lock-test", StringComparer.OrdinalIgnoreCase))
        {
            // This diagnostic deliberately refuses to contact non-loopback servers.
            var profile = new NasProfile
            {
                Protocol = "FTP", Host = "127.0.0.1",
                Port = int.Parse(Environment.GetEnvironmentVariable("NASMOUNT_FTP_TEST_PORT") ?? "21219"),
                Username = "testuser", Share = "", ExplicitTls = false, Letter = "R"
            };
            try
            {
                string result = CooperativeLocks.Test(profile, "testpass_local_only");
                Directory.CreateDirectory(AppStore.DirectoryPath);
                File.WriteAllText(Path.Combine(AppStore.DirectoryPath, "ftp-lock-integration.txt"),
                    "PASS " + result);
            }
            catch (Exception ex)
            {
                Directory.CreateDirectory(AppStore.DirectoryPath);
                File.WriteAllText(Path.Combine(AppStore.DirectoryPath, "ftp-lock-integration.txt"),
                    "FAIL " + ex);
                Environment.ExitCode = 1;
            }
            return;
        }

        if (args.Contains("--local-ftp-connect-test", StringComparer.OrdinalIgnoreCase))
        {
            var p = new NasProfile
            {
                Protocol = "FTP", Host = "127.0.0.1", Port = 21219,
                Share = "", Username = "testuser", ExplicitTls = false, Letter = "R"
            };
            var result = RemoteMount.Diagnose(p, "testpass_local_only").GetAwaiter().GetResult();
            Directory.CreateDirectory(AppStore.DirectoryPath);
            File.WriteAllText(Path.Combine(AppStore.DirectoryPath, "ftp-connect-integration.txt"),
                (result.Success ? "PASS " : "FAIL ") + result.Message);
            if (!result.Success) Environment.ExitCode = 1;
            return;
        }

        if (args.Contains("--local-webdav-save-test", StringComparer.OrdinalIgnoreCase))
        {
            // Local fixture only: never address a real NAS from self-tests.
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            var result = WebDavSaveProbe.Test(client,
                new Uri("http://127.0.0.1:21441/")).GetAwaiter().GetResult();
            Directory.CreateDirectory(AppStore.DirectoryPath);
            File.WriteAllText(Path.Combine(AppStore.DirectoryPath, "webdav-save-integration.txt"),
                (result.Success ? "PASS " : "FAIL ") + result.Message);
            if (!result.Success) Environment.ExitCode = 1;
            return;
        }

        if (args.Contains("--local-webdav-lock-test", StringComparer.OrdinalIgnoreCase))
        {
            // Loopback-only. No production NAS or pre-existing documents.
            using var primaryHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            using var secondaryHttp = new HttpClient { Timeout = TimeSpan.FromSeconds(8) };
            var result = WebDavLockProbe.Test(primaryHttp, secondaryHttp,
                new Uri("http://127.0.0.1:21441/")).GetAwaiter().GetResult();
            Directory.CreateDirectory(AppStore.DirectoryPath);
            File.WriteAllText(Path.Combine(AppStore.DirectoryPath, "webdav-lock-integration.txt"),
                (result.Success ? "PASS " : "FAIL ") +
                "supported="+result.Supported+" blocked="+result.Enforced+
                " token-write="+result.AuthorizedWrite+
                " unlocked="+result.Released+" cleaned="+result.CleanupSucceeded+"\n"+
                result.Detail);
            if (!result.Success) Environment.ExitCode = 1;
            return;
        }

        if (args.Contains("--local-webdav-read-test", StringComparer.OrdinalIgnoreCase))
        {
            // Loopback-only test; never access production NAS or user documents.
            string output=Path.Combine(AppStore.DirectoryPath,"webdav-read-integration.txt");
            Directory.CreateDirectory(AppStore.DirectoryPath);
            using var http=new HttpClient { Timeout=TimeSpan.FromSeconds(15) };
            var uri=new Uri("http://127.0.0.1:21441/.nasmount_probe_"+
                Guid.NewGuid().ToString("N")+".xlsx");
            try
            {
                byte[] bytes=System.Security.Cryptography.RandomNumberGenerator.GetBytes(700_000);
                using(var content=new ByteArrayContent(bytes))
                using(var put=http.PutAsync(uri,content).GetAwaiter().GetResult())
                    put.EnsureSuccessStatusCode();
                var probe=OfficeReadProbe.ReadRemote(http,uri,256*1024,
                    CancellationToken.None).GetAwaiter().GetResult();
                bool passed=probe.RangeSupported && probe.BytesFirst==256*1024 &&
                    probe.BytesTail==256*1024 &&
                    System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
                        probe.FirstHash,System.Security.Cryptography.SHA256.HashData(
                            bytes.AsSpan(0,256*1024))) &&
                    System.Security.Cryptography.CryptographicOperations.FixedTimeEquals(
                        probe.TailHash,System.Security.Cryptography.SHA256.HashData(
                            bytes.AsSpan(bytes.Length-256*1024)));
                File.WriteAllText(output,passed
                    ? "PASS WebDAV HEAD / Range first and tail / SHA256"
                    : "FAIL HTTP range or content verification");
                if(!passed)Environment.ExitCode=1;
            }
            catch(Exception ex)
            {
                File.WriteAllText(output,"FAIL "+ex);
                Environment.ExitCode=1;
            }
            finally
            {
                try { using var response=http.DeleteAsync(uri).GetAwaiter().GetResult(); }
                catch { }
            }
            return;
        }

        using var mutex = new Mutex(true, @"Local\NASMount_GUI_6ED3194D", out var first);
        if (!first)
        {
            MessageBox.Show("FileHub AIO가 이미 실행 중입니다. 작업 표시줄의 숨겨진 아이콘을 확인하세요.",
                "FileHub AIO", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        // This must happen BEFORE any rclone/LockBridge is created. A Windows
        // Job Object now owns the GUI and every child mount: even a crash or
        // Task Manager forced exit terminates owned mount processes.
        try { MountProcessLifetime.AttachGuiBeforeMounts(); }
        catch (Exception ex)
        {
            MessageBox.Show(
                "FileHub 연결을 Windows 종료 수명에 묶을 수 없어 마운트를 시작하지 않습니다.\n\n" +
                ex.Message + "\n\n기존 드라이브는 변경하지 않았습니다.",
                "FileHub AIO · 마운트 종료 보호", MessageBoxButtons.OK,
                MessageBoxIcon.Error);
            return;
        }
        Application.Run(new MainForm(args.Contains("--minimized", StringComparer.OrdinalIgnoreCase)));
    }
}

internal static class SelfTest
{
    public static void Run()
    {
        var results = new StringBuilder();
        try
        {
            StartupReadinessPolicy.SelfTest();
            ExplorerRefreshCoalescer.PolicyTest();
            ShellArrivalBatch.PolicyTest();
            GoogleStartupTrace.PolicyTest();
            GoogleMountPacingPolicy.SelfTest();
            GoogleExplorerStabilityPolicy.SelfTest();
            ProfileDeletionPolicy.SelfTest();
            ExplorerDriveIcon.DisplayNamePolicyTest();
            void Verify(string description, bool condition)
            {
                results.AppendLine((condition ? "PASS " : "FAIL ") + description);
                if (!condition) throw new InvalidOperationException(description);
            }
            Verify("valid UNC", WindowsShare.TryValidate(new NasProfile
            {
                Host = "nas.local", Share = "Documents", Letter = "R"
            }, out _));
            Verify("valid WebDAV profile", RemoteMount.TryValidate(new NasProfile
            {
                Protocol = "WebDAV", Host = "nas.local", Share = "/Documents", Port = 5006, Letter = "R"
            }, out _));
            var lockStatusDav = new NasProfile { Protocol = "WebDAV", UseLockBridge = true };
            Verify("connected WebDAV cooperative lock clearly shown",
                LockStatusPresentation.CardStatus(lockStatusDav, true, false).Contains("파일잠금 브리지"));
            lockStatusDav.UseLockBridge = false;
            lockStatusDav.AllowUnprotectedWrites = true;
            Verify("unprotected writable WebDAV warned",
                LockStatusPresentation.CardStatus(lockStatusDav, true, false).Contains("잠금 꺼짐"));
            lockStatusDav.AllowUnprotectedWrites = false;
            Verify("WebDAV read-only state differentiated",
                LockStatusPresentation.CardStatus(lockStatusDav, true, false).Contains("읽기 전용"));
            foreach (string provider in new[] {
                "WebDAV", "FTP", "SFTP", "GoogleDrive", "OneDrive", "TeraBox", "MyBox"
            })
            {
                var selection = new NasProfile { Protocol = provider };
                ModernProfileDialog.ApplyPerformanceMode(selection, 0);
                Verify(provider + " writable mode does not display read-only",
                    !LockStatusPresentation.IsReadOnlyMode(selection) &&
                    !LockStatusPresentation.CardStatus(selection, true, false)
                        .Contains("읽기 전용"));
                ModernProfileDialog.ApplyPerformanceMode(selection, 1);
                Verify(provider + " read-only mode displays read-only in green status",
                    LockStatusPresentation.IsReadOnlyMode(selection) &&
                    LockStatusPresentation.CardStatus(selection, true, false)
                        .Contains("연결됨 · 읽기 전용"));
                Verify(provider + " disconnected state never claims read-only connected",
                    !LockStatusPresentation.CardStatus(selection, false, false)
                        .Contains("읽기 전용"));
            }
            Verify("SMB status never promises a local read-only enforcement",
                !LockStatusPresentation.CardStatus(
                    new NasProfile { Protocol = "SMB" }, true, false)
                    .Contains("읽기 전용"));
            var myboxLegacyNames = new AppConfig { Profiles = [
                new NasProfile { Protocol = "MyBox", DisplayName = "네이버 MYBOX (beta)" },
                new NasProfile { Protocol = "MyBox", DisplayName = "우리회사 MYBOX" },
                new NasProfile { Protocol = "WebDAV", DisplayName = "네이버 MYBOX (beta)" }
            ] };
            Verify("MYBOX old built-in default is normalized once",
                AppStore.NormalizeLegacyMyBoxDefaultNames(myboxLegacyNames) &&
                myboxLegacyNames.Profiles[0].DisplayName == "네이버 MYBOX" &&
                myboxLegacyNames.Profiles[1].DisplayName == "우리회사 MYBOX" &&
                myboxLegacyNames.Profiles[2].DisplayName == "네이버 MYBOX (beta)" &&
                !AppStore.NormalizeLegacyMyBoxDefaultNames(myboxLegacyNames));
            var davPlain = new NasProfile
            {
                Protocol = "WebDAV", Host = "nas.local", Port = 5000,
                WebDavUseHttp = true, Letter = "R"
            };
            Verify("HTTP WebDAV requires explicit opt-in", !new NasProfile().WebDavUseHttp);
            Verify("HTTP WebDAV URI uses exact scheme and port",
                WebDavSaveProbe.RootUri(davPlain).AbsoluteUri == "http://nas.local:5000/");
            davPlain.WebDavUseHttp = false;
            Verify("HTTPS WebDAV remains default",
                WebDavSaveProbe.RootUri(davPlain).AbsoluteUri == "https://nas.local:5000/");
            Verify("DAV realm is correctly detected",
                FtpServiceDetector.DetectHttp("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=\"DAV-root\"\r\n\r\n") == FtpServiceKind.HttpWebDav);
            Verify("normal FTP greeting is not mistaken for HTTP",
                FtpServiceDetector.DetectHttp("220 FTP Ready\r\n") == FtpServiceKind.Unknown);
            Verify("Windows cache rename failure is high-severity",
                RcloneIntegrityMonitor.Classify(
                    "INFO: test.xlsx: File.Rename failed in Cache: failed to rename in cache: Access denied")
                    ?.Contains("위험", StringComparison.Ordinal) == true);
            Verify("VFS upload failure is high-severity",
                RcloneIntegrityMonitor.Classify(
                    "ERROR : x.xlsx: vfs cache: upload failed")
                    ?.Contains("위험", StringComparison.Ordinal) == true);
            Verify("successful VFS upload is not a false warning",
                RcloneIntegrityMonitor.Classify("INFO: x.xlsx: vfs cache: upload succeeded try #1") is null);
            Verify("valid SFTP profile", RemoteMount.TryValidate(new NasProfile
            {
                Protocol = "SFTP", Host = "nas.local", Share = "/volume1/Documents", Port = 22, Letter = "S"
            }, out _));
            Verify("valid FTP profile", RemoteMount.TryValidate(new NasProfile
            {
                Protocol = "FTP", Host = "nas.local", Share = "/Documents", Port = 21, Letter = "T"
            }, out _));
            Verify("same RC process PID detected",
                RemoteMount.MatchingProcessPid("{\"pid\":24007}",24007));
            Verify("unexpected RC process PID rejected",
                !RemoteMount.MatchingProcessPid("{\"pid\":24008}",24007));
            Verify("existing WebDAV profiles preserve network mode",
                RemoteMount.UsesNetworkDriveMode(new NasProfile { Protocol = "WebDAV" }));
            Verify("fixed mode omits synthetic UNC network mode",
                !RemoteMount.UsesNetworkDriveMode(new NasProfile {
                    Protocol = "WebDAV", UseFixedDriveMode = true
                }));
            Verify("Google Drive shows in network locations by default",
                RemoteMount.UsesNetworkDriveMode(new NasProfile {
                    Protocol = "GoogleDrive", UseFixedDriveMode = false
                }));
            Verify("TeraBox shows in network locations by default",
                RemoteMount.UsesNetworkDriveMode(new NasProfile {
                    Protocol = "TeraBox", UseFixedDriveMode = false
                }));
            Verify("OneDrive shows in network locations by default",
                RemoteMount.UsesNetworkDriveMode(new NasProfile {
                    Protocol = "OneDrive", UseFixedDriveMode = false
                }));
            foreach (string cloud in new[] { "GoogleDrive", "TeraBox", "OneDrive" })
            {
                var legacyCloud = new NasProfile {
                    Protocol = cloud, DisplayName = cloud switch {
                        "GoogleDrive" => "Google Drive",
                        "OneDrive" => "OneDrive",
                        _ => "TeraBox"
                    },
                    UseFixedDriveMode = false
                };
                var mountArgs = RemoteMount.WinFspPresentationArgs(legacyCloud);
                Verify(cloud + " actual mount command uses network mode",
                    mountArgs.Contains("--network-mode"));
                Verify(cloud + " actual mount command owns exact UNC share",
                    mountArgs.Last() == MountPresentation.RcloneNetworkVolume(legacyCloud));
                legacyCloud.UseFixedDriveMode = true;
                var safeArgs = RemoteMount.WinFspPresentationArgs(legacyCloud);
                Verify(cloud + " optional fixed mode omits UNC",
                    safeArgs.SequenceEqual(new[] {
                        "--volname", legacyCloud.DisplayName
                    }));
            }
            var preservedNasNetwork = new NasProfile {
                Protocol = "WebDAV", DisplayName = "NAS"
            };
            var nasArgs = RemoteMount.WinFspPresentationArgs(preservedNasNetwork);
            Verify("existing NAS network presentation unchanged",
                nasArgs.Contains("--network-mode") &&
                nasArgs.Last() == MountPresentation.RcloneNetworkVolume(preservedNasNetwork));
            Verify("Google Drive fixed fallback detected only when selected",
                RemoteMount.UsesStableCloudFixedMode(new NasProfile {
                    Protocol = "GoogleDrive", UseFixedDriveMode = true
                }) && !RemoteMount.UsesStableCloudFixedMode(new NasProfile {
                    Protocol = "GoogleDrive", UseFixedDriveMode = false
                }));
            Verify("TeraBox fixed fallback detected only when selected",
                RemoteMount.UsesStableCloudFixedMode(new NasProfile {
                    Protocol = "TeraBox", UseFixedDriveMode = true
                }) && !RemoteMount.UsesStableCloudFixedMode(new NasProfile {
                    Protocol = "TeraBox", UseFixedDriveMode = false
                }));
            Verify("device broadcast letter masks",
                ExplorerDriveIcon.VolumeUnitMask("A") == 1 &&
                ExplorerDriveIcon.VolumeUnitMask("Q") == 65536 &&
                ExplorerDriveIcon.VolumeUnitMask("Z") == 1 << 25);
            Verify("NAS WebDAV/SFTP/FTP still support network presentation",
                new[] { "WebDAV", "SFTP", "FTP" }.All(t =>
                    RemoteMount.UsesNetworkDriveMode(new NasProfile {
                        Protocol = t, UseLockBridge = false
                    })));
            Verify("fixed cloud icon permitted only for verified managed mount",
                ExplorerDriveIcon.MayApplyIcon(DriveType.Fixed, true, true) &&
                !ExplorerDriveIcon.MayApplyIcon(DriveType.Fixed, false, true) &&
                !ExplorerDriveIcon.MayApplyIcon(DriveType.Fixed, true, false) &&
                !ExplorerDriveIcon.MayApplyIcon(DriveType.CDRom, true, true));
            Verify("WebDAV diagnostic URI encodes Excel Korean filename",
                OfficeReadProbe.FileUri(new NasProfile {
                    Protocol="WebDAV",Host="nas.local",Port=5006,Share="/Docs"
                },"2026 매출/정산.xlsx").AbsoluteUri.Contains("%EC%A0%95%EC%82%B0.xlsx",
                    StringComparison.OrdinalIgnoreCase));
            Verify("lock bridge stays separate from network mode",
                !RemoteMount.UsesNetworkDriveMode(new NasProfile {
                    Protocol = "WebDAV", UseLockBridge = true
                }));
            Verify("pending VFS upload blocks dismount",
                !RemoteMount.ParseUploadStatus(
                    "{\"diskCache\":{\"uploadsQueued\":1,\"uploadsInProgress\":0,\"erroredFiles\":0,\"outOfSpace\":false}}")
                    .IsSafeToDisconnect);
            Verify("failed VFS upload blocks dismount",
                !RemoteMount.ParseUploadStatus(
                    "{\"diskCache\":{\"uploadsQueued\":0,\"uploadsInProgress\":0,\"erroredFiles\":1,\"outOfSpace\":false}}")
                    .IsSafeToDisconnect);
            Verify("clean VFS upload state allows dismount",
                RemoteMount.ParseUploadStatus(
                    "{\"diskCache\":{\"uploadsQueued\":0,\"uploadsInProgress\":0,\"erroredFiles\":0,\"outOfSpace\":false}}")
                    .IsSafeToDisconnect);
            Verify("reject remote URL injection", !RemoteMount.TryValidate(new NasProfile
            {
                Protocol = "WebDAV", Host = "https://nas.local/path", Share = "docs", Letter = "R"
            }, out _));
            Verify("reject URL host", !WindowsShare.TryValidate(new NasProfile
            {
                Host = "https://nas.local", Share = "Documents", Letter = "R"
            }, out _));
            Verify("reject drive path", !WindowsShare.TryValidate(new NasProfile
            {
                Host = "nas.local", Share = @"Docs\Another", Letter = "R"
            }, out _));
            var file = Path.Combine(Path.GetTempPath(), "NASMount_test_" + Guid.NewGuid().ToString("N") + ".lock");
            try
            {
                var p = WindowsShare.ProbeFileLock(Path.GetDirectoryName(file)!, Path.GetFileName(file));
                Verify("Windows exclusive handle blocks second opener", p.Success);
            }
            finally
            {
                if (File.Exists(file)) File.Delete(file);
            }
            var path = Path.Combine(Path.GetTempPath(), "NASMount_config_" + Guid.NewGuid().ToString("N") + ".json");
            try
            {
                var sample = new AppConfig
                {
                    Profiles = [new NasProfile { Protocol = "SFTP", Port = 22,
                        Host = "nas.local", Share = "/Docs", Letter = "R",
                        FastReadOnly = true, UseFixedDriveMode = true, OfficeReadTuning = true }]
                };
                AppStore.WriteConfig(path, sample);
                var loaded = AppStore.ReadConfig(path);
                Verify("JSON round trip", loaded.Profiles.Count == 1 && loaded.Profiles[0].Host == "nas.local"
                    && loaded.Profiles[0].Protocol == "SFTP" && loaded.Profiles[0].Port == 22);
                Verify("fast read setting persists", loaded.Profiles[0].FastReadOnly);
                Verify("fixed disk mode setting persists", loaded.Profiles[0].UseFixedDriveMode);
                Verify("Office tuning setting persists", loaded.Profiles[0].OfficeReadTuning);
                Verify("old profiles retain compatibility mode by default", !new NasProfile().FastReadOnly);
                Verify("old profiles keep network drive mode by default", !new NasProfile().UseFixedDriveMode);
                Verify("old profiles keep Office tuning off by default", !new NasProfile().OfficeReadTuning);
                var display = new NasProfile { DisplayName = "윈텍", Protocol = "WebDAV" };
                Verify("network drive uses user display name, not NASMount hash label",
                    MountPresentation.WinFspPrefix(display).EndsWith("\\윈텍") &&
                    MountPresentation.RcloneNetworkVolume(display).StartsWith("\\\\NASMount-"));
                var hidden = MountPresentation.NewBackendMountpoint(display);
                Verify("hidden backend uses non-existent private directory, not drive letter",
                    !Directory.Exists(hidden) &&
                    MountPresentation.IsOurBackendMountpoint(display, hidden) &&
                    hidden.StartsWith(MountPresentation.BackendParent, StringComparison.OrdinalIgnoreCase));
                Verify("private mountpoint rejects foreign and unsafe paths",
                    !MountPresentation.IsOurBackendMountpoint(new NasProfile(), hidden) &&
                    !MountPresentation.IsOurBackendMountpoint(display, "C:\\"));
                var autoA = new NasProfile { Letter = "Z" };
                var autoB = new NasProfile { Letter = "Y" };
                Verify("auto drive picks highest free Z", DriveLetterAllocator.HighestFree([], occupiedOverride: ["C:\\", "D:\\"]) == "Z");
                Verify("auto drive skips saved reservations", DriveLetterAllocator.HighestFree([autoA, autoB],
                    occupiedOverride: ["C:\\", "D:\\"]) == "X");
                Verify("auto drive skips mounted Windows drives", DriveLetterAllocator.HighestFree([autoA],
                    occupiedOverride: ["C:\\", "Y:\\", "X:\\"]) == "W");
                Verify("auto drive ignores own old reservation", DriveLetterAllocator.HighestFree([autoA], autoA.Id,
                    occupiedOverride: ["C:\\", "D:\\"]) == "Z");
                Verify("auto drive reports exhaustion", DriveLetterAllocator.HighestFree([], occupiedOverride:
                    Enumerable.Range('D', 'Z' - 'D' + 1).Select(x => ((char)x) + ":\\")) is null);
                var performance = new NasProfile { Protocol = "WebDAV", AllowUnprotectedWrites = true };
                ModernProfileDialog.ApplyPerformanceMode(performance, 0);
                Verify("document editing is protected, not unguarded", performance.UseLockBridge &&
                    !performance.AllowUnprotectedWrites && !performance.FastReadOnly && !performance.OfficeReadTuning);
                ModernProfileDialog.ApplyPerformanceMode(performance, 1);
                Verify("standard readonly retains VFS defaults", !performance.UseLockBridge &&
                    !performance.AllowUnprotectedWrites && !performance.FastReadOnly && !performance.OfficeReadTuning);
                ModernProfileDialog.ApplyPerformanceMode(performance, 1);
                Verify("removed fast read is migrated to safe read only", !performance.FastReadOnly &&
                    !performance.UseLockBridge && !performance.AllowUnprotectedWrites);
                ModernProfileDialog.ApplyPerformanceMode(performance, 2);
                Verify("Excel read optimization remains read only", performance.OfficeReadTuning &&
                    !performance.UseLockBridge && !performance.AllowUnprotectedWrites);
                var ftpOptions = new NasProfile
                {
                    Protocol = "FTP", ExplicitTls = true, FtpPreferPasv = true,
                    FtpDisableUtf8 = true, FtpTls12Only = true
                };
                var ftpEnvironment = RemoteMount.FtpEnvironment(ftpOptions);
                Verify("FTP PASV option is forwarded to rclone", ftpEnvironment["DISABLE_EPSV"] == "true");
                Verify("FTP server-default encoding option is forwarded", ftpEnvironment["DISABLE_UTF8"] == "true");
                Verify("FTP explicit TLS option forwarded", ftpEnvironment["EXPLICIT_TLS"] == "true" &&
                    ftpEnvironment["TLS"] == "false");
                Verify("FTP TLS 1.2 preference forwarded", ftpEnvironment["DISABLE_TLS13"] == "true");
                ftpOptions.FtpImplicitTls = true;
                ftpEnvironment = RemoteMount.FtpEnvironment(ftpOptions);
                Verify("FTP implicit and explicit TLS cannot both be enabled",
                    ftpEnvironment["EXPLICIT_TLS"] == "false" && ftpEnvironment["TLS"] == "true");
                Verify("charset catalog has international codepages",
                    CharsetSupport.Names.Length >= 30 &&
                    CharsetSupport.Names.Any(x => x.Contains("CP949")) &&
                    CharsetSupport.Names.Any(x => x.Contains("ISO-8859-1")));
                Verify("actual FTP UTF-8 and server-default are supported",
                    CharsetSupport.IsSupported("FTP", "UTF-8") &&
                    CharsetSupport.IsSupported("FTP", "서버 기본"));
                Verify("SFTP now accepts verified CP949 wire conversion",
                    CharsetSupport.IsSupported("SFTP", "UTF-8") &&
                    CharsetSupport.IsSupported("SFTP", "CP949 / EUC-KR (한국어)") &&
                    CharsetSupport.RequiresGateway("SFTP", "CP949 / EUC-KR (한국어)"));
                Verify("FTP Shift-JIS profile accepts gateway conversion",
                    RemoteMount.TryValidate(new NasProfile
                    { Protocol = "FTP", Host = "test.local", FtpCharset = "Shift-JIS / CP932 (일본어)" },
                        out _));
                Verify("unknown FTP charset still rejected before connection",
                    !RemoteMount.TryValidate(new NasProfile
                    { Protocol = "FTP", Host = "test.local", FtpCharset = "unknown-charset" },
                        out _));
                foreach (string protocol in new[] { "FTP", "WebDAV", "SFTP", "SMB" })
                {
                    var anon = new NasProfile { Protocol = protocol, Username = "private-account", IsAnonymous = true };
                    Verify(protocol + " anonymous effective username",
                        RemoteMount.RemoteUsername(anon) ==
                        (protocol is "FTP" or "SFTP" ? "anonymous" : ""));
                    Verify(protocol + " anonymous effective password",
                        RemoteMount.RemoteSecret(anon, "should-never-be-used") ==
                        (protocol == "FTP" ? "anonymous@" : null));
                }
                Verify("password not serialized",
                    !File.ReadAllText(path).Contains("\"Password\":", StringComparison.OrdinalIgnoreCase));
            }
            finally
            {
                if (File.Exists(path)) File.Delete(path);
            }
            results.AppendLine("ALL CHECKS PASSED");
        }
        catch (Exception ex)
        {
            results.AppendLine("SELF TEST FAILED: " + ex);
            Environment.ExitCode = 1;
        }
        var reportPath = Path.Combine(AppStore.DirectoryPath, "self-test.txt");
        Directory.CreateDirectory(AppStore.DirectoryPath);
        File.WriteAllText(reportPath, results.ToString(), new UTF8Encoding(true));
    }
}
