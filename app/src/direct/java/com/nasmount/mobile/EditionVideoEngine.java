package com.nasmount.mobile;
import android.content.Context;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleExtractorsFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.FfmpegSubtitleSourceKt;
@androidx.media3.common.util.UnstableApi
final class EditionVideoEngine {
    static ExoPlayer create(Context context, DataSource.Factory dataFactory) {
        NextRenderersFactory renderers = new NextRenderersFactory(context);
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        DefaultMediaSourceFactory source = new DefaultMediaSourceFactory(
                context, new FfmpegSubtitleExtractorsFactory()).setDataSourceFactory(dataFactory);
        MediaSource.Factory media = FfmpegSubtitleSourceKt.withFfmpegSubtitles(source, dataFactory);
        return new ExoPlayer.Builder(context, renderers).setMediaSourceFactory(media).build();
    }
}
