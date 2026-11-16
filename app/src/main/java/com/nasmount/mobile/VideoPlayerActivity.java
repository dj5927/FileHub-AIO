package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.util.Rational;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleSourceKt;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Integrated Media3 player: remote video is read incrementally, not downloaded. */
@androidx.media3.common.util.UnstableApi
public final class VideoPlayerActivity extends Activity {
    private static final String PIP_PREFS="video_popup_playback";
    private static final String PIP_AUTO_ON_HOME="auto_on_home";
    private static final String ACTION_PIP_TOGGLE=
            "com.nasmount.mobile.action.PIP_TOGGLE";
    private static WeakReference<VideoPlayerActivity> currentPipOwner=
            new WeakReference<>(null);
    static final String EXTRA_PROFILE_ID = "profileId";
    static final String EXTRA_REMOTE_PATH = "remotePath";
    static final String EXTRA_REMOTE_SIZE = "remoteSize";
    static final String EXTRA_LOCAL_URI = "localUri";
    static final String EXTRA_FOLDER_SCOPE = "sourceFolderScope";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_EPISODE_PATHS = "episodePaths";
    static final String EXTRA_EPISODE_NAMES = "episodeNames";
    static final String EXTRA_EPISODE_SIZES = "episodeSizes";
    static final String EXTRA_EPISODE_INDEX = "episodeIndex";
    private static final int REQUEST_SUBTITLE = 611;
    private static final long MAX_SUBTITLE = 2L * 1024 * 1024;
    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private ExoPlayer player;
    private Models.RemoteProfile profile;
    private String remotePath;
    private String displayName;
    private Uri video;
    private TextView subtitlesButton;
    private TextView audioInfoButton;
    private Format activeAudioInputFormat;
    private String activeAudioDecoderName;
    private TextView skipHint;
    private TextView lockHint;
    private TextView lockButton;
    private TextView unlockButton;
    private TextView rotateButton;
    private TextView rotateModeLabel;
    private TextView audioCodecLabel;
    private TextView subtitleModeLabel;
    private TextView speedValueButton;
    private ArrayList<String> episodePaths;
    private ArrayList<String> episodeNames;
    private long[] episodeSizes;
    private int episodeIndex = -1;
    private boolean nextEpisodePromptShown;
    private View previousEpisodeButton;
    private View nextEpisodeButton;
    private LinearLayout leftToolsBar;
    private LinearLayout rightToolsBar;
    private FrameLayout playerRoot;
    private int orientationMode = PlaybackControlPolicy.AUTO;
    private Boolean detectedVideoLandscape;
    private int playbackSpeedTenths = PlaybackControlPolicy.DEFAULT_SPEED_TENTHS;
    private TextView swipeHint;
    private LinearLayout topBar;
    private PlayerView videoView;
    private TextView popupButton;
    private boolean popupUiActive;
    private int pipAspectWidth=16,pipAspectHeight=9;
    private DataSource.Factory dataFactory;
    private AudioManager audioManager;
    private final VideoSwipePolicy swipePolicy = new VideoSwipePolicy();
    private int maxVolume;
    private int startingVolume;
    private float startingBrightness;
    private boolean swipeCaptured;
    private boolean controlsVisible = true;
    private boolean chromeInController;
    private View bottomMediaBar;
    private final ViewTreeObserver.OnPreDrawListener synchronizeChromeWithSeekBar = () -> {
        if (!chromeInController || bottomMediaBar == null || isVideoTouchLocked())
            return true;
        // Media3 animates exo_bottom_bar to alpha=0 first, but reports the
        // exo_controller itself GONE only later. Match the *visible seek bar*
        // alpha every animation frame instead of waiting for that late event.
        float shownAlpha = bottomMediaBar.getVisibility() == View.VISIBLE
                ? bottomMediaBar.getAlpha() : 0f;
        if (topBar != null && topBar.getVisibility() == View.VISIBLE &&
                topBar.getAlpha() != shownAlpha)
            topBar.setAlpha(shownAlpha);
        if (lockButton != null && lockButton.getVisibility() == View.VISIBLE &&
                lockButton.getAlpha() != shownAlpha)
            lockButton.setAlpha(shownAlpha);
        if (leftToolsBar != null && leftToolsBar.getVisibility() == View.VISIBLE &&
                leftToolsBar.getAlpha() != shownAlpha)
            leftToolsBar.setAlpha(shownAlpha);
        if (rightToolsBar != null && rightToolsBar.getVisibility() == View.VISIBLE &&
                rightToolsBar.getAlpha() != shownAlpha)
            rightToolsBar.setAlpha(shownAlpha);
        return true;
    };
    private String progressId;
    private String folderSettingsScope="";
    private long previouslyWatchedMs = -1;
    private boolean resumeDecisionMade = true;
    private boolean resumePromptShown;
    private final Handler watchHandler = new Handler(Looper.getMainLooper());
    private final PlaybackEndGuard playbackEndGuard = new PlaybackEndGuard();
    private final Runnable monitorActualVideoEnd = new Runnable() {
        @Override public void run() {
            if (player == null || isFinishing() || isDestroyed()) return;
            checkVideoEnd();
            if (!playbackEndGuard.handled())
                watchHandler.postDelayed(this, PlaybackEndGuard.CONFIRM_WINDOW_MS);
        }
    };
    private final Runnable saveWatchTick = new Runnable() {
        @Override public void run() {
            saveWatchPosition();
            watchHandler.postDelayed(this, 5000L);
        }
    };
    private final Runnable hideSwipeHint = () -> {
        if (swipeHint != null) swipeHint.setVisibility(View.GONE);
    };
    private final List<MediaItem.SubtitleConfiguration> subtitleTracks = new ArrayList<>();
    private int subtitleSourceMode = SubtitleSourcePolicy.AUTO;
    private String selectedEmbeddedTrackKey;
    private boolean manualExternalFileChosen;
    private final TouchHoldLock touchLock = new TouchHoldLock();
    private boolean isVideoTouchLocked() { return touchLock.isLocked(); }
    private final Handler touchHandler = new Handler(Looper.getMainLooper());
    private float holdMovementTolerance;
    private boolean userSelectedOrientation;
    private boolean lockButtonGesture;
    private final Runnable hideUnlockButton = () -> {
        if (unlockButton != null && touchLock.isLocked())
            unlockButton.setVisibility(View.GONE);
    };
    private final Runnable hideLockFeedback = () -> {
        if (lockHint != null) lockHint.setVisibility(View.GONE);
    };
    private final Runnable completeHold = () -> {
        if (!touchLock.toggleIfHeld(SystemClock.uptimeMillis())) return;
        applyLockState();
        if (touchLock.isLocked()) {
            // Cancel a control that may have received the original down event.
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now,
                    MotionEvent.ACTION_CANCEL, 0, 0, 0);
            try {
                VideoPlayerActivity.super.dispatchTouchEvent(cancel);
            } finally {
                cancel.recycle();
            }
        }
    };

    @Override protected void onCreate(Bundle state) {
        setTheme(AppearanceSettings.isDark(this) ?
                R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        currentPipOwner=new WeakReference<>(this);
        folderSettingsScope=FolderMediaSettings.video(this,getIntent());
        // Start portrait until Media3 reports the *video* dimensions.
        // Avoid forcibly rotating Shorts and other portrait videos.
        setRequestedOrientation(TvMode.isTv(this) ?
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE :
                FoldPlayerLayoutPolicy.isExpanded(
                getResources().getConfiguration().smallestScreenWidthDp,
                getResources().getConfiguration().screenWidthDp,
                getResources().getConfiguration().screenHeightDp) ?
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED :
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        holdMovementTolerance = ViewConfiguration.get(this).getScaledTouchSlop() * 1.5f;
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        maxVolume = audioManager == null ? 15 :
                Math.max(1, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        FrameLayout root = new FrameLayout(this);
        playerRoot = root;
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        hideSystemBars();
        videoView = new PlayerView(this);
        videoView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        videoView.setControllerShowTimeoutMs(4000);
        videoView.setControllerHideOnTouch(true);
        videoView.setFullscreenButtonClickListener(fullscreen ->
                setOrientationMode(fullscreen ?
                        PlaybackControlPolicy.LANDSCAPE : PlaybackControlPolicy.AUTO));
        int resize = PlayerSettings.ratio(this,folderSettingsScope);
        videoView.setResizeMode(resize == PlayerSettings.ZOOM ?
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM :
                resize == PlayerSettings.STRETCH ?
                        AspectRatioFrameLayout.RESIZE_MODE_FILL :
                        AspectRatioFrameLayout.RESIZE_MODE_FIT);
        root.addView(videoView, new FrameLayout.LayoutParams(-1, -1));
        topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(0x910B0F16);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(-1, dp(49),
                Gravity.TOP);
        topParams.topMargin = dp(10);
        root.addView(topBar, topParams);
        TextView back = button("‹", 28);
        back.setGravity(Gravity.CENTER);
        topBar.addView(back, new LinearLayout.LayoutParams(dp(48), -1));
        back.setOnClickListener(v -> finish());
        displayName = getIntent().getStringExtra(EXTRA_TITLE);
        if (displayName == null) displayName = "동영상";
        readEpisodeQueue();
        TextView title = button(displayName, 15);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        topBar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));
        popupButton=button("▣",23);
        popupButton.setGravity(Gravity.CENTER);
        popupButton.setContentDescription("팝업 재생으로 전환하거나 홈으로 나가기");
        topBar.addView(popupButton,
                new LinearLayout.LayoutParams(dp(48),-1));
        popupButton.setOnClickListener(v->confirmPopupOrLeave());
        TextView more = button("⋯", 26);
        more.setGravity(Gravity.CENTER);
        more.setContentDescription("동영상 재생 추가 설정");
        topBar.addView(more, new LinearLayout.LayoutParams(dp(48), -1));
        more.setOnClickListener(v -> showPlayerQuickMenu());

        // Both side tool rails belong to the Media3 controller hierarchy:
        // they appear/disappear with the seek bar, not two seconds later.
        buildSideToolRails();
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(
                dp(68), -2, Gravity.START | Gravity.CENTER_VERTICAL);
        leftParams.leftMargin = dp(13);
        FrameLayout.LayoutParams rightParams = new FrameLayout.LayoutParams(
                dp(69), -2, Gravity.END | Gravity.CENTER_VERTICAL);
        rightParams.rightMargin = dp(13);
        root.addView(leftToolsBar, leftParams);
        root.addView(rightToolsBar, rightParams);
        skipHint = button("", 23);
        skipHint.setVisibility(View.GONE);
        skipHint.setGravity(Gravity.CENTER);
        skipHint.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams skipParams = new FrameLayout.LayoutParams(
                dp(115), dp(52), Gravity.CENTER);
        root.addView(skipHint, skipParams);
        lockHint = button("", 14);
        lockHint.setGravity(Gravity.CENTER);
        lockHint.setPadding(dp(18), 0, dp(18), 0);
        lockHint.setBackgroundColor(0xC5222528);
        lockHint.setVisibility(View.GONE);
        lockHint.setClickable(false);
        FrameLayout.LayoutParams lockParams = new FrameLayout.LayoutParams(
                -2, dp(43), Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lockParams.topMargin = dp(84);
        root.addView(lockHint, lockParams);
        lockButton = button("🔒", 22);
        lockButton.setTextColor(Color.WHITE);
        lockButton.setGravity(Gravity.CENTER);
        lockButton.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable lockBackground =
                new android.graphics.drawable.GradientDrawable();
        lockBackground.setColor(0xD020252B);
        lockBackground.setCornerRadius(dp(12));
        lockButton.setBackground(lockBackground);
        lockButton.setContentDescription("동영상 터치 잠금 또는 해제");
        FrameLayout.LayoutParams buttonParams = new FrameLayout.LayoutParams(
                dp(46), dp(46), Gravity.START | Gravity.BOTTOM);
        buttonParams.leftMargin = dp(14);
        buttonParams.bottomMargin = dp(10);
        root.addView(lockButton, buttonParams);
        lockButton.setOnClickListener(v -> {
            touchHandler.removeCallbacks(completeHold);
            touchLock.toggleFromButton();
            applyLockState();
        });
        // Attach the title bar and lock button to the *same Media3 controller
        // view hierarchy* as the bottom seek bar. This fixes the delay caused
        // by observing the final GONE callback after the built-in fade.
        ViewGroup mediaController = videoView.findViewById(
                androidx.media3.ui.R.id.exo_controller);
        if (mediaController != null) {
            // Media3's default fade leaves the progress-only state visible for
            // an extra two seconds. Disable that intermediate phase so the
            // top bar, side rails, bottom bar and lock all hide together.
            if (mediaController instanceof androidx.media3.ui.PlayerControlView)
                ((androidx.media3.ui.PlayerControlView) mediaController)
                        .setAnimationEnabled(false);
            root.removeView(topBar);
            root.removeView(lockButton);
            root.removeView(leftToolsBar);
            root.removeView(rightToolsBar);
            mediaController.addView(topBar, topParams);
            mediaController.addView(lockButton, buttonParams);
            mediaController.addView(leftToolsBar, leftParams);
            mediaController.addView(rightToolsBar, rightParams);
            chromeInController = true;
            bottomMediaBar = videoView.findViewById(
                    androidx.media3.ui.R.id.exo_bottom_bar);
            if (bottomMediaBar != null) {
                videoView.getViewTreeObserver().addOnPreDrawListener(
                        synchronizeChromeWithSeekBar);
            }
        }
        unlockButton = button("🔓 잠금 해제", 15);
        unlockButton.setGravity(Gravity.CENTER);
        unlockButton.setContentDescription("화면 잠금 해제");
        unlockButton.setPadding(dp(14), 0, dp(14), 0);
        unlockButton.setBackground(lockBackground.getConstantState().newDrawable().mutate());
        unlockButton.setVisibility(View.GONE);
        FrameLayout.LayoutParams unlockParams = new FrameLayout.LayoutParams(
                -2, dp(48), Gravity.START | Gravity.BOTTOM);
        unlockParams.leftMargin = dp(15);
        unlockParams.bottomMargin = dp(12);
        root.addView(unlockButton, unlockParams);
        unlockButton.setOnClickListener(v -> {
            touchHandler.removeCallbacks(completeHold);
            touchLock.toggleFromButton();
            applyLockState();
        });
        videoView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visible -> {
            controlsVisible = visible == View.VISIBLE;
            if (!chromeInController) updateChromeVisibility(controlsVisible);
            hideSystemBars();
        });
        root.addOnLayoutChangeListener((view, left, top, right, bottom,
                                        oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft ||
                    bottom - top != oldBottom - oldTop)
                applyAdaptivePlayerLayout();
        });
        root.post(this::applyAdaptivePlayerLayout);
        swipeHint = button("", 17);
        swipeHint.setGravity(Gravity.CENTER);
        swipeHint.setPadding(dp(22), dp(7), dp(22), dp(7));
        swipeHint.setBackgroundColor(0xCE20252B);
        swipeHint.setClickable(false);
        swipeHint.setVisibility(View.GONE);
        root.addView(swipeHint, new FrameLayout.LayoutParams(
                -2, dp(53), Gravity.CENTER));
        tapDetector = new GestureDetectorHolder().create();
        videoView.setOnTouchListener((v, event) -> {
            tapDetector.onTouchEvent(event);
            return false; // Let Media3 manage single taps, scrubbing and controls.
        });
        applySubtitleTypeface();
        try {
            String id = getIntent().getStringExtra(EXTRA_PROFILE_ID);
            if (id != null) {
                for (Models.RemoteProfile candidate :
                        new SecureStore(this).activeRemotes()) {
                    if (id.equals(candidate.id)) { profile = candidate; break; }
                }
                if (profile == null) throw new IOException("원격 연결을 찾을 수 없습니다.");
                remotePath = RemoteProvider.relativePath(
                        getIntent().getStringExtra(EXTRA_REMOTE_PATH));
                long size = getIntent().getLongExtra(EXTRA_REMOTE_SIZE, C.LENGTH_UNSET);
                progressId = PlaybackProgressStore.idFor("remote:" + profile.id +
                        ":" + remotePath + ":" + size);
                video = Uri.parse("nasmount://video/" + Uri.encode(displayName));
                dataFactory = new DefaultDataSource.Factory(this,
                        new VideoStreamDataSource.Factory(profile, remotePath, size));
            } else {
                String local = getIntent().getStringExtra(EXTRA_LOCAL_URI);
                if (local == null) throw new IOException("영상 위치가 없습니다.");
                progressId = PlaybackProgressStore.idFor("local:" + local);
                video = Uri.parse(local);
                dataFactory = new DefaultDataSource.Factory(this);
            }
            previouslyWatchedMs = PlaybackProgressStore.resumePosition(this, progressId);
            resumeDecisionMade = previouslyWatchedMs < 0;
            subtitleSourceMode = PlayerSettings.videoSubtitleSource(
                    this, progressId,folderSettingsScope);
            applySubtitleTypeface();
            // Hardware decoding first, bundled FFmpeg if no system decoder is
            // able to render this codec (E-AC3, TrueHD, DTS, etc).
            NextRenderersFactory renderers = new NextRenderersFactory(this);
            renderers.setExtensionRendererMode(
                    DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
            DefaultMediaSourceFactory mediaSources =
                    new DefaultMediaSourceFactory(
                            this, new FfmpegSubtitleExtractorsFactory())
                            .setDataSourceFactory(dataFactory);
            MediaSource.Factory subtitlesAndMedia =
                    FfmpegSubtitleSourceKt.withFfmpegSubtitles(mediaSources, dataFactory);
            player = new ExoPlayer.Builder(this, renderers)
                    .setMediaSourceFactory(subtitlesAndMedia)
                    .build();
            player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                    .buildUpon()
                    .setPreferredTextLanguage(PlayerSettings.defaultSubtitleLanguage(this))
                    .setSelectUndeterminedTextLanguage(false)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,
                            subtitleSourceMode == SubtitleSourcePolicy.OFF)
                    .build());
            videoView.setPlayer(player);
            installEpisodeControls();
            player.addAnalyticsListener(new AnalyticsListener() {
                @Override public void onAudioInputFormatChanged(
                        EventTime eventTime, Format format,
                        DecoderReuseEvaluation decoderReuseEvaluation) {
                    // Format is emitted by the actual audio renderer, not
                    // inferred from the filename or container extension.
                    activeAudioInputFormat = format;
                    if (decoderReuseEvaluation == null ||
                            decoderReuseEvaluation.result ==
                                    DecoderReuseEvaluation.REUSE_RESULT_NO) {
                        activeAudioDecoderName = null;
                    }
                    updateAudioCodecButton();
                }

                @Override public void onAudioDecoderInitialized(
                        EventTime eventTime, String decoderName,
                        long initializedTimestampMs, long initializationDurationMs) {
                    // MediaCodec reports c2./OMX.; NextLib reports ffmpeg*.
                    // Never infer which decoder is active from its presence.
                    activeAudioDecoderName = decoderName;
                    updateAudioCodecButton();
                }

                @Override public void onAudioDisabled(
                        EventTime eventTime, DecoderCounters decoderCounters) {
                    activeAudioDecoderName = null;
                    activeAudioInputFormat = null;
                    updateAudioCodecButton();
                }
            });
            player.addListener(new Player.Listener() {
                @Override public void onTracksChanged(Tracks tracks) {
                    // MediaItem-added sidecars carry an explicit stable ID and
                    // label. Counting groups is unsafe: one embedded track
                    // plus one external track can otherwise look ambiguous.
                    for (Tracks.Group group : tracks.getGroups()) {
                        if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                        for (int i = 0; i < group.length; i++) {
                            if (!isExternalSubtitle(group.getTrackFormat(i)) &&
                                    progressId != null) {
                                SubtitleInventory.record(
                                        VideoPlayerActivity.this, progressId, true);
                                break;
                            }
                        }
                    }
                    applySubtitleSourceSelection(tracks);
                    updateSubtitleButton();
                    updateAudioCodecButton();
                }
                @Override public void onPlaybackStateChanged(int state) {
                    refreshPictureInPictureParams();
                    updateAudioCodecButton();
                    if (state == Player.STATE_READY && !resumeDecisionMade &&
                            !resumePromptShown) {
                        resumePromptShown = true;
                        showResumePrompt();
                    } else if (state == Player.STATE_ENDED && resumeDecisionMade) {
                        checkVideoEnd();
                    }
                }
                @Override public void onVideoSizeChanged(VideoSize size) {
                    int w=size.width,h=size.height;
                    if(size.unappliedRotationDegrees%180!=0){
                        int temp=w;w=h;h=temp;
                    }
                    int[] shape=VideoPipPolicy.aspect(
                            Math.max(1,Math.round(w*size.pixelWidthHeightRatio)),h);
                    pipAspectWidth=shape[0];
                    pipAspectHeight=shape[1];
                    refreshPictureInPictureParams();
                    Boolean landscape = VideoOrientationPolicy.isLandscape(
                            size.width, size.height, size.unappliedRotationDegrees,
                            size.pixelWidthHeightRatio);
                    detectedVideoLandscape = landscape;
                    // On a television, even portrait Shorts play within a
                    // landscape player surface instead of rotating the TV UI.
                    if (TvMode.isTv(VideoPlayerActivity.this)) return;
                    if (landscape == null || orientationMode != PlaybackControlPolicy.AUTO)
                        return;
                    int needed = isFoldExpanded() ?
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED :
                            (landscape ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE :
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    if (getRequestedOrientation() != needed)
                        setRequestedOrientation(needed);
                }
                @Override public void onPlayerError(PlaybackException problem) {
                    Toast.makeText(VideoPlayerActivity.this,
                            "재생 실패: 네트워크 또는 지원하지 않는 코덱 (" +
                                    problem.getErrorCodeName() + ")", Toast.LENGTH_LONG).show();
                }
                @Override public void onIsPlayingChanged(boolean isPlaying){
                    refreshPictureInPictureParams();
                }
            });
            // Hold initial playback when a stored viewing position exists.
            // Do not begin from zero while the user is choosing resume/start.
            reload(C.TIME_UNSET, resumeDecisionMade);
            if (profile != null) autoSubtitleRemote();
            else autoSubtitleLocal();
        } catch (Exception problem) {
            new AlertDialog.Builder(this).setTitle("동영상 재생 오류")
                    .setMessage(problem.getMessage())
                    .setPositiveButton("닫기", (d, w) -> finish()).show();
        }
    }

    private void readEpisodeQueue() {
        ArrayList<String> paths = getIntent().getStringArrayListExtra(EXTRA_EPISODE_PATHS);
        ArrayList<String> names = getIntent().getStringArrayListExtra(EXTRA_EPISODE_NAMES);
        long[] sizes = getIntent().getLongArrayExtra(EXTRA_EPISODE_SIZES);
        int index = getIntent().getIntExtra(EXTRA_EPISODE_INDEX, -1);
        String current = getIntent().hasExtra(EXTRA_PROFILE_ID) ?
                getIntent().getStringExtra(EXTRA_REMOTE_PATH) :
                getIntent().getStringExtra(EXTRA_LOCAL_URI);
        if (paths == null || names == null || sizes == null ||
                paths.size() < 2 || paths.size() > VideoEpisodeQueue.MAX_TRANSFER ||
                paths.size() != names.size() || paths.size() != sizes.length ||
                index < 0 || index >= paths.size() ||
                !paths.get(index).equals(current))
            return;
        episodePaths = paths;
        episodeNames = names;
        episodeSizes = sizes;
        episodeIndex = index;
    }

    private boolean hasAdjacentEpisode(int step) {
        return episodePaths != null && episodeIndex + step >= 0 &&
                episodeIndex + step < episodePaths.size();
    }

    private void playAdjacentEpisode(int step) {
        if (!hasAdjacentEpisode(step) || isFinishing() || isDestroyed()) return;
        int target = episodeIndex + step;
        Intent videoIntent = new Intent(this, VideoPlayerActivity.class);
        String path = episodePaths.get(target);
        if (profile != null) {
            videoIntent.putExtra(EXTRA_PROFILE_ID, profile.id);
            videoIntent.putExtra(EXTRA_REMOTE_PATH, path);
            videoIntent.putExtra(EXTRA_REMOTE_SIZE, episodeSizes[target]);
        } else {
            videoIntent.putExtra(EXTRA_LOCAL_URI, path);
            videoIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        videoIntent.putExtra(EXTRA_TITLE, episodeNames.get(target));
        if(getIntent().hasExtra(EXTRA_FOLDER_SCOPE))
            videoIntent.putExtra(EXTRA_FOLDER_SCOPE,
                    getIntent().getStringExtra(EXTRA_FOLDER_SCOPE));
        videoIntent.putStringArrayListExtra(EXTRA_EPISODE_PATHS, episodePaths);
        videoIntent.putStringArrayListExtra(EXTRA_EPISODE_NAMES, episodeNames);
        videoIntent.putExtra(EXTRA_EPISODE_SIZES, episodeSizes);
        videoIntent.putExtra(EXTRA_EPISODE_INDEX, target);
        saveWatchPosition();
        startActivity(videoIntent);
        finish();
    }

    /**
     * Media3 disables the built-in exo_prev/next when the current MediaItem
     * is the only one in its timeline. Remote video uses a per-file streaming
     * DataSource, so replace those two *visual* buttons with controller-owned
     * sibling navigation that loads the next item through its own DataSource.
     * Keep the same drawable, size and position as the old controls.
     */
    private void installEpisodeControls() {
        if (episodePaths == null || videoView == null) return;
        previousEpisodeButton = replaceEpisodeControl(
                androidx.media3.ui.R.id.exo_prev, -1, "이전 영상 재생");
        nextEpisodeButton = replaceEpisodeControl(
                androidx.media3.ui.R.id.exo_next, 1, "다음 영상 재생");
        updateEpisodeControls();
    }

    private View replaceEpisodeControl(int controlId, int step, String description) {
        View original = videoView.findViewById(controlId);
        if (original == null || !(original.getParent() instanceof ViewGroup)) {
            // Fallback for an OEM controller layout with missing ExoPlayer IDs.
            TextView alternate = button(step < 0 ? "⏮" : "⏭", 23);
            alternate.setGravity(Gravity.CENTER);
            alternate.setContentDescription(description);
            topBar.addView(alternate, new LinearLayout.LayoutParams(dp(45), -1));
            alternate.setOnClickListener(v -> playAdjacentEpisode(step));
            return alternate;
        }
        ViewGroup host = (ViewGroup) original.getParent();
        int index = host.indexOfChild(original);
        ViewGroup.LayoutParams params = original.getLayoutParams();
        View replacement;
        if (original instanceof ImageView) {
            ImageView image = new ImageView(this);
            android.graphics.drawable.Drawable artwork =
                    ((ImageView) original).getDrawable();
            if (artwork != null && artwork.getConstantState() != null)
                image.setImageDrawable(artwork.getConstantState()
                        .newDrawable().mutate());
            else
                image.setImageResource(step < 0 ?
                        androidx.media3.ui.R.drawable.exo_icon_previous :
                        androidx.media3.ui.R.drawable.exo_icon_next);
            image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            image.setPadding(original.getPaddingLeft(), original.getPaddingTop(),
                    original.getPaddingRight(), original.getPaddingBottom());
            image.setImageTintList(
                    android.content.res.ColorStateList.valueOf(Color.WHITE));
            replacement = image;
        } else {
            TextView label = button(step < 0 ? "⏮" : "⏭", 23);
            label.setGravity(Gravity.CENTER);
            replacement = label;
        }
        replacement.setClickable(true);
        replacement.setFocusable(true);
        replacement.setContentDescription(description);
        host.removeView(original);
        host.addView(replacement, index, params);
        replacement.setOnClickListener(v -> playAdjacentEpisode(step));
        return replacement;
    }

    private void updateEpisodeControls() {
        if (previousEpisodeButton != null) {
            boolean available = hasAdjacentEpisode(-1);
            previousEpisodeButton.setEnabled(available);
            previousEpisodeButton.setAlpha(available ? 1f : 0.3f);
        }
        if (nextEpisodeButton != null) {
            boolean available = hasAdjacentEpisode(1);
            nextEpisodeButton.setEnabled(available);
            nextEpisodeButton.setAlpha(available ? 1f : 0.3f);
        }
    }

    private void promptNextEpisodeAfterEnd() {
        if (nextEpisodePromptShown || !hasAdjacentEpisode(1) ||
                isFinishing() || isDestroyed()) return;
        nextEpisodePromptShown = true;
        String nextName = episodeNames.get(episodeIndex + 1);
        new AlertDialog.Builder(this)
                .setTitle("다음 편을 재생할까요?")
                .setMessage("현재 영상이 끝났습니다.\n\n다음 영상:\n" +
                        nextName + "\n\n계속 재생하시겠습니까?")
                .setNegativeButton("아니요", null)
                .setPositiveButton("다음 편 재생", (dialog, which) ->
                        playAdjacentEpisode(1))
                .show();
    }

    /**
     * Media3 may stay READY or BUFFERING after video reaches its duration if
     * the subtitle renderer or network stream does not reach EOF promptly.
     * The duration watch closes this gap and prompts only once.
     */
    private void checkVideoEnd() {
        if (player == null) return;
        if (!playbackEndGuard.sample(player.getPlaybackState(),
                player.getPlayWhenReady(), resumeDecisionMade,
                player.getCurrentPosition(), player.getDuration(),
                SystemClock.elapsedRealtime())) return;
        player.pause();
        PlaybackProgressStore.clear(this, progressId);
        promptNextEpisodeAfterEnd();
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private TextView roundTool(String glyph, int textSize) {
        TextView view;
        if ("⟳".equals(glyph)) {
            view = new CenteredPlayerTool(this, CenteredPlayerTool.ROTATE);
        } else if ("+".equals(glyph)) {
            view = new CenteredPlayerTool(this, CenteredPlayerTool.PLUS);
        } else if ("−".equals(glyph)) {
            view = new CenteredPlayerTool(this, CenteredPlayerTool.MINUS);
        } else {
            view = button(glyph, textSize);
        }
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, 0);
        android.graphics.drawable.GradientDrawable background =
                new android.graphics.drawable.GradientDrawable();
        background.setColor(0xBC202831);
        background.setCornerRadius(dp(32));
        background.setStroke(dp(1), 0x48FFFFFF);
        // The old CenteredPlayerTool rendered the icon in TextView.onDraw,
        // which Samsung/One UI skipped for empty-text controls. The round
        // background was still rendered, leaving three EMPTY CIRCLES.
        // The new custom background draws both the round surface and symbol
        // in Drawable.draw(), which Android already calls for these buttons.
        if (!(view instanceof CenteredPlayerTool))
            view.setBackground(background);
        view.setSingleLine(true);
        return view;
    }

    private TextView addSideTool(LinearLayout parent, TextView control,
                                 String label, Runnable action) {
        LinearLayout tool = new LinearLayout(this);
        tool.setOrientation(LinearLayout.VERTICAL);
        tool.setGravity(Gravity.CENTER);
        tool.setPadding(0, dp(5), 0, dp(5));
        parent.addView(tool, new LinearLayout.LayoutParams(-1, -2));
        tool.addView(control, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView caption = button(label, 11);
        caption.setGravity(Gravity.CENTER);
        caption.setTextColor(0xFFE7ECF1);
        caption.setSingleLine(true);
        caption.setPadding(0, dp(2), 0, 0);
        tool.addView(caption, new LinearLayout.LayoutParams(-1, dp(20)));
        tool.setContentDescription(label);
        tool.setOnClickListener(v -> {
            if (!isVideoTouchLocked()) action.run();
        });
        return caption;
    }

    private void buildSideToolRails() {
        leftToolsBar = new LinearLayout(this);
        leftToolsBar.setOrientation(LinearLayout.VERTICAL);
        leftToolsBar.setGravity(Gravity.CENTER);

        rotateButton = roundTool("⟳", 28);
        rotateModeLabel = addSideTool(leftToolsBar, rotateButton, "자동",
                this::rotateVideo);
        rotateButton.setContentDescription("화면 방향 자동, 가로, 세로 순환");
        updateRotateLabel();

        audioInfoButton = roundTool("♫", 28);
        audioCodecLabel = addSideTool(leftToolsBar, audioInfoButton, "오디오",
                this::showAudioSettings);
        audioInfoButton.setContentDescription("오디오 트랙 및 디코더 설정");

        subtitlesButton = roundTool("CC", 18);
        subtitleModeLabel = addSideTool(leftToolsBar, subtitlesButton, "자막",
                this::chooseSubtitle);
        subtitlesButton.setContentDescription("외부 및 내장 자막 설정");

        rightToolsBar = new LinearLayout(this);
        rightToolsBar.setOrientation(LinearLayout.VERTICAL);
        rightToolsBar.setGravity(Gravity.CENTER);
        TextView faster = roundTool("+", 36);
        addSideTool(rightToolsBar, faster, "증가",
                () -> adjustPlaybackSpeed(1));
        faster.setContentDescription("재생 속도 0.1배 증가");

        speedValueButton = roundTool("1.0×", 15);
        addSideTool(rightToolsBar, speedValueButton, "배속",
                this::resetPlaybackSpeed);
        speedValueButton.setContentDescription("현재 재생 속도, 누르면 1배로 초기화");

        TextView slower = roundTool("−", 36);
        addSideTool(rightToolsBar, slower, "감소",
                () -> adjustPlaybackSpeed(-1));
        slower.setContentDescription("재생 속도 0.1배 감소");
    }

    private void adjustPlaybackSpeed(int direction) {
        playbackSpeedTenths = PlaybackControlPolicy.adjustSpeed(
                playbackSpeedTenths, direction);
        if (player != null)
            player.setPlaybackSpeed(PlaybackControlPolicy.toSpeed(playbackSpeedTenths));
        updateSpeedLabel();
    }

    private void resetPlaybackSpeed() {
        playbackSpeedTenths = PlaybackControlPolicy.DEFAULT_SPEED_TENTHS;
        if (player != null) player.setPlaybackSpeed(1.0f);
        updateSpeedLabel();
    }

    private void updateSpeedLabel() {
        if (speedValueButton != null) {
            speedValueButton.setText(
                    PlaybackControlPolicy.speedLabel(playbackSpeedTenths));
            speedValueButton.setContentDescription("재생 배속 " +
                    PlaybackControlPolicy.speedLabel(playbackSpeedTenths) +
                    ", 누르면 기본 속도로 복귀");
        }
    }

    /** Re-layout the player when a Galaxy Fold is opened, without reloading. */
    private void applyAdaptivePlayerLayout() {
        if (playerRoot == null || leftToolsBar == null || rightToolsBar == null)
            return;
        boolean expanded = isFoldExpanded();
        int heightDp = getResources().getConfiguration().screenHeightDp;
        int control = FoldPlayerLayoutPolicy.railControlDp(expanded, heightDp);
        int width = expanded ? 84 : 68;
        for (LinearLayout rail : new LinearLayout[]{leftToolsBar, rightToolsBar}) {
            for (int index = 0; index < rail.getChildCount(); index++) {
                View item = rail.getChildAt(index);
                if (!(item instanceof LinearLayout)) continue;
                LinearLayout tool = (LinearLayout) item;
                if (tool.getChildCount() >= 2) {
                    View icon = tool.getChildAt(0);
                    ViewGroup.LayoutParams lp = icon.getLayoutParams();
                    lp.width = dp(control);
                    lp.height = dp(control);
                    icon.setLayoutParams(lp);
                    View caption = tool.getChildAt(1);
                    caption.setVisibility(heightDp < 420 ? View.GONE : View.VISIBLE);
                    if (caption instanceof TextView)
                        ((TextView) caption).setTextSize(expanded ? 12 : 11);
                }
            }
            FrameLayout.LayoutParams lp =
                    (FrameLayout.LayoutParams) rail.getLayoutParams();
            lp.width = dp(width);
            int margin = expanded ? 24 : 12;
            if (rail == leftToolsBar) lp.leftMargin = dp(margin);
            else lp.rightMargin = dp(margin);
            rail.setLayoutParams(lp);
        }
        if (topBar != null && topBar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams top =
                    (FrameLayout.LayoutParams) topBar.getLayoutParams();
            top.height = dp(expanded ? 58 : 49);
            top.leftMargin = expanded ? dp(18) : 0;
            top.rightMargin = expanded ? dp(18) : 0;
            top.topMargin = dp(expanded ? 16 : 10);
            topBar.setLayoutParams(top);
        }
    }

    private boolean supportsPopupPlayback(){
        return VideoPipPolicy.canOffer(
                getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_PICTURE_IN_PICTURE),
                TvMode.isTv(this),
                player!=null&&player.getPlaybackState()==Player.STATE_ENDED);
    }

    private boolean autoPopupOnHome(){
        return getSharedPreferences(PIP_PREFS,MODE_PRIVATE)
                .getBoolean(PIP_AUTO_ON_HOME,false);
    }

    private void saveAutoPopupOnHome(boolean enabled){
        getSharedPreferences(PIP_PREFS,MODE_PRIVATE).edit()
                .putBoolean(PIP_AUTO_ON_HOME,enabled).apply();
        refreshPictureInPictureParams();
    }

    /**
     * Home belongs to Android system navigation: after it is pressed this
     * Activity is not allowed to show a reliable foreground yes/no dialog.
     * The visible player popup button provides that dialog before leaving;
     * this preference optionally enables seamless Home -> PiP afterwards.
     */
    private void confirmPopupOrLeave(){
        if(!supportsPopupPlayback()){
            Toast.makeText(this,"이 기기에서는 팝업 재생을 사용할 수 없습니다.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("팝업 재생")
                .setMessage("팝업 재생으로 전환할까요?\n"+
                        "예: 재생을 유지한 채 팝업으로 전환합니다. "+
                        "이후 홈 버튼으로 나갈 때도 자동 팝업을 사용합니다.\n"+
                        "아니오: 팝업 없이 홈으로 나갑니다.")
                .setPositiveButton("예",(dialog,which)->{
                    saveAutoPopupOnHome(true);
                    enterPopupPlayback();
                })
                .setNegativeButton("아니오",(dialog,which)->{
                    saveAutoPopupOnHome(false);
                    moveTaskToBack(true);
                })
                .setNeutralButton("취소",null)
                .show();
    }

    private void configurePopupOnHome(){
        if(!supportsPopupPlayback()){
            Toast.makeText(this,"이 기기에서는 팝업 재생을 사용할 수 없습니다.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("홈 버튼 팝업 재생")
                .setMessage("재생 중 홈으로 이동하면 자동으로 팝업 재생할까요?\n"+
                        "끄면 홈으로 나갈 때 일반적인 방식으로 재생이 중단됩니다.")
                .setPositiveButton("예, 사용",(dialog,which)->
                        saveAutoPopupOnHome(true))
                .setNegativeButton("아니오, 끄기",(dialog,which)->
                        saveAutoPopupOnHome(false))
                .show();
    }

    private PictureInPictureParams pictureInPictureParameters(){
        PictureInPictureParams.Builder builder=
                new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(
                                pipAspectWidth,pipAspectHeight));
        if(videoView!=null){
            Rect frame=new Rect();
            if(videoView.getGlobalVisibleRect(frame)&&!frame.isEmpty())
                builder.setSourceRectHint(frame);
        }
        if(player!=null){
            boolean playing=player.isPlaying();
            Intent intent=new Intent(this,PipControlReceiver.class)
                    .setAction(ACTION_PIP_TOGGLE);
            PendingIntent toggle=PendingIntent.getBroadcast(this,198,
                    intent,PendingIntent.FLAG_UPDATE_CURRENT|
                            PendingIntent.FLAG_IMMUTABLE);
            RemoteAction control=new RemoteAction(
                    Icon.createWithResource(this,playing?
                            android.R.drawable.ic_media_pause:
                            android.R.drawable.ic_media_play),
                    playing?"일시정지":"재생",
                    playing?"동영상 일시정지":"동영상 다시 재생",toggle);
            builder.setActions(Collections.singletonList(control));
        }
        if(Build.VERSION.SDK_INT>=31){
            builder.setSeamlessResizeEnabled(true);
            builder.setAutoEnterEnabled(VideoPipPolicy.autoEnter(
                    autoPopupOnHome(),supportsPopupPlayback(),
                    TvMode.isTv(this),player!=null&&player.getPlayWhenReady()&&
                            player.getPlaybackState()!=Player.STATE_IDLE,
                    player!=null&&player.getPlaybackState()==Player.STATE_ENDED));
        }
        return builder.build();
    }

    private void refreshPictureInPictureParams(){
        if(isFinishing()||isDestroyed()||!supportsPopupPlayback())return;
        try{
            setPictureInPictureParams(pictureInPictureParameters());
        }catch(IllegalArgumentException|IllegalStateException ignored){
            // An OEM can reject a PiP request during an orientation change.
            // This must never interrupt video playback.
        }
    }

    private void enterPopupPlayback(){
        if(!supportsPopupPlayback()||player==null||
                isFinishing()||isDestroyed()||isInPictureInPictureMode())return;
        try{
            if(!enterPictureInPictureMode(pictureInPictureParameters()))
                Toast.makeText(this,"팝업 재생을 시작하지 못했습니다.",
                        Toast.LENGTH_SHORT).show();
        }catch(IllegalStateException|IllegalArgumentException problem){
            Toast.makeText(this,"팝업 재생 전환이 불가능합니다.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    /** PiP's system controls include close and expand/full-screen. */
    public static final class PipControlReceiver extends BroadcastReceiver {
        @Override public void onReceive(Context context,Intent intent){
            if(intent==null||
                    !ACTION_PIP_TOGGLE.equals(intent.getAction()))return;
            VideoPlayerActivity activity=currentPipOwner.get();
            if(activity==null||activity.player==null||
                    !activity.isInPictureInPictureMode())return;
            if(activity.player.isPlaying())activity.player.pause();
            else activity.player.play();
            activity.refreshPictureInPictureParams();
        }
    }

    private void showPlayerQuickMenu() {
        new AlertDialog.Builder(this).setTitle("재생 설정")
                .setItems(new String[]{
                        "자막 설정", "오디오 트랙 및 정보",
                        "화면 회전 · " + PlaybackControlPolicy.orientationLabel(orientationMode),
                        "재생 속도 초기화 (1.0×)", "화면 맞춤 / 확대",
                        "팝업 재생 / 홈으로 나가기",
                        "홈 버튼 자동 팝업 · " + (autoPopupOnHome()?"켜짐":"꺼짐"),
                        "이 폴더 개별 설정 초기화 (기본값 사용)"
                }, (dialog, which) -> {
                    if (which == 0) chooseSubtitle();
                    else if (which == 1) showAudioSettings();
                    else if (which == 2) rotateVideo();
                    else if (which == 3) resetPlaybackSpeed();
                    else if (which == 4) toggleVideoFit();
                    else if (which == 5) confirmPopupOrLeave();
                    else if (which == 6) configurePopupOnHome();
                    else if (which == 7) resetVideoFolderOverrides();
                }).show();
    }

    private void resetVideoFolderOverrides(){
        new AlertDialog.Builder(this).setTitle("이 폴더의 동영상 설정 초기화")
                .setMessage("이 폴더에서 변경한 영상 화면비율·자막 크기·자막 소스·자막 글꼴 설정을 해제하고 메인 기본값을 사용합니다.\n"+
                        "영상 파일과 시청 기록은 삭제하지 않습니다.")
                .setNegativeButton("취소",null)
                .setPositiveButton("기본값 사용",(dialog,which)->{
                    FolderMediaSettings.clear(this,FolderMediaSettings.VIDEO,
                            folderSettingsScope,PlayerSettings.RATIO,
                            PlayerSettings.SKIP_SECONDS,"subtitle_size",
                            "subtitle_source","subtitle_font");
                    PlayerSettings.resetVideoSubtitleSize(this,progressId);
                    if(progressId!=null)PlayerSettings.prefs(this).edit()
                            .remove(PlayerSettings.VIDEO_SUBTITLE_SOURCE_PREFIX+
                                    progressId).apply();
                    subtitleSourceMode=PlayerSettings.videoSubtitleSource(
                            this,progressId,folderSettingsScope);
                    int ratio=PlayerSettings.ratio(this,folderSettingsScope);
                    videoView.setResizeMode(ratio==PlayerSettings.ZOOM?
                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM:
                            ratio==PlayerSettings.STRETCH?
                                    AspectRatioFrameLayout.RESIZE_MODE_FILL:
                                    AspectRatioFrameLayout.RESIZE_MODE_FIT);
                    applySubtitleTypeface();
                    if(player!=null)
                        applySubtitleSourceSelection(player.getCurrentTracks());
                    updateSubtitleButton();
                    Toast.makeText(this,"이 폴더의 기본 설정을 복원했습니다.",
                            Toast.LENGTH_SHORT).show();
                }).show();
    }
    private void toggleVideoFit() {
        if (videoView == null) return;
        int current = videoView.getResizeMode();
        int next = current == AspectRatioFrameLayout.RESIZE_MODE_FIT ?
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM :
                AspectRatioFrameLayout.RESIZE_MODE_FIT;
        videoView.setResizeMode(next);
        PlayerSettings.setFolderRatio(this,folderSettingsScope,
                next == AspectRatioFrameLayout.RESIZE_MODE_ZOOM?
                        PlayerSettings.ZOOM:PlayerSettings.FIT);
        Toast.makeText(this, next == AspectRatioFrameLayout.RESIZE_MODE_ZOOM ?
                "화면 확대" : "화면 맞춤", Toast.LENGTH_SHORT).show();
    }
    private void hideSystemBars() {
        if(isInPictureInPictureMode()||popupUiActive)return;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        controller.hide(WindowInsetsCompat.Type.systemBars());
    }
    private void fadeChrome(View item, boolean visible) {
        if (item == null) return;
        item.animate().cancel();
        if (visible) {
            if (item.getVisibility() != View.VISIBLE) {
                item.setAlpha(0f);
                item.setVisibility(View.VISIBLE);
            }
            item.animate().alpha(1f).setDuration(200).start();
        } else if (item.getVisibility() == View.VISIBLE) {
            item.animate().alpha(0f).setDuration(200).withEndAction(() -> {
                if (!controlsVisible && !touchLock.isLocked())
                    item.setVisibility(View.GONE);
            }).start();
        }
    }
    /** Exactly one visibility signal drives the title and lock alongside Media3. */
    private void updateChromeVisibility(boolean visible) {
        if (touchLock.isLocked()) {
            if (topBar != null) {
                topBar.animate().cancel();
                topBar.setVisibility(View.GONE);
            }
            return;
        }
        fadeChrome(topBar, visible);
        fadeChrome(lockButton, visible);
        fadeChrome(leftToolsBar, visible);
        fadeChrome(rightToolsBar, visible);
    }
    private void showResumePrompt() {
        if (isFinishing() || isDestroyed() || player == null) return;
        long position = previouslyWatchedMs;
        long minutes = position / 60000L;
        long seconds = (position / 1000L) % 60L;
        new AlertDialog.Builder(this).setTitle("이어서 재생할까요?")
                .setMessage("이전에 " + String.format(Locale.KOREA,
                        "%02d:%02d", minutes, seconds) +
                        "까지 시청했어요.\n어디부터 재생할까요?")
                .setCancelable(false)
                .setPositiveButton("이어보기", (d, w) -> {
                    resumeDecisionMade = true;
                    if (player != null) {
                        player.seekTo(Math.max(0L, position));
                        player.play();
                    }
                })
                .setNegativeButton("처음부터", (d, w) -> {
                    resumeDecisionMade = true;
                    PlaybackProgressStore.clear(this, progressId);
                    if (player != null) {
                        player.seekTo(0L);
                        player.play();
                    }
                }).show();
    }
    private void saveWatchPosition() {
        if (player == null || progressId == null || !resumeDecisionMade) return;
        if (playbackEndGuard.handled() ||
                player.getPlaybackState() == Player.STATE_ENDED) {
            PlaybackProgressStore.clear(this, progressId);
            return;
        }
        long position = player.getCurrentPosition();
        long duration = player.getDuration();
        PlaybackProgressStore.save(this, progressId, position,
                duration > 0 && duration != C.TIME_UNSET ? duration : -1);
    }
    private void startSwipe(MotionEvent event) {
        swipeCaptured = false;
        swipePolicy.reset();
        if (videoView == null || touchLock.isLocked()) return;
        // The seek-bar guard is only needed while the controller is visible.
        // Keeping a fixed 145dp guard when hidden blocks low-starting upward
        // volume gestures on landscape phones.
        float viewportHeight = videoView.getHeight();
        float topGuard = VideoSwipePolicy.topProtectionPx(
                viewportHeight, dp(80), controlsVisible);
        float bottomGuard = VideoSwipePolicy.bottomProtectionPx(
                viewportHeight, dp(145), controlsVisible);
        swipePolicy.begin(event.getX(), event.getY(),
                videoView.getWidth(), viewportHeight,
                topGuard, bottomGuard);
        if (audioManager != null)
            startingVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        float requested = getWindow().getAttributes().screenBrightness;
        if (requested < 0f) {
            int system = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS, 128);
            requested = system / 255f;
        }
        startingBrightness = VideoSwipePolicy.clamp(Math.max(0.01f, requested));
    }
    private void cancelPlayerChildTouch() {
        long now = SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(now, now,
                MotionEvent.ACTION_CANCEL, 0, 0, 0);
        try { VideoPlayerActivity.super.dispatchTouchEvent(cancel); }
        finally { cancel.recycle(); }
    }
    private boolean applySwipe(MotionEvent event) {
        if (!swipePolicy.update(event.getX(), event.getY(),
                holdMovementTolerance)) return false;
        if (!swipeCaptured) {
            swipeCaptured = true;
            touchHandler.removeCallbacks(completeHold);
            touchLock.end();
            cancelPlayerChildTouch();
        }
        float amount = swipePolicy.amount(event.getY());
        String hint;
        if (swipePolicy.side() == VideoSwipePolicy.BRIGHTNESS) {
            float level = Math.max(0.01f,
                    VideoSwipePolicy.clamp(startingBrightness + amount));
            WindowManager.LayoutParams params = getWindow().getAttributes();
            params.screenBrightness = level;
            getWindow().setAttributes(params);
            hint = "☀  밝기 " + Math.round(level * 100) + "%";
        } else {
            int volume = Math.max(0, Math.min(maxVolume,
                    Math.round(startingVolume + amount * maxVolume)));
            if (audioManager != null)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0);
            hint = "🔊  볼륨 " + Math.round(volume * 100f / maxVolume) + "%";
        }
        swipeHint.setText(hint);
        swipeHint.setVisibility(View.VISIBLE);
        swipeHint.removeCallbacks(hideSwipeHint);
        return true;
    }
    private void finishSwipe() {
        swipePolicy.reset();
        swipeCaptured = false;
        if (swipeHint != null) {
            swipeHint.removeCallbacks(hideSwipeHint);
            swipeHint.postDelayed(hideSwipeHint, 850);
        }
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) hideSystemBars();
    }
    private boolean isFoldExpanded() {
        Configuration config = getResources().getConfiguration();
        return FoldPlayerLayoutPolicy.isExpanded(
                config.smallestScreenWidthDp, config.screenWidthDp,
                config.screenHeightDp);
    }

    private void rotateVideo() {
        setOrientationMode(
                PlaybackControlPolicy.nextOrientation(orientationMode));
    }

    private void setOrientationMode(int selectedMode) {
        orientationMode = selectedMode;
        userSelectedOrientation = selectedMode != PlaybackControlPolicy.AUTO;
        int requested;
        if (TvMode.isTv(this)) {
            requested = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else if (selectedMode == PlaybackControlPolicy.LANDSCAPE) {
            requested = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        } else if (selectedMode == PlaybackControlPolicy.PORTRAIT) {
            requested = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else if (isFoldExpanded()) {
            requested = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        } else if (detectedVideoLandscape != null) {
            requested = detectedVideoLandscape ?
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE :
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        } else {
            requested = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
        if (getRequestedOrientation() != requested)
            setRequestedOrientation(requested);
        updateRotateLabel();
    }
    private void updateRotateLabel() {
        if (rotateButton == null) return;
        // CenteredPlayerTool paints the symbol geometrically; adding a font
        // glyph here would reintroduce its asymmetric font side bearings.
        String label = PlaybackControlPolicy.orientationLabel(orientationMode);
        rotateButton.setContentDescription("화면 회전 · " + label);
        if (rotateModeLabel != null) rotateModeLabel.setText(label);
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        // PiP is system-resized; do not fight its window geometry by forcing
        // an activity rotation while the player lives in a small overlay.
        if(isInPictureInPictureMode()||popupUiActive)return;
        // On Fold unfold/refold, use the correct width class without
        // recreating the player and losing stream position.
        if (orientationMode == PlaybackControlPolicy.AUTO) {
            int requested = TvMode.isTv(this) ?
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE : isFoldExpanded() ?
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED :
                    detectedVideoLandscape == null ?
                            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED :
                            detectedVideoLandscape ?
                                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE :
                                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            if (getRequestedOrientation() != requested)
                setRequestedOrientation(requested);
        }
        applyAdaptivePlayerLayout();
        updateRotateLabel();
        hideSystemBars();
    }
    private void showLockFeedback(String message) {
        if (lockHint == null) return;
        lockHint.setText(message);
        lockHint.setVisibility(View.VISIBLE);
        lockHint.removeCallbacks(hideLockFeedback);
        lockHint.postDelayed(hideLockFeedback, touchLock.isLocked() ? 2300 : 1200);
    }
    private void showUnlockButtonTemporarily() {
        if (unlockButton == null || !touchLock.isLocked()) return;
        unlockButton.setVisibility(View.VISIBLE);
        unlockButton.removeCallbacks(hideUnlockButton);
        unlockButton.postDelayed(hideUnlockButton, 3500);
    }
    private void applyLockState() {
        if (lockButton == null || videoView == null) return;
        if (touchLock.isLocked()) {
            videoView.hideController();
            topBar.animate().cancel();
            topBar.setVisibility(View.GONE);
            lockButton.setVisibility(View.GONE);
            leftToolsBar.setVisibility(View.GONE);
            rightToolsBar.setVisibility(View.GONE);
            showUnlockButtonTemporarily();
            showLockFeedback("🔒 화면 잠금 · 자물쇠 버튼 또는 2초 터치로 해제");
        } else {
            unlockButton.removeCallbacks(hideUnlockButton);
            unlockButton.setVisibility(View.GONE);
            lockButton.setText("🔒");
            topBar.setAlpha(1f);
            lockButton.setAlpha(1f);
            leftToolsBar.setAlpha(1f);
            rightToolsBar.setAlpha(1f);
            topBar.setVisibility(View.VISIBLE);
            lockButton.setVisibility(View.VISIBLE);
            leftToolsBar.setVisibility(View.VISIBLE);
            rightToolsBar.setVisibility(View.VISIBLE);
            showLockFeedback("🔓 화면 잠금 해제");
            videoView.showController();
            controlsVisible = true;
            if (!chromeInController) updateChromeVisibility(true);
        }
    }
    private boolean touchedLockButton(MotionEvent event) {
        TextView target = touchLock.isLocked() ? unlockButton : lockButton;
        if (target == null || !target.isShown())
            return false;
        android.graphics.Rect bounds = new android.graphics.Rect();
        return target.getGlobalVisibleRect(bounds) &&
                bounds.contains((int) event.getRawX(), (int) event.getRawY());
    }
    /**
     * Activity-wide interception is necessary: a PlayerView touch listener
     * cannot block its child controller buttons, scrubber or top overlay.
     * Locked touches never reach any player control. The one exception is a
     * deliberate stationary two-second press to unlock.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (videoView == null || player == null) return super.dispatchTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN)
            lockButtonGesture = touchedLockButton(event);
        if (lockButtonGesture) {
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                lockButtonGesture = false;
            return handled;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            startSwipe(event);
            touchLock.begin(SystemClock.uptimeMillis(), event.getX(), event.getY());
            touchHandler.removeCallbacks(completeHold);
            touchHandler.postDelayed(completeHold, TouchHoldLock.HOLD_MILLIS);
            if (touchLock.isLocked()) {
                showUnlockButtonTemporarily();
                return true;
            }
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (event.getPointerCount() > 1 ||
                    !touchLock.move(event.getX(), event.getY(), holdMovementTolerance))
                touchHandler.removeCallbacks(completeHold);
            if (event.getPointerCount() > 1) {
                touchLock.end();
                swipePolicy.reset();
            }
            if (!touchLock.isLocked() && event.getPointerCount() == 1 &&
                    applySwipe(event)) return true;
            if (swipeCaptured) return true;
        } else if (action == MotionEvent.ACTION_UP) {
            if (swipeCaptured) {
                touchHandler.removeCallbacks(completeHold);
                touchLock.end();
                finishSwipe();
                return true;
            }
            swipePolicy.reset();
            // A release exactly at 2 seconds should toggle even when the
            // posted callback has not yet been executed.
            completeHold.run();
            touchHandler.removeCallbacks(completeHold);
            boolean justToggled = touchLock.wasToggledThisPress();
            touchLock.end();
            if (touchLock.isLocked() || justToggled) return true;
        } else if (action == MotionEvent.ACTION_CANCEL ||
                action == MotionEvent.ACTION_POINTER_DOWN ||
                action == MotionEvent.ACTION_POINTER_UP) {
            if (swipeCaptured) {
                touchHandler.removeCallbacks(completeHold);
                touchLock.end();
                finishSwipe();
                return true;
            }
            swipePolicy.reset();
            touchHandler.removeCallbacks(completeHold);
            boolean justToggled = touchLock.wasToggledThisPress();
            touchLock.end();
            if (touchLock.isLocked() || justToggled) return true;
        }
        if (touchLock.isLocked()) return true;
        return super.dispatchTouchEvent(event);
    }
    private GestureDetector tapDetector;
    private final class GestureDetectorHolder {
        GestureDetector create() {
            return new GestureDetector(VideoPlayerActivity.this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override public boolean onDown(MotionEvent event) {
                            return true;
                        }
                        @Override public boolean onDoubleTap(MotionEvent event) {
                            if (player == null || videoView == null) return false;
                            boolean forward = event.getX() >= videoView.getWidth() / 2f;
                            long change = PlayerSettings.skipSeconds(
                                    VideoPlayerActivity.this,folderSettingsScope)
                                    * 1000L * (forward ? 1L : -1L);
                            long seekTo = Math.max(0, player.getCurrentPosition() + change);
                            long duration = player.getDuration();
                            if (duration > 0 && duration != C.TIME_UNSET)
                                seekTo = Math.min(seekTo, duration);
                            player.seekTo(seekTo);
                            skipHint.setText((forward ? "+" : "-") +
                                    PlayerSettings.skipSeconds(
                                            VideoPlayerActivity.this,folderSettingsScope) + "초");
                            skipHint.setVisibility(View.VISIBLE);
                            skipHint.removeCallbacks(hideSkipFeedback);
                            skipHint.postDelayed(hideSkipFeedback, 750);
                            return true;
                        }
                    });
        }
    }
    private final Runnable hideSkipFeedback = () -> {
        if (skipHint != null) skipHint.setVisibility(View.GONE);
    };
    private void applySubtitleTypeface() {
        if (videoView == null) return;
        SubtitleView subtitleView = videoView.getSubtitleView();
        if (subtitleView != null) {
            Typeface selectedFont = SubtitleFonts.selectedTypeface(
                    this,folderSettingsScope);
            // Prefer the user-selected size to font sizes embedded in ASS/other cues.
            subtitleView.setApplyEmbeddedFontSizes(false);
            subtitleView.setStyle(new CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, selectedFont));
            subtitleView.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP,
                    PlayerSettings.videoSubtitleSize(this, progressId,
                            folderSettingsScope));
        }
    }
    private TextView button(String label, int size) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(Color.WHITE);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(dp(9), 0, dp(9), 0);
        return view;
    }
    private static boolean isExternalSubtitle(Format format) {
        return SubtitleSourcePolicy.isExternal(format.id, format.label);
    }

    private static String embeddedTrackKey(Format format) {
        if (format.id != null && !format.id.isEmpty())
            return "id:" + format.id;
        return "format:" + (format.language == null ? "" : format.language) + "|" +
                (format.label == null ? "" : format.label) + "|" +
                (format.sampleMimeType == null ? "" : format.sampleMimeType);
    }

    private static final class TextTrackCandidate {
        final Tracks.Group group;
        final int index;
        final Format format;
        TextTrackCandidate(Tracks.Group group, int index) {
            this.group = group;
            this.index = index;
            this.format = group.getTrackFormat(index);
        }
        boolean selected() { return group.isTrackSelected(index); }
    }

    /** Explicitly select the only subtitle track that should be rendered. */
    private void applySubtitleSourceSelection(Tracks tracks) {
        if (player == null) return;
        List<TextTrackCandidate> external = new ArrayList<>();
        List<TextTrackCandidate> embedded = new ArrayList<>();
        int selectedCount = 0;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) selectedCount++;
                if (!group.isTrackSupported(i)) continue;
                TextTrackCandidate candidate = new TextTrackCandidate(group, i);
                if (isExternalSubtitle(candidate.format)) external.add(candidate);
                else embedded.add(candidate);
            }
        }
        int desired = SubtitleSourcePolicy.desiredSource(
                subtitleSourceMode, !external.isEmpty(), !embedded.isEmpty());
        boolean disabled = player.getTrackSelectionParameters()
                .disabledTrackTypes.contains(C.TRACK_TYPE_TEXT);
        if (desired == SubtitleSourcePolicy.NONE) {
            boolean shouldDisable = subtitleSourceMode != SubtitleSourcePolicy.AUTO;
            if (disabled != shouldDisable || (shouldDisable && selectedCount > 0)) {
                player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                        .buildUpon()
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, shouldDisable)
                        .build());
            }
            return;
        }
        TextTrackCandidate target;
        if (desired == SubtitleSourcePolicy.EXTERNAL) {
            target = external.get(0);
        } else {
            target = embedded.get(0);
            if (selectedEmbeddedTrackKey != null) {
                for (TextTrackCandidate candidate : embedded) {
                    if (selectedEmbeddedTrackKey.equals(
                            embeddedTrackKey(candidate.format))) {
                        target = candidate;
                        break;
                    }
                }
            } else {
                String preferred = PlayerSettings.defaultSubtitleLanguage(this);
                for (TextTrackCandidate candidate : embedded) {
                    if (SubtitleSourcePolicy.matchesLanguage(
                            candidate.format.language, preferred)) {
                        target = candidate;
                        break;
                    }
                }
            }
        }
        // Track overrides fire onTracksChanged. If already correct, do not
        // reapply an override (otherwise subtitle selection can oscillate).
        if (!disabled && target.selected() && selectedCount == 1) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(new TrackSelectionOverride(
                        target.group.getMediaTrackGroup(), target.index))
                .build());
    }

    private void setSubtitleSourceMode(int source) {
        subtitleSourceMode = SubtitleSourcePolicy.normalize(source);
        if (subtitleSourceMode != SubtitleSourcePolicy.EMBEDDED)
            selectedEmbeddedTrackKey = null;
        PlayerSettings.setFolderSubtitleSource(this,folderSettingsScope,
                subtitleSourceMode);
        if (player != null) applySubtitleSourceSelection(player.getCurrentTracks());
        updateSubtitleButton();
    }

    private void updateSubtitleButton() {
        if (subtitlesButton == null) return;
        String source;
        if (subtitleSourceMode == SubtitleSourcePolicy.OFF) {
            source = "끔";
        } else if (subtitleSourceMode == SubtitleSourcePolicy.EXTERNAL) {
            source = "외부";
        } else if (subtitleSourceMode == SubtitleSourcePolicy.EMBEDDED) {
            source = "내장";
        } else {
            source = "자동";
            if (player != null) {
                for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
                    if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                    for (int i = 0; i < group.length; i++) {
                        if (!group.isTrackSelected(i)) continue;
                        source = isExternalSubtitle(group.getTrackFormat(i)) ?
                                "외부" : "내장";
                        break;
                    }
                    if (!"자동".equals(source)) break;
                }
            }
        }
        subtitlesButton.setText("CC");
        if (subtitleModeLabel != null) subtitleModeLabel.setText(source);
        subtitlesButton.setContentDescription(
                "자막 설정 · " + SubtitleSourcePolicy.LABELS[subtitleSourceMode]);
    }

    private void reload(long position, boolean play) {
        // Old overrides refer to a previous MediaItem/TrackGroup.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,
                        subtitleSourceMode == SubtitleSourcePolicy.OFF)
                .build());
        MediaItem.Builder item = new MediaItem.Builder().setUri(video);
        if (!subtitleTracks.isEmpty())
            item.setSubtitleConfigurations(new ArrayList<>(subtitleTracks));
        player.setMediaItem(item.build());
        player.prepare();
        if (position != C.TIME_UNSET) player.seekTo(position);
        player.setPlayWhenReady(play);
        updateSubtitleButton();
    }
    private void attachSubtitle(Uri uri, String name, boolean manual) {
        if (player == null) return;
        if (!manual && manualExternalFileChosen) return;
        if (manual) {
            manualExternalFileChosen = true;
            subtitleSourceMode = SubtitleSourcePolicy.EXTERNAL;
            selectedEmbeddedTrackKey = null;
            PlayerSettings.setFolderSubtitleSource(this,folderSettingsScope,
                    subtitleSourceMode);
        }
        MediaItem.SubtitleConfiguration item =
                new MediaItem.SubtitleConfiguration.Builder(uri)
                        .setMimeType(name.toLowerCase(Locale.ROOT).endsWith(".vtt") ?
                                MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP)
                        .setId(SubtitleSourcePolicy.EXTERNAL_TRACK_ID)
                        .setLabel(SubtitleSourcePolicy.EXTERNAL_LABEL_PREFIX + " · " + name)
                        .setLanguage("ko")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build();
        long current = player.getCurrentPosition();
        boolean playing = player.getPlayWhenReady();
        subtitleTracks.clear();
        subtitleTracks.add(item);
        reload(current, playing);
    }
    private static boolean isSami(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".smi") || lower.endsWith(".sami");
    }

    /** Bounded read for content:// and local SAMI; no source file changes. */
    private byte[] readSubtitleBytes(InputStream input) throws IOException {
        if (input == null) throw new IOException("자막 파일을 열 수 없습니다.");
        try (InputStream stream = input;
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                if (count == 0) continue;
                if (output.size() + (long) count > MAX_SUBTITLE)
                    throw new IOException("자막 파일 크기 제한(2MB)을 초과했습니다.");
                output.write(chunk, 0, count);
            }
            return output.toByteArray();
        }
    }

    /** App-private cache for converted SMI or downloaded NAS SRT/VTT. */
    private Uri cacheSubtitle(byte[] bytes, String originalName) throws IOException {
        boolean sami = isSami(originalName);
        byte[] playable;
        try {
            playable = sami ?
                    SamiSubtitleConverter.toSrt(bytes)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8) : bytes;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("SMI 변환 실패: " + malformed.getMessage(), malformed);
        }
        String extension = !sami && originalName.toLowerCase(Locale.ROOT)
                .endsWith(".vtt") ? ".vtt" : ".srt";
        File target = new File(getCacheDir(),
                "subtitle_" + System.nanoTime() + extension);
        try (FileOutputStream file = new FileOutputStream(target)) {
            file.write(playable);
        }
        return Uri.fromFile(target);
    }

    private void chooseSubtitle() {
        if (player == null) return;
        new AlertDialog.Builder(this).setTitle("자막 설정")
                .setItems(new String[]{
                        "자막 소스 선택 · " + SubtitleSourcePolicy.LABELS[subtitleSourceMode],
                        "내장 자막 언어 / 트랙 선택",
                        "자막 크기 (이 폴더 전체 영상)",
                        "외부 SRT · VTT · SMI 파일 선택",
                        "외부 자막 제거",
                        "모든 자막 끄기",
                        "자동 선택으로 되돌리기 (외부 우선)",
                        "자막 글꼴 (이 폴더 전체 영상)"
                }, (d, which) -> {
                    switch (which) {
                        case 0:
                            chooseSubtitleSource();
                            break;
                        case 1:
                            chooseEmbeddedSubtitle();
                            break;
                        case 2:
                            chooseSubtitleSize();
                            break;
                        case 3:
                            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                    .setType("*/*")
                                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            startActivityForResult(pick, REQUEST_SUBTITLE);
                            break;
                        case 4:
                            // Explicit removal cancels an in-flight NAS
                            // auto-search, so it cannot reattach the file.
                            manualExternalFileChosen = true;
                            if (!subtitleTracks.isEmpty()) {
                                long current = player.getCurrentPosition();
                                boolean playing = player.getPlayWhenReady();
                                subtitleTracks.clear();
                                selectedEmbeddedTrackKey = null;
                                subtitleSourceMode = SubtitleSourcePolicy.AUTO;
                                PlayerSettings.setFolderSubtitleSource(
                                        this, folderSettingsScope, subtitleSourceMode);
                                reload(current, playing);
                            } else {
                                setSubtitleSourceMode(SubtitleSourcePolicy.AUTO);
                            }
                            break;
                        case 5:
                            setSubtitleSourceMode(SubtitleSourcePolicy.OFF);
                            break;
                        case 6:
                            setSubtitleSourceMode(SubtitleSourcePolicy.AUTO);
                            break;
                        case 7:
                            chooseSubtitleFont();
                            break;
                        default: break;
                    }
                }).show();
    }

    private void chooseSubtitleFont(){
        List<String> installed=SubtitleFonts.installed(this);
        String defaultName=SubtitleFonts.current(this);
        String[] options=new String[installed.size()+2];
        options[0]="기본값 사용 ("+
                (defaultName.isEmpty()?"Android 시스템 글꼴":defaultName)+")";
        options[1]="Android 시스템 글꼴 (이 폴더)";
        for(int i=0;i<installed.size();i++)options[i+2]=installed.get(i);
        boolean customized=FolderMediaSettings.contains(this,
                FolderMediaSettings.VIDEO,folderSettingsScope,"subtitle_font");
        String current=SubtitleFonts.current(this,folderSettingsScope);
        int selected=customized?(current.isEmpty()?1:0):0;
        if(customized&&!current.isEmpty()){
            int index=installed.indexOf(current);
            if(index>=0)selected=index+2;
        }
        new AlertDialog.Builder(this).setTitle("동영상 폴더의 자막 글꼴")
                .setSingleChoiceItems(options,selected,(dialog,which)->{
                    if(which==0)SubtitleFonts.resetFolderFont(
                            this,folderSettingsScope);
                    else SubtitleFonts.setFolderFont(this,folderSettingsScope,
                            which==1?"":installed.get(which-2));
                    applySubtitleTypeface();
                    dialog.dismiss();
                }).setNegativeButton("취소",null).show();
    }
    private void chooseSubtitleSource() {
        if (player == null) return;
        new AlertDialog.Builder(this).setTitle("자막 소스 선택")
                .setSingleChoiceItems(SubtitleSourcePolicy.LABELS, subtitleSourceMode,
                        (dialog, selected) -> {
                            setSubtitleSourceMode(selected);
                            dialog.dismiss();
                            int external = 0;
                            int embedded = 0;
                            for (Tracks.Group group :
                                    player.getCurrentTracks().getGroups()) {
                                if (group.getType() != C.TRACK_TYPE_TEXT) continue;
                                for (int i = 0; i < group.length; i++) {
                                    if (!group.isTrackSupported(i)) continue;
                                    if (isExternalSubtitle(group.getTrackFormat(i)))
                                        external++;
                                    else embedded++;
                                }
                            }
                            if (selected == SubtitleSourcePolicy.EXTERNAL &&
                                    external == 0) {
                                Toast.makeText(this,
                                        "외부 자막이 아직 없습니다. 파일 선택으로 추가할 수 있어요.",
                                        Toast.LENGTH_LONG).show();
                            } else if (selected == SubtitleSourcePolicy.EMBEDDED &&
                                    embedded == 0) {
                                Toast.makeText(this,
                                        "선택 가능한 내장 자막이 없습니다. 하드섭 영상일 수도 있어요.",
                                        Toast.LENGTH_LONG).show();
                            }
                        }).setNegativeButton("취소", null).show();
    }

    private void chooseEmbeddedSubtitle() {
        if (player == null) return;
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        List<String> options = new ArrayList<>();
        String preferred = PlayerSettings.languageLabel(
                PlayerSettings.defaultSubtitleLanguage(this));
        options.add("내장 자막 기본 언어 (" + preferred + ")");
        int selected = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) continue;
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSupported(i)) continue;
                Format format = group.getTrackFormat(i);
                if (isExternalSubtitle(format)) continue;
                String name = PlayerSettings.languageLabel(format.language);
                if (format.label != null && !format.label.trim().isEmpty())
                    name += " · " + format.label.trim();
                if (format.sampleMimeType != null)
                    name += " (" + format.sampleMimeType + ")";
                if (subtitleSourceMode == SubtitleSourcePolicy.EMBEDDED &&
                        group.isTrackSelected(i)) selected = options.size();
                groups.add(group);
                indexes.add(i);
                options.add(name);
            }
        }
        if (options.size() == 1) {
            new AlertDialog.Builder(this).setTitle("내장 자막")
                    .setMessage("현재 플레이어에서 선택 가능한 자막 트랙이 없습니다.\n" +
                            "영상에 자막이 없거나 디코더가 지원하지 않는 형식일 수 있습니다.")
                    .setPositiveButton("확인", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("내장 자막 언어 / 트랙")
                .setSingleChoiceItems(options.toArray(new String[0]), selected,
                        (dialog, which) -> {
                            if (which == 0) {
                                selectedEmbeddedTrackKey = null;
                            } else {
                                int pos = which - 1;
                                selectedEmbeddedTrackKey = embeddedTrackKey(
                                        groups.get(pos).getTrackFormat(indexes.get(pos)));
                            }
                            setSubtitleSourceMode(SubtitleSourcePolicy.EMBEDDED);
                            dialog.dismiss();
                        })
                .setNegativeButton("취소", null).show();
    }

    private void chooseSubtitleSize() {
        int[] sizes = PlayerSettings.SUBTITLE_SIZES;
        String[] options = new String[sizes.length + 1];
        int defaultSize = PlayerSettings.defaultSubtitleSize(this);
        options[0] = "기본값 사용 (" + defaultSize + "sp)";
        int current = PlayerSettings.videoSubtitleSize(
                this, progressId,folderSettingsScope);
        int selected = PlayerSettings.hasFolderSubtitleSize(
                this,progressId,folderSettingsScope) ? -1 : 0;
        for (int i = 0; i < sizes.length; i++) {
            options[i + 1] = sizes[i] + "sp";
            if (selected == -1 && current == sizes[i]) selected = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("이 폴더의 자막 크기")
                .setSingleChoiceItems(options, selected, (dialog, which) -> {
                    if (which == 0) {
                        PlayerSettings.resetFolderSubtitleSize(
                                this,folderSettingsScope,progressId);
                    } else {
                        PlayerSettings.setFolderSubtitleSize(
                                this,folderSettingsScope,sizes[which - 1]);
                    }
                    applySubtitleTypeface();
                    dialog.dismiss();
                }).setNegativeButton("취소", null).show();
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQUEST_SUBTITLE || result != RESULT_OK ||
                data == null || data.getData() == null) return;
        Uri chosen = data.getData();
        String name = chosen.getLastPathSegment();
        try (android.database.Cursor cursor = getContentResolver().query(chosen,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (Exception ignored) {}
        if (name == null || !(name.toLowerCase(Locale.ROOT).endsWith(".srt") ||
                name.toLowerCase(Locale.ROOT).endsWith(".vtt") || isSami(name))) {
            Toast.makeText(this, "SRT, VTT, SMI 파일을 지원합니다.", Toast.LENGTH_LONG).show();
            return;
        }
        if (isSami(name)) {
            // Reserve the manual selection immediately so an unfinished NAS
            // auto-scan cannot replace the chosen file.
            manualExternalFileChosen = true;
            String selectedName = name;
            jobs.execute(() -> {
                try {
                    Uri prepared = cacheSubtitle(readSubtitleBytes(
                            getContentResolver().openInputStream(chosen)), selectedName);
                    runOnUiThread(() -> {
                        if (!isFinishing() && player != null)
                            attachSubtitle(prepared, selectedName, true);
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "SMI 자막 불러오기 실패: " + error.getMessage(),
                            Toast.LENGTH_LONG).show());
                }
            });
        } else {
            attachSubtitle(chosen, name, true);
        }
    }
    private void autoSubtitleRemote() {
        Models.RemoteProfile source = profile;
        String folder = RemoteProvider.parent(remotePath);
        String stem = baseName(displayName);
        jobs.execute(() -> {
            try {
                for (Models.Entry item : RemoteProvider.list(source, folder)) {
                    if (item.directory || item.size > MAX_SUBTITLE ||
                            !subtitleMatches(stem, item.name)) continue;
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    OutputStream bounded = new java.io.FilterOutputStream(bytes) {
                        long used;
                        @Override public void write(int b) throws IOException {
                            if (++used > MAX_SUBTITLE)
                                throw new IOException("자막 크기 제한 초과");
                            out.write(b);
                        }
                        @Override public void write(byte[] b, int o, int n)
                                throws IOException {
                            if (used + n > MAX_SUBTITLE)
                                throw new IOException("자막 크기 제한 초과");
                            out.write(b, o, n); used += n;
                        }
                    };
                    RemoteProvider.download(source, item.path, bounded);
                    Uri prepared = cacheSubtitle(bytes.toByteArray(), item.name);
                    runOnUiThread(() -> {
                        if (!isFinishing() && player != null)
                            attachSubtitle(prepared, item.name, false);
                    });
                    return;
                }
            } catch (Exception ignored) {
                // Manual subtitle picking remains available on connection failure.
            }
        });
    }
    private void autoSubtitleLocal() {
        if (!"file".equals(video.getScheme())) return;
        File source = new File(video.getPath());
        File parent = source.getParentFile();
        if (parent == null) return;
        File[] files = parent.listFiles();
        if (files == null) return;
        for (File child : files) {
            if (subtitleMatches(baseName(displayName), child.getName()) &&
                    child.isFile() && child.length() <= MAX_SUBTITLE) {
                if (isSami(child.getName())) {
                    jobs.execute(() -> {
                        try {
                            Uri prepared = cacheSubtitle(readSubtitleBytes(
                                    new java.io.FileInputStream(child)), child.getName());
                            runOnUiThread(() -> {
                                if (!isFinishing() && player != null)
                                    attachSubtitle(prepared, child.getName(), false);
                            });
                        } catch (Exception ignored) {
                            // A damaged sidecar must not block video playback.
                        }
                    });
                } else {
                    attachSubtitle(Uri.fromFile(child), child.getName(), false);
                }
                return;
            }
        }
    }
    static String baseName(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }
    static boolean subtitleMatches(String videoStem, String subtitleName) {
        return SubtitleSourcePolicy.matchesExternalFilename(videoStem, subtitleName);
    }
    /** Actual track selected by Media3; never use the movie filename. */
    private Format selectedAudioFormat() {
        if (player == null) return null;
        Format activeFormat = player.getAudioFormat();
        if (activeFormat != null) return activeFormat;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int index = 0; index < group.length; index++) {
                if (group.isTrackSelected(index))
                    return group.getTrackFormat(index);
            }
        }
        return null;
    }

    /** Called on main thread by Media3 after audio tracks/decoders change. */
    private void updateAudioCodecButton() {
        if (audioInfoButton == null) return;
        Format format = selectedAudioFormat();
        String title;
        if (format != null) {
            title = AudioPlaybackLabels.codecName(format.sampleMimeType, format.codecs);
        } else if (player != null && (player.getPlaybackState() == Player.STATE_READY
                || player.getPlaybackState() == Player.STATE_ENDED)) {
            title = "오디오 없음";
        } else {
            title = "오디오 확인 중";
        }
        audioInfoButton.setText("♫");
        if (audioCodecLabel != null) {
            audioCodecLabel.setText(title.length() > 9 ? "오디오" : title);
            audioCodecLabel.setContentDescription("재생 중인 오디오: " + title);
        }
        audioInfoButton.setContentDescription(
                "현재 오디오 코덱: " + title + " · 디코더 상세 정보 보기");
    }

    private void showAudioSettings() {
        if (player == null) return;
        new AlertDialog.Builder(this).setTitle("오디오 설정")
                .setItems(new String[]{"오디오 트랙 / 언어 선택",
                        "현재 오디오 소스 · 코덱 · 디코더 정보"},
                        (dialog, which) -> {
                            if (which == 0) chooseAudioTrack();
                            else showAudioInfo();
                        }).show();
    }

    private void chooseAudioTrack() {
        if (player == null) return;
        List<Tracks.Group> groups = new ArrayList<>();
        List<Integer> indexes = new ArrayList<>();
        List<String> names = new ArrayList<>();
        names.add("자동 (기본 오디오)");
        int selected = 0;
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_AUDIO) continue;
            for (int index = 0; index < group.length; index++) {
                if (!group.isTrackSupported(index)) continue;
                Format format = group.getTrackFormat(index);
                StringBuilder item = new StringBuilder(
                        PlayerSettings.languageLabel(format.language));
                if (format.label != null && !format.label.trim().isEmpty())
                    item.append(" · ").append(format.label.trim());
                item.append(" · ").append(AudioPlaybackLabels.codecName(
                        format.sampleMimeType, format.codecs));
                if (format.channelCount > 0)
                    item.append(" · ").append(AudioPlaybackLabels.channelLabel(
                            format.channelCount));
                if (group.isTrackSelected(index)) selected = names.size();
                names.add(item.toString());
                groups.add(group);
                indexes.add(index);
            }
        }
        if (names.size() == 1) {
            new AlertDialog.Builder(this).setTitle("오디오 트랙")
                    .setMessage("현재 재생기에서 선택 가능한 오디오 트랙이 없습니다.")
                    .setPositiveButton("확인", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("오디오 소스 / 언어")
                .setSingleChoiceItems(names.toArray(new String[0]), selected,
                        (dialog, which) -> {
                            androidx.media3.common.TrackSelectionParameters.Builder builder =
                                    player.getTrackSelectionParameters().buildUpon()
                                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false);
                            if (which > 0) {
                                int pos = which - 1;
                                builder.setOverrideForType(new TrackSelectionOverride(
                                        groups.get(pos).getMediaTrackGroup(),
                                        indexes.get(pos)));
                            }
                            player.setTrackSelectionParameters(builder.build());
                            dialog.dismiss();
                            updateAudioCodecButton();
                        }).setNegativeButton("취소", null).show();
    }

    private void showAudioInfo() {
        Format format = selectedAudioFormat();
        String decoderName = format == null ? null : activeAudioDecoderName;
        String codec = format == null ? (player != null &&
                player.getPlaybackState() == Player.STATE_READY ?
                "오디오 없음" : "확인 중") :
                AudioPlaybackLabels.codecName(format.sampleMimeType, format.codecs);
        StringBuilder info = new StringBuilder();
        info.append("오디오 코덱: ").append(codec);
        if (format != null) {
            info.append("\nMIME 형식: ")
                    .append(format.sampleMimeType == null ? "정보 없음" : format.sampleMimeType);
            if (format.codecs != null && !format.codecs.isEmpty())
                info.append("\n코덱 식별자: ").append(format.codecs);
            info.append("\n채널: ")
                    .append(AudioPlaybackLabels.channelLabel(format.channelCount))
                    .append("\n샘플레이트: ")
                    .append(AudioPlaybackLabels.sampleRateLabel(format.sampleRate));
            if (format.averageBitrate > 0)
                info.append("\n평균 비트레이트: ")
                        .append(Math.round(format.averageBitrate / 1000f)).append(" kbps");
            if (format.language != null && !format.language.isEmpty())
                info.append("\n언어: ")
                        .append(PlayerSettings.languageLabel(format.language));
        }
        info.append("\n\n실제 사용 디코더: ")
                .append(AudioPlaybackLabels.decoderKind(decoderName));
        info.append("\n디코더 이름: ")
                .append(decoderName == null || decoderName.trim().isEmpty() ?
                        "아직 보고되지 않음" : decoderName);
        info.append("\n\n표시된 코덱은 영상에 실제로 선택된 오디오 트랙 기준입니다. ");
        info.append("디코더 종류는 Media3가 재생 중 보고한 디코더 이름을 사용하며, ");
        info.append("FFmpeg가 APK에 설치되어 있다는 이유만으로 FFmpeg 사용 중이라고 표시하지 않습니다.");
        new AlertDialog.Builder(this).setTitle("현재 영상 오디오 정보")
                .setMessage(info.toString())
                .setPositiveButton("확인", null).show();
    }
    @Override protected void onResume() {
        super.onResume();
        refreshPictureInPictureParams();
        watchHandler.removeCallbacks(saveWatchTick);
        watchHandler.postDelayed(saveWatchTick, 5000L);
        watchHandler.removeCallbacks(monitorActualVideoEnd);
        watchHandler.postDelayed(monitorActualVideoEnd,
                PlaybackEndGuard.CONFIRM_WINDOW_MS);
    }
    @Override protected void onPause() {
        if(!isInPictureInPictureMode()){
            watchHandler.removeCallbacks(saveWatchTick);
            watchHandler.removeCallbacks(monitorActualVideoEnd);
        }
        saveWatchPosition();
        super.onPause();
    }
    @Override protected void onStop() {
        saveWatchPosition();
        touchHandler.removeCallbacks(completeHold);
        touchLock.end();
        swipePolicy.reset();
        if (unlockButton != null) unlockButton.removeCallbacks(hideUnlockButton);
        // A video in Android native PiP is still visible and must keep
        // playing. Normal Home (when the user declined PiP) still pauses.
        if (player != null && !isInPictureInPictureMode()) player.pause();
        super.onStop();
    }
    @Override protected void onUserLeaveHint(){
        super.onUserLeaveHint();
        // Android 12+ performs this itself with setAutoEnterEnabled.
        // Pre-12 needs to enter explicitly while Activity is foreground.
        if(Build.VERSION.SDK_INT<31&&VideoPipPolicy.autoEnter(
                autoPopupOnHome(),supportsPopupPlayback(),TvMode.isTv(this),
                player!=null&&player.getPlayWhenReady()&&
                        player.getPlaybackState()!=Player.STATE_IDLE,
                player!=null&&player.getPlaybackState()==Player.STATE_ENDED))
            enterPopupPlayback();
    }
    @Override public void onPictureInPictureModeChanged(
            boolean inPictureInPictureMode,Configuration newConfig){
        super.onPictureInPictureModeChanged(
                inPictureInPictureMode,newConfig);
        popupUiActive=inPictureInPictureMode;
        if(videoView==null)return;
        if(inPictureInPictureMode){
            watchHandler.removeCallbacks(saveWatchTick);
            watchHandler.postDelayed(saveWatchTick,5000L);
            watchHandler.removeCallbacks(monitorActualVideoEnd);
            watchHandler.postDelayed(monitorActualVideoEnd,
                    PlaybackEndGuard.CONFIRM_WINDOW_MS);
            videoView.hideController();
            videoView.setUseController(false);
            if(topBar!=null)topBar.setVisibility(View.GONE);
            if(leftToolsBar!=null)leftToolsBar.setVisibility(View.GONE);
            if(rightToolsBar!=null)rightToolsBar.setVisibility(View.GONE);
            if(lockButton!=null)lockButton.setVisibility(View.GONE);
            if(unlockButton!=null)unlockButton.setVisibility(View.GONE);
            if(swipeHint!=null)swipeHint.setVisibility(View.GONE);
            if(skipHint!=null)skipHint.setVisibility(View.GONE);
            refreshPictureInPictureParams();
        }else{
            videoView.setUseController(true);
            if(!touchLock.isLocked()){
                controlsVisible=true;
                if(chromeInController){
                    if(topBar!=null)topBar.setVisibility(View.VISIBLE);
                    if(leftToolsBar!=null)leftToolsBar.setVisibility(View.VISIBLE);
                    if(rightToolsBar!=null)rightToolsBar.setVisibility(View.VISIBLE);
                    if(lockButton!=null)lockButton.setVisibility(View.VISIBLE);
                }else updateChromeVisibility(true);
                videoView.showController();
            }else applyLockState();
            applyAdaptivePlayerLayout();
            hideSystemBars();
            refreshPictureInPictureParams();
        }
    }
    @Override protected void onDestroy() {
        if(currentPipOwner.get()==this)currentPipOwner.clear();
        if (videoView != null && videoView.getViewTreeObserver().isAlive()) {
            videoView.getViewTreeObserver().removeOnPreDrawListener(
                    synchronizeChromeWithSeekBar);
        }
        watchHandler.removeCallbacks(saveWatchTick);
        watchHandler.removeCallbacks(monitorActualVideoEnd);
        saveWatchPosition();
        touchHandler.removeCallbacks(completeHold);
        if (lockHint != null) lockHint.removeCallbacks(hideLockFeedback);
        if (unlockButton != null) unlockButton.removeCallbacks(hideUnlockButton);
        if (swipeHint != null) swipeHint.removeCallbacks(hideSwipeHint);
        jobs.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
