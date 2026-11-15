package com.nasmount.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import java.io.IOException;

/**
 * Foreground mediaPlayback service, independent of the explorer Activity.
 * The same streaming adapter used by video enables NAS and cloud audio.
 */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MusicPlaybackService extends Service {
    static final String ACTION_OPEN="com.nasmount.mobile.music.OPEN";
    static final String ACTION_TOGGLE="com.nasmount.mobile.music.TOGGLE";
    static final String ACTION_STOP="com.nasmount.mobile.music.STOP";
    static final String ACTION_SEEK="com.nasmount.mobile.music.SEEK";
    static final String EXTRA_URI="music_uri";
    static final String EXTRA_NAME="music_name";
    static final String EXTRA_PROFILE="music_profile_id";
    static final String EXTRA_PATH="music_remote_path";
    static final String EXTRA_SIZE="music_remote_size";
    static final String EXTRA_SEEK_MS="music_seek_ms";
    private static final String CHANNEL="nasmount_music_playback";
    private static final int NOTIFICATION_ID=1838;

    static volatile boolean active=false;
    static volatile boolean playing=false;
    static volatile String title="";
    static volatile String error="";
    static volatile long position=0;
    static volatile long duration=0;

    private ExoPlayer player;
    private NotificationManager notices;
    private final Handler heartbeat=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable(){
        @Override public void run(){
            if(player!=null) {
                playing=player.isPlaying();
                long p=player.getCurrentPosition(), d=player.getDuration();
                position=Math.max(0,p);
                duration=(d==C.TIME_UNSET||d<0)?0:d;
                heartbeat.postDelayed(this,650);
            }
        }
    };

    @Override public void onCreate(){
        super.onCreate();
        notices=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) {
            NotificationChannel ch=new NotificationChannel(CHANNEL,
                    "NAS Mount 음악 재생",NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("백그라운드 음악 재생 상태 및 조작");
            notices.createNotificationChannel(ch);
        }
    }

    @Override public int onStartCommand(Intent request,int flags,int startId){
        if(request==null){stopSelf();return START_NOT_STICKY;}
        String action=request.getAction();
        if(ACTION_STOP.equals(action)){
            if(player!=null)player.stop();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if(ACTION_TOGGLE.equals(action)){
            if(player!=null){
                if(player.isPlaying())player.pause();
                else player.play();
                updateNotification();
            }
            return START_NOT_STICKY;
        }
        if(ACTION_SEEK.equals(action)){
            if(player!=null&&player.getDuration()>0)
                player.seekTo(Math.max(0,Math.min(player.getDuration(),
                        request.getLongExtra(EXTRA_SEEK_MS,0))));
            return START_NOT_STICKY;
        }
        if(!ACTION_OPEN.equals(action))return START_NOT_STICKY;
        String requested=request.getStringExtra(EXTRA_NAME);
        title=requested==null?"음악 재생":requested;
        error="";
        position=0;
        duration=0;
        active=true;
        try {
            if(Build.VERSION.SDK_INT>=29) {
                startForeground(NOTIFICATION_ID,notification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID,notification());
            }
            if(player!=null){player.release();player=null;}
            String profileId=request.getStringExtra(EXTRA_PROFILE);
            if(profileId!=null&&!profileId.isEmpty()){
                Models.RemoteProfile remote=null;
                for(Models.RemoteProfile item:new SecureStore(this).activeRemotes()){
                    if(profileId.equals(item.id)){remote=item;break;}
                }
                if(remote==null)throw new IOException("원격 저장소 연결을 찾지 못했습니다.");
                String path=request.getStringExtra(EXTRA_PATH);
                if(path==null)throw new IOException("음악 파일 경로가 없습니다.");
                long size=request.getLongExtra(EXTRA_SIZE,C.LENGTH_UNSET);
                DefaultDataSource.Factory sources=new DefaultDataSource.Factory(this,
                        new VideoStreamDataSource.Factory(remote,path,size));
                player=new ExoPlayer.Builder(this)
                        .setMediaSourceFactory(new DefaultMediaSourceFactory(sources)).build();
                player.setMediaItem(MediaItem.fromUri(
                        Uri.parse("nasmount://audio/"+Uri.encode(title))));
            }else{
                String value=request.getStringExtra(EXTRA_URI);
                if(value==null||value.isEmpty())throw new IOException("음악 파일 위치가 없습니다.");
                player=new ExoPlayer.Builder(this).build();
                player.setMediaItem(MediaItem.fromUri(Uri.parse(value)));
            }
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),true);
            player.setHandleAudioBecomingNoisy(true);
            player.setWakeMode(C.WAKE_MODE_NETWORK);
            player.addListener(new Player.Listener(){
                @Override public void onIsPlayingChanged(boolean isPlaying){
                    playing=isPlaying;updateNotification();
                }
                @Override public void onPlaybackStateChanged(int state){
                    if(state==Player.STATE_ENDED){
                        playing=false;
                        updateNotification();
                    }
                }
                @Override public void onPlayerError(PlaybackException failure){
                    error="음악 재생 오류: "+failure.getErrorCodeName();
                    playing=false;
                    updateNotification();
                }
            });
            player.prepare();
            player.play();
            heartbeat.removeCallbacks(tick);
            heartbeat.post(tick);
            updateNotification();
        } catch(Exception failure){
            error="음악 파일을 재생할 수 없습니다: "+
                    failure.getClass().getSimpleName();
            playing=false;
            updateNotification();
        }
        return START_NOT_STICKY;
    }

    private PendingIntent serviceIntent(String action,int code){
        Intent i=new Intent(this,MusicPlaybackService.class).setAction(action);
        return PendingIntent.getService(this,code,i,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
    }
    private Notification notification(){
        Intent home=new Intent(this,MainActivity.class);
        home.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open=PendingIntent.getActivity(this,7,home,
                PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title.isEmpty()?"NAS Mount 음악":title)
                .setContentText(error.isEmpty()?(playing?"재생 중":"일시정지"):error)
                .setContentIntent(open)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(playing?android.R.drawable.ic_media_pause:
                                android.R.drawable.ic_media_play,
                        playing?"일시정지":"재생",serviceIntent(ACTION_TOGGLE,41))
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "종료",serviceIntent(ACTION_STOP,42));
        n.setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0,1));
        return n.build();
    }
    private void updateNotification(){
        if(active&&notices!=null)notices.notify(NOTIFICATION_ID,notification());
    }
    @Nullable @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        heartbeat.removeCallbacks(tick);
        if(player!=null){player.release();player=null;}
        active=false;playing=false;title="";error="";position=0;duration=0;
        super.onDestroy();
    }
}
