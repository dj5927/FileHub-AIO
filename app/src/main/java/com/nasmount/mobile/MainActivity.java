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
public class MainActivity extends LocalizedActivity {
    private OtaUpdater otaUpdater;
    private static final int REQ_SD = 501;
    private static final int REQ_UPLOAD = 502;
    private static final int REQ_MEDIA = 503;
    private static final int REQ_FOLDER = 504;
    private static final int REQ_SETTINGS = 505;
    private static final int REQ_TEXT_EDITOR = 506;
    private static final int REQ_OFFICE_EDITOR = 508;
    private static final int REQ_MUSIC_NOTIFICATIONS = 509;
    private static final int REQ_TRANSFER_NOTIFICATIONS = 510;
    static final int REQ_LAN_QR_SCAN = 511;
    private static final int ICON_BLUE = 0xFF4989CF;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    // Shelf listing is read-only and must not wait behind unrelated file I/O.
    private final ExecutorService assignedShelfWorker=Executors.newFixedThreadPool(2);
    private Future<?> assignedShelfTask;
    private final ExecutorService previewWorker = Executors.newFixedThreadPool(2);
    // Counts never compete with thumbnails, subtitle probes, or file I/O.
    private final java.util.concurrent.ThreadPoolExecutor localCountWorker=
            (java.util.concurrent.ThreadPoolExecutor)Executors.newFixedThreadPool(4);
    private final java.util.concurrent.ThreadPoolExecutor remoteCountWorker=
            (java.util.concurrent.ThreadPoolExecutor)Executors.newFixedThreadPool(4);
    private final Handler folderCountHandler=new Handler(Looper.getMainLooper());
    private final Map<java.util.concurrent.FutureTask<Integer>,Runnable> folderCountTasks=new LinkedHashMap<>();
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
    private String renderedLanguage;
    private String currentTitle = "FileHub AIO";
    private File localFolder;
    private DocumentFile safFolder;
    private String safRelative = "/";
    private String activeTreeUri = "";
    private String activeTreeLabel = "SD 저장소";
    private boolean sdPermissionRequestPending;
    private Models.RemoteProfile remote;
    private String remoteFolder = "/";
    // Cached only during this app session; no SSH password or home path is
    // written to backups. Explicit '/' stays an accessible root breadcrumb.
    private final Map<String, String> sftpLoginHomes = new ConcurrentHashMap<>();
    private String sftpHomeDiscoveryPendingId = "";
    private String mediaKind;
    private String assignedMode;
    private AssignedFolderBackBoundary assignedBackBoundary;
    private boolean drawerShown;
    private FlutterNavigationSurface flutterHomeSurface, flutterDrawerSurface, flutterExplorerSurface;
    private ExplorerSession explorerSession;
    private boolean nativeExplorerOnly;
    private List<RemoteBreadcrumbs.Step> explorerCrumbs=Collections.emptyList();
    private Consumer<String> explorerCrumbAction;
    private String explorerQuota="";
    private boolean nativeDrawerOnly, activityResumed;
    // A single attached Flutter viewport for MainActivity navigation.
    // Native toolbar/permissions/I/O remain owned by this Activity.
    private FrameLayout flutterBodyLayer;
    private FlutterNavigationSurface flutterBodySurface;
    private FlutterRemoteEditor embeddedRemoteEditor;
    private String embeddedEditorToken;
    private boolean embeddedEditorSaved,editorAddedSecureFlag;
    private View editorToolbarShade;
    private boolean bodyDisplayed, bodyWaiting;
    private boolean initialHome=true;
    private LinearLayout startupPage;
    private View startupPlaceholder;
    private void clearStartupPlaceholder(){
        if(startupPage!=null&&startupPlaceholder!=null){
            startupPage.removeView(startupPlaceholder);
            for(int i=0;i<startupPage.getChildCount();i++)
                startupPage.getChildAt(i).setVisibility(View.VISIBLE);
        }
        startupPage=null;startupPlaceholder=null;
    }
    private void showStartupPlaceholder(){
        if(!initialHome)return;
        initialHome=false;
        if(!FlutterUiMode.enabled(this))return;
        startupPage=page;
        for(int i=0;i<page.getChildCount();i++)page.getChildAt(i).setVisibility(View.GONE);
        LinearLayout loading=Ui.vertical(this);
        loading.setGravity(Gravity.CENTER);
        ImageView logo=new ImageView(this);
        logo.setImageResource(R.drawable.filehub_aio_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        loading.addView(logo,Ui.lp(this,112,112));
        android.widget.ProgressBar progress=new android.widget.ProgressBar(this);
        LinearLayout.LayoutParams bar=Ui.lp(this,26,26);bar.topMargin=dp(18);
        loading.addView(progress,bar);
        startupPlaceholder=loading;
        page.addView(loading,Ui.lp(this,-1,360));
        currentScroll.post(()->{if(startupPlaceholder==loading&&currentScroll!=null){
            int available=currentScroll.getHeight()-startupPage.getPaddingTop()-startupPage.getPaddingBottom();
            if(available>0)loading.setLayoutParams(new LinearLayout.LayoutParams(-1,available));
        }});
        loading.postDelayed(()->{if(startupPlaceholder==loading)clearStartupPlaceholder();},12000);
    }
    private SettingsContent settingsContent;
    private String settingsReturnScreen="home",settingsScope;
    private final Handler screenHandoffHandler=new Handler(Looper.getMainLooper());
    private final Runnable screenHandoffTimeout=()->{
        if(bodyWaiting){
            bodyDisplayed=false;
            if(flutterBodySurface==null||!flutterBodySurface.isActive())bodyWaiting=false;
            presentBodyLayer();
        }
    };
    private boolean flutterRemoteOrigin;
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
    private boolean showThumbnails = true;
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
    private FlutterNavigationSurface flutterTransferSurface;
    private FlutterFilePrompt flutterFilePrompt;
    private Map<String,Object> lastFlutterTransfer=Collections.emptyMap();
    private boolean lastOperationTransferActive, nativeTransferOnly, destroyingActivity;
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
    private final EditionApkInstaller apkInstaller = new EditionApkInstaller(this);
    private LinearLayout miniPlayerBar;
    private FrameLayout miniPlayerOverlay;
    private boolean miniPlayerShown;
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
        FlutterUiMode.prewarm(this);
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
            apkInstaller.restore(state);
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
        showThumbnails = viewSettings.getBoolean("thumbnails", true);
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
        setContentView(EditionMonetization.content(this,container));
        renderedLanguage=L10n.language();
        showHome();
        if (BuildConfig.DIRECT_EDITION) {
            otaUpdater = new OtaUpdater(this);
            otaUpdater.startupCheck();
        }
        handleTvPairIntent(getIntent());
        handleOneDriveReturn(getIntent());
    }

    private void handleOneDriveReturn(Intent intent){
        if(intent==null||!intent.getBooleanExtra(OneDriveConnectActivity.REFRESH,false))return;
        intent.removeExtra(OneDriveConnectActivity.REFRESH);
        if(embeddedRemoteEditor!=null)closeEmbeddedEditor(true);
        folderCache.clearCounts();showRemoteList();
    }

    private void handleTvPairIntent(Intent intent) {
        if (intent == null || intent.getData() == null ||
                !BuildConfig.PAIR_SCHEME.equalsIgnoreCase(intent.getData().getScheme())) return;
        String host=intent.getData().getHost();
        String value = intent.getData().toString();
        intent.setData(null);
        if("relay".equalsIgnoreCase(host))
            toast(L10n.t("인터넷 QR은 더 이상 지원하지 않습니다. LAN QR을 이용해 주세요."));
        else if("pair".equalsIgnoreCase(host))
            showSendToTv(value);
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean("sd_permission_pending", sdPermissionRequestPending);
        apkInstaller.save(outState);
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
        if (miniPlayerOverlay != null && miniPlayerOverlay.getParent() == container) {
            FrameLayout.LayoutParams musicBounds = (FrameLayout.LayoutParams) miniPlayerOverlay.getLayoutParams();
            if (musicBounds.bottomMargin != navigationBarInsetPx) {
                musicBounds.bottomMargin = navigationBarInsetPx;
                miniPlayerOverlay.setLayoutParams(musicBounds);
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
        destroyingActivity=true;
        if(settingsContent!=null)settingsContent.deactivate();
        finishScreenHandoff();
        if(flutterFilePrompt!=null)flutterFilePrompt.close();
        if(flutterTransferSurface!=null){flutterTransferSurface.close();flutterTransferSurface=null;}
        disposeFlutterNavigation();
        if(otaUpdater!=null)otaUpdater.close();
        stopTvPairing();
        if(settingsBackupUi!=null)settingsBackupUi.shutdown();
        transferHandler.removeCallbacks(transferTicker);
        if (transferDialog != null) transferDialog.dismiss();
        musicHandler.removeCallbacks(musicTicker);
        worker.shutdownNow();
        if(assignedShelfTask!=null)assignedShelfTask.cancel(true);
        assignedShelfWorker.shutdownNow();
        cancelFolderCountJobs();
        localCountWorker.shutdownNow();
        remoteCountWorker.shutdownNow();
        previewWorker.shutdownNow();
        subtitleProbeWorker.shutdownNow();
        if(recursiveSearchTask!=null)recursiveSearchTask.cancel(true);
        recursiveSearchWorker.shutdownNow();
        super.onDestroy();
    }

    private int dp(int n) { return Ui.dp(this, n); }
    void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }
    String safeError(Throwable error) {
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
            if (e instanceof RemoteProvider.SftpPermissionFailure)
                return e.getMessage();
            if (e instanceof SftpConnectionDiagnostics)
                return ((SftpConnectionDiagnostics) e).userMessage();
            if (e instanceof RemoteProvider.UntrustedHostKey)
                return "SFTP 서버의 호스트 키 확인이 필요합니다.";
            if (e instanceof SecurityException &&
                    e.getMessage() != null &&
                    e.getMessage().contains("SFTP 서버 호스트 키"))
                return "SFTP 서버의 호스트 키가 이전에 확인한 키와 다릅니다. " +
                        "다른 서버일 수 있으므로 연결을 차단했습니다. " +
                        "기존 키를 임의로 지우지 말고 서버 관리자에게 확인하세요.";
            if (e instanceof net.schmizz.sshj.userauth.UserAuthException)
                return "SFTP 로그인 인증에 실패했습니다. Steam Deck 계정명(deck)과 " +
                        "설정한 암호, SSH 비밀번호 로그인을 확인하세요.";
            if (e instanceof net.schmizz.sshj.transport.TransportException) {
                String detail = e.getMessage() == null ? "" :
                        e.getMessage().toLowerCase(java.util.Locale.ROOT);
                if (detail.contains("no matching") || detail.contains("negotiat"))
                    return "SFTP 서버와 SSH 암호화/호스트 키 알고리즘 협상이 " +
                            "일치하지 않습니다. SSH 서버 및 앱 버전을 확인하세요.";
                if (detail.contains("encode key") || detail.contains("eddsa") ||
                        detail.contains("ed25519"))
                    return "SFTP SSH 호스트 공개키(Ed25519) 호환성 오류입니다. " +
                            "FileHub 업데이트를 확인하세요.";
                if (detail.contains("host key") || detail.contains("verify"))
                    return "SFTP 서버의 호스트 키를 확인하지 못했습니다. " +
                            "새 서버라면 지문 확인창이 표시되는지 확인하세요.";
                return "SFTP SSH 연결 수립 중 오류가 발생했습니다. " +
                        "FileZilla의 SFTP 연결 설정과 주소·포트·인증 방식을 비교하세요. " +
                        "(TransportException)";
            }
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
                new AlertDialog.Builder(this).setTitle(L10n.t("SFTP 서버 신뢰 확인"))
                        .setMessage(L10n.t("처음 접속한 서버입니다. 관리자에게 아래 키 지문을 확인하세요.\n\n") +
                                fp + L10n.t("\n\n확인 후 이 키를 저장하고 연결할까요?"))
                        .setNegativeButton(L10n.t("취소"), null)
                        .setPositiveButton(L10n.t("확인하고 연결"), (dialog, which) -> {
                            remote.fingerprint = fp;
                            try {
                                store.putRemote(remote);
                                if (remote.id.equals(sftpHomeDiscoveryPendingId))
                                    openRemoteStart(remote);
                                else openRemote(remote, remoteFolder);
                            } catch (Exception ex) { showError(safeError(ex)); }
                        }).show();
                return;
            }
        }
        showError(safeError(error));
    }
    void showError(String message) {
        if(useFlutterFileChrome()&&flutterFilePrompt==null&&!destroyingActivity&&
                (transferDialog==null||!transferDialog.isShowing())){
            showFilePrompt("FileHub AIO",message,null,L10n.t("확인"),null);
            return;
        }
        new AlertDialog.Builder(this).setTitle("FileHub AIO")
                .setMessage(message).setPositiveButton(L10n.t("확인"), null).show();
    }
    interface Job<T> { T execute() throws Exception; }
    <T> void runJob(String label, Job<T> operation, Consumer<T> onSuccess) {
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

    /** Folder navigation already has a loading row in its destination.
     * Do not create/dismiss another window for ordinary directory reads. */
    private <T> void runFolderJob(int generation,String label,Job<T> operation,Consumer<T> onSuccess){
        worker.execute(()->{
            T value=null;Throwable error=null;
            try{value=operation.execute();}catch(Throwable failure){error=failure;}
            final T result=value;final Throwable problem=error;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||generation!=renderGeneration)return;
                if(problem instanceof NasShareApi.OtpRequired){
                    showNasOtpPrompt((NasShareApi.OtpRequired)problem,label,operation,
                            data->{if(generation==renderGeneration)onSuccess.accept(data);});
                }else if(problem!=null){
                    finishScreenHandoff();failure(problem);
                }else onSuccess.accept(result);
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
        otp.setHint(L10n.t("6자리 인증코드"));
        otp.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        otp.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(6)});
        otp.setAutofillHints((String[]) null);
        panel.addView(otp, Ui.lp(this, -1, 52));
        android.widget.CheckBox trust = new android.widget.CheckBox(this);
        trust.setText(L10n.t("이 기기를 신뢰하여 다음 로그인부터 코드 생략"));
        trust.setChecked(false);
        panel.addView(trust, Ui.lp(this, -1, -2));
        TextView safety = Ui.text(this,
                L10n.t("코드는 저장하지 않습니다. '이 기기 신뢰'를 선택하면 DSM에서 발급한 기기 식별자만 암호화 저장합니다."),
                12, Ui.MUTED, false);
        panel.addView(safety, Ui.lp(this, -1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t("DSM 2단계 인증"))
                .setView(panel)
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("인증하고 계속"), null)
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

    private void ensureBodyLayer(){
        if(flutterBodyLayer!=null)return;
        flutterBodyLayer=new FrameLayout(this){
            @Override public boolean dispatchTouchEvent(android.view.MotionEvent event){
                if(bodyWaiting&&bodyDisplayed)return true;
                if(!bodyDisplayed||flutterBodySurface==null||!flutterBodySurface.isShown())return false;
                return super.dispatchTouchEvent(event);
            }
        };
        flutterBodyLayer.setAlpha(0f);
        flutterBodyLayer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        container.addView(flutterBodyLayer,new FrameLayout.LayoutParams(1,1));
        container.getViewTreeObserver().addOnPreDrawListener(()->{
            if(flutterBodyLayer==null||currentScroll==null||
                    !currentScroll.isAttachedToWindow()||currentScroll.getWidth()<=0||
                    currentScroll.getHeight()<=0)return true;
            int[] a=new int[2],b=new int[2];
            currentScroll.getLocationInWindow(a);container.getLocationInWindow(b);
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)flutterBodyLayer.getLayoutParams();
            int left=a[0]-b[0],top=a[1]-b[1],w=currentScroll.getWidth(),h=currentScroll.getHeight();
            if(lp.leftMargin==left&&lp.topMargin==top&&lp.width==w&&lp.height==h)return true;
            lp.gravity=Gravity.TOP|Gravity.LEFT;lp.leftMargin=left;lp.topMargin=top;lp.width=w;lp.height=h;
            flutterBodyLayer.setLayoutParams(lp);
            return false;
        });
    }
    private void presentBodyLayer(){
        if(flutterBodyLayer==null)return;
        // Keep the texture attached even when native-only content is shown.
        // Near-zero opacity permits buffer acquisition during startup.
        flutterBodyLayer.setAlpha(bodyDisplayed||bodyWaiting?1f:0f);
        if(!bodyDisplayed&&bodyWaiting&&flutterBodySurface!=null)
            flutterBodySurface.dimUntilReady();
        flutterBodyLayer.setImportantForAccessibility(
                bodyDisplayed&&!bodyWaiting?View.IMPORTANT_FOR_ACCESSIBILITY_AUTO:
                        View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        flutterBodyLayer.bringToFront();
        if(miniPlayerOverlay!=null)miniPlayerOverlay.bringToFront();
        if(transferChip!=null)transferChip.bringToFront();
        if(drawerLayer!=null)drawerLayer.bringToFront();
    }
    private void beginScreenHandoff(){
        clearStartupPlaceholder();
        closeEmbeddedEditor(true);
        if(assignedShelfTask!=null){assignedShelfTask.cancel(true);assignedShelfTask=null;}
        cancelFolderCountJobs();
        screenHandoffHandler.removeCallbacks(screenHandoffTimeout);
        if(explorerSession!=null)explorerSession.detached=true;
        explorerSession=null;
        bodyDisplayed=activityResumed&&FlutterUiMode.enabled(this)&&
                flutterBodySurface!=null&&flutterBodySurface.isShown();
        bodyWaiting=bodyDisplayed;
        if(flutterBodySurface!=null)flutterBodySurface.unbind();
        if(flutterBodySurface!=null&&!FlutterUiMode.enabled(this)){
            flutterBodySurface.close();flutterBodySurface=null;
        }
        flutterHomeSurface=null;flutterExplorerSurface=null;
        if(flutterDrawerSurface!=null){flutterDrawerSurface.close();flutterDrawerSurface=null;}
        if(bodyWaiting)screenHandoffHandler.postDelayed(screenHandoffTimeout,1500);
    }
    private void finishScreenHandoff(){
        closeEmbeddedEditor(true);
        screenHandoffHandler.removeCallbacks(screenHandoffTimeout);
        bodyWaiting=false;bodyDisplayed=false;
        if(flutterBodySurface!=null)flutterBodySurface.unbind();
        presentBodyLayer();
    }
    private void screenHandoffReady(int generation){
        if(generation!=renderGeneration)return;
        clearStartupPlaceholder();
        screenHandoffHandler.removeCallbacks(screenHandoffTimeout);
        bodyWaiting=false;
        bodyDisplayed=flutterBodySurface!=null&&flutterBodySurface.isShown();
        presentBodyLayer();
    }
    private FlutterNavigationSurface bindBody(String route,FlutterNavigationSurface.Host host){
        if(!FlutterUiMode.enabled(this)||currentScroll==null)return null;
        ensureBodyLayer();
        bodyWaiting=true;
        if(flutterBodySurface==null||flutterBodySurface.isClosed()){
            bodyDisplayed=false;
            flutterBodySurface=new FlutterNavigationSurface(this,flutterBodyLayer,currentScroll,
                    route,host,activityResumed,true);
        }else flutterBodySurface.rebind(currentScroll,route,host,activityResumed);
        presentBodyLayer();
        return flutterBodySurface;
    }
    private void render(String title, boolean back, String right, Runnable onRight) {
        beginScreenHandoff();
        if(settingsContent!=null){settingsContent.deactivate();settingsContent=null;}
        explorerCrumbs=Collections.emptyList();
        explorerCrumbAction=null;
        explorerQuota="";
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
        for(int i=container.getChildCount()-1;i>=0;i--){
            if(container.getChildAt(i)!=flutterBodyLayer && container.getChildAt(i)!=miniPlayerOverlay)container.removeViewAt(i);
        }
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
        hamburger.setContentDescription(L10n.t("왼쪽 탐색 메뉴"));
        hamburger.setOnClickListener(v -> {if(drawerShown)closeDrawer();else showDrawer();});
        if (!"home".equals(currentScreen)) {
            ToolbarIconView home = new ToolbarIconView(this, ToolbarIconView.HOME);
            home.setContentDescription(L10n.t("홈으로 이동"));
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
            wordmark.setContentDescription(L10n.t("FileHub AIO 홈"));
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
                toolbarFavorite.setContentDescription(L10n.t("현재 폴더 즐겨찾기 추가"));
                top.addView(toolbarFavorite, Ui.lp(this, 40, -1));
                toolbarFavorite.setOnClickListener(v -> toggleCurrentFolderFavorite());
                updateFavoriteIndicators();
            }
            ToolbarIconView refresh =
                    new ToolbarIconView(this, ToolbarIconView.REFRESH);
            refresh.setContentDescription(L10n.t("현재 폴더 새로고침"));
            top.addView(refresh, Ui.lp(this, 40, -1));
            refresh.setOnClickListener(v -> refreshCurrentFolderFromToolbar());
            ToolbarIconView search =
                    new ToolbarIconView(this, ToolbarIconView.SEARCH);
            search.setContentDescription(L10n.t("현재 폴더에서 검색"));
            top.addView(search, Ui.lp(this, 40, -1));
            search.setOnClickListener(v -> searchCurrentFolder());
            ToolbarIconView views = new ToolbarIconView(this, ToolbarIconView.VIEW);
            views.setContentDescription(L10n.t("보기 방식 및 정렬 설정"));
            top.addView(views, Ui.lp(this, 40, -1));
            views.setOnClickListener(v -> showViewSettings());
            ToolbarIconView actions = new ToolbarIconView(this, ToolbarIconView.MORE);
            actions.setContentDescription(L10n.t("현재 폴더 작업"));
            top.addView(actions, Ui.lp(this, 40, -1));
            actions.setOnClickListener(v -> fileActionsForCurrent());
        } else if ("home".equals(currentScreen)) {
            if(!EditionMonetization.full(this)){
                ToolbarIconView crown=new ToolbarIconView(this,ToolbarIconView.CROWN);
                crown.setTint(0xFFFFD27A);
                crown.setContentDescription(L10n.t("풀버전으로 업그레이드"));
                top.addView(crown,Ui.lp(this,40,-1));
                crown.setOnClickListener(v->EditionMonetization.showUpgrade(this));
            }
            ToolbarIconView more=new ToolbarIconView(this,ToolbarIconView.MORE);
            top.addView(more,Ui.lp(this,40,-1));
            more.setContentDescription(L10n.t("메뉴"));
            more.setOnClickListener(v->menu(more));
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
        page.setPadding(dp(16), dp(18), dp(16), dp(97));
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
        // The home, explorer and designated-folder routes bind into the
        // same viewport; a native-only destination never waits on Dart.
        if(!fileScreen&&!"home".equals(currentScreen)&&!"assigned".equals(currentScreen)&&
                !"remotes".equals(currentScreen)&&!"favorites".equals(currentScreen)&&!"settings".equals(currentScreen))
            finishScreenHandoff();
        presentBodyLayer();
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
            TextView more=Ui.action(this,L10n.t("더 보기  (")+end+" / "+entries.size()+")");
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
        if(offset==0)attachFlutterExplorer(entries,generation);
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
        }catch(Exception e){showError(L10n.t("음악 재생 조작에 실패했습니다."));}
    }

    private void createMiniPlayerBar(){
        if(miniPlayerOverlay!=null && miniPlayerOverlay.getParent()==container){
            miniPlayerBar.setBackgroundColor(Ui.darkMode?0xFF0D2527:0xFF153E40);
            miniPlayerOverlay.bringToFront();
            refreshMiniPlayer();
            return;
        }
        miniPlayerShown=false;
        miniPlayerOverlay=new FrameLayout(this);
        miniPlayerOverlay.setClipChildren(true);
        miniPlayerOverlay.setVisibility(View.INVISIBLE);
        miniPlayerBar=Ui.vertical(this);
        miniPlayerBar.setBackgroundColor(Ui.darkMode?0xFF0D2527:0xFF153E40);
        miniPlayerBar.setTranslationY(dp(61));
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
        miniToggle.setContentDescription(L10n.t("음악 일시정지 또는 재생"));
        controls.addView(miniToggle,Ui.lp(this,42,-1));
        miniToggle.setOnClickListener(v->musicAction(MusicPlaybackService.ACTION_TOGGLE));
        TextView stop=Ui.text(this,"×",25,Color.WHITE,false);
        stop.setGravity(Gravity.CENTER);
        stop.setContentDescription(L10n.t("음악 재생 종료"));
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
        miniPlayerOverlay.addView(miniPlayerBar,new FrameLayout.LayoutParams(-1,-1));
        FrameLayout.LayoutParams musicBounds=new FrameLayout.LayoutParams(-1,dp(61),Gravity.BOTTOM);
        musicBounds.bottomMargin=navigationBarInsetPx;
        container.addView(miniPlayerOverlay,musicBounds);
        refreshMiniPlayer();
    }
    private void refreshMiniPlayer(){
        if(miniPlayerOverlay==null||miniPlayerOverlay.getParent()!=container)return;
        boolean visible=MusicPlaybackService.active&&!drawerShown&&selection.isEmpty()&&stagedFiles.isEmpty();
        if(visible!=miniPlayerShown){
            miniPlayerShown=visible;
            final View bar=miniPlayerBar;
            bar.animate().cancel();
            if(visible){
                miniPlayerOverlay.setVisibility(View.VISIBLE);
                bar.animate().translationY(0).setDuration(220).start();
            }else{
                bar.animate().translationY(dp(61)).setDuration(180).withEndAction(()->{
                    if(bar==miniPlayerBar&&!miniPlayerShown)miniPlayerOverlay.setVisibility(View.INVISIBLE);
                }).start();
            }
        }
        if(!visible)return;
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
            cancel.setContentDescription(L10n.t("파일 선택 취소"));
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
        syncFileOperationChrome();
        refreshMiniPlayer();
        if(flutterExplorerSurface!=null)flutterExplorerSurface.refresh();
    }

    private boolean useFlutterFileChrome(){
        return !tvDevice&&!destroyingActivity&&flutterExplorerSurface!=null&&flutterExplorerSurface.isShown();
    }
    private void syncFileOperationChrome(){
        int visibility=useFlutterFileChrome()?View.GONE:View.VISIBLE;
        if(selectionToolbar!=null)selectionToolbar.setVisibility(visibility);
        if(operationBottomBar!=null)operationBottomBar.setVisibility(visibility);
    }
    private void showFilePrompt(String title,String message,String initial,String positive,Consumer<String> confirm){
        final int generation=renderGeneration;
        FlutterFilePrompt prompt=new FlutterFilePrompt(this,title,message,initial,positive,confirm,
                ()->!destroyingActivity&&!isFinishing()&&!isDestroyed()&&renderGeneration==generation,
                closed->{if(flutterFilePrompt==closed)flutterFilePrompt=null;});
        if(flutterFilePrompt!=null)flutterFilePrompt.close();
        flutterFilePrompt=prompt;
        prompt.show(activityResumed);
    }

    private boolean allSelectedWritable(){
        for(FileTransferEngine.Item item:selection.snapshot())if(!item.writable())return false;
        return true;
    }
    private boolean pasteDestinationWritable(){
        if("local".equals(currentScreen))return localFolder!=null;
        if("saf".equals(currentScreen))return safFolder!=null;
        return "remote".equals(currentScreen)&&remote!=null&&!remote.readOnly&&!CloudProvider.isReadOnly(remote);
    }
    private boolean hasPasteDestination(){
        return ("local".equals(currentScreen)&&localFolder!=null)||
                ("saf".equals(currentScreen)&&safFolder!=null)||
                ("remote".equals(currentScreen)&&remote!=null);
    }
    private boolean permitFileOperation(String action){
        return FileOperationUiPolicy.permits(action,selection.size(),visibleFileItems.size(),stagedFiles.size(),
                allSelectedWritable(),hasPasteDestination(),pasteDestinationWritable(),FileTransferService.snapshot().active);
    }
    private Map<String,Object> fileOperationSnapshot(){
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("selected",selection.size());data.put("selectable",visibleFileItems.size());
        data.put("staged",stagedFiles.size());data.put("moving",stagedMove);
        List<String> allowed=new ArrayList<>();
        boolean writable=allSelectedWritable(),destination=hasPasteDestination(),
                destWritable=pasteDestinationWritable(),active=FileTransferService.snapshot().active;
        for(String action:FileOperationUiPolicy.ACTIONS)
            if(FileOperationUiPolicy.permits(action,selection.size(),visibleFileItems.size(),stagedFiles.size(),
                    writable,destination,destWritable,active))allowed.add(action);
        data.put("allowed",allowed);data.put("destination",hasPasteDestination());
        return data;
    }
    private void handleFileOperation(String action){
        if(!permitFileOperation(action))return;
        switch(action){
            case "selectAll":selection.selectAll(visibleFileItems);updateFileOperationChrome();break;
            case "clearSelection":selection.clear();updateFileOperationChrome();break;
            case "copySelected":stageSelected(false);break;
            case "moveSelected":stageSelected(true);break;
            case "renameSelected":renameSelected();break;
            case "deleteSelected":deleteSelected();break;
            case "selectedMore":showSelectedMore();break;
            case "pasteClipboard":pasteStagedFiles();break;
            case "clearClipboard":stagedFiles=Collections.emptyList();stagedMove=false;updateFileOperationChrome();break;
            default:break;
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
            showError(L10n.t("파일 전송 중에는 새 복사·이동을 시작할 수 없습니다."));
            return;
        }
        if (selection.isEmpty()) return;
        List<FileTransferEngine.Item> items = selection.snapshot();
        if (moving) for (FileTransferEngine.Item item : items)
            if (!item.writable()) {
                showError(L10n.t("읽기 전용 저장소의 파일은 이동할 수 없습니다. 복사를 사용하세요."));
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
        if(stagedFiles.isEmpty())return;
        if (FileTransferService.snapshot().active) {
            showError(L10n.t("기존 파일 전송이 완료된 뒤 붙여넣기를 사용할 수 있습니다."));
            return;
        }
        FileTransferEngine.Destination dest = currentPasteDestination();
        if (dest == null) {
            showError(L10n.t("붙여넣을 파일 탐색기 폴더로 이동하세요."));
            return;
        }
        List<FileTransferEngine.Item> sources = new ArrayList<>(stagedFiles);
        boolean moving = stagedMove;
        runJob(L10n.t("대상 폴더에서 같은 이름 확인 중..."),
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
                        names.append("\n").append(L10n.f("외 %1$d개", conflicts.size() - 4));
                    String title=L10n.f("같은 이름의 파일이 %1$d개 있습니다",conflicts.size());
                    String message=names+"\n\n"+L10n.t("기존 파일을 덮어쓸까요? 폴더가 포함된 경우 하위 동일 이름의 파일도 교체됩니다. 원본 파일은 덮어쓰기 전에 백업해 두는 것이 안전합니다.");
                    if(useFlutterFileChrome()){
                        showFilePrompt(title,message,null,L10n.t("덮어쓰기"),
                                ignored->commitStagedPaste(sources,dest,moving,true));
                        return;
                    }
                    new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                            .setNegativeButton(L10n.t("취소"), null)
                            .setPositiveButton(L10n.t("덮어쓰기"), (dialog, which) ->
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
        if(sources.isEmpty()||!validateOperationProfiles(sources,dest,moving))return;
        if (FileTransferService.snapshot().active) {
            showError(L10n.t("이미 파일 전송 중입니다. 진행률 아이콘에서 확인하세요."));
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
                showError(L10n.t("이미 파일 전송 중입니다."));
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
            showError(L10n.t("백그라운드 전송 서비스를 시작하지 못했습니다: ") + safeError(error));
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
        transferName = Ui.text(this, L10n.t("전송 준비 중..."), 13, Ui.MUTED, false);
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
        transferAmount = Ui.text(this, L10n.t("0 B / 용량 확인 중"), 13, Ui.TEXT, false);
        transferAmount.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(transferAmount, new LinearLayout.LayoutParams(0, dp(30), 1f));
        box.addView(row, Ui.lp(this, -1, 30));
        Ui.gap(box, this, 7);
        transferFiles = Ui.text(this, L10n.t("0개 완료"), 12, Ui.MUTED, false);
        box.addView(transferFiles, Ui.lp(this, -1, 24));

        FrameLayout frame=new FrameLayout(this);
        frame.setMinimumHeight(dp(310));
        frame.addView(box,new FrameLayout.LayoutParams(-1,dp(310)));
        AlertDialog dialog = new AlertDialog.Builder(this).setView(frame)
                .setCancelable(false)
                .setNegativeButton(L10n.t("숨기기"), null)
                .setPositiveButton(L10n.t("취소"), null)
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
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(L10n.t("취소 중..."));
                updateTransferUi();
            });
        });
        dialog.setOnDismissListener(ignored -> {
            if(flutterTransferSurface!=null){flutterTransferSurface.close();flutterTransferSurface=null;}
            lastFlutterTransfer=Collections.emptyMap();
            if (transferDialog == dialog) transferDialog = null;
            if(!destroyingActivity)updateTransferUi();
        });
        dialog.show();
        if(FlutterUiMode.enabled(this)&&!nativeTransferOnly)attachFlutterTransfer(frame,box,dialog);
        updateTransferUi();
    }

    private void attachFlutterTransfer(FrameLayout frame,View fallback,AlertDialog dialog){
        lastFlutterTransfer=Collections.emptyMap();
        flutterTransferSurface=new FlutterNavigationSurface(this,frame,fallback,
                FlutterUiContract.TRANSFER,new FlutterNavigationSurface.Host(){
            private boolean live(){return !destroyingActivity&&transferDialog==dialog&&dialog.isShowing();}
            @Override public Map<String,Object> snapshot(String route){
                if(!live())throw new IllegalStateException("closed transfer dialog");
                Map<String,Object> data=FlutterOperationSnapshot.base(MainActivity.this,route,FlutterOperationSnapshot.labels());
                data.put("transfer",FlutterOperationSnapshot.transfer());
                return data;
            }
            @Override public void action(String route,String action,String id){
                if(!live())return;
                if("transferHide".equals(action)){
                    transferHidden=true;dialog.dismiss();updateTransferUi();
                }else if("transferCancel".equals(action)){
                    FileTransferService.State s=FileTransferService.snapshot();
                    if(s.active&&!s.cancelling)FileTransferService.requestCancel(MainActivity.this);
                    updateTransferUi();
                }else if("transferClassic".equals(action)){
                    nativeTransferOnly=true;
                    if(flutterTransferSurface!=null){flutterTransferSurface.close();flutterTransferSurface=null;}
                }
            }
            @Override public void reorderFavorites(Object order){throw new IllegalStateException("transfer");}
            private void buttons(boolean visible){
                if(!live())return;
                for(int id:new int[]{AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_POSITIVE}){
                    Button button=dialog.getButton(id);
                    if(button!=null)button.setVisibility(visible?View.VISIBLE:View.GONE);
                }
            }
            @Override public void flutterShown(){
                buttons(false);
                lastFlutterTransfer=Collections.emptyMap();
                updateTransferUi();
            }
            @Override public void fallbackShown(){buttons(true);}
        },activityResumed);
    }

    private void attachTransferChip() {
        if (container == null || transferChip != null) return;
        TextView chip = Ui.text(this, "0%", 13, Color.WHITE, true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(Ui.bg(this, Ui.TEAL, 15, 0));
        chip.setElevation(dp(10));
        chip.setContentDescription(L10n.t("파일 전송 진행률, 눌러서 전송창 다시 열기"));
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
        if(destroyingActivity)return;
        FileTransferService.State s = FileTransferService.snapshot();
        if(lastOperationTransferActive!=s.active){
            lastOperationTransferActive=s.active;
            updateFileOperationChrome();
        }
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
                if(s.cancelling){
                    Button cancel=transferDialog.getButton(AlertDialog.BUTTON_POSITIVE);
                    if(cancel!=null){cancel.setEnabled(false);cancel.setText(L10n.t("취소 중..."));}
                }
                if(flutterTransferSurface!=null&&flutterTransferSurface.isShown()){
                    Map<String,Object> next=FlutterOperationSnapshot.transfer();
                    if(!next.equals(lastFlutterTransfer)){
                        lastFlutterTransfer=next;
                        flutterTransferSurface.push("transferUpdate",next);
                    }
                }
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
            if (s.cancelled) toast(L10n.t("파일 전송이 취소되었습니다."));
            else if (s.failed) showError(L10n.t("파일 전송 실패: ") + s.error);
            else if (s.finished) toast(L10n.t("파일 전송이 완료되었습니다."));
            if (!isFinishing() && !isDestroyed()) refreshCurrent();
        }
    }

    private void renameSelected() {
        if (selection.size() != 1) {
            toast(L10n.t("이름 변경은 파일 또는 폴더 하나만 선택하세요."));
            return;
        }
        FileTransferEngine.Item item = selection.snapshot().get(0);
        if(!item.writable()){
            showError(L10n.t("읽기 전용 저장소입니다."));return;
        }
        if(!validateOperationProfiles(Collections.singletonList(item),null,true))return;
        selection.clear();
        updateFileOperationChrome();
        if ("local".equals(item.kind)) renameLocal(item.local, null, item.name);
        else if ("saf".equals(item.kind)) renameLocal(null, item.saf, item.name);
        else if ("remote".equals(item.kind)) {
            if (isReadOnlyCloud(item.remote)) showError(L10n.t("읽기 전용 저장소입니다."));
            else renameRemote(item.remote,
                    new Models.Entry(item.name, item.path, item.directory));
        } else toast(L10n.t("미디어 화면의 이름 변경은 저장소 폴더에서 진행하세요."));
    }

    private void deleteSelected() {
        List<FileTransferEngine.Item> sources = selection.snapshot();
        if (sources.isEmpty()) return;
        for (FileTransferEngine.Item item : sources) if (!item.writable()) {
            showError(L10n.t("읽기 전용 파일은 삭제할 수 없습니다."));
            return;
        }
        String title=L10n.f("%1$d개 항목 삭제",sources.size());
        boolean localTrash=true;
        for(FileTransferEngine.Item item:sources)
            if(!"local".equals(item.kind)&&!"saf".equals(item.kind)){localTrash=false;break;}
        if(localTrash){
            LinearLayout content=Ui.vertical(this);content.setPadding(dp(24),dp(8),dp(24),dp(8));
            TextView message=Ui.text(this,"",15,Ui.TEXT,false);content.addView(message,Ui.lp(this,-1,-2));
            CheckBox permanent=new CheckBox(this);permanent.setText(L10n.t("완전히 삭제하기"));
            content.addView(permanent,Ui.lp(this,-1,-2));
            String normal=L10n.t("선택한 파일과 폴더를 휴지통으로 이동할까요?");
            int days=LocalTrash.retention(this);
            if(days>0)normal+=" "+L10n.f("%d일 후 자동 삭제",days);
            final String normalMessage=normal;
            message.setText(normalMessage);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(content)
                .setNegativeButton(L10n.t("취소"),null).setPositiveButton(L10n.t("삭제"),(d,w)->{
                    if(permanent.isChecked())commitSelectedDelete(sources);
                    else commitTrash(sources);
                }).create();
            permanent.setOnCheckedChangeListener((v,checked)->{
                message.setText(checked?L10n.t("완전히 삭제하면 복원할 수 없습니다."):normalMessage);
                if(dialog.isShowing())dialog.getButton(AlertDialog.BUTTON_POSITIVE).setText(L10n.t(checked?"완전히 삭제":"삭제"));
            });
            dialog.show();return;
        }
        boolean allOneDrive=true;
        for(FileTransferEngine.Item item:sources)
            if(item.remote==null||!OneDriveAuth.PROTOCOL.equals(item.remote.protocol)){allOneDrive=false;break;}
        String message=allOneDrive?
                L10n.t("선택한 파일과 폴더를 OneDrive 휴지통으로 이동할까요? 폴더 안의 파일도 함께 이동됩니다."):
                L10n.t("선택한 파일과 폴더를 삭제하시겠습니까? 폴더 안의 파일도 삭제되며 ") +
                        L10n.t("SD·일반 NAS는 휴지통 없이 삭제될 수 있습니다. ") +
                        L10n.t("네이버 MYBOX 파일은 MYBOX 휴지통으로 이동합니다.");
        if(useFlutterFileChrome()){
            showFilePrompt(title,message,null,L10n.t("삭제"),ignored->commitSelectedDelete(sources));
            return;
        }
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("삭제"), (dialog, which) ->commitSelectedDelete(sources)).show();
    }
    private static final int TRASH_REQUEST=7397;
    private void openTrash(){startActivityForResult(new Intent(this,TrashActivity.class),TRASH_REQUEST);}
    private void commitTrash(List<FileTransferEngine.Item> sources){
        if(sources.isEmpty())return;
        runJob(L10n.t("휴지통으로 이동 중..."),()->LocalTrash.move(getApplicationContext(),sources),out->{
            folderCache.clearCounts();refreshCurrent();
            if(out.count>0)toast(L10n.f("%d개 항목을 휴지통으로 이동했습니다.",out.count));
            if(out.failed>0)showError(L10n.f("%d개 항목을 처리하지 못했습니다.",out.failed)+"\n"+out.first);
        });
    }
    private void commitSelectedDelete(List<FileTransferEngine.Item> sources){
        if(sources.isEmpty()||!validateOperationProfiles(sources,null,true))return;
        runJob(L10n.t("선택 항목 삭제 중..."),
                                () -> FileTransferEngine.delete(this, sources),
                                count -> {
                                    for (FileTransferEngine.Item item : sources)
                                        if (item.remote != null)
                                            folderCache.invalidateProfile(item.remote);
                                    folderCache.clearCounts();
                                    toast(count + "개 삭제했습니다.");
                                    refreshCurrent();
                                });
    }

    /** Check access to staged sources again at commit, including after a
     * permission prompt or navigation into a different destination. */
    private boolean validateOperationProfiles(List<FileTransferEngine.Item> sources,
                                              FileTransferEngine.Destination dest,boolean removeSources){
        try{
            Map<String,Boolean> checked=new LinkedHashMap<>();
            for(FileTransferEngine.Item item:sources){
                if(removeSources&&!item.writable()){
                    showError(L10n.t("읽기 전용 저장소입니다."));return false;
                }
                if(item.remote!=null)checked.put(item.remote.id,removeSources);
            }
            if(dest!=null&&dest.remote!=null)checked.put(dest.remote.id,true);
            if(checked.isEmpty())return true;
            Map<String,Models.RemoteProfile> active=new LinkedHashMap<>();
            for(Models.RemoteProfile p:store.activeRemotes())active.put(p.id,p);
            for(Map.Entry<String,Boolean> entry:checked.entrySet()){
                Models.RemoteProfile p=active.get(entry.getKey());
                if(p==null){showError(L10n.t("연결 설정이 변경되었습니다. 화면을 닫고 다시 열어 주세요."));return false;}
                if(entry.getValue()&&(p.readOnly||CloudProvider.isReadOnly(p))){
                    showError(L10n.t("읽기 전용 저장소입니다."));return false;
                }
            }
            return true;
        }catch(Exception e){failure(e);return false;}
    }

    private void showSelectedMore() {
        if (selection.isEmpty()) return;
        List<FileTransferEngine.Item> picked = selection.snapshot();
        boolean canShare = picked.size() == 1 &&
                "remote".equals(picked.get(0).kind) &&
                NasSharePolicy.supported(picked.get(0).remote);
        boolean canChmod = picked.size() == 1 &&
                "remote".equals(picked.get(0).kind) &&
                picked.get(0).remote != null &&
                "SFTP".equals(picked.get(0).remote.protocol) &&
                !picked.get(0).symbolicLink &&
                !picked.get(0).remote.readOnly;
        List<String> actions = new ArrayList<>(Arrays.asList(
                "즐겨찾기 추가/해제", "폴더 용도 설정", "선택 파일 압축하기"));
        if (canShare) actions.add("공유 링크 생성");
        if (canChmod) actions.add("리눅스 파일 권한 변경 (chmod)");
        actions.add("선택 해제");
        new AlertDialog.Builder(this)
                .setTitle(selection.size() + "개 선택")
                .setItems(L10n.labels(actions.toArray(new String[0])), (dialog, which) -> {
                    List<FileTransferEngine.Item> items = selection.snapshot();
                    String selectedAction = actions.get(which);
                    if (selectedAction.equals("즐겨찾기 추가/해제")) {
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
                    } else if (selectedAction.equals("폴더 용도 설정")) {
                        if (items.size() != 1 || !items.get(0).directory) {
                            toast(L10n.t("폴더 용도 설정은 폴더 하나만 선택하세요."));
                            return;
                        }
                        showFolderPurposeMenu(items.get(0));
                    } else if (selectedAction.equals("선택 파일 압축하기")) {
                        chooseCompression(items);
                    } else if (selectedAction.equals("공유 링크 생성")) {
                        showNasShareCreate(picked.get(0));
                    } else if (selectedAction.equals("리눅스 파일 권한 변경 (chmod)")) {
                        openSftpChmod(picked.get(0));
                    } else {
                        selection.clear();
                        updateFileOperationChrome();
                    }
                }).show();
    }

    private void openSftpChmod(FileTransferEngine.Item item) {
        if (item == null || item.remote == null ||
                !"SFTP".equals(item.remote.protocol) || item.remote.readOnly) {
            showError(L10n.t("권한 변경은 쓰기 가능한 SFTP 파일 또는 폴더 한 개를 선택하세요."));
            return;
        }
        runJob(L10n.t("현재 리눅스 파일 권한 확인 중..."),
                () -> RemoteProvider.sftpPermissions(item.remote, item.path),
                mode -> showSftpChmodDialog(item, mode));
    }

    private void showSftpChmodDialog(FileTransferEngine.Item item, int initialMode) {
        LinearLayout form = Ui.vertical(this);
        form.setPadding(dp(18), dp(10), dp(18), dp(10));
        TextView filename = Ui.text(this, item.name, 16, Ui.TEXT, true);
        form.addView(filename, Ui.lp(this, -1, -2));
        Ui.gap(form, this, 10);
        TextView current = Ui.text(this,
                L10n.t("현재: ") + LinuxFilePermissions.octal(initialMode) + " (" +
                        LinuxFilePermissions.symbolic(initialMode) + ")",
                13, Ui.MUTED, false);
        form.addView(current, Ui.lp(this, -1, -2));
        EditText octal = field(form, "권한 숫자 (8진수 chmod)",
                LinuxFilePermissions.octal(initialMode),
                android.text.InputType.TYPE_CLASS_NUMBER);
        octal.setFilters(new android.text.InputFilter[]{
                new android.text.InputFilter.LengthFilter(4)});
        TextView preview = Ui.text(this, "", 13, Ui.TEAL, false);
        form.addView(preview, Ui.lp(this, -1, 32));
        final CheckBox[] rights = new CheckBox[9];
        final String[] sections = {"소유자", "그룹", "기타"};
        final String[] verbs = {"읽기", "쓰기", "실행"};
        for (int section = 0; section < 3; section++) {
            LinearLayout group = Ui.horizontal(this);
            group.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading = Ui.text(this, sections[section], 13, Ui.TEXT, true);
            group.addView(heading, Ui.lp(this, 55, 44));
            for (int bit = 0; bit < 3; bit++) {
                int index = section * 3 + bit;
                CheckBox check = new CheckBox(this);
                check.setText(verbs[bit]);
                check.setTextSize(13);
                check.setChecked((initialMode &
                        LinuxFilePermissions.RWX_BITS[index]) != 0);
                rights[index] = check;
                group.addView(check, new LinearLayout.LayoutParams(0, dp(44), 1));
            }
            form.addView(group, Ui.lp(this, -1, 44));
        }
        TextView warning = Ui.text(this,
                L10n.t("쓰기/실행 권한을 제거하면 파일 수정 또는 폴더 접근이 막힐 수 있습니다. ") +
                L10n.t("현재 파일의 권한만 변경하며 하위 항목에는 재귀적으로 적용되지 않습니다."),
                12, Ui.MUTED, false);
        form.addView(warning, Ui.lp(this, -1, -2));
        final boolean[] updating = {false};
        Runnable fromChecks = () -> {
            if (updating[0]) return;
            updating[0] = true;
            try {
                boolean[] checked = new boolean[9];
                for (int i = 0; i < rights.length; i++)
                    checked[i] = rights[i].isChecked();
                // Preserve existing setuid/setgid/sticky bits unless edited
                // explicitly in the four-digit octal field.
                int base;
                try { base = LinuxFilePermissions.parseOctal(
                        octal.getText().toString()); }
                catch (IllegalArgumentException ignore) { base = initialMode; }
                int mode = LinuxFilePermissions.withRwx(base, checked);
                String value = LinuxFilePermissions.octal(mode);
                octal.setText(value);
                octal.setSelection(value.length());
                preview.setText(L10n.t("변경할 권한: ") +
                        LinuxFilePermissions.symbolic(mode));
            } finally { updating[0] = false; }
        };
        for (CheckBox check : rights)
            check.setOnCheckedChangeListener((button, checked) -> fromChecks.run());
        octal.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start,
                    int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start,
                    int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                if (updating[0]) return;
                try {
                    int mode = LinuxFilePermissions.parseOctal(s.toString());
                    updating[0] = true;
                    try {
                        for (int i = 0; i < rights.length; i++)
                            rights[i].setChecked(
                                    (mode & LinuxFilePermissions.RWX_BITS[i]) != 0);
                        preview.setText(L10n.t("변경할 권한: ") +
                                LinuxFilePermissions.symbolic(mode));
                    } finally { updating[0] = false; }
                } catch (IllegalArgumentException invalid) {
                    preview.setText(L10n.t("755, 644, 0755처럼 3~4자리 8진수를 입력하세요."));
                }
            }
        });
        preview.setText(L10n.t("변경할 권한: ") +
                LinuxFilePermissions.symbolic(initialMode));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t("리눅스 파일 권한 변경 (chmod)"))
                .setView(scroll)
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("적용"), null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    final int mode;
                    try { mode = LinuxFilePermissions.parseOctal(
                            octal.getText().toString()); }
                    catch (IllegalArgumentException invalid) {
                        octal.setError(invalid.getMessage());
                        return;
                    }
                    if (mode == initialMode) {
                        dialog.dismiss();
                        toast(L10n.t("파일 권한이 변경되지 않았습니다."));
                        return;
                    }
                    Runnable apply = () -> {
                        dialog.dismiss();
                        runJob(L10n.t("SFTP chmod 권한 변경 중..."),
                                () -> RemoteProvider.sftpChmod(item.remote, item.path,
                                        initialMode, mode), verified -> {
                                    selection.clear();
                                    updateFileOperationChrome();
                                    folderCache.invalidateProfile(item.remote);
                                    toast(L10n.t("권한 변경 완료: ") +
                                            LinuxFilePermissions.octal(verified));
                                    refreshCurrent();
                                });
                    };
                    if (item.directory && (mode & 0100) == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle(L10n.t("폴더 접근 권한 주의"))
                                .setMessage(L10n.t("폴더 소유자의 실행(x) 권한을 제거하면 ") +
                                        L10n.t("이 폴더에 다시 들어가지 못할 수 있습니다. 적용할까요?"))
                                .setNegativeButton(L10n.t("취소"), null)
                                .setPositiveButton(L10n.t("적용"), (d, w) -> apply.run())
                                .show();
                    } else apply.run();
                }));
        dialog.show();
    }

    private void chooseCompression(List<FileTransferEngine.Item> sources) {
        FileTransferEngine.Destination destination = currentPasteDestination();
        if (destination == null) {
            showError(L10n.t("압축파일을 저장할 파일관리자 폴더에서 선택해 주세요."));
            return;
        }
        if (destination.remote != null &&
                CloudProvider.isReadOnly(destination.remote)) {
            showError(L10n.t("읽기 전용 저장소에는 압축 파일을 생성할 수 없습니다."));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(L10n.t("선택한 ") + sources.size() + "개 항목 압축하기")
                .setItems(new String[]{L10n.t("ZIP 만들기"), L10n.t("7z 만들기")}, (dialog, formatIndex) -> {
                    String format = formatIndex == 0 ? "zip" : "7z";
                    EditText name = new EditText(this);
                    name.setSingleLine(true);
                    name.setText(sources.size() == 1 ?
                            sources.get(0).name + "." + format : "선택파일." + format);
                    name.selectAll();
                    new AlertDialog.Builder(this)
                            .setTitle(L10n.t("압축파일 이름"))
                            .setView(name)
                            .setMessage(L10n.t("압축파일은 현재 폴더에 만들어집니다. ") +
                                    L10n.t("암호화 및 RAR 생성은 지원하지 않습니다."))
                            .setNegativeButton(L10n.t("취소"), null)
                            .setPositiveButton(L10n.t("압축하기"), (d, w) -> {
                                String outputName = name.getText().toString().trim();
                                if (outputName.isEmpty()) {
                                    showError(L10n.t("압축파일 이름을 입력하세요."));
                                    return;
                                }
                                runJob(L10n.t("파일 압축·저장 중..."), () ->
                                                ArchiveWorkflows.compress(this,
                                                        sources, destination,
                                                        outputName, format),
                                        result -> {
                                            selection.clear();
                                            updateFileOperationChrome();
                                            if (destination.remote != null)
                                                folderCache.invalidateProfile(destination.remote);
                                            folderCache.clearCounts();
                                            toast(L10n.t("압축 파일 생성: ") + result.destinationName);
                                            refreshCurrent();
                                        });
                            }).show();
                }).show();
    }

    private void offerExtractArchive(FileTransferEngine.Item archive) {
        FileTransferEngine.Destination destination = currentPasteDestination();
        if (destination == null) {
            showError(L10n.t("압축 파일이 있는 저장소 폴더에서 압축을 해제하세요."));
            return;
        }
        if (destination.remote != null &&
                CloudProvider.isReadOnly(destination.remote)) {
            showError(L10n.t("읽기 전용 저장소에서 압축해제할 수 없습니다."));
            return;
        }
        String newFolder = ArchiveCodec.baseFolder(archive.name);
        new AlertDialog.Builder(this)
                .setTitle(L10n.t("압축 파일 해제"))
                .setMessage(archive.name + "\n\n현재 폴더에 '" + newFolder +
                        "' 폴더를 만들고 압축을 풉니다.\n" +
                        "같은 이름이 이미 있으면 '" + newFolder +
                        " (1)', '" + newFolder + " (2)'처럼 자동으로 이름을 붙입니다. " +
                        "기존 파일이나 폴더를 덮어쓰지 않습니다. " +
                        "임시 저장 공간과 추출 용량에 제한이 있습니다.")
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("압축 해제"), (d, which) ->
                        runJob(L10n.t("압축 해제·파일 업로드 중..."), () ->
                                ArchiveWorkflows.extract(this, archive, destination),
                                result -> {
                                    if (destination.remote != null)
                                        folderCache.invalidateProfile(destination.remote);
                                    folderCache.clearCounts();
                                    toast(L10n.t("압축 ") + result.entries + "개 항목 해제했습니다.");
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
        flutterRemoteOrigin=false;
        assignedBackBoundary = null;
        currentScreen = "home";
        remote = null;
        localFolder = null;
        safFolder = null;
        mediaKind = null;
        render("FileHub AIO", false, "⋮", this::menu);
        heading(L10n.t("저장소"), 0);
        LinearLayout r1 = Ui.horizontal(this);
        page.addView(r1, Ui.lp(this, -1, expandedLayout() ? 195 : 158));
        boolean sdPresent = hasSd();
        tile(r1, L10n.t("메인 저장소"), "drive", Ui.TEAL,
                capacity(), () -> browseLocal(Environment.getExternalStorageDirectory()));
        if (sdPresent)
            tile(r1, L10n.t("SD 저장소"), "sd", 0xFF6B8CBE,
                    L10n.t("외장 메모리"), this::openSd);
        else
            tile(r1, L10n.t("다운로드"), "download", ICON_BLUE, "",
                    () -> browseLocal(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS)));
        if (sdPresent)
            tile(r1, L10n.t("다운로드"), "download", ICON_BLUE, "",
                    () -> browseLocal(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS)));
        else tile(r1, L10n.t("이미지"), "image", 0xFFA66BC4, "",
                () -> openMedia("image"));
        Ui.gap(page, this, 10);
        LinearLayout r2 = Ui.horizontal(this);
        page.addView(r2, Ui.lp(this, -1, expandedLayout() ? 195 : 158));
        if (sdPresent) tile(r2, L10n.t("이미지"), "image", 0xFFA66BC4, "",
                () -> openMedia("image"));
        tile(r2, L10n.t("오디오"), "audio", 0xFF159E90, "", () -> openMedia("audio"));
        tile(r2, L10n.t("동영상"), "video", 0xFFDD7662, "", () -> openMedia("video"));
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
        heading(L10n.t("추가기능"), 17);
        LinearLayout features=Ui.horizontal(this);
        page.addView(features,Ui.lp(this,-1,expandedLayout()?195:158));
        tile(features,L10n.t("동영상"),"video",0xFFDD7662,"",
                ()->showAssignedFolders(AssignedFolderCatalog.VIDEO));
        tile(features,L10n.t("만화책"),"comic",0xFFA66BC4,"",
                ()->showAssignedFolders(AssignedFolderCatalog.COMIC));
        tile(features,L10n.t("소설책"),"book",0xFF6B99C2,"",
                ()->showAssignedFolders(AssignedFolderCatalog.TEXT));
        Ui.gap(page,this,14);
        heading(L10n.t("원격 저장소"), 15);
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
        text.addView(Ui.text(this, L10n.t("네트워크 폴더"), 16, Ui.TEXT, true));
        try {
            int count = store.activeRemotes().size();
            text.addView(Ui.text(this, count == 0 ? "연결 추가하기" :
                    "저장된 연결 " + count + "개", 12, Ui.MUTED, false));
        } catch (Exception error) {
            text.addView(Ui.text(this, L10n.t("설정을 확인해 주세요"), 12, Ui.MUTED, false));
        }
        TextView arrow = Ui.text(this, "›", 28, Ui.MUTED, false);
        remoteEntry.addView(arrow, Ui.lp(this, 20, 42));
        remoteEntry.setOnClickListener(v -> openFlutterConnections());
        Ui.gap(page,this,14);
        if (FlutterUiMode.enabled(this)) {showStartupPlaceholder();attachFlutterHome();}
    }

    private void openFlutterConnections() {
        showRemoteList();
    }

    private void disposeFlutterNavigation() {
        if(explorerSession!=null)explorerSession.detached=true;
        explorerSession=null;
        flutterHomeSurface=null;flutterExplorerSurface=null;
        if(flutterBodySurface!=null){flutterBodySurface.close();flutterBodySurface=null;}
        if(flutterBodyLayer!=null&&flutterBodyLayer.getParent()==container)
            container.removeView(flutterBodyLayer);
        flutterBodyLayer=null;
        if (flutterDrawerSurface != null) { flutterDrawerSurface.close(); flutterDrawerSurface=null; }
    }

    private FlutterNavigationSurface.Host flutterNavigationHost() {
        final int generation=renderGeneration;
        return new FlutterNavigationSurface.Host() {
            @Override public void flutterShown(){screenHandoffReady(generation);}
            @Override public void fallbackShown(){screenHandoffReady(generation);}
            @Override public Map<String,Object> snapshot(String route) {
                return FlutterHomeSnapshot.create(MainActivity.this,route,hasSd(),
                        drawerSections.expanded(DrawerSectionState.FAVORITES),
                        drawerSections.expanded(DrawerSectionState.NETWORK));
            }
            @Override public void action(String route,String action,String id) {
                if(embeddedRemoteEditor==null)handleFlutterNavigationAction(route,action,id);
            }
            @Override public void explorerCall(io.flutter.plugin.common.MethodCall call,
                    io.flutter.plugin.common.MethodChannel.Result result){
                if(generation!=renderGeneration||!"remotes".equals(currentScreen)){
                    result.error("stale",null,null);return;
                }
                embeddedEditorCall(call,result);
            }
            @Override public void reorderFavorites(Object order) throws Exception {
                if (!drawerShown) throw new IllegalStateException("Drawer closed");
                store.reorderFavorites(FlutterUiContract.resolveFavoriteOrder(store.activeFavorites(),order));
            }
        };
    }

    private void attachFlutterHome() {
        flutterHomeSurface=bindBody(FlutterUiContract.HOME,flutterNavigationHost());
    }

    private FileTransferEngine.Item explorerItem(Models.Entry e) {
        switch(currentScreen) {
            case "local":
                if(e.file!=null)return new FileTransferEngine.Item("local",e.name,e.path,
                        e.directory,false,e.file,null,null,null,"",e.size);
                break;
            case "saf":
                if(e.document!=null)return new FileTransferEngine.Item("saf",e.name,
                        RemoteProvider.child(safRelative,e.name),e.directory,false,
                        null,e.document,null,null,activeTreeUri,e.size>0?e.size:-1);
                break;
            case "remote":
                if(remote!=null)return FileTransferEngine.remote(remote,e);
                break;
            case "media":
                if(e.uri!=null)return FileTransferEngine.media(e.uri,e.name);
                break;
        }
        return null;
    }
    private static final class ExplorerRow {
        final Models.Entry entry;
        final FileTransferEngine.Item item;
        ExplorerRow(Models.Entry entry,FileTransferEngine.Item item){this.entry=entry;this.item=item;}
    }
    private final class ExplorerSession implements FlutterNavigationSurface.Host {
        final int generation,layout,columns;
        final String kind,revision=java.util.UUID.randomUUID().toString();
        final Models.RemoteProfile provider;
        final List<RemoteBreadcrumbs.Step> crumbs;
        final Consumer<String> navigate;
        final ExplorerPageIndex<ExplorerRow> index;
        final boolean hidden,images,videos;
        final Map<String,Integer> indicesByKey=new java.util.HashMap<>();
        int inFlight;
        volatile boolean detached;
        ExplorerSession(List<Models.Entry> source,int generation) {
            this.generation=generation;kind=currentScreen;provider=remote;
            crumbs=new ArrayList<>(explorerCrumbs);navigate=explorerCrumbAction;
            layout=layoutMode;columns=FoldPlayerLayoutPolicy.fileGridColumns(expandedLayout(),currentScreenWidthDp());
            hidden=showHidden;images=showThumbnails;videos=showVideoThumbnails;
            List<ExplorerRow> rows=new ArrayList<>();
            String scope=folderScope();
            Boolean hideSubtitles=null;
            for(Models.Entry entry:source){
                if(!FileViewOptions.visible(entry.name,entry.name.startsWith("."),hidden))continue;
                if(FolderScopeFilter.isSubtitleDocument(entry.name)) {
                    if(hideSubtitles==null)hideSubtitles=PlayerSettings.hideThisFile(MainActivity.this,scope,entry.name);
                    if(hideSubtitles)continue;
                }
                FileTransferEngine.Item item=explorerItem(entry);
                if(item!=null)indicesByKey.put(item.key(),rows.size());
                rows.add(new ExplorerRow(entry,item));
            }
            index=new ExplorerPageIndex<>(revision,rows);
        }
        boolean live(){return !detached&&explorerSession==this&&generation==renderGeneration&&!isFinishing()&&!isDestroyed();}
        boolean allowed(){
            if(!live())return false;
            if(provider==null||EditionMonetization.full(MainActivity.this))return true;
            try{return store.remoteAllowed(provider.id);}catch(Exception ignored){return false;}
        }
        Map<String,Object> pageData(int offset) {
            if(!live())throw new IllegalStateException("stale directory");
            int end=index.end(offset);
            List<Map<String,Object>> list=new ArrayList<>();
            for(int i=offset;i<end;i++){
                ExplorerRow row=index.get(i);Models.Entry e=row.entry;
                Map<String,Object> m=new LinkedHashMap<>();
                m.put("id",index.id(i));m.put("name",e.name);
                m.put("directory",e.directory);m.put("kind",e.directory?"folder":iconFor(e.name));
                m.put("secondary",L10n.t(fileSecondary(e)));m.put("date",shortDate(e.modified));
                m.put("selectable",row.item!=null);
                m.put("reading",readingProgressFor(e));
                m.put("purpose",folderPurposeFlags(e,row.item));
                m.put("subtitles",subtitleBadgeFlags(e));
                boolean thumb=!e.directory&&(VideoThumbnailPolicy.eligible(e.name,false)?videos:
                        images&&"image".equals(iconFor(e.name)));
                Integer remembered=e.directory&&!e.symbolicLink?folderCache.count(countKey(e)):null;
                boolean count=e.directory&&!e.symbolicLink&&!"media".equals(kind);
                String total=serverCountLabel(e,provider);
                if(total!=null)m.put("secondary",total);
                else if(remembered!=null)m.put("secondary",L10n.f("%d개 항목",remembered));
                m.put("preview",thumb||(count&&remembered==null&&total==null)||(!e.directory&&isVideo(e.name)));
                list.add(m);
            }
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("revision",revision);data.put("offset",offset);data.put("next",end);
            data.put("total",index.size());data.put("rows",list);
            return data;
        }
        Map<String,Object> selected(){
            List<Integer> yes=new ArrayList<>(),no=new ArrayList<>();
            for(FileTransferEngine.Item item:selection.snapshot()){
                Integer i=indicesByKey.get(item.key());if(i!=null)yes.add(i);
            }
            boolean inverted=yes.size()>index.size()/2;
            if(inverted){
                Set<Integer> chosen=new java.util.HashSet<>(yes);
                for(int i=0;i<index.size();i++)if(!chosen.contains(i))no.add(i);
            }
            Map<String,Object> state=new LinkedHashMap<>();
            state.put("count",selection.size());state.put("inverted",inverted);
            state.put("indices",inverted?no:yes);
            return state;
        }
        @Override public Map<String,Object> snapshot(String route) {
            if(!live())throw new IllegalStateException("stale directory");
            Map<String,Object> data=new LinkedHashMap<>();
            data.put("route",route);data.put("language",L10n.language());
            data.put("languageIndex",L10n.selectedIndex(MainActivity.this));
            data.put("themeIndex",AppearanceSettings.mode(MainActivity.this));
            data.put("dark",AppearanceSettings.isDark(MainActivity.this));
            data.put("direct",BuildConfig.DIRECT_EDITION);data.put("full",EditionMonetization.full(MainActivity.this));
            data.put("version",BuildConfig.VERSION_NAME);
            data.put("languages",Collections.emptyList());data.put("themes",Collections.emptyList());
            data.put("profiles",Collections.emptyList());data.put("profileError",false);
            Map<String,String> labels=new LinkedHashMap<>();
            labels.put("empty",L10n.t("이 폴더는 비어 있습니다."));
            labels.put("classicExplorer",L10n.t("기존 파일 탐색기"));
            labels.put("more",L10n.t("더 보기"));
            labels.put("refresh",L10n.t("새로고침"));labels.put("error",L10n.t("설정을 확인해 주세요"));
            labels.put("reading",L10n.t("읽음"));labels.put("external",L10n.t("외부"));
            labels.put("embedded",L10n.t("내장"));labels.put("videoFolder",L10n.t("동영상폴더"));
            labels.put("readingFolder",L10n.t("독서용폴더"));
            labels.put("root",L10n.t("저장소 최상위"));
            labels.put("items",L10n.t("항목"));
            labels.put("countUnknown",L10n.t("항목 수 미확인"));
            labels.putAll(FlutterOperationSnapshot.labels());
            data.put("labels",labels);
            Map<String,Object> explorer=pageData(0);
            explorer.put("layout",layout);explorer.put("columns",columns);explorer.put("selection",selected());
            explorer.put("quota",explorerQuota);
            explorer.put("operations",fileOperationSnapshot());
            List<Map<String,Object>> steps=new ArrayList<>();
            for(int i=0;i<crumbs.size();i++){
                Map<String,Object> step=new LinkedHashMap<>();
                step.put("id",revision+":"+i);step.put("label",i==0?labels.get("root"):crumbs.get(i).label);
                steps.add(step);
            }
            explorer.put("crumbs",steps);data.put("explorer",explorer);
            return data;
        }
        @Override public void action(String route,String action,String id){
            if(!allowed()||drawerShown)return;
            if(FileOperationUiPolicy.ACTIONS.contains(action)){
                if(revision.equals(id))handleFileOperation(action);
                return;
            }
            if("classicExplorer".equals(action)){
                nativeExplorerOnly=true;
                if(flutterExplorerSurface!=null){
                    detached=true;flutterExplorerSurface=null;
                    finishScreenHandoff();syncFileOperationChrome();
                    if(currentScroll!=null)currentScroll.post(MainActivity.this::requestVisibleJobs);
                }
                return;
            }
            if("crumb".equals(action)){
                if(navigate==null||id==null||!id.startsWith(revision+":"))return;
                try{
                    int i=Integer.parseInt(id.substring(revision.length()+1));
                    if(i>=0&&i<crumbs.size()-1&&selection.isEmpty())navigate.accept(crumbs.get(i).path);
                }catch(NumberFormatException ignored){}
                return;
            }
            int i=index.resolve(id);if(i<0)return;
            ExplorerRow row=index.get(i);
            if("entrySelect".equals(action)||!selection.isEmpty()){
                if(row.item!=null)toggleFileSelection(row.item);
            }else if("entryOpen".equals(action))openExplorerEntry(row.entry);
        }
        @Override public void reorderFavorites(Object order){throw new IllegalStateException("explorer");}
        @Override public void fallbackShown(){
            detached=true;
            syncFileOperationChrome();
            screenHandoffReady(generation);
            if(explorerSession==this&&generation==renderGeneration&&currentScroll!=null)
                currentScroll.post(MainActivity.this::requestVisibleJobs);
        }
        @Override public void flutterShown(){syncFileOperationChrome();screenHandoffReady(generation);}
        @Override public void explorerCall(io.flutter.plugin.common.MethodCall call,
                                           io.flutter.plugin.common.MethodChannel.Result result){
            if(!live()){result.error("STALE_DIRECTORY",null,null);return;}
            if("explorerPage".equals(call.method)){
                try{
                    if(!(call.arguments instanceof Map)||!revision.equals(call.argument("revision"))||
                            !(call.argument("offset") instanceof Integer))throw new IllegalArgumentException();
                    result.success(pageData((Integer)call.argument("offset")));
                }catch(Exception error){result.error("INVALID_PAGE",null,null);}
                return;
            }
            if(!"explorerPreview".equals(call.method)){result.notImplemented();return;}
            int i=call.arguments instanceof String?index.resolve((String)call.arguments):-1;
            if(i<0){result.error("STALE_DIRECTORY",null,null);return;}
            Models.Entry e=index.get(i).entry;
            String key=countKey(e);
            boolean canCount=e.directory&&!e.symbolicLink&&key!=null&&!"media".equals(kind);
            if(canCount){
                String total=serverCountLabel(e,provider);
                if(total!=null){result.success(Collections.singletonMap("secondary",total));return;}
                Integer remembered=folderCache.count(key);
                if(remembered!=null){
                    result.success(Collections.singletonMap("secondary",countLabel(remembered)));
                    return;
                }
                if(!allowed()){result.error("STALE_DIRECTORY",null,null);return;}
                submitFolderCount(provider!=null,()->explorerCount(e,provider,hidden),count->{
                    if(!live()){result.error("STALE_DIRECTORY",null,null);return;}
                    if(count>=0)folderCache.rememberCount(key,count);
                    result.success(Collections.singletonMap("secondary",countLabel(count)));
                });
                return;
            }
            if(inFlight>=6){result.success(Collections.singletonMap("deferred",true));return;}
            boolean video=VideoThumbnailPolicy.eligible(e.name,false);
            boolean thumbnail=!e.directory&&(video?videos:images&&"image".equals(iconFor(e.name)));
            inFlight++;
            previewWorker.execute(()->{
                Map<String,Object> payload=new LinkedHashMap<>();
                try{
                    if(detached)throw new IllegalStateException("stale directory");
                    if(provider!=null&&!EditionMonetization.full(MainActivity.this)&&
                            !new SecureStore(MainActivity.this).remoteAllowed(provider.id))
                        throw new IllegalStateException("locked");
                    if(thumbnail&&!detached){
                        Bitmap original=loadExplorerThumbnail(e,provider,video);
                        if(original!=null){
                            Bitmap small=original;
                            try{
                                int longest=Math.max(original.getWidth(),original.getHeight());
                                if(longest>192)small=Bitmap.createScaledBitmap(original,
                                        Math.max(1,original.getWidth()*192/longest),
                                        Math.max(1,original.getHeight()*192/longest),true);
                                ByteArrayOutputStream out=new ByteArrayOutputStream();
                                if(small.compress(Bitmap.CompressFormat.JPEG,80,out)&&out.size()<=160*1024)
                                    payload.put("image",out.toByteArray());
                            }finally{if(small!=original)small.recycle();original.recycle();}
                        }
                    }
                    if(!detached&&!e.directory&&isVideo(e.name)){
                        String sid=SubtitleInventory.entryId(e,provider);
                        if(sid!=null&&SubtitleInventory.status(MainActivity.this,sid)!=1){
                            if(provider==null&&SubtitleInventory.canProbeLocally(e)&&localSubtitleProbed.add(sid))
                                SubtitleInventory.record(MainActivity.this,sid,SubtitleInventory.probeLocal(getApplicationContext(),e));
                            else if(provider!=null&&MatroskaSubtitleProbe.supportsName(e.name)&&videoHeadersProbed.add(sid)){
                                try{
                                    if(MatroskaSubtitleProbe.remoteHeaderContainsSubtitles(provider,e))
                                        SubtitleInventory.record(MainActivity.this,sid,true);
                                }catch(Exception error){videoHeadersProbed.remove(sid);}
                            }
                        }
                    }
                }catch(Exception ignored){}
                runOnUiThread(()->{
                    inFlight--;
                    if(!live()){result.error("STALE_DIRECTORY",null,null);return;}
                    payload.put("subtitles",subtitleBadgeFlags(e));
                    result.success(payload);
                });
            });
        }
    }

    private int explorerCount(Models.Entry entry,Models.RemoteProfile provider,boolean hidden)throws Exception{
        int count=0;
        if(entry.file!=null){
            String[] children=entry.file.list();if(children==null)return -1;
            for(String child:children)if(FileViewOptions.visible(child,false,hidden))count++;
        }else if(entry.document!=null){
            // One provider query instead of one Binder metadata call per child.
            Uri uri=entry.document.getUri();
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(uri,DocumentsContract.getDocumentId(uri));
            try(android.database.Cursor cursor=getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
                if(cursor==null)return -1;
                while(cursor.moveToNext())if(FileViewOptions.visible(cursor.getString(0),false,hidden))count++;
            }
        }else if(provider!=null){
            // A count-only request must not hold the foreground listing lock.
            List<Models.Entry> cached=folderCache.peek(provider,entry.path);
            if(cached==null)return RemoteProvider.countChildren(provider,entry.path,hidden);
            for(Models.Entry child:cached)if(FileViewOptions.visible(child.name,false,hidden))count++;
        }else return -1;
        return count;
    }
    private void openExplorerEntry(Models.Entry e){
        switch(currentScreen){
            case "local":
                if(e.directory)browseLocal(e.file);
                else if(isVideo(e.name))openVideo(Uri.fromFile(e.file),e.name);
                else openFile(e.file);break;
            case "saf":
                if(e.directory)browseSaf(e.document,RemoteProvider.child(safRelative,e.name));
                else if(isVideo(e.name))openVideo(e.document.getUri(),e.name);
                else openNamedUri(e.document.getUri(),e.name);break;
            case "remote":
                if(e.symbolicLink&&e.linkTargetUnavailable){
                    showError(L10n.t("심볼릭 링크가 가리키는 대상에 접근할 수 없습니다. ")+
                            L10n.t("끊어진 링크 또는 서버 권한을 확인하세요."));return;
                }
                if(e.directory)openRemote(remote,e.path);
                else if(isVideo(e.name))openRemoteVideo(remote,e);
                else openRemoteFile(remote,e);break;
            case "media":
                if(isVideo(e.name)){visibleEpisodeQueue=mediaFolderEpisodeQueues.get(e.path);openVideo(e.uri,e.name);}
                else if(MediaFileTypes.isAudio(e.name))playMusic(e.uri,e.name);
                else if(MediaFileTypes.isImage(e.name))viewImage("uri",e.name,null,e.uri,null);
                else openUri(e.uri,mime(e.name));break;
        }
    }
    private void attachFlutterExplorer(List<Models.Entry> entries,int generation){
        if(!FlutterUiMode.enabled(this)||nativeExplorerOnly||explorerSession!=null||generation!=renderGeneration||
                currentScroll==null||currentScroll.getParent()!=screenRoot)return;
        ExplorerSession session=new ExplorerSession(entries,generation);
        explorerSession=session;
        visibleFileItems.clear();currentSearch.clear();visibleComicVolumes.clear();
        for(int i=0;i<session.index.size();i++){
            ExplorerRow row=session.index.get(i);Models.Entry e=row.entry;
            if(row.item!=null)visibleFileItems.put(row.item.key(),row.item);
            currentSearch.add(new SearchResult(e.name,()->openExplorerEntry(e)));
            if(!e.directory&&ComicArchive.supports(e.name)&&visibleComicVolumes.size()<BookSeriesQueue.MAX_VOLUMES){
                if("local".equals(session.kind)&&e.file!=null)visibleComicVolumes.add(new BookSeriesQueue.Volume(e.name,e.path,null));
                else if("saf".equals(session.kind)&&e.document!=null)visibleComicVolumes.add(new BookSeriesQueue.Volume(e.name,null,e.path));
                else if("remote".equals(session.kind))visibleComicVolumes.add(new BookSeriesQueue.Volume(e.name,e.path,null));
            }
        }
        completeFolderSearchIndexed=true;
        flutterExplorerSurface=bindBody(FlutterUiContract.EXPLORER,session);
    }

    private void handleFlutterNavigationAction(String route,String action,String id) {
        if (!FlutterUiContract.validNavigationAction(route,action,id) ||
                isFinishing() || isDestroyed()) return;
        if(FlutterUiContract.CONNECTIONS.equals(route)){
            if("remotes".equals(currentScreen)&&!drawerShown)
                handleFlutterConnectionAction(new Intent().putExtra(FlutterUiContract.ACTION,action)
                        .putExtra(FlutterUiContract.PROFILE_ID,id));
            return;
        }
        if(FlutterUiContract.FAVORITES.equals(route)){
            if(!"favorites".equals(currentScreen)||drawerShown)return;
            try{
                for(Models.Favorite favorite:store.activeFavorites())
                    if(FlutterUiContract.favoriteId(favorite).equals(id)){
                        if("favoriteManage".equals(action))showFavoriteActions(favorite);
                        else openFavorite(favorite);
                        return;
                    }
                showFavorites();
            }catch(Exception error){failure(error);}
            return;
        }
        if (FlutterUiContract.HOME.equals(route)) {
            if (!"home".equals(currentScreen) || drawerShown) return;
            switch (action) {
                case "trash": openTrash(); break;
                case "storage": browseLocal(Environment.getExternalStorageDirectory()); break;
                case "sd": openSd(); break;
                case "downloads": browseLocal(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)); break;
                case "images": openMedia("image"); break;
                case "audio": openMedia("audio"); break;
                case "videos": openMedia("video"); break;
                case "videoShelf": showAssignedFolders(AssignedFolderCatalog.VIDEO); break;
                case "comicShelf": showAssignedFolders(AssignedFolderCatalog.COMIC); break;
                case "textShelf": showAssignedFolders(AssignedFolderCatalog.TEXT); break;
                case "connections": openFlutterConnections(); break;
                case "upgrade": EditionMonetization.showUpgrade(this); break;
                default: break;
            }
            return;
        }
        if (!drawerShown) return;
        if ("toggleFavorites".equals(action)) { drawerSections.toggle(DrawerSectionState.FAVORITES); return; }
        if ("toggleNetwork".equals(action)) { drawerSections.toggle(DrawerSectionState.NETWORK); return; }
        if ("nativeDrawer".equals(action)) {
            nativeDrawerOnly=true;
            // Rebuild so native fallback reflects any Flutter reordering/toggles.
            closeDrawer();
            showDrawer();
            return;
        }
        closeDrawer();
        try {
            switch (action) {
                case "home": showHome(); break;
                case "trash": openTrash(); break;
                case "connections": openFlutterConnections(); break;
                case "settings": openSettings(); break;
                case "upgrade": if(!BuildConfig.DIRECT_EDITION)EditionMonetization.showUpgrade(this);break;
                case "favorites": showFavorites(); break;
                case "open":
                    for (Models.RemoteProfile p:store.activeRemotes())
                        if (p.id.equals(id)) {
                            flutterRemoteOrigin=true;
                            openRemoteStart(p);
                            return;
                        }
                    showError(L10n.t("삭제된 원격 연결입니다.")); break;
                case "favorite":
                case "favoriteManage":
                    for (Models.Favorite f:store.activeFavorites())
                        if (FlutterUiContract.favoriteId(f).equals(id)) {
                            if ("favorite".equals(action)) {
                                flutterRemoteOrigin="remote".equals(f.kind);
                                openFavorite(f);
                            }
                            else showFavoriteActions(f);
                            return;
                        }
                    showFavorites(); break;
                default: break;
            }
        } catch (Exception e) { failure(e); }
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
                muted(L10n.t("아직 ")+title+"으로 지정한 폴더가 없습니다.");
                muted(L10n.t("오른쪽 위 + 를 눌러 로컬·SD·네트워크 폴더를 찾아 지정하세요."));
                attachAssignedShelf(Collections.emptyList(),Collections.emptyList(),roots,mode,screen,
                        L10n.t("오른쪽 위 + 를 눌러 로컬·SD·네트워크 폴더를 찾아 지정하세요."));
                return;
            }
            String loadingText=L10n.t("지정한 폴더의 하위 폴더를 찾는 중...");
            TextView loading=muted(loadingText);
            // Paint the new shelf immediately, then publish results to that
            // same Flutter surface; network delay no longer holds Home open.
            final AssignedShelfSession session=attachAssignedShelf(
                    Collections.emptyList(),Collections.emptyList(),roots,mode,screen,loadingText);
            if(session!=null)session.loading=true;
            assignedShelfTask=assignedShelfWorker.submit(()->{
                Map<String,AssignedFolderCatalog.Folder> unique=new LinkedHashMap<>();
                List<Models.RemoteProfile> profiles;
                try{profiles=store.activeRemotes();}
                catch(Exception e){profiles=Collections.emptyList();}
                for(AssignedFolderCatalog.Folder root:roots){
                    if(Thread.currentThread().isInterrupted())return;
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
                if(Thread.currentThread().isInterrupted())return;
                List<AssignedFolderCatalog.Folder> visible=
                        new ArrayList<>(unique.values());
                visible.sort((a,b)->a.name().compareToIgnoreCase(b.name()));
                final List<Models.RemoteProfile> accounts=profiles;
                runOnUiThread(()->{
                    if(screen!=renderGeneration||!"assigned".equals(currentScreen)||
                            !mode.equals(assignedMode)||isFinishing()||isDestroyed())
                        return;
                    assignedShelfTask=null;
                    String empty=visible.isEmpty()?
                            L10n.t("표시할 하위 폴더가 없습니다. + 버튼으로 폴더를 추가하세요."):"";
                    if(session!=null)session.complete(visible,accounts,empty);
                    else attachAssignedShelf(visible,accounts,roots,mode,screen,empty);
                    // Preserve native fallback, without blocking the first
                    // Flutter loading frame while creating native row views.
                    final LinearLayout fallbackPage=page;
                    fallbackPage.post(()->{
                        if(screen!=renderGeneration||page!=fallbackPage||isFinishing()||isDestroyed())return;
                        fallbackPage.removeView(loading);
                        if(visible.isEmpty()){muted(empty);return;}
                        muted(L10n.t("폴더 ")+visible.size()+"개 · 길게 누르면 해당 폴더의 용도 지정 해제");
                        appendAssignedFolderPage(visible,accounts,roots,mode,0,screen);
                    });
                });
            });
        }catch(Exception error){
            finishScreenHandoff();
            showError(L10n.t("지정 폴더를 표시하지 못했습니다: ")+error.getMessage());
        }
    }

    private AssignedShelfSession attachAssignedShelf(List<AssignedFolderCatalog.Folder> folders,
            List<Models.RemoteProfile> accounts,List<AssignedFolderCatalog.Folder> roots,
            String mode,int generation,String empty){
        if(!FlutterUiMode.enabled(this)||generation!=renderGeneration)return null;
        AssignedShelfSession session=new AssignedShelfSession(folders,accounts,roots,mode,generation,empty);
        bindBody(FlutterUiContract.SHELF,session);
        return session;
    }
    private final class AssignedShelfSession implements FlutterNavigationSurface.Host {
        private List<AssignedFolderCatalog.Folder> folders;
        private final List<AssignedFolderCatalog.Folder> roots;
        private List<Models.RemoteProfile> accounts;
        private final String mode;
        private String empty;
        private boolean loading;
        private final int generation;
        private NativePanelModel model;
        AssignedShelfSession(List<AssignedFolderCatalog.Folder> folders,List<Models.RemoteProfile> accounts,
                List<AssignedFolderCatalog.Folder> roots,String mode,int generation,String empty){
            this.folders=new ArrayList<>(folders);this.accounts=new ArrayList<>(accounts);
            this.roots=new ArrayList<>(roots);this.mode=mode;this.generation=generation;this.empty=empty;
            rebuild(0);
        }
        private boolean live(){
            return generation==renderGeneration&&"assigned".equals(currentScreen)&&mode.equals(assignedMode)
                    &&!isFinishing()&&!isDestroyed();
        }
        private void rebuild(int offset){
            model=new NativePanelModel("",new String[0],offset/LargeFolderPagePolicy.ROWS_PER_PAGE,null);
            if(folders.isEmpty()){
                model.note(empty);return;
            }
            int end=LargeFolderPagePolicy.end(folders.size(),offset);
            model.note(folders.size()+" "+L10n.t("항목")+" · "+(offset+1)+"–"+end);
            if(offset>0)model.action(L10n.t("이전"),"",true,
                    ()->changePage(Math.max(0,offset-LargeFolderPagePolicy.ROWS_PER_PAGE)));
            for(int i=offset;i<end;i++){
                AssignedFolderCatalog.Folder f=folders.get(i);
                String origin=L10n.t("메인·로컬 저장소");
                if("remote".equals(f.kind)){
                    origin=L10n.t("네트워크 폴더");
                    for(Models.RemoteProfile account:accounts)
                        if(account.id.equals(f.owner)){origin=account.label();break;}
                }else if("saf".equals(f.kind)||"storage".equals(f.kind))origin=L10n.t("SD·USB 저장소");
                model.folder(f.name(),origin+" · "+f.path,
                        ()->openAssignedFolder(f,AssignedFolderCatalog.configuredRoot(roots,f)),
                        ()->confirmRemoveAssignedRole(mode,f));
            }
            if(end<folders.size())model.action(L10n.t("더 보기"),"",true,()->changePage(end));
        }
        private void complete(List<AssignedFolderCatalog.Folder> updated,
                              List<Models.RemoteProfile> profiles,String message){
            if(!live())return;
            folders=new ArrayList<>(updated);accounts=new ArrayList<>(profiles);
            empty=message;loading=false;
            rebuild(0);
            if(flutterBodySurface!=null)flutterBodySurface.refresh();
        }
        private void changePage(int offset){
            if(!live())return;
            rebuild(offset);
            if(flutterBodySurface!=null)flutterBodySurface.refresh();
        }
        @Override public Map<String,Object> snapshot(String route){
            if(!live())throw new IllegalStateException("stale shelf");
            Map<String,String> labels=FlutterOperationSnapshot.labels();
            labels.put("removeRole",L10n.t("폴더 용도 지정 해제"));
            Map<String,Object> data=FlutterOperationSnapshot.base(MainActivity.this,route,labels);
            Map<String,Object> panel=model.snapshot();
            panel.put("showClassic",false);panel.put("loading",loading);data.put("panel",panel);
            return data;
        }
        @Override public void action(String route,String action,String id){}
        @Override public void reorderFavorites(Object order){throw new IllegalStateException("shelf");}
        @Override public void flutterShown(){screenHandoffReady(generation);}
        @Override public void fallbackShown(){screenHandoffReady(generation);}
        @Override public void explorerCall(io.flutter.plugin.common.MethodCall call,
                                          io.flutter.plugin.common.MethodChannel.Result result){
            if(!live()||drawerShown||!"panelCommand".equals(call.method)||!(call.arguments instanceof Map)){
                result.error("STALE_SHELF",null,null);return;
            }
            NativePanelModel current=model;
            Runnable action=current.command(call.argument("revision"),call.argument("id"),call.argument("value"));
            if(action==null){result.error("INVALID_SHELF_COMMAND",null,null);return;}
            result.success(null);
            runOnUiThread(()->container.post(()->{
                if(live()&&!drawerShown&&model==current&&flutterBodySurface!=null&&flutterBodySurface.isShown())
                    action.run();
            }));
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
            TextView more=Ui.action(this,L10n.t("폴더 더 보기  (")+end+" / "+folders.size()+")");
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
        String label=L10n.t(AssignedFolderCatalog.VIDEO.equals(mode)?"동영상":
                AssignedFolderCatalog.COMIC.equals(mode)?"만화책":"소설책");
        new AlertDialog.Builder(this).setTitle(L10n.t("폴더 용도 지정 해제"))
                .setMessage(folder.name()+"\n\n"+L10n.f("%1$s 폴더 용도 지정을 해제할까요?\n상위 폴더에서 상속된 지정도 이 폴더부터 적용되지 않습니다.\n파일이나 폴더 자체는 삭제되지 않습니다.",label))
                .setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("제외하기"),(dialog,which)->{
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
            pathLabel=Ui.text(MainActivity.this,L10n.t("저장소를 선택하세요"),13,Ui.MUTED,false);
            pathLabel.setPadding(dp(4),dp(5),dp(4),dp(11));
            panel.addView(pathLabel,Ui.lp(MainActivity.this,-1,-2));
            ScrollView scroll=new ScrollView(MainActivity.this);
            entries=Ui.vertical(MainActivity.this);
            scroll.addView(entries);
            panel.addView(scroll,Ui.lp(MainActivity.this,-1,410));
            dialog=new AlertDialog.Builder(MainActivity.this)
                    .setTitle(L10n.t("폴더 추가 · 탐색기"))
                    .setView(panel)
                    .setNegativeButton(L10n.t("취소"),null)
                    .setPositiveButton(L10n.t("현재 폴더 지정"),null)
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
            pathLabel.setText(L10n.t("저장소를 선택한 뒤 원하는 하위 폴더로 이동하세요."));
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
                        L10n.t("네트워크 계정을 읽지 못했습니다."),12,Ui.MUTED,false));
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
                    L10n.t("하위 폴더 목록을 읽는 중..."),13,Ui.MUTED,false);
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
                                L10n.t("폴더를 읽지 못했습니다: ")+issue,12,Ui.MUTED,false));
                        return;
                    }
                    if(children.isEmpty()){
                        entries.addView(Ui.text(MainActivity.this,
                                L10n.t("하위 폴더가 없습니다. 이 폴더를 지정할 수 있습니다."),
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
                        L10n.t("하위 폴더 더 보기  (")+end+" / "+children.size()+")");
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
                toast(L10n.t("폴더 용도를 지정했습니다."));
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
                    showError(L10n.t("지정한 로컬 폴더를 찾을 수 없습니다: ")+folder.path);
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
                    showError(L10n.t("원격 연결 정보가 없거나 삭제되었습니다."));
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
            default:showError(L10n.t("지원하지 않는 폴더입니다."));
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
                showError(L10n.t("SD·USB 폴더 접근 권한이 없습니다. 저장소에서 권한을 다시 설정해 주세요."));
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
        showError(L10n.t("영상 폴더에 접근할 수 없습니다. SD·USB 또는 폴더 접근 권한을 다시 지정해 주세요."));
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
        if(normalFileToolbar!=null&&normalFileToolbar.getChildCount()>0)
            menu(normalFileToolbar.getChildAt(normalFileToolbar.getChildCount()-1));
    }
    private void menu(View anchor) {
        String[] labels=L10n.labels(OverflowMenuItems.labelsForEdition(BuildConfig.DIRECT_EDITION));
        String[] icons={"settings","star","remote","drive","refresh"};
        LinearLayout items=Ui.vertical(this);
        items.setPadding(dp(6),dp(6),dp(6),dp(6));
        items.setBackground(Ui.bg(this,Ui.DARK,8,0));
        android.widget.PopupWindow popup=new android.widget.PopupWindow(items,dp(220),-2,true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));
        popup.setOutsideTouchable(true);
        for(int i=0;i<labels.length;i++){
            final int index=i;
            LinearLayout row=Ui.horizontal(this);
            row.setPadding(dp(12),0,dp(8),0);
            View menuIcon;
            if(index==OverflowMenuItems.UPDATE){
                menuIcon=new ToolbarIconView(this,ToolbarIconView.REFRESH);
                menuIcon.setClickable(false);menuIcon.setFocusable(false);
            }else menuIcon=new Ui.Icon(this,icons[i],Color.WHITE);
            row.addView(menuIcon,Ui.lp(this,24,24));
            TextView label=Ui.text(this,labels[i],14,Color.WHITE,false);
            label.setSingleLine(true);label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.leftMargin=dp(12);
            row.addView(label,lp);items.addView(row,Ui.lp(this,-1,48));
            row.setOnClickListener(v->{popup.dismiss();switch(index){
                case OverflowMenuItems.SETTINGS: openSettings();break;
                case OverflowMenuItems.FAVORITES: showFavorites();break;
                case OverflowMenuItems.REMOTES: openFlutterConnections();break;
                case OverflowMenuItems.STORAGE_PERMISSION: requestAllFiles();break;
                case OverflowMenuItems.UPDATE: if(otaUpdater!=null)otaUpdater.manualCheck();break;
                default:break;
            }});
        }
        items.setAlpha(0f);items.setScaleX(.80f);items.setScaleY(.80f);
        popup.showAsDropDown(anchor,0,-anchor.getHeight(),Gravity.END);
        items.post(()->{
            items.setPivotX(items.getWidth());items.setPivotY(0);
            items.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190)
                .setInterpolator(new DecelerateInterpolator()).start();
        });
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
        refreshMiniPlayer();
        // Use the same attached Flutter viewport; never create a second
        // renderer for the drawer. Native menu remains the fallback.
        if(FlutterUiMode.enabled(this)&&!nativeDrawerOnly&&flutterBodySurface!=null&&
                flutterBodySurface.showDrawerOverlay(flutterNavigationHost()))return;
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
        FrameLayout drawerHost = new FrameLayout(this);
        drawerPanel = drawerHost;
        LinearLayout drawerContent=Ui.vertical(this);
        drawerContent.setBackgroundColor(Ui.SURFACE);
        drawerHost.addView(drawerContent,new FrameLayout.LayoutParams(-1,-1));
        drawerContent.addView(scroller,new LinearLayout.LayoutParams(-1,0,1));
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
        layer.addView(drawerHost, drawerParams);
        ViewCompat.setOnApplyWindowInsetsListener(drawerHost, (view, insets) -> {
            int safeBottom = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars()).bottom;
            FrameLayout.LayoutParams params =
                    (FrameLayout.LayoutParams) drawerHost.getLayoutParams();
            int requestedMargin = FileHubBrandLayout.drawerBottomPx(
                    navigationBarInsetPx,safeBottom);
            if (params.bottomMargin != requestedMargin) {
                params.bottomMargin = requestedMargin;
                drawerHost.setLayoutParams(params);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(drawerHost);
        drawerHost.setTranslationX(-drawerParams.width);
        LinearLayout inside = Ui.vertical(this);
        scroller.addView(inside);
        drawerLink(inside, L10n.t("홈"), "drive", () -> showHome());

        LinearLayout favoriteEntries = drawerSection(inside,
                L10n.t("즐겨찾기"), "star", DrawerSectionState.FAVORITES);
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
                L10n.t("네트워크 폴더"), "remote", DrawerSectionState.NETWORK);
        try {
            for (Models.RemoteProfile p : store.activeRemotes())
                drawerNestedLink(networkEntries, p.label(), cloudIconKind(p.protocol),
                        () -> openRemoteStart(p));
        } catch (Exception ignored) {
            drawerSectionEmpty(networkEntries, "네트워크 연결을 확인할 수 없습니다.");
        }
        // Network connections can still be managed without a redundant
        // top-level '원격 저장소' drawer item.
        drawerNestedLink(networkEntries, "연결 추가 · 관리", "settings",
                this::openFlutterConnections);

        View footerLine=new View(this);footerLine.setBackgroundColor(Ui.BORDER);
        drawerContent.addView(footerLine,Ui.lp(this,-1,1));
        LinearLayout footer=Ui.horizontal(this);
        footer.setPadding(dp(6),dp(3),dp(8),dp(3));
        drawerContent.addView(footer,Ui.lp(this,-1,-2));
        if(!BuildConfig.DIRECT_EDITION&&!EditionMonetization.full(this)){
            LinearLayout premium=Ui.horizontal(this);
            premium.setMinimumHeight(dp(48));
            premium.setPadding(dp(6),0,dp(6),0);
            footer.addView(premium,new LinearLayout.LayoutParams(0,-2,1));
            ToolbarIconView crown=new ToolbarIconView(this,ToolbarIconView.CROWN);
            crown.setTint(0xFFD8A34E);crown.setClickable(false);crown.setFocusable(false);
            premium.addView(crown,Ui.lp(this,24,32));
            TextView label=Ui.text(this,L10n.t("프리미엄 업그레이드"),12,Ui.TEXT,true);
            label.setMaxLines(2);label.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(6);
            premium.addView(label,lp);
            premium.setOnClickListener(v->{closeDrawer();EditionMonetization.showUpgrade(this);});
        }else footer.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        ToolbarIconView trash=new ToolbarIconView(this,ToolbarIconView.TRASH);
        trash.setTint(Ui.TEXT);trash.setContentDescription(L10n.t("휴지통"));
        footer.addView(trash,Ui.lp(this,48,48));
        trash.setOnClickListener(v->{closeDrawer();openTrash();});
        shade.animate().alpha(1f).setDuration(220).start();
        drawerHost.animate().translationX(0).setDuration(250)
                .setInterpolator(new DecelerateInterpolator()).start();
    }
    private void closeDrawer() {
        if(!drawerShown)return;
        if(drawerLayer==null){
            drawerShown=false;
            if(flutterBodySurface!=null)flutterBodySurface.closeDrawerOverlay();
            refreshMiniPlayer();
            return;
        }
        final FrameLayout layer = drawerLayer;
        final View panel = drawerPanel;
        final View shade = drawerShade;
        final FlutterNavigationSurface closingSurface=flutterDrawerSurface;
        flutterDrawerSurface=null;
        drawerShown = false;
        drawerLayer = null;
        drawerPanel = null;
        drawerShade = null;
        refreshMiniPlayer();
        if (closingSurface!=null) {
            closingSurface.pause();
            new Handler(Looper.getMainLooper()).postDelayed(closingSurface::close,250);
        }
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
        row.addView(providerIcon(kind,Ui.TEAL), Ui.lp(this, 32, 32));
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
        row.addView(providerIcon(iconKind,Ui.TEAL),
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
        if("settings".equals(currentScreen))return;
        if(FlutterUiMode.enabled(this)){
            settingsReturnScreen=currentScreen;
            settingsScope=folderScope();
            showSettingsContent(0);
            return;
        }
        Intent intent = new Intent(this, SettingsActivity.class);
        String scope = folderScope();
        if (scope != null)
            intent.putExtra(SettingsActivity.EXTRA_CURRENT_SCOPE, scope);
        startActivityForResult(intent, REQ_SETTINGS);
    }
    private void showSettingsContent(int tab){
        currentScreen="settings";
        render(L10n.t("설정"),true,null,null);
        final int generation=renderGeneration;
        settingsContent=new SettingsContent(this,page,currentScroll,settingsScope,tab,
                ()->{
                    if(generation!=renderGeneration||settingsContent==null)return;
                    int selected=settingsContent.selectedTab();
                    Ui.applyAppearance(this);
                    setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
                    container.setBackgroundColor(Ui.BG);
                    getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
                    getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                    renderedLanguage=L10n.language();
                    showSettingsContent(selected);
                },()->{
                    if(generation==renderGeneration&&flutterBodySurface!=null)
                        flutterBodySurface.refresh();
                });
        final SettingsContent controller=settingsContent;
        if(!FlutterUiMode.enabled(this)){finishScreenHandoff();return;}
        bindBody(FlutterUiContract.PANEL,new FlutterNavigationSurface.Host(){
            boolean live(){return generation==renderGeneration&&settingsContent==controller&&
                    "settings".equals(currentScreen)&&!isDestroyed()&&!isFinishing();}
            @Override public Map<String,Object> snapshot(String route){
                if(!live())throw new IllegalStateException("stale settings");
                Map<String,String> labels=FlutterOperationSnapshot.labels();
                labels.put("apply",L10n.t("적용"));
                Map<String,Object> data=FlutterOperationSnapshot.base(MainActivity.this,route,labels);
                Map<String,Object> panel=controller.model().snapshot();
                panel.put("showClassic",false);data.put("panel",panel);
                return data;
            }
            @Override public void action(String route,String action,String id){}
            @Override public void reorderFavorites(Object order){throw new IllegalStateException("settings");}
            @Override public void flutterShown(){screenHandoffReady(generation);}
            @Override public void fallbackShown(){screenHandoffReady(generation);}
            @Override public void explorerCall(io.flutter.plugin.common.MethodCall call,
                    io.flutter.plugin.common.MethodChannel.Result result){
                if(!live()||drawerShown||!"panelCommand".equals(call.method)||!(call.arguments instanceof Map)){
                    result.error("STALE_SETTINGS",null,null);return;
                }
                NativePanelModel model=controller.model();
                Runnable command=model.command(call.argument("revision"),call.argument("id"),call.argument("value"));
                if(command==null){result.error("INVALID_SETTINGS_COMMAND",null,null);return;}
                result.success(null);
                container.post(()->{
                    if(live()&&!drawerShown&&controller.model()==model&&flutterBodySurface!=null&&flutterBodySurface.isShown()){
                        try{command.run();}catch(RuntimeException failure){MainActivity.this.failure(failure);}
                    }
                });
            }
        });
    }
    private void showFavorites() {
        currentScreen = "favorites";
        render(L10n.t("즐겨찾기"), true, null, null);
        try {
            List<Models.Favorite> items = store.activeFavorites();
            if (items.isEmpty()) muted(L10n.t("즐겨찾기한 폴더가 없습니다."));
            for (Models.Favorite favorite : items) favoriteRow(page, favorite);
        } catch (Exception e) { failure(e); }
        if(FlutterUiMode.enabled(this))bindBody(FlutterUiContract.FAVORITES,flutterNavigationHost());
        else finishScreenHandoff();
    }
    private void favoriteRow(LinearLayout target, Models.Favorite f) {
        LinearLayout box = Ui.horizontal(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(8), dp(6), dp(6), dp(6));
        box.setBackgroundColor(Ui.SURFACE);
        LinearLayout.LayoutParams rowParams = Ui.lp(this, -1, 54);
        if(target.getChildCount()>0){
            View divider=new View(this);
            divider.setBackgroundColor((Ui.TEXT&0x00ffffff)|0x20000000);
            target.addView(divider,Ui.lp(this,-1,1));
        }
        target.addView(box, rowParams);
        Ui.Icon favoriteIcon=new Ui.Icon(this,"star",0xFFD8A34E);
        favoriteIcon.setBackground(Ui.bg(this,0x1FD8A34E,8,0));
        box.addView(favoriteIcon,Ui.lp(this,38,38));
        LinearLayout labels = Ui.vertical(this);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textParams =
                new LinearLayout.LayoutParams(0, -1, 1);
        textParams.leftMargin = dp(12);
        box.addView(labels, textParams);
        TextView name = Ui.text(this, f.displayTitle(), 14, Ui.TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        labels.addView(name, Ui.lp(this, -1, 23));
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
                .setItems(new String[]{L10n.t("즐겨찾기 이름 변경"), L10n.t("즐겨찾기 삭제")},
                        (dialog, selected) -> {
                            if (selected == 0) editFavoriteName(favorite);
                            else confirmRemoveFavorite(favorite);
                        }).show();
    }

    private void confirmRemoveFavorite(Models.Favorite favorite) {
        new AlertDialog.Builder(this)
                .setTitle(L10n.t("즐겨찾기 삭제"))
                .setMessage(L10n.f("%1$s 즐겨찾기를 삭제할까요?\n원본 폴더는 삭제되지 않습니다.",favorite.displayTitle()))
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("삭제"), (dialog, which) -> {
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
        input.setHint(L10n.t("즐겨찾기에 표시할 이름"));
        input.setText(favorite.displayTitle());
        input.setSelection(input.length());
        LinearLayout content = Ui.vertical(this);
        content.setPadding(dp(20), dp(8), dp(20), dp(2));
        TextView original = Ui.text(this, L10n.t("실제 폴더명: ") + favorite.title,
                12, Ui.MUTED, false);
        content.addView(original, Ui.lp(this, -1, -2));
        content.addView(input, Ui.lp(this, -1, 54));
        TextView note = Ui.text(this,
                L10n.t("즐겨찾기에 표시되는 이름만 변경되며 실제 폴더명은 그대로입니다."),
                11, Ui.MUTED, false);
        content.addView(note, Ui.lp(this, -1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t("즐겨찾기 이름 변경"))
                .setView(content)
                .setNegativeButton(L10n.t("취소"), null)
                .setNeutralButton(L10n.t("원래 이름"), null)
                .setPositiveButton(L10n.t("저장"), null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    store.renameFavorite(favorite, input.getText().toString());
                    dialog.dismiss();
                    refreshFavoriteSurfaces();
                    toast(L10n.t("즐겨찾기 이름을 변경했습니다."));
                } catch (IllegalArgumentException invalid) {
                    input.setError(invalid.getMessage());
                } catch (Exception error) { failure(error); }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                try {
                    store.renameFavorite(favorite, "");
                    dialog.dismiss();
                    refreshFavoriteSurfaces();
                    toast(L10n.t("원래 폴더명으로 표시합니다."));
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
                showError(L10n.t("삭제된 원격 연결입니다."));
            } catch (Exception ex) { failure(ex); }
        } else if ("saf".equals(f.kind)) {
            activeTreeUri = f.remoteId;
            activeTreeLabel = activeTreeUri.equals(store.sdTreeUri()) ?
                    "SD 저장소" : "선택한 폴더";
            openSafPath(f.path);
        } else browseLocal(new File(f.path));
    }
    private static String cloudIconKind(String protocol){
        switch(protocol){
            case "SMB":case "FTP":case "SFTP":case "WebDAV":case "GoogleDrive":case "OneDrive":case "MYBOX":case "TeraBox":return protocol;
            default:return "remote";
        }
    }
    private View providerIcon(String kind,int color){
        int res=0;
        switch(kind){
            case "SMB":res=R.drawable.protocol_smb;break;
            case "FTP":res=R.drawable.protocol_ftp;break;
            case "SFTP":res=R.drawable.protocol_sftp;break;
            case "WebDAV":res=R.drawable.protocol_webdav;break;
            case "trash":res=R.drawable.local_trash;break;
            case "GoogleDrive":res=R.drawable.cloud_google_drive;break;
            case "OneDrive":res=R.drawable.cloud_onedrive;break;
            case "MYBOX":res=R.drawable.cloud_mybox;break;
            case "TeraBox":res=R.drawable.cloud_terabox;break;
        }
        if(res==0)return new Ui.Icon(this,kind,color);
        android.widget.ImageView v=new android.widget.ImageView(this);
        v.setImageResource(res);v.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        v.setPadding(dp(3),dp(3),dp(3),dp(3));return v;
    }
    private void row(LinearLayout parent, String label, String subtitle, String iconType,
                     int color, Runnable click, Runnable longClick) {
        LinearLayout box = Ui.horizontal(this);
        box.setPadding(dp(12), dp(7), dp(11), dp(7));
        box.setBackground(Ui.bg(this, Ui.SURFACE, 13, Ui.BORDER));
        LinearLayout.LayoutParams params = Ui.lp(this, -1, 72);
        params.bottomMargin = dp(7);
        parent.addView(box, params);
        box.addView(providerIcon(iconType,color), Ui.lp(this, 42, 42));
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
        if(flutterExplorerSurface!=null&&flutterExplorerSurface.isActive())return;
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
    private String serverCountLabel(Models.Entry entry,Models.RemoteProfile provider){
        if(entry==null||!entry.directory||entry.symbolicLink||entry.serverChildCount<0||provider==null||
                !OneDriveAuth.PROTOCOL.equals(provider.protocol))return null;
        // Graph returns total immediate children, including dot-prefixed names.
        // Label explicitly as total; don't present it as a filtered count.
        return L10n.f("전체 %d개 항목",entry.serverChildCount);
    }
    private String countLabel(int count){
        return count<0?L10n.t("항목 수 미확인"):L10n.f("%d개 항목",count);
    }
    // Bookkeeping on the UI thread. Every count resolves, even on timeout.
    private void submitFolderCount(boolean network,Callable<Integer> load,Consumer<Integer> complete){
        java.util.concurrent.ThreadPoolExecutor pool=network?remoteCountWorker:localCountWorker;
        java.util.concurrent.FutureTask<Integer> task=new java.util.concurrent.FutureTask<Integer>(load){
            @Override protected void done(){
                folderCountHandler.post(()->{
                    Runnable timeout=folderCountTasks.remove(this);
                    if(timeout==null)return;
                    folderCountHandler.removeCallbacks(timeout);
                    int value=-1;
                    try{value=get();}catch(Exception ignored){}
                    complete.accept(value);
                });
            }
        };
        Runnable timeout=()->{task.cancel(true);pool.remove(task);};
        folderCountTasks.put(task,timeout);
        folderCountHandler.postDelayed(timeout,network?20000:8000);
        try{pool.execute(task);}
        catch(java.util.concurrent.RejectedExecutionException closed){task.cancel(true);}
    }
    private void cancelFolderCountJobs(){
        for(java.util.concurrent.FutureTask<Integer> task:new ArrayList<>(folderCountTasks.keySet()))
            task.cancel(true);
        localCountWorker.purge();remoteCountWorker.purge();
    }
    private void scheduleCount(View anchor, TextView target,
                               Models.Entry entry, Callable<Integer> count) {
        String total=serverCountLabel(entry,remote);
        if(total!=null){target.setText(total);return;}
        String key=countKey(entry);
        Integer remembered=folderCache.count(key);
        if(remembered!=null){target.setText(countLabel(remembered));return;}
        int screen=renderGeneration;
        Models.RemoteProfile provider=remote;
        boolean hidden=showHidden;
        addLazyJob(anchor,()->submitFolderCount(provider!=null,
                ()->explorerCount(entry,provider,hidden),value->{
            if(screen!=renderGeneration||!anchor.isAttachedToWindow())return;
            if(value>=0)folderCache.rememberCount(key,value);
            target.setText(countLabel(value));
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
            Bitmap image = loadExplorerThumbnail(item,provider,video);
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
    private Bitmap loadExplorerThumbnail(Models.Entry item,Models.RemoteProfile provider,boolean video) {
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
         } else if (provider != null && item.size <= 24L * 1024 * 1024) {
            final int limit = 24 * 1024 * 1024;
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            OutputStream bounded = new java.io.FilterOutputStream(bytes) {
                int used;
                @Override public void write(int b) throws IOException {
                    if (++used > limit) throw new IOException("Preview limit");
                    out.write(b);
                }
                @Override public void write(byte[] b, int o, int l) throws IOException {
                    if (l > limit - used) throw new IOException("Preview limit");
                    out.write(b, o, l); used += l;
                }
            };
            RemoteProvider.download(provider, item.path, bounded);
            byte[] raw = bytes.toByteArray();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 384)
                options.inSampleSize *= 2;
            image = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
        }
    } catch (Exception ignored) {}
        return image;
    }

    private FrameLayout fileIcon(Models.Entry entry, int dimension) {
        FrameLayout frame = new FrameLayout(this);
        int side = dp(dimension);
        frame.addView(new Ui.Icon(this,
                entry.directory ? "folder" : iconFor(entry.name),
                entry.directory ? 0xFFE5AD5B : FileIconType.color(iconFor(entry.name))),
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
        if (entry.symbolicLink) {
            if (entry.linkTargetUnavailable) return "심볼릭 링크 · 대상 접근 불가";
            return entry.directory ? "심볼릭 링크 → 폴더" : "심볼릭 링크 → 파일";
        }
        return entry.directory ? ("media".equals(currentScreen)?L10n.t("폴더"):L10n.t("항목 수 확인 중")) : pretty(entry.size);
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
        return folderPurposeFlags(entry,fileItem(entry));
    }
    private int folderPurposeFlags(Models.Entry entry,FileTransferEngine.Item item) {
        if (!entry.directory) return 0;
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
        if (explorerSession==null && !entry.directory && ComicArchive.supports(entry.name) &&
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
                date.setContentDescription(L10n.t("파일 수정 또는 생성 날짜 ") +
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
        if (entry.directory && count != null && !entry.symbolicLink)
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
        form.addView(Ui.text(this, L10n.t("보기 방식"), 15, Ui.TEAL, true));
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
        form.addView(Ui.text(this, L10n.t("정렬"), 15, Ui.TEAL, true));
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
        onlyHere.setText(L10n.t("이 폴더에만 적용 (하위 폴더 제외)"));
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
        form.addView(Ui.text(this, L10n.t("기타"), 15, Ui.TEAL, true));
        CheckBox hidden = new CheckBox(this);
        hidden.setText(L10n.t("숨겨진 파일 표시"));
        hidden.setChecked(showHidden);
        form.addView(hidden, Ui.lp(this, -1, 49));
        CheckBox thumbs = new CheckBox(this);
        thumbs.setText(L10n.t("썸네일 표시 (이미지)"));
        thumbs.setChecked(showThumbnails);
        form.addView(thumbs, Ui.lp(this, -1, 49));
        CheckBox videoThumbs = new CheckBox(this);
        videoThumbs.setText(L10n.t("동영상 실제 장면 미리보기 (기본 켜짐)"));
        videoThumbs.setChecked(showVideoThumbnails);
        form.addView(videoThumbs, Ui.lp(this, -1, 49));
        TextView hint = Ui.text(this,
                L10n.t("원격 썸네일은 1MB 이하 이미지에서만 다운로드합니다. ") +
                        L10n.t("NAS 동영상은 구간 읽기로 일부만 가져오며, ") +
                        L10n.t("지원되지 않는 영상은 기본 재생 아이콘으로 표시합니다."),
                12, Ui.MUTED, false);
        form.addView(hint, Ui.lp(this, -1, -2));
        ScrollView content = new ScrollView(this);
        content.addView(form);
        new AlertDialog.Builder(this).setTitle(L10n.t("보기 및 정렬"))
                .setView(content)
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("적용"), (d, which) -> {
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
        query.setHint(L10n.t("현재 폴더에서 파일명 검색"));
        content.addView(query, Ui.lp(this, -1, 53));
        CheckBox recursive=new CheckBox(this);
        recursive.setText(L10n.t("하위 폴더 포함 검색"));
        recursive.setEnabled(canRecurse);
        recursive.setChecked(false);
        content.addView(recursive,Ui.lp(this,-1,49));
        TextView searchButton=Ui.action(this,L10n.t("하위 폴더까지 검색 시작"));
        searchButton.setGravity(Gravity.CENTER);
        searchButton.setVisibility(View.GONE);
        content.addView(searchButton,Ui.lp(this,-1,46));
        ScrollView scroller = new ScrollView(this);
        LinearLayout results = Ui.vertical(this);
        scroller.addView(results);
        content.addView(scroller, Ui.lp(this, -1, 330));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t("검색"))
                .setView(content)
                .setNegativeButton(L10n.t("닫기"), null).create();
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
                        L10n.t("파일명 일부를 입력하세요. 현재 폴더의 항목에서 검색합니다."),
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
                TextView empty = Ui.text(this, L10n.t("검색 결과가 없습니다."),
                        13, Ui.MUTED, false);
                results.addView(empty, Ui.lp(this, -1, 56));
            }
            if (matching > 100) {
                TextView more = Ui.text(this,
                        L10n.t("일치하는 파일 ") + matching + "개 · 앞의 100개만 표시",
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
                    L10n.t("하위 폴더를 검색하고 있습니다…"),
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
                        results.addView(Ui.text(this,L10n.t("일치하는 파일이 없습니다."),
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
            toast(L10n.t("폴더 하나를 선택해 주세요."));
            return;
        }
        new AlertDialog.Builder(this).setTitle(L10n.t("폴더 용도 설정 · ") + item.name)
                .setItems(new String[]{L10n.t("동영상 폴더 설정/해제"),
                        L10n.t("독서용 폴더 설정/해제")},
                        (dialog, which) -> {
                            // Multi-selection hides the top menu; honor its
                            // bottom More operation before presenting settings.
                            if (!selection.isEmpty()) {
                                selection.clear();
                                updateFileOperationChrome();
                            }
                            if (which == 0) {
                                String scope = videoScopeForFolder(item);
                                if (scope == null) toast(L10n.t("지원하지 않는 폴더입니다."));
                                else configureVideoFolder(scope, item.name);
                            } else showReaderFolderOptions(item);
                        }).setNegativeButton(L10n.t("취소"), null).show();
    }

    private void showReaderFolderOptions(FileTransferEngine.Item folder) {
        ReaderFolders.Scope comic=readerScopeForFolder(folder,ReaderFolders.COMIC);
        ReaderFolders.Scope text=readerScopeForFolder(folder,ReaderFolders.TEXT);
        if(comic==null||text==null){
            showError(L10n.t("책 폴더는 폴더 하나를 선택해 지정해 주세요."));
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
                .setTitle(L10n.t("독서용 폴더 설정/해제 · ") + folder.name)
                .setItems(modes,(dialog,index)->{
                    try{
                        ReaderFolders.Scope target=index==0?comic:text;
                        boolean enabled=readerFolders.toggle(target.mode,target.kind,
                                target.owner,target.path);
                        toast((index==0?"만화책":"텍스트책")+
                                (enabled?" 폴더 지정 완료":" 폴더 지정 해제"));
                        refreshCurrent();
                    }catch(Exception e){failure(e);}
                }).setNegativeButton(L10n.t("취소"),null).show();
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
            new AlertDialog.Builder(this).setTitle(L10n.t("현재 폴더"))
                    .setItems(L10n.labels(entries),
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
        runJob(L10n.t("갤러리 미디어 등록 요청 중..."),
                () -> MediaIndexing.requestFolderScan(this, folder),
                count -> toast(count == 0 ?
                        "이 폴더에서 등록할 미디어를 찾지 못했습니다." :
                        count + "개 미디어 검색 요청 완료 · 갤러리에 표시되기까지 시간이 걸릴 수 있습니다."));
    }
    private void chooseNewEntry() {
        if ("remote".equals(currentScreen) &&
                (remote == null || isReadOnlyCloud(remote))) {
            showError(L10n.t("클라우드 베타 연결은 읽기 전용입니다."));
            return;
        }
        new AlertDialog.Builder(this).setTitle(L10n.t("새로 만들기"))
                .setItems(new String[]{L10n.t("새 파일"), L10n.t("새 폴더")}, (d, index) ->
                        createEntry(index == 1)).show();
    }
    private void createEntry(boolean directory) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint(directory ? "새 폴더 이름" : "새 파일 이름 (예: 메모.txt)");
        new AlertDialog.Builder(this)
                .setTitle(directory ? "새 폴더 만들기" : "새 파일 만들기")
                .setView(input)
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("만들기"), (dialog, button) -> {
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
                                if (file.exists()) throw new IOException(L10n.t("같은 이름이 이미 존재합니다."));
                                boolean ok = directory ? file.mkdir() : file.createNewFile();
                                if (!ok) throw new IOException("새 항목을 만들 수 없습니다.");
                            } else if (safTarget != null) {
                                if (safTarget.findFile(name) != null)
                                    throw new IOException(L10n.t("같은 이름이 이미 존재합니다."));
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
                            toast(L10n.t("생성했습니다."));
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
                toast(L10n.t("이미 저장소 접근 권한이 허용되어 있습니다."));
                return;
            }
            new AlertDialog.Builder(this).setTitle(L10n.t("저장소 접근 권한"))
                    .setMessage(L10n.t("메인 저장소와 다운로드의 모든 폴더를 탐색하려면 파일관리자 접근 권한이 필요합니다. 보호된 시스템·앱 개인 폴더는 제외됩니다."))
                    .setNegativeButton(L10n.t("취소"), null)
                    .setNeutralButton(L10n.t("폴더 직접 선택"), (d,w) -> pickTree(REQ_FOLDER))
                    .setPositiveButton(L10n.t("설정에서 허용"), (d,w) -> {
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
            showError(L10n.t("기기에 폴더 선택 앱이 없어 SD카드를 선택할 수 없습니다. ") +
                    L10n.t("시스템 파일 관리자 앱을 확인해 주세요."));
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
            showError(L10n.t("이 폴더를 열 수 없습니다. 저장소 접근 권한을 확인하세요."));
            return;
        }
        if(LocalTrash.reservedPath(directory.getAbsolutePath())){showError(L10n.t("휴지통은 휴지통 화면에서 관리해 주세요."));return;}
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
        TextView loading=muted(L10n.t("파일 목록을 읽는 중..."));
        worker.execute(()->{
            File[] raw=directory.listFiles();
            if(raw==null){
                runOnUiThread(()->{
                    if(screen==renderGeneration){
                        page.removeView(loading);
                        muted(L10n.t("폴더 읽기 권한이 없습니다."));
                    }
                });
                return;
            }
            List<Models.Entry> items=new ArrayList<>(raw.length);
            List<String> siblings=new ArrayList<>(raw.length);
            List<VideoEpisodeQueue.Episode> episodes=new ArrayList<>();
            for(File file:raw){
                String name=file.getName();
                if(LocalTrash.reserved(name))continue;
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
                if(items.isEmpty())muted(L10n.t("이 폴더는 비어 있습니다."));
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
            showError(L10n.t("장착된 SD카드를 찾지 못했습니다. 카드가 마운트되었는지 확인해 주세요."));
            return;
        }
        if (Build.VERSION.SDK_INT < 30) {
            new AlertDialog.Builder(this).setTitle(L10n.t("SD카드 접근 설정"))
                    .setMessage(L10n.t("SD카드의 원하는 폴더를 선택하고 접근을 허용해 주세요. ") +
                            L10n.t("선택한 폴더와 하위 폴더를 사용할 수 있습니다."))
                    .setNegativeButton(L10n.t("취소"), null)
                    .setPositiveButton(L10n.t("SD 폴더 선택"), (d,w) -> pickTree(REQ_SD))
                    .show();
            return;
        }
        boolean full = Environment.isExternalStorageManager();
        new AlertDialog.Builder(this).setTitle(L10n.t("SD카드 접근 설정"))
                .setMessage((full ? "전체 파일 관리 권한이 허용되어 있습니다.\n\n" :
                        "SD카드 전체를 이용하려면 '전체 파일 접근' 권한이 필요합니다.\n\n") +
                        "Android 11 이상에서는 SD카드 최상위 폴더의 " +
                        "'이 폴더 사용' 버튼이 비활성화될 수 있습니다. " +
                        "이는 안드로이드의 제한입니다.\n\n" +
                        "① 전체 접근: 시스템 설정에서 NAS Mount의 " +
                        "'모든 파일에 대한 접근 허용'을 켜세요.\n" +
                        "② 폴더 접근: SD카드 안의 하위 폴더를 선택하세요. " +
                        "그 폴더 밖은 사용할 수 없습니다.")
                .setNegativeButton(L10n.t("취소"), null)
                .setNeutralButton(L10n.t("SD 폴더 선택"), (d,w) -> pickTree(REQ_SD))
                .setPositiveButton(L10n.t("전체 접근 설정"), (d,w) -> openSdAllFilesSettings())
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
                showError(L10n.t("이 기기에서는 전체 파일 접근 설정 화면을 열 수 없습니다. ") +
                        L10n.t("SD 폴더 선택을 사용해 주세요."));
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
            toast(L10n.t("SD카드가 장착되어 있지 않습니다."));
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
        } catch (Exception e) { showError(L10n.t("즐겨찾기한 SD 폴더를 찾을 수 없습니다.")); }
    }
    private void browseSaf(DocumentFile folder, String relative) {
        if(LocalTrash.reservedPath(relative)){showError(L10n.t("휴지통은 휴지통 화면에서 관리해 주세요."));return;}
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
        TextView loading=muted(L10n.t("SD 폴더를 읽는 중..."));
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
                    if(LocalTrash.reserved(childName))continue;
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
                if(failure!=null){muted(L10n.t("SD 폴더를 읽지 못했습니다."));return;}
                siblingSubtitleIndex=SubtitleInventory.fromSiblings(siblings);
                visibleEpisodeQueue=queue;
                ImageFolderSequence.showFolder("uri", "", entries, showHidden);
                if(entries.isEmpty())muted(L10n.t("이 폴더는 비어 있습니다."));
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
        muted(L10n.t("기기에 저장된 파일 · 보기 설정에서 정렬 변경 가능"));
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
            muted(L10n.t("미디어 조회 권한이 없습니다. 설정에서 접근을 허용해 주세요."));
            return;
        }
        if (items.isEmpty()) muted(L10n.t("찾은 파일이 없습니다."));
        else muted(L10n.t("총 ") + items.size() + "개");
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
            TextView more = Ui.action(this, L10n.t("더 보기  (") + end + " / " + items.size() + ")");
            LinearLayout.LayoutParams p = Ui.lp(this, -1, 48);
            p.topMargin = dp(8);
            page.addView(more, p);
            more.setOnClickListener(v -> {
                page.removeView(more);
                appendMediaPage(items, type, end);
            });
        }
        if(offset==0)attachFlutterExplorer(displayed,renderGeneration);
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
        return FileIconType.kind(name);
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
            showError(L10n.t("음악 재생 서비스를 실행하지 못했습니다: ")+
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
        if (!BuildConfig.DIRECT_EDITION && ExternalApps.rar(f.getName())) {
            ExternalApps.openArchive(this, Uri.fromFile(f), f.getName());
            return;
        }
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
        if (!BuildConfig.DIRECT_EDITION && ExternalApps.rar(name)) {
            ExternalApps.openArchive(this, uri, name);
            return;
        }
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
                showError(L10n.t("압축 파일을 읽을 수 없습니다."));
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
        if(!EditionMonetization.full(this)){
            EditionMonetization.showUpgrade(this);return;
        }
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
            startActivity(Intent.createChooser(i, L10n.t("파일 열기")));
        } catch (ActivityNotFoundException e) {
            toast(L10n.t("이 형식의 파일을 열 수 있는 앱이 없습니다."));
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
            new AlertDialog.Builder(this).setTitle(L10n.t("동영상 폴더"))
                    .setMessage(L10n.t("이 폴더는 이미 상위 폴더의 동영상 폴더 설정을 적용받고 있어요. ") +
                            L10n.t("상위 폴더의 설정을 해제하면 하위 폴더도 함께 해제됩니다."))
                    .setPositiveButton(L10n.t("확인"), null).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(directlySelected ?
                        L10n.t("동영상 폴더 설정 해제") : L10n.t("동영상 폴더로 설정"))
                .setMessage(title + "\n\n" + (directlySelected ?
                        L10n.t("이 폴더 및 하위 폴더의 자막 숨기기 규칙을 해제할까요?") :
                        L10n.t("이 폴더와 모든 하위 폴더의 SRT·SMI·ASS·VTT·SUB·SUP·IDX·TXT·NFO 파일을 탐색기 목록에서 숨깁니다. 실제 파일은 삭제하지 않으며 동영상 목록의 외부 자막 표시는 계속 유지됩니다.")))
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(directlySelected ? L10n.t("해제") : L10n.t("설정"), (d, w) -> {
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
        if(useFlutterFileChrome()){
            final DocumentFile parent=safFolder;
            showFilePrompt(L10n.t("이름 변경"),"",oldName,L10n.t("변경"),value->{
                String next=value.trim();
                if(next.equals(oldName))return;
                try{
                    RemoteProvider.child("/",next);
                    runJob(L10n.t("이름 변경 중..."),()->{
                        boolean ok;
                        if(local!=null){
                            File target=new File(local.getParentFile(),next);
                            ok=!target.exists()&&local.renameTo(target);
                        }else ok=parent!=null&&parent.findFile(next)==null&&saf.renameTo(next);
                        if(!ok)throw new IOException(L10n.t("파일 이름을 변경할 수 없습니다."));
                        return true;
                    },ignored->refreshCurrent());
                }catch(Exception error){failure(error);}
            });
            return;
        }
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(oldName);
        input.selectAll();
        new AlertDialog.Builder(this).setTitle(L10n.t("이름 변경"))
                .setView(input).setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("변경"), (d, i) -> {
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
        if("settings".equals(currentScreen))showSettingsContent(settingsContent==null?0:settingsContent.selectedTab());
        else if ("local".equals(currentScreen)) browseLocal(localFolder);
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
    private void handleFlutterConnectionAction(Intent data) {
        String action = data.getStringExtra(FlutterUiContract.ACTION);
        String id = data.getStringExtra(FlutterUiContract.PROFILE_ID);
        if (!FlutterUiContract.validAction(FlutterUiContract.CONNECTIONS, action, id)) return;
        if ("upgrade".equals(action)) { EditionMonetization.showUpgrade(this); return; }
        if ("nativeList".equals(action)) { showRemoteList(true); return; }
        if ("add".equals(action)) { openFlutterEditor(null,true); return; }
        if ("backup".equals(action)) { settingsBackupUi().show(); return; }
        try {
            // Resolve again from the live secure store; Dart is not authoritative.
            for (Models.RemoteProfile p : store.supportedRemotes()) {
                if (!p.id.equals(id)) continue;
                if (!store.remoteAllowed(id)) {
                    if("manage".equals(action))showRemoteList(true);
                    else EditionMonetization.showUpgrade(this);
                    return;
                }
                if ("open".equals(action)) {
                    flutterRemoteOrigin=true;
                    openRemoteStart(p);
                }
                else if ("edit".equals(action)) { openFlutterEditor(p.id,true); }
                else { remoteMenu(p); }
                return;
            }
            showRemoteList();
        } catch (Exception error) { showError(safeError(error)); }
    }

    private void showRemoteList() {
        showRemoteList(false);
    }
    private void showRemoteList(boolean classic) {
        flutterRemoteOrigin=false;
        currentScreen = "remotes";
        remote = null;
        render(L10n.t("원격 저장소"), true, "+", () -> remoteForm(null));
        muted(L10n.t("NAS · Google Drive · OneDrive · TeraBox · MYBOX"));
        try {
            List<Models.RemoteProfile> profiles = store.activeRemotes();
            if (profiles.isEmpty()) {
                Ui.gap(page, this, 16);
                muted(L10n.t("연결을 추가하면 여기에 표시돼요."));
            }
            for (Models.RemoteProfile profile : profiles) {
                row(page, profile.label(), profile.summary(), cloudIconKind(profile.protocol),
                        Ui.TEAL, () -> openRemoteStart(profile),
                        () -> remoteMenu(profile));
            }
            if(!EditionMonetization.full(this)){
                muted(L10n.t("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다."));
                for(Models.RemoteProfile locked:store.supportedRemotes()){
                    if(store.remoteAllowed(locked.id))continue;
                    row(page,locked.label(),locked.summary()+" · "+L10n.t("풀버전 필요"),
                            cloudIconKind(locked.protocol),Ui.MUTED,()->EditionMonetization.showUpgrade(this),
                            ()->new AlertDialog.Builder(this)
                                    .setTitle(locked.label())
                                    .setMessage(L10n.t("풀버전 구매 후 이 연결을 사용할 수 있습니다."))
                                    .setNegativeButton(L10n.t("닫기"),null)
                                    .setNeutralButton(L10n.t("삭제"),(d,w)->{
                                        try {store.deleteRemote(locked.id);showRemoteList();}
                                        catch(Exception e){showError(safeError(e));}
                                    })
                                    .setPositiveButton(L10n.t("풀버전 구매"),(d,w)->EditionMonetization.showUpgrade(this))
                                    .show());
                }
            }
        } catch (Exception e) {
            showError(L10n.t("저장된 연결 설정을 읽을 수 없습니다. 암호화 키를 확인하세요."));
        }
        Ui.gap(page, this, 12);
        TextView add = Ui.action(this, L10n.t("+  원격 저장소 추가"));
        page.addView(add, Ui.lp(this, -1, 49));
        add.setOnClickListener(v -> remoteForm(null));
        Ui.gap(page,this,12);
        TextView backup=Ui.action(this,L10n.t("설정 파일 내보내기 · 가져오기"));
        page.addView(backup,Ui.lp(this,-1,52));
        backup.setOnClickListener(v -> settingsBackupUi().show());
        if(!classic&&FlutterUiMode.enabled(this))
            bindBody(FlutterUiContract.CONNECTIONS,flutterNavigationHost());
        else finishScreenHandoff();
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
        TextView label = Ui.text(this, L10n.t("유효기간"), 12, Ui.MUTED, true);
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
                L10n.t("링크를 받은 사람이 NAS에 로그인하지 않고 다운로드할 수 있습니다. ") +
                        L10n.t("외부 접속 가능 여부는 NAS HTTPS/QuickConnect/myQNAPcloud 설정에 따라 다릅니다."),
                12, Ui.MUTED, false);
        layout.addView(hint, Ui.lp(this, -1, -2));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        new AlertDialog.Builder(this).setTitle(L10n.t("공유 링크 생성"))
                .setView(scroll).setNegativeButton(L10n.t("닫기"), null)
                .setPositiveButton(L10n.t("링크 생성"), (d, w) -> {
                    int[] days = {1, 3, 7, 30, 0, -1};
                    int selected = days[expiry.getSelectedItemPosition()];
                    String custom = date.getText().toString().trim();
                    String secret = pass.getText().toString();
                    try {
                        NasSharePolicy.expiryDate(selected, custom);
                        if (NasSharePolicy.SYNOLOGY.equals(p.nasVendor) && secret.length() > 16)
                            throw new IllegalArgumentException("Synology 링크 암호는 최대 16자입니다.");
                        runJob(L10n.t("NAS에서 공유 링크 생성 중..."),
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
        startActivity(Intent.createChooser(intent, L10n.t("다운로드 링크 공유")));
    }

    private void showNasShareResult(Models.RemoteProfile p, NasShareApi.Link link) {
        new AlertDialog.Builder(this).setTitle(L10n.t("다운로드 링크 생성 완료"))
                .setMessage(link.url + "\n\n이 링크를 받은 사람은 파일을 다운로드할 수 있습니다. " +
                        "외부에서 접속되는지 확인한 뒤 전달하세요.")
                .setNegativeButton(L10n.t("닫기"), null)
                .setNeutralButton(L10n.t("링크 복사"), (d, w) -> {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "NAS 다운로드 공유 링크", link.url));
                    toast(L10n.t("공유 링크를 복사했습니다."));
                })
                .setPositiveButton(L10n.t("공유하기"), (d, w) -> shareNasUrl(link.url))
                .show();
    }

    private void showNasShareList(Models.RemoteProfile p) {
        if (!NasSharePolicy.supported(p)) return;
        runJob(L10n.t("NAS 공유 링크 목록 확인 중..."), () -> new NasShareApi(p).list(), links -> {
            if (links.isEmpty()) {
                new AlertDialog.Builder(this).setTitle(L10n.t("NAS 공유 링크"))
                        .setMessage(L10n.t("현재 설정한 공유 폴더에 발급된 링크가 없습니다."))
                        .setPositiveButton(L10n.t("확인"), null).show();
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
            TextView title = Ui.text(this, L10n.t("NAS 공유 링크 관리"), 18, Ui.TEXT, true);
            heading.addView(title, Ui.lp(this, -1, 34));
            TextView selectionCount = Ui.text(this, "0 / " + links.size() + "개 선택",
                    13, Ui.MUTED, false);
            heading.addView(selectionCount, Ui.lp(this, -1, 25));
            LinearLayout controls = Ui.horizontal(this);
            TextView all = Ui.action(this, L10n.t("전체 선택"));
            TextView clear = Ui.action(this, L10n.t("선택 해제"));
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
                    .setNegativeButton(L10n.t("닫기"), null)
                    .setNeutralButton(L10n.t("링크 복사"), null)
                    .setPositiveButton(L10n.t("선택 링크 해제"), null)
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
                        toast(L10n.t("링크 하나만 선택하면 주소를 복사할 수 있습니다."));
                        return;
                    }
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                            "NAS 공유 링크", selected.get(0).url));
                    toast(L10n.t("공유 링크를 복사했습니다."));
                });
                manager.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    List<NasShareApi.Link> selected = selectedNasLinks(links, checked);
                    if (selected.isEmpty()) {
                        toast(L10n.t("해제할 공유 링크를 선택하세요."));
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle(L10n.f("%1$d개 공유 링크를 해제할까요?",selected.size()))
                            .setMessage(L10n.t("선택한 다운로드 공유 주소만 무효화합니다. ") +
                                    L10n.t("NAS에 저장된 원본 파일과 폴더는 삭제되지 않습니다."))
                            .setNegativeButton(L10n.t("취소"), null)
                            .setPositiveButton(L10n.t("공유 링크 해제"), (dialog, which) -> {
                                manager.dismiss();
                                runJob(L10n.t("선택한 NAS 공유 링크 해제 중..."),
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
        new AlertDialog.Builder(this).setTitle(L10n.t("공유 링크 일괄 해제 결과"))
                .setMessage(text.toString())
                .setNegativeButton(L10n.t("닫기"), null)
                .setPositiveButton(L10n.t("목록 새로고침"), (dialog, which) -> showNasShareList(profile))
                .show();
    }

    private void showNasShareBrowser(Models.RemoteProfile p, String folder) {
        if (!NasSharePolicy.supported(p)) return;
        String current = RemoteProvider.relativePath(folder);
        if (!"/".equals(current) && !NasSharePolicy.inAllowedRoot(p, current)) {
            showError(L10n.t("선택하지 않은 NAS 공유 폴더입니다."));
            return;
        }
        runJob(L10n.t("File Station 공유 폴더 탐색 중..."), () -> {
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
                                .setItems(new String[]{L10n.t("폴더 열기"), L10n.t("이 폴더 공유 링크 만들기")},
                                        (dialog, option) -> {
                                            if (option == 0)
                                                showNasShareBrowser(p, selected.path);
                                            else
                                                showNasShareCreateForPath(p, selected.path,
                                                        selected.name, true);
                                        }).setNegativeButton(L10n.t("닫기"), null).show();
                    })
                    .setPositiveButton(L10n.t("닫기"), null);
            if (!"/".equals(current)) {
                String parent = RemoteProvider.parent(current);
                if (!NasSharePolicy.inAllowedRoot(p, parent)) parent = "/";
                String back = parent;
                builder.setNegativeButton(L10n.t("상위 폴더"), (d, w) ->
                        showNasShareBrowser(p, back));
            }
            if (entries.isEmpty()) builder.setMessage(L10n.t("이 폴더는 비어 있습니다."));
            builder.show();
        });
    }

    private void showNasShareSettings(Models.RemoteProfile p) {
        if (!("SMB".equals(p.protocol) || "SFTP".equals(p.protocol) ||
                "FTP".equals(p.protocol) || "WebDAV".equals(p.protocol))) {
            showError(L10n.t("공유 링크는 Synology·QNAP 개인 NAS 연결에서만 사용할 수 있습니다."));
            return;
        }
        LinearLayout panel = Ui.vertical(this);
        panel.setPadding(dp(20), dp(8), dp(20), dp(12));
        TextView hint = Ui.text(this,
                L10n.t("DSM/QTS File Station 관리 API를 별도로 연결합니다. ") +
                        L10n.t("제조사는 실제 로그인 및 File Station 응답 확인 후 활성화됩니다. ") +
                        L10n.t("관리 주소는 올바른 HTTPS 인증서가 있어야 합니다."),
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
        TextView qnapCloudButton = Ui.action(this, L10n.t("QNAP CloudLink로 포트 개방 없이 접속 (공식 웹)"));
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
        shareRoot.setHint(L10n.t("예: /video 또는 /Public"));
        final List<String> checkedNasRoots = new ArrayList<>(NasSharePolicy.roots(p));
        TextView selectedRoots = Ui.text(this, "", 12, Ui.TEXT, false);
        selectedRoots.setText(checkedNasRoots.isEmpty() ? "선택된 공유 폴더 없음" :
                "선택: " + android.text.TextUtils.join(", ", checkedNasRoots));
        TextView findShares = Ui.action(this, L10n.t("공유 폴더 목록 불러오기 · 여러 개 선택"));
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
                showError(L10n.t("Synology 또는 QNAP 제조사를 먼저 선택하세요."));
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
                runJob(L10n.t("NAS 공유 폴더 조회 중..."),
                        () -> new NasShareApi(candidate).listShares(), shares -> {
                            verifiedDevice[0] = candidate.nasDeviceId;
                            verifiedAccount[0] = candidate.nasApiUser;
                            verifiedEndpoint[0] = candidate.nasApiBase;
                            verifiedPassword[0] = candidate.nasApiPassword;
                            if (shares.isEmpty()) {
                                showError(L10n.t("접근 가능한 공유 폴더가 없습니다."));
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
                                    .setTitle(L10n.t("링크 공유를 허용할 NAS 폴더"))
                                    .setMultiChoiceItems(names, chosen,
                                            (dialog, index, isChecked) -> chosen[index] = isChecked)
                                    .setNegativeButton(L10n.t("닫기"), null)
                                    .setPositiveButton(L10n.t("선택 완료"), (dialog, which) -> {
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
                L10n.t("SMB 경로와 File Station 경로는 다를 수 있습니다. ") +
                        "예: SMB 시작 경로 /video/영화 → API 기준 폴더 /video/영화. " +
                        L10n.t("기본 전송 접속 설정은 변경되지 않습니다."),
                12, Ui.MUTED, false);
        Ui.gap(panel, this, 10);
        panel.addView(warning, Ui.lp(this, -1, -2));
        ScrollView scrolling = new ScrollView(this);
        scrolling.addView(panel);
        new AlertDialog.Builder(this).setTitle(L10n.t("NAS 공유 링크 설정"))
                .setView(scrolling)
                .setNegativeButton(L10n.t("닫기"), null)
                .setNeutralButton(L10n.t("연결 해제"), (d, w) -> {
                    p.nasVendor = "";
                    p.nasApiBase = "";
                    p.nasApiUser = "";
                    p.nasApiPassword = "";
                    p.nasDeviceId = "";
                    p.nasShareRoot = "";
                    p.nasShareRoots.clear();
                    p.nasPublicHost = "";
                    try { store.putRemote(p); toast(L10n.t("NAS 공유 기능 연결을 해제했습니다.")); }
                    catch (Exception error) { failure(error); }
                })
                .setPositiveButton(L10n.t("인증 확인 후 저장"), (d, w) -> {
                    if (vendor.getSelectedItemPosition() == 0) {
                        showError(L10n.t("먼저 Synology 또는 QNAP 제조사를 선택하세요. ") +
                                L10n.t("기능을 해제하려면 '연결 해제'를 누르세요."));
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
                        runJob(L10n.t("NAS 제조사 및 File Station 인증 확인 중..."), () -> {
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
                                toast(L10n.t("NAS 공유 링크 인증이 완료됐습니다."));
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
                .setItems(L10n.labels(menu.toArray(new String[0])),
                        (d, i) -> {
                            String choice = menu.get(i);
                            if (choice.equals("연결하기")) openRemoteStart(p);
                            else if (choice.equals("설정 수정")) remoteForm(p);
                            else if (choice.equals("NAS 공유 링크 설정")) showNasShareSettings(p);
                            else if (choice.equals("NAS 공유 폴더 탐색")) showNasShareBrowser(p, "/");
                            else if (choice.equals("공유 링크 관리")) showNasShareList(p);
                            else if (choice.startsWith("QNAP CloudLink")) showQnapCloudBrowser(p);
                            else if (choice.equals("연결 삭제"))
                                new AlertDialog.Builder(this)
                                    .setTitle(L10n.t("원격 연결 삭제"))
                                    .setMessage(L10n.t("저장한 ‘") + p.label() +
                                            "’ 연결 정보만 삭제합니다. 서버 파일은 변경되지 않습니다.")
                                    .setNegativeButton(L10n.t("취소"), null)
                                    .setPositiveButton(L10n.t("삭제"), (d2, j) -> {
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
            showError(L10n.t("myQNAPcloud 페이지를 열려면 웹 브라우저가 필요합니다."));
        }
    }

    private void showQnapCloudBrowser(Models.RemoteProfile profile) {
        LinearLayout box = Ui.vertical(this);
        box.setPadding(dp(20), dp(12), dp(20), dp(10));
        box.addView(Ui.text(this,
                L10n.t("공식 myQNAPcloud Link 웹 접속입니다. NAS 관리 포트 개방은 필요하지 않습니다. ") +
                        L10n.t("QNAP 계정으로 로그인하여 File Station에서 공유 링크를 만드세요. ") +
                        L10n.t("FileHub의 직접 NAS API 연결과는 별개이며 웹 세션을 가져오지 않습니다."),
                13, Ui.TEXT, false), Ui.lp(this, -1, -2));
        Ui.gap(box, this, 12);
        EditText smartUrl = field(box, "내 NAS SmartURL (선택)",
                profile.qnapCloudSmartUrl, android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_URI);
        smartUrl.setHint(L10n.t("https://qlink.to/내장치이름"));
        box.addView(Ui.text(this,
                L10n.t("SmartURL을 모르면 '공식 포털 열기'를 선택하세요. ") +
                        L10n.t("QNAP 계정 > 내 장치에서 SmartURL을 확인할 수 있습니다."),
                12, Ui.MUTED, false), Ui.lp(this, -1, -2));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(L10n.t("QNAP CloudLink · 포트 개방 불필요"))
                .setView(box)
                .setNegativeButton(L10n.t("닫기"), null)
                .setNeutralButton(L10n.t("공식 포털 열기"), (d, w) ->
                        openQnapOfficialSite(QnapCloudLinkPolicy.PORTAL))
                .setPositiveButton(L10n.t("SmartURL 저장 후 열기"), null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    try {
                        String url = QnapCloudLinkPolicy.smartUrl(
                                smartUrl.getText().toString());
                        if (QnapCloudLinkPolicy.PORTAL.equals(url)) {
                            showError(L10n.t("장치 SmartURL을 입력하거나 공식 포털 열기를 선택하세요."));
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

    private void teraboxWebLogin(java.util.function.Consumer<String> onCookie) {
        new RemoteCloudUi(this).login(onCookie, () -> {});
    }

    private void openFlutterEditor(String id,boolean returnToFlutter) {
        // Keep the list and its attached Flutter texture visible. On phones
        // all add/edit operations are overlays, not a second Flutter Activity.
        if("remotes".equals(currentScreen)&&flutterBodySurface!=null&&
                flutterBodySurface.isShown()&&!drawerShown){
            if(embeddedRemoteEditor!=null)return;
            final String token=java.util.UUID.randomUUID().toString();
            embeddedEditorToken=token;embeddedEditorSaved=false;
            editorAddedSecureFlag=(getWindow().getAttributes().flags&
                    android.view.WindowManager.LayoutParams.FLAG_SECURE)==0;
            if(editorAddedSecureFlag)getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            // Shade and intercept the native toolbar, which sits outside the
            // persistent Flutter viewport; the dialog shades its own body.
            if(normalFileToolbar!=null){
                editorToolbarShade=new View(this);
                editorToolbarShade.setBackgroundColor(0x8A000000);
                editorToolbarShade.setClickable(true);
                FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(-1,
                        dp(FileHubBrandLayout.HEADER_HEIGHT_DP));
                lp.topMargin=statusBarInsetPx;
                container.addView(editorToolbarShade,lp);
            }
            embeddedRemoteEditor=new FlutterRemoteEditor(this,worker,id,action->{
                if(token.equals(embeddedEditorToken)&&"saved".equals(action))embeddedEditorSaved=true;
            });
            Map<String,Object> payload=new LinkedHashMap<>();
            Map<String,String> labels=new LinkedHashMap<>();
            FlutterRemoteEditor.labels(labels);
            payload.put("labels",labels);payload.put("token",token);
            embeddedRemoteEditor.snapshot(payload,new io.flutter.plugin.common.MethodChannel.Result(){
                @Override public void success(Object value){
                    if(token.equals(embeddedEditorToken)&&flutterBodySurface!=null)
                        flutterBodySurface.push("editorOpen",value);
                }
                @Override public void error(String code,String message,Object details){
                    if(token.equals(embeddedEditorToken)){
                        closeEmbeddedEditor(false);showError(L10n.t("연결 설정이 변경되었습니다. 화면을 닫고 다시 열어 주세요."));
                    }
                }
                @Override public void notImplemented(){error("stale",null,null);}
            });
            return;
        }
        // Native-only/TV/failed surface fallback stays available.
        Intent intent=FlutterInterfaceActivity.intent(this,FlutterUiContract.EDITOR);
        if(id!=null)intent.putExtra(FlutterUiContract.PROFILE_ID,id);
        intent.putExtra(FlutterRemoteEditor.RETURN_FLUTTER,returnToFlutter);
        startActivityForResult(intent,FlutterUiContract.REQUEST_EDITOR);
    }
    private void embeddedEditorCall(io.flutter.plugin.common.MethodCall call,
            io.flutter.plugin.common.MethodChannel.Result result){
        if(!"editorCommand".equals(call.method)||embeddedRemoteEditor==null||
                !(call.arguments instanceof Map)||
                !java.util.Objects.equals(embeddedEditorToken,call.argument("token"))){
            result.error("stale",null,null);return;
        }
        Object raw=call.argument("method");
        if("editorCancel".equals(raw)){
            if(embeddedRemoteEditor.isBusy()){result.error("busy",null,null);return;}
            result.success(null);closeEmbeddedEditor(false);return;
        }
        if(!"editorSave".equals(raw)&&!"editorLogin".equals(raw)&&!"editorHelp".equals(raw)){
            result.error("invalid",null,null);return;
        }
        if(embeddedEditorSaved){result.error("stale",null,null);return;}
        embeddedRemoteEditor.handle(new io.flutter.plugin.common.MethodCall((String)raw,call.argument("args")),result);
    }
    private void closeEmbeddedEditor(boolean force){
        if(embeddedRemoteEditor==null)return;
        String token=embeddedEditorToken;
        boolean saved=embeddedEditorSaved;
        embeddedRemoteEditor.close();embeddedRemoteEditor=null;
        embeddedEditorToken=null;embeddedEditorSaved=false;
        if(force&&flutterBodySurface!=null)flutterBodySurface.push("editorDismiss",token);
        if(editorToolbarShade!=null){container.removeView(editorToolbarShade);editorToolbarShade=null;}
        if(editorAddedSecureFlag){
            editorAddedSecureFlag=false;
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        }
        if(saved&&!force){
            folderCache.clearCounts();
            if(flutterBodySurface!=null)flutterBodySurface.refresh();
            toast(L10n.t("연결 설정을 저장했습니다."));
        }
    }
    private void remoteForm(Models.RemoteProfile original) {
        if(embeddedRemoteEditor!=null)return;
        if(!FlutterUiMode.enabled(this))nativeRemoteForm(original);
        else openFlutterEditor(original==null?null:original.id,false);
    }
    private void nativeRemoteForm(Models.RemoteProfile original) {
        if(original!=null&&OneDriveAuth.linked(original)){nativeOneDriveForm(original);return;}
        if(original!=null&&GoogleDriveAuth.linked(original)){
            nativeGoogleForm(original);return;
        }
        if(original==null){
            new AlertDialog.Builder(this).setTitle(L10n.t("원격 저장소 추가"))
                .setItems(new String[]{"Google Drive","OneDrive",L10n.t("기타 원격 저장소")},(d,w)->{
                    if(w==0)nativeGoogleForm(null);else if(w==1)nativeOneDriveForm(null);else nativeTraditionalRemoteForm(null);
                }).show();return;
        }
        nativeTraditionalRemoteForm(original);
    }
    private void nativeGoogleForm(Models.RemoteProfile original){
        LinearLayout form=Ui.vertical(this);form.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText name=field(form,"표시할 이름",original==null?"":original.name,android.text.InputType.TYPE_CLASS_TEXT);
        CheckBox ro=new CheckBox(this);ro.setText(L10n.t("읽기 전용 (변경·삭제·업로드 차단)"));
        ro.setChecked(original!=null&&original.readOnly);form.addView(ro,Ui.lp(this,-1,44));
        form.addView(Ui.text(this,L10n.t(original==null?"휴대폰의 Google 계정을 선택하고 접근 권한을 허용하세요.":"기존 Google 계정으로 다시 로그인합니다."),13,Ui.MUTED,false));
        new AlertDialog.Builder(this).setTitle("Google Drive").setView(form)
            .setNegativeButton(L10n.t("취소"),null).setPositiveButton(L10n.t("Google 계정 연결"),(d,w)->
                startActivityForResult(GoogleDriveConnectActivity.intent(this,original==null?null:original.id,name.getText().toString(),ro.isChecked()),GoogleDriveConnectActivity.REQUEST)).show();
    }
    private void nativeOneDriveForm(Models.RemoteProfile original){
        LinearLayout form=Ui.vertical(this);form.setPadding(dp(20),dp(8),dp(20),dp(8));
        EditText name=field(form,"표시할 이름",original==null?"":original.name,android.text.InputType.TYPE_CLASS_TEXT);
        CheckBox ro=new CheckBox(this);ro.setText(L10n.t("읽기 전용 (변경·삭제·업로드 차단)"));
        ro.setChecked(original!=null&&original.readOnly);form.addView(ro,Ui.lp(this,-1,44));
        form.addView(Ui.text(this,L10n.t(original==null?"Microsoft에 로그인하고 OneDrive 접근을 허용하세요.":"기존 Microsoft 계정으로 다시 로그인합니다."),13,Ui.MUTED,false));
        new AlertDialog.Builder(this).setTitle("OneDrive").setView(form)
            .setNegativeButton(L10n.t("취소"),null).setPositiveButton(L10n.t("Microsoft 계정 연결"),(d,w)->
                startActivityForResult(OneDriveConnectActivity.intent(this,original==null?null:original.id,name.getText().toString(),ro.isChecked()),OneDriveConnectActivity.REQUEST)).show();
    }
    private void nativeTraditionalRemoteForm(Models.RemoteProfile original) {
        String[] types = {"SMB", "FTP", "SFTP", "WebDAV",
                "TeraBox", "MYBOX"};
        final int[] defaultPorts = {445, 21, 22, 443, 0, 0};
        LinearLayout form = Ui.vertical(this);
        form.setPadding(dp(20), dp(6), dp(20), dp(12));
        TextView protocolTitle = Ui.text(this, L10n.t("연결 방식"), 12, Ui.MUTED, true);
        form.addView(protocolTitle, Ui.lp(this, -1, 26));
        Spinner protocol = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, types);
        protocol.setAdapter(adapter);
        form.addView(protocol, Ui.lp(this, -1, 49));
        EditText title = field(form, "표시할 이름", original == null ? "" : original.name,
                android.text.InputType.TYPE_CLASS_TEXT);
        title.setHint(L10n.t("예: 집 NAS"));
        EditText host = field(form, "서버 주소", original == null ? "" : original.host,
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_URI);
        host.setHint(L10n.t("호스트 또는 IP"));
        EditText port = field(form, "포트", original == null ? "445" :
                String.valueOf(original.port),
                android.text.InputType.TYPE_CLASS_NUMBER);
        EditText root = field(form, "공유/시작 경로", original == null ? "" : original.root,
                android.text.InputType.TYPE_CLASS_TEXT);
        root.setHint(L10n.t("SMB: 공유이름    기타: /시작폴더"));
        EditText username = field(form, "사용자명 (익명 FTP는 비워 두기)",
                original == null ? "" : original.username,
                android.text.InputType.TYPE_CLASS_TEXT);
        EditText password = field(form, "비밀번호", original == null ? "" : original.password,
                android.text.InputType.TYPE_CLASS_TEXT |
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox https = new CheckBox(this);
        https.setText(L10n.t("WebDAV HTTPS 보안 연결 사용"));
        https.setTextSize(14);
        https.setChecked(original == null || original.ssl);
        form.addView(https, Ui.lp(this, -1, 44));
        TextView ftpTlsTitle = Ui.text(this, L10n.t("FTP 연결 보안"), 12, Ui.MUTED, true);
        form.addView(ftpTlsTitle, Ui.lp(this, -1, 25));
        String[] ftpTlsLabels = {"일반 FTP", "FTPS Explicit TLS (21)",
                "FTPS Implicit TLS (990)"};
        Spinner ftpTls = new Spinner(this);
        ftpTls.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ftpTlsLabels));
        ftpTls.setSelection(original != null && original.ftpImplicitTls ? 2 :
                original != null && original.ftpExplicitTls ? 1 : 0);
        form.addView(ftpTls, Ui.lp(this, -1, 45));
        TextView ftpCharsetTitle = Ui.text(this, L10n.t("FTP 파일명 문자셋"), 12, Ui.MUTED, true);
        form.addView(ftpCharsetTitle, Ui.lp(this, -1, 25));
        String[] ftpCharsetLabels = {"UTF-8", "CP949 / EUC-KR (한국어)",
                "EUC-KR", "서버 기본"};
        Spinner ftpCharset = new Spinner(this);
        ftpCharset.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, ftpCharsetLabels));
        if (original != null) {
            String chosen = original.ftpCharset;
            if ("CP949".equalsIgnoreCase(chosen) || "MS949".equalsIgnoreCase(chosen))
                chosen = "CP949 / EUC-KR (한국어)";
            int index = Arrays.asList(ftpCharsetLabels).indexOf(chosen);
            if (index >= 0) ftpCharset.setSelection(index);
        }
        form.addView(ftpCharset, Ui.lp(this, -1, 45));
        TextView sftpCharsetTitle = Ui.text(this,
                L10n.t("SFTP 파일명 문자셋"), 12, Ui.MUTED, true);
        form.addView(sftpCharsetTitle, Ui.lp(this, -1, 25));
        Spinner sftpCharset = new Spinner(this);
        sftpCharset.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                SftpFilenameCharset.OPTIONS));
        if (original != null) {
            String chosen = original.sftpCharset;
            if ("CP949".equalsIgnoreCase(chosen) || "MS949".equalsIgnoreCase(chosen))
                chosen = "CP949 / EUC-KR (한국어)";
            int index = Arrays.asList(SftpFilenameCharset.OPTIONS).indexOf(chosen);
            if (index >= 0) sftpCharset.setSelection(index);
        }
        form.addView(sftpCharset, Ui.lp(this, -1, 45));
        CheckBox ftpPasv = new CheckBox(this);
        ftpPasv.setText(L10n.t("FTP 수동 모드(PASV)"));
        ftpPasv.setTextSize(14);
        ftpPasv.setChecked(original == null || original.ftpPreferPasv);
        form.addView(ftpPasv, Ui.lp(this, -1, 43));
        CheckBox readOnly = new CheckBox(this);
        readOnly.setText(L10n.t("읽기 전용 (변경·삭제·업로드 차단)"));
        readOnly.setTextSize(14);
        readOnly.setChecked(original != null && original.readOnly);
        form.addView(readOnly, Ui.lp(this, -1, 43));
        ftpTls.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                    View view, int pos, long id) {
                if (protocol.getSelectedItemPosition() == 1 &&
                        (port.getText().toString().equals("21") ||
                         port.getText().toString().equals("990")))
                    port.setText(pos == 2 ? "990" : "21");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        TextView cloudHint = Ui.text(this, "", 12, Ui.MUTED, false);
        cloudHint.setPadding(dp(3), dp(5), dp(3), dp(5));
        form.addView(cloudHint, Ui.lp(this, -1, -2));
        TextView teraboxAuth = Ui.action(this, L10n.t("TeraBox 계정으로 웹 로그인 · 세션 가져오기"));
        form.addView(teraboxAuth, Ui.lp(this, -1, 42));
        teraboxAuth.setOnClickListener(v -> teraboxWebLogin(
                ndus -> password.setText(ndus)));
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
                ftpTlsTitle.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
                ftpTls.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
                ftpCharsetTitle.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
                ftpCharset.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
                sftpCharsetTitle.setVisibility(selected == 2 ? View.VISIBLE : View.GONE);
                sftpCharset.setVisibility(selected == 2 ? View.VISIBLE : View.GONE);
                ftpPasv.setVisibility(selected == 1 ? View.VISIBLE : View.GONE);
                boolean ordinary = selected < 4;
                host.setVisibility(ordinary ? View.VISIBLE : View.GONE);
                port.setVisibility(ordinary ? View.VISIBLE : View.GONE);
                username.setVisibility(ordinary ? View.VISIBLE : View.GONE);
                password.setVisibility(View.VISIBLE);
                teraboxAuth.setVisibility(selected == 4 ? View.VISIBLE : View.GONE);
                if (pcHelpButton[0] != null) {
                    pcHelpButton[0].setVisibility(
                            selected == 4 || selected == 5 ? View.VISIBLE : View.GONE);
                    pcHelpButton[0].setText(selected == 5 ?
                            "PAT 발급 방법" : "PC에서 가져오는 방법");
                }
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
                    password.setHint(L10n.t("ndus 세션값 또는 ndus=..."));
                    cloudHint.setText(L10n.t("TeraBox 베타: 폴더·파일 읽기/쓰기 지원. ndus 세션값이 필요합니다. ") +
                            "휴대폰 Chrome 로그인만으로는 ndus를 가져올 수 없습니다. " +
                            "PC 브라우저에서 확인해 입력하거나 위의 TeraBox 웹 로그인을 이용하세요.");
                } else if (selected == 5) {
                    password.setHint(L10n.t("mbx_pat_... 개인 액세스 토큰"));
                    cloudHint.setText(L10n.t("네이버 MYBOX 공식 Open API PAT가 필요합니다. ") +
                            "폴더 탐색·업로드·다운로드·이름 변경·복사·이동·삭제를 지원합니다.");
                } else {
                    password.setHint("");
                    cloudHint.setText("");
                }
                root.setHint(selected == 0 ? "공유이름/하위경로 (필수)" :
                        selected == 2 ?
                        "비우면 로그인 홈 폴더 · / 입력하면 루트부터" :
                        "선택 사항 · 시작 경로 /");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(original == null ? "원격 저장소 추가" : "원격 저장소 수정")
                .setView(scroll).setNegativeButton(L10n.t("취소"), null)
                .setNeutralButton(L10n.t("PC에서 가져오는 방법"), null)
                .setPositiveButton(L10n.t("저장"), null).create();
        dialog.setOnShowListener(ignore -> {
            pcHelpButton[0] = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
            pcHelpButton[0].setAllCaps(false);
            pcHelpButton[0].setTextSize(11);
            int initialProtocol = protocol.getSelectedItemPosition();
            pcHelpButton[0].setVisibility(initialProtocol == 4 || initialProtocol == 5 ?
                    View.VISIBLE : View.GONE);
            pcHelpButton[0].setText(initialProtocol == 5 ?
                    "PAT 발급 방법" : "PC에서 가져오는 방법");
            pcHelpButton[0].setOnClickListener(v -> {
                if (protocol.getSelectedItemPosition() == 5) showMyBoxPatHelp();
                else showTeraBoxPcHelp();
            });
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
                    if(!store.canSaveRemote(original==null?null:original.id,types[selected])){
                        EditionMonetization.showUpgrade(this);return;
                    }
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
                    p.ftpExplicitTls = selected == 1 && ftpTls.getSelectedItemPosition() == 1;
                    p.ftpImplicitTls = selected == 1 && ftpTls.getSelectedItemPosition() == 2;
                    p.ftpCharset = selected == 1
                            ? ftpCharset.getSelectedItem().toString() : p.ftpCharset;
                    if (selected == 2) {
                        p.sftpCharset = sftpCharset.getSelectedItem().toString();
                        // Reject unsupported Android code pages before saving.
                        SftpFilenameCharset.resolve(p.sftpCharset);
                    }
                    p.ftpPreferPasv = ftpPasv.isChecked();
                    p.readOnly = readOnly.isChecked();
                    // Removed experimental RFC4918 server-lock profile option.
                    // Excel owner-file handling is automatic for WebDAV.
                    p.useNativeWebDavLocks = false;
                    store.putRemote(p);
                    dialog.dismiss();
                    showRemoteList();
                    toast(L10n.t("연결 설정을 저장했습니다."));
                } catch (Exception e) { showError(safeError(e)); }
            });
        });
        dialog.show();
    }

    private void showTeraBoxPcHelp() { new RemoteCloudUi(this).showTeraBoxPcHelp(); }
    private void showMyBoxPatHelp() { new RemoteCloudUi(this).showMyBoxPatHelp(); }

    /** Entry from a storage connection: SFTP opens the login home, not '/'.
     * Navigation to '/' via the breadcrumb remains explicit and unchanged. */
    private void openRemoteStart(Models.RemoteProfile profile) {
        if (profile == null) { showRemoteList(); return; }
        if(!EditionMonetization.full(this)){
            try{if(!store.remoteAllowed(profile.id)){EditionMonetization.showUpgrade(this);return;}}
            catch(Exception e){showError(safeError(e));return;}
        }
        if (!RemoteProvider.sftpUsesLoginHome(profile)) {
            sftpHomeDiscoveryPendingId = "";
            openRemote(profile, "/");
            return;
        }
        String cacheKey = profile.id + "|" + profile.host + "|" + profile.port +
                "|" + profile.username;
        String cached = sftpLoginHomes.get(cacheKey);
        if (cached != null) {
            sftpHomeDiscoveryPendingId = "";
            openRemote(profile, cached);
            return;
        }
        remote = profile;
        remoteFolder = "/";
        sftpHomeDiscoveryPendingId = profile.id;
        currentScreen = "remote";
        render(profile.label(), true, "⋮", this::remoteFolderActions);
        muted(L10n.t("SFTP 로그인 계정의 홈 폴더를 확인하는 중..."));
        final int screen = renderGeneration;
        runFolderJob(screen,L10n.t("SFTP 홈 디렉터리 확인 중..."), () ->
                RemoteProvider.sftpLoginHome(profile), path -> {
            if (renderGeneration != screen || remote != profile ||
                    !profile.id.equals(sftpHomeDiscoveryPendingId)) return;
            sftpLoginHomes.put(cacheKey, path);
            sftpHomeDiscoveryPendingId = "";
            openRemote(profile, path);
        });
    }

    private void openRemote(Models.RemoteProfile profile, String folder) {
        if (profile == null) { showRemoteList(); return; }
        if(!EditionMonetization.full(this)){
            try{if(!store.remoteAllowed(profile.id)){EditionMonetization.showUpgrade(this);return;}}
            catch(Exception e){showError(safeError(e));return;}
        }
        remote = profile;
        remoteFolder = RemoteProvider.relativePath(folder);
        final String requestedFolder = remoteFolder;
        currentScreen = "remote";
        render(profile.label(), true, "⋮", this::remoteFolderActions);
        showRemoteBreadcrumb(profile, remoteFolder);
        final int screen = renderGeneration;
        if ("WebDAV".equals(profile.protocol) && "/".equals(remoteFolder)) {
            TextView quotaLabel = muted(L10n.t("서버 저장공간 확인 중..."));
            worker.execute(() -> {
                WebDavQuota quota = null;
                try { quota = RemoteProvider.webdavQuota(profile); }
                catch (Exception ignored) { /* Unknown remote quota. */ }
                final WebDavQuota result = quota;
                runOnUiThread(() -> {
                    if (renderGeneration != screen || quotaLabel.getParent() == null)
                        return;
                    if (result == null) page.removeView(quotaLabel);
                    else {
                        explorerQuota=WebDavQuota.summary(result);
                        quotaLabel.setText(explorerQuota);
                        if(flutterExplorerSurface!=null)flutterExplorerSurface.refresh();
                    }
                });
            });
        }
        List<Models.Entry> cached = folderCache.peek(profile, requestedFolder);
        if (cached != null) {
            // Returning to the same folder needs neither a loading screen
            // nor an additional network request until explicit refresh.
            showRemoteContents(profile, requestedFolder, cached);
            return;
        }
        TextView loading = muted(L10n.t("서버 폴더를 불러오는 중..."));
        runFolderJob(screen,L10n.t("네트워크 폴더 연결 중..."), () ->
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
        explorerCrumbs=new ArrayList<>(steps);
        explorerCrumbAction=navigate;
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
            segment.setContentDescription(L10n.t("폴더 이동: ") +
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
        TextView loading=muted(L10n.t("파일 목록을 정리하는 중..."));
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
                if(sorted.isEmpty())muted(L10n.t("이 폴더는 비어 있습니다."));
                indexFullFolderSearch(sorted,file->()->{
                    if(file.symbolicLink && file.linkTargetUnavailable) {
                        showError(L10n.t("심볼릭 링크가 가리키는 대상에 접근할 수 없습니다. ") +
                                "끊어진 링크 또는 서버 권한을 확인하세요.");
                        return;
                    }
                    if(file.directory)openRemote(p,file.path);
                    else if(isVideo(file.name))openRemoteVideo(p,file);
                    else openRemoteFile(p,file);
                });
                appendDirectoryPage(sorted,0,screen,file->
                        fileRow(file,()->{
                            if(file.symbolicLink && file.linkTargetUnavailable) {
                                showError(L10n.t("심볼릭 링크가 가리키는 대상에 접근할 수 없습니다. ") +
                                        "끊어진 링크 또는 서버 권한을 확인하세요.");
                                return;
                            }
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
            new AlertDialog.Builder(this).setTitle(L10n.t("클라우드 폴더"))
                    .setItems(new String[]{L10n.t("즐겨찾기 추가/해제"), L10n.t("새로고침"),
                                    L10n.t("폴더 용도 설정")},
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
        new AlertDialog.Builder(this).setTitle(L10n.t("폴더 작업"))
                .setItems(new String[]{L10n.t("파일 업로드"), L10n.t("새로 만들기"), L10n.t("즐겨찾기 추가/해제"),
                        L10n.t("새로고침"), L10n.t("폴더 용도 설정")}, (dialog, which) -> {
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
        name.setHint(L10n.t("새 폴더 이름"));
        new AlertDialog.Builder(this).setTitle(L10n.t("새 폴더"))
                .setView(name).setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("만들기"), (d, i) -> {
                    try {
                        String path = RemoteProvider.child(remoteFolder,
                                name.getText().toString().trim());
                        Models.RemoteProfile target = remote;
                        runJob(L10n.t("폴더 생성 중..."), () -> {
                            RemoteProvider.mkdir(target, path); return true;
                        }, ignored -> refreshRemote(target, remoteFolder));
                    } catch (Exception e) { failure(e); }
                }).show();
    }
    private void openRemoteFile(Models.RemoteProfile p, Models.Entry entry) {
        if (!BuildConfig.DIRECT_EDITION && ExternalApps.rar(entry.name)) {
            new AlertDialog.Builder(this)
                    .setTitle(L10n.t("외부 압축 앱으로 열기"))
                    .setMessage(L10n.t("원격 압축 파일을 임시로 내려받은 뒤 외부 앱에서 엽니다."))
                    .setNegativeButton(L10n.t("취소"),null)
                    .setPositiveButton(L10n.t("열기"),(d,w) ->
                        runJob(L10n.t("파일을 여는 중..."),
                                () -> ExternalApps.stageArchive(this,p,entry),
                                local -> ExternalApps.openArchive(this,Uri.fromFile(local),entry.name)))
                    .show();
            return;
        }
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
        runJob(L10n.t("파일을 여는 중..."), () -> {
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
    private void stageApkAndOfferInstall(String name, Job<InputStream> source) {
        apkInstaller.stage(name, source);
    }

    private void downloadRemoteEntry(Models.RemoteProfile profile, Models.Entry file,
                                     OutputStream output) throws Exception {
        RemoteProvider.download(profile, file.path, output);
    }
    private void downloadRemote(Models.RemoteProfile p, Models.Entry entry) {
        runJob(L10n.t("다운로드 중..."), () -> {
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
        if(useFlutterFileChrome()){
            final String folder=remoteFolder;
            showFilePrompt(L10n.t("이름 변경"),"",entry.name,L10n.t("변경"),value->{
                String next=value.trim();
                if(next.equals(entry.name))return;
                if(!validateOperationProfiles(Collections.singletonList(FileTransferEngine.remote(p,entry)),null,true))return;
                try{
                    String newPath=RemoteProvider.child(RemoteProvider.parent(entry.path),next);
                    runJob(L10n.t("이름 변경 중..."),()->{
                        RemoteProvider.rename(p,entry.path,newPath,entry.directory);return true;
                    },ignored->refreshRemote(p,folder));
                }catch(Exception error){failure(error);}
            });
            return;
        }
        EditText name = new EditText(this);
        name.setSingleLine();
        name.setText(entry.name);
        name.selectAll();
        new AlertDialog.Builder(this).setTitle(L10n.t("이름 변경"))
                .setView(name).setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("변경"), (d, i) -> {
                    try {
                        String newPath = RemoteProvider.child(
                                RemoteProvider.parent(entry.path),
                                name.getText().toString().trim());
                        runJob(L10n.t("이름 변경 중..."), () -> {
                            RemoteProvider.rename(p, entry.path, newPath, entry.directory);
                            return true;
                        }, ignored -> refreshRemote(p, remoteFolder));
                    } catch (Exception e) { failure(e); }
                }).show();
    }
    private void deleteRemote(Models.RemoteProfile p, Models.Entry entry) {
        new AlertDialog.Builder(this).setTitle(L10n.t("삭제 확인"))
                .setMessage(L10n.f("서버의 ‘%1$s’을(를) 삭제할까요?\n이 작업은 되돌릴 수 없을 수 있습니다.",entry.name))
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("삭제"), (d, i) ->
                        runJob(L10n.t("네트워크 파일 삭제 중..."), () -> {
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
        if(requestCode==TRASH_REQUEST){if(resultCode==RESULT_OK){folderCache.clearCounts();refreshCurrent();}return;}
        if(requestCode==OneDriveConnectActivity.REQUEST){
            if(embeddedRemoteEditor!=null){
                try{embeddedRemoteEditor.googleActivityResult(requestCode,resultCode);}
                catch(RuntimeException e){OneDriveDiagnostics.mark(this,"OD28_EDITOR_RESULT",e);}
            }
            if(resultCode==RESULT_OK){
                // The original overlay may have been detached while the browser
                // was in the foreground. Never depend on its close callback.
                try{
                    if(embeddedRemoteEditor!=null)closeEmbeddedEditor(true);
                    folderCache.clearCounts();showRemoteList();
                    OneDriveDiagnostics.mark(this,"OD29_LIST_REFRESH",null);
                    toast(L10n.t("연결 설정을 저장했습니다."));
                }catch(RuntimeException e){
                    OneDriveDiagnostics.mark(this,"OD30_LIST_REFRESH_FAILED",e);
                    toast(L10n.t("OneDrive 연결을 다시 확인해 주세요."));
                }
            }
            return;
        }
        if(requestCode==GoogleDriveConnectActivity.REQUEST){
            if(embeddedRemoteEditor!=null&&embeddedRemoteEditor.googleActivityResult(requestCode,resultCode))return;
            if(resultCode==RESULT_OK){folderCache.clearCounts();showRemoteList();}
            return;
        }
        if(SettingsContent.handlesResult(requestCode)){
            if(settingsContent!=null)settingsContent.onActivityResult(requestCode,resultCode,data);
            return;
        }
        if(requestCode==FlutterUiContract.REQUEST_EDITOR) {
            if(resultCode==RESULT_OK && data!=null) {
                String action=data.getStringExtra(FlutterUiContract.ACTION);
                if("saved".equals(action)) {
                    folderCache.clearCounts();
                    showRemoteList();
                    toast(L10n.t("연결 설정을 저장했습니다."));
                } else if("classic".equals(action)) {
                    String id=data.getStringExtra(FlutterUiContract.PROFILE_ID);
                    try {
                        if(id==null)nativeRemoteForm(null);
                        else for(Models.RemoteProfile p:store.activeRemotes())
                            if(id.equals(p.id)){nativeRemoteForm(p);break;}
                    } catch(Exception error){failure(error);}
                }
            }
            return;
        }
        if (requestCode == FlutterUiContract.REQUEST_CONNECTIONS) {
            if (resultCode == RESULT_OK && data != null) handleFlutterConnectionAction(data);
            return;
        }
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
        if (apkInstaller.handleResult(requestCode)) return;
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
        if (requestCode == REQ_SD || requestCode == REQ_FOLDER) {
            try {
                if (requestCode == REQ_SD) {
                    StorageVolume currentCard = sdVolume();
                    if (currentCard == null) {
                        showError(L10n.t("SD카드가 제거되었거나 연결이 끊겼습니다. ") +
                                "카드 장착 상태를 확인해 주세요.");
                        return;
                    }
                    String treeId = DocumentsContract.getTreeDocumentId(chosen);
                    if (!SdAccessPolicy.validSdDocumentId(chosen.getAuthority(),
                            treeId, currentCard.getUuid())) {
                        showError(L10n.t("선택한 위치가 현재 SD카드의 폴더가 아닙니다. ") +
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
                    showError(L10n.t("선택한 SD/저장소 폴더의 읽기 권한을 받지 못했습니다. ") +
                            "폴더를 다시 선택해 주세요.");
                    return;
                }
                getContentResolver().takePersistableUriPermission(chosen, grant);
                if (!hasPersistedTreeRead(chosen)) {
                    showError(L10n.t("폴더 권한을 영구 저장하지 못했습니다. ") +
                            "폴더 선택 화면에서 '사용 허용'을 확인해 주세요.");
                    return;
                }
                DocumentFile selectedRoot = DocumentFile.fromTreeUri(this, chosen);
                if (selectedRoot == null || !selectedRoot.canRead()) {
                    showError(L10n.t("폴더가 선택됐지만 읽기 권한이 없습니다. ") +
                            "다른 폴더를 선택하거나 전체 파일 접근 권한을 허용해 주세요.");
                    return;
                }
                activeTreeUri = chosen.toString();
                if (requestCode == REQ_SD) {
                    store.sdTreeUri(chosen.toString());
                    activeTreeLabel = selectedRoot.canWrite() ?
                            "SD 저장소 (선택 폴더)" : "SD 저장소 (읽기 전용)";
                } else {
                    store.selectedTreeUri(chosen.toString());
                    activeTreeLabel = "선택한 폴더";
                }
                openSafPath("/");
            } catch (Exception error) {
                showError(L10n.t("SD/저장소 폴더 권한을 저장하는 중 오류가 발생했습니다. ") +
                        "다시 선택하거나 전체 파일 접근 권한을 확인해 주세요. (" +
                        error.getClass().getSimpleName() + ")");
            }
        } else if (requestCode == REQ_UPLOAD && remote != null) {
            final Models.RemoteProfile target = remote;
            final String destination = remoteFolder;
            final String display = documentName(chosen);
            final long byteLength = documentSize(chosen);
            runJob(L10n.t("대상 폴더에서 같은 이름 확인 중..."), () -> {
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
                        .setTitle(L10n.t("기존 파일 덮어쓰기"))
                        .setMessage(L10n.f("대상 폴더에 '%1$s' 파일이 이미 있습니다.\n\n기존 파일을 새 파일로 덮어쓸까요? 취소하면 기존 파일을 그대로 유지합니다.",display))
                        .setNegativeButton(L10n.t("취소"), null)
                        .setPositiveButton(L10n.t("덮어쓰기"), (dialog, which) ->
                                uploadSelectedDocument(chosen, target, destination,
                                        display, byteLength, true))
                        .show();
            });
        }
    }

    private void uploadSelectedDocument(Uri chosen, Models.RemoteProfile target,
                                        String destination, String display,
                                        long byteLength, boolean overwrite) {
            runJob(L10n.t("네트워크 저장소에 업로드 중..."), () -> {
                try (InputStream in = getContentResolver().openInputStream(chosen)) {
                    if (in == null) throw new IOException("선택한 파일을 읽을 수 없습니다.");
                    RemoteProvider.upload(target, destination, display, in, byteLength,
                            overwrite);
                }
                return true;
            }, result -> {
                toast(L10n.t("업로드를 완료했습니다."));
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
                showError(L10n.t("백그라운드 전송 진행률을 알림바에 표시하려면 알림 권한이 필요합니다."));
            }
            return;
        }
        if (requestCode == REQ_MEDIA && mediaKind != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                openMedia(mediaKind);
            else showError(L10n.t("선택한 미디어를 표시하려면 파일 접근 권한이 필요합니다."));
        } else if (requestCode == REQ_MEDIA && permissionPendingFile != null &&
                results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            File pending = permissionPendingFile;
            permissionPendingFile = null;
            browseLocal(pending);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        activityResumed=true;
        if (flutterBodySurface!=null) flutterBodySurface.resume();
        if (flutterDrawerSurface!=null) flutterDrawerSurface.resume();
        if (flutterTransferSurface!=null) flutterTransferSurface.resume();
        if (flutterFilePrompt!=null)flutterFilePrompt.resume();
        String language=L10n.language();
        if(!language.equals(renderedLanguage)){
            renderedLanguage=language;
            if(drawerShown)closeDrawer();
            refreshCurrent();
        }
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
                        .setTitle(L10n.t("SD카드 접근 권한"))
                        .setMessage(L10n.t("전체 파일 접근 권한이 허용되지 않았습니다.\n\n") +
                                "SD카드 전체 대신 특정 하위 폴더만 이용하려면 " +
                                "'SD 폴더 선택'을 누르세요. " +
                                "Android 11 이상에서는 SD카드 최상위 폴더를 " +
                                "선택할 수 없습니다.")
                        .setNegativeButton(L10n.t("닫기"), null)
                        .setPositiveButton(L10n.t("SD 폴더 선택"),
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

    void onEditionEntitlementChanged(){
        if(isFinishing()||isDestroyed()||store==null)return;
        try{
            if(remote!=null&&!store.remoteAllowed(remote.id)){showRemoteList();return;}
            if("settings".equals(currentScreen)&&settingsContent!=null)settingsContent.render();
            else if("home".equals(currentScreen)||"remotes".equals(currentScreen))
                refreshCurrent();
        }catch(Exception ignored){}
    }

    @Override protected void onPause(){
        activityResumed=false;
        if(bodyWaiting){
            screenHandoffHandler.removeCallbacks(screenHandoffTimeout);
            bodyDisplayed=false;
            if(flutterBodySurface==null||!flutterBodySurface.isActive())bodyWaiting=false;
            presentBodyLayer();
        }
        if (flutterBodySurface!=null) flutterBodySurface.pause();
        if (flutterDrawerSurface!=null) flutterDrawerSurface.pause();
        if (flutterTransferSurface!=null) flutterTransferSurface.pause();
        if (flutterFilePrompt!=null)flutterFilePrompt.pause();
        transferHandler.removeCallbacks(transferTicker);
        musicHandler.removeCallbacks(musicTicker);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTvPairIntent(intent);
        handleOneDriveReturn(intent);
        if (intent != null && intent.getBooleanExtra(FileTransferService.EXTRA_SHOW, false)) {
            intent.removeExtra(FileTransferService.EXTRA_SHOW);
            showTransferDialog();
        }
    }

    @Override public void onBackPressed() {
        if(embeddedRemoteEditor!=null){
            if(!embeddedRemoteEditor.isBusy()&&flutterBodySurface!=null)
                flutterBodySurface.push("editorBack",embeddedEditorToken);
            return;
        }
        if (drawerShown) {
            closeDrawer();
            return;
        }
        if("settings".equals(currentScreen)){
            currentScreen=settingsReturnScreen;
            settingsReturnScreen="home";settingsScope=null;
            refreshCurrent();return;
        }
        if (!selection.isEmpty()) {
            selection.clear();
            updateFileOperationChrome();
            return;
        }
        if (backFromAssignedEntry()) return;
        switch (currentScreen) {
            case "remote":
                if (remoteFolder.equals("/")) {
                    showRemoteList();
                }
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
