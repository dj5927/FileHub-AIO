package com.nasmount.mobile;
import android.content.Context;
import androidx.media3.datasource.DataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;

@androidx.media3.common.util.UnstableApi
final class EditionVideoEngine {
    static ExoPlayer create(Context context, DataSource.Factory dataFactory) {
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(context);
        renderers.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);
        return new ExoPlayer.Builder(context, renderers)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(context)
                        .setDataSourceFactory(dataFactory)).build();
    }
}
