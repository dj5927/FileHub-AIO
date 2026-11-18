using System.Drawing;
using System.Diagnostics;
using System.Collections.Concurrent;
using System.Windows.Forms;
namespace NASMount;

internal sealed partial class MainForm : Form
{
    readonly AppConfig cfg;
    readonly Label status = new() { Dock = DockStyle.Fill, ForeColor = Color.LightGray };
    NotifyIcon tray = null!;
    readonly System.Windows.Forms.Timer timer = new() { Interval = 30000 };
    // Keep reconnect attempts at 30s but refresh upload status separately.
    readonly System.Windows.Forms.Timer uploadStatusTimer = new() { Interval = 5000 };
    bool transferStatusRefreshing;
    bool transferStatusRefreshAgain;
    string? lastTransferWarning;
    Guid? lastTransferWarningProfile;
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
    public MainForm(bool minimized, AppConfig? previewConfig = null)
    {
        cfg = previewConfig ?? AppStore.Load();
        // Old configuration files may retain this retired opt-in flag.
        cfg.GoogleStartupDiagnostics = false;
        GoogleStartupTrace.Enabled = false;
        shellRefresh = new ExplorerRefreshCoalescer(cause =>
        {
            if (shutdownRequested || exiting || IsDisposed || Disposing) return;
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
                foreach (var p in profiles.Where(p =>
                    p.Protocol is "MyBox" or "OneDrive"))
                    ExplorerDriveIcon.EnsureConfiguredDisplayName(p,
                        deferGlobalNotify: true);
                int count = ExplorerDriveIcon.NotifyMountedBatch(profiles);
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
        Disposed += (_, _) => uploadStatusTimer.Dispose();
        InitializeModernUi(minimized);
        if (previewConfig is null)
        {
            try { AppStore.UpdateExistingAutoStartTarget(); }
            catch (Exception ex)
            {
                AppStore.Log("AUTOSTART_TARGET_MIGRATION_SKIPPED " + ex.GetType().Name);
            }
            AppStore.Log("NASMOUNT_START VERSION=1.3.95 PRODUCT=FileHubAIO SFTP_POLICY=PINNED_HOST_KEYS " +
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
        NasProfile profile, bool manual = false)
    {
        if (shutdownRequested || exiting || IsDisposed || Disposing ||
            profile.Protocol != "GoogleDrive" ||
            !RemoteMount.IsRunning(profile)) return;
        if (!cloudHealthRunning.Add(profile.Id)) return;
        try
        {
            if (!manual && nextCloudHealthCheck.TryGetValue(profile.Id,
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
                if (manual)
                    MessageBox.Show(this, health.Message +
                        "\n\n기존 연결 프로세스와 미전송 파일은 보존했습니다.",
                        "Google Drive 연결 재확인", MessageBoxButtons.OK,
                        MessageBoxIcon.Warning);
                return;
            }
            bool? shown = await ExplorerDriveIcon.ComputerFolderShowsAsync(profile.Letter);
            if (shutdownRequested || IsDisposed || Disposing ||
                !RemoteMount.IsMounted(profile)) return;
            cloudShellShown[profile.Id] = shown;
            AppStore.Log("GOOGLE_EXPLORER_SHELL_VISIBILITY id=" + profile.Id +
                " letter=" + profile.Letter + " shown=" +
                (shown?.ToString() ?? "unknown") + " manual=" + manual);
            if (shown == false || manual)
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
                if (manual && !IsDisposed && !Disposing)
                    MessageBox.Show(this,
                        after == true
                            ? "드라이브 " + profile.Letter + ": 가 Windows '내 PC' 목록에 등록돼 있습니다.\n" +
                              "탐색기가 계속 이전 목록을 표시하면 해당 탐색기 창에서 F5를 눌러주세요."
                            : "Google Drive 마운트 파일은 읽을 수 있지만 Windows '내 PC' 등록 상태를 아직 확인하지 못했습니다.\n" +
                              "드라이브 문자를 변경하거나 캐시를 삭제하지 않았습니다.\n" +
                              "Windows 탐색기 주소창에서 " + profile.Letter + @":\ 를 직접 입력해 접근을 확인하세요.",
                        "Google Drive 탐색기 표시 복구",
                        MessageBoxButtons.OK, after == true
                            ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
            }
            else if (manual)
                MessageBox.Show(this, "탐색기 O: 드라이브 등록을 확인했습니다.",
                    "Google Drive 탐색기 표시");
            if (!IsDisposed && !Disposing) RefreshList();
        }
        catch (Exception ex)
        {
            AppStore.Log("GOOGLE_EXPLORER_RECONCILE_FAILED id=" + profile.Id +
                " reason=" + ex.GetType().Name);
            if (manual && !IsDisposed && !Disposing)
                MessageBox.Show(this, "탐색기 표시 확인 중 오류: " + ex.Message,
                    "Google Drive");
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
            if(p.Protocol!="SMB" && MessageBox.Show("열린 문서를 모두 저장하고 닫았나요?\n캐시에 업로드 대기 중인 파일이 있을 수 있습니다.",
                "연결 해제 확인",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;
            // Store the user's intent BEFORE awaiting the unmount. Timer
            // ticks, startup auto-connect and cloud retry callbacks must not
            // undo a manual disconnect in the current program session.
            manualReconnectPause.ManualDisconnectRequested(p.Id);
            AppStore.Log("MANUAL_DISCONNECT_AUTO_RECONNECT_SUSPENDED " + p.Id);
            await Perform(p,false,null,true);
        }
    }
    async Task ConnectOne(NasProfile p,bool explicitRequest,string? suppliedPassword=null)
    {
        if (shutdownRequested || deletingProfiles.Contains(p.Id)) return;
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
        if (p.Protocol == "GoogleDrive" && !GoogleDriveAuth.IsAuthorized(p))
        {
            if (!explicitRequest)
            {
                retry[p.Id] = DateTime.UtcNow.AddMinutes(5);
                return; // No unsolicited Google login window on Windows startup.
            }
            using var login = new GoogleDriveLoginDialog(p);
            if (login.ShowDialog(this) != DialogResult.OK) return;
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

    async Task CheckPersistentMount()
    {
        var p = Selected();
        if (p is null || p.Protocol == "SMB")
        {
            MessageBox.Show("연결된 WebDAV/SFTP/FTP 프로필을 선택하세요.");
            return;
        }
        status.Text = "rclone 프로세스와 로컬 제어 채널 확인 중...";
        try
        {
            var result = await RemoteMount.CheckPersistentMount(p);
            AppStore.Log("PERSISTENT_MOUNT_CHECK " + p.Id + " ok=" + result.Success);
            MessageBox.Show(result.Message, "FileHub AIO 연결 유지 상태",
                MessageBoxButtons.OK, result.Success ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
        }
        catch(Exception ex) { MessageBox.Show(ex.Message,"연결 확인 오류"); }
        finally { RefreshList(); }
    }
    async Task RefreshRootMetadata()
    {
        var p = Selected();
        if (p is null || p.Protocol != "WebDAV")
        {
            MessageBox.Show("WebDAV 프로필을 선택하세요.");
            return;
        }
        status.Text = "NAS 최상위 폴더 목록을 미리 조회 중...";
        try
        {
            var result = await RemoteMount.RefreshRootCache(p);
            AppStore.Log("VFS_ROOT_REFRESH " + p.Id + " ok=" + result.Success);
            MessageBox.Show(result.Message, "NAS 공유 폴더 목록 예열",
                MessageBoxButtons.OK, result.Success ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
        }
        catch(Exception ex) { MessageBox.Show(ex.Message,"목록 예열 오류"); }
        finally { RefreshList(); }
    }
    async Task DiagnoseSelected()
    {
        var p=Selected();
        if(p is null) {MessageBox.Show("먼저 NAS 연결 프로필을 선택하세요.");return;}
        if(p.Protocol=="SMB")
        {
            MessageBox.Show("SMB는 [연결]을 눌러 Windows 네트워크 오류를 확인하세요.");
            return;
        }
        string? password=p.RememberPassword?WindowsCredentials.Read(p.Id):null;
        if(password is null && (!string.IsNullOrWhiteSpace(p.Username) || p.Protocol is "TeraBox" or "MyBox"))
        {
            using var dialog=new PasswordDialog(p.DisplayName);
            if(dialog.ShowDialog(this)!=DialogResult.OK)return;
            password=dialog.Password;
        }
        try
        {
            status.Text=p.DisplayName+" NAS 연결 진단 중...";
            var result=await RemoteMount.Diagnose(p,password);
            AppStore.Log("DIAGNOSE "+p.RemoteDisplay+" | "+result.Message);
            MessageBox.Show(result.Message,result.Success?"연결 진단 통과":"연결 진단 실패",
                MessageBoxButtons.OK,result.Success?MessageBoxIcon.Information:MessageBoxIcon.Warning);
        }
        catch(Exception ex) { MessageBox.Show("진단 오류: "+ex.Message); }
        finally {RefreshList(); RefreshWinFspNotice();}
    }
    async Task CompareExcelRead()
    {
        var p=Selected();
        if (p is null || p.Protocol!="WebDAV" || !IsConnected(p))
        {
            MessageBox.Show("먼저 연결된 WebDAV 프로필을 선택하세요.","Excel 읽기 진단");
            return;
        }
        using var picker=new OpenFileDialog {
            Title="읽기 속도를 비교할 Excel 테스트 파일 선택",
            InitialDirectory=p.Letter.ToUpperInvariant()+@":\",
            Filter="Excel 파일 (*.xlsx;*.xlsm;*.xls)|*.xlsx;*.xlsm;*.xls|모든 파일 (*.*)|*.*",
            CheckFileExists=true, Multiselect=false, RestoreDirectory=true
        };
        if (picker.ShowDialog(this)!=DialogResult.OK)return;
        string? password=p.RememberPassword?WindowsCredentials.Read(p.Id):null;
        if (password is null && !string.IsNullOrWhiteSpace(p.Username))
        {
            using var dialog=new PasswordDialog(p.DisplayName);
            if(dialog.ShowDialog(this)!=DialogResult.OK)return;
            password=dialog.Password;
        }
        try
        {
            status.Text="Excel 파일 읽기 비교 중... (파일을 변경하지 않습니다)";
            string report=await OfficeReadProbe.Compare(p,picker.FileName,password);
            AppStore.Log("EXCEL_READ_DIAGNOSTIC "+p.Id+" "+report.Replace('\n',' '));
            MessageBox.Show(report,"Excel 드라이브 / WebDAV 직접 읽기 비교",
                MessageBoxButtons.OK,MessageBoxIcon.Information);
        }
        catch(Exception ex)
        {
            AppStore.Log("EXCEL_READ_DIAGNOSTIC_ERROR "+p.Id+" "+ex.Message);
            MessageBox.Show(ex.Message,"Excel 읽기 검사 실패",
                MessageBoxButtons.OK,MessageBoxIcon.Warning);
        }
        finally {RefreshList();}
    }
    async Task CheckWebDavStandardLock()
    {
        var p=Selected();
        if (p is null || p.Protocol!="WebDAV")
        {
            MessageBox.Show("검사할 WebDAV NAS 프로필을 선택하세요.","WebDAV 표준 잠금 검사");
            return;
        }
        if (MessageBox.Show(
            "선택한 WebDAV 공유 폴더에 임의의 작은 테스트 파일을 만들어\n" +
            "LOCK → 다른 연결의 무토큰 쓰기 시도 → UNLOCK → 삭제를 검사합니다.\n\n" +
            "NAS에 기존 문서를 수정하지 않지만 서버가 UNLOCK을 지원하지 않으면\n" +
            "테스트 파일이 잠금 만료까지 남을 수 있습니다.\n" +
            "실제 문서의 두 PC 자동 잠금 완료를 의미하지 않습니다.\n\n진행할까요?",
            "WebDAV LOCK/UNLOCK 지원 검사",MessageBoxButtons.YesNo,MessageBoxIcon.Question)
            !=DialogResult.Yes) return;
        string? password=p.RememberPassword?WindowsCredentials.Read(p.Id):null;
        if(password is null && !p.IsAnonymous)
        {
            using var d=new PasswordDialog(p.DisplayName);
            if(d.ShowDialog(this)!=DialogResult.OK)return;
            password=d.Password;
        }
        try
        {
            status.Text="WebDAV LOCK/UNLOCK 교차 쓰기 검사 중...";
            var result=await WebDavLockProbe.TestProfile(p,password??"");
            string headline=result.Success
                ? "서버 WebDAV 잠금 프로토콜 및 무토큰 쓰기 차단 확인"
                : "WebDAV 잠금 완전 지원을 확인하지 못함";
            string report=headline+"\n\n"+result.Detail+"\n\n" +
                (result.CleanupSucceeded ? "임시 파일 정리 확인" :
                    "임시 파일 정리가 확인되지 않았습니다. NAS 관리자 확인 필요");
            AppStore.Log("WEBDAV_LOCK_RFC4918 profile="+p.Id+
                " support="+result.Supported+" enforced="+result.Enforced+
                " token-write="+result.AuthorizedWrite+
                " unlock="+result.Released+" cleanup="+result.CleanupSucceeded);
            MessageBox.Show(report,"WebDAV 표준 잠금 검사",
                MessageBoxButtons.OK,result.Success?MessageBoxIcon.Information:MessageBoxIcon.Warning);
        }
        catch(Exception ex)
        {
            AppStore.Log("WEBDAV_LOCK_RFC4918_FAIL "+p.Id+" "+ex.Message);
            MessageBox.Show(ex.Message,"WebDAV 표준 잠금 검사 실패");
        }
        finally {RefreshList();}
    }
    async Task CheckWebDavSave()
    {
        var p=Selected();
        if(p is null) { MessageBox.Show("연결 프로필을 선택하세요."); return; }
        if(p.Protocol!="WebDAV")
        {
            MessageBox.Show("이 검사는 WebDAV 연결에서만 사용할 수 있습니다.");
            return;
        }
        if(MessageBox.Show(
            "WebDAV 공유 폴더에 임의 이름의 작은 임시 테스트 파일을 만들고\n" +
            "PUT → MOVE(이름 변경) → GET(내용 확인) 후 삭제합니다.\n" +
            "기존 문서는 변경하지 않습니다. 쓰기/삭제 권한이 필요합니다.\n\n검사를 진행할까요?",
            "WebDAV Excel 저장 호환성",MessageBoxButtons.YesNo,MessageBoxIcon.Question)
            !=DialogResult.Yes)return;
        string? password=p.RememberPassword?WindowsCredentials.Read(p.Id):null;
        if(password is null && !p.IsAnonymous)
        {
            using var d=new PasswordDialog(p.DisplayName);
            if(d.ShowDialog(this)!=DialogResult.OK)return;
            password=d.Password;
        }
        try
        {
            status.Text="WebDAV 임시 파일 → 이름 변경 → 내용 검증 중...";
            var result=await WebDavSaveProbe.TestProfile(p,password??"");
            AppStore.Log("WEBDAV_SAVE_PROBE "+p.Id+" "+result.Message);
            MessageBox.Show(result.Message,
                result.Success?"WebDAV 저장 검사 통과":"WebDAV 저장 검사 실패",
                MessageBoxButtons.OK,result.Success?MessageBoxIcon.Information:MessageBoxIcon.Warning);
        }
        catch(Exception ex)
        {
            AppStore.Log("WEBDAV_SAVE_PROBE FAIL "+p.Id+" "+ex.Message);
            MessageBox.Show(ex.Message,"WebDAV 저장 검사 실패");
        }
        finally { RefreshList(); }
    }
    async Task CheckUploadStatus()
    {
        var p=Selected();
        if(p is null || p.Protocol=="SMB")
        {
            MessageBox.Show("SMB가 아닌 연결된 NAS를 선택하세요.");
            return;
        }
        var result=await RemoteMount.GetTransferStatus(p);
        AppStore.Log("UPLOAD_STATUS "+p.Id+" "+result.Message);
        if (!IsDisposed && !Disposing && Selected()?.Id == p.Id)
            PresentTransferStatus(p, result);
        MessageBox.Show(result.Message,
            result.Success?"업로드 대기 없음":"업로드 미완료 또는 오류",
            MessageBoxButtons.OK,result.Success?MessageBoxIcon.Information:MessageBoxIcon.Warning);
    }

    private static string NormalTransferFooter() =>
        $"{(RemoteMount.HasWinFsp ? "● WinFsp 정상" : "● WinFsp 설치 필요")}    ·    " +
        "NAS 연결 및 파일 잠금 상태";

    // This footer owns only the upload text it last wrote. A completed
    // upload must not overwrite PC update downloads or other diagnostics.
    private void ClearTransferWarning()
    {
        if (lastTransferWarning is not null && status.Text == lastTransferWarning)
            status.Text = NormalTransferFooter();
        lastTransferWarning = null;
        lastTransferWarningProfile = null;
    }

    private void PresentTransferStatus(NasProfile profile, OperationResult result)
    {
        if (result.Success)
        {
            ClearTransferWarning();
            return;
        }
        string next = profile.DisplayName + " | " +
            result.Message.Replace('\r', ' ').Replace('\n', ' ');
        if (status.Text != next) status.Text = next;
        lastTransferWarning = next;
        lastTransferWarningProfile = profile.Id;
    }

    private void RefreshSelectedTransferStatus()
    {
        if (lastTransferWarningProfile != selectedId)
            ClearTransferWarning();
        if (IsHandleCreated && !IsDisposed && !Disposing)
            _ = UpdateTransferIndicator();
    }

    async Task UpdateTransferIndicator()
    {
        if (transferStatusRefreshing)
        {
            transferStatusRefreshAgain = true;
            return;
        }
        if (IsDisposed || Disposing || shutdownRequested) return;
        transferStatusRefreshing = true;
        try
        {
            do
            {
                transferStatusRefreshAgain = false;
                var p = Selected();
                if (p is null || p.Protocol == "SMB" || !IsConnected(p))
                {
                    ClearTransferWarning();
                    continue;
                }
                try
                {
                    if (RemoteMount.GetIntegrityAlert(p.Id) is { } critical &&
                        integrityNoticesShown.Add(p.Id))
                    {
                        tray.ShowBalloonTip(15000, "FileHub AIO: 파일 저장 무결성 경고",
                            critical, ToolTipIcon.Error);
                    }
                    var result = await RemoteMount.GetTransferStatus(p);
                    if (IsDisposed || Disposing || shutdownRequested) break;
                    if (Selected()?.Id != p.Id)
                    {
                        // The selected NAS changed during the network probe.
                        // Never render the old drive's result on the new card.
                        transferStatusRefreshAgain = true;
                        continue;
                    }
                    PresentTransferStatus(p, result);
                }
                catch (Exception ex)
                {
                    AppStore.Log("UPLOAD_STATUS_REFRESH " + ex.GetType().Name);
                }
            }
            while (transferStatusRefreshAgain && !IsDisposed && !shutdownRequested);
        }
        finally
        {
            transferStatusRefreshing = false;
        }
    }
    internal void VerifyTransferFooterForUiTest()
    {
        string savedText = status.Text;
        Guid? savedSelection = selectedId;
        string? savedWarning = lastTransferWarning;
        Guid? savedWarningProfile = lastTransferWarningProfile;
        try
        {
            var profile = cfg.Profiles.First(p => p.Protocol != "SMB");
            selectedId = profile.Id;
            PresentTransferStatus(profile, OperationResult.Fail(
                "업로드 대기 1개 / 전송 중 0개 / 실패 0개"));
            if (!status.Text.Contains("업로드 대기 1개"))
                throw new InvalidOperationException("Pending upload was not shown");

            // Reported regression: rclone becomes idle but stale warning remains.
            PresentTransferStatus(profile, OperationResult.Ok(
                "업로드 대기 0개 / 전송 중 0개 / 실패 0개"));
            if (status.Text != NormalTransferFooter() ||
                lastTransferWarning is not null)
                throw new InvalidOperationException("Completed upload warning remained");

            PresentTransferStatus(profile, OperationResult.Fail(
                "업로드 대기 1개 / 전송 중 0개 / 실패 0개"));
            status.Text = "PC 업데이트 파일 다운로드 중...";
            PresentTransferStatus(profile, OperationResult.Ok(
                "업로드 대기 0개 / 전송 중 0개 / 실패 0개"));
            if (status.Text != "PC 업데이트 파일 다운로드 중...")
                throw new InvalidOperationException(
                    "Completed upload overwrote unrelated operation");

            PresentTransferStatus(profile, OperationResult.Fail(
                "업로드 대기 1개 / 전송 중 0개 / 실패 0개"));
            selectedId = cfg.Profiles.First(p => p.Id != profile.Id).Id;
            ClearTransferWarning();
            if (status.Text != NormalTransferFooter())
                throw new InvalidOperationException(
                    "Old drive warning survived profile switch");
            if (uploadStatusTimer.Interval != 5000 || timer.Interval != 30000)
                throw new InvalidOperationException(
                    "Wrong upload refresh or reconnection interval");
            Console.WriteLine("UPLOAD_FOOTER_LIVE_REFRESH_UI_POLICY_PASS");
        }
        finally
        {
            selectedId = savedSelection;
            status.Text = savedText;
            lastTransferWarning = savedWarning;
            lastTransferWarningProfile = savedWarningProfile;
        }
    }

    async Task InstallWinFsp()
    {
        var installed = WinFspUpgradePolicy.InstalledVersion();
        // MSI is NOT a safe downgrade mechanism for a shared kernel driver.
        // RaiDrive and other WinFsp consumers may have active mount handles.
        if (RemoteMount.HasWinFsp && WinFspUpgradePolicy.IsKnownBeta(installed))
        {
            MessageBox.Show(this,
                "WinFsp " + installed + " (2026 Beta)가 설치되어 있습니다.\n\n" +
                "드라이버 버전이 베타라는 사실만으로 연결 오류가 있는 것은 아닙니다.\n" +
                "RaiDrive와 FileHub의 연결이 정상이라면 변경할 필요가 없습니다.\n\n" +
                "FileHub 패키지에는 2025 정식판이 포함되어 있지만,\n" +
                "다른 프로그램이 사용하는 공용 시스템 드라이버를 강제로\n" +
                "내리거나 덮어쓰지 않습니다. 실제 문제가 재발하면\n" +
                "연결 로그와 설치 상태를 먼저 확인하세요.",
                "WinFsp 설치 버전 확인",
                MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }
        if(RemoteMount.HasWinFsp && !WinFspUpgradePolicy.UpgradeAvailable(installed))
        {
            MessageBox.Show("WinFsp " + installed + "이(가) 설치되어 있으며 " +
                "FileHub 포함 안정판(2025)과 같거나 더 최신입니다.\n" +
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
                "포함된 WinFsp: " + WinFspUpgradePolicy.PackageVersion + " (2025 정식판)\n\n" +
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
    async Task Perform(NasProfile p,bool connect,string? password,bool showError)
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
                : (connect?await RemoteMount.Connect(p,password):await RemoteMount.Disconnect(p));
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
                    if (p.Protocol != "SMB")
                        PostExplorerUpdate(p, mounted: true);
                }
                else
                {
                    ExplorerDriveIcon.Restore(p);
                    if (p.Protocol != "SMB")
                        PostExplorerUpdate(p, mounted: false);
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
                }
                else
                {
                    if (RemoteMount.DriveLetterExists(p.Letter)) return;
                    ExplorerDriveIcon.NotifySafeUnmounted(p);
                }
                shellRefresh.Request(
                    (mounted ? "FAST_CONNECT_" : "FAST_DISCONNECT_") +
                    p.Id.ToString("N")[..8]);
                if (mounted && p.Protocol is ("MyBox" or "OneDrive"))
                    _ = RefreshCloudLabelWhenSettled(p);
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
        if (shutdownRequested || connectBatchRunning) return;
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
    async Task CleanupOldNetworkLocation()
    {
        var p = Selected();
        if (p is null || p.Protocol == "SMB") return;
        if (RemoteMount.HasManagedRecord(p) || IsConnected(p))
        {
            MessageBox.Show(this,
                "이 연결이 아직 실행 중입니다. 먼저 파일을 저장하고" +
                " [연결 해제]로 업로드 완료를 확인하세요.",
                "연결 실행 중", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        if (p.UseLockBridge &&
            (await BridgeOwnership.ProbeAsync(p.Id)).IsOccupied)
        {
            MessageBox.Show(this,
                "이전 WinFsp 잠금 브리지가 아직 실행 중입니다." +
                " 문서를 닫고 이전 프로그램에서 정상 종료한 후 다시 시도하세요.",
                "잠금 브리지 실행 중", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        // Old versions can leave a process without its GUI. Check this
        // profile's GUID instead of blocking because ANY unrelated rclone
        // process (including a healthy RaiDrive/FileHub AIO) is running.
        if (NetworkDriveResidueCleaner.OldBackendMayStillOwn(p))
        {
            MessageBox.Show(this,
                "이 연결의 이전 rclone/WinFsp 프로세스가 살아 있거나" +
                " 프로세스 소유권을 안전하게 확인할 수 없습니다.\n" +
                "다른 프로그램과 파일 전송을 건드리지 않도록 정리를 중단했습니다.",
                "업로드 안전 확인 필요", MessageBoxButtons.OK,
                MessageBoxIcon.Warning);
            return;
        }
        if (RemoteMount.HasVerifiedOtherVendorMapping(p))
        {
            MessageBox.Show(this,
                p.Letter + ": 드라이브는 다른 프로그램에서 사용 중입니다." +
                " RaiDrive 등 타사 연결은 제거하지 않습니다.",
                "다른 드라이브 보호", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        if (MessageBox.Show(this,
            p.DisplayName + " (" + p.Letter + ":)의 끊어진 Windows 네트워크 위치만 정리합니다.\n\n" +
            "NAS 서버 파일이나 Excel 문서, 캐시는 삭제하지 않습니다.\n" +
            "이 프로필의 저장된 연결 및 탐색기 기록을 정리합니다.\n계속할까요?",
            "끊어진 네트워크 위치 정리", MessageBoxButtons.YesNo,
            MessageBoxIcon.Question) != DialogResult.Yes) return;
        var result = NetworkDriveResidueCleaner.AfterSafeStop(p);
        bool free = !RemoteMount.DriveLetterExists(p.Letter);
        AppStore.Log("MANUAL_NETWORK_GHOST_CLEANUP " + p.Id + " " +
            result.Message + " letterFree=" + free);
        RefreshList();
        MessageBox.Show(this, result.Message + (free
            ? "\n탐색기의 네트워크 위치 목록에서도 제거됐는지 확인하세요."
            : "\n드라이브 문자가 아직 남아 있습니다. 강제 종료하지 않았습니다."),
            "네트워크 위치 정리 결과", MessageBoxButtons.OK,
            result.Success && free ? MessageBoxIcon.Information : MessageBoxIcon.Warning);
    }
    void OpenDrive()
    {
        var p=Selected();if(p is null)return;
        if(!IsConnected(p)){MessageBox.Show("먼저 연결하세요.");return;}
        Process.Start(new ProcessStartInfo("explorer.exe",p.Letter+":\\") { UseShellExecute=true });
    }
    async Task LockCheck()
    {
        var p=Selected();if(p is null)return;
        if(!IsConnected(p)){MessageBox.Show("먼저 연결하세요.");return;}
        if(p.Protocol!="SMB" && !p.AllowUnprotectedWrites && !p.UseLockBridge)
        {
            MessageBox.Show("읽기 전용 드라이브에서는 임시 파일 잠금 검사를 할 수 없습니다.\n" +
                "원격 NAS 잠금은 [서버 잠금 검사]를 사용하세요.");
            return;
        }
        if(MessageBox.Show("공유 루트에 임시 파일을 생성하여 배타적 파일 잠금을 진단합니다. 계속할까요?","잠금 진단",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;
        var result=await Task.Run(()=>WindowsShare.ProbeFileLock(p.Letter+":\\"));
        AppStore.Log("LOCK TEST "+p.RemoteDisplay+" | "+result.Message);
        MessageBox.Show(result.Message+"\n\n주의: 이 검사는 같은 PC의 배타적 파일 핸들만 검사합니다.\nWebDAV/SFTP/FTP 다중 PC 잠금은 별도 검증이 필요합니다.","잠금 진단");
    }
    async Task ShowWebDavLockStatus()
    {
        var p = Selected();
        if (p is null || p.Protocol != "WebDAV") return;
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
                   "서버의 잠금 충돌까지 보려면 […] → [서버 잠금 충돌 검사]를 실행하세요.\n" +
                   "실제 2대 PC 동시 편집 차단은 별도로 확인해야 합니다.";
        AppStore.Log("WEBDAV_LOCK_STATUS profile=" + p.Id +
            " enabled=" + p.UseLockBridge + " mounted=" + IsConnected(p));
        MessageBox.Show(this, message, "WebDAV 파일잠금 상태",
            MessageBoxButtons.OK, p.UseLockBridge && IsConnected(p) ?
                MessageBoxIcon.Information : MessageBoxIcon.Warning);
    }
    async Task RemoteLockCheck()
    {
        var p=Selected();if(p is null)return;
        if(p.Protocol=="SMB")
        {
            MessageBox.Show("SMB는 NAS 서버/Windows 파일 잠금을 사용합니다.\n2대 PC에서 동일 Excel 파일을 열어 검사하세요.");
            return;
        }
        if (p.Protocol == "WebDAV" && !p.UseLockBridge)
        {
            MessageBox.Show(this, "현재 프로필에서 [파일잠금] 모드가 꺼져 있습니다.\n" +
                "먼저 드라이브를 안전하게 해제한 후 연결 설정에서 [파일잠금]을 선택하세요.",
                "WebDAV 파일잠금 비활성", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }
        if(MessageBox.Show("서버에 임시 .nasmount.lock 폴더를 생성해 중복 획득이 차단되는지 검사합니다.\n" +
            "검사가 성공하면 소유권을 확인하고 이 테스트 폴더만 삭제합니다.\n" +
            "이 검사는 실제 Excel 파일을 수정하지 않으며 Office 자동 잠금을 증명하지 않습니다.\n진행할까요?",
            "서버 잠금 검사",MessageBoxButtons.YesNo)!=DialogResult.Yes)return;
        string? password=p.RememberPassword?WindowsCredentials.Read(p.Id):null;
        if(password is null && !p.IsAnonymous)
        {
            using var dialog=new PasswordDialog(p.DisplayName);
            if(dialog.ShowDialog(this)!=DialogResult.OK)return;
            password=dialog.Password;
        }
        try
        {
            var pw=password??"";
            status.Text="서버 잠금 검사 중...";
            string result=await Task.Run(()=>CooperativeLocks.Test(p,pw));
            AppStore.Log("SERVER LOCK TEST "+p.RemoteDisplay+" | "+result);
            MessageBox.Show(result,"서버 잠금 진단");
        }
        catch(Exception ex)
        {
            AppStore.Log("SERVER LOCK TEST ERROR "+p.RemoteDisplay+" | "+ex.Message);
            MessageBox.Show(ex.Message+"\n\n실패 시 안전을 위해 잠금 폴더가 남아 있을 수 있습니다.","서버 잠금 진단 실패");
        }
        finally { RefreshList(); }
    }
    void OpenLogs()
    {
        string folder=Path.Combine(AppStore.DirectoryPath,"logs");Directory.CreateDirectory(folder);
        Process.Start(new ProcessStartInfo("explorer.exe",folder){UseShellExecute=true});
    }

    // This deliberately does NOT wait for Perform's profile gate: a hung
    // WinFsp/native WebDAV connection can own that semaphore indefinitely.
    // Only the explicit emergency menu may stop owned child processes.
    // A user pressing Exit is not asking to keep the GUI open until every
    // broken WinFsp call recovers. Failed NEVER-mounted connections are
    // forcibly stopped, without an upload warning. A previously mounted
    // session gets a short graceful disconnect; if it does not respond,
    // leave its child + cache intact and always exit the GUI.
    private async Task ExitProgramFast()
    {
        if (shutdownRequested || exiting) return;
        shutdownRequested = true;
        timer.Stop();
        uploadStatusTimer.Stop();
        status.Text = "프로그램 종료 중...";
        var profiles = cfg.Profiles.Where(p => p.Protocol != "SMB").ToArray();
        foreach (var p in profiles)
            if (preparingConnections.Contains(p.Id) &&
                !RemoteMount.IsMounted(p))
                RemoteMount.RequestEmergency(p.Id);
        try
        {
            var stops = profiles.Select(async p =>
            {
                if (RemoteMount.FailedBeforeFirstMount(p))
                {
                    var force = await Task.Run(() =>
                        RemoteMount.StopFailedBeforeFirstMountAsync(p));
                    AppStore.Log("EXIT_FAILED_CONNECT_FORCE_STOP id=" + p.Id +
                        " success=" + force.Success);
                    return;
                }
                if (!RemoteMount.HasManagedRecord(p)) return;
                var gate = GateFor(p);
                if (!await gate.WaitAsync(TimeSpan.FromMilliseconds(350)))
                {
                    AppStore.Log("EXIT_BUSY_MOUNT_PRESERVED id=" + p.Id);
                    return;
                }
                try
                {
                    var outcome = await Task.Run(() => RemoteMount.Disconnect(p));
                    AppStore.Log("EXIT_HEALTHY_MOUNT_SAFE_STOP id=" + p.Id +
                        " success=" + outcome.Success);
                    // Avoid a potentially blocking Explorer Shell COM call
                    // on the UI thread during the guaranteed Exit path.
                }
                catch (Exception ex)
                {
                    AppStore.Log("EXIT_MOUNT_RECOVERY_DEFERRED id=" + p.Id +
                        " error=" + ex.GetType().Name);
                }
                finally { gate.Release(); }
            }).ToArray();
            // Whole program budget, not one 8-second wait per drive.
            await Task.WhenAll(stops).WaitAsync(TimeSpan.FromSeconds(8));
        }
        catch (Exception ex)
        {
            AppStore.Log("EXIT_FAST_UNCERTAIN_BACKENDS_PRESERVED type=" +
                ex.GetType().Name);
        }
        finally
        {
            AppStore.Log("EXIT_FAST_GUI_TERMINATE reason=user_exit");
            exiting = true;
            tray.Visible = false;
            Environment.Exit(0);
        }
    }

    async Task ExitSafely(PcUpdateService.Staged? installUpdate = null)
    {
        if (shutdownRequested) return;
        shutdownRequested = true;
        timer.Stop();
        var heldGates = new List<SemaphoreSlim>();
        try
        {
            // The timer or a user click may already have started connecting.
            // Wait for EACH already-running profile operation. A shared
            // global gate would serialize all startup and shutdown work.
            // The 45-second limit is GLOBAL, not 45 seconds per profile.
            status.Text = "자동 연결 중지 · 진행 중인 연결 작업 확인 중...";
            DateTime deadline = DateTime.UtcNow.AddSeconds(45);
            foreach (var p in cfg.Profiles.ToArray())
            {
                var remaining = deadline - DateTime.UtcNow;
                var currentGate = GateFor(p);
                if (remaining <= TimeSpan.Zero ||
                    !await currentGate.WaitAsync(remaining))
                {
                    AppStore.Log("SHUTDOWN_PROFILE_GATE_BUSY_TIMEOUT " + p.Id);
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
            if (running.Length > 0 &&
                MessageBox.Show(
                    "원격 드라이브 " + running.Length + "개가 이 프로그램에서 실행 중입니다.\n" +
                    "열린 문서를 모두 저장하고 닫았나요?\n\n" +
                    "종료 전에 업로드 완료 및 WinFsp 드라이브 문자 해제까지 확인합니다.\n" +
                    "안전하게 해제할 수 없는 연결이 있으면 프로그램 종료를 취소합니다.\n" +
                    "SMB 및 다른 프로그램의 드라이브는 건드리지 않습니다.",
                    "FileHub AIO 안전 종료", MessageBoxButtons.YesNo,
                    MessageBoxIcon.Question) != DialogResult.Yes)
                return;
            status.Text = "연결 " + running.Length + "개 동시 안전 해제 중...";
            var disconnectTimer = Stopwatch.StartNew();
            AppStore.Log("BATCH_SAFE_DISCONNECT_PARALLEL_START count=" + running.Length);
            // No forced unmounts: RemoteMount.Disconnect independently
            // refuses to stop any volume with dirty uploads, open Office
            // bridge handles or unverifiable WinFsp release. The others can
            // finish without waiting behind that particular profile.
            var outcomes = await Task.WhenAll(running.Select(async p =>
            {
                try { return (Profile: p, Result: await RemoteMount.Disconnect(p)); }
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
            if (failed.Count > 0)
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
            // We do not silently kill older rclone/WinFsp processes from
            // another FileHub AIO instance. They may contain dirty VFS data.
            if (installUpdate is not null)
            {
                // The helper is launched only after every managed remote
                // volume passes the existing safe-disconnect checks.
                // An active Office handle or dirty VFS upload must never be
                // bypassed merely because the user requested an update.
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
