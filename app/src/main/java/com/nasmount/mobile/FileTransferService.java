package com.nasmount.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One transfer at a time, owned by a dataSync foreground service, never by Activity. */
public final class FileTransferService extends Service {
    static final String ACTION_START = "com.nasmount.mobile.transfer.START";
    static final String ACTION_CANCEL = "com.nasmount.mobile.transfer.CANCEL";
    static final String EXTRA_SHOW = "show_transfer";
    private static final String CHANNEL = "filehub_transfer_v1";
    private static final int NOTICE_ID = 1849;
    private static final Object LOCK = new Object();
    private static Pending pending;
    private static volatile State state = State.idle();
    private static volatile FileTransferService runningService;

    static final class State {
        final boolean active, cancelling, finished, failed, cancelled;
        final long done, total;
        final String name, error;
        final int completed, count;
        final boolean moving;

        State(boolean active, boolean cancelling, boolean finished, boolean failed,
              boolean cancelled, long done, long total, String name, String error,
              int completed, int count, boolean moving) {
            this.active = active;
            this.cancelling = cancelling;
            this.finished = finished;
            this.failed = failed;
            this.cancelled = cancelled;
            this.done = done;
            this.total = total;
            this.name = name;
            this.error = error;
            this.completed = completed;
            this.count = count;
            this.moving = moving;
        }
        static State idle() {
            return new State(false, false, false, false, false,
                    0, -1, "", "", 0, 0, false);
        }
        int percent() {
            return TransferProgressFormat.percent(done, total, finished);
        }
    }

    private static final class Pending {
        final List<FileTransferEngine.Item> sources;
        final FileTransferEngine.Destination destination;
        final boolean moving, overwrite;
        final FileTransferEngine.TransferControl control =
                new FileTransferEngine.TransferControl();
        Pending(List<FileTransferEngine.Item> sources,
                FileTransferEngine.Destination destination,
                boolean moving, boolean overwrite) {
            this.sources = new ArrayList<>(sources);
            this.destination = destination;
            this.moving = moving;
            this.overwrite = overwrite;
        }
    }

    static State snapshot() { return state; }

    static boolean begin(android.content.Context context,
                         List<FileTransferEngine.Item> sources,
                         FileTransferEngine.Destination destination,
                         boolean moving, boolean overwrite) {
        Pending job;
        synchronized (LOCK) {
            if (pending != null || state.active || sources.isEmpty()) return false;
            job = new Pending(sources, destination, moving, overwrite);
            pending = job;
            state = new State(true, false, false, false, false,
                    0, -1, "전송 준비 중...", "", 0, sources.size(), moving);
        }
        try {
            Intent request = new Intent(context, FileTransferService.class)
                    .setAction(ACTION_START);
            ContextCompat.startForegroundService(context, request);
            return true;
        } catch (RuntimeException error) {
            synchronized (LOCK) {
                if (pending == job) {
                    pending = null;
                    state = State.idle();
                }
            }
            throw error;
        }
    }

    static void requestCancel(android.content.Context context) {
        Pending job;
        synchronized (LOCK) {
            job = pending;
            if (job != null && state.active) {
                State s = state;
                state = new State(true, true, false, false, false,
                        s.done, s.total, s.name, "", s.completed, s.count, s.moving);
            }
        }
        if (job != null) job.control.cancel();
        try {
            context.startService(new Intent(context, FileTransferService.class)
                    .setAction(ACTION_CANCEL));
        } catch (RuntimeException ignored) {
            // Cancellation token is already set; service thread will observe it.
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private NotificationManager notices;
    private PowerManager.WakeLock transferWakeLock;
    private long lastNotification;
    private boolean started;

    @Override public void onCreate() {
        super.onCreate();
        runningService = this;
        notices = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "파일 복사 및 이동", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("백그라운드 파일 전송 진행률");
        notices.createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            Pending cancelling;
            synchronized (LOCK) {
                cancelling = pending;
                if (cancelling != null) {
                    State s = state;
                    state = new State(true, true, false, false, false,
                            s.done, s.total, s.name, "", s.completed, s.count, s.moving);
                }
            }
            if (cancelling != null) cancelling.control.cancel();
            if (cancelling == null) {
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            publishNotification(true);
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(action)) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        Pending job;
        synchronized (LOCK) { job = pending; }
        if (job == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        started = true;
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTICE_ID, notification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            else startForeground(NOTICE_ID, notification());
        } catch (RuntimeException error) {
            complete(job, error);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        try {
            PowerManager manager = (PowerManager) getSystemService(POWER_SERVICE);
            if (manager != null) {
                transferWakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                        "FileHubAIO:FileTransfer");
                transferWakeLock.setReferenceCounted(false);
                transferWakeLock.acquire(6L * 60L * 60L * 1000L);
            }
        } catch (RuntimeException ignored) {
            // The foreground service is still usable without a wake lock.
        }
        executor.execute(() -> {
            Throwable failure = null;
            try {
                FileTransferEngine.transfer(getApplicationContext(), job.sources,
                        job.destination, job.moving, job.overwrite,
                        (done, total, name, completed, count) -> {
                            State existing = state;
                            state = new State(true, existing.cancelling, false,
                                    false, false, done, total, name, "",
                                    completed, count, job.moving);
                            publishNotification(false);
                        }, job.control);
            } catch (Throwable error) { failure = error; }
            complete(job, failure);
            if (transferWakeLock != null && transferWakeLock.isHeld())
                transferWakeLock.release();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        });
        return START_NOT_STICKY;
    }

    private void complete(Pending job, Throwable failure) {
        synchronized (LOCK) {
            if (pending != job) return;
            State s = state;
            boolean cancelled = job.control.isCancelled();
            state = new State(false, false, failure == null && !cancelled,
                    failure != null && !cancelled, cancelled,
                    s.done, s.total, cancelled ? "취소됨" :
                    failure == null ? "전송 완료" : "전송 실패",
                    failure == null ? "" : String.valueOf(failure.getMessage()),
                    s.completed, s.count, job.moving);
            pending = null;
        }
    }

    private PendingIntent openIntent() {
        Intent launch = new Intent(this, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launch.putExtra(EXTRA_SHOW, true);
        return PendingIntent.getActivity(this, 1850, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification notification() {
        State s = state;
        int percent = s.percent();
        String progress = percent < 0 ? "진행 중" : percent + "%";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.filehub_aio_icon)
                .setContentTitle((s.moving ? "파일 이동 " : "파일 복사 ") + progress)
                .setContentText(s.name + " · " + TransferProgressFormat.amount(s.done, s.total))
                .setContentIntent(openIntent())
                .setOnlyAlertOnce(true)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(100, Math.max(0, percent), percent < 0);
        PendingIntent cancel = PendingIntent.getService(this, 1851,
                new Intent(this, FileTransferService.class).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                "취소", cancel);
        return builder.build();
    }

    private void publishNotification(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastNotification < 450) return;
        lastNotification = now;
        try { notices.notify(NOTICE_ID, notification()); }
        catch (RuntimeException ignored) {}
    }

    @Override public void onDestroy() {
        if (runningService == this) runningService = null;
        if (transferWakeLock != null && transferWakeLock.isHeld())
            transferWakeLock.release();
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
