package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class CodecAddonManagerTest {
    @Test public void acceptsArchivesAndNativeLibraries() {
        assertTrue(CodecAddonManager.acceptedFilename("media3-ffmpeg.aar"));
        assertTrue(CodecAddonManager.acceptedFilename("ffmpeg.zip"));
        assertTrue(CodecAddonManager.acceptedFilename("libffmpeg.so"));
        assertTrue(CodecAddonManager.acceptedFilename("libffmpeg.mx.so.neon64"));
    }
    @Test public void rejectsNonCodecFiles() {
        assertFalse(CodecAddonManager.acceptedFilename("movie.mkv"));
        assertFalse(CodecAddonManager.acceptedFilename("run.sh"));
        assertFalse(CodecAddonManager.acceptedFilename("plugin.dex"));
    }
    @Test public void sanitizesImportNames() {
        assertEquals("_", CodecAddonManager.safeFilename("../").substring(0, 1));
        assertEquals("ffmpeg.so", CodecAddonManager.safeFilename("ffmpeg.so"));
    }
}
