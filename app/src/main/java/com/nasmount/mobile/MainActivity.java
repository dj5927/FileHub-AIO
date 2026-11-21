package com.nasmount.mobile;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.webkit.MimeTypeMap;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import java.io.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MainActivity extends Activity {
    private OtaUpdater otaUpdater;
    private static final int REQ_SD = 501;
    private static final int REQ_UPLOAD = 502;
    private static final int REQ_MEDIA = 503;
    private static final int REQ_FOLDER = 504;
    private static final int REQ_SETTINGS = 505;
    private static final int REQ_TEXT_EDITOR = 506;
    private static final int REQ_APK_INSTALL_AUTH = 507;
    private static final int REQ_OFFICE_EDITOR = 508;
    private static final int REQ_MUSIC_NOTIFICATIONS = 509;
    private static final int REQ_TRANSFER_NOTIFICATIONS = 510;
    static final int REQ_LAN_QR_SCAN = 511;
    private static final int REQ_GOOGLE_DRIVE_SAF = 512;
    private static final String GOOGLE_DRIVE_DOCUMENTS_AUTHORITY =
            "com.google.android.apps.docs.storage";
    private static final int ICON_BLUE = 0xFF4989CF;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService previewWorker = Executors.newFixedThreadPool(2);
    private final ExecutorService subtitleProbeWorker =
            Executors.newSingleThreadExecutor();
    private final ExecutorService recursiveSearchWorker=
            Executors.newSingleThreadExecutor();
    private Future<?> recursiveSearchTask;
    private volatile int recursiveSearchGeneration;
    private final Set<String> videoHeadersProbed = ConcurrentHashMap.newKeySet();
    private final Set<String> localSubtitleProbed = ConcurrentHashMap.newKeySet();
    private final FolderSessionCache folderCache = new FolderSessionCache();
    private SecureStore store;
    private boolean tvDevice;
    private TvPairingServer tvPairingServer;
    private AlertDialog tvPairingDialog;
    private TvPairingUi pairingUi;
    private SettingsBackupUi settingsBackupUi;
    private ReaderFolders readerFolders;
    private List<ReaderFolders.Scope> folderPurposeReaders = Collections.emptyList();
    private Set<String> folderPurposeVideos = Collections.emptySet();
    private DrawerSectionState drawerSections;
    private FrameLayout container;
    private LinearLayout screenRoot, page;
    private View statusBarBackground;
    private View navigationBarBackground;
    private int statusBarInsetPx;
    private int navigationBarInsetPx;
    private String currentScreen = "home";
    private String currentTitle = "FileHub AIO";
    private File localFolder;
    private DocumentFile safFolder;
    private String safRelative = "/";
    private String activeTreeUri = "";
    private String activeTreeLabel = "SD 저장소";
    private boolean sdPermissionRequestPending;
    private Models.RemoteProfile remote;
    private String remoteFolder = "/";
    private String mediaKind;
    private String assignedMode;
    private AssignedFolderBackBoundary assignedBackBoundary;
    private boolean drawerShown;
    private FrameLayout drawerLayer;
    private View drawerShade;
    private View drawerPanel;
    private ScrollView currentScroll;
    private int renderGeneration;
    private static final int LARGE_DIRECTORY_PAGE_SIZE=
            LargeFolderPagePolicy.ROWS_PER_PAGE;
    private TextView nextDirectoryPage;
    private int gridCellIndex;
    private LinearLayout activeGridRow;
    private final List<LazyUiJob> lazyJobs = new ArrayList<>();
    private final List<SearchResult> currentSearch = new ArrayList<>();
    private boolean completeFolderSearchIndexed;
    private VideoEpisodeQueue visibleEpisodeQueue;
    private Map<String, VideoEpisodeQueue> mediaFolderEpisodeQueues =
            Collections.emptyMap();
    private int layoutMode = FileViewOptions.LIST;
    private int sortMode = FileViewOptions.NAME_ASC;
    private boolean showHidden = true; // Older versions showed dot files.
    private boolean showThumbnails = false;
    private boolean showVideoThumbnails = true;
    private Map<String, Set<String>> siblingSubtitleIndex = Collections.emptyMap();
    private boolean refreshAfterVideo;
    private boolean refreshAfterReader;
    private final List<BookSeriesQueue.Volume> visibleComicVolumes=new ArrayList<>();
    // Multi-select is scoped to the visible folder; clipboard survives
    // navigation between local, SD and all connected network drives.
    private final FileSelectionModel<FileTransferEngine.Item> selection =
            new FileSelectionModel<>();
    private final LinkedHashMap<String, FileTransferEngine.Item> visibleFileItems =
            new LinkedHashMap<>();
    private final LinkedHashMap<String, View> selectableRows =
            new LinkedHashMap<>();
    private List<FileTransferEngine.Item> stagedFiles = Collections.emptyList();
    private boolean stagedMove;
    private Runnable pendingTransferPermission;
    private AlertDialog transferDialog;
    private ProgressBar transferProgressBar;
    private TextView transferName, transferPercent, transferAmount, transferFiles;
    private TextView transferChip;
    private boolean transferHidden = true;
    private boolean transferHadActive;
    private List<FileTransferEngine.Item> transferSourceCache = Collections.emptyList();
    private FileTransferEngine.Destination transferTargetCache;
    private final Handler transferHandler = new Handler(Looper.getMainLooper());
    private final Runnable transferTicker = new Runnable() {
        @Override public void run() {
            updateTransferUi();
            transferHandler.postDelayed(this, 250);
        }
    };
    private LinearLayout normalFileToolbar;
    private LinearLayout selectionToolbar;
    private LinearLayout operationBottomBar;
    private ToolbarIconView toolbarFavorite;
    private File pendingApkInstall;
    private LinearLayout miniPlayerBar;
    private TextView miniTitle,miniToggle;
    private SeekBar miniProgress;
    private boolean miniSeeking;
    private final Handler musicHandler=new Handler(Looper.getMainLooper());
    private final Runnable musicTicker=new Runnable(){
        @Override public void run(){
            refreshMiniPlayer();
            musicHandler.postDelayed(this,850);
        }
    };

    private boolean expandedLayout() {
        Configuration config = getResources().getConfiguration();
        return FoldPlayerLayoutPolicy.isExpanded(
                config.smallestScreenWidthDp, config.screenWidthDp,
                config.screenHeightDp);
    }

    private int currentScreenWidthDp() {
        int configured = getResources().getConfiguration().screenWidthDp;
        return configured > 0 ? configured :
                Math.round(getResources().getDisplayMetrics().widthPixels /
                        getResources().getDisplayMetrics().density);
    }

    @Override public void onCreate(Bundle state) {
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        tvDevice = TvMode.isTv(this);
        if (tvDevice) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        // Android 15 (target 35) enforces edge-to-edge. Explicitly fit our
        // app below the status bar and above the gesture/navigation region.
        // A dark status scrim is drawn *behind* the white system icons.
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29)
            getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode ?
                0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        // The phone appearance flag above must not undo Android TV immersive mode.
        if (tvDevice) getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        store = new SecureStore(this);
        readerFolders = new ReaderFolders(this);
        if (state != null) {
            sdPermissionRequestPending = state.getBoolean("sd_permission_pending", false);
            String queued = state.getString("pending_apk_install", "");
            if (!queued.isEmpty() &&
                    queued.startsWith(new File(getCacheDir(), "apk_install").getAbsolutePath() +
                            File.separator))
                pendingApkInstall = new File(queued);
        }
        android.content.SharedPreferences drawerPrefs = getSharedPreferences(
                DrawerSectionState.PREFS_NAME, MODE_PRIVATE);
        drawerSections = new DrawerSectionState(new DrawerSectionState.Storage() {
            @Override public boolean read(String key, boolean defaultValue) {
                return drawerPrefs.getBoolean(key, defaultValue);
            }
            @Override public void write(String key, boolean value) {
                // Persist synchronously so a fast app restart retains the
                // user's exact collapsed/expanded choice.
                drawerPrefs.edit().putBoolean(key, value).commit();
            }
        });
        android.content.SharedPreferences viewSettings = getSharedPreferences(
                "file_view", MODE_PRIVATE);
        layoutMode = viewSettings.getInt("layout", FileViewOptions.LIST);
        sortMode = viewSettings.getInt("sort", FileViewOptions.NAME_ASC);
        showHidden = viewSettings.getBoolean("hidden", true);
        showThumbnails = viewSettings.getBoolean("thumbnails", false);
        showVideoThumbnails = viewSettings.getBoolean("video_thumbnails", true);
        container = new FrameLayout(this);
        container.setBackgroundColor(Ui.BG);
        ViewCompat.setOnApplyWindowInsetsListener(container, (view, insets) -> {
            Insets system = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() |
                            WindowInsetsCompat.Type.displayCutout());
            Insets navigation = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() |
                            WindowInsetsCompat.Type.displayCutout());
            statusBarInsetPx = system.top;
            navigationBarInsetPx = navigation.bottom;
            updateSystemBarLayout();
            return insets;
        });
        setContentView(container);
        showHome();
        otaUpdater = new OtaUpdater(this);
        otaUpdater.startupCheck();
        handleTvPairIntent(getIntent());
    }

    private void handleTvPairIntent(Intent intent) {
        if (intent == null || intent.getData() == null ||
                !"filehubaio".equalsIgnoreCase(intent.getData().getScheme())) return;
        String host=intent.getData().getHost();
        String value = intent.getData().toString();
        intent.setData(null);
        if("relay".equalsIgnoreCase(host))
            toast("인터넷 QR은 더 이상 지원하지 않습니다. LAN QR을 이용해 주세요.");
        else if("pair".equalsIgnoreCase(host))
            showSendToTv(value);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("sd_permission_pending", sdPermissionRequestPending);
        if (pendingApkInstall != null)
            outState.putString("pending_apk_install",
                    pendingApkInstall.getAbsolutePath());
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Fold/unfold retains the same active NAS connection and in-memory
        // session caches instead of recreating the entire Activity.
        if (drawerShown) closeDrawer();
        refreshCurrent();
    }

    private void updateSystemBarLayout() {
        if (screenRoot != null && screenRoot.getParent() == container) {
            FrameLayout.LayoutParams layout =
                    (FrameLayout.LayoutParams) screenRoot.getLayoutParams();
            if (layout.topMargin != statusBarInsetPx ||
                    layout.bottomMargin != navigationBarInsetPx) {
                layout.topMargin = statusBarInsetPx;
                layout.bottomMargin = navigationBarInsetPx;
                screenRoot.setLayoutParams(layout);
            }
        }
        if (statusBarBackground != null &&
                statusBarBackground.getParent() == container) {
            FrameLayout.LayoutParams top = (FrameLayout.LayoutParams)
                    statusBarBackground.getLayoutParams();
            if (top.height != statusBarInsetPx) {
                top.height = statusBarInsetPx;
                statusBarBackground.setLayoutParams(top);
            }
        }
        if (navigationBarBackground != null &&
                navigationBarBackground.getParent() == container) {
            FrameLayout.LayoutParams bottom = (FrameLayout.LayoutParams)
                    navigationBarBackground.getLayoutParams();
            if (bottom.height != navigationBarInsetPx) {
                bottom.height = navigationBarInsetPx;
                navigationBarBackground.setLayoutParams(bottom);
            }
        }
    }

    @Override public void onDestroy() {
        if(otaUpdater!=null)otaUpdater.close();
        stopTvPairing();
        if(settingsBackupUi!=null)settingsBackupUi.shutdown();
        transferHandler.removeCallbacks(transferTicker);
        if (transferDialog != null) transferDialog.dismiss();
        musicHandler.removeCallbacks(musicTicker);
        worker.shutdownNow();
        previewWorker.shutdownNow();
        subtitleProbeWorker.shutdownNow();
        if(recursiveSearchTask!=null)recursiveSearchTask.cancel(true);
        recursiveSearchWorker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int n) { return Ui.dp(this, n); }
    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
    private String safeError(Throwable error) {
        // Never hide manufacturer-specific diagnostic codes under a generic IOException.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof NasShareApi.Failure)
                return ((NasShareApi.Failure) cause).userMessage();
        }
        if (error instanceof FileTransferEngine.NameConflict)
            return "대상 폴더에 같은 이름의 파일 또는 폴더가 있습니다. 기존 파일을 " +
                    "덮어쓰지 않았습니다. 이름을 변경하거나 다른 폴더를 선택하세요.";
        if (error instanceof FileTransferEngine.BatchFailure) {
            FileTransferEngine.BatchFailure batch =
                    (FileTransferEngine.BatchFailure) error;
            String reason = "";
            for (Throwable cause = error.getCause(); cause != null;
                 cause = cause.getCause()) {
                if (cause instanceof CloudProvider.CloudFailure) {
                    reason = "\n\n오류 상세: " +
                            ((CloudProvider.CloudFailure)cause).userMessage();
                    break;
                }
                if (cause instanceof CloudProvider.CloudApiFailure) {
                    reason = "\n\n오류 상세: " + cause.getMessage();
                    break;
                }
            }
            return batch.total + "개 중 " + batch.completed + "개 완료 후 작업이 중단됐습니다. " +
                    (batch.deleting ? "일부 파일은 삭제됐을 수 있습니다. " :
                            "일부 대상 파일은 복사됐을 수 있습니다. 원본은 확인 전 삭제하지 마세요. ") +
                    "파일 목록을 새로고침하고 저장소 연결·권한·남은 용량을 확인하세요." +
                    reason;
        }
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof CloudProvider.CloudFailure)
                return ((CloudProvider.CloudFailure)cause).userMessage();
            if (cause instanceof CloudProvider.CloudApiFailure)
                return cause.getMessage();
        }
        if (error instanceof RemoteProvider.WebDavHttpFailure) {
            int status = ((RemoteProvider.WebDavHttpFailure) error).status;
            if (status == 401) return "WebDAV 인증 실패 (401). 사용자명과 비밀번호를 확인하세요.";
            if (status == 403) return "WebDAV 접근이 거부됐습니다 (403). NAS 폴더 권한을 확인하세요.";
            if (status == 404) return "WebDAV 폴더가 없습니다 (404). 시작 경로와 WebDAV 주소를 확인하세요.";
            if (status == 405) return "이 주소에서 WebDAV 요청을 지원하지 않습니다 (405). WebDAV 포트와 시작 경로를 확인하세요.";
            if (status == 301 || status == 302)
                return "WebDAV 주소가 변경됐습니다. HTTP/HTTPS와 포트를 확인하세요.";
            if (status == 200)
                return "WebDAV 폴더 목록(207) 대신 일반 응답(200)을 받았습니다. WebDAV 전용 경로인지 확인하세요.";
            return "WebDAV 폴더 요청 실패 (HTTP " + status + ").";
        }
        if (error instanceof javax.net.ssl.SSLException)
            return "WebDAV HTTPS 보안 연결에 실패했습니다. 인증서·포트·HTTPS 설정을 확인하세요.";
        // Never expose protocol URLs, credentials, arbitrary server paths,
        // or SSH diagnostic stack traces in an error dialog.
        for (Throwable e=error; e!=null; e=e.getCause()) {
            if (e instanceof RemoteProvider.UntrustedHostKey)
                return "SFTP 서버의 호스트 키 확인이 필요합니다.";
        }
        if (error instanceof SecurityException)
            return "파일 접근 권한이 없거나 서버 인증이 거부되었습니다.";
        if (error instanceof java.net.SocketTimeoutException)
            return "서버 응답 시간이 초과됐습니다.";
        return "요청을 완료하지 못했습니다. 서버 주소·연결·권한을 확인하세요. (" +
                error.getClass().getSimpleName() + ")";
    }
    private void failure(Throwable error) {
        for (Throwable e=error; e!=null; e=e.getCause()) {
            if (e instanceof RemoteProvider.UntrustedHostKey && remote != null) {
                String fp = ((RemoteProvider.UntrustedHostKey)e).fingerprint;
                new AlertDialog.Builder(this).setTitle("SFTP 서버 신뢰 확인")
                        .setMessage("처음 접속한 서버입니다. 관리자에게 아래 키 지문을 확인하세요.\n\n" +
                                fp + "\n\n확인 후 이 키를 저장하고 연결할까요?")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("확인하고 연결", (dialog, which) -> {
                            remote.fingerprint = fp;
                            try {
                                store.putRemote(remote);
                                openRemote(remote, remoteFolder);
                            } catch (Exception ex) { showError(safeError(ex)); }
                        }).show();
                return;
            }
        }
        showError(safeError(error));
    }
    private void showError(String message) {
        new AlertDialog.Builder(this).setTitle("NAS Mount")
                .setMessage(message).setPositiveButton("확인", null).show();
    }
    interface Job<T> { T execute() throws Exception; }
    private <T> void runJob(String label, Job<T> operation, Consumer<T> onSuccess) {
        LinearLayout box = Ui.horizontal(this);
        box.setPadding(dp(24), dp(20), dp(24), dp(20));
        ProgressBar wheel = new ProgressBar(this);
        box.addView(wheel, Ui.lp(this, 36, 36));
        TextView caption = Ui.text(this, label, 15, Ui.TEXT, false);
        LinearLayout.LayoutParams p = Ui.lp(this, -1, 56);
        p.leftMargin = dp(18);
        box.addView(caption, p);
        AlertDialog waiting = new AlertDialog.Builder(this).setView(box)
                .setCancelable(false).create();
        waiting.show();
        worker.execute(() -> {
            T result;
            Throwable problem = null;
            try { result = operation.execute(); }
            catch (Throwable ex) { result = null; problem = ex; }
            T finalResult = result;
            Throwable finalProblem = problem;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                waiting.dismiss();
                if (finalProblem instanceof NasShareApi.OtpRequired) {
                    showNasOtpPrompt((NasShareApi.OtpRequired) finalProblem,
                            label, operation, onSuccess);
                } else if (finalProblem != null) failure(finalProblem);
                else onSuccess.accept(finalResult);
            });
        });
    }

    private <T> void showNasOtpPrompt(NasShareApi.OtpRequired challenge,
                                      String label, Job<T> operation,
                                      Consumer<T> onSuccess) {
        Models.RemoteProfile profile = challenge.profile;
        if (profile == null || !NasSharePolicy.SYNOLOGY.equals(profile.nasVendor)) {
            showError(challenge.userMessage());
            return;
        }
        LinearLayout panel = Ui.vertical(this);
        panel.setPadding(dp(22), dp(12), dp(22), dp(8));
        TextView hint = Ui.text(this, challenge.rejected ?
                "입력한 코드가 맞지 않습니다. 인증 앱에서 새 6자리 코드를 확인하세요." :
                "Synology DSM에서 2단계 인증을 요청했습니다. Secure SignIn 또는 인증 앱의 6자리 코드를 입력하세요.",
                14, Ui.TEXT, false);
        panel.addView(hint, Ui.lp(this, -1, -2));
        Ui.gap(panel, this, 12);
        EditText otp = new EditText(this);
        otp.setSingleLine(true);
        otp.setHint("6자리 인증코드");
        otp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        otp.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        otp.setAutofillHints((String[]) null);
        panel.addView(otp, Ui.lp(this, -1, 52));
        android.widget.CheckBox trust = new android.widget.CheckBox(this);
        trust.setText("이 기기를 신뢰하여 다음 로그인부터 코드 생략");
        trust.setChecked(false);
        panel.addView(trust, Ui.lp(this, -1, -2));
        TextView safety = Ui.text(this,
                "코드는 저장하지 않습니다. '이 기기 신뢰'를 선택하면 DSM에서 발급한 기기 식별자만 암호화 저장합니다.",
                12, Ui.MUTED, false);
        panel.addView(safety, Ui.lp(this, -1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("DSM 2단계 인증")
                .setView(panel)
                .setNegativeButton("취소", null)
                .setPositiveButton("인증하고 계속", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String code = otp.getText().toString().trim();
                    if (!code.matches("[0-9]{6}")) {
                        otp.setError("숫자 6자리를 입력하세요.");
                        return;
                    }
                    profile.nasOtpCode = code;
                    profile.nasRememberDevice = trust.isChecked();
                    otp.setText("");
                    dialog.dismiss();
                    runJob(label, () -> {
                        T result = operation.execute();
                        try { persistTrustedNasDevice(profile); }
                        catch (Exception optionalSaveError) {
                            // A completed NAS API operation must not be reported as
                            // failed just because optional device trust could not save.
                        }
                        return result;
                    }, onSuccess);
                }));
        dialog.show();
    }

    private void persistTrustedNasDevice(Models.RemoteProfile changed) throws Exception {
        if (changed.nasDeviceId == null || changed.nasDeviceId.isEmpty()) return;
        for (Models.RemoteProfile saved : store.remotes()) {
            if (!saved.id.equals(changed.id)) continue;
            // Only reuse an approved device ID with its exact endpoint/account.
            if (!saved.nasApiBase.equals(changed.nasApiBase) ||
                    !saved.nasApiUser.equals(changed.nasApiUser) ||
                    !saved.nasApiPassword.equals(changed.nasApiPassword)) return;
            saved.nasDeviceId = changed.nasDeviceId;
            store.putRemote(saved);
            return;
        }
    }

    private void render(String title, boolean back, String right, Runnable onRight) {
        currentTitle = title;
        visibleComicVolumes.clear();
        try {
            folderPurposeReaders = readerFolders == null ?
                    Collections.emptyList() : readerFolders.all();
        } catch (Exception badSettings) {
            folderPurposeReaders = Collections.emptyList();
        }
        folderPurposeVideos = new java.util.HashSet<>(
                PlayerSettings.prefs(this).getStringSet(
                        PlayerSettings.HIDE_FOLDERS, Collections.emptySet()));
        selection.clear();
        visibleFileItems.clear();
        selectableRows.clear();
        selectionToolbar = null;
        operationBottomBar = null;
        toolbarFavorite = null;
        renderGeneration++;
        nextDirectoryPage=null;
        lazyJobs.clear();
        currentSearch.clear();
        completeFolderSearchIndexed=false;
        siblingSubtitleIndex = Collections.emptyMap();
        visibleEpisodeQueue = null;
        mediaFolderEpisodeQueues = Collections.emptyMap();
        gridCellIndex = 0;
        activeGridRow = null;
        container.removeAllViews();
        transferChip = null;
        drawerShown = false;
        drawerLayer = null;
        drawerShade = null;
        drawerPanel = null;
        screenRoot = Ui.vertical(this);
        screenRoot.setBackgroundColor(Ui.BG);
        FrameLayout.LayoutParams mainBounds = new FrameLayout.LayoutParams(-1, -1);
        mainBounds.topMargin = statusBarInsetPx;
        mainBounds.bottomMargin = navigationBarInsetPx;
        container.addView(screenRoot, mainBounds);
        // System-bar areas remain their own surfaces. Do not place buttons
        // beneath the clock, cutout or bottom navigation/gesture indicators.
        statusBarBackground = new View(this);
        statusBarBackground.setBackgroundColor(Ui.DARK);
        container.addView(statusBarBackground, new FrameLayout.LayoutParams(
                -1, statusBarInsetPx, Gravity.TOP));
        navigationBarBackground = new View(this);
        navigationBarBackground.setBackgroundColor(Ui.BG);
        container.addView(navigationBarBackground, new FrameLayout.LayoutParams(
                -1, navigationBarInsetPx, Gravity.BOTTOM));
        ViewCompat.requestApplyInsets(container);
        attachTransferChip();
        LinearLayout top = Ui.horizontal(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        normalFileToolbar = top;
        top.setBackgroundColor(Ui.DARK);
        top.setPadding(dp(8), 0, dp(8), 0);
        screenRoot.addView(top, Ui.lp(this, -1,
                FileHubBrandLayout.HEADER_HEIGHT_DP));
        // Keep the menu button in exactly the same position on every screen.
        ToolbarIconView hamburger = new ToolbarIconView(this, ToolbarIconView.MENU);
        top.addView(hamburger, Ui.lp(this, 40, -1));
        hamburger.setContentDescription("왼쪽 탐색 메뉴");
        hamburger.setOnClickListener(v -> showDrawer());
        if (!"home".equals(currentScreen)) {
            ToolbarIconView home = new ToolbarIconView(this, ToolbarIconView.HOME);
            home.setContentDescription("홈으로 이동");
            top.addView(home, Ui.lp(this, 40, -1));
            home.setOnClickListener(v -> showHome());
        }
        LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(0, -1, 1);
        if ("home".equals(currentScreen)) {
            // Exact chosen FileHub AIO typography asset. Keep the original
            // title slot and therefore all toolbar buttons unchanged.
            ImageView wordmark = new ImageView(this);
            wordmark.setImageResource(R.drawable.filehub_aio_wordmark);
            // FIT_START pinned the visible 368x66 artwork to the top of its
            // 90x30dp view. FIT_CENTER aligns the ALPHA CONTENT midpoint
            // with the hamburger glyph (the icon itself is centered).
            wordmark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            wordmark.setAdjustViewBounds(false);
            wordmark.setContentDescription("FileHub AIO 홈");
            LinearLayout brandSlot = Ui.horizontal(this);
            brandSlot.setGravity(Gravity.CENTER_VERTICAL);
            brandSlot.addView(wordmark, Ui.lp(this,
                    FileHubBrandLayout.wordmarkWidthDp(currentScreenWidthDp()), 30));
            top.addView(brandSlot, weight);
        } else {
            TextView titleView = Ui.text(this, title, 19, Color.WHITE, true);
            titleView.setGravity(Gravity.CENTER_VERTICAL);
            titleView.setSingleLine();
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(titleView, weight);
        }
        boolean fileScreen = "local".equals(currentScreen) ||
                "saf".equals(currentScreen) || "remote".equals(currentScreen) ||
                "media".equals(currentScreen);
        if (fileScreen) {
            if ("local".equals(currentScreen) || "saf".equals(currentScreen) ||
                    "remote".equals(currentScreen)) {
                toolbarFavorite = new ToolbarIconView(this, ToolbarIconView.FAVORITE);
                toolbarFavorite.setContentDescription("현재 폴더 즐겨찾기 추가");
                top.addView(toolbarFavorite, Ui.lp(this, 40, -1));
                toolbarFavorite.setOnClickListener(v -> toggleCurrentFolderFavorite());
                updateFavoriteIndicators();
            }
            ToolbarIconView refresh =
                    new ToolbarIconView(this, ToolbarIconView.REFRESH);
            refresh.setContentDescription("현재 폴더 새로고침");
            top.addView(refresh, Ui.lp(this, 40, -1));
            refresh.setOnClickListener(v -> refreshCurrentFolderFromToolbar());
            ToolbarIconView search =
                    new ToolbarIconView(this, ToolbarIconView.SEARCH);
            search.setContentDescription("현재 폴더에서 검색");
            top.addView(search, Ui.lp(this, 40, -1));
            search.setOnClickListener(v -> searchCurrentFolder());
            ToolbarIconView views = new ToolbarIconView(this, ToolbarIconView.VIEW);
            views.setContentDescription("보기 방식 및 정렬 설정");
            top.addView(views, Ui.lp(this, 40, -1));
            views.setOnClickListener(v -> showViewSettings());
            ToolbarIconView actions = new ToolbarIconView(this, ToolbarIconView.MORE);
            actions.setContentDescription("현재 폴더 작업");
            top.addView(actions, Ui.lp(this, 40, -1));
            actions.setOnClickListener(v -> fileActionsForCurrent());
        } else if (right != null) {
            TextView action = Ui.text(this, right, 22, Color.WHITE, false);
            action.setGravity(Gravity.CENTER);
            top.addView(action, Ui.lp(this, 50, -1));
            action.setOnClickListener(v -> onRight.run());
        }
        ScrollView scroll = new ScrollView(this);
        currentScroll = scroll;
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> {
            requestVisibleJobs();
            if(nextDirectoryPage!=null && nextDirectoryPage.getParent()==page &&
                    page.getHeight()>0 &&
                    y+scroll.getHeight()>=page.getHeight()-dp(180)){
                TextView more=nextDirectoryPage;
                nextDirectoryPage=null;
                scroll.post(more::performClick);
            }
        });
        screenRoot.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        page = Ui.vertical(this);
        page.setPadding(dp(16), dp(18), dp(16), dp(36));
        int contentWidthDp = FoldPlayerLayoutPolicy.contentWidthDp(
                expandedLayout(), currentScreenWidthDp());
        scroll.addView(page, new FrameLayout.LayoutParams(
                dp(contentWidthDp), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        page.post(this::requestVisibleJobs);
        if (tvDevice) {
            page.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (page != null && page.isAttachedToWindow())
                    TvMode.decorate(screenRoot,this);
            });
        }
        createMiniPlayerBar();
        updateFileOperationChrome();
    }

    /** Renders only a small number of file/folder rows per UI frame.
     * The next batch is shown on scroll or by clicking its button. */
    private void appendDirectoryPage(List<Models.Entry> entries,int offset,
                                     int generation,
                                     java.util.function.Consumer<Models.Entry> showRow) {
        if(generation!=renderGeneration || isFinishing()||isDestroyed())return;
        int end=LargeFolderPagePolicy.end(entries.size(),offset);
        for(int i=offset;i<end;i++)showRow.accept(entries.get(i));
        if(end<entries.size()){
            TextView more=Ui.action(this,"더 보기  ("+end+" / "+entries.size()+")");
            LinearLayout.LayoutParams lp=Ui.lp(this,-1,49);
            lp.topMargin=dp(9);
            page.addView(more,lp);
            nextDirectoryPage=more;
            more.setOnClickListener(v->{
                if(generation!=renderGeneration||more.getParent()!=page)return;
                if(nextDirectoryPage==more)nextDirectoryPage=null;
                page.removeView(more);
                appendDirectoryPage(entries,end,generation,showRow);
            });
        }
    }

    /** Search all files even if only 60 rows are currently inflated. */
    private void indexFullFolderSearch(List<Models.Entry> entries,
            java.util.function.Function<Models.Entry,Runnable> opener) {
        currentSearch.clear();
        for(Models.Entry entry:entries){
            // Use cached names only: File.isHidden() and preference lookups
            // for thousands of rows on the UI thread can trigger ANRs.
            if(!FileViewOptions.visible(entry.name,
                    entry.name.startsWith("."),showHidden))continue;
            currentSearch.add(new SearchResult(entry.name,opener.apply(entry)));
        }
        completeFolderSearchIndexed=true;
    }

    private void musicAction(String action){
        try{
            startService(new Intent(this,MusicPlaybackService.class).setAction(action));
        }catch(Exception e){showError("음악 재생 조작에 실패했습니다.");}
    }

    private void createMiniPlayerBar(){
        miniPlayerBar=Ui.vertical(this);
        miniPlayerBar.setBackgroundColor(Ui.darkMode?0xFF0D2527:0xFF153E40);
        miniPlayerBar.setVisibility(View.GONE);
        LinearLayout controls=Ui.horizontal(this);
        controls.setPadding(dp(9),0,dp(7),0);
        miniPlayerBar.addView(controls,Ui.lp(this,-1,42));
        TextView music=Ui.text(this,"♫",20,Color.WHITE,true);
        music.setGravity(Gravity.CENTER);
        controls.addView(music,Ui.lp(this,29,-1));
        miniTitle=Ui.text(this,"",13,Color.WHITE,true);
        miniTitle.setSingleLine(true);
        miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        controls.addView(miniTitle,new LinearLayout.LayoutParams(0,-1,1));
        miniToggle=Ui.text(this,"Ⅱ",23,Color.WHITE,true);
        miniToggle.setGravity(Gravity.CENTER);
        miniToggle.setContentDescription("음악 일시정지 또는 재생");
        controls.addView(miniToggle,Ui.lp(this,42,-1));
        miniToggle.setOnClickListener(v->musicAction(MusicPlaybackService.ACTION_TOGGLE));
        TextView stop=Ui.text(this,"×",25,Color.WHITE,false);
        stop.setGravity(Gravity.CENTER);
        stop.setContentDescription("음악 재생 종료");
        controls.addView(stop,Ui.lp(this,38,-1));
        stop.setOnClickListener(v->musicAction(MusicPlaybackService.ACTION_STOP));
        miniProgress=new SeekBar(this);
        miniProgress.setMax(1000);
        miniProgress.setPadding(dp(4),0,dp(4),0);
        miniPlayerBar.addView(miniProgress,Ui.lp(this,-1,19));
        miniProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int p,boolean user){}
            @Override public void onStartTrackingTouch(SeekBar s){miniSeeking=true;}
            @Override public void onStopTrackingTouch(SeekBar s){
                miniSeeking=false;
                long duration=MusicPlaybackService.duration;
                if(duration>0){
                    Intent seek=new Intent(MainActivity.this,MusicPlaybackService.class);
                    seek.setAction(MusicPlaybackService.ACTION_SEEK);
                    seek.putExtra(MusicPlaybackService.EXTRA_SEEK_MS,
                            duration*s.getProgress()/1000);
                    startService(seek);
                }
            }
        });
        screenRoot.addView(miniPlayerBar,Ui.lp(this,-1,61));
        refreshMiniPlayer();
    }
    private void refreshMiniPlayer(){
        if(miniPlayerBar==null||miniPlayerBar.getParent()!=screenRoot)return;
        if(!MusicPlaybackService.active){
            miniPlayerBar.setVisibility(View.GONE);
            return;
        }
        miniPlayerBar.setVisibility(View.VISIBLE);
        String error=MusicPlaybackService.error;
        miniTitle.setText(error.isEmpty()?MusicPlaybackService.title:error);
        miniToggle.setText(MusicPlaybackService.playing?"Ⅱ":"▶");
        if(!miniSeeking){
            long duration=MusicPlaybackService.duration;
            miniProgress.setProgress(duration>0?
                    (int)Math.min(1000,MusicPlaybackService.position*1000/duration):0);
            miniProgress.setEnabled(duration>0);
        }
    }

    private Models.Favorite currentFolderFavorite() {
        if ("local".equals(currentScreen) && localFolder != null)
            return new Models.Favorite("local", localFolder.getName(),
                    localFolder.getAbsolutePath(), "");
        if ("saf".equals(currentScreen) && safFolder != null)
            return new Models.Favorite("saf",
                    safFolder.getName() == null ? activeTreeLabel : safFolder.getName(),
                    safRelative, activeTreeUri);
        if ("remote".equals(currentScreen) && remote != null)
            return new Models.Favorite("remote",
                    remoteFolder.equals("/") ? remote.label() :
                            remoteFolder.substring(remoteFolder.lastIndexOf('/') + 1),
                    remoteFolder, remote.id);
        return null;
    }

    private void toggleCurrentFolderFavorite() {
        Models.Favorite favorite = currentFolderFavorite();
        if (favorite != null)
            favoriteHere(favorite.kind, favorite.path, favorite.title, favorite.remoteId);
    }

    private void updateFavoriteIndicators() {
        Models.Favorite folder = currentFolderFavorite();
        boolean checked = false;
        try {
            if (folder != null) checked = store.isFavorite(folder);
        } catch (Exception error) {
            failure(error);
        }
        if (toolbarFavorite != null) {
            toolbarFavorite.setChecked(checked);
            toolbarFavorite.setContentDescription(checked ?
                    "즐겨찾기 등록됨, 누르면 해제" : "즐겨찾기 미등록, 누르면 추가");
        }
    }

    private FileTransferEngine.Item fileItem(Models.Entry entry) {
        if ("local".equals(currentScreen) && entry.file != null)
            return FileTransferEngine.local(entry.file);
        if ("saf".equals(currentScreen) && entry.document != null)
            return FileTransferEngine.saf(entry.document,
                    RemoteProvider.child(safRelative, entry.name), activeTreeUri);
        if ("remote".equals(currentScreen) && remote != null)
            return FileTransferEngine.remote(remote, entry);
        if ("media".equals(currentScreen) && entry.uri != null)
            return FileTransferEngine.media(entry.uri, entry.name);
        return null;
    }

    private void toggleFileSelection(FileTransferEngine.Item item) {
        selection.toggle(item.key(), item);
        updateFileOperationChrome();
    }

    private void updateFileOperationChrome() {
        if (screenRoot == null || normalFileToolbar == null) return;
        if (selectionToolbar != null) {
            screenRoot.removeView(selectionToolbar);
            selectionToolbar = null;
        }
        if (operationBottomBar != null) {
            screenRoot.removeView(operationBottomBar);
            operationBottomBar = null;
        }
        boolean selecting = !selection.isEmpty();
        normalFileToolbar.setVisibility(selecting ? View.GONE : View.VISIBLE);
        for (Map.Entry<String, View> row : selectableRows.entrySet()) {
            boolean selected = selection.contains(row.getKey());
            row.getValue().setActivated(selected);
            if (selected)
                row.getValue().setBackground(Ui.bg(this, Ui.darkMode ?
                        0xFF236D6A : 0xFFA6DDD6, 8, Ui.TEAL));
            else if (layoutMode == FileViewOptions.GRID)
                row.getValue().setBackground(null);
            else
                row.getValue().setBackgroundColor(Ui.SURFACE);
        }
        if (selecting) {
            selectionToolbar = Ui.horizontal(this);
            selectionToolbar.setBackgroundColor(0xFF003544);
            selectionToolbar.setPadding(dp(8), 0, dp(8), 0);
            TextView cancel = Ui.text(this, "×", 32, Color.WHITE, false);
            cancel.setGravity(Gravity.CENTER);
            cancel.setContentDescription("파일 선택 취소");
            selectionToolbar.addView(cancel, Ui.lp(this, 46, -1));
            cancel.setOnClickListener(v -> {
                selection.clear();
                updateFileOperationChrome();
            });
            TextView count = Ui.text(this, selection.size() + "/" +
                    visibleFileItems.size(), 19, Color.WHITE, true);
            selectionToolbar.addView(count,
                    new LinearLayout.LayoutParams(0, -1, 1));
            TextView selectAll = Ui.text(this,
                    selection.size() == visibleFileItems.size() ? "▣" : "▢",
                    26, Color.WHITE, true);
            selectAll.setGravity(Gravity.CENTER);
            selectAll.setContentDescription(
                    selection.size() == visibleFileItems.size() ?
                            "전체 선택 해제" : "전체 선택");
            selectionToolbar.addView(selectAll, Ui.lp(this, 54, -1));
            selectAll.setOnClickListener(v -> {
                selection.selectAll(visibleFileItems);
                updateFileOperationChrome();
            });
            screenRoot.addView(selectionToolbar, 0, Ui.lp(this, -1, 65));

            operationBottomBar = newOperationBar();
            if (!FileTransferService.snapshot().active) {
                addOperationButton(operationBottomBar, "▣", "복사",
                        () -> stageSelected(false));
                addOperationButton(operationBottomBar, "⇥", "이동",
                        () -> stageSelected(true));
            }
            addOperationButton(operationBottomBar, "✎", "이름 변경",
                    this::renameSelected);
            addOperationButton(operationBottomBar, "▤", "삭제",
                    this::deleteSelected);
            addOperationButton(operationBottomBar, "⋮", "더 보기",
                    this::showSelectedMore);
            screenRoot.addView(operationBottomBar, Ui.lp(this, -1, 74));
        } else if (!stagedFiles.isEmpty() && ("local".equals(currentScreen) ||
                "saf".equals(currentScreen) || "remote".equals(currentScreen))) {
            operationBottomBar = newOperationBar();
            addOperationButton(operationBottomBar, "×", "취소", () -> {
                stagedFiles = Collections.emptyList();
                stagedMove = false;
                updateFileOperationChrome();
            });
            TextView status = Ui.text(this,
                    stagedFiles.size() + "개 " + (stagedMove ? "이동 대기" : "복사 대기"),
                    13, Color.WHITE, false);
            status.setGravity(Gravity.CENTER);
            operationBottomBar.addView(status,
                    new LinearLayout.LayoutParams(0, -1, 1));
            addOperationButton(operationBottomBar, "▣", "붙여넣기",
                    this::pasteStagedFiles);
            screenRoot.addView(operationBottomBar, Ui.lp(this, -1, 74));
        }
    }

    private LinearLayout newOperationBar() {
        LinearLayout bar = Ui.horizontal(this);
        bar.setBackgroundColor(0xFF003544);
        bar.setPadding(dp(4), dp(5), dp(4), dp(5));
        return bar;
    }

    private void addOperationButton(LinearLayout bar, String symbol,
                                    String label, Runnable onClick) {
        LinearLayout column = Ui.vertical(this);
        column.setGravity(Gravity.CENTER);
        TextView icon = Ui.text(this, symbol, 25, Color.WHITE, false);
        icon.setGravity(Gravity.CENTER);
        column.addView(icon, Ui.lp(this, -1, 36));
        TextView caption = Ui.text(this, label, 11.5f, Color.WHITE, false);
        caption.setGravity(Gravity.CENTER);
        caption.setSingleLine(true);
        column.addView(caption, Ui.lp(this, -1, 23));
        column.setContentDescription(label);
        column.setClickable(true);
        column.setOnClickListener(v -> onClick.run());
        bar.addView(column, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void stageSelected(boolean moving) {
        if (FileTransferService.snapshot().active) {
            showError("파일 전송 중에는 새 복사·이동을 시작할 수 없습니다.");
            return;
        }
        if (selection.isEmpty()) return;
        List<FileTransferEngine.Item> items = selection.snapshot();
        if (moving) for (FileTransferEngine.Item item : items)
            if (!item.writable()) {
                showError("읽기 전용 저장소의 파일은 이동할 수 없습니다. 복사를 사용하세요.");
                return;
            }
        stagedFiles = items;
        stagedMove = moving;
        selection.clear();
        updateFileOperationChrome();
        toast(items.size() + "개 선택됨 · 붙여넣을 폴더를 찾아가세요.");
    }

    private FileTransferEngine.Destination currentPasteDestination() {
        if ("local".equals(currentScreen) && localFolder != null)
            return FileTransferEngine.localFolder(localFolder);
        if ("saf".equals(currentScreen) && safFolder != null)
            return FileTransferEngine.safFolder(safFolder, safRelative, activeTreeUri);
        if ("remote".equals(currentScreen) && remote != null)
            return FileTransferEngine.remoteFolder(remote, remoteFolder);
        return null;
    }

    private void invalidateTransferCaches(List<FileTransferEngine.Item> sources,
                                          FileTransferEngine.Destination target) {
        for (FileTransferEngine.Item item : sources)
            if (item.remote != null) folderCache.invalidateProfile(item.remote);
        if (target.remote != null) folderCache.invalidateProfile(target.remote);
        folderCache.clearCounts();
    }

    private void pasteStagedFiles() {
        if (FileTransferService.snapshot().active) {
            showError("기존 파일 전송이 완료된 뒤 붙여넣기를 사용할 수 있습니다.");
            return;
        }
        FileTransferEngine.Destination dest = currentPasteDestination();
        if (dest == null) {
            showError("붙여넣을 파일 탐색기 폴더로 이동하세요.");
            return;
        }
        List<FileTransferEngine.Item> sources = new ArrayList<>(stagedFiles);
        boolean moving = stagedMove;
        runJob("대상 폴더에서 같은 이름 확인 중...",
                () -> FileTransferEngine.conflictingNames(sources, dest, moving),
                conflicts -> {
                    if (conflicts.isEmpty()) {
                        commitStagedPaste(sources, dest, moving, false);
                        return;
                    }
                    StringBuilder names = new StringBuilder();
                    for (int i = 0; i < Math.min(conflicts.size(), 4); i++) {
                        if (i > 0) names.append("\n");
                        names.append("• ").append(conflicts.get(i));
                    }
                    if (conflicts.size() > 4)
                        names.append("\n외 ").append(conflicts.size() - 4).append("개");
                    new AlertDialog.Builder(this)
                            .setTitle("같은 이름의 파일이 " + conflicts.size() + "개 있습니다")
                            .setMessage(names + "\n\n기존 파일을 덮어쓸까요? " +
                                    "폴더가 포함된 경우 하위 동일 이름의 파일도 교체됩니다. " +
                                    "원본 파일은 덮어쓰기 전에 백업해 두는 것이 안전합니다.")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("덮어쓰기", (dialog, which) ->
                                    commitStagedPaste(sources, dest, moving, true))
                            .show();
                });
    }

    private void commitStagedPaste(List<FileTransferEngine.Item> sources,
                                   FileTransferEngine.Destination dest,
                                   boolean moving, boolean overwrite) {
        runTransferJob(sources, dest, moving, overwrite);
    }

    private void runTransferJob(List<FileTransferEngine.Item> sources,
                                FileTransferEngine.Destination dest,
                                boolean moving, boolean overwrite) {
        if (FileTransferService.snapshot().active) {
            showError("이미 파일 전송 중입니다. 진행률 아이콘에서 확인하세요.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
            pendingTransferPermission = () -> runTransferJob(sources, dest, moving, overwrite);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_TRANSFER_NOTIFICATIONS);
            return;
        }
        try {
            if (!FileTransferService.begin(this, sources, dest, moving, overwrite)) {
                showError("이미 파일 전송 중입니다.");
                return;
            }
            transferSourceCache = new ArrayList<>(sources);
            transferTargetCache = dest;
            transferHadActive = true;
            stagedFiles = Collections.emptyList();
            stagedMove = false;
            updateFileOperationChrome();
            showTransferDialog();
        } catch (RuntimeException error) {
            showError("백그라운드 전송 서비스를 시작하지 못했습니다: " + safeError(error));
        }
    }

    private void showTransferDialog() {
        if (transferDialog != null && transferDialog.isShowing()) return;
        if (!FileTransferService.snapshot().active) return;
        transferHidden = false;
        LinearLayout box = Ui.vertical(this);
        box.setPadding(dp(22), dp(20), dp(22), dp(10));
        TextView title = Ui.text(this,
                FileTransferService.snapshot().moving ? "파일 이동 중..." : "파일 복사 중...",
                17, Ui.TEXT, true);
        box.addView(title, Ui.lp(this, -1, 30));
        Ui.gap(box, this, 10);
        transferName = Ui.text(this, "전송 준비 중...", 13, Ui.MUTED, false);
        transferName.setSingleLine(true);
        transferName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        box.addView(transferName, Ui.lp(this, -1, 25));
        Ui.gap(box, this, 9);
        transferProgressBar = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
        transferProgressBar.setMax(1000);
        transferProgressBar.setProgressTintList(
                android.content.res.ColorStateList.valueOf(Ui.TEAL));
        box.addView(transferProgressBar, Ui.lp(this, -1, 8));
        Ui.gap(box, this, 12);
        LinearLayout row = Ui.horizontal(this);
        transferPercent = Ui.text(this, "0%", 18, Ui.TEAL, true);
        row.addView(transferPercent, Ui.lp(this, 65, 30));
        transferAmount = Ui.text(this, "0 B / 용량 확인 중", 13, Ui.TEXT, false);
        transferAmount.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(transferAmount, new LinearLayout.LayoutParams(0, dp(30), 1f));
        box.addView(row, Ui.lp(this, -1, 30));
        Ui.gap(box, this, 7);
        transferFiles = Ui.text(this, "0개 완료", 12, Ui.MUTED, false);
        box.addView(transferFiles, Ui.lp(this, -1, 24));

        AlertDialog dialog = new AlertDialog.Builder(this).setView(box)
                .setCancelable(false)
                .setNegativeButton("숨기기", null)
                .setPositiveButton("취소", null)
                .create();
        transferDialog = dialog;
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                transferHidden = true;
                dialog.dismiss();
                updateTransferUi();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                if (FileTransferService.snapshot().cancelling) return;
                FileTransferService.requestCancel(this);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText("취소 중...");
                updateTransferUi();
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if (transferDialog == dialog) transferDialog = null;
            updateTransferUi();
        });
        dialog.show();
        updateTransferUi();
    }

    private void attachTransferChip() {
        if (container == null || transferChip != null) return;
        TextView chip = Ui.text(this, "0%", 13, Color.WHITE, true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.bg(this, Ui.TEAL, 15, 0));
        chip.setElevation(dp(10));
        chip.setContentDescription("파일 전송 진행률, 눌러서 전송창 다시 열기");
        chip.setOnClickListener(v -> showTransferDialog());
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dp(53), dp(34), Gravity.TOP | Gravity.RIGHT);
        params.topMargin = statusBarInsetPx + dp(62);
        params.rightMargin = dp(12);
        container.addView(chip, params);
        transferChip = chip;
        updateTransferUi();
    }

    private void updateTransferUi() {
        FileTransferService.State s = FileTransferService.snapshot();
        int progress = s.percent();
        if (transferChip != null) {
            transferChip.setVisibility(s.active && transferHidden ? View.VISIBLE : View.GONE);
            if (s.active) transferChip.setText(progress < 0 ? "--%" : progress + "%");
        }
        if (transferDialog != null && transferDialog.isShowing()) {
            if (!s.active) {
                transferHidden = true;
                transferDialog.dismiss();
            } else {
                transferName.setText(s.cancelling ? "취소 중... " + s.name : s.name);
                transferProgressBar.setIndeterminate(progress < 0);
                if (progress >= 0) transferProgressBar.setProgress(progress * 10);
                transferPercent.setText(progress < 0 ? "--%" : progress + "%");
                transferAmount.setText(TransferProgressFormat.amount(s.done, s.total));
                transferFiles.setText(s.completed + " / " + s.count + "개 항목");
            }
        }
        if (transferHadActive && !s.active) {
            transferHadActive = false;
            transferHidden = true;
            if (transferTargetCache != null) {
                invalidateTransferCaches(transferSourceCache, transferTargetCache);
                transferSourceCache = Collections.emptyList();
                transferTargetCache = null;
            } else folderCache.clearCounts();
            if (s.cancelled) toast("파일 전송이 취소되었습니다.");
            else if (s.failed) showError("파일 전송 실패: " + s.error);
            else if (s.finished) toast("파일 전송이 완료되었습니다.");
            if (!isFinishing() && !isDestroyed()) refreshCurrent();
        }
    }

    private void renameSelected() {
        if (selection.size() != 1) {
            toast("이름 변경은 파일 또는 폴더 하나만 선택하세요.");
            return;
        }
        FileTransferEngine.Item item = selection.snapshot().get(0);
        selection.clear();
        updateFileOperationChrome();
        if ("local".equals(item.kind)) renameLocal(item.local, null, item.name);
        else if ("saf".equals(item.kind)) renameLocal(null, item.saf, item.name);
        else if ("remote".equals(item.kind)) {
            if (isReadOnlyCloud(item.remote)) showError("읽기 전용 저장소입니다.");
            else renameRemote(item.remote,
                    new Models.Entry(item.name, item.path, item.directory));
        } else toast("미디어 화면의 이름 변경은 저장소 폴더에서 진행하세요.");
    }

    private void deleteSelected() {
        List<FileTransferEngine.Item> sources = selection.snapshot();
        if (sources.isEmpty()) return;
        for (FileTransferEngine.Item item : sources) if (!item.writable()) {
            showError("읽기 전용 파일은 삭제할 수 없습니다.");
            return;
        }
        new AlertDialog.Builder(this).setTitle(sources.size() + "개 항목 삭제")
                .setMessage("선택한 파일과 폴더를 삭제하시겠습니까? 폴더 안의 파일도 삭제되며 " +
                        "SD·일반 NAS는 휴지통 없이 삭제될 수 있습니다. " +
                        "네이버 MYBOX 파일은 MYBOX 휴지통으로 이동합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) ->
                        runJob("선택 항목 삭제 중...",
                                () -> FileTransferEngine.delete(this, sources),
                                count -> {
                                    for (FileTransferEngine.Item item : sources)
                                        if (item.remote != null)
                                            folderCache.invalidateProfile(item.remote);
                                    folderCache.clearCounts();
                                    toast(count + "개 삭제했습니다.");
                                    refreshCurrent();
                                })).show();
    }

    private void showSelectedMore() {
        if (selection.isEmpty()) return;
        List<FileTransferEngine.Item> picked = selection.snapshot();
        boolean canShare = picked.size() == 1 &&
                "remote".equals(picked.get(0).kind) &&
                NasSharePolicy.supported(picked.get(0).remote);
        new AlertDialog.Builder(this)
                .setTitle(selection.size() + "개 선택")
                .setItems(canShare ? new String[]{"즐겨찾기 추가/해제", "폴더 용도 설정",
                        "선택 파일 압축하기", "공유 링크 생성", "선택 해제"} :
                        new String[]{"즐겨찾기 추가/해제", "폴더 용도 설정",
                                "선택 파일 압축하기", "선택 해제"},
                        (dialog, which) -> {
                    List<FileTransferEngine.Item> items = selection.snapshot();
                    if (which == 0) {
                        int changed = 0;
                        try {
                            for (FileTransferEngine.Item item : items) {
                                Models.Favorite fav = favoriteForItem(item);
                                if (fav == null) continue;
                                store.toggleFavorite(fav);
                                changed++;
                            }
                            toast(changed == 0 ? "폴더만 즐겨찾기에 등록할 수 있습니다." :
                                    changed + "개 폴더의 즐겨찾기를 변경했습니다.");
                            updateFavoriteIndicators();
                        } catch (Exception error) { failure(error); }
                    } else if (which == 1) {
                        if (items.size() != 1 || !items.get(0).directory) {
                            toast("폴더 용도 설정은 폴더 하나만 선택하세요.");
                            return;
                        }
                        showFolderPurposeMenu(items.get(0));
                    } else if (which == 2) {
                        chooseCompression(items);
                    } else if (canShare && which == 3) {
                        showNasShareCreate(picked.get(0));
                    } else {
                        selection.clear();
                        updateFileOperationChrome();
                    }
                }).show();
    }

    private void chooseCompression(List<FileTransferEngine.Item> sources) {
        FileTransferEngine.Destination destination = currentPasteDestination();
        if (destination == null) {
            showError("압축파일을 저장할 파일관리자 폴더에서 선택해 주세요.");
            return;
        }
        if (destination.remote != null &&
                CloudProvider.isReadOnly(destination.remote)) {
            showError("읽기 전용 저장소에는 압축 파일을 생성할 수 없습니다.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("선택한 " + sources.size() + "개 항목 압축하기")
                .setItems(new String[]{"ZIP 만들기", "7z 만들기"}, (dialog, formatIndex) -> {
                    String format = formatIndex == 0 ? "zip" : "7z";
                    EditText name = new EditText(this);
                    name.setSingleLine(true);
                    name.setText(sources.size() == 1 ?
                            sources.get(0).name + "." + format : "선택파일." + format);
                    name.selectAll();
                    new AlertDialog.Builder(this)
                            .setTitle("압축파일 이름")
                            .setView(name)
                            .setMessage("압축파일은 현재 폴더에 만들어집니다. " +
                                    "암호화 및 RAR 생성은 지원하지 않습니다.")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("압축하기", (d, w) -> {
                                String outputName = name.getText().toString().trim();
                                if (outputName.isEmpty()) {
                                    showError("압축파일 이름을 입력하세요.");
                                    return;
                                }
                                runJob("파일 압축·저장 중...", () ->
                                                ArchiveWorkflows.compress(this,
                                                        sources, destination,
                                                        outputName, format),
                                        result -> {
                                            selection.clear();
                                            updateFileOperationChrome();
                                            if (destination.remote != null)
                                                folderCache.invalidateProfile(destination.remote);
                                            folderCache.clearCounts();
                                            toast("압축 파일 생성: " + result.destinationName);
                                            refreshCurrent();
                                        });
                            }).show();
                }).show();
    }

    private void offerExtractArchive(FileTransferEngine.Item archive) {
        FileTransferEngine.Destination destination = currentPasteDestination();
        if (destination == null) {
            showError("압축 파일이 있는 저장소 폴더에서 압축을 해제하세요.");
            return;
        }
        if (destination.remote != null &&
                CloudProvider.isReadOnly(destination.remote)) {
            showError("읽기 전용 저장소에서 압축해제할 수 없습니다.");
            return;
        }
        String newFolder = ArchiveCodec.baseFolder(archive.name);
        new AlertDialog.Builder(this)
                .setTitle("압축 파일 해제")
                .setMessage(archive.name + "\n\n현재 폴더에 '" + newFolder +
                        "' 폴더를 만들고 압축을 풉니다.\n" +
                        "같은 이름이 이미 있으면 '" + newFolder +
                        " (1)', '" + newFolder + " (2)'처럼 자동으로 이름을 붙입니다. " +
                        "기존 파일이나 폴더를 덮어쓰지 않습니다. " +
                        "임시 저장 공간과 추출 용량에 제한이 있습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("압축 해제", (d, which) ->
                        runJob("압축 해제·파일 업로드 중...", () ->
                                ArchiveWorkflows.extract(this, archive, destination),
                                result -> {
                                    if (destination.remote != null)
                                        folderCache.invalidateProfile(destination.remote);
                                    folderCache.clearCounts();
                                    toast("압축 " + result.entries + "개 항목 해제했습니다.");
                                    refreshCurrent();
                                })).show();
    }

    private Models.Favorite favoriteForItem(FileTransferEngine.Item item) {
        if (!item.directory) return null;
        if ("local".equals(item.kind))
            return new Models.Favorite("local", item.name,
                    item.local.getAbsolutePath(), "");
        if ("saf".equals(item.kind))
            return new Models.Favorite("saf", item.name, item.path, item.tree);
        if ("remote".equals(item.kind))
            return new Models.Favorite("remote", item.name, item.path, item.remote.id);
        return null;
    }

    private void openSelectedItem(FileTransferEngine.Item item) {
        switch (item.kind) {
            case "local":
                if (item.directory) browseLocal(item.local);
                else if (isVideo(item.name)) openVideo(Uri.fromFile(item.local), item.name);
                else if (MediaFileTypes.isAudio(item.name))
                    playMusic(Uri.fromFile(item.local),item.name);
                else if (MediaFileTypes.isImage(item.name))
                    viewImage("local",item.name,item.local.getAbsolutePath(),null,null);
                else openFile(item.local);
                break;
            case "saf":
                if (item.directory) browseSaf(item.saf, item.path);
                else if (isVideo(item.name)) openVideo(item.saf.getUri(), item.name);
                else if (MediaFileTypes.isAudio(item.name))
                    playMusic(item.saf.getUri(),item.name);
                else if (MediaFileTypes.isImage(item.name))
                    viewImage("uri",item.name,null,item.saf.getUri(),null);
                else openNamedUri(item.saf.getUri(), item.name);
                break;
            case "remote":
                if (item.directory) openRemote(item.remote, item.path);
                else {
                    Models.Entry entry = new Models.Entry(item.name, item.path, false);
                    if (isVideo(item.name)) openRemoteVideo(item.remote, entry);
                    else if(MediaFileTypes.isAudio(item.name))
                        playRemoteMusic(item.remote,entry);
                    else if(MediaFileTypes.isImage(item.name))
                        viewImage("remote",entry.name,entry.path,null,item.remote);
                    else openRemoteFile(item.remote, entry);
                }
                break;
            case "media":
                if (isVideo(item.name)) openVideo(item.media, item.name);
                else if(MediaFileTypes.isAudio(item.name))
                    playMusic(item.media,item.name);
                else if(MediaFileTypes.isImage(item.name))
                    viewImage("uri",item.name,null,item.media,null);
                else openUri(item.media, mime(item.name));
                break;
        }
    }
    private TextView heading(String text, int marginTop) {
        TextView h = Ui.text(this, text, 16, Ui.TEXT, true);
        h.setPadding(dp(4), dp(marginTop), 0, dp(12));
        page.addView(h, Ui.lp(this, -1, -2));
        return h;
    }
    private TextView muted(String text) {
        TextView t = Ui.text(this, text, 13, Ui.MUTED, false);
        t.setPadding(dp(4), dp(3), dp(4), dp(8));
        page.addView(t, Ui.lp(this, -1, -2));
        return t;
    }
    private StorageVolume sdVolume() {
        StorageManager manager = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (manager == null) return null;
        for (StorageVolume vol : manager.getStorageVolumes())
            if (SdAccessPolicy.isMountedSd(
                    vol.isRemovable(), vol.isPrimary(), vol.getState()))
                return vol;
        return null;
    }
    private boolean hasSd() {
        return sdVolume() != null;
    }
    private File directSdRoot(StorageVolume volume) {
        if (volume == null) return null;
        // API 30+ exposes the actual mounted removable volume directory.
        if (Build.VERSION.SDK_INT >= 30) {
            File root = volume.getDirectory();
            if (root != null && root.isDirectory()) return root;
        }
        // OEM fallback: derive the mount root from *our own* app-scoped
        // folder returned by Android, never from a guessed UUID.
        StorageManager manager = (StorageManager) getSystemService(STORAGE_SERVICE);
        if (manager != null) for (File scoped : getExternalFilesDirs(null)) {
            if (scoped == null) continue;
            StorageVolume candidate = manager.getStorageVolume(scoped);
            if (candidate == null || !candidate.equals(volume)) continue;
            String rootPath = SdAccessPolicy.rootFromAppFilesPath(
                    scoped.getAbsolutePath());
            if (rootPath != null) {
                File root = new File(rootPath);
                if (root.isDirectory()) return root;
            }
        }
        return null;
    }
    private boolean isDirectSdRoot(File directory) {
        if (directory == null) return false;
        File root = directSdRoot(sdVolume());
        return root != null && directory.equals(root);
    }
    private boolean isInsideSdStorage() {
        if ("saf".equals(currentScreen))
            return !activeTreeUri.isEmpty() && activeTreeUri.equals(store.sdTreeUri());
        if (!"local".equals(currentScreen) || localFolder == null) return false;
        File root = directSdRoot(sdVolume());
        if (root == null) return false;
        String rootPath = root.getAbsolutePath();
        String current = localFolder.getAbsolutePath();
        return current.equals(rootPath) ||
                current.startsWith(rootPath + File.separator);
    }
    private void showHome() {
        assignedBackBoundary = null;
        currentScreen = "home";
        remote = null;
        localFolder = null;
        safFolder = null;
        mediaKind = null;
        render("FileHub AIO", false, "⋮", this::menu);
        heading("저장소", 0);
        LinearLayout r1 = Ui.horizontal(this);
        page.addView(r1, Ui.lp(this, -1, expandedLayout() ? 195 : 158));
        boolean sdPresent = hasSd();
        tile(r1, "메인 저장소", "drive", Ui.TEAL,
                capacity(), () -> browseLocal(Environment.getExternalStorageDirectory()));
        if (sdPresent)
            tile(r1, "SD 저장소", "sd", 0xFF6B8CBE,
                    "외장 메모리", this::openSd);
        else
            tile(r1, "다운로드", "download", ICON_BLUE, "",
                    () -> browseLocal(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS)));
        if (sdPresent)
            tile(r1, "다운로드", "download", ICON_BLUE, "",
                    () -> browseLocal(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS)));
        else tile(r1, "이미지", "image", 0xFFA66BC4, "",
                () -> openMedia("image"));
        Ui.gap(page, this, 10);
        LinearLayout r2 = Ui.horizontal(this);
        page.addView(r2, Ui.lp(this, -1, expandedLayout() ? 195 : 158));
        if (sdPresent) tile(r2, "이미지", "image", 0xFFA66BC4, "",
                () -> openMedia("image"));
        tile(r2, "오디오", "audio", 0xFF159E90, "", () -> openMedia("audio"));
        tile(r2, "동영상", "video", 0xFFDD7662, "", () -> openMedia("video"));
        if (!sdPresent) tileEmpty(r2);
        Ui.gap(page, this, 18);
        // Storage shortcuts above are preserved, including removable/SD
        // storage when connected. The virtual bookshelves below show ONLY
        // folders explicitly assigned by the user, across all providers.
        View sectionDivider = new View(this);
        sectionDivider.setBackgroundColor(Ui.BORDER);
        LinearLayout.LayoutParams dividerLayout=Ui.lp(this,-1,1);
        dividerLayout.leftMargin=dp(6);
        dividerLayout.rightMargin=dp(6);
        page.addView(sectionDivider,dividerLayout);
        heading("추가기능", 17);
        LinearLayout features=Ui.horizontal(this);
        page.addView(features,Ui.lp(this,-1,expandedLayout()?195:158));
        tile(features,"동영상","video",0xFFDD7662,"",
                ()->showAssignedFolders(AssignedFolderCatalog.VIDEO));
        tile(features,"만화책","comic",0xFFA66BC4,"",
                ()->showAssignedFolders(AssignedFolderCatalog.COMIC));
        tile(features,"소설책","book",0xFF6B99C2,"",
                ()->showAssignedFolders(AssignedFolderCatalog.TEXT));
        Ui.gap(page,this,14);
        heading("원격 저장소", 15);
        LinearLayout remoteEntry = Ui.horizontal(this);
        remoteEntry.setPadding(dp(16), dp(12), dp(12), dp(12));
        remoteEntry.setBackground(Ui.bg(this, Ui.SURFACE, 17, Ui.BORDER));
        page.addView(remoteEntry, Ui.lp(this, -1, 78));
        Ui.Icon icon = new Ui.Icon(this, "remote", Ui.TEAL);
        remoteEntry.addView(icon, Ui.lp(this, 48, 48));
        LinearLayout text = Ui.vertical(this);
        LinearLayout.LayoutParams w = new LinearLayout.LayoutParams(0, -2, 1);
        w.leftMargin = dp(12);
        remoteEntry.addView(text, w);
        text.addView(Ui.text(this, "네트워크 폴더", 16, Ui.TEXT, true));
        try {
            int count = store.activeRemotes().size();
            text.addView(Ui.text(this, count == 0 ? "연결 추가하기" :
                    "저장된 연결 " + count + "개", 12, Ui.MUTED, false));
        } catch (Exception error) {
            text.addView(Ui.text(this, "설정을 확인해 주세요", 12, Ui.MUTED, false));
        }
        TextView arrow = Ui.text(this, "›", 28, Ui.MUTED, false);
        remoteEntry.addView(arrow, Ui.lp(this, 20, 42));
        remoteEntry.setOnClickListener(v -> showRemoteList());
        Ui.gap(page,this,14);
    }
    /** Virtual shelves list all explicitly designated folders across storage
     * providers. Open each item in the original file manager, not a copy. */
    private void showAssignedFolders(String mode) {
        assignedBackBoundary = null;
        assignedMode=mode;
        currentScreen="assigned";
        remote=null;
        localFolder=null;
        safFolder=null;
        mediaKind=null;
        String title=AssignedFolderCatalog.VIDEO.equals(mode)?"동영상":
                AssignedFolderCatalog.COMIC.equals(mode)?"만화책":"소설책";
        render(title+" · 지정 폴더",true,"+",this::showAssignedPicker);
        final int screen=renderGeneration;
        try{
            Set<String> videos=new java.util.HashSet<>(
                    PlayerSettings.prefs(this).getStringSet(
                            PlayerSettings.HIDE_FOLDERS,Collections.emptySet()));
            List<AssignedFolderCatalog.Folder> roots=
                    AssignedFolderCatalog.collect(mode,videos,readerFolders.all());
            if(roots.isEmpty()){
                muted("아직 "+title+"으로 지정한 폴더가 없습니다.");
                muted("오른쪽 위 + 를 눌러 로컬·SD·네트워크 폴더를 찾아 지정하세요.");
                return;
            }
            TextView loading=muted("지정한 폴더의 하위 폴더를 찾는 중...");
            worker.execute(()->{
                Map<String,AssignedFolderCatalog.Folder> unique=new LinkedHashMap<>();
                List<Models.RemoteProfile> profiles;
                try{profiles=store.activeRemotes();}
                catch(Exception e){profiles=Collections.emptyList();}
                for(AssignedFolderCatalog.Folder root:roots){
                    try{
                        List<AssignedFolderCatalog.Folder> children=
                                assignedChildFolders(root,profiles);
                        if(children.isEmpty())children=Collections.singletonList(root);
                        for(AssignedFolderCatalog.Folder child:children){
                            if(!isFolderRoleActive(mode,child,videos))continue;
                            unique.put(child.key(),child);
                        }
                    }catch(Exception ignored){
                        // Offline accounts remain in the user's settings;
                        // do not remove an assigned folder due to a temporary
                        // connection failure. Offer its saved root instead.
                        if(isFolderRoleActive(mode,root,videos))
                            unique.put(root.key(),root);
                    }
                }
                List<AssignedFolderCatalog.Folder> visible=
                        new ArrayList<>(unique.values());
                visible.sort((a,b)->a.name().compareToIgnoreCase(b.name()));
                final List<Models.RemoteProfile> accounts=profiles;
                runOnUiThread(()->{
                    if(screen!=renderGeneration||!"assigned".equals(currentScreen)||
                            !mode.equals(assignedMode)||isFinishing()||isDestroyed())
                        return;
                    page.removeView(loading);
                    if(visible.isEmpty()){
                        muted("표시할 하위 폴더가 없습니다. + 버튼으로 폴더를 추가하세요.");
                        return;
                    }
                    muted("폴더 "+visible.size()+"개 · 길게 누르면 해당 폴더의 용도 지정 해제");
                    appendAssignedFolderPage(visible,accounts,roots,mode,0,screen);
                });
            });
        }catch(Exception error){
            showError("지정 폴더를 표시하지 못했습니다: "+error.getMessage());
        }
    }

    private void appendAssignedFolderPage(
            List<AssignedFolderCatalog.Folder> folders,
            List<Models.RemoteProfile> accounts,
            List<AssignedFolderCatalog.Folder> roots,String mode,int offset,int screen) {
        if(screen!=renderGeneration || !"assigned".equals(currentScreen))return;
        int end=LargeFolderPagePolicy.end(folders.size(),offset);
        for(int i=offset;i<end;i++){
            AssignedFolderCatalog.Folder folder=folders.get(i);
            String origin="메인·로컬 저장소";
            if("remote".equals(folder.kind)){
                origin="네트워크 폴더";
                for(Models.RemoteProfile account:accounts)
                    if(account.id.equals(folder.owner)){
                        origin=account.label();break;
                    }
            }else if("saf".equals(folder.kind)||"storage".equals(folder.kind))
                origin="SD·USB 저장소";
            row(page,folder.name(),origin+" · "+folder.path,
                    "folder",0xFFE5AD5B,
                    ()->openAssignedFolder(folder,
                            AssignedFolderCatalog.configuredRoot(roots,folder)),
                    ()->confirmRemoveAssignedRole(mode,folder));
        }
        if(end<folders.size()){
            TextView more=Ui.action(this,"폴더 더 보기  ("+end+" / "+folders.size()+")");
            page.addView(more,Ui.lp(this,-1,50));
            nextDirectoryPage=more;
            more.setOnClickListener(v->{
                if(screen!=renderGeneration||more.getParent()!=page)return;
                if(nextDirectoryPage==more)nextDirectoryPage=null;
                page.removeView(more);
                appendAssignedFolderPage(folders,accounts,roots,mode,end,screen);
            });
        }
    }
    private String assignedVideoScope(AssignedFolderCatalog.Folder folder){
        switch(folder.kind){
            case "local":return "local:"+folder.path;
            case "remote":return "remote:"+folder.owner+":"+folder.path;
            case "storage":return "storage:"+folder.path;
            case "saf":return "saf:"+folder.owner+":"+folder.path;
            default:return "";
        }
    }
    private boolean isFolderRoleActive(String mode,AssignedFolderCatalog.Folder folder,
                                       Set<String> videoScopes){
        if(AssignedFolderCatalog.VIDEO.equals(mode))
            return AssignedRoleExclusions.videoAllowed(this,
                    assignedVideoScope(folder),videoScopes);
        return readerFolders.allowed(mode,folder.kind,folder.owner,folder.path);
    }
    private void confirmRemoveAssignedRole(String mode,
                                           AssignedFolderCatalog.Folder folder){
        String label=AssignedFolderCatalog.VIDEO.equals(mode)?"동영상":
                AssignedFolderCatalog.COMIC.equals(mode)?"만화책":"소설책";
        new AlertDialog.Builder(this).setTitle("폴더 용도 지정 해제")
                .setMessage(folder.name()+"\n\n"+label+
                        " 폴더 용도 지정을 해제할까요?\n"+
                        "상위 폴더에서 상속된 지정도 이 폴더부터 적용되지 않습니다."+
                        "\n파일이나 폴더 자체는 삭제되지 않습니다.")
                .setNegativeButton("취소",null)
                .setPositiveButton("제외하기",(dialog,which)->{
                    try{
                        if(AssignedFolderCatalog.VIDEO.equals(mode)){
                            String scope=assignedVideoScope(folder);
                            PlayerSettings.removeHiddenFolder(this,
                                    FolderScopeFilter.normalize(scope));
                            Set<String> markers=PlayerSettings.prefs(this)
                                    .getStringSet(PlayerSettings.HIDE_FOLDERS,
                                            Collections.emptySet());
                            if(FolderScopeFilter.folderCovered(scope,markers))
                                AssignedRoleExclusions.exclude(this,mode,folder.kind,
                                        folder.owner,folder.path);
                            else AssignedRoleExclusions.clear(this,mode,folder.kind,
                                    folder.owner,folder.path);
                        }else{
                            readerFolders.removeExact(mode,folder.kind,
                                    folder.owner,folder.path);
                            if(ReaderFolders.contains(readerFolders.all(),mode,
                                    folder.kind,folder.owner,folder.path))
                                AssignedRoleExclusions.exclude(this,mode,folder.kind,
                                        folder.owner,folder.path);
                            else AssignedRoleExclusions.clear(this,mode,folder.kind,
                                    folder.owner,folder.path);
                        }
                        folderCache.clearCounts();
                        toast(label+" 폴더 용도 지정이 해제되었습니다.");
                        showAssignedFolders(mode);
                    }catch(Exception error){failure(error);}
                }).show();
    }
    private DocumentFile assignedSafDocument(String tree,String relative){
        if(tree==null||tree.isEmpty())return null;
        Uri rootUri=Uri.parse(tree);
        if(!hasPersistedTreeRead(rootUri))return null;
        DocumentFile folder=DocumentFile.fromTreeUri(this,rootUri);
        if(folder==null)return null;
        if(!"/".equals(relative)){
            for(String part:RemoteProvider.relativePath(relative)
                    .substring(1).split("/")){
                folder=folder.findFile(part);
                if(folder==null||!folder.isDirectory())return null;
            }
        }
        return folder;
    }
    private List<AssignedFolderCatalog.Folder> assignedChildFolders(
            AssignedFolderCatalog.Folder root,
            List<Models.RemoteProfile> profiles)throws Exception{
        List<AssignedFolderCatalog.Folder> result=new ArrayList<>();
        switch(root.kind){
            case "local":{
                File[] files=new File(root.path).listFiles();
                if(files!=null)for(File child:files)
                    if(child.isDirectory()&&child.canRead())
                        result.add(new AssignedFolderCatalog.Folder(root.mode,
                                "local","",child.getAbsolutePath()));
                break;
            }
            case "remote":{
                Models.RemoteProfile account=null;
                for(Models.RemoteProfile item:profiles)
                    if(item.id.equals(root.owner)){account=item;break;}
                if(account==null)throw new IOException("연결되지 않은 네트워크 계정");
                for(Models.Entry entry:RemoteProvider.list(account,root.path))
                    if(entry.directory)result.add(new AssignedFolderCatalog.Folder(
                            root.mode,"remote",root.owner,entry.path));
                break;
            }
            case "saf":{
                DocumentFile folder=assignedSafDocument(root.owner,root.path);
                if(folder==null)throw new IOException("선택한 폴더 권한이 없습니다.");
                for(DocumentFile child:folder.listFiles()){
                    if(!child.isDirectory())continue;
                    String name=child.getName();
                    if(name!=null&&!name.isEmpty())
                        result.add(new AssignedFolderCatalog.Folder(root.mode,
                                "saf",root.owner,RemoteProvider.child(root.path,name)));
                }
                break;
            }
            case "storage":{
                boolean resolved=false;
                for(android.content.UriPermission grant:
                        getContentResolver().getPersistedUriPermissions()){
                    if(!grant.isReadPermission())continue;
                    try{
                        String rootId=DocumentsContract.getTreeDocumentId(grant.getUri());
                        String relative=AssignedFolderCatalog.relativeDocumentId(
                                rootId,root.path);
                        if(relative==null)continue;
                        DocumentFile folder=assignedSafDocument(
                                grant.getUri().toString(),relative);
                        if(folder==null)continue;
                        for(DocumentFile child:folder.listFiles()){
                            if(!child.isDirectory())continue;
                            String id=DocumentsContract.getDocumentId(child.getUri());
                            result.add(new AssignedFolderCatalog.Folder(root.mode,
                                    "storage","",id));
                        }
                        resolved=true;break;
                    }catch(Exception ignored){}
                }
                if(!resolved&&root.path.startsWith("primary:")){
                    File base=Environment.getExternalStorageDirectory();
                    File folder=new File(base,
                            root.path.substring("primary:".length()));
                    File[] children=folder.listFiles();
                    if(children!=null){
                        for(File child:children)
                            if(child.isDirectory()){
                                String relative=child.getAbsolutePath().substring(
                                        base.getAbsolutePath().length())
                                        .replace(File.separatorChar,'/');
                                result.add(new AssignedFolderCatalog.Folder(root.mode,
                                        "storage","", "primary:"+
                                                (relative.startsWith("/")?
                                                        relative.substring(1):relative)));
                            }
                        resolved=true;
                    }
                }
                if(!resolved)throw new IOException("SD·USB 경로에 접근할 수 없습니다.");
                break;
            }
        }
        return result;
    }
    /** Explorer-style picker scoped to existing local, SAF and saved NAS
     * connections. It never changes folders until the user presses Add. */
    private void showAssignedPicker(){
        if(assignedMode==null)return;
        new AssignedFolderPicker(assignedMode).show();
    }
    private final class AssignedFolderPicker {
        final String mode;
        AlertDialog dialog;
        LinearLayout entries;
        TextView pathLabel;
        AssignedFolderCatalog.Folder current,base;
        int generation;
        AssignedFolderPicker(String mode){this.mode=mode;}
        void show(){
            LinearLayout panel=Ui.vertical(MainActivity.this);
            panel.setPadding(dp(14),dp(9),dp(14),dp(4));
            pathLabel=Ui.text(MainActivity.this,"저장소를 선택하세요",13,Ui.MUTED,false);
            pathLabel.setPadding(dp(4),dp(5),dp(4),dp(11));
            panel.addView(pathLabel,Ui.lp(MainActivity.this,-1,-2));
            ScrollView scroll=new ScrollView(MainActivity.this);
            entries=Ui.vertical(MainActivity.this);
            scroll.addView(entries);
            panel.addView(scroll,Ui.lp(MainActivity.this,-1,410));
            dialog=new AlertDialog.Builder(MainActivity.this)
                    .setTitle("폴더 추가 · 탐색기")
                    .setView(panel)
                    .setNegativeButton("취소",null)
                    .setPositiveButton("현재 폴더 지정",null)
                    .create();
            dialog.setOnDismissListener(ignored->generation++);
            dialog.setOnShowListener(ignored->{
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->save());
                roots();
            });
            dialog.show();
        }
        void updateSelection(){
            if(dialog!=null&&dialog.isShowing())
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setEnabled(current!=null);
        }
        void emptyRows(){entries.removeAllViews();}
        void rowItem(String title,String subtitle,Runnable onClick){
            row(entries,title,subtitle,"folder",0xFFE5AD5B,onClick,null);
        }
        void roots(){
            ++generation;
            current=null;base=null;
            pathLabel.setText("저장소를 선택한 뒤 원하는 하위 폴더로 이동하세요.");
            emptyRows();
            updateSelection();
            File main=Environment.getExternalStorageDirectory();
            rowItem("메인 저장소",main.getAbsolutePath(),
                    ()->open(new AssignedFolderCatalog.Folder(mode,
                            "local","",main.getAbsolutePath()),true));
            File downloads=Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
            if(downloads!=null&&downloads.isDirectory())
                rowItem("다운로드",downloads.getAbsolutePath(),
                        ()->open(new AssignedFolderCatalog.Folder(mode,
                                "local","",downloads.getAbsolutePath()),true));
            StorageManager manager=(StorageManager)getSystemService(STORAGE_SERVICE);
            if(manager!=null)for(StorageVolume volume:manager.getStorageVolumes()){
                if(!volume.isRemovable())continue;
                File storage=directSdRoot(volume);
                if(storage!=null&&storage.isDirectory())
                    rowItem("SD·USB · "+volume.getDescription(MainActivity.this),
                            storage.getAbsolutePath(),
                            ()->open(new AssignedFolderCatalog.Folder(mode,
                                    "local","",storage.getAbsolutePath()),true));
            }
            for(android.content.UriPermission grant:
                    getContentResolver().getPersistedUriPermissions()){
                if(!grant.isReadPermission())continue;
                String tree=grant.getUri().toString();
                rowItem("허용된 SD·USB 폴더",
                        tree,()->open(new AssignedFolderCatalog.Folder(
                                mode,"saf",tree,"/"),true));
            }
            try{
                for(Models.RemoteProfile profile:store.activeRemotes()){
                    rowItem("네트워크 · "+profile.label(),profile.protocol,
                            ()->open(new AssignedFolderCatalog.Folder(
                                    mode,"remote",profile.id,"/"),true));
                }
            }catch(Exception error){
                entries.addView(Ui.text(MainActivity.this,
                        "네트워크 계정을 읽지 못했습니다.",12,Ui.MUTED,false));
            }
        }
        void back(){
            if(current==null||base==null||
                    current.key().equals(base.key())){roots();return;}
            String parent;
            if("local".equals(current.kind)){
                File file=new File(current.path).getParentFile();
                parent=file==null?base.path:file.getAbsolutePath();
            }else parent=RemoteProvider.parent(current.path);
            open(new AssignedFolderCatalog.Folder(
                    mode,current.kind,current.owner,parent),false);
        }
        void open(AssignedFolderCatalog.Folder folder,boolean newBase){
            if(newBase)base=folder;
            current=folder;
            final int token=++generation;
            pathLabel.setText(folder.name()+"  ·  "+folder.path);
            emptyRows();
            updateSelection();
            rowItem("〈 상위 폴더","뒤로 이동",this::back);
            TextView loading=Ui.text(MainActivity.this,
                    "하위 폴더 목록을 읽는 중...",13,Ui.MUTED,false);
            loading.setPadding(dp(12),dp(15),dp(4),dp(12));
            entries.addView(loading,Ui.lp(MainActivity.this,-1,-2));
            worker.execute(()->{
                List<AssignedFolderCatalog.Folder> found=Collections.emptyList();
                String error=null;
                try{
                    found=assignedChildFolders(folder,store.activeRemotes());
                    found.sort((a,b)->a.name().compareToIgnoreCase(b.name()));
                }catch(Exception failure){error=safeError(failure);}
                final List<AssignedFolderCatalog.Folder> children=found;
                final String issue=error;
                runOnUiThread(()->{
                    if(token!=generation||dialog==null||!dialog.isShowing()||
                            isFinishing()||isDestroyed())return;
                    entries.removeView(loading);
                    if(issue!=null){
                        entries.addView(Ui.text(MainActivity.this,
                                "폴더를 읽지 못했습니다: "+issue,12,Ui.MUTED,false));
                        return;
                    }
                    if(children.isEmpty()){
                        entries.addView(Ui.text(MainActivity.this,
                                "하위 폴더가 없습니다. 이 폴더를 지정할 수 있습니다.",
                                12,Ui.MUTED,false));
                    }
                    appendPickerFolderPage(children,0,token);
                });
            });
        }
        void appendPickerFolderPage(List<AssignedFolderCatalog.Folder> children,
                                    int offset,int token){
            if(token!=generation||dialog==null||!dialog.isShowing())return;
            int end=LargeFolderPagePolicy.end(children.size(),offset);
            for(int i=offset;i<end;i++){
                AssignedFolderCatalog.Folder child=children.get(i);
                rowItem(child.name(),child.path,()->open(child,false));
            }
            if(end<children.size()){
                TextView next=Ui.action(MainActivity.this,
                        "하위 폴더 더 보기  ("+end+" / "+children.size()+")");
                entries.addView(next,Ui.lp(MainActivity.this,-1,49));
                next.setOnClickListener(v->{
                    if(token!=generation||next.getParent()!=entries)return;
                    entries.removeView(next);
                    appendPickerFolderPage(children,end,token);
                });
            }
        }
        void save(){
            if(current==null)return;
            try{
                if(AssignedFolderCatalog.VIDEO.equals(mode)){
                    String scope=assignedVideoScope(current);
                    if("saf".equals(current.kind)){
                        DocumentFile folder=assignedSafDocument(
                                current.owner,current.path);
                        if(folder!=null)
                            scope="storage:"+
                                    DocumentsContract.getDocumentId(folder.getUri());
                    }
                    PlayerSettings.addHiddenFolder(MainActivity.this,scope);
                    PlayerSettings.prefs(MainActivity.this).edit()
                            .putBoolean(PlayerSettings.HIDE_SUBTITLES,true).apply();
                }else{
                    if(!readerFolders.exact(mode,current.kind,
                            current.owner,current.path))
                        readerFolders.toggle(mode,current.kind,
                                current.owner,current.path);
                    else AssignedRoleExclusions.clear(MainActivity.this,
                            mode,current.kind,current.owner,current.path);
                }
                folderCache.clearCounts();
                dialog.dismiss();
                toast("폴더 용도를 지정했습니다.");
                showAssignedFolders(mode);
            }catch(Exception error){failure(error);}
        }
    }
    private void openAssignedFolder(AssignedFolderCatalog.Folder folder,
                                    AssignedFolderCatalog.Folder configuredRoot){
        if(folder==null)return;
        assignedBackBoundary=null;
        switch(folder.kind){
            case "local":{
                File target=new File(folder.path);
                if(!target.isDirectory()){
                    showError("지정한 로컬 폴더를 찾을 수 없습니다: "+folder.path);
                    return;
                }
                browseLocal(target);
                rememberAssignedEntryBoundary(folder, configuredRoot);
                return;
            }
            case "remote":
                try{
                    for(Models.RemoteProfile profile:store.activeRemotes()){
                        if(profile.id.equals(folder.owner)){
                            assignedBackBoundary = new AssignedFolderBackBoundary(
                                    "remote", profile.id,
                                    RemoteProvider.relativePath(configuredRoot.path));
                            openRemote(profile,folder.path);
                            return;
                        }
                    }
                    showError("원격 연결 정보가 없거나 삭제되었습니다.");
                }catch(Exception error){failure(error);}
                return;
            case "saf":
                openAssignedSaf(folder.owner,folder.path);
                rememberAssignedEntryBoundary(folder, configuredRoot);
                return;
            case "storage":
                openAssignedDocumentId(folder.path);
                rememberAssignedEntryBoundary(folder, configuredRoot);
                return;
            default:showError("지원하지 않는 폴더입니다.");
        }
    }

    private void rememberAssignedEntryBoundary(AssignedFolderCatalog.Folder opened,
                                                AssignedFolderCatalog.Folder configured) {
        if (configured == null) configured = opened;
        String root = configured.path;
        if ("storage".equals(opened.kind) && !opened.path.equals(configured.path)) {
            // A legacy document ID may resolve to a local file or SAF URI.
            // Its saved parent has the same number of ancestor segments in
            // whichever physical provider was selected by Android.
            String suffix = opened.path.substring(configured.path.length());
            int levels = 0;
            for (String segment : suffix.split("/")) if (!segment.isEmpty()) levels++;
            if ("local".equals(currentScreen) && localFolder != null) {
                File parent = localFolder;
                for (int i = 0; i < levels && parent.getParentFile() != null; i++)
                    parent = parent.getParentFile();
                root = parent.getAbsolutePath();
            } else if ("saf".equals(currentScreen)) {
                root = safRelative;
                for (int i = 0; i < levels; i++) root = RemoteProvider.parent(root);
            }
        }
        // The actual provider can differ from the assigned role (legacy SD
        // document IDs may resolve to a local or SAF directory).
        if ("local".equals(currentScreen) && localFolder != null) {
            assignedBackBoundary = new AssignedFolderBackBoundary("local", "",
                    "storage".equals(opened.kind) ? root : configured.path);
        } else if ("remote".equals(currentScreen) && remote != null) {
            assignedBackBoundary = new AssignedFolderBackBoundary("remote", remote.id,
                    configured.path);
        } else if ("saf".equals(currentScreen) && safFolder != null) {
            assignedBackBoundary = new AssignedFolderBackBoundary("saf", activeTreeUri,
                    "storage".equals(opened.kind) ? root : configured.path);
        }
    }

    private boolean backFromAssignedEntry() {
        if (assignedBackBoundary == null) return false;
        boolean atRoot = false;
        if ("local".equals(currentScreen) && localFolder != null)
            atRoot = assignedBackBoundary.atRoot("local", "",
                    localFolder.getAbsolutePath());
        else if ("remote".equals(currentScreen) && remote != null)
            atRoot = assignedBackBoundary.atRoot("remote", remote.id, remoteFolder);
        else if ("saf".equals(currentScreen) && safFolder != null)
            atRoot = assignedBackBoundary.atRoot("saf", activeTreeUri, safRelative);
        if (!atRoot) return false;
        showHome();
        return true;
    }
    /** Breadcrumbs may move above the configured root. Once tapped, the
     * chosen ancestor becomes the new boundary for the Back -> Home action. */
    private void navigateAssignedBreadcrumb(String screen, String owner,
                                             String target,
                                             java.util.function.Consumer<String> open) {
        if (assignedBackBoundary != null &&
                !assignedBackBoundary.contains(screen, owner, target))
            assignedBackBoundary = new AssignedFolderBackBoundary(screen, owner, target);
        open.accept(target);
    }
    private void openAssignedSaf(String treeUri,String relative){
        try{
            if(treeUri==null||treeUri.isEmpty()||
                    !hasPersistedTreeRead(Uri.parse(treeUri))){
                showError("SD·USB 폴더 접근 권한이 없습니다. 저장소에서 권한을 다시 설정해 주세요.");
                return;
            }
            activeTreeUri=treeUri;
            activeTreeLabel=treeUri.equals(store.sdTreeUri())?
                    "SD 저장소":"선택한 폴더";
            openSafPath(relative);
        }catch(Exception error){failure(error);}
    }
    private void openAssignedDocumentId(String documentId){
        // Legacy video-folder settings may contain DocumentsContract IDs.
        // Resolve authorized SAF trees first, including removable SD/USB.
        for(android.content.UriPermission grant:
                getContentResolver().getPersistedUriPermissions()){
            if(!grant.isReadPermission())continue;
            try{
                String root=DocumentsContract.getTreeDocumentId(grant.getUri());
                String relative=AssignedFolderCatalog.relativeDocumentId(
                        root,documentId);
                if(relative!=null){
                    openAssignedSaf(grant.getUri().toString(),relative);
                    return;
                }
            }catch(Exception ignored){}
        }
        if(documentId.startsWith("primary:")){
            File folder=new File(Environment.getExternalStorageDirectory(),
                    documentId.substring("primary:".length()));
            if(folder.isDirectory()){
                browseLocal(folder);
                return;
            }
        }
        StorageManager manager=(StorageManager)getSystemService(STORAGE_SERVICE);
        if(manager!=null)for(StorageVolume volume:manager.getStorageVolumes()){
            String uuid=volume.getUuid();
            if(uuid==null||!documentId.startsWith(uuid+":"))continue;
            File root=directSdRoot(volume);
            if(root!=null){
                File folder=new File(root,documentId.substring(uuid.length()+1));
                if(folder.isDirectory()){
                    browseLocal(folder);
                    return;
                }
            }
        }
        showError("영상 폴더에 접근할 수 없습니다. SD·USB 또는 폴더 접근 권한을 다시 지정해 주세요.");
    }
    private void tileEmpty(LinearLayout row) {
        View empty = new View(this);
        row.addView(empty, new LinearLayout.LayoutParams(0, -1, 1));
    }
    private String capacity() {
        try {
            File root = Environment.getExternalStorageDirectory();
            return pretty(root.getFreeSpace()) + " 남음";
        } catch (Exception ignored) { return ""; }
    }
    private String pretty(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + "B";
        double value = bytes;
        String[] units = {"B", "KB", "GB", "GB", "TB"};
        int exponent = 0;
        while (value >= 1024 && exponent < 4) { value /= 1024; exponent++; }
        return String.format(Locale.KOREA, "%.1f%s", value,
                exponent == 2 ? "MB" : units[exponent]);
    }
    private void tile(LinearLayout row, String name, String kind, int tint,
                      String subtitle, Runnable click) {
        LinearLayout col = Ui.vertical(this);
        boolean expanded = expandedLayout();
        col.setGravity((expanded ? Gravity.CENTER_VERTICAL : Gravity.TOP) |
                Gravity.CENTER_HORIZONTAL);
        col.setPadding(dp(expanded ? 12 : 3), dp(expanded ? 12 : 7),
                dp(expanded ? 12 : 3), 0);
        LinearLayout.LayoutParams weight = new LinearLayout.LayoutParams(0, -1, 1);
        if (expanded) {
            weight.leftMargin = dp(6);
            weight.rightMargin = dp(6);
            col.setBackground(Ui.bg(this, Ui.SURFACE, 17, Ui.BORDER));
        }
        row.addView(col, weight);
        LinearLayout square = Ui.vertical(this);
        square.setGravity(Gravity.CENTER);
        if (!expanded)
            square.setBackground(Ui.bg(this, Ui.SURFACE, 18, Ui.BORDER));
        int squareDp = FoldPlayerLayoutPolicy.homeTileDp(expanded);
        col.addView(square, Ui.lp(this, squareDp, squareDp));
        square.addView(new Ui.Icon(this, kind, tint),
                Ui.lp(this, expanded ? 72 : 58, expanded ? 72 : 58));
        Ui.gap(col, this, 10);
        TextView label = Ui.text(this, name, expanded ? 15 : 13.5f, Ui.TEXT, true);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine();
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(label, Ui.lp(this, -1, 25));
        if (!subtitle.isEmpty()) {
            TextView sub = Ui.text(this, subtitle, 11, Ui.MUTED, false);
            sub.setGravity(Gravity.CENTER);
            sub.setSingleLine();
            col.addView(sub, Ui.lp(this, -1, 20));
        }
        col.setOnClickListener(v -> click.run());
    }
    private void menu() {
        new AlertDialog.Builder(this).setItems(OverflowMenuItems.labels(), (d, i) -> {
            switch (i) {
                case OverflowMenuItems.FAVORITES: showFavorites(); break;
                case OverflowMenuItems.REMOTES: showRemoteList(); break;
                case OverflowMenuItems.STORAGE_PERMISSION: requestAllFiles(); break;
                case OverflowMenuItems.UPDATE:
                    if (otaUpdater != null) otaUpdater.manualCheck();
                    break;
                default: break;
            }
        }).show();
    }
    private TvPairingUi pairingUi() {
        if (pairingUi == null) pairingUi = new TvPairingUi(this,store);
        return pairingUi;
    }
    private void startTvPairing() {pairingUi().startTv();}
    private void showSendToTv(String qr) {pairingUi().sendFromPhone(qr);}
    void startPhoneToTvPairing() {
        if(tvDevice)startTvPairing();
        else showSendToTv("");
    }
    private void stopTvPairing() {
        if (pairingUi != null) pairingUi.close();
    }
    private SettingsBackupUi settingsBackupUi() {
        if(settingsBackupUi==null)
            settingsBackupUi=new SettingsBackupUi(this,store,()->{
                folderCache.clearCounts();
                Ui.applyAppearance(this);
                showRemoteList();
            });
        return settingsBackupUi;
    }
    private void showDrawer() {
        if (drawerShown) return;
        drawerShown = true;
        FrameLayout layer = new FrameLayout(this);
        drawerLayer = layer;
        container.addView(layer, new FrameLayout.LayoutParams(-1, -1));
        View shade = new View(this);
        shade.setBackgroundColor(0x99000000);
        drawerShade = shade;
        layer.addView(shade, new FrameLayout.LayoutParams(-1, -1));
        shade.setAlpha(0f);
        shade.setOnClickListener(v -> closeDrawer());
        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        drawerPanel = scroller;
        scroller.setBackgroundColor(Ui.SURFACE);
        FrameLayout.LayoutParams drawerParams = new FrameLayout.LayoutParams(
                dp(FoldPlayerLayoutPolicy.drawerWidthDp(
                        expandedLayout(), currentScreenWidthDp())), -1,
                Gravity.START);
        // Drawer is drawn edge-to-edge. Reserve the system navigation region:
        // the bottom Settings item must never sit behind Android's nav buttons.
        drawerParams.topMargin = FileHubBrandLayout.drawerTopPx(statusBarInsetPx);
        drawerParams.bottomMargin = FileHubBrandLayout.drawerBottomPx(
                navigationBarInsetPx,navigationBarInsetPx);
        layer.addView(scroller, drawerParams);
        ViewCompat.setOnApplyWindowInsetsListener(scroller, (view, insets) -> {
            int safeBottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()).bottom;
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) scroller.getLayoutParams();
            int requestedMargin = FileHubBrandLayout.drawerBottomPx(
                    navigationBarInsetPx,safeBottom);
            if (params.bottomMargin != requestedMargin) {
                params.bottomMargin = requestedMargin;
                scroller.setLayoutParams(params);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(scroller);
        scroller.setTranslationX(-drawerParams.width);
        LinearLayout inside = Ui.vertical(this);
        scroller.addView(inside);
        // The drawer's old 106dp header started behind the status clock.
        // Make its 65dp row sit at the EXACT same Y as the home toolbar
        // (both have the same status-bar inset and toolbar height).
        LinearLayout branding = Ui.horizontal(this);
        branding.setGravity(Gravity.CENTER_VERTICAL);
        branding.setPadding(dp(8), 0, dp(8), 0);
        branding.setBackgroundColor(Ui.DARK);
        inside.addView(branding, Ui.lp(this, -1,
                FileHubBrandLayout.HEADER_HEIGHT_DP));
        // Same 40dp leading slot as the main screen's hamburger.
        // Its image is centered in the full-height slot instead of being
        // positioned by a separate 30x33dp row with its own baseline.
        FrameLayout drawerLeadingSlot = new FrameLayout(this);
        branding.addView(drawerLeadingSlot, Ui.lp(this,
                FileHubBrandLayout.LEADING_SLOT_WIDTH_DP, -1));
        ImageView logoIcon = new ImageView(this);
        logoIcon.setImageResource(R.drawable.filehub_aio_icon);
        logoIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logoIcon.setContentDescription("FileHub AIO 아이콘");
        drawerLeadingSlot.addView(logoIcon, new FrameLayout.LayoutParams(
                dp(30), dp(33), Gravity.CENTER));
        ImageView logoText = new ImageView(this);
        logoText.setImageResource(R.drawable.filehub_aio_wordmark);
        // The PNG has transparent top/bottom padding; FIT_START made its
        // visible letters sit above the neighboring icon. Center the actual
        // bitmap inside this view to align both alpha-content centers.
        logoText.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logoText.setContentDescription("FileHub AIO");
        LinearLayout.LayoutParams brandTitleBounds=Ui.lp(this,
                FileHubBrandLayout.drawerWordmarkWidthDp(
                        FoldPlayerLayoutPolicy.drawerWidthDp(
                                expandedLayout(), currentScreenWidthDp())), 22);
        brandTitleBounds.leftMargin=0;
        branding.addView(logoText, brandTitleBounds);
        drawerLink(inside, "홈", "drive", () -> showHome());

        LinearLayout favoriteEntries = drawerSection(inside,
                "즐겨찾기", "star", DrawerSectionState.FAVORITES);
        try {
            List<Models.Favorite> favorites = store.activeFavorites();
            if (favorites.isEmpty())
                drawerSectionEmpty(favoriteEntries, "등록된 즐겨찾기가 없습니다.");
            FavoriteDrawerReorder favoriteDrag = new FavoriteDrawerReorder(
                    favoriteEntries, scroller, store::reorderFavorites, this::failure);
            for (Models.Favorite f : favorites)
                drawerFavoriteLink(favoriteEntries, f, favoriteDrag);
        } catch (Exception ignored) {
            drawerSectionEmpty(favoriteEntries, "즐겨찾기를 확인할 수 없습니다.");
        }

        LinearLayout networkEntries = drawerSection(inside,
                "네트워크 폴더", "remote", DrawerSectionState.NETWORK);
        try {
            for (Models.RemoteProfile p : store.activeRemotes())
                drawerNestedLink(networkEntries, p.label(), "remote",
                        () -> openRemote(p, "/"));
        } catch (Exception ignored) {
            drawerSectionEmpty(networkEntries, "네트워크 연결을 확인할 수 없습니다.");
        }
        // Network connections can still be managed without a redundant
        // top-level '원격 저장소' drawer item.
        drawerNestedLink(networkEntries, "연결 추가 · 관리", "settings",
                this::showRemoteList);

        inside.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1));
        drawerLink(inside, "설정", "settings", this::openSettings);
        Ui.gap(inside, this, 12);
        shade.animate().alpha(1f).setDuration(220).start();
        scroller.animate().translationX(0).setDuration(250)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
    private void closeDrawer() {
        if (!drawerShown || drawerLayer == null) return;
        final FrameLayout layer = drawerLayer;
        final View panel = drawerPanel;
        final View shade = drawerShade;
        drawerShown = false;
        drawerLayer = null;
        drawerPanel = null;
        drawerShade = null;
        if (shade != null) shade.animate().alpha(0f).setDuration(180).start();
        if (panel != null) {
            panel.animate().translationX(-panel.getWidth()).setDuration(210)
                    .withEndAction(() -> {
                        if (layer.getParent() == container) container.removeView(layer);
                    }).start();
        } else if (layer.getParent() == container) container.removeView(layer);
    }
    private void drawerLink(LinearLayout parent, String text, String kind, Runnable action) {
        LinearLayout row = Ui.horizontal(this);
        row.setPadding(dp(18), 0, dp(12), 0);
        parent.addView(row, Ui.lp(this, -1, 57));
        row.addView(new Ui.Icon(this, kind, Ui.TEAL), Ui.lp(this, 32, 32));
        TextView label = Ui.text(this, text, 15, Ui.TEXT, false);
        LinearLayout.LayoutParams lp = Ui.lp(this, -1, -1);
        lp.leftMargin = dp(15);
        row.addView(label, lp);
        row.setOnClickListener(v -> {
            closeDrawer();
            action.run();
        });
        View divider = new View(this);
        divider.setBackgroundColor(Ui.BORDER);
        parent.addView(divider, Ui.lp(this, -1, 1));
    }
    private LinearLayout drawerSection(LinearLayout parent,
                                       String title, String iconKind, String key) {
        LinearLayout header = Ui.horizontal(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(18), 0, dp(18), 0);
        parent.addView(header, Ui.lp(this, -1, 58));
        header.addView(new Ui.Icon(this, iconKind, Ui.TEAL),
                Ui.lp(this, 32, 32));
        TextView label = Ui.text(this, title, 15, Ui.TEXT, true);
        label.setSingleLine();
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = dp(15);
        header.addView(label, labelParams);
        TextView chevron = Ui.text(this, "▾", 19, Ui.MUTED, true);
        chevron.setGravity(Gravity.CENTER);
        header.addView(chevron, Ui.lp(this, 26, 38));

        LinearLayout nested = Ui.vertical(this);
        parent.addView(nested, Ui.lp(this, -1, -2));
        boolean expanded = drawerSections.expanded(key);
        nested.setVisibility(expanded ? View.VISIBLE : View.GONE);
        chevron.setText(expanded ? "▾" : "▸");
        header.setContentDescription(title + (expanded ? " 접기" : " 펼치기"));
        header.setOnClickListener(v -> {
            boolean nowExpanded = drawerSections.toggle(key);
            nested.setVisibility(nowExpanded ? View.VISIBLE : View.GONE);
            chevron.setText(nowExpanded ? "▾" : "▸");
            header.setContentDescription(title + (nowExpanded ? " 접기" : " 펼치기"));
        });

        View divider = new View(this);
        divider.setBackgroundColor(Ui.BORDER);
        parent.addView(divider, Ui.lp(this, -1, 1));
        return nested;
    }

    private void drawerNestedLink(LinearLayout parent, String title,
                                  String iconKind, Runnable action) {
        LinearLayout row = Ui.horizontal(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(32), 0, dp(14), 0);
        parent.addView(row, Ui.lp(this, -1, 51));
        row.addView(new Ui.Icon(this, iconKind, Ui.TEAL),
                Ui.lp(this, 27, 27));
        TextView label = Ui.text(this, title, 14, Ui.TEXT, false);
        label.setSingleLine();
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.leftMargin = dp(14);
        row.addView(label, labelParams);
        row.setOnClickListener(v -> {
            closeDrawer();
            action.run();
        });
    }

    // Favorite bookmarks use two lines so a custom name does not hide
    // the real folder name, and have an explicit edit menu at the right.
    private void drawerFavoriteLink(LinearLayout parent, Models.Favorite favorite,
                                    FavoriteDrawerReorder reorder) {
        LinearLayout line = Ui.horizontal(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(dp(32), 0, dp(8), 0);
        parent.addView(line, Ui.lp(this, -1, 57));
        line.addView(new Ui.Icon(this, "star", Ui.TEAL), Ui.lp(this, 27, 27));
        LinearLayout texts = Ui.vertical(this);
        texts.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -1, 1);
        labelParams.leftMargin = dp(14);
        line.addView(texts, labelParams);
        TextView main = Ui.text(this, favorite.displayTitle(), 14, Ui.TEXT, false);
        main.setSingleLine(true);
        main.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(main, Ui.lp(this, -1, 25));
        if (favorite.hasCustomTitle()) {
            TextView original = Ui.text(this, favorite.title, 10, Ui.MUTED, false);
            original.setSingleLine(true);
            original.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(original, Ui.lp(this, -1, 16));
        }
        TextView options = Ui.text(this, "⋮", 20, Ui.MUTED, true);
        options.setGravity(Gravity.CENTER);
        options.setContentDescription(favorite.displayTitle() + " 즐겨찾기 편집");
        line.addView(options, Ui.lp(this, 39, 45));
        options.setOnClickListener(v -> {
            closeDrawer();
            showFavoriteActions(favorite);
        });
        line.setOnClickListener(v -> {
            closeDrawer();
            openFavorite(favorite);
        });
        // Long press drags the row; the right-side menu edits its label/deletes.
        reorder.add(line, favorite.key());
    }

    private void drawerSectionEmpty(LinearLayout parent, String text) {
        TextView empty = Ui.text(this, text, 12, Ui.MUTED, false);
        empty.setGravity(Gravity.CENTER_VERTICAL);
        empty.setPadding(dp(48), 0, dp(10), 0);
        parent.addView(empty, Ui.lp(this, -1, 46));
    }

    private String folderScope() {
        if ("remote".equals(currentScreen) && remote != null)
            return "remote:" + remote.id + ":" + remoteFolder;
        if ("local".equals(currentScreen) && localFolder != null)
            return "local:" + localFolder.getAbsolutePath();
        if ("saf".equals(currentScreen) && safFolder != null) {
            try {
                return "storage:" + DocumentsContract.getDocumentId(safFolder.getUri());
            } catch (Exception ignored) {
                return "saf:" + activeTreeUri + ":" + safRelative;
            }
        }
        return null;
    }
    /** Include SAF provider and tree so identically named SD locations differ. */
    private String folderSortScope(){
        if("saf".equals(currentScreen)&&safFolder!=null)
            return "sort-saf:"+activeTreeUri+":"+safFolder.getUri();
        return folderScope();
    }
    /** Exact folder override, otherwise the global file manager sort. */
    private int currentFolderSortMode(){
        return FolderSortPreferences.get(this,folderSortScope(),sortMode);
    }
    private void openSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        String scope = folderScope();
        if (scope != null)
            intent.putExtra(SettingsActivity.EXTRA_CURRENT_SCOPE, scope);
        startActivityForResult(intent, REQ_SETTINGS);
    }
    private void showFavorites() {
        currentScreen = "favorites";
        render("즐겨찾기", true, null, null);
        try {
            List<Models.Favorite> items = store.activeFavorites();
            if (items.isEmpty()) muted("즐겨찾기한 폴더가 없습니다.");
            for (Models.Favorite favorite : items) favoriteRow(page, favorite);
        } catch (Exception e) { failure(e); }
    }
    private void favoriteRow(LinearLayout target, Models.Favorite f) {
        LinearLayout box = Ui.horizontal(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), dp(6), dp(7), dp(6));
        box.setBackground(Ui.bg(this, Ui.SURFACE, 13, Ui.BORDER));
        LinearLayout.LayoutParams rowParams = Ui.lp(this, -1, 70);
        rowParams.bottomMargin = dp(7);
        target.addView(box, rowParams);
        box.addView(new Ui.Icon(this, "star", 0xFFE5A542),
                Ui.lp(this, 42, 42));
        LinearLayout labels = Ui.vertical(this);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, -1, 1);
        textParams.leftMargin = dp(13);
        box.addView(labels, textParams);
        TextView name = Ui.text(this, f.displayTitle(), 15.5f, Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name, Ui.lp(this, -1, 27));
        String description = f.hasCustomTitle() ? f.title :
                f.kind.equals("remote") ? "네트워크 폴더" :
                        f.kind.equals("saf") ? "SD 저장소" : "메인 저장소";
        TextView original = Ui.text(this, description, 10, Ui.MUTED, false);
        original.setSingleLine(true);
        original.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(original, Ui.lp(this, -1, 19));
        TextView options = Ui.text(this, "⋮", 23, Ui.MUTED, false);
        options.setGravity(Gravity.CENTER);
        options.setContentDescription(f.displayTitle() + " 즐겨찾기 이름 변경 또는 삭제");
        box.addView(options, Ui.lp(this, 39, 48));
        options.setOnClickListener(v -> showFavoriteActions(f));
        labels.setOnClickListener(v -> openFavorite(f));
        box.setOnClickListener(v -> openFavorite(f));
        box.setOnLongClickListener(v -> {
            showFavoriteActions(f);
            return true;
        });
    }
    private void showFavoriteActions(Models.Favorite favorite) {
        new AlertDialog.Builder(this)
                .setTitle(favorite.displayTitle())
                .setItems(new String[]{"즐겨찾기 이름 변경", "즐겨찾기 삭제"},
                        (dialog, selected) -> {
                            if (selected == 0) editFavoriteName(favorite);
                            else confirmRemoveFavorite(favorite);
                        }).show();
    }

    private void confirmRemoveFavorite(Models.Favorite favorite) {
        new AlertDialog.Builder(this)
                .setTitle("즐겨찾기 삭제")
                .setMessage(favorite.displayTitle() +
                        " 즐겨찾기를 삭제할까요?\n원본 폴더는 삭제되지 않습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    try {
                        if (store.isFavorite(favorite)) store.toggleFavorite(favorite);
                        refreshFavoriteSurfaces();
                    } catch (Exception error) { failure(error); }
                }).show();
    }

    private void refreshFavoriteSurfaces() {
        if ("home".equals(currentScreen)) showHome();
        else if ("favorites".equals(currentScreen)) showFavorites();
        else updateFavoriteIndicators();
    }

    private void editFavoriteName(Models.Favorite favorite) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(16);
        input.setHint("즐겨찾기에 표시할 이름");
        input.setText(favorite.displayTitle());
        input.setSelection(input.length());
        LinearLayout content = Ui.vertical(this);
        content.setPadding(dp(20), dp(8), dp(20), dp(2));
        TextView original = Ui.text(this, "실제 폴더명: " + favorite.title,
                12, Ui.MUTED, false);
        content.addView(original, Ui.lp(this, -1, -2));
        content.addView(input, Ui.lp(this, -1, 54));
        TextView note = Ui.text(this,
                "즐겨찾기에 표시되는 이름만 변경되며 실제 폴더명은 그대로입니다.",
                11, Ui.MUTED, false);
        content.addView(note, Ui.lp(this, -1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("즐겨찾기 이름 변경")
                .setView(content)
                .setNegativeButton("취소", null)
                .setNeutralButton("원래 이름", null)
                .setPositiveButton("저장", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    store.renameFavorite(favorite, input.getText().toString());
                    dialog.dismiss();
                    refreshFavoriteSurfaces();
                    toast("즐겨찾기 이름을 변경했습니다.");
                } catch (IllegalArgumentException invalid) {
                    input.setError(invalid.getMessage());
                } catch (Exception error) { failure(error); }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    store.renameFavorite(favorite, "");
                    dialog.dismiss();
                    refreshFavoriteSurfaces();
                    toast("원래 폴더명으로 표시합니다.");
                } catch (Exception error) { failure(error); }
            });
        });
        dialog.show();
    }

    private void openFavorite(Models.Favorite f) {
        if ("remote".equals(f.kind)) {
            try {
                for (Models.RemoteProfile item : store.activeRemotes())
                    if (item.id.equals(f.remoteId)) { openRemote(item, f.path); return; }
                showError("삭제된 원격 연결입니다.");
            } catch (Exception ex) { failure(ex); }
        } else if ("saf".equals(f.kind)) {
            activeTreeUri = f.remoteId;
            activeTreeLabel = activeTreeUri.equals(store.sdTreeUri()) ?
                    "SD 저장소" : "선택한 폴더";
            openSafPath(f.path);
        } else browseLocal(new File(f.path));
    }
    private void row(LinearLayout parent, String label, String subtitle, String iconType,
                     int color, Runnable click, Runnable longClick) {
        LinearLayout box = Ui.horizontal(this);
        box.setPadding(dp(12), dp(7), dp(11), dp(7));
        box.setBackground(Ui.bg(this, Ui.SURFACE, 13, Ui.BORDER));
        LinearLayout.LayoutParams params = Ui.lp(this, -1, 72);
        params.bottomMargin = dp(7);
        parent.addView(box, params);
        box.addView(new Ui.Icon(this, iconType, color), Ui.lp(this, 42, 42));
        LinearLayout labels = Ui.vertical(this);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(0, -1, 1);
        l.leftMargin = dp(13);
        box.addView(labels, l);
        TextView name = Ui.text(this, label, 15.5f, Ui.TEXT, true);
        name.setSingleLine();
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name, Ui.lp(this, -1, 27));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = Ui.text(this, subtitle, 12, Ui.MUTED, false);
            sub.setSingleLine();
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(sub, Ui.lp(this, -1, 23));
        }
        TextView arrow = Ui.text(this, "›", 23, Ui.MUTED, false);
        arrow.setGravity(Gravity.CENTER);
        box.addView(arrow, Ui.lp(this, 22, 36));
        box.setOnClickListener(v -> click.run());
        if (longClick != null) box.setOnLongClickListener(v -> {
            longClick.run();
            return true;
        });
    }

    private static final class SearchResult {
        final String name;
        final Runnable open;
        SearchResult(String name, Runnable open) {
            this.name = name; this.open = open;
        }
    }
    private static final class LazyUiJob {
        final View anchor;
        final Runnable start;
        boolean started;
        LazyUiJob(View anchor, Runnable start) {
            this.anchor = anchor; this.start = start;
        }
    }
    private void requestVisibleJobs() {
        if (currentScroll == null) return;
        for (LazyUiJob job : lazyJobs) {
            if (!job.started && job.anchor.isAttachedToWindow() &&
                    job.anchor.getGlobalVisibleRect(new android.graphics.Rect())) {
                job.started = true;
                job.start.run();
            }
        }
    }
    private void addLazyJob(View anchor, Runnable start) {
        lazyJobs.add(new LazyUiJob(anchor, start));
        anchor.post(this::requestVisibleJobs);
    }
    private String countKey(Models.Entry entry) {
        if (entry == null || !entry.directory) return null;
        String scope = "remote".equals(currentScreen) && remote != null ?
                FolderSessionCache.key(remote, entry.path) :
                entry.file != null ? "local|" + entry.file.getAbsolutePath() :
                entry.document != null ? "saf|" + entry.document.getUri() :
                "uri|" + entry.path;
        return scope + "|count|hidden:" + showHidden + "|subtitles:" +
                PlayerSettings.prefs(this).getBoolean(PlayerSettings.HIDE_SUBTITLES, false);
    }
    private void scheduleCount(View anchor, TextView target,
                               Models.Entry entry, Callable<Integer> count) {
        String key = countKey(entry);
        Integer remembered = folderCache.count(key);
        if (remembered != null) {
            target.setText(remembered + "개 항목");
            return;
        }
        int screen = renderGeneration;
        addLazyJob(anchor, () -> previewWorker.execute(() -> {
            String result;
            int computed = -1;
            try {
                computed = count.call();
                result = computed < 0 ? "항목 수 미확인" : computed + "개 항목";
            } catch (Exception ignored) {
                result = "항목 수 미확인";
            }
            String label = result;
            int finalCount = computed;
            runOnUiThread(() -> {
                if (screen == renderGeneration && anchor.isAttachedToWindow()) {
                    if (finalCount >= 0) folderCache.rememberCount(key, finalCount);
                    target.setText(label);
                }
            });
        }));
    }
    private void scheduleThumbnail(View anchor, FrameLayout iconBox,
                                   Models.Entry item) {
        if (item.directory) return;
        boolean video = VideoThumbnailPolicy.eligible(item.name, false);
        if (video ? !showVideoThumbnails :
                (!showThumbnails || !"image".equals(iconFor(item.name)))) return;
        int screen = renderGeneration;
        Models.RemoteProfile provider = remote;
        addLazyJob(anchor, () -> previewWorker.execute(() -> {
            Bitmap image = null;
            try {
                if (video) {
                    image = VideoThumbnailLoader.load(getApplicationContext(), item,
                            provider);
                } else if (item.file != null) {
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(item.file.getAbsolutePath(), bounds);
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    int size = Math.max(bounds.outWidth, bounds.outHeight);
                    options.inSampleSize = 1;
                    while (size / options.inSampleSize > 200)
                        options.inSampleSize *= 2;
                    image = BitmapFactory.decodeFile(item.file.getAbsolutePath(), options);
                } else if (item.document != null) {
                    try (InputStream stream = getContentResolver().openInputStream(
                            item.document.getUri())) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 4;
                        image = BitmapFactory.decodeStream(stream, null, options);
                    }
                } else if (item.uri != null) {
                    try (InputStream stream = getContentResolver().openInputStream(item.uri)) {
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 4;
                        image = BitmapFactory.decodeStream(stream, null, options);
                    }
                } else if (item.size >= 0 && item.size <= 1024 * 1024 &&
                        provider != null && "remote".equals(currentScreen)) {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    OutputStream bounded = new java.io.FilterOutputStream(bytes) {
                        int used;
                        @Override public void write(int b) throws IOException {
                            if (++used > 1024 * 1024) throw new IOException("Preview limit");
                            out.write(b);
                        }
                        @Override public void write(byte[] b, int o, int l) throws IOException {
                            if (used + l > 1024 * 1024)
                                throw new IOException("Preview limit");
                            out.write(b, o, l); used += l;
                        }
                    };
                    RemoteProvider.download(provider, item.path, bounded);
                    byte[] raw = bytes.toByteArray();
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = 2;
                    image = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
                }
            } catch (Exception ignored) {}
            Bitmap ready = image;
            runOnUiThread(() -> {
                if (ready == null || screen != renderGeneration ||
                        !anchor.isAttachedToWindow()) {
                    if (ready != null) ready.recycle();
                    return;
                }
                ImageView thumbnail = new ImageView(this);
                thumbnail.setImageBitmap(ready);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iconBox.removeAllViews();
                iconBox.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
                if (video) {
                    TextView play = Ui.text(this, "▶", 17, Color.WHITE, true);
                    play.setGravity(Gravity.CENTER);
                    play.setBackground(Ui.bg(this, 0x99000000, 100, 0));
                    FrameLayout.LayoutParams overlay = new FrameLayout.LayoutParams(
                            dp(25), dp(25), Gravity.CENTER);
                    iconBox.addView(play, overlay);
                }
            });
        }));
    }
    private FrameLayout fileIcon(Models.Entry entry, int dimension) {
        FrameLayout frame = new FrameLayout(this);
        int side = dp(dimension);
        frame.addView(new Ui.Icon(this,
                entry.directory ? "folder" : iconFor(entry.name),
                entry.directory ? 0xFFE5AD5B : Ui.TEAL),
                new FrameLayout.LayoutParams(side, side));
        return frame;
    }
    private int subtitleBadgeFlags(Models.Entry entry) {
        if (entry.directory || !isVideo(entry.name)) return 0;
        boolean external = !SubtitleInventory.externalLabel(
                siblingSubtitleIndex, entry.name).isEmpty();
        String id = SubtitleInventory.entryId(entry,
                "remote".equals(currentScreen) ? remote : null);
        boolean embedded = SubtitleInventory.status(this, id) == 1;
        return SubtitleBadgePolicy.flags(external, embedded);
    }
    private String fileSecondary(Models.Entry entry) {
        return entry.directory ? "항목 수 확인 중" : pretty(entry.size);
    }
    private int readingProgressFor(Models.Entry entry) {
        if(entry.directory)return -1;
        String kind=currentScreen;
        if(!"local".equals(kind)&&!"remote".equals(kind)&&!"saf".equals(kind))
            return -1;
        String mode=ComicArchive.supports(entry.name)?ReaderFolders.COMIC:
                (entry.name.toLowerCase(Locale.ROOT).endsWith(".txt") ||
                 entry.name.toLowerCase(Locale.ROOT).endsWith(".epub") ||
                 PdfBookTypes.supports(entry.name))?
                        ReaderFolders.TEXT:null;
        if(mode==null)return -1;
        String owner="",folder,path=null,uri=null;
        if("local".equals(kind) && entry.file!=null){
            path=entry.file.getAbsolutePath();
            folder=entry.file.getParent();
        }else if("remote".equals(kind) && remote!=null){
            owner=remote.id;
            path=entry.path;
            folder=RemoteProvider.parent(entry.path);
        }else if("saf".equals(kind) && entry.document!=null){
            owner=activeTreeUri;
            folder=safRelative;
            uri=entry.document.getUri().toString();
        }else return -1;
        if(!readerFolders.allowed(mode,kind,owner,folder))
            return -1;
        String id=ReaderHistory.id(kind,"remote".equals(kind)?owner:null,path,uri);
        return ReaderHistory.percent(this,id);
    }

    private void addReadingProgressChip(LinearLayout holder,int value) {
        TextView badge=Ui.text(this,value+"% 읽음",10.2f,
                Ui.darkMode?0xFF8DE4BE:0xFF13734D,true);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setPadding(dp(5),0,dp(5),0);
        badge.setBackground(Ui.bg(this,
                Ui.darkMode?0xFF214336:0xFFE0F5E8,8,0));
        LinearLayout.LayoutParams lp=Ui.lp(this,-2,18);
        lp.leftMargin=dp(6);
        holder.addView(badge,lp);
    }
    private void addSubtitleChip(LinearLayout holder, String label, boolean external) {
        int fg = external ?
                (Ui.darkMode ? 0xFF70E1B2 : 0xFF087B59) :
                (Ui.darkMode ? 0xFFFFA0A3 : 0xFFBB424C);
        int bg = external ?
                (Ui.darkMode ? 0xFF1B3932 : 0xFFE1F5E9) :
                (Ui.darkMode ? 0xFF45272A : 0xFFFDE9EB);
        TextView chip = Ui.text(this, "● " + label, 10.2f, fg, true);
        chip.setGravity(Gravity.CENTER);
        chip.setSingleLine(true);
        chip.setPadding(dp(5), 0, dp(5), 0);
        chip.setBackground(Ui.bg(this, bg, 10, 0));
        LinearLayout.LayoutParams params = Ui.lp(this, -2, 18);
        params.rightMargin = dp(4);
        holder.addView(chip, params);
    }
    private void refreshSubtitleChips(LinearLayout holder, Models.Entry entry) {
        if (holder == null) return;
        holder.removeAllViews();
        int flags = subtitleBadgeFlags(entry);
        if ((flags & SubtitleBadgePolicy.EXTERNAL) != 0)
            addSubtitleChip(holder, "외부", true);
        if ((flags & SubtitleBadgePolicy.EMBEDDED) != 0)
            addSubtitleChip(holder, "내장", false);
    }
    private void scheduleLocalEmbeddedSubtitleProbe(View anchor, LinearLayout chips,
                                                     Models.Entry entry) {
        if (entry.directory || !isVideo(entry.name) ||
                !SubtitleInventory.canProbeLocally(entry)) return;
        String id = SubtitleInventory.entryId(entry, null);
        // A v0.1.17 negative result can be stale: its detector did not
        // understand some single-track Matroska layouts. Recheck once per
        // process with the new parser, even if a former version stored 0.
        if (id == null || SubtitleInventory.status(this, id) == 1 ||
                localSubtitleProbed.contains(id)) return;
        int generation = renderGeneration;
        addLazyJob(anchor, () -> {
            if (!localSubtitleProbed.add(id)) return;
            previewWorker.execute(() -> {
            try {
                boolean embedded = SubtitleInventory.probeLocal(
                        getApplicationContext(), entry);
                SubtitleInventory.record(getApplicationContext(), id, embedded);
                if (embedded) runOnUiThread(() -> {
                    if (generation == renderGeneration && anchor.isAttachedToWindow())
                        refreshSubtitleChips(chips, entry);
                });
            } catch (Exception ignored) {
                // Unsupported subtitle containers may be detected during playback.
            }
            });
        });
    }
    private void scheduleRemoteEmbeddedSubtitleProbe(View anchor, LinearLayout chips,
                                                      Models.Entry entry) {
        Models.RemoteProfile source = remote;
        if (entry.directory || source == null || !"remote".equals(currentScreen) ||
                !MatroskaSubtitleProbe.supportsName(entry.name)) return;
        String id = SubtitleInventory.entryId(entry, source);
        if (id == null || SubtitleInventory.status(this, id) == 1 ||
                videoHeadersProbed.contains(id)) return;
        int generation = renderGeneration;
        // One bounded, early-exit MKV header reader; independent of the directory
        // list/count worker pool so badges cannot block count updates.
        addLazyJob(anchor, () -> {
            if (!videoHeadersProbed.add(id)) return;
            subtitleProbeWorker.execute(() -> {
            try {
                if (!MatroskaSubtitleProbe.remoteHeaderContainsSubtitles(source, entry))
                    return; // Inconclusive; tracks may be outside the header.
                SubtitleInventory.record(getApplicationContext(), id, true);
                runOnUiThread(() -> {
                    if (generation == renderGeneration && anchor.isAttachedToWindow())
                        refreshSubtitleChips(chips, entry);
                });
            } catch (Exception ignored) {
                // Retry the next time the row is visited if NAS I/O fails.
                // Do not turn a transient network error into a cached miss.
                videoHeadersProbed.remove(id);
            }
            });
        });
    }
    private int folderPurposeFlags(Models.Entry entry) {
        if (!entry.directory) return 0;
        FileTransferEngine.Item item = fileItem(entry);
        if (item == null || !item.directory || "media".equals(item.kind)) return 0;
        String owner = "remote".equals(item.kind) && item.remote != null ?
                item.remote.id : "saf".equals(item.kind) ? item.tree : "";
        return FolderPurposePolicy.labels(folderPurposeVideos, folderPurposeReaders,
                item.kind, owner, item.path, videoScopeForFolder(item));
    }

    private void addFolderPurposeChip(LinearLayout holder,
                                      String label, boolean video) {
        int fg = video ? (Ui.darkMode ? 0xFF9ACAFF : 0xFF235F9D) :
                (Ui.darkMode ? 0xFF8BE0BA : 0xFF147352);
        int bg = video ? (Ui.darkMode ? 0xFF20384D : 0xFFE5F0FB) :
                (Ui.darkMode ? 0xFF1F4034 : 0xFFE0F4E9);
        TextView badge = Ui.text(this, label, 9.5f, fg, true);
        badge.setGravity(Gravity.CENTER);
        badge.setSingleLine(true);
        badge.setPadding(dp(4), 0, dp(4), 0);
        badge.setBackground(Ui.bg(this, bg, 7, 0));
        LinearLayout.LayoutParams chipParams = Ui.lp(this, -2, 17);
        chipParams.rightMargin = dp(3);
        holder.addView(badge, chipParams);
    }

    private LinearLayout folderPurposeChips(int purposeFlags) {
        LinearLayout result = Ui.horizontal(this);
        result.setGravity(Gravity.CENTER_VERTICAL);
        if ((purposeFlags & FolderPurposePolicy.VIDEO) != 0)
            addFolderPurposeChip(result, "동영상폴더", true);
        if ((purposeFlags & FolderPurposePolicy.READING) != 0)
            addFolderPurposeChip(result, "독서용폴더", false);
        return result;
    }

    private void fileRow(Models.Entry entry, Runnable open,
                         Callable<Integer> count) {
        if (!FileViewOptions.visible(entry.name,
                entry.file != null && entry.file.isHidden(), showHidden)) return;
        if (PlayerSettings.hideThisFile(this, folderScope(), entry.name)) return;
        if (!entry.directory && ComicArchive.supports(entry.name) &&
                visibleComicVolumes.size()<BookSeriesQueue.MAX_VOLUMES) {
            if ("local".equals(currentScreen)&&entry.file!=null)
                visibleComicVolumes.add(new BookSeriesQueue.Volume(
                        entry.name,entry.file.getAbsolutePath(),null));
            else if ("saf".equals(currentScreen)&&entry.document!=null)
                visibleComicVolumes.add(new BookSeriesQueue.Volume(
                        entry.name,null,entry.document.getUri().toString()));
            else if ("remote".equals(currentScreen)&&remote!=null)
                visibleComicVolumes.add(new BookSeriesQueue.Volume(
                        entry.name,entry.path,null));
        }
        if(!completeFolderSearchIndexed)
            currentSearch.add(new SearchResult(entry.name, open));
        String sub = fileSecondary(entry);
        int readingPercent=readingProgressFor(entry);
        int purposeFlags = folderPurposeFlags(entry);
        FrameLayout iconBox;
        TextView secondary;
        LinearLayout chips = Ui.horizontal(this);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        View anchor;
        if (layoutMode == FileViewOptions.GRID) {
            int gridColumns = FoldPlayerLayoutPolicy.fileGridColumns(
                    expandedLayout(), currentScreenWidthDp());
            if (gridCellIndex % gridColumns == 0) {
                activeGridRow = Ui.horizontal(this);
                activeGridRow.setGravity(Gravity.TOP);
                LinearLayout.LayoutParams rp = Ui.lp(this, -1, 152);
                rp.bottomMargin = dp(5);
                page.addView(activeGridRow, rp);
            }
            LinearLayout tile = Ui.vertical(this);
            tile.setPadding(dp(4), dp(8), dp(4), dp(3));
            tile.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            activeGridRow.addView(tile, new LinearLayout.LayoutParams(0, -1, 1));
            iconBox = fileIcon(entry, 52);
            tile.addView(iconBox, Ui.lp(this, 52, 52));
            Ui.gap(tile, this, 6);
            TextView name = Ui.text(this, entry.name, 12.5f, Ui.TEXT, true);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tile.addView(name, Ui.lp(this, -1, 35));
            secondary = Ui.text(this, sub, 11, Ui.MUTED, false);
            secondary.setGravity(Gravity.CENTER);
            secondary.setSingleLine();
            tile.addView(secondary, Ui.lp(this, -1, 19));
            if(readingPercent>=0){
                LinearLayout readBadge=Ui.horizontal(this);
                readBadge.setGravity(Gravity.CENTER);
                addReadingProgressChip(readBadge,readingPercent);
                tile.addView(readBadge,Ui.lp(this,-1,19));
            }
            if (purposeFlags != 0) {
                LinearLayout roles = folderPurposeChips(purposeFlags);
                roles.setGravity(Gravity.CENTER);
                tile.addView(roles, Ui.lp(this, -1, 19));
            }
            if (!entry.directory && isVideo(entry.name)) {
                chips.setGravity(Gravity.CENTER);
                tile.addView(chips, Ui.lp(this, -1, 19));
            }
            tile.setOnClickListener(v -> open.run());
            anchor = tile;
            gridCellIndex++;
        } else {
            LinearLayout strip = Ui.horizontal(this);
            int height = layoutMode == FileViewOptions.DETAILS ? 78 : 60;
            strip.setPadding(dp(9), dp(3), dp(10), dp(3));
            strip.setBackgroundColor(Ui.SURFACE);
            page.addView(strip, Ui.lp(this, -1, height));
            iconBox = fileIcon(entry, 39);
            strip.addView(iconBox, Ui.lp(this, 39, 39));
            LinearLayout labels = Ui.vertical(this);
            labels.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            lp.leftMargin = dp(12);
            strip.addView(labels, lp);
            TextView name = Ui.text(this, entry.name, 15.5f, Ui.TEXT, true);
            name.setSingleLine();
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labels.addView(name, Ui.lp(this, -1,
                    layoutMode == FileViewOptions.DETAILS ? 29 : 25));
            LinearLayout metadata = Ui.horizontal(this);
            labels.addView(metadata, Ui.lp(this, -1, 19));
            secondary = Ui.text(this, sub, 12, Ui.MUTED, false);
            secondary.setSingleLine();
            secondary.setEllipsize(android.text.TextUtils.TruncateAt.END);
            if(!entry.directory && readingPercent>=0){
                metadata.addView(secondary,Ui.lp(this,-2,19));
                addReadingProgressChip(metadata,readingPercent);
                metadata.addView(new View(this),
                        new LinearLayout.LayoutParams(0,dp(1),1));
            }else if (entry.directory && purposeFlags != 0) {
                secondary.setMaxWidth(dp(95));
                metadata.addView(secondary, Ui.lp(this, -2, 19));
                LinearLayout roles = folderPurposeChips(purposeFlags);
                LinearLayout.LayoutParams roleParams = Ui.lp(this, -2, 19);
                roleParams.leftMargin = dp(5);
                metadata.addView(roles, roleParams);
                metadata.addView(new View(this),
                        new LinearLayout.LayoutParams(0, dp(1), 1));
            } else if (!entry.directory && isVideo(entry.name)) {
                metadata.addView(secondary, Ui.lp(this, -2, 19));
                LinearLayout.LayoutParams chipParams = Ui.lp(this, -2, 19);
                chipParams.leftMargin = dp(6);
                metadata.addView(chips, chipParams);
                metadata.addView(new View(this),
                        new LinearLayout.LayoutParams(0, dp(1), 1));
            } else {
                metadata.addView(secondary,
                        new LinearLayout.LayoutParams(0, dp(19), 1));
            }
            if (entry.modified > 0) {
                TextView date = Ui.text(this, shortDate(entry.modified),
                        11f, Ui.MUTED, false);
                date.setSingleLine(true);
                date.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                date.setMinWidth(dp(77));
                date.setContentDescription("파일 수정 또는 생성 날짜 " +
                        shortDate(entry.modified));
                LinearLayout.LayoutParams dateParams = Ui.lp(this, -2, 19);
                dateParams.leftMargin = dp(8);
                metadata.addView(date, dateParams);
            }
            if (entry.directory) {
                TextView arrow = Ui.text(this, "›", 22, Ui.MUTED, false);
                arrow.setGravity(Gravity.CENTER);
                strip.addView(arrow, Ui.lp(this, 17, -1));
            }
            View border = new View(this);
            border.setBackgroundColor(Ui.BORDER);
            page.addView(border, Ui.lp(this, -1, 1));
            strip.setOnClickListener(v -> open.run());
            anchor = strip;
        }
        FileTransferEngine.Item selectable = fileItem(entry);
        if (selectable != null) {
            visibleFileItems.put(selectable.key(), selectable);
            selectableRows.put(selectable.key(), anchor);
            anchor.setOnClickListener(v -> {
                if (!selection.isEmpty()) toggleFileSelection(selectable);
                else open.run();
            });
            anchor.setOnLongClickListener(v -> {
                toggleFileSelection(selectable);
                return true;
            });
        }
        if (entry.directory && count != null)
            scheduleCount(anchor, secondary, entry, count);
        else scheduleThumbnail(anchor, iconBox, entry);
        refreshSubtitleChips(chips, entry);
        scheduleLocalEmbeddedSubtitleProbe(anchor, chips, entry);
        scheduleRemoteEmbeddedSubtitleProbe(anchor, chips, entry);
    }

    private void showViewSettings() {
        final String thisFolder=folderSortScope();
        final boolean hasFolder=thisFolder!=null&&!thisFolder.isEmpty();
        final boolean overridden=hasFolder&&FolderSortPreferences.has(
                this,thisFolder);
        LinearLayout form = Ui.vertical(this);
        form.setPadding(dp(20), dp(12), dp(20), dp(8));
        form.addView(Ui.text(this, "보기 방식", 15, Ui.TEAL, true));
        RadioGroup layouts = new RadioGroup(this);
        layouts.setOrientation(LinearLayout.HORIZONTAL);
        String[] modes = {"☷ 목록", "▦ 격자", "≡ 자세히"};
        int[] layoutIds = new int[modes.length];
        for (int i = 0; i < modes.length; i++) {
            RadioButton option = new RadioButton(this);
            layoutIds[i] = View.generateViewId();
            option.setId(layoutIds[i]);
            option.setText(modes[i]);
            option.setTextSize(13);
            layouts.addView(option, new RadioGroup.LayoutParams(0, dp(52), 1));
        }
        layouts.check(layoutIds[Math.max(0, Math.min(2, layoutMode))]);
        form.addView(layouts, Ui.lp(this, -1, 56));
        Ui.gap(form, this, 8);
        form.addView(Ui.text(this, "정렬", 15, Ui.TEAL, true));
        RadioGroup sorting = new RadioGroup(this);
        sorting.setOrientation(LinearLayout.VERTICAL);
        String[] choices = {
                "이름 ▲ (오름차순)", "이름 ▼ (내림차순)",
                "날짜 · 최신순", "날짜 · 오래된순",
                "크기 · 큰 파일 먼저", "크기 · 작은 파일 먼저"
        };
        int[] sortIds = new int[choices.length];
        for (int i = 0; i < choices.length; i++) {
            RadioButton option = new RadioButton(this);
            sortIds[i] = View.generateViewId();
            option.setId(sortIds[i]);
            option.setText(choices[i]);
            option.setTextSize(14);
            sorting.addView(option, Ui.lp(this, -1, 43));
        }
        sorting.check(sortIds[Math.max(0,Math.min(5,
                hasFolder?currentFolderSortMode():sortMode))]);
        form.addView(sorting);
        CheckBox onlyHere=new CheckBox(this);
        onlyHere.setText("이 폴더에만 적용 (하위 폴더 제외)");
        onlyHere.setChecked(overridden);
        onlyHere.setEnabled(hasFolder);
        form.addView(onlyHere,Ui.lp(this,-1,49));
        TextView sortHint=Ui.text(this,hasFolder?
                "체크하면 이 폴더에서만 선택한 정렬을 사용하며, 하위 폴더와 다른 저장소에는 영향이 없습니다. "+
                "기존 체크를 해제하면 이 폴더는 전체 기본 정렬로 돌아갑니다.":
                "폴더 안에서 열면 '이 폴더에만 적용'을 사용할 수 있습니다.",
                12,Ui.MUTED,false);
        form.addView(sortHint,Ui.lp(this,-1,-2));
        Ui.gap(form, this, 8);
        form.addView(Ui.text(this, "기타", 15, Ui.TEAL, true));
        CheckBox hidden = new CheckBox(this);
        hidden.setText("숨겨진 파일 표시");
        hidden.setChecked(showHidden);
        form.addView(hidden, Ui.lp(this, -1, 49));
        CheckBox thumbs = new CheckBox(this);
        thumbs.setText("썸네일 표시 (이미지)");
        thumbs.setChecked(showThumbnails);
        form.addView(thumbs, Ui.lp(this, -1, 49));
        CheckBox videoThumbs = new CheckBox(this);
        videoThumbs.setText("동영상 실제 장면 미리보기 (기본 켜짐)");
        videoThumbs.setChecked(showVideoThumbnails);
        form.addView(videoThumbs, Ui.lp(this, -1, 49));
        TextView hint = Ui.text(this,
                "원격 썸네일은 1MB 이하 이미지에서만 다운로드합니다. " +
                        "NAS 동영상은 구간 읽기로 일부만 가져오며, " +
                        "지원되지 않는 영상은 기본 재생 아이콘으로 표시합니다.",
                12, Ui.MUTED, false);
        form.addView(hint, Ui.lp(this, -1, -2));
        ScrollView content = new ScrollView(this);
        content.addView(form);
        new AlertDialog.Builder(this).setTitle("보기 및 정렬")
                .setView(content)
                .setNegativeButton("취소", null)
                .setPositiveButton("적용", (d, which) -> {
                    for (int i=0; i<layoutIds.length; i++)
                        if (layouts.getCheckedRadioButtonId() == layoutIds[i])
                            layoutMode = i;
                    int selectedSort=sortMode;
                    for (int i=0; i<sortIds.length; i++)
                        if (sorting.getCheckedRadioButtonId() == sortIds[i])
                            selectedSort = i;
                    if(hasFolder && onlyHere.isChecked())
                        FolderSortPreferences.set(this,thisFolder,selectedSort);
                    else{
                        if(hasFolder)FolderSortPreferences.clear(this,thisFolder);
                        if(!overridden)sortMode=selectedSort;
                    }
                    showHidden = hidden.isChecked();
                    showThumbnails = thumbs.isChecked();
                    showVideoThumbnails = videoThumbs.isChecked();
                    getSharedPreferences("file_view", MODE_PRIVATE).edit()
                            .putInt("layout", layoutMode)
                            .putInt("sort", sortMode)
                            .putBoolean("hidden", showHidden)
                            .putBoolean("thumbnails", showThumbnails)
                            .putBoolean("video_thumbnails", showVideoThumbnails)
                            .apply();
                    refreshCurrent();
                }).show();
    }
    private void searchCurrentFolder() {
        List<SearchResult> snapshot = new ArrayList<>(currentSearch);
        boolean canRecurse=("local".equals(currentScreen)&&localFolder!=null)||
                ("saf".equals(currentScreen)&&safFolder!=null)||
                ("remote".equals(currentScreen)&&remote!=null);
        LinearLayout content = Ui.vertical(this);
        content.setPadding(dp(18), dp(3), dp(18), dp(10));
        EditText query = new EditText(this);
        query.setSingleLine(true);
        query.setTextSize(16);
        query.setHint("현재 폴더에서 파일명 검색");
        content.addView(query, Ui.lp(this, -1, 53));
        CheckBox recursive=new CheckBox(this);
        recursive.setText("하위 폴더 포함 검색");
        recursive.setEnabled(canRecurse);
        recursive.setChecked(false);
        content.addView(recursive,Ui.lp(this,-1,49));
        TextView searchButton=Ui.action(this,"하위 폴더까지 검색 시작");
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setVisibility(View.GONE);
        content.addView(searchButton,Ui.lp(this,-1,46));
        ScrollView scroller = new ScrollView(this);
        LinearLayout results = Ui.vertical(this);
        scroller.addView(results);
        content.addView(scroller, Ui.lp(this, -1, 330));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("검색")
                .setView(content)
                .setNegativeButton("닫기", null).create();
        Runnable update = () -> {
            results.removeAllViews();
            String keyword = query.getText().toString().trim();
            if(recursive.isChecked()){
                results.addView(Ui.text(this,keyword.isEmpty()?
                        "검색어를 입력하고 검색 시작을 누르세요.":
                        "검색 시작을 누르면 하위 폴더도 확인합니다. "+
                        "NAS 검색은 파일 수에 따라 시간이 걸릴 수 있습니다.",
                        13,Ui.MUTED,false),Ui.lp(this,-1,68));
                return;
            }
            if (keyword.isEmpty()) {
                TextView notice = Ui.text(this,
                        "파일명 일부를 입력하세요. 현재 폴더의 항목에서 검색합니다.",
                        13, Ui.MUTED, false);
                results.addView(notice, Ui.lp(this, -1, 60));
                return;
            }
            int matching = 0;
            for (SearchResult item : snapshot) {
                if (!FileViewOptions.matches(item.name, keyword)) continue;
                matching++;
                if (matching > 100) continue;
                TextView result = Ui.text(this, item.name, 15, Ui.TEXT, false);
                result.setSingleLine();
                result.setEllipsize(android.text.TextUtils.TruncateAt.END);
                results.addView(result, Ui.lp(this, -1, 50));
                result.setOnClickListener(v -> {
                    dialog.dismiss();
                    item.open.run();
                });
                View line = new View(this);
                line.setBackgroundColor(Ui.BORDER);
                results.addView(line, Ui.lp(this, -1, 1));
            }
            if (matching == 0) {
                TextView empty = Ui.text(this, "검색 결과가 없습니다.",
                        13, Ui.MUTED, false);
                results.addView(empty, Ui.lp(this, -1, 56));
            }
            if (matching > 100) {
                TextView more = Ui.text(this,
                        "일치하는 파일 " + matching + "개 · 앞의 100개만 표시",
                        12, Ui.MUTED, false);
                results.addView(more, Ui.lp(this, -1, 40));
            }
        };
        recursive.setOnCheckedChangeListener((check,enabled)->{
            ++recursiveSearchGeneration;
            if(recursiveSearchTask!=null)recursiveSearchTask.cancel(true);
            searchButton.setVisibility(enabled?View.VISIBLE:View.GONE);
            update.run();
        });
        searchButton.setOnClickListener(v->{
            String keyword=query.getText().toString().trim();
            if(keyword.isEmpty()){
                query.setError("파일명 일부를 입력해 주세요.");
                return;
            }
            if(!canRecurse)return;
            final int generation=++recursiveSearchGeneration;
            if(recursiveSearchTask!=null)recursiveSearchTask.cancel(true);
            final String screen=currentScreen;
            final File localRoot=localFolder;
            final DocumentFile safRoot=safFolder;
            final String relative=safRelative;
            final Models.RemoteProfile profile=remote;
            final String remoteRoot=remoteFolder;
            final boolean includeHidden=showHidden;
            results.removeAllViews();
            results.addView(Ui.text(this,
                    "하위 폴더를 검색하고 있습니다…",
                    14,Ui.MUTED,false),Ui.lp(this,-1,55));
            recursiveSearchTask=recursiveSearchWorker.submit(()->{
                RecursiveSearchBatch batch=recursiveSearchMatches(
                        screen,localRoot,safRoot,relative,
                        profile,remoteRoot,keyword,includeHidden,
                        ()->generation!=recursiveSearchGeneration);
                runOnUiThread(()->{
                    if(generation!=recursiveSearchGeneration||
                            !dialog.isShowing()||!recursive.isChecked()||
                            isFinishing()||isDestroyed())return;
                    results.removeAllViews();
                    String summary="검색 결과 "+batch.hits.size()+"개 · "+
                            "파일/폴더 "+batch.entries+"개 확인"+
                            (batch.limited?" · 검색 상한 도달":"")+
                            (batch.errors>0?" · 읽지 못한 폴더 "+batch.errors+"개":"");
                    results.addView(Ui.text(this,summary,12,Ui.MUTED,false),
                            Ui.lp(this,-1,42));
                    if(batch.hits.isEmpty())
                        results.addView(Ui.text(this,"일치하는 파일이 없습니다.",
                                14,Ui.MUTED,false),Ui.lp(this,-1,55));
                    for(SearchResult hit:batch.hits){
                        TextView item=Ui.text(this,hit.name,14,Ui.TEXT,false);
                        item.setMaxLines(2);
                        results.addView(item,Ui.lp(this,-1,57));
                        item.setOnClickListener(click->{
                            dialog.dismiss();
                            hit.open.run();
                        });
                        View divider=new View(this);
                        divider.setBackgroundColor(Ui.BORDER);
                        results.addView(divider,Ui.lp(this,-1,1));
                    }
                });
            });
        });
        query.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int n, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int before, int count) {
                if(recursive.isChecked()){
                    ++recursiveSearchGeneration;
                    if(recursiveSearchTask!=null)recursiveSearchTask.cancel(true);
                }
                update.run();
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });
        dialog.setOnDismissListener(ignored->{
            ++recursiveSearchGeneration;
            if(recursiveSearchTask!=null)recursiveSearchTask.cancel(true);
        });
        update.run();
        dialog.show();
    }
    private static final class RecursiveSearchBatch {
        final List<SearchResult> hits=new ArrayList<>();
        int entries,errors;
        boolean limited;
    }
    private RecursiveSearchBatch recursiveSearchMatches(
            String screen,File localRoot,DocumentFile safRoot,
            String safBase,Models.RemoteProfile profile,String remoteRoot,
            String keyword,boolean includeHidden,
            java.util.function.BooleanSupplier cancel){
        RecursiveSearchBatch batch=new RecursiveSearchBatch();
        if("local".equals(screen)&&localRoot!=null){
            FolderRecursiveSearch.Result<File> result=
                    FolderRecursiveSearch.find(localRoot,
                    new FolderRecursiveSearch.Adapter<File>(){
                        public List<File> list(File dir){
                            File[] children=dir.listFiles();
                            return children==null?Collections.emptyList():
                                    Arrays.asList(children);
                        }
                        public boolean directory(File item){
                            // Do not escape the selected subtree through a
                            // local symlink, or follow directory cycles.
                            return item.isDirectory()&&
                                    !java.nio.file.Files.isSymbolicLink(
                                            item.toPath());
                        }
                        public String name(File item){return item.getName();}
                        public String id(File item){
                            try{return item.getCanonicalPath();}
                            catch(IOException denied){return item.getAbsolutePath();}
                        }
                    },keyword,includeHidden,cancel);
            for(FolderRecursiveSearch.Hit<File> hit:result.hits){
                File file=hit.node;
                batch.hits.add(new SearchResult(hit.path,()->{
                    if(file.isDirectory())browseLocal(file);
                    else if(isVideo(file.getName()))
                        openVideo(Uri.fromFile(file),file.getName());
                    else openFile(file);
                }));
            }
            batch.entries=result.entries;
            batch.errors=result.errors;
            batch.limited=result.limited;
        }else if("saf".equals(screen)&&safRoot!=null){
            FolderRecursiveSearch.Result<DocumentFile> result=
                    FolderRecursiveSearch.find(safRoot,
                    new FolderRecursiveSearch.Adapter<DocumentFile>(){
                        public List<DocumentFile> list(DocumentFile dir){
                            return Arrays.asList(dir.listFiles());
                        }
                        public boolean directory(DocumentFile item){
                            return item.isDirectory();
                        }
                        public String name(DocumentFile item){return item.getName();}
                        public String id(DocumentFile item){
                            return item.getUri().toString();
                        }
                    },keyword,includeHidden,cancel);
            for(FolderRecursiveSearch.Hit<DocumentFile> hit:result.hits){
                DocumentFile file=hit.node;
                String path=RemoteProvider.relativePath(safBase+"/"+hit.path);
                batch.hits.add(new SearchResult(hit.path,()->{
                    if(file.isDirectory())browseSaf(file,path);
                    else if(isVideo(file.getName()))
                        openVideo(file.getUri(),file.getName());
                    else openNamedUri(file.getUri(),file.getName());
                }));
            }
            batch.entries=result.entries;
            batch.errors=result.errors;
            batch.limited=result.limited;
        }else if("remote".equals(screen)&&profile!=null){
            Models.Entry root=new Models.Entry("",
                    RemoteProvider.relativePath(remoteRoot),true);
            FolderRecursiveSearch.Result<Models.Entry> result=
                    FolderRecursiveSearch.find(root,
                    new FolderRecursiveSearch.Adapter<Models.Entry>(){
                        public List<Models.Entry> list(Models.Entry folder)
                                throws Exception {
                            return cachedRemoteFiles(profile,folder.path);
                        }
                        public boolean directory(Models.Entry node){
                            return node.directory;
                        }
                        public String name(Models.Entry node){return node.name;}
                        public String id(Models.Entry node){return node.path;}
                    },keyword,includeHidden,cancel);
            for(FolderRecursiveSearch.Hit<Models.Entry> hit:result.hits){
                Models.Entry item=hit.node;
                batch.hits.add(new SearchResult(hit.path,()->{
                    if(item.directory)openRemote(profile,item.path);
                    else if(isVideo(item.name))openRemoteVideo(profile,item);
                    else openRemoteFile(profile,item);
                }));
            }
            batch.entries=result.entries;
            batch.errors=result.errors;
            batch.limited=result.limited;
        }
        return batch;
    }
    private ReaderFolders.Scope currentReaderFolder(String mode) {
        if ("local".equals(currentScreen) && localFolder != null)
            return new ReaderFolders.Scope(mode,"local","",
                    localFolder.getAbsolutePath());
        if ("saf".equals(currentScreen) && safFolder != null &&
                !activeTreeUri.isEmpty())
            return new ReaderFolders.Scope(mode,"saf",activeTreeUri,safRelative);
        if ("remote".equals(currentScreen) && remote != null)
            return new ReaderFolders.Scope(mode,"remote",remote.id,remoteFolder);
        return null;
    }
    private void showReaderFolderIndicator() {
        ReaderFolders.Scope comic=currentReaderFolder(ReaderFolders.COMIC);
        ReaderFolders.Scope text=currentReaderFolder(ReaderFolders.TEXT);
        if(comic==null||text==null)return;
        boolean a=readerFolders.allowed(comic.mode,comic.kind,comic.owner,comic.path);
        boolean b=readerFolders.allowed(text.mode,text.kind,text.owner,text.path);
        if(!a&&!b)return;
        TextView badge=Ui.text(this,
                (a?"▣ 만화책 뷰어":"")+(a&&b?"  ·  ":"")+
                        (b?"▤ 텍스트책 뷰어":"")+
                        "  |  오른쪽 위 ⋮에서 폴더 설정",
                11,Ui.TEAL,false);
        badge.setSingleLine(true);
        badge.setEllipsize(android.text.TextUtils.TruncateAt.END);
        badge.setPadding(dp(9),0,dp(6),0);
        page.addView(badge,Ui.lp(this,-1,27));
    }
    private FileTransferEngine.Item currentFolderItem() {
        if ("local".equals(currentScreen) && localFolder != null)
            return FileTransferEngine.local(localFolder);
        if ("saf".equals(currentScreen) && safFolder != null)
            return FileTransferEngine.saf(safFolder, safRelative, activeTreeUri);
        if ("remote".equals(currentScreen) && remote != null)
            return FileTransferEngine.remote(remote,
                    new Models.Entry(remoteFolder.equals("/") ? remote.label() :
                            remoteFolder.substring(remoteFolder.lastIndexOf('/')+1),
                            remoteFolder, true));
        return null;
    }

    private ReaderFolders.Scope readerScopeForFolder(FileTransferEngine.Item item,
                                                     String mode) {
        if (item == null || !item.directory) return null;
        if ("local".equals(item.kind) && item.local != null)
            return new ReaderFolders.Scope(mode, "local", "",
                    item.local.getAbsolutePath());
        if ("saf".equals(item.kind) && item.saf != null)
            return new ReaderFolders.Scope(mode, "saf", item.tree, item.path);
        if ("remote".equals(item.kind) && item.remote != null)
            return new ReaderFolders.Scope(mode, "remote", item.remote.id, item.path);
        return null;
    }

    private String videoScopeForFolder(FileTransferEngine.Item item) {
        if (item == null || !item.directory) return null;
        if ("local".equals(item.kind) && item.local != null)
            return "local:" + item.local.getAbsolutePath();
        if ("remote".equals(item.kind) && item.remote != null)
            return "remote:" + item.remote.id + ":" + item.path;
        if ("saf".equals(item.kind) && item.saf != null) {
            try {
                return "storage:" + DocumentsContract.getDocumentId(item.saf.getUri());
            } catch (Exception unsupported) {
                return "saf:" + item.tree + ":" + item.path;
            }
        }
        return null;
    }

    private void showFolderPurposeMenu(FileTransferEngine.Item item) {
        if (item == null || !item.directory ||
                readerScopeForFolder(item, ReaderFolders.COMIC) == null) {
            toast("폴더 하나를 선택해 주세요.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("폴더 용도 설정 · " + item.name)
                .setItems(new String[]{"동영상 폴더 설정/해제",
                        "독서용 폴더 설정/해제"},
                        (dialog, which) -> {
                            // Multi-selection hides the top menu; honor its
                            // bottom More operation before presenting settings.
                            if (!selection.isEmpty()) {
                                selection.clear();
                                updateFileOperationChrome();
                            }
                            if (which == 0) {
                                String scope = videoScopeForFolder(item);
                                if (scope == null) toast("지원하지 않는 폴더입니다.");
                                else configureVideoFolder(scope, item.name);
                            } else showReaderFolderOptions(item);
                        }).setNegativeButton("취소", null).show();
    }

    private void showReaderFolderOptions(FileTransferEngine.Item folder) {
        ReaderFolders.Scope comic=readerScopeForFolder(folder,ReaderFolders.COMIC);
        ReaderFolders.Scope text=readerScopeForFolder(folder,ReaderFolders.TEXT);
        if(comic==null||text==null){
            showError("책 폴더는 폴더 하나를 선택해 지정해 주세요.");
            return;
        }
        boolean comicActive=readerFolders.exact(comic.mode,comic.kind,comic.owner,comic.path);
        boolean textActive=readerFolders.exact(text.mode,text.kind,text.owner,text.path);
        String[] modes={
                "만화책 폴더 "+(comicActive?"지정 해제":"지정")+" · ZIP/7Z/RAR",
                "텍스트책 폴더 "+(textActive?"지정 해제":"지정")+" · TXT/EPUB"
        };
        // AlertDialog.Builder.setMessage() overrides setItems() when
        // both are supplied: the v0.1.42 dialog showed only Cancel.
        new AlertDialog.Builder(this)
                .setTitle("독서용 폴더 설정/해제 · " + folder.name)
                .setItems(modes,(dialog,index)->{
                    try{
                        ReaderFolders.Scope target=index==0?comic:text;
                        boolean enabled=readerFolders.toggle(target.mode,target.kind,
                                target.owner,target.path);
                        toast((index==0?"만화책":"텍스트책")+
                                (enabled?" 폴더 지정 완료":" 폴더 지정 해제"));
                        refreshCurrent();
                    }catch(Exception e){failure(e);}
                }).setNegativeButton("취소",null).show();
    }

    private void fileActionsForCurrent() {
        if ("remote".equals(currentScreen)) {
            remoteFolderActions();
        } else if ("local".equals(currentScreen) || "saf".equals(currentScreen)) {
            boolean sdStorage = isInsideSdStorage();
            boolean localStorage = "local".equals(currentScreen);
            String[] entries = localStorage ?
                    (sdStorage ?
                            new String[]{"새로 만들기", "즐겨찾기 추가/해제",
                                    "새로고침", "이 폴더 미디어 재검색",
                                    "SD카드 접근 권한 설정", "폴더 용도 설정"} :
                            new String[]{"새로 만들기", "즐겨찾기 추가/해제",
                                    "새로고침", "이 폴더 미디어 재검색",
                                    "폴더 용도 설정"}) :
                    (sdStorage ?
                            new String[]{"새로 만들기", "즐겨찾기 추가/해제",
                                    "새로고침", "SD카드 접근 권한 설정",
                                    "폴더 용도 설정"} :
                            new String[]{"새로 만들기", "즐겨찾기 추가/해제",
                                    "새로고침", "폴더 용도 설정"});
            new AlertDialog.Builder(this).setTitle("현재 폴더")
                    .setItems(entries,
                            (dialog, which) -> {
                                if (which == 0) chooseNewEntry();
                                else if (which == 1) {
                                    if ("local".equals(currentScreen)) favoriteHere("local",
                                            localFolder.getAbsolutePath(),
                                            localFolder.getName(), "");
                                    else favoriteHere("saf", safRelative,
                                            safFolder.getName() == null ?
                                                    activeTreeLabel : safFolder.getName(),
                                            activeTreeUri);
                                } else if (which == 2) refreshCurrent();
                                else if (localStorage && which == 3)
                                    reindexCurrentLocalFolder();
                                else if (sdStorage && which == (localStorage ? 4 : 3))
                                    showSdAccessOptions();
                                else showFolderPurposeMenu(currentFolderItem());
                            }).show();
        } else refreshCurrent();
    }

    private void reindexCurrentLocalFolder() {
        File folder = localFolder;
        if (folder == null || !folder.isDirectory()) return;
        runJob("갤러리 미디어 등록 요청 중...",
                () -> MediaIndexing.requestFolderScan(this, folder),
                count -> toast(count == 0 ?
                        "이 폴더에서 등록할 미디어를 찾지 못했습니다." :
                        count + "개 미디어 검색 요청 완료 · 갤러리에 표시되기까지 시간이 걸릴 수 있습니다."));
    }
    private void chooseNewEntry() {
        if ("remote".equals(currentScreen) &&
                (remote == null || isReadOnlyCloud(remote))) {
            showError("클라우드 베타 연결은 읽기 전용입니다.");
            return;
        }
        new AlertDialog.Builder(this).setTitle("새로 만들기")
                .setItems(new String[]{"새 파일", "새 폴더"}, (d, index) ->
                        createEntry(index == 1)).show();
    }
    private void createEntry(boolean directory) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(directory ? "새 폴더 이름" : "새 파일 이름 (예: 메모.txt)");
        new AlertDialog.Builder(this)
                .setTitle(directory ? "새 폴더 만들기" : "새 파일 만들기")
                .setView(input)
                .setNegativeButton("취소", null)
                .setPositiveButton("만들기", (dialog, button) -> {
                    try {
                        String name = input.getText().toString().trim();
                        RemoteProvider.child("/", name);
                        File localTarget = "local".equals(currentScreen) ?
                                localFolder : null;
                        DocumentFile safTarget = "saf".equals(currentScreen) ?
                                safFolder : null;
                        Models.RemoteProfile remoteTarget = "remote".equals(currentScreen) ?
                                remote : null;
                        String remoteTargetFolder = remoteFolder;
                        int screen = renderGeneration;
                        runJob(directory ? "폴더 생성 중..." : "파일 생성 중...", () -> {
                            if (localTarget != null) {
                                File file = new File(localTarget, name);
                                if (file.exists()) throw new IOException("같은 이름이 이미 존재합니다.");
                                boolean ok = directory ? file.mkdir() : file.createNewFile();
                                if (!ok) throw new IOException("새 항목을 만들 수 없습니다.");
                            } else if (safTarget != null) {
                                if (safTarget.findFile(name) != null)
                                    throw new IOException("같은 이름이 이미 존재합니다.");
                                DocumentFile made = directory ?
                                        safTarget.createDirectory(name) :
                                        safTarget.createFile(mime(name), name);
                                if (made == null) throw new IOException("새 항목 생성 실패");
                            } else if (remoteTarget != null) {
                                if (isReadOnlyCloud(remoteTarget))
                                    throw new IOException("읽기 전용 클라우드입니다.");
                                if (directory) RemoteProvider.mkdir(remoteTarget,
                                        RemoteProvider.child(remoteTargetFolder, name));
                                else if ("TeraBox".equals(remoteTarget.protocol))
                                    throw new IOException("TeraBox는 0바이트 빈 파일 생성을 지원하지 않습니다. " +
                                            "내용이 있는 파일을 업로드하세요.");
                                else RemoteProvider.upload(remoteTarget, remoteTargetFolder,
                                        name, new ByteArrayInputStream(new byte[0]), 0);
                            } else throw new IOException("현재 저장소에서는 생성할 수 없습니다.");
                            return true;
                        }, ignored -> {
                            toast("생성했습니다.");
                            if (remoteTarget != null)
                                folderCache.invalidateProfile(remoteTarget);
                            if (screen == renderGeneration) refreshCurrent();
                        });
                    } catch (Exception error) { failure(error); }
                }).show();
    }

    private File permissionPendingFile;
    private void requestAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            if (Environment.isExternalStorageManager()) {
                toast("이미 저장소 접근 권한이 허용되어 있습니다.");
                return;
            }
            new AlertDialog.Builder(this).setTitle("저장소 접근 권한")
                    .setMessage("메인 저장소와 다운로드의 모든 폴더를 탐색하려면 파일관리자 접근 권한이 필요합니다. 보호된 시스템·앱 개인 폴더는 제외됩니다.")
                    .setNegativeButton("취소", null)
                    .setNeutralButton("폴더 직접 선택", (d,w) -> pickTree(REQ_FOLDER))
                    .setPositiveButton("설정에서 허용", (d,w) -> {
                        try {
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    }).show();
        } else if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_MEDIA);
    }
    private void pickTree(int code) {
        Intent i;
        if (code == REQ_SD && Build.VERSION.SDK_INT >= 29 && sdVolume() != null)
            i = sdVolume().createOpenDocumentTreeIntent();
        else i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, code);
        } catch (ActivityNotFoundException e) {
            showError("기기에 폴더 선택 앱이 없어 SD카드를 선택할 수 없습니다. " +
                    "시스템 파일 관리자 앱을 확인해 주세요.");
        }
    }
    private void browseLocal(File directory) {
        if (Build.VERSION.SDK_INT < 30 &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED) {
            permissionPendingFile = directory;
            requestAllFiles();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            if (directory.equals(Environment.getExternalStorageDirectory()) &&
                    !store.selectedTreeUri().isEmpty()) {
                activeTreeUri = store.selectedTreeUri();
                activeTreeLabel = "선택한 폴더";
                openSafPath("/");
                return;
            }
            permissionPendingFile = directory;
            requestAllFiles();
            return;
        }
        if (!directory.isDirectory()) {
            showError("이 폴더를 열 수 없습니다. 저장소 접근 권한을 확인하세요.");
            return;
        }
        currentScreen = "local";
        localFolder = directory; safFolder = null; remote = null;
        render(directory.equals(Environment.getExternalStorageDirectory()) ?
                "메인 저장소" : isDirectSdRoot(directory) ?
                        "SD 저장소" : directory.getName(), true, "☆",
                () -> favoriteHere("local", directory.getAbsolutePath(),
                        directory.getName(), ""));
        showLocalBreadcrumb(directory);
        final int screen=renderGeneration;
        final int requestedSort=currentFolderSortMode();
        TextView loading=muted("파일 목록을 읽는 중...");
        worker.execute(()->{
            File[] raw=directory.listFiles();
            if(raw==null){
                runOnUiThread(()->{
                    if(screen==renderGeneration){
                        page.removeView(loading);
                        muted("폴더 읽기 권한이 없습니다.");
                    }
                });
                return;
            }
            List<Models.Entry> items=new ArrayList<>(raw.length);
            List<String> siblings=new ArrayList<>(raw.length);
            List<VideoEpisodeQueue.Episode> episodes=new ArrayList<>();
            for(File file:raw){
                String name=file.getName();
                boolean folder=file.isDirectory();
                Models.Entry entry=new Models.Entry(name,file.getAbsolutePath(),folder);
                entry.file=file;
                entry.size=folder?-1:file.length();
                entry.modified=file.lastModified();
                items.add(entry);
                siblings.add(name);
                if(!folder&&VideoEpisodeQueue.isVideo(name))
                    episodes.add(new VideoEpisodeQueue.Episode(
                            Uri.fromFile(file).toString(),name,entry.size));
            }
            items.sort((a,b)->FileViewOptions.compare(
                    a.directory,a.name,a.size,a.modified,
                    b.directory,b.name,b.size,b.modified,requestedSort));
            VideoEpisodeQueue queue=VideoEpisodeQueue.from(episodes);
            runOnUiThread(()->{
                if(screen!=renderGeneration || !"local".equals(currentScreen) ||
                        !directory.equals(localFolder)||isFinishing())return;
                page.removeView(loading);
                siblingSubtitleIndex=SubtitleInventory.fromSiblings(siblings);
                visibleEpisodeQueue=queue;
                ImageFolderSequence.showFolder("local", "", items, showHidden);
                if(items.isEmpty())muted("이 폴더는 비어 있습니다.");
                indexFullFolderSearch(items,item->()->{
                    File file=item.file;
                    if(item.directory)browseLocal(file);
                    else if(isVideo(item.name))openVideo(Uri.fromFile(file),item.name);
                    else openFile(file);
                });
                appendDirectoryPage(items,0,screen,item->{
                    File file=item.file;
                    fileRow(item,()->{
                        if(item.directory)browseLocal(file);
                        else if(isVideo(item.name))
                            openVideo(Uri.fromFile(file),item.name);
                        else openFile(file);
                    },item.directory?()->{
                        File[] children=file.listFiles();
                        if(children==null)return -1;
                        int total=0;
                        for(File child:children)
                            if(FileViewOptions.visible(child.getName(),
                                    child.isHidden(),showHidden))total++;
                        return total;
                    }:null);
                });
            });
        });
    }
    private void showSdAccessOptions() {
        StorageVolume volume = sdVolume();
        if (volume == null) {
            showError("장착된 SD카드를 찾지 못했습니다. 카드가 마운트되었는지 확인해 주세요.");
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            new AlertDialog.Builder(this).setTitle("SD카드 접근 설정")
                    .setMessage("SD카드의 원하는 폴더를 선택하고 접근을 허용해 주세요. " +
                            "선택한 폴더와 하위 폴더를 사용할 수 있습니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("SD 폴더 선택", (d,w) -> pickTree(REQ_SD))
                    .show();
            return;
        }
        boolean full = Environment.isExternalStorageManager();
        new AlertDialog.Builder(this).setTitle("SD카드 접근 설정")
                .setMessage((full ? "전체 파일 관리 권한이 허용되어 있습니다.\n\n" :
                        "SD카드 전체를 이용하려면 '전체 파일 접근' 권한이 필요합니다.\n\n") +
                        "Android 11 이상에서는 SD카드 최상위 폴더의 " +
                        "'이 폴더 사용' 버튼이 비활성화될 수 있습니다. " +
                        "이는 안드로이드의 제한입니다.\n\n" +
                        "① 전체 접근: 시스템 설정에서 NAS Mount의 " +
                        "'모든 파일에 대한 접근 허용'을 켜세요.\n" +
                        "② 폴더 접근: SD카드 안의 하위 폴더를 선택하세요. " +
                        "그 폴더 밖은 사용할 수 없습니다.")
                .setNegativeButton("취소", null)
                .setNeutralButton("SD 폴더 선택", (d,w) -> pickTree(REQ_SD))
                .setPositiveButton("전체 접근 설정", (d,w) -> openSdAllFilesSettings())
                .show();
    }

    private void openSdAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            pickTree(REQ_SD);
            return;
        }
        // The user requested an SD permission flow, not a pending internal
        // storage folder. Do not reopen that unrelated folder on return.
        permissionPendingFile = null;
        sdPermissionRequestPending = true;
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException error) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (ActivityNotFoundException unsupported) {
                sdPermissionRequestPending = false;
                showError("이 기기에서는 전체 파일 접근 설정 화면을 열 수 없습니다. " +
                        "SD 폴더 선택을 사용해 주세요.");
            }
        }
    }

    private boolean hasPersistedTreeRead(Uri tree) {
        for (android.content.UriPermission grant :
                getContentResolver().getPersistedUriPermissions())
            if (tree.equals(grant.getUri()) && grant.isReadPermission()) return true;
        return false;
    }

    private void openSd() {
        StorageVolume volume = sdVolume();
        if (volume == null) {
            toast("SD카드가 장착되어 있지 않습니다.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 30 &&
                Environment.isExternalStorageManager()) {
            File root = directSdRoot(volume);
            if (SdAccessPolicy.useDirectRoot(Build.VERSION.SDK_INT, true,
                    root != null && root.isDirectory() && root.canRead())) {
                activeTreeUri = "";
                activeTreeLabel = "SD 저장소 (전체 접근)";
                browseLocal(root);
                return;
            }
            // A nonstandard OEM may expose the volume only through SAF.
        }
        String saved = store.sdTreeUri();
        if (!saved.isEmpty()) {
            try {
                Uri uri = Uri.parse(saved);
                if (!hasPersistedTreeRead(uri)) {
                    store.sdTreeUri("");
                    showSdAccessOptions();
                    return;
                }
                DocumentFile root = DocumentFile.fromTreeUri(this, uri);
                if (root == null || !root.exists() || !root.canRead())
                    throw new IOException("SD 폴더 읽기 권한이 없습니다.");
                activeTreeUri = saved;
                activeTreeLabel = root.canWrite() ?
                        "SD 저장소 (선택 폴더)" : "SD 저장소 (읽기 전용)";
                browseSaf(root, "/");
                return;
            } catch (Exception error) {
                store.sdTreeUri("");
                showSdAccessOptions();
                return;
            }
        }
        showSdAccessOptions();
    }
    private void openSafPath(String relative) {
        if (activeTreeUri.isEmpty()) { openSd(); return; }
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(this,
                    Uri.parse(activeTreeUri));
            if (folder == null) throw new IOException("SD 저장소 없음");
            if (!relative.equals("/")) for (String part : relative.substring(1).split("/")) {
                folder = folder.findFile(part);
                if (folder == null || !folder.isDirectory())
                    throw new IOException("하위 폴더 없음");
            }
            browseSaf(folder, relative);
        } catch (Exception e) { showError("즐겨찾기한 SD 폴더를 찾을 수 없습니다."); }
    }
    private void browseSaf(DocumentFile folder, String relative) {
        currentScreen = "saf";
        localFolder = null; safFolder = folder; safRelative = RemoteProvider.relativePath(relative);
        remote = null;
        String name = folder.getName() == null ? "SD 저장소" : folder.getName();
        render(name, true, "☆", () -> favoriteHere(
                "saf", safRelative, name, activeTreeUri));
        showSafBreadcrumb();
        final int screen=renderGeneration;
        final String requestedRelative=safRelative;
        final int requestedSort=currentFolderSortMode();
        TextView loading=muted("SD 폴더를 읽는 중...");
        worker.execute(()->{
            List<Models.Entry> entries=new ArrayList<>();
            List<String> siblings=new ArrayList<>();
            List<VideoEpisodeQueue.Episode> episodes=new ArrayList<>();
            Exception issue=null;
            try {
                // DocumentFile providers may block for seconds per metadata
                // call; never enumerate or sort them on the main thread.
                for(DocumentFile file:folder.listFiles()){
                    String childName=file.getName();
                    if(childName==null)childName="이름 없음";
                    boolean isFolder=file.isDirectory();
                    Models.Entry item=new Models.Entry(
                            childName,file.getUri().toString(),isFolder);
                    item.document=file;
                    item.size=isFolder?-1:file.length();
                    item.modified=file.lastModified();
                    entries.add(item);
                    siblings.add(childName);
                    if(!isFolder&&VideoEpisodeQueue.isVideo(childName))
                        episodes.add(new VideoEpisodeQueue.Episode(
                                file.getUri().toString(),childName,item.size));
                }
                entries.sort((a,b)->FileViewOptions.compare(
                        a.directory,a.name,a.size,a.modified,
                        b.directory,b.name,b.size,b.modified,requestedSort));
            }catch(Exception error){issue=error;}
            final Exception failure=issue;
            VideoEpisodeQueue queue=VideoEpisodeQueue.from(episodes);
            runOnUiThread(()->{
                if(screen!=renderGeneration || !"saf".equals(currentScreen) ||
                        safFolder!=folder || !requestedRelative.equals(safRelative) ||
                        isFinishing())return;
                page.removeView(loading);
                if(failure!=null){muted("SD 폴더를 읽지 못했습니다.");return;}
                siblingSubtitleIndex=SubtitleInventory.fromSiblings(siblings);
                visibleEpisodeQueue=queue;
                ImageFolderSequence.showFolder("uri", "", entries, showHidden);
                if(entries.isEmpty())muted("이 폴더는 비어 있습니다.");
                indexFullFolderSearch(entries,item->()->{
                    DocumentFile file=item.document;
                    if(item.directory)browseSaf(file,
                            RemoteProvider.child(requestedRelative,item.name));
                    else if(isVideo(item.name))openVideo(file.getUri(),item.name);
                    else openNamedUri(file.getUri(),item.name);
                });
                appendDirectoryPage(entries,0,screen,item->{
                    DocumentFile file=item.document;
                    fileRow(item,()->{
                        if(item.directory)browseSaf(file,
                                RemoteProvider.child(requestedRelative,item.name));
                        else if(isVideo(item.name))
                            openVideo(file.getUri(),item.name);
                        else openNamedUri(file.getUri(),item.name);
                    },item.directory?()->{
                        int count=0;
                        for(DocumentFile child:file.listFiles())
                            if(FileViewOptions.visible(
                                    child.getName(),false,showHidden))count++;
                        return count;
                    }:null);
                });
            });
        });
    }
    private void openMedia(String type) {
        mediaKind = type;
        String permission = type.equals("image") ? Manifest.permission.READ_MEDIA_IMAGES :
                type.equals("audio") ? Manifest.permission.READ_MEDIA_AUDIO :
                        Manifest.permission.READ_MEDIA_VIDEO;
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{permission}, REQ_MEDIA);
            return;
        }
        if (Build.VERSION.SDK_INT < 33 &&
                checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) !=
                        PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_MEDIA);
            return;
        }
        currentScreen = "media";
        render(type.equals("image") ? "이미지" : type.equals("audio") ? "오디오" : "동영상",
                true, null, null);
        muted("기기에 저장된 파일 · 보기 설정에서 정렬 변경 가능");
        Uri collection = type.equals("image") ?
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI :
                type.equals("audio") ? MediaStore.Audio.Media.EXTERNAL_CONTENT_URI :
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = Build.VERSION.SDK_INT >= 29 ?
                new String[]{MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED,
                        MediaStore.MediaColumns.RELATIVE_PATH} :
                new String[]{MediaStore.MediaColumns._ID,
                        MediaStore.MediaColumns.DISPLAY_NAME,
                        MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED};
        List<Models.Entry> items = new ArrayList<>();
        try (android.database.Cursor cursor = getContentResolver().query(
                collection, projection, null, null,
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC")) {
            if (cursor != null) while (cursor.moveToNext()) {
                String folderName = cursor.getColumnCount() > 4 ?
                        cursor.getString(4) : "";
                Models.Entry item = new Models.Entry(cursor.getString(1),
                        folderName == null ? "" : folderName, false);
                item.uri = android.content.ContentUris.withAppendedId(
                        collection, cursor.getLong(0));
                item.size = cursor.getLong(2);
                item.modified = cursor.getLong(3) * 1000L;
                items.add(item);
            }
        } catch (SecurityException e) {
            muted("미디어 조회 권한이 없습니다. 설정에서 접근을 허용해 주세요.");
            return;
        }
        if (items.isEmpty()) muted("찾은 파일이 없습니다.");
        else muted("총 " + items.size() + "개");
        Map<String, List<VideoEpisodeQueue.Episode>> grouped = new LinkedHashMap<>();
        for (Models.Entry item : items)
            if (VideoEpisodeQueue.isVideo(item.name) && item.uri != null &&
                    !item.path.isEmpty())
                grouped.computeIfAbsent(item.path, ignored -> new ArrayList<>())
                        .add(new VideoEpisodeQueue.Episode(item.uri.toString(),
                                item.name, item.size));
        Map<String, VideoEpisodeQueue> queues = new LinkedHashMap<>();
        for (Map.Entry<String, List<VideoEpisodeQueue.Episode>> folder : grouped.entrySet())
            queues.put(folder.getKey(), VideoEpisodeQueue.from(folder.getValue()));
        mediaFolderEpisodeQueues = queues;
        appendMediaPage(items, type, 0);
    }
    private void appendMediaPage(List<Models.Entry> items, String type, int offset) {
        int end = Math.min(offset + 120, items.size());
        List<Models.Entry> displayed = new ArrayList<>(items);
        Collections.sort(displayed, (a,b) -> FileViewOptions.compare(
                false, a.name, a.size, a.modified, false, b.name, b.size,
                b.modified, sortMode));
        for (int i = offset; i < end; i++) {
            Models.Entry item = displayed.get(i);
            fileRow(item, () -> {
                        if (isVideo(item.name)) {
                            visibleEpisodeQueue = mediaFolderEpisodeQueues.get(item.path);
                            openVideo(item.uri, item.name);
                        }
                        else if(MediaFileTypes.isAudio(item.name))
                            playMusic(item.uri,item.name);
                        else if(MediaFileTypes.isImage(item.name))
                            viewImage("uri",item.name,null,item.uri,null);
                        else openUri(item.uri, mime(item.name));
                    }, null);
        }
        if (end < items.size()) {
            TextView more = Ui.action(this, "더 보기  (" + end + " / " + items.size() + ")");
            LinearLayout.LayoutParams p = Ui.lp(this, -1, 48);
            p.topMargin = dp(8);
            page.addView(more, p);
            more.setOnClickListener(v -> {
                page.removeView(more);
                appendMediaPage(items, type, end);
            });
        }
    }
    private String when(long time) {
        return time <= 0 ? "" : DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT).format(new Date(time));
    }
    private String shortDate(long millis) {
        if (millis <= 0) return "";
        return new java.text.SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
                .format(new Date(millis));
    }
    private String iconFor(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.matches(".*\\.(jpg|jpeg|png|gif|webp|bmp|heic)$")) return "image";
        if (MediaFileTypes.isAudio(n)) return "audio";
        if (n.matches(".*\\.(mp4|m4v|mov|mkv|avi|webm|3gp|ts|m2ts)$"))
            return "video";
        return "file";
    }
    private boolean isVideo(String filename) {
        return filename != null && filename.toLowerCase(Locale.ROOT)
                .matches(".*\\.(mp4|m4v|mov|mkv|avi|webm|3gp|ts|m2ts)$");
    }
    private void openVideo(Uri uri, String name) {
        refreshAfterVideo = true;
        Intent play = new Intent(this, VideoPlayerActivity.class);
        play.putExtra(VideoPlayerActivity.EXTRA_LOCAL_URI, uri.toString());
        play.putExtra(VideoPlayerActivity.EXTRA_TITLE, name);
        if(folderScope()!=null)
            play.putExtra(VideoPlayerActivity.EXTRA_FOLDER_SCOPE,folderScope());
        attachEpisodeQueue(play, uri.toString());
        play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(play);
    }
    private void playMusic(Uri uri,String name){
        Intent play=new Intent(this,MusicPlaybackService.class);
        play.setAction(MusicPlaybackService.ACTION_OPEN);
        play.putExtra(MusicPlaybackService.EXTRA_URI,uri.toString());
        play.putExtra(MusicPlaybackService.EXTRA_NAME,name);
        play.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startMusic(play);
    }
    private void playRemoteMusic(Models.RemoteProfile profile,Models.Entry file){
        Intent play=new Intent(this,MusicPlaybackService.class);
        play.setAction(MusicPlaybackService.ACTION_OPEN);
        play.putExtra(MusicPlaybackService.EXTRA_PROFILE,profile.id);
        play.putExtra(MusicPlaybackService.EXTRA_PATH,file.path);
        play.putExtra(MusicPlaybackService.EXTRA_SIZE,file.size);
        play.putExtra(MusicPlaybackService.EXTRA_NAME,file.name);
        startMusic(play);
    }
    private void startMusic(Intent intent){
        try{
            androidx.core.content.ContextCompat.startForegroundService(this,intent);
            musicHandler.postDelayed(this::refreshMiniPlayer,350);
            if(Build.VERSION.SDK_INT>=33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED){
                android.content.SharedPreferences prefs=getSharedPreferences(
                        "music_notifications",MODE_PRIVATE);
                if(!prefs.getBoolean("asked",false)){
                    prefs.edit().putBoolean("asked",true).apply();
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                            REQ_MUSIC_NOTIFICATIONS);
                }
            }
        }catch(Exception exception){
            showError("음악 재생 서비스를 실행하지 못했습니다: "+
                    exception.getClass().getSimpleName());
        }
    }
    private void viewImage(String kind,String name,String path,Uri uri,
                           Models.RemoteProfile profile){
        Intent view=new Intent(this,ImageViewerActivity.class);
        view.putExtra(ImageViewerActivity.EXTRA_KIND,kind);
        view.putExtra(ImageViewerActivity.EXTRA_NAME,name);
        if(path!=null)view.putExtra(ImageViewerActivity.EXTRA_PATH,path);
        if(uri!=null)view.putExtra(ImageViewerActivity.EXTRA_URI,uri.toString());
        if(profile!=null)view.putExtra(ImageViewerActivity.EXTRA_PROFILE,profile.id);
        // The sequence is the entire folder snapshot, including images on
        // not-yet-rendered pagination rows. The token is only valid for the
        // same item, provider and profile; media-wide lists are excluded.
        if ("local".equals(currentScreen) || "saf".equals(currentScreen) ||
                "remote".equals(currentScreen)) {
            String token=ImageFolderSequence.tokenFor(kind,
                    profile==null?"":profile.id,
                    path!=null?path:uri==null?null:uri.toString());
            if(token!=null)view.putExtra(ImageViewerActivity.EXTRA_SEQUENCE,token);
        }
        if(uri!=null)view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(view);
    }

    private void openRemoteVideo(Models.RemoteProfile p, Models.Entry file) {
        refreshAfterVideo = true;
        Intent play = new Intent(this, VideoPlayerActivity.class);
        play.putExtra(VideoPlayerActivity.EXTRA_PROFILE_ID, p.id);
        play.putExtra(VideoPlayerActivity.EXTRA_REMOTE_PATH, file.path);
        play.putExtra(VideoPlayerActivity.EXTRA_REMOTE_SIZE, file.size);
        play.putExtra(VideoPlayerActivity.EXTRA_TITLE, file.name);
        if(folderScope()!=null)
            play.putExtra(VideoPlayerActivity.EXTRA_FOLDER_SCOPE,folderScope());
        attachEpisodeQueue(play, file.path);
        startActivity(play);
    }

    private void attachEpisodeQueue(Intent play, String currentPath) {
        if (visibleEpisodeQueue == null) return;
        VideoEpisodeQueue.Window window = visibleEpisodeQueue.window(currentPath);
        if (window == null) return;
        ArrayList<String> paths = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        long[] sizes = new long[window.episodes.size()];
        for (int i = 0; i < window.episodes.size(); i++) {
            VideoEpisodeQueue.Episode episode = window.episodes.get(i);
            paths.add(episode.path);
            names.add(episode.name);
            sizes[i] = episode.size;
        }
        play.putStringArrayListExtra(VideoPlayerActivity.EXTRA_EPISODE_PATHS, paths);
        play.putStringArrayListExtra(VideoPlayerActivity.EXTRA_EPISODE_NAMES, names);
        play.putExtra(VideoPlayerActivity.EXTRA_EPISODE_SIZES, sizes);
        play.putExtra(VideoPlayerActivity.EXTRA_EPISODE_INDEX, window.currentIndex);
    }
    private String mime(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String m = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                filename.substring(dot+1).toLowerCase(Locale.ROOT));
        return m == null ? "application/octet-stream" : m;
    }
    private void openFile(File f) {
        if (MediaFileTypes.isAudio(f.getName())) {
            playMusic(Uri.fromFile(f),f.getName());
            return;
        }
        if (MediaFileTypes.isImage(f.getName())) {
            viewImage("local",f.getName(),f.getAbsolutePath(),null,null);
            return;
        }
        if (ComicArchive.supports(f.getName()) &&
                readerFolders.allowed(ReaderFolders.COMIC,"local","",
                        f.getParentFile().getAbsolutePath())) {
            openBookReader(ComicBookActivity.class,"local",f.getName(),
                    f.getAbsolutePath(),null,null);
            return;
        }
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".txt") &&
                readerFolders.allowed(ReaderFolders.TEXT,"local","",
                        f.getParentFile().getAbsolutePath())) {
            openBookReader(BookTextActivity.class,"local",f.getName(),
                    f.getAbsolutePath(),null,null);
            return;
        }
        if (f.getName().toLowerCase(Locale.ROOT).endsWith(".epub") &&
                readerFolders.allowed(ReaderFolders.TEXT,"local","",
                        f.getParentFile().getAbsolutePath())) {
            openBookReader(EpubReaderActivity.class,"local",f.getName(),
                    f.getAbsolutePath(),null,null);
            return;
        }
        if(PdfBookTypes.supports(f.getName())){
            openBookReader(EpubReaderActivity.class,"local",f.getName(),
                    f.getAbsolutePath(),null,null);
            return;
        }
        if (ArchiveCodec.isArchive(f.getName())) {
            offerExtractArchive(FileTransferEngine.local(f));
            return;
        }
        if (OfficeFileTypes.supports(f.getName())) {
            openBuiltInOffice("local", f.getName(), f.getAbsolutePath(), null, null);
            return;
        }
        if (EditableFileTypes.isEditableText(f.getName())) {
            openTextEditor("local", f.getName(), f.getAbsolutePath(), null, null);
            return;
        }
        if (EditableFileTypes.isApk(f.getName())) {
            stageApkAndOfferInstall(f.getName(), () -> new FileInputStream(f));
            return;
        }
        try {
            openUri(FileProvider.getUriForFile(this,
                    getPackageName()+".files", f), mime(f.getName()));
        } catch (Exception e) { failure(e); }
    }
    private void openNamedUri(Uri uri, String name) {
        if (ComicArchive.supports(name) &&
                readerFolders.allowed(ReaderFolders.COMIC,"saf",
                        activeTreeUri,safRelative)) {
            openBookReader(ComicBookActivity.class,"saf",name,null,uri,null);
        } else if (name.toLowerCase(Locale.ROOT).endsWith(".txt") &&
                readerFolders.allowed(ReaderFolders.TEXT,"saf",
                        activeTreeUri,safRelative)) {
            openBookReader(BookTextActivity.class,"saf",name,null,uri,null);
        } else if (name.toLowerCase(Locale.ROOT).endsWith(".epub") &&
                readerFolders.allowed(ReaderFolders.TEXT,"saf",
                        activeTreeUri,safRelative)) {
            openBookReader(EpubReaderActivity.class,"saf",name,null,uri,null);
        } else if(PdfBookTypes.supports(name)){
            openBookReader(EpubReaderActivity.class,"saf",name,null,uri,null);
        } else if(MediaFileTypes.isAudio(name)){
            playMusic(uri,name);
        } else if(MediaFileTypes.isImage(name)){
            viewImage("uri",name,null,uri,null);
        } else if (ArchiveCodec.isArchive(name)) {
            DocumentFile document = DocumentFile.fromSingleUri(this, uri);
            if (document == null) {
                showError("압축 파일을 읽을 수 없습니다.");
                return;
            }
            offerExtractArchive(FileTransferEngine.saf(document,
                    RemoteProvider.child("/", name), activeTreeUri));
        } else if (OfficeFileTypes.supports(name)) {
            openBuiltInOffice("saf", name, null, uri, null);
        } else if (EditableFileTypes.isEditableText(name)) {
            openTextEditor("saf", name, null, uri, null);
        } else if (EditableFileTypes.isApk(name)) {
            stageApkAndOfferInstall(name, () -> {
                InputStream input = getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("APK 파일을 읽을 수 없습니다.");
                return input;
            });
        } else {
            openUri(uri, mime(name));
        }
    }

    private void openBookReader(Class<? extends Activity> screen,String kind,
                                String name,String path,Uri uri,
                                Models.RemoteProfile provider) {
        Intent book=new Intent(this,screen);
        book.putExtra(ReaderSource.KIND,kind);
        book.putExtra(ReaderSource.NAME,name);
        if(folderScope()!=null)
            book.putExtra(ReaderSource.FOLDER_SCOPE,folderScope());
        if(path!=null)book.putExtra(ReaderSource.PATH,path);
        if(uri!=null){
            book.putExtra(ReaderSource.URI,uri.toString());
            book.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        if(provider!=null)book.putExtra(ReaderSource.PROFILE,provider.id);
        if(screen==ComicBookActivity.class) {
            try {
                book.putExtra(BookSeriesQueue.EXTRA,
                        BookSeriesQueue.encode(visibleComicVolumes));
            }catch(Exception ignored) {
                // Single-volume reading still works if the queue fails.
            }
        }
        refreshAfterReader=true;
        startActivity(book);
    }

    private void openTextEditor(String kind, String name, String path,
                                Uri uri, Models.RemoteProfile provider) {
        Intent edit = new Intent(this, TextEditorActivity.class);
        edit.putExtra(TextEditorActivity.EXTRA_KIND, kind);
        edit.putExtra(TextEditorActivity.EXTRA_NAME, name);
        if (path != null) edit.putExtra(TextEditorActivity.EXTRA_PATH, path);
        if (uri != null) edit.putExtra(TextEditorActivity.EXTRA_URI, uri.toString());
        if (provider != null)
            edit.putExtra(TextEditorActivity.EXTRA_PROFILE, provider.id);
        startActivityForResult(edit, REQ_TEXT_EDITOR);
    }
    private void openBuiltInOffice(String kind, String name, String path,
                                  Uri uri, Models.RemoteProfile provider) {
        Intent edit = new Intent(this, BuiltInOfficeActivity.class);
        edit.putExtra(BuiltInOfficeActivity.EXTRA_KIND, kind);
        edit.putExtra(BuiltInOfficeActivity.EXTRA_NAME, name);
        if (path != null) edit.putExtra(BuiltInOfficeActivity.EXTRA_PATH, path);
        if (uri != null) edit.putExtra(BuiltInOfficeActivity.EXTRA_URI, uri.toString());
        if (provider != null)
            edit.putExtra(BuiltInOfficeActivity.EXTRA_PROFILE, provider.id);
        startActivityForResult(edit, REQ_OFFICE_EDITOR);
    }
    private void openUri(Uri uri, String type) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, type);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "파일 열기"));
        } catch (ActivityNotFoundException e) {
            toast("이 형식의 파일을 열 수 있는 앱이 없습니다.");
        }
    }
    private void favoriteHere(String kind, String path, String title, String id) {
        try {
            Models.Favorite f = new Models.Favorite(kind, title, path, id);
            boolean existed = store.isFavorite(f);
            store.toggleFavorite(f);
            toast(existed ? "즐겨찾기를 해제했습니다." : "즐겨찾기에 추가했습니다.");
            updateFavoriteIndicators();
            // Let the user enter a friendly label immediately after
            // bookmarking; cancel leaves the original-name shortcut saved.
            if (!existed) editFavoriteName(f);
        } catch (Exception e) { failure(e); }
    }
    private void configureVideoFolder(String scope, String title) {
        String normalized = FolderScopeFilter.normalize(scope);
        Set<String> selected = PlayerSettings.prefs(this).getStringSet(
                PlayerSettings.HIDE_FOLDERS, Collections.emptySet());
        boolean directlySelected = selected.contains(normalized);
        boolean inherited = !directlySelected &&
                FolderScopeFilter.folderCovered(normalized, selected);
        boolean activeInherited=inherited&&
                AssignedRoleExclusions.videoAllowed(this,normalized,selected);
        if (activeInherited) {
            new AlertDialog.Builder(this).setTitle("동영상 폴더")
                    .setMessage("이 폴더는 이미 상위 폴더의 동영상 폴더 설정을 적용받고 있어요. " +
                            "상위 폴더의 설정을 해제하면 하위 폴더도 함께 해제됩니다.")
                    .setPositiveButton("확인", null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(directlySelected ?
                        "동영상 폴더 설정 해제" : "동영상 폴더로 설정")
                .setMessage(title + "\n\n" + (directlySelected ?
                        "이 폴더 및 하위 폴더의 자막 숨기기 규칙을 해제할까요?" :
                        "이 폴더와 모든 하위 폴더의 SRT·SMI·ASS·VTT·SUB·SUP·IDX·TXT·NFO " +
                                "파일을 탐색기 목록에서 숨깁니다. 실제 파일은 삭제하지 않으며 " +
                                "동영상 목록의 외부 자막 표시는 계속 유지됩니다."))
                .setNegativeButton("취소", null)
                .setPositiveButton(directlySelected ? "해제" : "설정", (d, w) -> {
                    if (directlySelected) {
                        PlayerSettings.removeHiddenFolder(this, normalized);
                    } else {
                        PlayerSettings.addHiddenFolder(this, normalized);
                        PlayerSettings.prefs(this).edit()
                                .putBoolean(PlayerSettings.HIDE_SUBTITLES, true).apply();
                    }
                    folderCache.clearCounts();
                    toast(directlySelected ? "동영상 폴더 설정을 해제했습니다." :
                            "동영상 폴더와 하위 폴더에 적용했습니다.");
                    refreshCurrent();
                }).show();
    }
    private void renameLocal(File local, DocumentFile saf, String oldName) {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(oldName);
        input.selectAll();
        new AlertDialog.Builder(this).setTitle("이름 변경")
                .setView(input).setNegativeButton("취소", null)
                .setPositiveButton("변경", (d, i) -> {
                    String next = input.getText().toString().trim();
                    try {
                        RemoteProvider.child("/", next);
                        boolean ok;
                        if (local != null) {
                            File target = new File(local.getParentFile(), next);
                            ok = !target.exists() && local.renameTo(target);
                        } else ok = (safFolder.findFile(next) == null ||
                                next.equals(oldName)) && saf.renameTo(next);
                        if (!ok) throw new IOException("파일 이름을 변경할 수 없습니다.");
                        refreshCurrent();
                    } catch (Exception e) { failure(e); }
                }).show();
    }
    private void refreshCurrent() {
        if ("local".equals(currentScreen)) browseLocal(localFolder);
        else if ("saf".equals(currentScreen)) browseSaf(safFolder, safRelative);
        else if ("remote".equals(currentScreen)) openRemote(remote, remoteFolder);
        else if ("remotes".equals(currentScreen)) showRemoteList();
        else if ("favorites".equals(currentScreen)) showFavorites();
        else if ("assigned".equals(currentScreen) && assignedMode!=null)
            showAssignedFolders(assignedMode);
        else if ("media".equals(currentScreen) && mediaKind != null)
            openMedia(mediaKind);
        else showHome();
    }
    private void showRemoteList() {
        currentScreen = "remotes";
        remote = null;
        render("원격 저장소", true, "+", () -> remoteForm(null));
        muted("NAS · TeraBox · 네이버 MYBOX 연결");
        // Unlike the Windows build, Android does not currently have a
        // Google Drive OAuth client registered for its package/signature.
        // Offer the platform's Google Drive DocumentsProvider instead.
        // It uses the user's already logged-in Android Drive account and
        // persistable folder permissions, never a Desktop OAuth client.
        TextView reviewNotice = Ui.text(this,
                "Google 인증 심사 진행 중 · 이용을 원하시면 관리자에게 문의하세요.",
                12, 0xFFFFCF64, true);
        reviewNotice.setPadding(dp(4), dp(10), dp(4), dp(4));
        page.addView(reviewNotice, Ui.lp(this, -1, -2));
        String googleTree = store.googleDriveTreeUri();
        row(page, "Google Drive (Android 파일 선택)",
                googleTree.isEmpty()
                        ? "기기 Google Drive 앱에서 폴더 선택 후 등록"
                        : "등록됨 · 선택한 폴더 열기",
                "remote", Ui.TEAL, this::openGoogleDriveSaf,
                this::showGoogleDriveSafSettings);
        try {
            List<Models.RemoteProfile> profiles = store.activeRemotes();
            if (profiles.isEmpty()) {
                Ui.gap(page, this, 16);
                muted("연결을 추가하면 여기에 표시돼요.");
            }
            for (Models.RemoteProfile profile : profiles) {
                row(page, profile.label(), profile.summary(), "remote",
                        Ui.TEAL, () -> openRemote(profile, "/"),
                        () -> remoteMenu(profile));
            }
        } catch (Exception e) {
            showError("저장된 연결 설정을 읽을 수 없습니다. 암호화 키를 확인하세요.");
        }
        Ui.gap(page, this, 12);
        TextView add = Ui.action(this, "+  원격 저장소 추가");
        page.addView(add, Ui.lp(this, -1, 49));
        add.setOnClickListener(v -> remoteForm(null));
        Ui.gap(page,this,12);
        TextView backup=Ui.action(this,"설정 파일 내보내기 · 가져오기");
        page.addView(backup,Ui.lp(this,-1,52));
        backup.setOnClickListener(v -> settingsBackupUi().show());
    }

    private void openGoogleDriveSaf() {
        String saved = store.googleDriveTreeUri();
        if (!saved.isEmpty()) {
            try {
                Uri uri = Uri.parse(saved);
                if (GOOGLE_DRIVE_DOCUMENTS_AUTHORITY.equals(uri.getAuthority()) &&
                        hasPersistedTreeRead(uri)) {
                    DocumentFile root = DocumentFile.fromTreeUri(this, uri);
                    if (root != null && root.exists() && root.canRead()) {
                        activeTreeUri = saved;
                        activeTreeLabel = "Google Drive (선택 폴더)";
                        openSafPath("/");
                        return;
                    }
                }
            } catch (Exception ignored) { /* Re-select a valid system folder. */ }
        }
        new AlertDialog.Builder(this)
                .setTitle("Google Drive 연결")
                .setMessage("기기의 Google Drive 앱에 로그인한 후 Android 파일 선택 화면에서 " +
                        "원하는 Drive 폴더를 선택하세요. Android 시스템이 부여한 해당 " +
                        "폴더의 읽기·쓰기 권한으로 연결하며, 별도 JSON 입력이나 " +
                        "FileHub용 Google 로그인 토큰은 필요하지 않습니다.\n\n" +
                        "이 기능은 선택한 폴더에만 접근합니다. 전체 Drive를 PC처럼 " +
                        "자동 마운트하는 기능과는 다릅니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("폴더 선택", (d, w) ->
                        pickTree(REQ_GOOGLE_DRIVE_SAF))
                .show();
    }

    private void showGoogleDriveSafSettings() {
        new AlertDialog.Builder(this)
                .setTitle("Google Drive 폴더 연결")
                .setMessage("연결을 다시 선택하거나 FileHub의 등록 정보를 해제할 수 있습니다. " +
                        "Google 계정 및 Google Drive의 원본 파일은 삭제되지 않습니다.")
                .setNegativeButton("닫기", null)
                .setNeutralButton("연결 해제", (d, w) -> {
                    store.googleDriveTreeUri("");
                    showRemoteList();
                })
                .setPositiveButton("다시 선택", (d, w) ->
                        pickTree(REQ_GOOGLE_DRIVE_SAF))
                .show();
    }
    private void showNasShareCreate(FileTransferEngine.Item item) {
        showNasShareCreateForPath(item.remote, item.path, item.name, false);
    }

    private void showNasShareCreateForPath(Models.RemoteProfile p, String path,
                                           String name, boolean fileStationPath) {
        if (!NasSharePolicy.supported(p)) return;
        LinearLayout layout = Ui.vertical(this);
        layout.setPadding(dp(20), dp(10), dp(20), dp(10));
        TextView file = Ui.text(this, name, 14, Ui.TEXT, true);
        file.setSingleLine(true);
        file.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        layout.addView(file, Ui.lp(this, -1, 40));
        TextView label = Ui.text(this, "유효기간", 12, Ui.MUTED, true);
        layout.addView(label, Ui.lp(this, -1, 24));
        Spinner expiry = new Spinner(this);
        expiry.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"1일", "3일", "7일", "30일", "무제한", "직접 지정"}));
        expiry.setSelection(2);
        layout.addView(expiry, Ui.lp(this, -1, 48));
        EditText date = field(layout, "만료일 직접 지정 (YYYY-MM-DD)", "",
                android.text.InputType.TYPE_CLASS_DATETIME |
                        android.text.InputType.TYPE_DATETIME_VARIATION_DATE);
        date.setHint("2026-12-31");
        date.setVisibility(View.GONE);
        View dateLabel = layout.getChildAt(layout.indexOfChild(date) - 1);
        dateLabel.setVisibility(View.GONE);
        expiry.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> a,
                                                 View v, int index, long id) {
                date.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
                dateLabel.setVisibility(index == 5 ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        EditText pass = field(layout, "링크 비밀번호 (선택)", "",
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        TextView hint = Ui.text(this,
                "링크를 받은 사람이 NAS에 로그인하지 않고 다운로드할 수 있습니다. " +
                        "외부 접속 가능 여부는 NAS HTTPS/QuickConnect/myQNAPcloud 설정에 따라 다릅니다.",
                12, Ui.MUTED, false);
        layout.addView(hint, Ui.lp(this, -1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        new AlertDialog.Builder(this).setTitle("공유 링크 생성")
                .setView(scroll).setNegativeButton("닫기", null)
                .setPositiveButton("링크 생성", (d, w) -> {
                    int[] days = {1, 3, 7, 30, 0, -1};
                    int selected = days[expiry.getSelectedItemPosition()];
                    String custom = date.getText().toString().trim();
                    String secret = pass.getText().toString();
                    try {
                        NasSharePolicy.expiryDate(selected, custom);
                        if (NasSharePolicy.SYNOLOGY.equals(p.nasVendor) && secret.length() > 16)
                            throw new IllegalArgumentException("Synology 링크 암호는 최대 16자입니다.");
                        runJob("NAS에서 공유 링크 생성 중...",
                                () -> fileStationPath ?
                                        new NasShareApi(p).createFileStationPath(
                                                path, selected, custom, secret) :
                                        new NasShareApi(p).create(path, selected, custom, secret),
                                link -> showNasShareResult(p, link));
                    } catch (Exception error) { showError(safeError(error)); }
                }).show();
    }

    private void shareNasUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, url);
        startActivity(Intent.createChooser(intent, "다운로드 링크 공유"));
    }

    private void showNasShareResult(Models.RemoteProfile p, NasShareApi.Link link) {
        new AlertDialog.Builder(this).setTitle("다운로드 링크 생성 완료")
                .setMessage(link.url + "\n\n이 링크를 받은 사람은 파일을 다운로드할 수 있습니다. " +
                        "외부에서 접속되는지 확인한 뒤 전달하세요.")
                .setNegativeButton("닫기", null)
                .setNeutralButton("링크 복사", (d, w) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "NAS 다운로드 공유 링크", link.url));
                    toast("공유 링크를 복사했습니다.");
                })
                .setPositiveButton("공유하기", (d, w) -> shareNasUrl(link.url))
                .show();
    }

    private void showNasShareList(Models.RemoteProfile p) {
        if (!NasSharePolicy.supported(p)) return;
        runJob("NAS 공유 링크 목록 확인 중...", () -> new NasShareApi(p).list(), links -> {
            if (links.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("NAS 공유 링크")
                        .setMessage("현재 설정한 공유 폴더에 발급된 링크가 없습니다.")
                        .setPositiveButton("확인", null).show();
                return;
            }
            String[] labels = new String[links.size()];
            boolean[] checked = new boolean[links.size()];
            for (int i = 0; i < links.size(); i++) {
                NasShareApi.Link link = links.get(i);
                labels[i] = link.path + (link.expires > 0 ?
                        " · 만료 " + new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.KOREA)
                                .format(new Date(link.expires * 1000L)) : " · 만료 정보 없음");
            }
            LinearLayout heading = Ui.vertical(this);
            heading.setPadding(dp(18), dp(14), dp(18), dp(5));
            TextView title = Ui.text(this, "NAS 공유 링크 관리", 18, Ui.TEXT, true);
            heading.addView(title, Ui.lp(this, -1, 34));
            TextView selectionCount = Ui.text(this, "0 / " + links.size() + "개 선택",
                    13, Ui.MUTED, false);
            heading.addView(selectionCount, Ui.lp(this, -1, 25));
            LinearLayout controls = Ui.horizontal(this);
            TextView all = Ui.action(this, "전체 선택");
            TextView clear = Ui.action(this, "선택 해제");
            all.setGravity(Gravity.CENTER);
            clear.setGravity(Gravity.CENTER);
            controls.addView(all, new LinearLayout.LayoutParams(0, dp(44), 1f));
            controls.addView(clear, new LinearLayout.LayoutParams(0, dp(44), 1f));
            heading.addView(controls, Ui.lp(this, -1, 44));

            AlertDialog manager = new AlertDialog.Builder(this)
                    .setCustomTitle(heading)
                    .setMultiChoiceItems(labels, checked, (dialog, index, selected) -> {
                        checked[index] = selected;
                        updateNasLinkSelectionCount(selectionCount, checked);
                    })
                    .setNegativeButton("닫기", null)
                    .setNeutralButton("링크 복사", null)
                    .setPositiveButton("선택 링크 해제", null)
                    .create();
            manager.setOnShowListener(unused -> {
                all.setOnClickListener(view -> {
                    for (int i = 0; i < checked.length; i++) {
                        checked[i] = true;
                        manager.getListView().setItemChecked(i, true);
                    }
                    updateNasLinkSelectionCount(selectionCount, checked);
                });
                clear.setOnClickListener(view -> {
                    for (int i = 0; i < checked.length; i++) {
                        checked[i] = false;
                        manager.getListView().setItemChecked(i, false);
                    }
                    updateNasLinkSelectionCount(selectionCount, checked);
                });
                manager.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                    List<NasShareApi.Link> selected = selectedNasLinks(links, checked);
                    if (selected.size() != 1) {
                        toast("링크 하나만 선택하면 주소를 복사할 수 있습니다.");
                        return;
                    }
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "NAS 공유 링크", selected.get(0).url));
                    toast("공유 링크를 복사했습니다.");
                });
                manager.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    List<NasShareApi.Link> selected = selectedNasLinks(links, checked);
                    if (selected.isEmpty()) {
                        toast("해제할 공유 링크를 선택하세요.");
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(selected.size() + "개 공유 링크를 해제할까요?")
                            .setMessage("선택한 다운로드 공유 주소만 무효화합니다. " +
                                    "NAS에 저장된 원본 파일과 폴더는 삭제되지 않습니다.")
                            .setNegativeButton("취소", null)
                            .setPositiveButton("공유 링크 해제", (dialog, which) -> {
                                manager.dismiss();
                                runJob("선택한 NAS 공유 링크 해제 중...",
                                        () -> new NasShareApi(p).revokeMany(selected),
                                        report -> showNasBulkRevokeReport(p, report));
                            }).show();
                });
            });
            manager.show();
        });
    }

    private static void updateNasLinkSelectionCount(TextView label, boolean[] checked) {
        int count = 0;
        for (boolean value : checked) if (value) count++;
        label.setText(count + " / " + checked.length + "개 선택");
    }

    private static List<NasShareApi.Link> selectedNasLinks(List<NasShareApi.Link> links,
                                                            boolean[] checked) {
        List<NasShareApi.Link> selected = new ArrayList<>();
        for (int i = 0; i < links.size(); i++)
            if (checked[i]) selected.add(links.get(i));
        return selected;
    }

    private void showNasBulkRevokeReport(Models.RemoteProfile profile,
                                         NasShareApi.RevokeReport report) {
        StringBuilder text = new StringBuilder()
                .append("요청 ").append(report.requested).append("개 / 성공 ")
                .append(report.succeeded).append("개 / 실패 ")
                .append(report.failed()).append("개");
        if (!report.failures.isEmpty()) {
            text.append("\n\n실패한 링크:\n");
            for (int i = 0; i < Math.min(8, report.failures.size()); i++)
                text.append("- ").append(report.failures.get(i)).append("\n");
            if (report.failures.size() > 8)
                text.append("외 ").append(report.failures.size() - 8).append("개");
        }
        new AlertDialog.Builder(this).setTitle("공유 링크 일괄 해제 결과")
                .setMessage(text.toString())
                .setNegativeButton("닫기", null)
                .setPositiveButton("목록 새로고침", (dialog, which) -> showNasShareList(profile))
                .show();
    }

    private void showNasShareBrowser(Models.RemoteProfile p, String folder) {
        if (!NasSharePolicy.supported(p)) return;
        String current = RemoteProvider.relativePath(folder);
        if (!"/".equals(current) && !NasSharePolicy.inAllowedRoot(p, current)) {
            showError("선택하지 않은 NAS 공유 폴더입니다.");
            return;
        }
        runJob("File Station 공유 폴더 탐색 중...", () -> {
            if ("/".equals(current)) {
                List<NasShareApi.NasEntry> shares = new ArrayList<>();
                for (String root : NasSharePolicy.roots(p))
                    shares.add(new NasShareApi.NasEntry(root.substring(1), root, true));
                return shares;
            }
            return new NasShareApi(p).listFolder(current);
        }, entries -> {
            String[] names = new String[entries.size()];
            for (int i = 0; i < entries.size(); i++) {
                NasShareApi.NasEntry entry = entries.get(i);
                names[i] = (entry.directory ? "📁 " : "📄 ") + entry.name;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("/".equals(current) ? "NAS 공유 폴더" : current)
                    .setItems(names, (d, selectedIndex) -> {
                        NasShareApi.NasEntry selected = entries.get(selectedIndex);
                        if (!selected.directory) {
                            showNasShareCreateForPath(p, selected.path, selected.name, true);
                            return;
                        }
                        new AlertDialog.Builder(this).setTitle(selected.name)
                                .setItems(new String[]{"폴더 열기", "이 폴더 공유 링크 만들기"},
                                        (dialog, option) -> {
                                            if (option == 0)
                                                showNasShareBrowser(p, selected.path);
                                            else
                                                showNasShareCreateForPath(p, selected.path,
                                                        selected.name, true);
                                        }).setNegativeButton("닫기", null).show();
                    })
                    .setPositiveButton("닫기", null);
            if (!"/".equals(current)) {
                String parent = RemoteProvider.parent(current);
                if (!NasSharePolicy.inAllowedRoot(p, parent)) parent = "/";
                String back = parent;
                builder.setNegativeButton("상위 폴더", (d, w) ->
                        showNasShareBrowser(p, back));
            }
            if (entries.isEmpty()) builder.setMessage("이 폴더는 비어 있습니다.");
            builder.show();
        });
    }

    private void showNasShareSettings(Models.RemoteProfile p) {
        if (!("SMB".equals(p.protocol) || "SFTP".equals(p.protocol) ||
                "FTP".equals(p.protocol) || "WebDAV".equals(p.protocol))) {
            showError("공유 링크는 Synology·QNAP 개인 NAS 연결에서만 사용할 수 있습니다.");
            return;
        }
        LinearLayout panel = Ui.vertical(this);
        panel.setPadding(dp(20), dp(8), dp(20), dp(12));
        TextView hint = Ui.text(this,
                "DSM/QTS File Station 관리 API를 별도로 연결합니다. " +
                        "제조사는 실제 로그인 및 File Station 응답 확인 후 활성화됩니다. " +
                        "관리 주소는 올바른 HTTPS 인증서가 있어야 합니다.",
                12, Ui.MUTED, false);
        panel.addView(hint, Ui.lp(this, -1, -2));
        Ui.gap(panel, this, 8);
        Spinner vendor = new Spinner(this);
        String[] vendors = {"사용 안 함", "Synology DSM", "QNAP QTS / QuTS"};
        vendor.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, vendors));
        vendor.setSelection(NasSharePolicy.SYNOLOGY.equals(p.nasVendor) ? 1 :
                NasSharePolicy.QNAP.equals(p.nasVendor) ? 2 : 0);
        panel.addView(vendor, Ui.lp(this, -1, 48));
        TextView qnapCloudButton = Ui.action(this, "QNAP CloudLink로 포트 개방 없이 접속 (공식 웹)");
        panel.addView(qnapCloudButton, Ui.lp(this, -1, 48));
        qnapCloudButton.setVisibility(vendor.getSelectedItemPosition() == 2 ?
                View.VISIBLE : View.GONE);
        qnapCloudButton.setOnClickListener(view -> showQnapCloudBrowser(p));
        EditText address = field(panel, "NAS 관리 HTTPS 주소",
                p.nasApiBase, android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_URI);
        address.setHint("https://nas.example.com:5001");
        EditText shareRoot = field(panel, "File Station 공유 폴더 기준 경로",
                p.nasShareRoot, android.text.InputType.TYPE_CLASS_TEXT);
        shareRoot.setHint("예: /video 또는 /Public");
        final List<String> checkedNasRoots = new ArrayList<>(NasSharePolicy.roots(p));
        TextView selectedRoots = Ui.text(this, "", 12, Ui.TEXT, false);
        selectedRoots.setText(checkedNasRoots.isEmpty() ? "선택된 공유 폴더 없음" :
                "선택: " + android.text.TextUtils.join(", ", checkedNasRoots));
        TextView findShares = Ui.action(this, "공유 폴더 목록 불러오기 · 여러 개 선택");
        panel.addView(findShares, Ui.lp(this, -1, 48));
        panel.addView(selectedRoots, Ui.lp(this, -1, -2));
        EditText publicHost = field(panel, "외부 공유용 HTTPS 주소 (선택 · QNAP)",
                p.nasPublicHost, android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_URI);
        publicHost.setHint("https://nas.example.com:443");
        EditText account = field(panel, "NAS 관리 계정", p.nasApiUser,
                android.text.InputType.TYPE_CLASS_TEXT);
        EditText password = field(panel, "NAS 관리 비밀번호",
                p.nasApiPassword, android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final String[] verifiedDevice = {p.nasDeviceId};
        final String[] verifiedAccount = {p.nasApiUser};
        final String[] verifiedEndpoint = {p.nasApiBase};
        final String[] verifiedPassword = {p.nasApiPassword};
        findShares.setOnClickListener(v -> {
            View focused = getCurrentFocus();
            if (focused != null) {
                android.view.inputmethod.InputMethodManager keyboard =
                        (android.view.inputmethod.InputMethodManager)
                                getSystemService(INPUT_METHOD_SERVICE);
                if (keyboard != null)
                    keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
                focused.clearFocus();
            }
            if (vendor.getSelectedItemPosition() == 0) {
                showError("Synology 또는 QNAP 제조사를 먼저 선택하세요.");
                return;
            }
            try {
                Models.RemoteProfile candidate = new Models.RemoteProfile();
                candidate.nasVendor = vendor.getSelectedItemPosition() == 1 ?
                        NasSharePolicy.SYNOLOGY : NasSharePolicy.QNAP;
                candidate.nasApiBase = NasSharePolicy.validateApiBase(
                        address.getText().toString());
                candidate.nasApiUser = account.getText().toString().trim();
                candidate.nasApiPassword = password.getText().toString();
                if (candidate.nasApiUser.equals(verifiedAccount[0]) &&
                        candidate.nasApiBase.equals(verifiedEndpoint[0]) &&
                        candidate.nasApiPassword.equals(verifiedPassword[0]))
                    candidate.nasDeviceId = verifiedDevice[0];
                if (candidate.nasApiUser.isEmpty() || candidate.nasApiPassword.isEmpty())
                    throw new IllegalArgumentException("먼저 NAS 관리 계정과 비밀번호를 입력하세요.");
                runJob("NAS 공유 폴더 조회 중...",
                        () -> new NasShareApi(candidate).listShares(), shares -> {
                            verifiedDevice[0] = candidate.nasDeviceId;
                            verifiedAccount[0] = candidate.nasApiUser;
                            verifiedEndpoint[0] = candidate.nasApiBase;
                            verifiedPassword[0] = candidate.nasApiPassword;
                            if (shares.isEmpty()) {
                                showError("접근 가능한 공유 폴더가 없습니다.");
                                return;
                            }
                            String[] names = new String[shares.size()];
                            boolean[] chosen = new boolean[shares.size()];
                            for (int i = 0; i < shares.size(); i++) {
                                NasShareApi.NasEntry share = shares.get(i);
                                names[i] = share.name;
                                chosen[i] = checkedNasRoots.contains(share.path);
                            }
                            new AlertDialog.Builder(this)
                                    .setTitle("링크 공유를 허용할 NAS 폴더")
                                    .setMultiChoiceItems(names, chosen,
                                            (dialog, index, isChecked) -> chosen[index] = isChecked)
                                    .setNegativeButton("닫기", null)
                                    .setPositiveButton("선택 완료", (dialog, which) -> {
                                        checkedNasRoots.clear();
                                        for (int i = 0; i < chosen.length; i++)
                                            if (chosen[i]) checkedNasRoots.add(shares.get(i).path);
                                        selectedRoots.setText(checkedNasRoots.isEmpty() ?
                                                "선택된 공유 폴더 없음" :
                                                "선택: " + android.text.TextUtils.join(", ", checkedNasRoots));
                                        if (!checkedNasRoots.isEmpty())
                                            shareRoot.setText(checkedNasRoots.get(0));
                                    }).show();
                        });
            } catch (Exception error) { showError(safeError(error)); }
        });
        shareRoot.setVisibility(vendor.getSelectedItemPosition() > 0 ? View.GONE : View.VISIBLE);
        View rootLabel = panel.getChildAt(panel.indexOfChild(shareRoot) - 1);
        rootLabel.setVisibility(shareRoot.getVisibility());
        findShares.setVisibility(vendor.getSelectedItemPosition() > 0 ? View.VISIBLE : View.GONE);
        selectedRoots.setVisibility(findShares.getVisibility());
        vendor.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 View view, int index, long id) {
                boolean nas = index > 0;
                qnapCloudButton.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
                shareRoot.setVisibility(nas ? View.GONE : View.VISIBLE);
                rootLabel.setVisibility(shareRoot.getVisibility());
                findShares.setVisibility(nas ? View.VISIBLE : View.GONE);
                selectedRoots.setVisibility(nas ? View.VISIBLE : View.GONE);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        TextView warning = Ui.text(this,
                "SMB 경로와 File Station 경로는 다를 수 있습니다. " +
                        "예: SMB 시작 경로 /video/영화 → API 기준 폴더 /video/영화. " +
                        "기본 전송 접속 설정은 변경되지 않습니다.",
                12, Ui.MUTED, false);
        Ui.gap(panel, this, 10);
        panel.addView(warning, Ui.lp(this, -1, -2));
        ScrollView scrolling = new ScrollView(this);
        scrolling.addView(panel);
        new AlertDialog.Builder(this).setTitle("NAS 공유 링크 설정")
                .setView(scrolling)
                .setNegativeButton("닫기", null)
                .setNeutralButton("연결 해제", (d, w) -> {
                    p.nasVendor = "";
                    p.nasApiBase = "";
                    p.nasApiUser = "";
                    p.nasApiPassword = "";
                    p.nasDeviceId = "";
                    p.nasShareRoot = "";
                    p.nasShareRoots.clear();
                    p.nasPublicHost = "";
                    try { store.putRemote(p); toast("NAS 공유 기능 연결을 해제했습니다."); }
                    catch (Exception error) { failure(error); }
                })
                .setPositiveButton("인증 확인 후 저장", (d, w) -> {
                    if (vendor.getSelectedItemPosition() == 0) {
                        showError("먼저 Synology 또는 QNAP 제조사를 선택하세요. " +
                                "기능을 해제하려면 '연결 해제'를 누르세요.");
                        return;
                    }
                    try {
                        String nasVendor = vendor.getSelectedItemPosition() == 1 ?
                                NasSharePolicy.SYNOLOGY : NasSharePolicy.QNAP;
                        Models.RemoteProfile test = new Models.RemoteProfile();
                        test.nasVendor = nasVendor;
                        test.nasApiBase = NasSharePolicy.validateApiBase(
                                address.getText().toString());
                        if (NasSharePolicy.SYNOLOGY.equals(nasVendor) ||
                                NasSharePolicy.QNAP.equals(nasVendor)) {
                            for (String root : checkedNasRoots)
                                test.nasShareRoots.add(NasSharePolicy.validateShareRoot(root));
                            if (test.nasShareRoots.isEmpty())
                                throw new IllegalArgumentException("'공유 폴더 목록 불러오기'에서 사용할 폴더를 선택하세요.");
                            test.nasShareRoot = test.nasShareRoots.get(0);
                        } else {
                            test.nasShareRoot = NasSharePolicy.validateShareRoot(
                                    shareRoot.getText().toString());
                            test.nasShareRoots.add(test.nasShareRoot);
                        }
                        test.nasPublicHost = NasSharePolicy.validatePublicHost(
                                publicHost.getText().toString());
                        test.nasApiUser = account.getText().toString().trim();
                        test.nasApiPassword = password.getText().toString();
                        if (test.nasApiUser.equals(verifiedAccount[0]) &&
                                test.nasApiBase.equals(verifiedEndpoint[0]) &&
                                test.nasApiPassword.equals(verifiedPassword[0]))
                            test.nasDeviceId = verifiedDevice[0];
                        if (test.nasApiUser.isEmpty() || test.nasApiPassword.isEmpty())
                            throw new IllegalArgumentException("NAS 관리 계정과 비밀번호를 입력하세요.");
                        runJob("NAS 제조사 및 File Station 인증 확인 중...", () -> {
                            new NasShareApi(test).verify(); return true;
                        }, unused -> {
                            p.nasVendor = test.nasVendor;
                            p.nasApiBase = test.nasApiBase;
                            p.nasShareRoot = test.nasShareRoot;
                            p.nasShareRoots = new ArrayList<>(test.nasShareRoots);
                            p.nasPublicHost = test.nasPublicHost;
                            p.nasApiUser = test.nasApiUser;
                            p.nasApiPassword = test.nasApiPassword;
                            p.nasDeviceId = test.nasDeviceId;
                            try {
                                store.putRemote(p);
                                toast("NAS 공유 링크 인증이 완료됐습니다.");
                            } catch (Exception ex) { failure(ex); }
                        });
                    } catch (Exception ex) { showError(safeError(ex)); }
                }).show();
    }

    private void remoteMenu(Models.RemoteProfile p) {
        boolean privateNas = "SMB".equals(p.protocol) || "WebDAV".equals(p.protocol) ||
                "SFTP".equals(p.protocol) || "FTP".equals(p.protocol);
        boolean shareReady = privateNas && NasSharePolicy.supported(p);
        boolean synologyBrowser = shareReady;
        boolean qnapCloud = privateNas && (NasSharePolicy.QNAP.equals(p.nasVendor) ||
                !p.qnapCloudSmartUrl.isEmpty());
        java.util.List<String> menu = new java.util.ArrayList<>();
        menu.add("연결하기");
        menu.add("설정 수정");
        if (privateNas) menu.add("NAS 공유 링크 설정");
        if (shareReady) {
            menu.add("NAS 공유 폴더 탐색");
            menu.add("공유 링크 관리");
        }
        if (qnapCloud) menu.add("QNAP CloudLink (포트 개방 없이 웹 접속)");
        menu.add("연결 삭제");
        new AlertDialog.Builder(this).setTitle(p.label())
                .setItems(menu.toArray(new String[0]),
                        (d, i) -> {
                            String choice = menu.get(i);
                            if (choice.equals("연결하기")) openRemote(p, "/");
                            else if (choice.equals("설정 수정")) remoteForm(p);
                            else if (choice.equals("NAS 공유 링크 설정")) showNasShareSettings(p);
                            else if (choice.equals("NAS 공유 폴더 탐색")) showNasShareBrowser(p, "/");
                            else if (choice.equals("공유 링크 관리")) showNasShareList(p);
                            else if (choice.startsWith("QNAP CloudLink")) showQnapCloudBrowser(p);
                            else if (choice.equals("연결 삭제"))
                                new AlertDialog.Builder(this)
                                    .setTitle("원격 연결 삭제")
                                    .setMessage("저장한 ‘" + p.label() +
                                            "’ 연결 정보만 삭제합니다. 서버 파일은 변경되지 않습니다.")
                                    .setNegativeButton("취소", null)
                                    .setPositiveButton("삭제", (d2, j) -> {
                                        try { store.deleteRemote(p.id); showRemoteList(); }
                                        catch (Exception e) { failure(e); }
                                    }).show();
                        }).show();
    }

    private void openQnapOfficialSite(String url) {
        String safe = QnapCloudLinkPolicy.smartUrl(url);
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(safe));
        browser.addCategory(Intent.CATEGORY_BROWSABLE);
        try { startActivity(browser); }
        catch (ActivityNotFoundException error) {
            showError("myQNAPcloud 페이지를 열려면 웹 브라우저가 필요합니다.");
        }
    }

    private void showQnapCloudBrowser(Models.RemoteProfile profile) {
        LinearLayout box = Ui.vertical(this);
        box.setPadding(dp(20), dp(12), dp(20), dp(10));
        box.addView(Ui.text(this,
                "공식 myQNAPcloud Link 웹 접속입니다. NAS 관리 포트 개방은 필요하지 않습니다. " +
                        "QNAP 계정으로 로그인하여 File Station에서 공유 링크를 만드세요. " +
                        "FileHub의 직접 NAS API 연결과는 별개이며 웹 세션을 가져오지 않습니다.",
                13, Ui.TEXT, false), Ui.lp(this, -1, -2));
        Ui.gap(box, this, 12);
        EditText smartUrl = field(box, "내 NAS SmartURL (선택)",
                profile.qnapCloudSmartUrl, android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_URI);
        smartUrl.setHint("https://qlink.to/내장치이름");
        box.addView(Ui.text(this,
                "SmartURL을 모르면 '공식 포털 열기'를 선택하세요. " +
                        "QNAP 계정 > 내 장치에서 SmartURL을 확인할 수 있습니다.",
                12, Ui.MUTED, false), Ui.lp(this, -1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("QNAP CloudLink · 포트 개방 불필요")
                .setView(box)
                .setNegativeButton("닫기", null)
                .setNeutralButton("공식 포털 열기", (d, w) ->
                        openQnapOfficialSite(QnapCloudLinkPolicy.PORTAL))
                .setPositiveButton("SmartURL 저장 후 열기", null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    try {
                        String url = QnapCloudLinkPolicy.smartUrl(
                                smartUrl.getText().toString());
                        if (QnapCloudLinkPolicy.PORTAL.equals(url)) {
                            showError("장치 SmartURL을 입력하거나 공식 포털 열기를 선택하세요.");
                            return;
                        }
                        profile.qnapCloudSmartUrl = url;
                        store.putRemote(profile);
                        dialog.dismiss();
                        openQnapOfficialSite(url);
                    } catch (Exception error) { showError(safeError(error)); }
                }));
        dialog.show();
    }
    private EditText field(LinearLayout parent, String label, String value,
                           int inputType) {
        TextView title = Ui.text(this, label, 12, Ui.MUTED, true);
        LinearLayout.LayoutParams t = Ui.lp(this, -1, 23);
        t.topMargin = dp(8);
        parent.addView(title, t);
        EditText edit = new EditText(this);
        edit.setTextSize(16);
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        edit.setText(value);
        edit.setPadding(dp(6), dp(8), dp(6), dp(8));
        parent.addView(edit, Ui.lp(this, -1, 43));
        return edit;
    }
    private boolean isReadOnlyCloud(Models.RemoteProfile p) {
        return CloudProvider.isReadOnly(p);
    }

    private void openTeraBoxInSystemBrowser() {
        // OAuth consent must run in a trusted external browser. Do not attempt
        // to inspect, scrape or import another app's private cookie store.
        Uri url = Uri.parse(TeraBoxWebPolicy.LOGIN_URL);
        Intent chrome = new Intent(Intent.ACTION_VIEW, url);
        chrome.addCategory(Intent.CATEGORY_BROWSABLE);
        chrome.setPackage("com.android.chrome");
        try {
            startActivity(chrome);
        } catch (ActivityNotFoundException noChrome) {
            Intent browser = new Intent(Intent.ACTION_VIEW, url);
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(Intent.createChooser(browser, "인터넷 브라우저 선택"));
            } catch (ActivityNotFoundException noBrowser) {
                showError("Chrome 또는 다른 인터넷 브라우저를 설치해야 합니다.");
            }
        }
    }

    private void showTeraBoxGoogleLoginNotice() {
        new AlertDialog.Builder(this)
                .setTitle("Google 계정으로 TeraBox 로그인")
                .setMessage("Google 보안 정책 때문에 앱 내부 웹 로그인은 차단됩니다. " +
                        "Chrome에서 Google 계정으로 TeraBox에 로그인할 수 있습니다.\n\n" +
                        "단, Chrome에서 로그인해도 NAS Mount에 자동 연결되지는 않습니다. " +
                        "Chrome의 TeraBox 세션(ndus)을 다른 앱이 읽을 수 없기 때문입니다.\n\n" +
                        "NAS Mount 연결용 세션값은 PC Chrome에서 로그인 후 개발자 도구(F12) " +
                        "> Application > Cookies > terabox.com > ndus를 확인하고, " +
                        "안전하게 휴대폰으로 옮겨 'TeraBox 로그인 세션(ndus)' 칸에 붙여넣으세요. " +
                        "세션값은 비밀번호처럼 보호해야 하며 타인과 공유하면 안 됩니다.")
                .setNegativeButton("닫기", null)
                .setPositiveButton("Chrome에서 로그인", (d, w) ->
                        openTeraBoxInSystemBrowser())
                .show();
    }

    private void teraboxWebLogin(java.util.function.Consumer<String> onCookie) {
        WebView web = new WebView(this);
        // AlertDialog's embedded WebView is not a native EditText, so make
        // sure its dynamic HTML editors receive focus and keyboard access.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN)
                view.requestFocus();
            if (event.getAction() == MotionEvent.ACTION_UP) {
                view.postDelayed(() -> {
                    if (!view.hasFocus() || !view.onCheckIsTextEditor()) return;
                    InputMethodManager ime = (InputMethodManager)
                            getSystemService(INPUT_METHOD_SERVICE);
                    if (ime != null)
                        ime.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
                }, 120);
            }
            return false; // Preserve the original WebView HTML tap.
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(TeraBoxWebPolicy.DESKTOP_USER_AGENT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebChromeClient(new WebChromeClient());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,
                    android.webkit.WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (TeraBoxWebPolicy.isGoogleAuthNavigation(
                        request.getUrl().toString())) {
                    showTeraBoxGoogleLoginNotice();
                    return true;
                }
                if (!TeraBoxWebPolicy.isAllowedWebNavigation(
                        request.getUrl().toString())) {
                    toast("외부 앱/허용되지 않은 주소로 이동하지 않습니다.");
                    return true;
                }
                // Return false to keep the official HTTPS page inside this
                // WebView, rather than launching an installed Android app.
                return false;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("TeraBox PC 웹 로그인")
                .setMessage("TeraBox 자체 계정으로 로그인한 후 세션 가져오기를 누르세요. " +
                        "Google 계정 로그인은 이 창에서 지원되지 않으며, " +
                        "외부 Chrome 로그인이 필요합니다.")
                .setView(web)
                .setNegativeButton("닫기", null)
                .setNeutralButton("PC 웹 새로고침", null)
                .setPositiveButton("세션 가져오기", null).create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                // Clear after show: AlertController may set this flag when
                // it sees a WebView rather than a native EditText.
                dialog.getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                dialog.getWindow().setLayout(
                        (int)(getResources().getDisplayMetrics().widthPixels * .96f),
                        (int)(getResources().getDisplayMetrics().heightPixels * .75f));
            }
            web.requestFocus();
            web.loadUrl(TeraBoxWebPolicy.LOGIN_URL);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                    v -> web.loadUrl(TeraBoxWebPolicy.LOGIN_URL));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    CookieManager.getInstance().flush();
                    String session = null;
                    for (String site : new String[]{
                            "https://www.terabox.com",
                            "https://www.terabox.app",
                            "https://www.teraboxapp.com"}) {
                        String cookies = CookieManager.getInstance().getCookie(site);
                        if (cookies == null) continue;
                        try { session = CloudProvider.ndusCookie(cookies); break; }
                        catch (IOException missingSession) { /* check next domain */ }
                    }
                    if (session == null) throw new IOException("ndus 쿠키가 없습니다.");
                    onCookie.accept(session);
                    dialog.dismiss();
                } catch (Exception e) {
                    showError("로그인 세션(ndus)을 아직 찾지 못했습니다. " +
                            "PC 웹에서 로그인을 완료했는지 확인해 주세요. " +
                            "웹사이트가 로그인을 차단하면 수동 ndus 입력을 사용할 수 있습니다.");
                }
            });
        });
        dialog.setOnDismissListener(d -> {
            web.stopLoading();
            web.destroy();
        });
        dialog.show();
    }

    private void remoteForm(Models.RemoteProfile original) {
        String[] types = {"SMB", "FTP", "SFTP", "WebDAV",
                "TeraBox", "MYBOX"};
        final int[] defaultPorts = {445, 21, 22, 443, 0, 0};
        LinearLayout form = Ui.vertical(this);
        form.setPadding(dp(20), dp(6), dp(20), dp(12));
        TextView protocolTitle = Ui.text(this, "연결 방식", 12, Ui.MUTED, true);
        form.addView(protocolTitle, Ui.lp(this, -1, 26));
        Spinner protocol = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, types);
        protocol.setAdapter(adapter);
        form.addView(protocol, Ui.lp(this, -1, 49));
        EditText title = field(form, "표시할 이름", original == null ? "" : original.name,
                android.text.InputType.TYPE_CLASS_TEXT);
        title.setHint("예: 집 NAS");
        EditText host = field(form, "서버 주소", original == null ? "" : original.host,
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_URI);
        host.setHint("호스트 또는 IP");
        EditText port = field(form, "포트", original == null ? "445" :
                String.valueOf(original.port),
                android.text.InputType.TYPE_CLASS_NUMBER);
        EditText root = field(form, "공유/시작 경로", original == null ? "" : original.root,
                android.text.InputType.TYPE_CLASS_TEXT);
        root.setHint("SMB: 공유이름    기타: /시작폴더");
        EditText username = field(form, "사용자명 (익명 FTP는 비워 두기)",
                original == null ? "" : original.username,
                android.text.InputType.TYPE_CLASS_TEXT);
        EditText password = field(form, "비밀번호", original == null ? "" : original.password,
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox https = new CheckBox(this);
        https.setText("WebDAV HTTPS 보안 연결 사용");
        https.setTextSize(14);
        https.setChecked(original == null || original.ssl);
        form.addView(https, Ui.lp(this, -1, 44));
        TextView cloudHint = Ui.text(this, "", 12, Ui.MUTED, false);
        cloudHint.setPadding(dp(3), dp(5), dp(3), dp(5));
        form.addView(cloudHint, Ui.lp(this, -1, -2));
        TextView teraboxAuth = Ui.action(this, "TeraBox 계정으로 웹 로그인 · 세션 가져오기");
        form.addView(teraboxAuth, Ui.lp(this, -1, 42));
        teraboxAuth.setOnClickListener(v -> teraboxWebLogin(
                ndus -> password.setText(ndus)));
        TextView teraboxGoogle = Ui.action(this, "Google 계정 로그인 (Chrome에서 열기)");
        form.addView(teraboxGoogle, Ui.lp(this, -1, 42));
        teraboxGoogle.setOnClickListener(v -> showTeraBoxGoogleLoginNotice());
        final android.widget.Button[] pcHelpButton = new android.widget.Button[1];
        if (original != null) {
            int existingType = Arrays.asList(types).indexOf(original.protocol);
            if (existingType >= 0) protocol.setSelection(existingType);
        }
        final int[] prevSelected = {protocol.getSelectedItemPosition()};
        protocol.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 View view, int selected, long id) {
                if (selected != prevSelected[0]) {
                    port.setText(String.valueOf(defaultPorts[selected]));
                    prevSelected[0] = selected;
                }
                https.setVisibility(selected == 3 ? View.VISIBLE : View.GONE);
                boolean ordinary = selected < 4;
                host.setVisibility(ordinary ? View.VISIBLE : View.GONE);
                port.setVisibility(ordinary ? View.VISIBLE : View.GONE);
                username.setVisibility(ordinary ? View.VISIBLE : View.GONE);
                password.setVisibility(View.VISIBLE);
                teraboxAuth.setVisibility(selected == 4 ? View.VISIBLE : View.GONE);
                teraboxGoogle.setVisibility(selected == 4 ? View.VISIBLE : View.GONE);
                if (pcHelpButton[0] != null) pcHelpButton[0].setVisibility(
                        selected == 4 ? View.VISIBLE : View.GONE);
                root.setVisibility(selected == 5 ? View.GONE : View.VISIBLE);
                // Each field() inserts a label directly before its EditText.
                // Hide both to avoid empty label-only gaps in cloud mode.
                ViewGroup fields = (ViewGroup) host.getParent();
                for (EditText edit : new EditText[]{host, port, username, password, root}) {
                    int position = fields.indexOfChild(edit);
                    if (position > 0) fields.getChildAt(position - 1)
                            .setVisibility(edit.getVisibility());
                }
                int passwordPosition = fields.indexOfChild(password);
                if (passwordPosition > 0) ((TextView) fields.getChildAt(
                        passwordPosition - 1)).setText(selected == 4 ?
                        "TeraBox 로그인 세션 (ndus)" : selected == 5 ?
                        "네이버 MYBOX 액세스 토큰 (PAT)" : "비밀번호");
                if (selected == 4) {
                    password.setHint("ndus 세션값 또는 ndus=...");
                    cloudHint.setText("TeraBox 베타: 폴더·파일 읽기/쓰기 지원. ndus 세션값이 필요합니다. " +
                            "Chrome의 Google 로그인 세션은 자동으로 복사되지 않습니다. " +
                            "PC 브라우저에서 ndus를 확인해 직접 붙여넣을 수 있습니다.");
                } else if (selected == 5) {
                    password.setHint("mbx_pat_... 개인 액세스 토큰");
                    cloudHint.setText("네이버 MYBOX 공식 Open API PAT가 필요합니다. " +
                            "폴더 탐색·업로드·다운로드·이름 변경·복사·이동·삭제를 지원합니다.");
                } else {
                    password.setHint("");
                    cloudHint.setText("");
                }
                root.setHint(selected == 0 ? "공유이름/하위경로 (필수)" :
                        "선택 사항 · 시작 경로 /");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(original == null ? "원격 저장소 추가" : "원격 저장소 수정")
                .setView(scroll).setNegativeButton("취소", null)
                .setNeutralButton("PC에서 가져오는 방법", null)
                .setPositiveButton("저장", null).create();
        dialog.setOnShowListener(ignore -> {
            pcHelpButton[0] = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            pcHelpButton[0].setAllCaps(false);
            pcHelpButton[0].setTextSize(11);
            pcHelpButton[0].setVisibility(protocol.getSelectedItemPosition() == 4 ?
                    View.VISIBLE : View.GONE);
            pcHelpButton[0].setOnClickListener(v -> showTeraBoxPcHelp());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    int selected = protocol.getSelectedItemPosition();
                    boolean cloud = selected >= 4;
                    String address = host.getText().toString().trim();
                    if (!cloud && (address.isEmpty() || address.contains("/") ||
                            address.contains("@") || address.contains(" ") ||
                            address.contains("://")))
                        throw new IllegalArgumentException("서버 주소에는 호스트 또는 IP만 입력하세요.");
                    int selectedPort = cloud ? 0 :
                            Integer.parseInt(port.getText().toString().trim());
                    if (!cloud && (selectedPort < 1 || selectedPort > 65535))
                        throw new IllegalArgumentException("포트 범위는 1~65535입니다.");
                    String share = selected == 5 ? "/" : root.getText().toString().trim();
                    if (selected == 0 && RemoteProvider.relativePath(share).equals("/"))
                        throw new IllegalArgumentException("SMB 공유 이름을 입력하세요.");
                    if (selected == 2 && username.getText().toString().trim().isEmpty())
                        throw new IllegalArgumentException("SFTP 사용자명을 입력하세요.");
                    // Validate before storing credentials to the Android Keystore.
                    RemoteProvider.relativePath(share);
                    if (selected == 4) CloudProvider.ndusCookie(password.getText().toString());
                    if (selected == 5 &&
                            !CloudProvider.validMyBoxToken(password.getText().toString()))
                        throw new IllegalArgumentException("MYBOX PAT는 mbx_pat_로 시작해야 합니다.");
                    Models.RemoteProfile p = original == null ? new Models.RemoteProfile() : original;
                    if (original != null && (!address.equalsIgnoreCase(original.host) ||
                            selectedPort != original.port || !types[selected].equals(original.protocol)))
                        p.fingerprint = ""; // A new host requires fresh user verification.
                    p.protocol = types[selected];
                    p.host = cloud ? (selected == 4 ? "terabox.com" :
                            "mybox.naver.com") : address;
                    p.port = selectedPort;
                    p.name = title.getText().toString().trim();
                    p.root = share;
                    p.username = cloud ? "" : username.getText().toString().trim();
                    p.password = password.getText().toString();
                    p.ssl = https.isChecked();
                    store.putRemote(p);
                    dialog.dismiss();
                    showRemoteList();
                    toast("연결 설정을 저장했습니다.");
                } catch (Exception e) { showError(safeError(e)); }
            });
        });
        dialog.show();
    }

    private void showTeraBoxPcHelp() {
        new AlertDialog.Builder(this)
                .setTitle("PC에서 TeraBox 세션 가져오는 방법")
                .setMessage("① PC Chrome에서 TeraBox 공식 웹사이트에 로그인합니다. " +
                        "Google 계정으로 로그인해도 됩니다.\n\n" +
                        "② 키보드 F12를 눌러 개발자 도구를 열고 " +
                        "Application(애플리케이션) → Cookies(쿠키)를 선택합니다.\n\n" +
                        "③ 로그인한 TeraBox 주소를 선택한 뒤 'ndus' 항목의 " +
                        "Value(값)를 확인합니다. 계정마다 값이 다릅니다.\n\n" +
                        "④ 세션값을 본인 휴대폰으로 안전하게 옮겨 " +
                        "NAS Mount의 'TeraBox 로그인 세션(ndus)' 칸에 붙여넣습니다. " +
                        "표시할 이름을 적고 저장한 뒤 연결하세요.\n\n" +
                        "※ ndus는 계정에 접근 가능한 민감한 세션 키입니다. " +
                        "스크린샷이나 채팅에 올리지 말고, 타인과 공유하지 마세요. " +
                        "세션이 만료되면 다시 가져와야 할 수 있습니다.")
                .setPositiveButton("확인", null).show();
    }

    private void openRemote(Models.RemoteProfile profile, String folder) {
        if (profile == null) { showRemoteList(); return; }
        if ("GoogleDrive".equals(profile.protocol)) {
            showError("Android판에서는 Google Drive 연결을 지원하지 않습니다.");
            return;
        }
        remote = profile;
        remoteFolder = RemoteProvider.relativePath(folder);
        final String requestedFolder = remoteFolder;
        currentScreen = "remote";
        render(profile.label(), true, "⋮", this::remoteFolderActions);
        showRemoteBreadcrumb(profile, remoteFolder);
        final int screen = renderGeneration;
        List<Models.Entry> cached = folderCache.peek(profile, requestedFolder);
        if (cached != null) {
            // Returning to the same folder needs neither a loading screen
            // nor an additional network request until explicit refresh.
            showRemoteContents(profile, requestedFolder, cached);
            return;
        }
        TextView loading = muted("서버 폴더를 불러오는 중...");
        runJob("네트워크 폴더 연결 중...", () ->
                cachedRemoteFiles(profile, requestedFolder), list -> {
            if (screen != renderGeneration ||
                    remote != profile || !currentScreen.equals("remote") ||
                    !remoteFolder.equals(requestedFolder)) return;
            page.removeView(loading);
            showRemoteContents(profile, remoteFolder, list);
        });
    }
    private List<Models.Entry> cachedRemoteFiles(Models.RemoteProfile profile,
                                                  String folder) throws Exception {
        return folderCache.load(profile, folder,
                () -> RemoteProvider.list(profile, folder));
    }
    private void refreshRemote(Models.RemoteProfile profile, String folder) {
        folderCache.invalidateProfile(profile);
        videoHeadersProbed.clear();
        openRemote(profile, folder);
    }

    /** Explicit refresh bypasses the otherwise persistent session snapshot. */
    private void refreshCurrentFolderFromToolbar() {
        if ("remote".equals(currentScreen) && remote != null) {
            refreshRemote(remote, remoteFolder);
        } else if ("local".equals(currentScreen) && localFolder != null) {
            folderCache.clearCounts();
            localSubtitleProbed.clear();
            browseLocal(localFolder);
        } else if ("saf".equals(currentScreen) && safFolder != null) {
            folderCache.clearCounts();
            browseSaf(safFolder, safRelative);
        } else if ("media".equals(currentScreen) && mediaKind != null) {
            openMedia(mediaKind);
        } else refreshCurrent();
    }

    /** Larger, horizontally scrolling, tappable actual path without 'WebDAV'. */
    private void showRemoteBreadcrumb(Models.RemoteProfile profile, String folder) {
        showFolderBreadcrumb(RemoteBreadcrumbs.steps(folder), remoteFolder,
                path -> navigateAssignedBreadcrumb("remote", profile.id, path,
                        target -> openRemote(profile, target)));
    }

    private void showLocalBreadcrumb(File directory) {
        File sdRoot = directSdRoot(sdVolume());
        File mainRoot = Environment.getExternalStorageDirectory();
        File volumeRoot = sdRoot != null &&
                StorageBreadcrumbs.withinRoot(sdRoot.getAbsolutePath(),
                        directory.getAbsolutePath()) ? sdRoot :
                StorageBreadcrumbs.withinRoot(mainRoot.getAbsolutePath(),
                        directory.getAbsolutePath()) ? mainRoot : directory;
        showFolderBreadcrumb(StorageBreadcrumbs.steps(
                volumeRoot.getAbsolutePath(),directory.getAbsolutePath()),
                directory.getAbsolutePath(),
                path -> navigateAssignedBreadcrumb("local", "", path,
                        target -> browseLocal(new File(target))));
    }

    private void showSafBreadcrumb() {
        // The root step is the authorized DocumentFile tree, not the SD
        // device root; never navigate outside the granted SAF subtree.
        showFolderBreadcrumb(RemoteBreadcrumbs.steps(safRelative),
                safRelative, path -> navigateAssignedBreadcrumb("saf", activeTreeUri,
                        path, this::openSafPath));
    }

    /** Shared remote/local/SAF breadcrumb: same sizes, colors and tap area. */
    private void showFolderBreadcrumb(List<RemoteBreadcrumbs.Step> steps,
                                      String currentPath,
                                      java.util.function.Consumer<String> navigate) {
        android.widget.HorizontalScrollView scroll =
                new android.widget.HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams bounds = Ui.lp(this, -1, 58);
        bounds.bottomMargin = dp(12);
        page.addView(scroll, bounds);
        LinearLayout crumbs = Ui.horizontal(this);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setPadding(dp(4), dp(2), dp(7), dp(2));
        scroll.addView(crumbs, new android.widget.FrameLayout.LayoutParams(
                -2, -1, Gravity.CENTER_VERTICAL));
        for (int i = 0; i < steps.size(); i++) {
            RemoteBreadcrumbs.Step crumb = steps.get(i);
            boolean isCurrent = i == steps.size() - 1;
            if (i > 0) {
                TextView separator = Ui.text(this, "›", 20, Ui.MUTED, false);
                separator.setGravity(Gravity.CENTER);
                crumbs.addView(separator, Ui.lp(this, 18, -1));
            }
            TextView segment = Ui.text(this, crumb.label,
                    isCurrent ? 16 : 15, isCurrent ? Ui.TEAL : Ui.TEXT, isCurrent);
            segment.setGravity(Gravity.CENTER);
            segment.setPadding(dp(9), 0, dp(9), 0);
            segment.setMinWidth(dp(42));
            segment.setSingleLine(true);
            segment.setContentDescription("폴더 이동: " +
                    (i == 0 ? "저장소 최상위" : crumb.path));
            segment.setBackground(Ui.bg(this,
                    isCurrent ? Ui.PALE : Ui.SURFACE, 9, 0));
            crumbs.addView(segment, Ui.lp(this, -2, 46));
            segment.setOnClickListener(v -> {
                if (!crumb.path.equals(currentPath))
                    navigate.accept(crumb.path);
            });
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void showRemoteContents(Models.RemoteProfile p, String folder,
                                    List<Models.Entry> files) {
        final int screen=renderGeneration;
        final int requestedSort=currentFolderSortMode();
        TextView loading=muted("파일 목록을 정리하는 중...");
        worker.execute(()->{
            List<String> siblings=new ArrayList<>(files.size());
            List<Models.Entry> sorted=new ArrayList<>(files);
            for(Models.Entry entry:files)siblings.add(entry.name);
            sorted.sort((a,b)->FileViewOptions.compare(
                    a.directory,a.name,a.size,a.modified,
                    b.directory,b.name,b.size,b.modified,requestedSort));
            List<VideoEpisodeQueue.Episode> episodes=new ArrayList<>();
            for(Models.Entry entry:sorted)
                if(!entry.directory&&VideoEpisodeQueue.isVideo(entry.name))
                    episodes.add(new VideoEpisodeQueue.Episode(
                            entry.path,entry.name,entry.size));
            VideoEpisodeQueue queue=VideoEpisodeQueue.from(episodes);
            runOnUiThread(()->{
                if(screen!=renderGeneration || !"remote".equals(currentScreen) ||
                        remote!=p || !remoteFolder.equals(folder) || isFinishing())return;
                page.removeView(loading);
                siblingSubtitleIndex=SubtitleInventory.fromSiblings(siblings);
                visibleEpisodeQueue=queue;
                ImageFolderSequence.showFolder("remote", p.id, sorted, showHidden);
                if(sorted.isEmpty())muted("이 폴더는 비어 있습니다.");
                indexFullFolderSearch(sorted,file->()->{
                    if(file.directory)openRemote(p,file.path);
                    else if(isVideo(file.name))openRemoteVideo(p,file);
                    else openRemoteFile(p,file);
                });
                appendDirectoryPage(sorted,0,screen,file->
                        fileRow(file,()->{
                            if(file.directory)openRemote(p,file.path);
                            else if(isVideo(file.name))openRemoteVideo(p,file);
                            else openRemoteFile(p,file);
                        },file.directory?()->{
                            List<Models.Entry> children=cachedRemoteFiles(p,file.path);
                            int count=0;
                            for(Models.Entry child:children)
                                if(FileViewOptions.visible(child.name,false,showHidden))
                                    count++;
                            return count;
                        }:null));
            });
        });
    }
    private void remoteFolderActions() {
        if (remote == null) return;
        if (isReadOnlyCloud(remote)) {
            new AlertDialog.Builder(this).setTitle("클라우드 폴더")
                    .setItems(new String[]{"즐겨찾기 추가/해제", "새로고침",
                                    "폴더 용도 설정"},
                            (dialog, which) -> {
                                if (which == 0) favoriteHere("remote", remoteFolder,
                                        remoteFolder.equals("/") ? remote.label() :
                                                remoteFolder.substring(remoteFolder.lastIndexOf('/')+1),
                                        remote.id);
                                else if(which==1)refreshRemote(remote, remoteFolder);
                                else showFolderPurposeMenu(currentFolderItem());
                            }).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("폴더 작업")
                .setItems(new String[]{"파일 업로드", "새로 만들기", "즐겨찾기 추가/해제",
                        "새로고침", "폴더 용도 설정"}, (dialog, which) -> {
                    if (which == 0) pickUpload();
                    if (which == 1) chooseNewEntry();
                    if (which == 2) favoriteHere("remote", remoteFolder,
                            remoteFolder.equals("/") ? remote.label() :
                                    remoteFolder.substring(remoteFolder.lastIndexOf('/')+1),
                            remote.id);
                    if (which == 3) refreshRemote(remote, remoteFolder);
                    if (which == 4) showFolderPurposeMenu(currentFolderItem());
                }).show();
    }
    private void createRemoteFolder() {
        if (remote == null) return;
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setHint("새 폴더 이름");
        new AlertDialog.Builder(this).setTitle("새 폴더")
                .setView(name).setNegativeButton("취소", null)
                .setPositiveButton("만들기", (d, i) -> {
                    try {
                        String path = RemoteProvider.child(remoteFolder,
                                name.getText().toString().trim());
                        Models.RemoteProfile target = remote;
                        runJob("폴더 생성 중...", () -> {
                            RemoteProvider.mkdir(target, path); return true;
                        }, ignored -> refreshRemote(target, remoteFolder));
                    } catch (Exception e) { failure(e); }
                }).show();
    }
    private void openRemoteFile(Models.RemoteProfile p, Models.Entry entry) {
        if (ComicArchive.supports(entry.name) &&
                readerFolders.allowed(ReaderFolders.COMIC,"remote",p.id,
                        RemoteProvider.parent(entry.path))) {
            openBookReader(ComicBookActivity.class,"remote",entry.name,
                    entry.path,null,p);
            return;
        }
        if (entry.name.toLowerCase(Locale.ROOT).endsWith(".txt") &&
                readerFolders.allowed(ReaderFolders.TEXT,"remote",p.id,
                        RemoteProvider.parent(entry.path))) {
            openBookReader(BookTextActivity.class,"remote",entry.name,
                    entry.path,null,p);
            return;
        }
        if (entry.name.toLowerCase(Locale.ROOT).endsWith(".epub") &&
                readerFolders.allowed(ReaderFolders.TEXT,"remote",p.id,
                        RemoteProvider.parent(entry.path))) {
            openBookReader(EpubReaderActivity.class,"remote",entry.name,
                    entry.path,null,p);
            return;
        }
        if(PdfBookTypes.supports(entry.name)){
            openBookReader(EpubReaderActivity.class,"remote",entry.name,
                    entry.path,null,p);
            return;
        }
        if(MediaFileTypes.isAudio(entry.name)){
            playRemoteMusic(p,entry);
            return;
        }
        if(MediaFileTypes.isImage(entry.name)){
            viewImage("remote",entry.name,entry.path,null,p);
            return;
        }
        if (ArchiveCodec.isArchive(entry.name)) {
            offerExtractArchive(FileTransferEngine.remote(p, entry));
            return;
        }
        if (OfficeFileTypes.supports(entry.name)) {
            openBuiltInOffice("remote", entry.name, entry.path, null, p);
            return;
        }
        if (EditableFileTypes.isEditableText(entry.name)) {
            openTextEditor("remote", entry.name, entry.path, null, p);
            return;
        }
        if (EditableFileTypes.isApk(entry.name)) {
            stageApkAndOfferInstall(entry.name,
                    () -> RemoteProvider.openStream(p, entry.path, 0));
            return;
        }
        runJob("파일을 여는 중...", () -> {
            File dir = new File(getCacheDir(), "shared_files");
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("캐시 폴더 접근 실패");
            String safe = entry.name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File output = new File(dir, java.util.UUID.randomUUID().toString() + "_" + safe);
            try (OutputStream out = new FileOutputStream(output)) {
                downloadRemoteEntry(p, entry, out);
            } catch (Exception error) { output.delete(); throw error; }
            return output;
        }, this::openFile);
    }
    /**
     * Stages local, SAF and NAS APK bytes in private cache, then delegates
     * user-approved installation to Android's system package installer.
     */
    private void stageApkAndOfferInstall(String name, Job<InputStream> openSource) {
        runJob("설치 패키지 준비 중...", () -> {
            try (InputStream input = openSource.execute()) {
                if (input == null) throw new IOException("APK 파일을 열 수 없습니다.");
                return ApkInstallStaging.stage(input, getCacheDir());
            }
        }, staged -> {
            android.content.pm.PackageInfo pkg =
                    getPackageManager().getPackageArchiveInfo(staged.getAbsolutePath(), 0);
            if (pkg == null || pkg.packageName == null || pkg.packageName.isEmpty()) {
                showError("Android 패키지 정보를 확인할 수 없습니다. " +
                        "단독 APK 설치 파일인지 확인해 주세요.");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("APK 설치")
                    .setMessage("파일: " + name +
                            "\n패키지: " + pkg.packageName +
                            "\n버전: " + pkg.versionName +
                            "\n크기: " + android.text.format.Formatter.formatFileSize(
                                    this, staged.length()) +
                            "\n\n신뢰할 수 있는 APK만 설치하세요. " +
                            "최종 설치 여부는 Android 설치 관리자에서 결정합니다.")
                    .setNegativeButton("취소", null)
                    .setPositiveButton("설치 화면 열기",
                            (dialog, which) -> requestPackageInstall(staged))
                    .show();
        });
    }

    private void requestPackageInstall(File staged) {
        pendingApkInstall = staged;
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("NAS Mount의 APK 설치 권한")
                    .setMessage("Android 설정에서 '이 출처의 앱 허용'을 켜야 " +
                            "APK 설치 화면을 열 수 있습니다. 설정에서 돌아오면 계속합니다.")
                    .setNegativeButton("취소", (d, w) -> pendingApkInstall = null)
                    .setPositiveButton("설정 열기", (d, w) -> {
                        try {
                            Intent settings = new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()));
                            startActivityForResult(settings, REQ_APK_INSTALL_AUTH);
                        } catch (ActivityNotFoundException unavailable) {
                            showError("이 기기의 앱 설치 허용 화면을 열 수 없습니다.");
                        }
                    }).show();
            return;
        }
        launchSystemApkInstaller(staged);
    }

    private void launchSystemApkInstaller(File staged) {
        if (staged == null || !staged.isFile()) {
            pendingApkInstall = null;
            showError("APK 파일이 사라졌습니다. 다시 선택해 주세요.");
            return;
        }
        try {
            ApkInstallStaging.verifyArchive(staged);
            Uri share = FileProvider.getUriForFile(this,
                    getPackageName() + ".files", staged);
            Intent install = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            install.setDataAndType(share, "application/vnd.android.package-archive");
            install.setClipData(android.content.ClipData.newUri(
                    getContentResolver(), "APK 설치 파일", share));
            install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(install);
            } catch (ActivityNotFoundException oldInstaller) {
                install.setAction(Intent.ACTION_VIEW);
                startActivity(install);
            }
            pendingApkInstall = null;
        } catch (ActivityNotFoundException missing) {
            showError("시스템 패키지 설치 관리자를 찾지 못했습니다.");
        } catch (Exception failure) {
            showError("APK 설치 준비 오류: " + safeError(failure));
        }
    }

    private void downloadRemoteEntry(Models.RemoteProfile profile, Models.Entry file,
                                     OutputStream output) throws Exception {
        RemoteProvider.download(profile, file.path, output);
    }
    private void downloadRemote(Models.RemoteProfile p, Models.Entry entry) {
        runJob("다운로드 중...", () -> {
            MediaIndexing.Category category = MediaIndexing.category(entry.name);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, entry.name);
                values.put(MediaStore.MediaColumns.MIME_TYPE,
                        MediaIndexing.mime(entry.name));
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        MediaIndexing.relativePath(category));
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri dest = getContentResolver().insert(
                        MediaIndexing.collection(category), values);
                if (dest == null) throw new IOException("다운로드 저장소 접근 실패");
                try {
                    try (OutputStream out = getContentResolver().openOutputStream(dest)) {
                        if (out == null) throw new IOException("다운로드 파일 생성 실패");
                        downloadRemoteEntry(p, entry, out);
                    }
                    values.clear();
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (getContentResolver().update(dest, values, null, null) < 1)
                        throw new IOException("미디어 등록을 완료하지 못했습니다.");
                } catch (Exception e) { getContentResolver().delete(dest, null, null); throw e; }
            } else {
                File folder = new File(Environment.getExternalStorageDirectory(),
                        MediaIndexing.relativePath(category));
                if (!folder.exists() && !folder.mkdirs()) throw new IOException("다운로드 폴더 없음");
                File target = new File(folder, entry.name);
                if (target.exists()) throw new IOException("동일한 이름의 파일이 있습니다.");
                try (OutputStream out = new FileOutputStream(target)) {
                    downloadRemoteEntry(p, entry, out);
                } catch (Exception e) { target.delete(); throw e; }
                MediaIndexing.scanLocal(this, target);
            }
            return MediaIndexing.relativePath(category);
        }, folder -> toast(folder + " 폴더에 저장했습니다."));
    }
    private void renameRemote(Models.RemoteProfile p, Models.Entry entry) {
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setText(entry.name);
        name.selectAll();
        new AlertDialog.Builder(this).setTitle("이름 변경")
                .setView(name).setNegativeButton("취소", null)
                .setPositiveButton("변경", (d, i) -> {
                    try {
                        String newPath = RemoteProvider.child(
                                RemoteProvider.parent(entry.path),
                                name.getText().toString().trim());
                        runJob("이름 변경 중...", () -> {
                            RemoteProvider.rename(p, entry.path, newPath, entry.directory);
                            return true;
                        }, ignored -> refreshRemote(p, remoteFolder));
                    } catch (Exception e) { failure(e); }
                }).show();
    }
    private void deleteRemote(Models.RemoteProfile p, Models.Entry entry) {
        new AlertDialog.Builder(this).setTitle("삭제 확인")
                .setMessage("서버의 ‘" + entry.name + "’을(를) 삭제할까요?\\n" +
                        "이 작업은 되돌릴 수 없을 수 있습니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (d, i) ->
                        runJob("네트워크 파일 삭제 중...", () -> {
                            RemoteProvider.delete(p, entry.path, entry.directory);
                            return true;
                        }, ignored -> refreshRemote(p, remoteFolder))).show();
    }
    private void pickUpload() {
        if (remote == null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_UPLOAD);
    }

    @android.annotation.SuppressLint("WrongConstant")
    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if(requestCode==REQ_LAN_QR_SCAN) {
            if(resultCode==RESULT_OK && data!=null){
                String scanned=data.getStringExtra(QrScanActivity.EXTRA_QR);
                if(scanned!=null)settingsBackupUi().handleLanQrScanned(scanned);
            }
            return;
        }
        if(requestCode==SettingsBackupUi.EXPORT_REQUEST ||
                requestCode==SettingsBackupUi.IMPORT_REQUEST) {
            settingsBackupUi().handleResult(requestCode,resultCode,data);
            return;
        }
        if (requestCode == REQ_APK_INSTALL_AUTH) {
            if (pendingApkInstall != null) {
                if (getPackageManager().canRequestPackageInstalls())
                    launchSystemApkInstaller(pendingApkInstall);
                else {
                    pendingApkInstall = null;
                    toast("APK 설치 권한이 허용되지 않아 취소했습니다.");
                }
            }
            return;
        }
        if (requestCode == REQ_TEXT_EDITOR || requestCode == REQ_OFFICE_EDITOR) {
            if (resultCode == RESULT_OK) {
                if (remote != null) folderCache.invalidateProfile(remote);
                folderCache.clearCounts();
                refreshCurrent();
            }
            return;
        }
        if (requestCode == REQ_SETTINGS) {
            Ui.applyAppearance(this);
            setTheme(Ui.darkMode ? R.style.AppThemeDark : R.style.AppTheme);
            container.setBackgroundColor(Ui.BG);
            getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode ?
                    0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            refreshCurrent();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri chosen = data.getData();
        if (requestCode == REQ_SD || requestCode == REQ_FOLDER ||
                requestCode == REQ_GOOGLE_DRIVE_SAF) {
            try {
                if (requestCode == REQ_GOOGLE_DRIVE_SAF &&
                        !GOOGLE_DRIVE_DOCUMENTS_AUTHORITY.equals(chosen.getAuthority())) {
                    showError("Google Drive 폴더를 선택해 주세요. 기기에 Google Drive 앱을 " +
                            "설치하고 Google 계정으로 로그인한 뒤 다시 시도하세요.");
                    return;
                }
                if (requestCode == REQ_SD) {
                    StorageVolume currentCard = sdVolume();
                    if (currentCard == null) {
                        showError("SD카드가 제거되었거나 연결이 끊겼습니다. " +
                                "카드 장착 상태를 확인해 주세요.");
                        return;
                    }
                    String treeId = DocumentsContract.getTreeDocumentId(chosen);
                    if (!SdAccessPolicy.validSdDocumentId(chosen.getAuthority(),
                            treeId, currentCard.getUuid())) {
                        showError("선택한 위치가 현재 SD카드의 폴더가 아닙니다. " +
                                "SD카드에 들어가 하위 폴더를 선택해 주세요. " +
                                "Android 11 이상에서 SD카드 최상위 폴더는 선택할 수 " +
                                "없는 경우가 있습니다.");
                        return;
                    }
                }
                int grant = data.getFlags() & (
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                if ((grant & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                    showError("선택한 SD/저장소 폴더의 읽기 권한을 받지 못했습니다. " +
                            "폴더를 다시 선택해 주세요.");
                    return;
                }
                getContentResolver().takePersistableUriPermission(chosen, grant);
                if (!hasPersistedTreeRead(chosen)) {
                    showError("폴더 권한을 영구 저장하지 못했습니다. " +
                            "폴더 선택 화면에서 '사용 허용'을 확인해 주세요.");
                    return;
                }
                DocumentFile selectedRoot = DocumentFile.fromTreeUri(this, chosen);
                if (selectedRoot == null || !selectedRoot.canRead()) {
                    showError("폴더가 선택됐지만 읽기 권한이 없습니다. " +
                            "다른 폴더를 선택하거나 전체 파일 접근 권한을 허용해 주세요.");
                    return;
                }
                activeTreeUri = chosen.toString();
                if (requestCode == REQ_GOOGLE_DRIVE_SAF) {
                    store.googleDriveTreeUri(chosen.toString());
                    activeTreeLabel = "Google Drive (선택 폴더)";
                } else if (requestCode == REQ_SD) {
                    store.sdTreeUri(chosen.toString());
                    activeTreeLabel = selectedRoot.canWrite() ?
                            "SD 저장소 (선택 폴더)" : "SD 저장소 (읽기 전용)";
                } else {
                    store.selectedTreeUri(chosen.toString());
                    activeTreeLabel = "선택한 폴더";
                }
                openSafPath("/");
            } catch (Exception error) {
                showError("SD/저장소 폴더 권한을 저장하는 중 오류가 발생했습니다. " +
                        "다시 선택하거나 전체 파일 접근 권한을 확인해 주세요. (" +
                        error.getClass().getSimpleName() + ")");
            }
        } else if (requestCode == REQ_UPLOAD && remote != null) {
            final Models.RemoteProfile target = remote;
            final String destination = remoteFolder;
            final String display = documentName(chosen);
            final long byteLength = documentSize(chosen);
            runJob("대상 폴더에서 같은 이름 확인 중...", () -> {
                for (Models.Entry entry : RemoteProvider.list(target, destination))
                    if (entry.name.equalsIgnoreCase(display)) {
                        if (entry.directory)
                            throw new IOException("같은 이름의 폴더가 이미 존재합니다.");
                        return true;
                    }
                return false;
            }, alreadyExists -> {
                if (!alreadyExists) {
                    uploadSelectedDocument(chosen, target, destination,
                            display, byteLength, false);
                    return;
                }
                new AlertDialog.Builder(this)
                        .setTitle("기존 파일 덮어쓰기")
                        .setMessage("대상 폴더에 '" + display + "' 파일이 이미 있습니다.\n\n" +
                                "기존 파일을 새 파일로 덮어쓸까요? " +
                                "취소하면 기존 파일을 그대로 유지합니다.")
                        .setNegativeButton("취소", null)
                        .setPositiveButton("덮어쓰기", (dialog, which) ->
                                uploadSelectedDocument(chosen, target, destination,
                                        display, byteLength, true))
                        .show();
            });
        }
    }

    private void uploadSelectedDocument(Uri chosen, Models.RemoteProfile target,
                                        String destination, String display,
                                        long byteLength, boolean overwrite) {
            runJob("네트워크 저장소에 업로드 중...", () -> {
                try (InputStream in = getContentResolver().openInputStream(chosen)) {
                    if (in == null) throw new IOException("선택한 파일을 읽을 수 없습니다.");
                    RemoteProvider.upload(target, destination, display, in, byteLength,
                            overwrite);
                }
                return true;
            }, result -> {
                toast("업로드를 완료했습니다.");
                refreshRemote(target, destination);
            });
    }

    private String documentName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                RemoteProvider.child("/", name); // Reject path traversal.
                return name;
            }
        } catch (Exception ignored) {}
        return "upload_" + System.currentTimeMillis();
    }

    private long documentSize(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0))
                return Math.max(-1L, cursor.getLong(0));
        } catch (Exception ignored) {}
        return -1L;
    }

    @Override public void onRequestPermissionsResult(int requestCode,
             String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_TRANSFER_NOTIFICATIONS) {
            Runnable action = pendingTransferPermission;
            pendingTransferPermission = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                if (action != null) action.run();
            } else {
                showError("백그라운드 전송 진행률을 알림바에 표시하려면 알림 권한이 필요합니다.");
            }
            return;
        }
        if (requestCode == REQ_MEDIA && mediaKind != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                openMedia(mediaKind);
            else showError("선택한 미디어를 표시하려면 파일 접근 권한이 필요합니다.");
        } else if (requestCode == REQ_MEDIA && permissionPendingFile != null &&
                results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            File pending = permissionPendingFile;
            permissionPendingFile = null;
            browseLocal(pending);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        transferHadActive |= FileTransferService.snapshot().active;
        transferHandler.removeCallbacks(transferTicker);
        transferHandler.post(transferTicker);
        updateTransferUi();
        if (getIntent().getBooleanExtra(FileTransferService.EXTRA_SHOW, false)) {
            getIntent().removeExtra(FileTransferService.EXTRA_SHOW);
            showTransferDialog();
        }
        if (sdPermissionRequestPending) {
            sdPermissionRequestPending = false;
            if (Build.VERSION.SDK_INT >= 30 &&
                    Environment.isExternalStorageManager()) {
                openSd();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("SD카드 접근 권한")
                        .setMessage("전체 파일 접근 권한이 허용되지 않았습니다.\n\n" +
                                "SD카드 전체 대신 특정 하위 폴더만 이용하려면 " +
                                "'SD 폴더 선택'을 누르세요. " +
                                "Android 11 이상에서는 SD카드 최상위 폴더를 " +
                                "선택할 수 없습니다.")
                        .setNegativeButton("닫기", null)
                        .setPositiveButton("SD 폴더 선택",
                                (dialog, which) -> pickTree(REQ_SD))
                        .show();
            }
        }
        musicHandler.removeCallbacks(musicTicker);
        musicHandler.post(musicTicker);
        if (refreshAfterVideo) {
            refreshAfterVideo = false;
            refreshCurrent();
        }
        if(refreshAfterReader){
            refreshAfterReader=false;
            refreshCurrent();
        }
        if (permissionPendingFile != null && (Build.VERSION.SDK_INT < 30 ||
                Environment.isExternalStorageManager())) {
            File target = permissionPendingFile;
            permissionPendingFile = null;
            browseLocal(target);
        }
    }

    @Override protected void onPause(){
        transferHandler.removeCallbacks(transferTicker);
        musicHandler.removeCallbacks(musicTicker);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTvPairIntent(intent);
        if (intent != null && intent.getBooleanExtra(FileTransferService.EXTRA_SHOW, false)) {
            intent.removeExtra(FileTransferService.EXTRA_SHOW);
            showTransferDialog();
        }
    }

    @Override public void onBackPressed() {
        if (drawerShown) {
            closeDrawer();
            return;
        }
        if (!selection.isEmpty()) {
            selection.clear();
            updateFileOperationChrome();
            return;
        }
        if (backFromAssignedEntry()) return;
        switch (currentScreen) {
            case "remote":
                if (remoteFolder.equals("/")) showRemoteList();
                else openRemote(remote, RemoteProvider.parent(remoteFolder));
                break;
            case "local":
                File mainRoot = Environment.getExternalStorageDirectory();
                if (localFolder == null || localFolder.equals(mainRoot) ||
                        isDirectSdRoot(localFolder) ||
                        localFolder.getParentFile() == null) showHome();
                else browseLocal(localFolder.getParentFile());
                break;
            case "saf":
                if (safRelative.equals("/")) showHome();
                else openSafPath(RemoteProvider.parent(safRelative));
                break;
            case "media":
            case "remotes":
            case "favorites":
            case "assigned":
                showHome();
                break;
            default:
                super.onBackPressed();
        }
    }
}
