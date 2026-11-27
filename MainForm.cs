using System.Drawing;
using System.Diagnostics;
using System.Collections.Concurrent;
using System.Text.Json;
using System.Windows.Forms;
namespace NASMount;

internal sealed partial class MainForm : Form
{
    readonly AppConfig cfg;
    readonly Label status = new() { Dock = DockStyle.Fill, ForeColor = Color.LightGray };
    readonly ProgressBar nasUploadProgress = new()
    {
        Dock = DockStyle.Bottom, Height = 5, Minimum = 0,
        Maximum = 1000, Style = ProgressBarStyle.Continuous,
        Visible = false
    };
    NotifyIcon tray = null!;
    readonly System.Windows.Forms.Timer timer = new() { Interval = 30000 };
    // The Explorer copy window reflects local WinFsp staging. Only this
    // independent local-pipe status reports NAS-accepted uploads.
    readonly System.Windows.Forms.Timer webDavTransferTimer = new()
        { Interval = 1700 };
    private bool webDavStatusPolling;
    private bool webDavHadPending;
    private DateTime webDavCompletedAtUtc;
    private string webDavTransferFooter = "";
    readonly ExplorerRefreshCoalescer shellRefresh;
    readonly ShellArrivalBatch batchArrivals;
    bool connectBatchRunning;
    // Different drives MUST NOT block one another. Only two operations on
    // the SAME profile share a gate, protecting its rclone/WinFsp ownership
    // and preventing an accidental connect+disconnect race.
    readonly ConcurrentDictionary<Guid, SemaphoreSlim> profileGates = new();
    readonly HashSet<Guid> preparingConnections = [];
    readonly HashSet<Guid> deletingProfiles = [];
    SemaphoreSlim GateFor(NasProfile profile) =>
        profileGates.GetOrAdd(profile.Id, _ => new SemaphoreSlim(1, 1));
    readonly Dictionary<Guid, DateTime> retry = new();
    readonly SessionReconnectPause manualReconnectPause = new();
    readonly HashSet<Guid> integrityNoticesShown = new();
    readonly HashSet<Guid> cloudHealthRunning = [];
    readonly Dictionary<Guid, DateTime> nextCloudHealthCheck = [];
    readonly Dictionary<Guid, bool?> cloudShellShown = [];
    bool exiting;
    // While a safe exit is in progress, startup/timer/cloud callbacks must
    // not create another mount behind the exit workflow.
    bool shutdownRequested;
    bool deletingProfile;
    bool startupDependenciesReady;
    bool startupDependencyCheckRunning;
    bool cacheMaintenanceBusy;
    bool updateCleanupBusy;
    bool updateCleanupComplete;
    DateTime nextCacheSweepUtc;
    public MainForm(bool minimized, AppConfig? previewConfig = null)
    {
        cfg = previewConfig ?? AppStore.Load();
        // Old configuration files may retain this retired opt-in flag.
        cfg.GoogleStartupDiagnostics = false;
        GoogleStartupTrace.Enabled = false;
        shellRefresh = new ExplorerRefreshCoalescer(cause =>
        {
            if (shutdownRequested || exiting || IsDisposed || Disposing) return;
            if (batchArrivals?.Active == true)
            {
                AppStore.Log("EXPLORER_BURST_SUPPRESSED_DURING_CONNECT_BATCH");
                return;
            }
            ExplorerDriveIcon.NotifyComputerChanged();
            ExplorerLiveViewRefresh.RefreshThisPc("BATCHED_" + cause);
            AppStore.Log("EXPLORER_BURST_REFRESH_DONE " + cause);
        });
        batchArrivals = new ShellArrivalBatch((profiles, reason) =>
        {
            if (shutdownRequested || exiting || IsDisposed || Disposing) return;
            try
            {
                // Later UNC label registration must not trigger another
                // desktop-wide refresh after the grouped arrival.
                foreach (var p in profiles.Where(RemoteMount.UsesNetworkDriveMode))
                    ExplorerDriveIcon.ScheduleNetworkLabelRepair(p);
                int count = ExplorerDriveIcon.NotifyMountedBatch(profiles);
                foreach (var google in profiles.Where(p =>
                    p.Protocol == "GoogleDrive"))
                    GoogleQuotaPresentation.Schedule(google);
                AppStore.Log("SHELL_BATCH_PRESENTATION_FINISHED count=" +
                    count + " reason=" + reason);
            }
            catch (Exception ex)
            {
                AppStore.Log("SHELL_BATCH_PRESENTATION_WARNING " +
                    ex.GetType().Name);
                shellRefresh.Request("BATCH_PRESENTATION_FALLBACK");
            }
        });
        Disposed += (_, _) => shellRefresh.Dispose();
        Disposed += (_, _) => batchArrivals.Dispose();
        InitializeModernUi(minimized);
        webDavTransferTimer.Tick += async (_, _) =>
            await PollWebDavTransferStatusAsync();
        webDavTransferTimer.Start();
        FormClosed += (_, _) => { webDavTransferTimer.Stop(); webDavTransferTimer.Dispose(); };
        if (previewConfig is null)
        {
            try { AppStore.UpdateExistingAutoStartTarget(); }
            catch (Exception ex)
            {
                AppStore.Log("AUTOSTART_TARGET_MIGRATION_SKIPPED " + ex.GetType().Name);
            }
            AppStore.Log("NASMOUNT_START VERSION=1.3.126 PRODUCT=FileHubAIO SFTP_POLICY=PINNED_HOST_KEYS " +
                "EXE=" + Environment.ProcessPath);
        }
    }
    private async Task<bool> PrepareStartupEnginesAsync()
    {
        if (shutdownRequested) return false;
        if (startupDependenciesReady) return true;
        if (startupDependencyCheckRunning) return false;
        startupDependencyCheckRunning = true;
        var readinessTimer = Stopwatch.StartNew();
        try
        {
            AppStore.Log("STARTUP_DEPENDENCY_GATE_BEGIN local_only=true");
            status.Text = "로컬 rclone · WinFsp · 잠금 브리지 준비 확인 중...";
            var ready = await StartupReadinessPolicy.VerifyLocalAsync(
                cfg.Profiles, RemoteMount.HasWinFsp);
            if (!ready.Success)
            {
                AppStore.Log("STARTUP_DEPENDENCY_GATE_BLOCKED " + ready.Message);
                status.Text = "연결 대기 · " + ready.Message;
                return false;
            }
            startupDependenciesReady = true;
            AppStore.Log("STARTUP_DEPENDENCY_GATE_READY engines_verified_before_auto_connect=true");
            return true;
        }
        catch (Exception ex)
        {
            AppStore.Log("STARTUP_DEPENDENCY_GATE_EXCEPTION " + ex.GetType().Name +
                " " + ex.Message);
            return false;
        }
        finally
        {
            AppStore.Log("STARTUP_DEPENDENCY_GATE_TIMING elapsedMs=" +
                readinessTimer.ElapsedMilliseconds);
            startupDependencyCheckRunning = false;
        }
    }

    void ShowSettings() => ShowCompactSettings();
    Button Btn(string title, Action click, Color? color=null)
    {
        var b = new Button { Text=title, Width=Math.Max(120, TextRenderer.MeasureText(title, Font).Width + 28), Height=42, AutoEllipsis=true, FlatStyle=FlatStyle.Flat, BackColor=color??Color.FromArgb(51,65,83),
            ForeColor=Color.White, Margin=new Padding(0,0,9,0) };
        b.FlatAppearance.BorderSize=0; b.Padding = new Padding(6,0,6,0); b.Click+=(_,_)=>click(); return b;
    }
    NasProfile? Selected() => selectedId is Guid id ? cfg.Profiles.FirstOrDefault(p => p.Id == id) : null;
    void RefreshList() => RefreshModernCards();

    // The WinFsp root and the Explorer "This PC" namespace are separate.
    // A cloud can browse at O:\ yet be absent from Explorer's drive list.
    // Probe both out of the UI thread, then re-announce ONLY our verified
    // mounted profile. Never restart Explorer, kill WinFsp or remount here.
    private async Task CheckGoogleExplorerVisibilityAsync(
        NasProfile profile)
    {
        if (shutdownRequested || exiting || IsDisposed || Disposing ||
            profile.Protocol != "GoogleDrive" ||
            !RemoteMount.IsRunning(profile)) return;
        if (!cloudHealthRunning.Add(profile.Id)) return;
        try
        {
            if (nextCloudHealthCheck.TryGetValue(profile.Id,
                    out var next) && DateTime.UtcNow < next) return;
            nextCloudHealthCheck[profile.Id] = DateTime.UtcNow.AddSeconds(55);
            var health = await RemoteMount.CheckMountedHealthAsync(profile);
            if (shutdownRequested || IsDisposed || Disposing) return;
            if (!health.Success)
            {
                AppStore.Log("GOOGLE_LIVE_MOUNT_UNHEALTHY id=" + profile.Id +
                    " detail=" + health.Message.Replace('\n', ' '));
                cloudShellShown[profile.Id] = null;
                RefreshList();
                return;
            }
            bool? shown = await ExplorerDriveIcon.ComputerFolderShowsAsync(profile.Letter);
            if (shutdownRequested || IsDisposed || Disposing ||
                !RemoteMount.IsMounted(profile)) return;
            cloudShellShown[profile.Id] = shown;
            AppStore.Log("GOOGLE_EXPLORER_SHELL_VISIBILITY id=" + profile.Id +
                " letter=" + profile.Letter + " shown=" +
                (shown?.ToString() ?? "unknown"));
            if (shown == false)
            {
                ExplorerDriveIcon.NotifyMounted(profile, deferGlobalNotify: true);
                shellRefresh.Request("GOOGLE_VERIFIED_EXPLORER_REANNOUNCE_" +
                    profile.Id.ToString("N")[..8]);
                await Task.Delay(1250);
                bool? after = await ExplorerDriveIcon.ComputerFolderShowsAsync(
                    profile.Letter);
                if (!IsDisposed && !Disposing)
                    cloudShellShown[profile.Id] = after;
                AppStore.Log("GOOGLE_EXPLORER_SHELL_REPAIR_RESULT id=" +
                    profile.Id + " shown=" + (after?.ToString() ?? "unknown"));
            }
            if (!IsDisposed && !Disposing) RefreshList();
        }
        catch (Exception ex)
        {
            AppStore.Log("GOOGLE_EXPLORER_RECONCILE_FAILED id=" + profile.Id +
                " reason=" + ex.GetType().Name);
        }
        finally { cloudHealthRunning.Remove(profile.Id); }
    }

    private async Task CheckAllGoogleExplorerVisibilityAsync()
    {
        foreach (var p in cfg.Profiles.ToArray().Where(p =>
            p.Protocol == "GoogleDrive" && RemoteMount.IsRunning(p)))
            await CheckGoogleExplorerVisibilityAsync(p);
    }
    void Add()
    {
        var free = DriveLetterAllocator.HighestFree(cfg.Profiles);
        if (free is null)
        {
            MessageBox.Show("사용 가능한 드라이브 문자가 없습니다. 연결을 추가할 수 없습니다.", "FileHub AIO");
            return;
        }
        var d = new ModernProfileDialog(new NasProfile
        {
            DisplayName = "새 NAS", Protocol = "WebDAV", Port = 5006,
            UseLockBridge = true, AllowUnprotectedWrites = false, Letter = free
        }, false);
        OpenSlidingView(d, "새 연결", _ => ApplyAddedProfile(d));
    }
    private void ApplyAddedProfile(ModernProfileDialog d)
    {
        if(cfg.Profiles.Any(x=>x.Letter.Equals(d.Profile.Letter,StringComparison.OrdinalIgnoreCase))) { MessageBox.Show("드라이브 문자가 중복됩니다.");return; }
        if(!SaveSecret(d)) return;
        cfg.Profiles.Add(d.Profile); AppStore.Save(cfg); RefreshList();
        // Use the same connection/WinFsp preflight for new connections as
        // for existing profiles. Direct Perform previously skipped it.
        _=ConnectOne(d.Profile,true,d.Password.Length>0 ? d.Password : null);
    }
    void Edit()
    {
        var old=Selected(); if(old is null)return;
        var d=new ModernProfileDialog(old.Clone(),true);
        OpenSlidingView(d, "연결 설정 · " + old.DisplayName, _ => ApplyEditedProfile(old,d));
    }
    private void ApplyEditedProfile(NasProfile old, ModernProfileDialog d)
    {
        if(cfg.Profiles.Any(x=>x.Id!=old.Id && x.Letter.Equals(d.Profile.Letter,StringComparison.OrdinalIgnoreCase))) { MessageBox.Show("드라이브 문자가 중복됩니다.");return; }
        if((IsConnected(old) || (old.Protocol!="SMB" && RemoteMount.IsRunning(old))) &&
            (old.RemoteDisplay!=d.Profile.RemoteDisplay || old.Letter!=d.Profile.Letter ||
            old.Protocol!=d.Profile.Protocol || old.AllowUnprotectedWrites!=d.Profile.AllowUnprotectedWrites ||
            old.UseLockBridge!=d.Profile.UseLockBridge ||
            old.UseNativeWebDavLocks!=d.Profile.UseNativeWebDavLocks ||
            old.FastReadOnly!=d.Profile.FastReadOnly ||
            old.OfficeReadTuning!=d.Profile.OfficeReadTuning ||
            old.UseFixedDriveMode!=d.Profile.UseFixedDriveMode ||
            old.ExplicitTls!=d.Profile.ExplicitTls || old.FtpImplicitTls!=d.Profile.FtpImplicitTls ||
            old.FtpPreferPasv!=d.Profile.FtpPreferPasv || old.FtpDisableUtf8!=d.Profile.FtpDisableUtf8 ||
            old.FtpTls12Only!=d.Profile.FtpTls12Only ||
            old.FtpCharset!=d.Profile.FtpCharset || old.SftpCharset!=d.Profile.SftpCharset ||
            old.IsAnonymous!=d.Profile.IsAnonymous ||
            old.DisplayName!=d.Profile.DisplayName ||
            old.SftpHostFingerprint!=d.Profile.SftpHostFingerprint))
        { MessageBox.Show("연결 해제 후 연결 방식/주소/드라이브 문자/쓰기 모드를 수정하세요.");return; }
        if(!SaveSecret(d))return;
        cfg.Profiles[cfg.Profiles.FindIndex(x=>x.Id==old.Id)]=d.Profile;AppStore.Save(cfg);RefreshList();
    }
    bool SaveSecret(ModernProfileDialog d)
    {
        try
        {
            if(d.Profile.RememberPassword && d.PasswordChanged)
                WindowsCredentials.Store(d.Profile.Id,d.Profile.Username,d.Password);
            if(!d.Profile.RememberPassword)WindowsCredentials.Delete(d.Profile.Id);
            return true;
        }
        catch(Exception e) { MessageBox.Show("자격 증명 오류: "+e.Message);return false; }
    }
    async void Delete()
    {
        if (deletingProfile) return;
        var p = Selected();
        if (p is null) return;
        deletingProfile = true;
        deletingProfiles.Add(p.Id);
        try
        {
            if (preparingConnections.Contains(p.Id))
            {
                MessageBox.Show(this,
                    "이 연결의 자동 연결 또는 인증 확인이 진행 중입니다.\n" +
                    "완료된 뒤 연결 삭제를 다시 눌러주세요.",
                    "연결 확인 중", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                return;
            }
            if (ProfileDeletionPolicy.IsBlocked(
                    IsConnected(p), RemoteMount.HasManagedRecord(p),
                    p.Protocol != "SMB" &&
                    NetworkDriveResidueCleaner.HasActiveOwnedNetworkMapping(p)))
            {
                MessageBox.Show(this,
                    "이 연결이 실행 중이거나 이 프로필 소유의 활성 드라이브가 있습니다.\n" +
                    "열린 문서와 업로드를 완료한 후 안전하게 연결 해제하세요.",
                    "연결 해제 필요", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            if (p.Protocol != "SMB")
            {
                // An earlier GUI may have exited while its worker still
                // owns dirty VFS files. Probe THIS profile, not every drive.
                if (p.UseLockBridge &&
                    (await BridgeOwnership.ProbeAsync(p.Id)).IsOccupied)
                {
                    MessageBox.Show(this,
                        "이 연결의 WinFsp 잠금 브리지가 아직 실행 중입니다.\n" +
                        "이전 FileHub AIO에서 안전하게 연결 해제한 뒤 삭제하세요.",
                        "연결 해제 필요");
                    return;
                }
                bool orphan = await Task.Run(() =>
                    NetworkDriveResidueCleaner.OldBackendMayStillOwn(p));
                if (orphan)
                {
                    MessageBox.Show(this,
                        "이 연결의 이전 백엔드가 실행 중이거나 " +
                        "소유권을 안전하게 확인할 수 없습니다.\n" +
                        "업로드 중인 파일을 보호하기 위해 삭제하지 않았습니다.",
                        "안전 확인 필요");
                    return;
                }
            }
            if (MessageBox.Show(this,
                    "연결 설정 '" + p.DisplayName + "'을 삭제할까요?\n" +
                    "원격 NAS/클라우드 파일과 다른 연결은 유지됩니다.",
                    "연결 삭제", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Question) != DialogResult.Yes) return;
            if (p.Protocol != "SMB")
            {
                if (NetworkDriveResidueCleaner.IsDisconnectedOwnedGhost(p))
                {
                    // Only exact profile-owned disconnected mapping.
                    var cleaned = NetworkDriveResidueCleaner.AfterSafeStop(p);
                    if (!cleaned.Success || RemoteMount.DriveLetterExists(p.Letter))
                    {
                        MessageBox.Show(this,
                            "이전 네트워크 위치를 안전하게 정리하지 못해 " +
                            "연결 설정을 보존했습니다.\n" + cleaned.Message,
                            "잔여 네트워크 위치", MessageBoxButtons.OK,
                            MessageBoxIcon.Warning);
                        return;
                    }
                }
                else if (!RemoteMount.DriveLetterExists(p.Letter))
                    NetworkDriveResidueCleaner.CleanupDisconnectedStartupHistory(p);
                // An occupied letter belonging to a DIFFERENT connection is
                // irrelevant to removal of this never-connected OneDrive.
                // Never disconnect or modify that other connection.
            }
            if (p.Protocol == "GoogleDrive")
            {
                if (GoogleDirectCredentialStore.HasNative(p))
                {
                    var choice = MessageBox.Show(this,
                        "Google 계정의 FileHub 접근 권한도 철회할까요?\n" +
                        "예: Google 권한 철회 및 이 PC의 인증 삭제\n" +
                        "아니요: 이 PC의 인증만 삭제\n" +
                        "권한 철회는 같은 Google 프로젝트를 사용하는 다른 기기에도 영향을 줄 수 있습니다.",
                        "Google 계정 연동 해제", MessageBoxButtons.YesNoCancel,
                        MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
                    if (choice == DialogResult.Cancel) return;
                    if (choice == DialogResult.Yes)
                        await GoogleDirectCredentialStore.RevokePublisherGrantAsync(p);
                }
                GoogleDirectCredentialStore.ForgetLocal(p);
                AppStore.ForgetLegacyOAuthConfig(GoogleDriveAuth.ConfigPath(p));
            }
            if (p.Protocol == "OneDrive")
            {
                AppStore.ForgetLegacyOAuthConfig(OneDriveAuth.ConfigPath(p));
            }
            WindowsCredentials.Delete(p.Id);
            int index = cfg.Profiles.FindIndex(x => x.Id == p.Id);
            if (index < 0) return;
            cfg.Profiles.RemoveAt(index);
            try { AppStore.Save(cfg); }
            catch
            {
                cfg.Profiles.Insert(index, p);
                throw;
            }
            manualReconnectPause.RemoveDeletedProfile(p.Id);
            retry.Remove(p.Id);
            AppStore.Log("PROFILE_DELETE_DISCONNECTED_SAFE id=" + p.Id +
                " protocol=" + p.Protocol);
            RefreshList();
        }
        catch (Exception ex)
        {
            AppStore.Log("PROFILE_DELETE_ERROR id=" + p.Id +
                " type=" + ex.GetType().Name);
            MessageBox.Show(this,
                "연결 설정을 삭제하지 못했습니다.\n" + ex.Message,
                "삭제 오류", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally
        {
            deletingProfiles.Remove(p.Id);
            deletingProfile = false;
        }
    }
    async Task SelectedOperation(bool connect)
    {
        var p=Selected();if(p is null)return;
        if(connect)await ConnectOne(p,true);
        else {
            // A connection that NEVER passed the first root browse cannot
            // contain a verified FileHub session. Do not show the Office
            // upload warning or wait on a hung per-profile semaphore.
            if (p.Protocol != "SMB" &&
                (RemoteMount.FailedBeforeFirstMount(p) ||
                 (preparingConnections.Contains(p.Id) &&
                  !RemoteMount.IsMounted(p))))
            {
                manualReconnectPause.ManualDisconnectRequested(p.Id);
                RemoteMount.RequestEmergency(p.Id);
                if (RemoteMount.FailedBeforeFirstMount(p))
                {
                    var stopped = await Task.Run(() =>
                        RemoteMount.StopFailedBeforeFirstMountAsync(p))
                        .WaitAsync(TimeSpan.FromSeconds(8));
                    AppStore.Log("MANUAL_FAILED_CONNECT_FORCE_STOP id=" +
                        p.Id + " ok=" + stopped.Success);
                }
                RefreshList();
                return;
            }
            if(p.Protocol!="SMB" && MessageBox.Show(
                "이 드라이브의 연결을 강제로 해제합니다.\n\n" +
                "열린 프로그램, Excel 문서, 파일 잠금이 있어도 연결을 끊습니다.\n" +
                "저장하지 않은 변경 내용 및 업로드 대기 파일이 손실될 수 있습니다.\n" +
                "복구 캐시와 기록은 삭제하지 않습니다.\n\n계속하시겠습니까?",
                "연결 해제 확인",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;
            // Store the user's intent BEFORE awaiting the unmount. Timer
            // ticks, startup auto-connect and cloud retry callbacks must not
            // undo a manual disconnect in the current program session.
            manualReconnectPause.ManualDisconnectRequested(p.Id);
            AppStore.Log("MANUAL_DISCONNECT_AUTO_RECONNECT_SUSPENDED " + p.Id);
            await Perform(p,false,null,true,forceDisconnect:p.Protocol!="SMB");
        }
    }
    // This is the same entry used by Connect and the packaged UI policy
    // test. Missing publisher JSON must still allow the disclosed OpenList
    // browser flow; startup auto-connect must never create a login window.
    internal static GoogleDriveLoginDialog? GoogleBrowserLoginFor(
        NasProfile profile, bool explicitRequest) =>
        explicitRequest && RemoteMount.GoogleRouteFor(profile) ==
            RemoteMount.GoogleEngineRoute.NativeGoogleNeedsBrowserGrant
                ? new GoogleDriveLoginDialog(profile) : null;

    async Task ConnectOne(NasProfile p,bool explicitRequest,string? suppliedPassword=null)
    {
        if (shutdownRequested || cacheMaintenanceBusy || deletingProfiles.Contains(p.Id)) return;
        if (explicitRequest)
            RemoteMount.ClearEmergency(p.Id);
        else if (RemoteMount.EmergencyPending(p.Id))
            return;
        // Timer ticks can arrive while authentication or backend readiness
        // is still in progress, BEFORE Perform obtains its per-drive gate.
        // Suppress duplicate preflight/credential prompts for that profile.
        // This method runs on the WinForms UI synchronization context.
        if (!preparingConnections.Add(p.Id)) return;
        try
        {
        if (!explicitRequest && manualReconnectPause.IsSuspended(p.Id)) return;
        if (explicitRequest && p.Protocol == "WebDAV" && !p.WebDavUseHttp)
        {
            try
            {
                var observed = await WebDavTlsPolicy.ObserveAliasAsync(p);
                if (observed is not null)
                {
                    string pin = WebDavTlsPolicy.AliasPin(p, observed);
                    if (!string.Equals(p.WebDavAliasPin, pin, StringComparison.OrdinalIgnoreCase))
                    {
                        if (MessageBox.Show(this,
                            "WebDAV 서버 인증서 이름이 입력한 주소와 일치하지 않습니다.\n\n" +
                            "NAS 관리자에게 별도로 확인한 SHA-256 인증서 지문과 아래 값이 일치할 때만 승인하세요.\n" + observed +
                            "\n\n이 주소의 이 인증서만 허용합니다.",
                            "WebDAV 인증서 확인", MessageBoxButtons.YesNo, MessageBoxIcon.Warning,
                            MessageBoxDefaultButton.Button2) != DialogResult.Yes) return;
                        string previous = p.WebDavAliasPin;
                        p.WebDavAliasPin = pin;
                        try { AppStore.Save(cfg); }
                        catch { p.WebDavAliasPin = previous; throw; }
                    }
                }
            }
            catch (Exception ex)
            {
                AppStore.Log("WEBDAV_TLS_PREFLIGHT_FAILED " + ex.GetType().Name);
                MessageBox.Show(this, "WebDAV 서버의 TLS 인증서를 확인하지 못했습니다. 서버 주소와 인증서를 확인하세요.",
                    "WebDAV 보안 연결 실패", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
        }
        if (p.Protocol == "SFTP" && SftpHostKeyTrust.RepairPreviousRsaPin(p))
        {
            // v1.3.43/44 could save a valid RSA key with the SSH signature
            // name rsa-sha2-512 instead of the wire key type ssh-rsa.
            // Only rewrite its algorithm when the previously approved
            // SHA256 fingerprint matches the SAME public key bytes.
            AppStore.Save(cfg);
            AppStore.Log("SFTP_RSA_SIGNATURE_TO_KEY_TYPE_MIGRATED profile=" + p.Id +
                " fingerprint=" + p.SftpHostFingerprint);
        }
        if (p.Protocol == "SFTP" && !SftpHostKeyTrust.HasTrustedKey(p))
        {
            if (!explicitRequest)
            {
                // Never show an unsolicited trust prompt at Windows startup.
                // Let the user inspect the server key on explicit Connect.
                retry[p.Id] = DateTime.UtcNow.AddMinutes(5);
                status.Text = p.DisplayName +
                    " · SFTP SSH 서버 키 최초 승인이 필요합니다 (연결 클릭)";
                return;
            }
            try
            {
                status.Text = p.DisplayName + " · SFTP SSH 호스트 키 확인 중...";
                var key = await SftpHostKeyTrust.ObserveAsync(p);
                if (!string.IsNullOrWhiteSpace(p.SftpHostFingerprint) &&
                    !SftpHostKeyTrust.MatchesFingerprint(
                        p.SftpHostFingerprint, key.Sha256Fingerprint))
                {
                    MessageBox.Show(this,
                        "저장된 SSH 서버 지문과 현재 서버 지문이 일치하지 않습니다.\n\n" +
                        "저장된 지문: " + p.SftpHostFingerprint + "\n" +
                        "현재 서버 지문: " + key.Sha256Fingerprint + "\n\n" +
                        "서버 키 변경 또는 다른 서버에 접속했을 가능성이 있습니다.\n" +
                        "NAS 관리자에게 확인한 뒤 연결 설정에서 SSH 키 지문을" +
                        " 직접 갱신하세요. 자동으로 신뢰하지 않습니다.",
                        "SFTP 서버 키 불일치 · 연결 차단",
                        MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return;
                }
                if (MessageBox.Show(this, SftpHostKeyTrust.TrustPrompt(key),
                        "SFTP 최초 연결 · 서버 SSH 키 승인",
                        MessageBoxButtons.YesNo, MessageBoxIcon.Question,
                        MessageBoxDefaultButton.Button2) != DialogResult.Yes)
                    return;
                // The user's explicit approval (or a matching expected key
                // fingerprint) is mandatory before storing any public key.
                SftpHostKeyTrust.Approve(p, key);
                AppStore.Save(cfg);
                AppStore.Log("SFTP_SERVER_PUBLIC_KEY_APPROVED profile=" + p.Id +
                    " host=" + p.Host + ":" + SftpHostKeyTrust.EffectivePort(p) +
                    " algorithm=" + key.Algorithm +
                    " fingerprint=" + key.Sha256Fingerprint);
            }
            catch (Exception ex)
            {
                AppStore.Log("SFTP_HOST_KEY_PROBE_FAILED " + p.Id + " " + ex.Message);
                MessageBox.Show(this,
                    "SFTP 서버 호스트 키 확인에 실패했습니다.\n\n" +
                    ex.Message + "\n\n" +
                    "레이드라이브와 동일한 서버 주소·SSH 포트로 설정했는지 확인하세요.\n" +
                    "SSH 지문을 검증할 수 없다면 접속하지 않습니다.",
                    "SFTP 연결 준비 실패", MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
                return;
            }
        }
        if(p.Protocol!="SMB" && !RemoteMount.HasWinFsp)
        {
            if(explicitRequest)
            {
                if(MessageBox.Show(
                    "드라이브 연결에 필요한 WinFsp 드라이버가 없습니다.\n" +
                    "배포본에 포함된 공식 서명 MSI를 설치할까요?\n\n" +
                    "설치 과정에서 Windows 관리자 권한 승인이 필요합니다.",
                    "필수 드라이버 설치",MessageBoxButtons.YesNo,MessageBoxIcon.Question)==DialogResult.Yes)
                    await InstallWinFsp();
                if(!RemoteMount.HasWinFsp) return;
            }
            else
            {
                retry[p.Id]=DateTime.UtcNow.AddMinutes(5);
                return;
            }
        }
        // Native Google API is the ONLY Google mount engine. A legacy
        // shared-rclone credential is not authorization for the new client.
        // Never auto-launch Chrome during Windows startup.
        if (RemoteMount.GoogleRouteFor(p) ==
            RemoteMount.GoogleEngineRoute.NativeGoogleNeedsBrowserGrant)
        {
            if (!explicitRequest)
            {
                retry[p.Id] = DateTime.UtcNow.AddMinutes(5);
                return;
            }
            status.Text = "Google 직접 API 연결을 위한 Chrome 계정 승인이 필요합니다.";
            using var directLogin = GoogleBrowserLoginFor(p, explicitRequest);
            if (directLogin is null || directLogin.ShowDialog(this) != DialogResult.OK) return;
            if (RemoteMount.GoogleRouteFor(p) !=
                RemoteMount.GoogleEngineRoute.NativeGoogleReady)
            {
                MessageBox.Show(this,
                    "Google 직접 엔진 인증이 완료되지 않았습니다. " +
                    "기존 rclone 인증으로 자동 연결하지 않습니다.",
                    "Google 직접 API", MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
                return;
            }
        }
        if (p.Protocol == "OneDrive" && !OneDriveAuth.IsAuthorized(p))
        {
            if (!explicitRequest)
            {
                retry[p.Id] = DateTime.UtcNow.AddMinutes(5);
                return; // No unsolicited Microsoft sign-in window at startup.
            }
            using var login = new OneDriveLoginDialog(p);
            if (login.ShowDialog(this) != DialogResult.OK) return;
        }
        string? password=suppliedPassword ?? (p.RememberPassword?WindowsCredentials.Read(p.Id):null);
        if(!explicitRequest && (!string.IsNullOrWhiteSpace(p.Username) || p.Protocol is "TeraBox" or "MyBox") && password is null)
        {
            retry[p.Id]=DateTime.UtcNow.AddMinutes(5);
            return;
        }
        if(password is null && !p.RememberPassword && (!string.IsNullOrWhiteSpace(p.Username) || p.Protocol is "TeraBox" or "MyBox") && explicitRequest)
        {
            using var dialog=new PasswordDialog(p.DisplayName);
            if(dialog.ShowDialog(this)!=DialogResult.OK)return;
            password=dialog.Password;
        }
        if (!shutdownRequested)
            await Perform(p,true,password,explicitRequest);
        }
        finally { preparingConnections.Remove(p.Id); }
    }
    async Task ReauthorizeGoogleDrive()
    {
        var p = Selected();
        if (p is null || p.Protocol != "GoogleDrive") return;
        if (RemoteMount.IsRunning(p))
        {
            MessageBox.Show(this, "Google Drive 연결을 안전하게 해제한 뒤 재인증하세요. 업로드가 끝나기 전에 강제로 종료하면 안 됩니다.",
                "Google Drive 재인증", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        using var login = new GoogleDriveLoginDialog(p);
        if (login.ShowDialog(this) == DialogResult.OK)
            await ConnectOne(p, true);
    }

    async Task ReauthorizeOneDrive()
    {
        var p = Selected();
        if (p is null || p.Protocol != "OneDrive") return;
        if (RemoteMount.IsRunning(p))
        {
            MessageBox.Show(this,
                "OneDrive를 안전하게 연결 해제한 뒤 재인증하세요. " +
                "전송 중인 파일은 강제로 종료하면 안 됩니다.",
                "OneDrive 재인증", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        using var login = new OneDriveLoginDialog(p);
        if (login.ShowDialog(this) == DialogResult.OK)
            await ConnectOne(p, true);
    }

    async Task CheckUploadStatus()
    {
        var p=Selected();
        if(p is null || p.Protocol=="SMB")
        {
            MessageBox.Show("SMB가 아닌 연결된 NAS를 선택하세요.");
            return;
        }
        if (p.Protocol == "GoogleDrive" &&
            GoogleDirectRecovery.PendingCount(p.Id) is int pending && pending > 0)
        {
            var connectionState = await RemoteMount.GetTransferStatus(p);
            if (connectionState.Message.Contains("열린 Google 파일",
                StringComparison.Ordinal))
            {
                MessageBox.Show(this,
                    "Google 파일을 사용 중입니다. 파일을 닫은 뒤 " +
                    "복구 상태를 다시 확인하세요.",
                    "Google 저장 진행 중", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
                return;
            }
            if (MessageBox.Show(this,
                "Google 직접 연결의 미완료 로컬 복구 기록 " + pending +
                "개가 있습니다.\n\n원본 기록은 그대로 두고 별도 폴더에 " +
                "복구 파일 사본을 내보낼까요?\n이 작업은 Google 파일을 수정하지 않습니다.",
                "Google 저장 복구", MessageBoxButtons.YesNo,
                MessageBoxIcon.Warning) != DialogResult.Yes) return;
            using var picker = new FolderBrowserDialog
            {
                Description = "Google 복구 파일 사본을 저장할 폴더 선택",
                UseDescriptionForTitle = true
            };
            if (picker.ShowDialog(this) != DialogResult.OK) return;
            try
            {
                int count = GoogleDirectRecovery.Export(p.Id, picker.SelectedPath);
                MessageBox.Show(this,
                    "복구 파일 " + count + "개를 내보냈습니다.\n" +
                    "원본 복구 기록은 삭제하지 않았습니다.",
                    "Google 복구 내보내기", MessageBoxButtons.OK,
                    MessageBoxIcon.Information);
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException
                or JsonException or ArgumentException)
            {
                AppStore.Log("GOOGLE_RECOVERY_EXPORT_FAILED type=" +
                    ex.GetType().Name);
                MessageBox.Show(this,
                    "복구 파일 내보내기에 실패했습니다. 원본 기록은 보존했습니다.\n" +
                    "다른 빈 폴더를 선택해 다시 시도하세요.",
                    "Google 복구 내보내기", MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
            }
            return;
        }
        var result=await RemoteMount.GetTransferStatus(p);
        AppStore.Log("UPLOAD_STATUS "+p.Id+" "+result.Message);
        MessageBox.Show(result.Message,
            result.Success?"업로드 대기 없음":"업로드 미완료 또는 오류",
            MessageBoxButtons.OK,result.Success?MessageBoxIcon.Information:MessageBoxIcon.Warning);
    }

    // No network polling: a lightweight local bridge-pipe status every
    // 1.7 seconds is needed to distinguish staged bytes from server ACK.
    private string NormalTransferFooter() => webDavTransferFooter;

    private async Task PollWebDavTransferStatusAsync()
    {
        if (webDavStatusPolling || shutdownRequested || exiting ||
            Disposing || IsDisposed) return;
        webDavStatusPolling = true;
        try
        {
            var mounted = cfg.Profiles.Where(p =>
                p.Protocol == "WebDAV" && RemoteMount.IsRunning(p)).ToArray();
            int queued = 0, uploading = 0, failed = 0;
            long accepted = 0, confirmed = 0, canceled = 0;
            bool unknown = mounted.Length == 0 && webDavHadPending;
            bool recoveryNeedsReview = false;
            foreach (var profile in mounted)
            {
                try
                {
                    var state = await RemoteMount.GetDirectWebDavStatusForUi(profile);
                    if (state is null) { unknown = true; continue; }
                    queued += state.Queued;
                    uploading += state.Uploading;
                    failed += state.Errors;
                    accepted += state.Accepted;
                    confirmed += state.Confirmed;
                    canceled += state.Canceled;
                    recoveryNeedsReview |= state.BlockedReason?.Contains(
                        "복구 기록", StringComparison.Ordinal) == true;
                }
                catch (Exception ex) when (ex is IOException or
                    OperationCanceledException or TimeoutException)
                {
                    unknown = true;
                }
            }
            if (IsDisposed || Disposing || shutdownRequested) return;
            bool pending = queued + uploading > 0;
            long eligible = Math.Max(0, accepted - canceled);
            if (pending && eligible > 0)
            {
                nasUploadProgress.Value = (int)Math.Clamp(
                    confirmed * 1000 / eligible, 0, 999);
                nasUploadProgress.Visible = true;
            }
            else if (!pending && !unknown && webDavHadPending &&
                failed == 0 && !recoveryNeedsReview)
            {
                nasUploadProgress.Value = 1000;
                nasUploadProgress.Visible = true;
            }
            else if (!pending && !webDavHadPending &&
                DateTime.UtcNow - webDavCompletedAtUtc > TimeSpan.FromSeconds(12))
                nasUploadProgress.Visible = false;
            if (pending)
            {
                if (!webDavHadPending && queued + uploading >= 3)
                    tray.ShowBalloonTip(3000, "FileHub AIO NAS 업로드 중",
                        "Windows 탐색기 복사 완료 후에도 NAS 전송이 진행될 수 있습니다. " +
                        "NAS 전송 완료 알림 전에는 다른 앱에서 삭제하지 마세요.",
                        ToolTipIcon.Info);
                webDavHadPending = true;
                string progress = eligible > 0
                    ? $" · 서버 확인 {confirmed}/{eligible}" : "";
                webDavTransferFooter = "NAS 실제 업로드 중 · 대기 " + queued +
                    " / 전송 " + uploading + progress +
                    (failed > 0 ? " / 실패 " + failed : "") +
                    " · 탐색기 완료는 로컬 캐시 기준";
            }
            else if (failed > 0 || recoveryNeedsReview)
            {
                webDavTransferFooter = failed > 0
                    ? "NAS 업로드 실패 " + failed + "개 · 복구 확인 필요"
                    : "NAS 복구 기록 확인 필요 · 서버 완료로 표시하지 않음";
                webDavHadPending = false;
            }
            else if (unknown && webDavHadPending)
            {
                // A broken status pipe must never be presented as success.
                webDavTransferFooter = "NAS 업로드 상태 확인 중 · 완료 미확인";
            }
            else if (!unknown && webDavHadPending)
            {
                webDavHadPending = false;
                webDavCompletedAtUtc = DateTime.UtcNow;
                webDavTransferFooter = "NAS 전송 완료 · 서버 응답 확인됨";
                tray.ShowBalloonTip(4500, "FileHub AIO NAS 업로드 완료",
                    "대기 중이던 파일의 NAS 전송이 모두 완료됐습니다.",
                    ToolTipIcon.Info);
            }
            else if (DateTime.UtcNow - webDavCompletedAtUtc >
                TimeSpan.FromSeconds(12))
                webDavTransferFooter = "";

            string title = "FileHub AIO v" + PcUpdateService.DisplayVersion;
            tray.Text = unknown && webDavHadPending
                ? "FileHub NAS 업로드 상태 미확인"
                : pending
                ? ("FileHub NAS 전송 중 " + (queued + uploading) + "개")
                : failed > 0 ? ("FileHub NAS 전송 실패 " + failed + "개")
                : recoveryNeedsReview ? "FileHub NAS 복구 확인 필요" : title;
            bool criticalOperation =
                status.Text.Contains("업데이트 파일 다운로드",
                    StringComparison.Ordinal) ||
                status.Text.StartsWith("PC 업데이트",
                    StringComparison.Ordinal);
            if (!criticalOperation &&
                (pending || failed > 0 || recoveryNeedsReview ||
                status.Text.Length == 0 ||
                status.Text.StartsWith("NAS ", StringComparison.Ordinal) ||
                status.Text.StartsWith("● WinFsp",
                    StringComparison.Ordinal)))
                status.Text = webDavTransferFooter;
        }
        finally { webDavStatusPolling = false; }
    }

    private void NotifyCriticalIntegrityAlerts()
    {
        // In-memory integrity signals are different from routine recovery
        // journals. Surface a genuine critical alert at most once; do not
        // turn upload/lease status into a permanent dashboard warning.
        foreach (var p in cfg.Profiles)
        {
            if (RemoteMount.GetIntegrityAlert(p.Id) is not { } critical ||
                !integrityNoticesShown.Add(p.Id)) continue;
            tray.ShowBalloonTip(15000, "FileHub AIO: 파일 저장 무결성 경고",
                critical, ToolTipIcon.Error);
        }
    }

    internal void VerifyTransferFooterForUiTest()
    {
        string savedText = status.Text;
        try
        {
            if (NormalTransferFooter() != "" || timer.Interval != 30000 ||
                webDavTransferTimer.Interval != 1700 ||
                nasUploadProgress.Maximum != 1000)
                throw new InvalidOperationException("Normal footer is not empty");
            if (typeof(MainForm).GetField("uploadStatusTimer",
                System.Reflection.BindingFlags.NonPublic |
                System.Reflection.BindingFlags.Instance) is not null)
                throw new InvalidOperationException("Obsolete auto-upload polling still enabled");
            status.Text = "PC 업데이트 파일 다운로드 중...";
            NotifyCriticalIntegrityAlerts();
            if (status.Text != "PC 업데이트 파일 다운로드 중...")
                throw new InvalidOperationException(
                    "Integrity inspection overwrote unrelated operation");
            Console.WriteLine("WEBDAV_REMOTE_ACK_PROGRESS_LOCAL_PIPE_ONLY_POLICY_PASS");
        }
        finally
        {
            status.Text = savedText;
        }
    }

    async Task InstallWinFsp()
    {
        var installed = WinFspUpgradePolicy.InstalledVersion();
        // Never downgrade the shared kernel driver. Upgrades from the
        // previous beta to 2.2 stable use the existing explicit consent
        // and disconnected-mount checks below.
        if(RemoteMount.HasWinFsp && !WinFspUpgradePolicy.UpgradeAvailable(installed))
        {
            MessageBox.Show("WinFsp " + installed + "이(가) 설치되어 있으며 " +
                "FileHub 포함 안정판(2026)과 같거나 더 최신입니다.\n" +
                "다른 앱의 드라이버를 임의로 교체하지 않았습니다.", "FileHub AIO");
            return;
        }
        if(RemoteMount.HasWinFsp)
        {
            if(cfg.Profiles.Any(p => p.Protocol != "SMB" && RemoteMount.IsRunning(p)))
            {
                MessageBox.Show(this,
                    "연결 중인 NAS 드라이브가 있어 WinFsp 드라이버를 교체할 수 없습니다.\n\n" +
                    "열린 Excel/Word 문서를 저장하고 모든 FileHub 드라이브를 안전하게 " +
                    "해제한 뒤 다시 시도하세요. 다른 WinFsp 프로그램도 종료하세요.",
                    "WinFsp 업그레이드 보류", MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return;
            }
            if(MessageBox.Show(this,
                "기존 WinFsp: " + (installed?.ToString() ?? "버전 확인 불가") + "\n" +
                "포함된 WinFsp: " + WinFspUpgradePolicy.PackageVersion + " (2026 정식판)\n\n" +
                "RaiDrive 등 다른 WinFsp 사용 프로그램도 설치에 영향을 받습니다.\n" +
                "진행 전 다른 WinFsp 프로그램의 마운트도 해제하세요.\n" +
                "드라이버 업데이트 후 재부팅이 필요할 수 있습니다.\n\n" +
                "공식 서명 MSI를 설치할까요?",
                "WinFsp 드라이버 업그레이드", MessageBoxButtons.YesNo,
                MessageBoxIcon.Question) != DialogResult.Yes) return;
        }
        string installer=RemoteMount.InstallerPath;
        if(!File.Exists(installer))
        {
            MessageBox.Show("드라이버 설치 파일을 찾을 수 없습니다.\n"+
                installer+"\n\n배포 ZIP을 완전히 풀고 실행하세요.", "WinFsp 설치");
            return;
        }
        try
        {
            const string expected=WinFspUpgradePolicy.InstallerSha256;
            using var stream=File.OpenRead(installer);
            string actual=Convert.ToHexString(await System.Security.Cryptography.SHA256.HashDataAsync(stream));
            if(!string.Equals(actual,expected,StringComparison.OrdinalIgnoreCase))
                throw new InvalidOperationException("WinFsp 설치 파일 해시가 공식 배포본과 일치하지 않습니다. 설치를 중단합니다.");
            status.Text="WinFsp 설치 프로그램 실행 중 (관리자 승인 필요)";
            using var process=Process.Start(new ProcessStartInfo
            {
                FileName="msiexec.exe",
                Arguments="/i \""+installer+"\" /passive /norestart",
                UseShellExecute=true,
                Verb="runas"
            }) ?? throw new InvalidOperationException("드라이버 설치 프로그램을 실행하지 못했습니다.");
            await process.WaitForExitAsync();
            if(process.ExitCode is 3010 or 1641)
            {
                MessageBox.Show("WinFsp 설치가 완료됐지만 드라이버 적용을 위해 " +
                    "Windows 재시작이 필요합니다. 열려 있는 작업을 저장하고 " +
                    "수동으로 재부팅하세요.", "WinFsp 재부팅 필요");
            }
            else if(process.ExitCode != 0)
            {
                MessageBox.Show("WinFsp MSI 설치가 성공하지 못했습니다.\n" +
                    "종료 코드: " + process.ExitCode + "\n" +
                    "기존 WinFsp 드라이버를 임의로 제거하지 않았습니다.",
                    "WinFsp 설치 실패");
            }
            else if(WinFspUpgradePolicy.UpgradeAvailable(
                WinFspUpgradePolicy.InstalledVersion()))
            {
                MessageBox.Show("MSI가 종료됐지만 WinFsp 새 버전이 확인되지 않습니다.\n" +
                    "PC를 재부팅한 뒤 환경 설정에서 다시 확인하세요.",
                    "WinFsp 버전 확인");
            }
            else if(!RemoteMount.HasWinFsp)
                MessageBox.Show("WinFsp 설치가 아직 확인되지 않았습니다. 설치 결과 코드: "+
                    process.ExitCode+"\n필요하면 재부팅 후 다시 연결하세요.", "WinFsp 설치");
            else
                MessageBox.Show("WinFsp " + WinFspUpgradePolicy.InstalledVersion() +
                    " 설치 확인 완료. NAS 드라이브 연결을 시도할 수 있습니다.","FileHub AIO");
        }
        catch(Exception ex)
        {
            MessageBox.Show("WinFsp 설치 오류: "+ex.Message, "FileHub AIO");
        }
        finally {RefreshList();}
    }
    async Task Perform(NasProfile p,bool connect,string? password,bool showError,
        bool forceDisconnect = false)
    {
        if (connect && shutdownRequested) return;
        if (connect && RemoteMount.EmergencyPending(p.Id)) return;
        // Parallel across profiles, serialized within each individual
        // profile. This protects Office file saves and mount ownership while
        // allowing independent WebDAV sessions to start simultaneously.
        var profileGate = GateFor(p);
        if (showError) await profileGate.WaitAsync();
        else if (!await profileGate.WaitAsync(0)) return;
        var operationTimer = Stopwatch.StartNew();
        try
        {
            if (connect && (shutdownRequested || RemoteMount.EmergencyPending(p.Id))) return;
            // An automatic operation may have entered ConnectOne before the
            // user pressed Disconnect, then waited during an auth/preflight.
            // Recheck the manual suppression AFTER acquiring the gate.
            if (connect && !showError && manualReconnectPause.IsSuspended(p.Id))
            {
                AppStore.Log("AUTO_CONNECT_SKIPPED_MANUAL_DISCONNECT " + p.Id);
                return;
            }
            // RemoteMount.Connect checks the exact same bridge owner again.
            // Do not open the named pipe twice per connection. That check
            // remains authoritative and rejects an older running bridge.
            if (connect && !EnsureAutomaticDriveLetter(p, showError)) return;
            status.Text=p.DisplayName+(connect
                ? p.Protocol is "GoogleDrive" or "TeraBox" or "OneDrive" or "MyBox"
                    ? " 초기 연결 준비 중 · 인증/폴더 응답 확인..."
                    : " 연결 중..."
                : " 해제 중...");
            var result=p.Protocol=="SMB"
                ? await Task.Run(()=>connect?WindowsShare.Connect(p,password):WindowsShare.Disconnect(p))
                : (connect ? await RemoteMount.Connect(p,password)
                    : forceDisconnect
                        ? await RemoteMount.ForceDisconnectOwnedAsync(p)
                        : await RemoteMount.Disconnect(p));
            AppStore.Log((connect?"CONNECT ":"DISCONNECT ")+p.RemoteDisplay+" | "+result.Message);
            if(!result.Success)
            {
                bool winFspNameConflict = connect &&
                    p.Protocol == "GoogleDrive" &&
                    (result.Message.Contains("80070050", StringComparison.OrdinalIgnoreCase) ||
                     result.Message.Contains("Mount failed", StringComparison.OrdinalIgnoreCase));
                if (connect && p.Protocol != "SMB" &&
                    RemoteMount.FailedBeforeFirstMount(p))
                {
                    // The backend/bridge may exist but the connection has
                    // never been usable. Stop it automatically, rather than
                    // leaving a broken WinFsp drive blocking later Exit.
                    try
                    {
                        var stopped = await Task.Run(() =>
                            RemoteMount.StopFailedBeforeFirstMountAsync(p))
                            .WaitAsync(TimeSpan.FromSeconds(8));
                        AppStore.Log("CONNECT_FAILURE_AUTO_FORCE_STOP id=" +
                            p.Id + " success=" + stopped.Success);
                    }
                    catch (Exception ex)
                    {
                        AppStore.Log("CONNECT_FAILURE_AUTO_STOP_TIMEOUT id=" +
                            p.Id + " type=" + ex.GetType().Name);
                    }
                }
                retry[p.Id] = DateTime.UtcNow.AddSeconds(
                    winFspNameConflict ? 900 :
                    connect && (p.UseNativeWebDavLocks ||
                        result.Message.Contains("인증서") ||
                        result.Message.Contains("HTTP 사용")) ? 600 :
                    connect && RemoteMount.IsRunning(p) ? 10 : 60);
                if (winFspNameConflict)
                {
                    AppStore.Log("GOOGLE_WINFSP_NAME_COLLISION_BACKOFF id=" +
                        p.Id + " letter=" + p.Letter + " autoRetrySeconds=900");
                    status.Text = "Google Drive · WinFsp 네트워크 마운트 충돌 (자동 재시도 일시 보류)";
                }
                if(showError)MessageBox.Show(
                    winFspNameConflict
                        ? "Google Drive 로그인 문제가 아니라 Windows WinFsp " +
                          "네트워크 마운트 생성 실패입니다 (80070050).\n\n" +
                          "같은 이름의 이전 FileHub 마운트 또는 Windows 네트워크 " +
                          "위치가 남아 있을 수 있습니다. 드라이브 문자는 자동 변경하지 " +
                          "않으며 반복 재연결도 일시 보류합니다.\n" +
                          "다른 프로그램과 기존 연결은 강제 종료하지 않았습니다.\n\n" +
                          "원본 오류:\n" + result.Message
                        : result.Message,
                    "FileHub AIO",MessageBoxButtons.OK,MessageBoxIcon.Warning);
            }
            else
            {
                retry.Remove(p.Id);
                if (connect && showError)
                {
                    manualReconnectPause.ManualConnectionSucceeded(p.Id);
                    AppStore.Log("MANUAL_CONNECT_AUTO_RECONNECT_RESTORED " + p.Id);
                }
                // Cosmetic Explorer integration is best-effort and must not
                // affect file locking, upload safety or connection success.
                if (connect)
                {
                    // Drive-specific WinFsp shell arrival is still immediate.
                    // Merge expensive desktop-wide icon association invalidation
                    // and Explorer F5 across simultaneously finishing drives.
                    ExplorerDriveIcon.Apply(p, deferGlobalNotify: p.Protocol != "SMB");
                    ExplorerDriveIcon.ScheduleNetworkLabelRepair(p);
                    if (p.Protocol != "SMB")
                        PostExplorerUpdate(p, mounted: true);
                }
                else
                {
                    ExplorerDriveIcon.Restore(p);
                    if (p.Protocol != "SMB")
                    {
                        try
                        {
                            var ghosts = await CleanupDisconnectedShellGhostsAsync(
                                p.Id.ToString("N")[..8])
                                .WaitAsync(TimeSpan.FromSeconds(20));
                            AppStore.Log("DISCONNECT_SHELL_GHOST_SWEEP id=" + p.Id +
                                " removed=" + ghosts.RemovedRecords +
                                " cancelled=" + ghosts.CancelledMappings +
                                " preserved=" + ghosts.Preserved +
                                " still=" + ghosts.StillShown);
                        }
                        catch (Exception ex)
                        {
                            AppStore.Log("DISCONNECT_SHELL_GHOST_SWEEP_DEFERRED id=" +
                                p.Id + " type=" + ex.GetType().Name);
                        }
                        PostExplorerUpdate(p, mounted: false);
                    }
                    else
                        ExplorerDriveIcon.NotifyComputerChanged();
                }
            }
            RefreshList();
        }
        catch(Exception e){AppStore.Log("ERROR "+e.Message);if(showError)MessageBox.Show(e.Message);}
        finally
        {
            operationTimer.Stop();
            AppStore.Log("PROFILE_OPERATION_TIMING id=" + p.Id +
                " operation=" + (connect ? "connect" : "disconnect") +
                " elapsedMs=" + operationTimer.ElapsedMilliseconds);
            profileGate.Release();
        }
    }
    private void PostExplorerUpdate(NasProfile p, bool mounted)
    {
        if (IsDisposed || Disposing || !IsHandleCreated) return;
        if (mounted && batchArrivals.Queue(p))
        {
            AppStore.Log("SHELL_ARRIVAL_DEFERRED_TO_BATCH id=" + p.Id +
                " letter=" + p.Letter);
            return;
        }
        // Shell COM enumeration must run on UI STA but is NOT required for
        // the already-verified WinFsp connect/safe disconnect to finish.
        // Post it so the operation gate and UI status are released first.
        BeginInvoke((Action)(() =>
        {
            if (IsDisposed || Disposing || exiting) return;
            try
            {
                if (mounted)
                {
                    if (!RemoteMount.IsMounted(p)) return;
                    // Apply() already set the label once. If the WinFsp
                    // MountPoints2 registration appears later, retry once
                    // below rather than running synchronous Shell COM twice.
                    ExplorerDriveIcon.NotifyMounted(p, deferGlobalNotify: true);
                    GoogleQuotaPresentation.Schedule(p);
                }
                else
                {
                    if (RemoteMount.DriveLetterExists(p.Letter)) return;
                    ExplorerDriveIcon.NotifySafeUnmounted(p);
                }
                shellRefresh.Request(
                    (mounted ? "FAST_CONNECT_" : "FAST_DISCONNECT_") +
                    p.Id.ToString("N")[..8]);
                if (mounted && RemoteMount.UsesNetworkDriveMode(p))
                    ExplorerDriveIcon.ScheduleNetworkLabelRepair(p);
            }
            catch (Exception ex)
            {
                AppStore.Log("FAST_EXPLORER_NOTIFY_WARNING " +
                    p.Id + " " + ex.Message);
            }
        }));
    }

    private async Task RefreshCloudLabelWhenSettled(NasProfile p)
    {
        // Some Windows 11 network-provider records appear just AFTER mount.
        // Retry a label-only registration; no per-drive desktop F5.
        await Task.Delay(650);
        if (IsDisposed || Disposing || exiting || !RemoteMount.IsMounted(p))
            return;
        try
        {
            if (ExplorerDriveIcon.EnsureConfiguredDisplayName(p,
                    deferGlobalNotify: true))
                shellRefresh.Request("CLOUD_LABEL_SETTLED_" +
                    p.Id.ToString("N")[..8]);
        }
        catch (Exception ex)
        {
            AppStore.Log("FAST_EXPLORER_SETTLED_WARNING " + p.Id + " " + ex.Message);
        }
    }
    async Task ConnectAll(bool manual)
    {
        if (shutdownRequested || cacheMaintenanceBusy || connectBatchRunning) return;
        if (!manual && !startupDependenciesReady)
        {
            AppStore.Log("AUTO_CONNECT_WAITING_FOR_STARTUP_DEPENDENCIES");
            return;
        }
        var requested = new List<NasProfile>();
        foreach(var p in cfg.Profiles.ToArray())
        {
            if (shutdownRequested) return;
            if (manual)
            {
                if (IsConnected(p)) continue;
            }
            else
            {
                // A live rclone/WinFsp backend must never be treated as a
                // disconnected profile just because a slow cloud health
                // check did not finish. Reconnecting here re-enumerates the
                // same root and makes the apparent connect/disconnect loop.
                if (p.Protocol != "SMB" && RemoteMount.IsRunning(p))
                {
                    if (!RemoteMount.IsMounted(p))
                        AppStore.Log("AUTO_RECONNECT_SKIPPED_LIVE_BACKEND id=" +
                            p.Id + " protocol=" + p.Protocol);
                    continue;
                }
                DateTime? retryAfter = retry.TryGetValue(p.Id, out var until)
                    ? until : null;
                if (!manualReconnectPause.ShouldAttemptAutoConnect(p,
                        IsConnected(p), retryAfter, DateTime.UtcNow))
                    continue;
            }
            requested.Add(p);
        }
        if (requested.Count == 0) return;
        connectBatchRunning = true;
        bool shellBatchActive = batchArrivals.Begin(requested);
        var batchTimer = Stopwatch.StartNew();
        AppStore.Log("BATCH_CONNECT_PARALLEL_START count=" + requested.Count +
            " manual=" + manual);
        // Start every independent profile before awaiting any one of them.
        // Each still performs its own authentication/WinFsp health checks.
        try
        {
            await Task.WhenAll(requested.Select(async p =>
            {
                try { await ConnectOne(p, manual); }
                catch (Exception e)
                {
                    AppStore.Log("BATCH_CONNECT_PROFILE_FAILED id=" + p.Id +
                        " type=" + e.GetType().Name);
                }
            }));
        }
        finally
        {
            if (shellBatchActive) batchArrivals.Completed();
            connectBatchRunning = false;
            AppStore.Log("BATCH_CONNECT_PARALLEL_FINISHED count=" + requested.Count +
                " elapsedMs=" + batchTimer.ElapsedMilliseconds +
                " shellBatchActive=" + shellBatchActive);
        }
    }
    internal void VerifyManualReconnectPauseForUiTest()
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") != "1")
            throw new InvalidOperationException("Manual reconnect UI verification is test-only");
        if (cfg.Profiles.Count < 2) return;
        var first = cfg.Profiles[0];
        var second = cfg.Profiles[1];
        manualReconnectPause.ManualDisconnectRequested(first.Id);
        RefreshList();
        if (!LockStatusPresentation.CardStatus(first, false, false,
                manualReconnectPause.IsSuspended(first.Id)).Contains("자동 재연결 중지"))
            throw new InvalidOperationException("Manual disconnect not shown in connection card");
        if (!manualReconnectPause.ShouldAttemptAutoConnect(second, false, null,
                DateTime.UtcNow) || manualReconnectPause.ShouldAttemptAutoConnect(
                first, false, null, DateTime.UtcNow))
            throw new InvalidOperationException("Manual reconnect suppression is not per profile");
        manualReconnectPause.ManualConnectionSucceeded(first.Id);
        RefreshList();
        if (!manualReconnectPause.ShouldAttemptAutoConnect(first, false, null,
                DateTime.UtcNow) || !first.AutoConnect)
            throw new InvalidOperationException("Explicit connect did not reenable auto connect");
        Console.WriteLine("MANUAL_DISCONNECT_UI_STATUS_PASS");
    }
    internal void VerifyParallelGatesForUiTest()
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") != "1")
            throw new InvalidOperationException("Parallel mount gate QA is test-only");
        // Exercises GateFor without touching NAS, cloud credentials or drives.
        var ids = new[] { Guid.NewGuid(), Guid.NewGuid(), Guid.NewGuid() };
        int active = 0, peak = 0;
        async Task Work(Guid id)
        {
            var gate = GateFor(new NasProfile { Id = id });
            await gate.WaitAsync().ConfigureAwait(false);
            try
            {
                int current = Interlocked.Increment(ref active);
                int old;
                do { old = peak; if (old >= current) break; }
                while (Interlocked.CompareExchange(ref peak, current, old) != old);
                await Task.Delay(170).ConfigureAwait(false);
            }
            finally { Interlocked.Decrement(ref active); gate.Release(); }
        }
        Task.WhenAll(ids.Select(id => Task.Run(() => Work(id))))
            .GetAwaiter().GetResult();
        if (peak < 2 || active != 0)
            throw new InvalidOperationException("Independent profiles serialized unexpectedly");
        peak = 0;
        Task.WhenAll(Enumerable.Range(0, 3)
            .Select(_ => Task.Run(() => Work(ids[0])))).GetAwaiter().GetResult();
        if (peak != 1 || active != 0)
            throw new InvalidOperationException("Same profile bypassed exclusive gate");
        Console.WriteLine("PARALLEL_DISTINCT_PROFILES_AND_SAME_PROFILE_EXCLUSION_PASS");
    }
    internal void VerifyShutdownGuardForUiTest()
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") != "1")
            throw new InvalidOperationException("Shutdown verification is test-only");
        bool prior = shutdownRequested;
        try
        {
            shutdownRequested = true;
            // Pending timer and explicit connect callbacks must not start a
            // network operation while the program is closing.
            ConnectAll(false).GetAwaiter().GetResult();
            ConnectAll(true).GetAwaiter().GetResult();
            if (cfg.Profiles.Count > 0)
            {
                var p = cfg.Profiles[0];
                ConnectOne(p, false).GetAwaiter().GetResult();
                ConnectOne(p, true).GetAwaiter().GetResult();
                Perform(p, true, null, false).GetAwaiter().GetResult();
                if (RemoteMount.HasManagedProcess(p))
                    throw new InvalidOperationException("Shutdown created a new backend");
            }
            Console.WriteLine("SHUTDOWN_GUARD_NO_NEW_MOUNT_PASS");
        }
        finally { shutdownRequested = prior; }
    }

    // Use a dedicated Shell STA: Explorer's network namespace is COM-based.
    // The worker never terminates a backend; the cleaner preserves active
    // WinFsp, MPR, rclone, and other vendors' drive-letter mappings.
    private Task<LegacyNetworkGhostCleaner.Summary> CleanupDisconnectedShellGhostsAsync(
        string? onlyGuid8 = null)
    {
        var completion = new TaskCompletionSource<LegacyNetworkGhostCleaner.Summary>(
            TaskCreationOptions.RunContinuationsAsynchronously);
        var profiles = cfg.Profiles.ToArray();
        var thread = new Thread(() =>
        {
            try
            {
                completion.TrySetResult(LegacyNetworkGhostCleaner.Cleanup(
                    profiles, onlyGuid8, includeHistorical: false));
            }
            catch (Exception ex) { completion.TrySetException(ex); }
        })
        {
            IsBackground = true,
            Name = "FileHub.DisconnectedShellGhostCleanup"
        };
        thread.SetApartmentState(ApartmentState.STA);
        thread.Start();
        return completion.Task;
    }

    async Task CheckStartupRemnants()
    {
        var startupCleanupTimer = Stopwatch.StartNew();
        // DO NOT issue network-disconnect Shell notifications for old GUIDs
        // inferred ONLY from 30 days of diagnostic logs on EVERY startup.
        // v1.3.53 replayed 65 phantom server disconnect events in <2sec,
        // despite zero mapped/registry/Shell entries actually removed.
        // Historical orphan cleanup is now explicit opt-in in Settings.
        // Per-profile stale mapping checks below are still guarded and run.
        AppStore.Log("STARTUP_HISTORY_SWEEP_DEFERRED manual_opt_in_only=true");
        bool changedShell = false;
        var conflicts = new List<string>();
        foreach (var p in cfg.Profiles)
        {
            if (p.Protocol == "SMB" || RemoteMount.HasManagedRecord(p)) continue;
            if (p.Protocol is "GoogleDrive" or "TeraBox" &&
                !RemoteMount.HasVerifiedOtherVendorMapping(p))
            {
                var orphan = await OrphanRcloneRecovery.TryRecoverAsync(p);
                if (orphan is { } recovery)
                {
                    AppStore.Log("STARTUP_OLD_GOOGLE_SAFE_RECOVERY id=" + p.Id +
                        " success=" + recovery.Success +
                        " letter=" + p.Letter);
                    if (recovery.Success) changedShell = true;
                    // An unverified or busy process stays mounted and will
                    // be shown as a collision below. Never kill it.
                }
            }
            // A red-X persistent map still appears in GetLogicalDrives().
            // Earlier versions treated mere letter existence as an active
            // drive and skipped cleanup forever. Verify disconnected state,
            // exact NASMount GUID and old backend ownership before cleanup.
            if (NetworkDriveResidueCleaner.IsDisconnectedOwnedGhost(p))
            {
                bool bridgeAlive = p.UseLockBridge &&
                    (await BridgeOwnership.ProbeAsync(p.Id)).IsOccupied;
                if (!bridgeAlive &&
                    !NetworkDriveResidueCleaner.OldBackendMayStillOwn(p))
                {
                    var cleaned = NetworkDriveResidueCleaner.AfterSafeStop(p);
                    AppStore.Log("STARTUP_RED_X_GHOST_RECOVERY " + p.Id +
                        " result=" + cleaned.Message);
                    if (cleaned.Success && !RemoteMount.DriveLetterExists(p.Letter))
                    {
                        changedShell = true;
                        continue;
                    }
                }
            }
            if (RemoteMount.DriveLetterExists(p.Letter))
            {
                if (RemoteMount.HasVerifiedOtherVendorMapping(p))
                {
                    // Windows may have reused an old FileHub AIO drive letter
                    // for RaiDrive. Remove only our own lingering icon
                    // override, leaving that other app's live drive alone.
                    ExplorerDriveIcon.Restore(p);
                    AppStore.Log("STARTUP_FOREIGN_DRIVE_ICON_OVERRIDE_RESTORED " +
                        p.Id + " letter=" + p.Letter);
                }
                conflicts.Add(p.DisplayName + " (" + p.Letter.ToUpperInvariant() +
                    ":) — Windows에서 드라이브 문자를 이미 사용 중");
                continue;
            }
            if (p.UseLockBridge)
            {
                var oldBridge = await BridgeOwnership.ProbeAsync(p.Id);
                if (oldBridge.IsOccupied)
                {
                    conflicts.Add(p.DisplayName + " (" + p.Letter.ToUpperInvariant() +
                        ":) — 이전 잠금 브리지가 실행 중");
                    continue;
                }
            }
            // No drive or running lock bridge: clear only our own icon and
            // label registrations for this profile, never system mappings.
            if (NetworkDriveResidueCleaner.HasDisconnectedOwnedResidue(p))
            {
                NetworkDriveResidueCleaner.CleanupDisconnectedStartupHistory(p);
                changedShell = true;
            }
        }
        // A session-named WinFsp network place may remain visible as a red-X
        // tile even after its old HKCU MountPoints2 key was deleted. Inspect
        // CURRENT Shell/provider evidence, not 30 days of historical logs.
        try
        {
            var ghosts = await CleanupDisconnectedShellGhostsAsync()
                .WaitAsync(TimeSpan.FromSeconds(25));
            AppStore.Log("STARTUP_DISCONNECTED_SHELL_GHOST_SWEEP found=" +
                ghosts.Found + " removed=" + ghosts.RemovedRecords +
                " cancelled=" + ghosts.CancelledMappings +
                " preserved=" + ghosts.Preserved + " still=" + ghosts.StillShown);
            changedShell |= ghosts.RemovedRecords > 0 ||
                ghosts.CancelledMappings > 0;
        }
        catch (Exception ex)
        {
            AppStore.Log("STARTUP_DISCONNECTED_SHELL_GHOST_SWEEP_DEFERRED " +
                ex.GetType().Name);
        }
        // A normal clean launch has no stale mapped drive to remove.
        // Avoid flashing the entire Explorer desktop before any mount exists.
        if (changedShell)
            shellRefresh.Request("STARTUP_RESIDUE_CLEANED");
        AppStore.Log("STARTUP_RESIDUE_CHECK_TIMING elapsedMs=" +
            startupCleanupTimer.ElapsedMilliseconds +
            " shellWorkNeeded=" + changedShell);
        if (conflicts.Count == 0) return;
        AppStore.Log("STARTUP_UNMANAGED_DRIVE_CONFLICT " +
            string.Join(" | ", conflicts));
        if (!Visible)
        {
            status.Text = "이전 연결 또는 드라이브 문자 충돌 · 로그 확인 필요";
            tray.ShowBalloonTip(9000, "FileHub AIO: 잔여 연결 확인",
                "이전 실행의 드라이브가 남아 있습니다. FileHub AIO를 열어 확인하세요.",
                ToolTipIcon.Warning);
            return;
        }
        MessageBox.Show(this,
            "이전 실행의 잔여 연결이 있거나 다른 프로그램이 같은 드라이브 문자를 사용 중입니다.\n\n" +
            string.Join("\n", conflicts) + "\n\n" +
            "업로드 대기 파일이 있을 수 있으므로 다른 프로그램의 드라이브를" +
            " 자동 제거하거나 rclone을 강제 종료하지 않습니다.\n" +
            "이전 FileHub AIO의 트레이 아이콘 및 작업 관리자에서 실행 중인" +
            " rclone / NASMountLockBridge를 확인해 안전하게 해제하세요.",
            "잔여 연결 또는 드라이브 문자 충돌",
            MessageBoxButtons.OK, MessageBoxIcon.Warning);
    }
    void OpenDrive()
    {
        var p=Selected();if(p is null)return;
        OpenDrive(p);
    }
    void OpenDrive(NasProfile p)
    {
        if(!IsConnected(p)){MessageBox.Show("먼저 연결하세요.");return;}
        Process.Start(new ProcessStartInfo("explorer.exe",p.Letter+":\\") { UseShellExecute=true });
    }
    async Task ShowWebDavLockStatus()
    {
        var p = Selected();
        if (p is null || p.Protocol != "WebDAV") return;
        if (RemoteMount.CurrentWebDavEngine(p.Id) == "direct-single-winfsp")
        {
            var state = IsConnected(p)
                ? await RemoteMount.CheckPersistentMount(p)
                : OperationResult.Fail("현재 WebDAV 드라이브가 연결되어 있지 않습니다.");
            string mode = p.UseLockBridge || p.AllowUnprotectedWrites
                ? "서버 조건부 저장 및 문서 잠금 검증 모드"
                : "읽기 전용 모드";
            MessageBox.Show(this,
                "FileHub 전용 WebDAV 엔진 (단일 WinFsp)\n" +
                "rclone 중간 마운트 없음\n연결 방식: " + mode + "\n\n" +
                state.Message + "\n\n" +
                "서버가 안전한 저장·잠금 기능을 지원하지 않으면 " +
                "무단 덮어쓰기를 허용하지 않습니다.",
                "WebDAV 연결 및 잠금 상태", MessageBoxButtons.OK,
                state.Success ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            return;
        }
        string message;
        if (!p.UseLockBridge)
        {
            message = p.AllowUnprotectedWrites
                ? "현재 WebDAV 연결은 파일잠금 없이 쓰기가 허용된 상태입니다.\n" +
                  "다른 PC와 같은 Excel 파일을 동시에 수정하면 내용이 충돌할 수 있습니다.\n\n" +
                  "안전하게 연결 해제한 후 [설정]에서 [파일잠금]을 선택하세요."
                : "현재 WebDAV 연결은 읽기 전용입니다.\n" +
                  "쓰기를 잠금으로 보호하려면 안전하게 해제한 뒤 [파일잠금] 모드를 선택하세요.";
        }
        else if (!IsConnected(p))
        {
            message = "설정에서 파일잠금 모드를 선택했지만 드라이브 연결이 확인되지 않았습니다.\n" +
                      "파일을 열기 전에 연결 상태와 로그를 확인하세요.";
        }
        else
        {
            var probe = await BridgeOwnership.ProbeAsync(p.Id);
            bool ready = probe.Presence == BridgePresence.Active &&
                probe.Detail.Contains("READY", StringComparison.Ordinal);
            message = ready
                ? "파일잠금 모드: 켜짐\n잠금 브리지: 응답 확인\n" +
                  probe.Detail + "\n\n" +
                  "Excel에서 쓰기용으로 문서를 열면 서버에 파일명.xlsx.nasmount.lock 폴더를 생성합니다.\n" +
                  "이 폴더는 FileHub AIO 드라이브 목록에서 숨기므로 NAS DSM이나 직접 WebDAV에서 확인하세요."
                : "파일잠금 모드: 켜짐, 하지만 잠금 브리지 응답이 확인되지 않았습니다.\n" +
                  probe.Detail + "\n\n편집을 중단하고 연결 상태와 로그를 확인하세요.";
        }
        message += "\n\n~$파일명.xlsx는 Excel이 직접 생성하는 숨김 소유자 파일입니다.\n" +
                   "이 파일이 보이지 않는 것만으로 FileHub AIO 잠금 실패를 판단할 수 없습니다.\n" +
                   "실제 2대 PC 동시 편집 차단은 별도로 확인해야 합니다.";
        AppStore.Log("WEBDAV_LOCK_STATUS profile=" + p.Id +
            " enabled=" + p.UseLockBridge + " mounted=" + IsConnected(p));
        MessageBox.Show(this, message, "WebDAV 파일잠금 상태",
            MessageBoxButtons.OK, p.UseLockBridge && IsConnected(p) ?
                MessageBoxIcon.Information : MessageBoxIcon.Warning);
    }
    void OpenLogs()
    {
        string folder=Path.Combine(AppStore.DirectoryPath,"logs");Directory.CreateDirectory(folder);
        Process.Start(new ProcessStartInfo("explorer.exe",folder){UseShellExecute=true});
    }

    // Both ordinary Exit and update installation require an explicit
    // user-approved data-loss warning before force-unmounting owned drives.
    private async Task ExitProgramFast()
    {
        await ExitSafely();
    }

    async Task ExitSafely(PcUpdateService.Staged? installUpdate = null)
    {
        if (shutdownRequested) return;
        shutdownRequested = true;
        timer.Stop();
        var heldGates = new List<SemaphoreSlim>();
        // The update install dialog already obtained explicit approval,
        // including the potential loss of unflushed writes. Do not ask again.
        bool forceApproved = installUpdate is not null;
        try
        {
            if (forceApproved)
            {
                var ownedOrStarting = cfg.Profiles.Where(p =>
                    p.Protocol != "SMB" &&
                    (RemoteMount.HasManagedRecord(p) ||
                     preparingConnections.Contains(p.Id))).ToArray();
                foreach (var p in ownedOrStarting)
                    RemoteMount.RequestEmergency(p.Id);
                AppStore.Log("USER_CONFIRMED_FORCE_UPDATE count=" +
                    ownedOrStarting.Length + " cachePreserved=true");
            }
            if (installUpdate is null)
            {
                var activeOrStarting = cfg.Profiles.Where(p =>
                    p.Protocol != "SMB" &&
                    (RemoteMount.HasManagedRecord(p) ||
                     preparingConnections.Contains(p.Id))).ToArray();
                if (activeOrStarting.Length > 0)
                {
                    if (MessageBox.Show(
                        "FileHub 원격 드라이브 " + activeOrStarting.Length +
                        "개를 해제하고 프로그램을 종료합니다.\n\n" +
                        "실행 중인 EXE, 열린 Excel 문서 및 파일 잠금이 있어도 " +
                        "FileHub 소유의 연결을 강제로 끊습니다.\n" +
                        "저장하지 않은 내용과 전송 대기 파일이 손실될 수 있습니다.\n" +
                        "복구 기록/캐시는 삭제하지 않습니다.\n" +
                        "SMB와 다른 프로그램의 드라이브는 강제로 해제하지 않습니다.\n\n" +
                        "강제 연결 해제 후 종료할까요?",
                        "FileHub AIO 종료 확인", MessageBoxButtons.YesNo,
                        MessageBoxIcon.Warning) != DialogResult.Yes)
                        return;
                    forceApproved = true;
                    // Cancel connections already in progress before waiting
                    // on their per-profile semaphore; never relaunch them.
                    foreach (var p in activeOrStarting)
                        RemoteMount.RequestEmergency(p.Id);
                    AppStore.Log("USER_CONFIRMED_FORCE_EXIT count=" +
                        activeOrStarting.Length + " cachePreserved=true");
                }
            }
            // The timer or a user click may already have started connecting.
            // Wait for EACH already-running profile operation. A shared
            // global gate would serialize all startup and shutdown work.
            // All user-approved force exits, including PC installation, use
            // a bounded wait rather than hanging on open Office handles.
            status.Text = "자동 연결 중지 · 진행 중인 연결 작업 확인 중...";
            DateTime deadline = DateTime.UtcNow.AddSeconds(
                forceApproved ? 6 : 45);
            foreach (var p in cfg.Profiles.ToArray())
            {
                var remaining = deadline - DateTime.UtcNow;
                var currentGate = GateFor(p);
                if (remaining <= TimeSpan.Zero ||
                    !await currentGate.WaitAsync(remaining))
                {
                    AppStore.Log("SHUTDOWN_PROFILE_GATE_BUSY_TIMEOUT " + p.Id);
                    if (forceApproved)
                    {
                        // The cancellation flag above prevents a connecting
                        // worker from creating a new mount after this point.
                        // Never block explicit Exit on a stuck network probe.
                        continue;
                    }
                    MessageBox.Show(
                        "진행 중인 드라이브 연결 작업이 완료되지 않아 종료를 보류했습니다.\n" +
                        "연결 또는 전송이 끝난 뒤 다시 [프로그램 종료]를 선택하세요.\n" +
                        "rclone/WinFsp를 강제로 종료하지 않았습니다.\n\n" +
                        "종료가 지연되면 열린 문서를 저장하고 일반 [프로그램 종료]를 다시 선택하세요.\n" +
                        "미전송 데이터가 있을 경우 손실 위험을 확인한 뒤 진행하세요.",
                        "FileHub AIO 종료 대기", MessageBoxButtons.OK,
                        MessageBoxIcon.Warning);
                    return;
                }
                heldGates.Add(currentGate);
            }
            var running = cfg.Profiles.Where(p =>
                p.Protocol != "SMB" && RemoteMount.HasManagedRecord(p)).ToArray();
            if (!forceApproved && running.Length > 0 &&
                MessageBox.Show(
                    "원격 드라이브 " + running.Length + "개가 이 프로그램에서 실행 중입니다.\n" +
                    "열린 문서를 모두 저장하고 닫았나요?\n\n" +
                    "종료 전에 업로드 완료 및 WinFsp 드라이브 문자 해제까지 확인합니다.\n" +
                    "안전하게 해제할 수 없는 연결이 있으면 프로그램 종료를 취소합니다.\n" +
                    "SMB 및 다른 프로그램의 드라이브는 건드리지 않습니다.",
                    "FileHub AIO 안전 종료", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Question) != DialogResult.Yes)
                return;
            status.Text = "연결 " + running.Length + "개 " +
                (forceApproved ? "강제 해제 중..." : "안전 해제 중...");
            var disconnectTimer = Stopwatch.StartNew();
            AppStore.Log("BATCH_DISCONNECT_PARALLEL_START count=" +
                running.Length + " forceApproved=" + forceApproved);
            var outcomes = await Task.WhenAll(running.Select(async p =>
            {
                try
                {
                    return (Profile: p,
                        Result: forceApproved
                            ? await RemoteMount.ForceDisconnectOwnedAsync(p)
                            : await RemoteMount.Disconnect(p));
                }
                catch (Exception ex)
                {
                    return (Profile: p,
                        Result: OperationResult.Fail("예외로 안전 해제 보류: " + ex.Message));
                }
            }));
            var failed = new List<string>();
            foreach (var outcome in outcomes)
            {
                var p = outcome.Profile;
                if (!outcome.Result.Success)
                {
                    failed.Add(p.DisplayName + ": " + outcome.Result.Message);
                    AppStore.Log("SHUTDOWN_SAFE_STOP_FAILED " + p.Id +
                        " " + outcome.Result.Message);
                    continue;
                }
                // Clear only our own Explorer icon and shell label, and only
                // after the WinFsp/rclone drive is verified to be released.
                ExplorerDriveIcon.Restore(p);
                ExplorerDriveIcon.NotifySafeUnmounted(p);
                AppStore.Log("SHUTDOWN_DRIVE_RELEASED " + p.Id + " letter=" + p.Letter);
            }
            if (running.Length > 0)
                ExplorerLiveViewRefresh.RefreshThisPc("SAFE_EXIT_BATCH");
            AppStore.Log("BATCH_SAFE_DISCONNECT_PARALLEL_FINISHED succeeded=" +
                (outcomes.Length - failed.Count) + " failed=" + failed.Count +
                " elapsedMs=" + disconnectTimer.ElapsedMilliseconds);
            if (failed.Count > 0 && !forceApproved)
            {
                MessageBox.Show(
                    "다음 드라이브를 안전하게 해제하지 못해 프로그램 종료를 취소했습니다.\n\n" +
                    string.Join("\n\n", failed.Take(5)) +
                    "\n\n다른 정상 드라이브는 안전하게 해제됐습니다.\n" +
                    "미완료 업로드/파일잠금은 그대로 보호합니다.\n" +
                    "업로드가 끝난 뒤 다시 업데이트를 진행하세요.",
                    "FileHub AIO 종료 보류", MessageBoxButtons.OK,
                    MessageBoxIcon.Warning);
                return;
            }
            if (failed.Count > 0 && forceApproved)
                AppStore.Log("USER_FORCE_EXIT_INCOMPLETE " +
                    string.Join(" | ", failed.Take(5)) +
                    " userAuthorizedExit=true cachePreserved=true");
            // All owned backends are now safely stopped. Remove their
            // session-suffixed disconnected Shell entries BEFORE closing
            // the UI, so the user does not inherit red-X network tiles.
            try
            {
                var ghosts = await CleanupDisconnectedShellGhostsAsync()
                    .WaitAsync(TimeSpan.FromSeconds(20));
                AppStore.Log("SAFE_EXIT_SHELL_GHOST_SWEEP found=" + ghosts.Found +
                    " removed=" + ghosts.RemovedRecords +
                    " cancelled=" + ghosts.CancelledMappings +
                    " preserved=" + ghosts.Preserved +
                    " still=" + ghosts.StillShown);
                if (ghosts.RemovedRecords > 0 || ghosts.CancelledMappings > 0)
                {
                    ExplorerDriveIcon.NotifyComputerChanged();
                    ExplorerLiveViewRefresh.RefreshThisPc("SAFE_EXIT_GHOST_CLEAN");
                }
            }
            catch (Exception ex)
            {
                AppStore.Log("SAFE_EXIT_SHELL_GHOST_SWEEP_DEFERRED type=" +
                    ex.GetType().Name);
            }
            // We do not silently kill older rclone/WinFsp processes from
            // another FileHub AIO instance. They may contain dirty VFS data.
            if (installUpdate is not null)
            {
                // Do not run the updater on top of a still-running owned
                // mount process. A stale Explorer letter alone is not such
                // a blocker; it was already handled as best-effort above.
                // Retry exact in-memory owned processes once if WinFsp did
                // not acknowledge the first force termination.
                foreach (var remaining in cfg.Profiles.Where(p =>
                    p.Protocol != "SMB" && RemoteMount.HasManagedRecord(p)))
                {
                    var retryStop = await RemoteMount.ForceDisconnectOwnedAsync(
                        remaining);
                    AppStore.Log("PC_UPDATE_FORCE_STOP_RETRY id=" +
                        remaining.Id + " success=" + retryStop.Success);
                }
                if (cfg.Profiles.Any(p =>
                    p.Protocol != "SMB" && RemoteMount.HasManagedRecord(p)))
                {
                    MessageBox.Show(this,
                        "Windows에서 일부 FileHub 마운트 프로세스의 종료를 확인하지 못했습니다.\n" +
                        "다른 프로그램의 프로세스는 강제 종료하지 않았습니다.\n" +
                        "컴퓨터를 재시작한 뒤 업데이트를 다시 실행하세요.",
                        "PC 업데이트 실행 확인 필요",
                        MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    return;
                }
                try { PcUpdateService.StartHelper(installUpdate); }
                catch (Exception ex)
                {
                    AppStore.Log("PC_UPDATE_HELPER_LAUNCH_BLOCKED " + ex.Message);
                    MessageBox.Show(this,
                        "NAS 연결은 안전하게 해제됐지만 업데이트 실행을 시작하지 못했습니다.\n" +
                        ex.Message + "\n기존 프로그램을 계속 사용할 수 있습니다.",
                        "PC 업데이트 보류", MessageBoxButtons.OK,
                        MessageBoxIcon.Warning);
                    return;
                }
            }
            exiting = true;
            tray.Visible = false;
            AppStore.Log("SHUTDOWN_SAFE_COMPLETE managed="+running.Length);
            Close();
        }
        catch (Exception e)
        {
            AppStore.Log("SHUTDOWN_EXCEPTION " + e);
            if (forceApproved)
            {
                // User already approved the unmount, including for update.
                // Attempt a bounded cleanup for owned processes only.
                foreach (var profile in cfg.Profiles.Where(p =>
                    p.Protocol != "SMB" && RemoteMount.HasManagedRecord(p)))
                {
                    try { await RemoteMount.ForceDisconnectOwnedAsync(profile); }
                    catch (Exception ex)
                    {
                        AppStore.Log("FORCE_EXIT_LAST_RESORT_FAILED id=" +
                            profile.Id + " type=" + ex.GetType().Name);
                    }
                }
                if (installUpdate is null)
                {
                    exiting = true;
                    tray.Visible = false;
                    Close();
                }
                else
                {
                    // Secondary failures must not silently skip installation;
                    // retry the helper only once every owned process is gone.
                    if (cfg.Profiles.All(p => p.Protocol == "SMB" ||
                        !RemoteMount.HasManagedRecord(p)))
                    {
                        try
                        {
                            PcUpdateService.StartHelper(installUpdate);
                            exiting = true;
                            tray.Visible = false;
                            Close();
                        }
                        catch (Exception updateEx)
                        {
                            AppStore.Log("PC_UPDATE_HELPER_RETRY_FAILED " +
                                updateEx.GetType().Name);
                            MessageBox.Show(this, "업데이트 도우미 실행 실패: " +
                                updateEx.Message, "PC 업데이트 보류",
                                MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        }
                    }
                    else
                        MessageBox.Show(this,
                            "일부 FileHub 프로세스가 남아 있어 업데이트를 실행하지 못했습니다.\n" +
                            "컴퓨터 재시작 후 다시 시도하세요.",
                            "PC 업데이트 보류", MessageBoxButtons.OK,
                            MessageBoxIcon.Warning);
                }
            }
            else
                MessageBox.Show("안전 종료 처리 중 오류가 발생했습니다.\n" + e.Message +
                    "\n업로드 중인 연결은 강제로 종료하지 않았습니다.",
                    "FileHub AIO 종료 보류", MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }
        finally
        {
            foreach (var profileGate in heldGates)
                profileGate.Release();
            if (!exiting)
            {
                shutdownRequested = false;
                timer.Start();
                RefreshList();
            }
        }
    }
    void ShowWindow(){Show();ShowInTaskbar=true;WindowState=FormWindowState.Normal;Activate();RefreshList();}
    internal void VerifySafeExitNoKeepAliveForUiTest()
    {
        if (Environment.GetEnvironmentVariable("NASMOUNT_UI_SMOKE") != "1")
            throw new InvalidOperationException("Exit menu check is test-only");
        var labels = tray.ContextMenuStrip?.Items.Cast<ToolStripItem>()
            .Select(item => item.Text).ToArray() ?? [];
        if (!labels.Contains("프로그램 종료") ||
            labels.Any(label => label.Contains("연결 유지") ||
                label.Contains("화면 종료", StringComparison.Ordinal)))
            throw new InvalidOperationException(
                "Close must safely disconnect all mounts, not retain a background engine");
        if (labels.Any(label => label.Contains("비상 종료")))
            throw new InvalidOperationException("Only the ordinary Exit action may be presented");
        foreach (var profile in cfg.Profiles)
        {
            using var menu = MakeCardMenu(profile);
            bool hasEmergency = menu.Items.Cast<ToolStripItem>()
                .Any(item => item.Text.Contains("비상 연결 종료"));
            if (hasEmergency)
                throw new InvalidOperationException(
                    "Extra emergency menus must not be shown");
        }
        Console.WriteLine("SAFE_EXIT_NO_KEEP_CONNECTED_BACKGROUND_ENGINE_PASS");
    }
    static bool IsConnected(NasProfile p) => p.Protocol=="SMB" ? WindowsShare.IsOurMapping(p) : RemoteMount.IsMounted(p);
}

internal sealed class PasswordDialog:Form
{
    readonly TextBox password=new(){UseSystemPasswordChar=true};
    public string Password=>password.Text;
    public PasswordDialog(string title)
    {
        ModernTheme.EnableDarkTitleBar(this);
        Text = title + " · NAS 암호";
        ClientSize = new Size(475, 250);
        StartPosition = FormStartPosition.CenterParent;
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        AutoScaleMode = AutoScaleMode.Dpi;
        BackColor = ModernTheme.Background;
        ForeColor = ModernTheme.Text;
        Font = new Font("Malgun Gothic", 10);

        var heading = ModernTheme.Label("NAS 접속 암호", 16, true);
        heading.SetBounds(28, 19, 410, 43);
        Controls.Add(heading);
        var help = ModernTheme.Label("저장하지 않은 계정의 암호를 입력하세요.", 9,
            color: ModernTheme.Muted);
        help.SetBounds(29, 65, 408, 25);
        Controls.Add(help);
        password.SetBounds(29, 105, 410, 38);
        password.BackColor = ModernTheme.Field;
        password.ForeColor = ModernTheme.Text;
        password.BorderStyle = BorderStyle.FixedSingle;
        password.Font = new Font("Malgun Gothic", 11);
        Controls.Add(password);

        var ok = ModernTheme.Button("연결", 110,
            () => { DialogResult = DialogResult.OK; Close(); }, true, 40);
        ok.SetBounds(329, 181, 110, 40);
        Controls.Add(ok);
        var cancel = ModernTheme.Button("취소", 103,
            () => { DialogResult = DialogResult.Cancel; Close(); }, height: 40);
        cancel.SetBounds(217, 181, 103, 40);
        Controls.Add(cancel);
        AcceptButton = ok;
        CancelButton = cancel;
    }
}
