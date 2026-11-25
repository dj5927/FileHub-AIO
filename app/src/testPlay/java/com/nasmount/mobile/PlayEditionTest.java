package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Locale;

public class PlayEditionTest {
    @Test public void storeBuildUsesSeparateIdentityAndNoRarEngine() {
        assertFalse(BuildConfig.DIRECT_EDITION);
        assertEquals("com.filehubaio.mobile",BuildConfig.APPLICATION_ID);
        assertEquals("filehubaio-play",BuildConfig.PAIR_SCHEME);
        assertEquals(3,OverflowMenuItems.labelsForEdition(false).length);
        assertFalse(ArchiveCodec.isArchive("a.rar"));
        assertFalse(ArchiveCodec.isArchive("a.cbr"));
        assertFalse(ComicArchive.supports("a.cbr"));
        assertTrue(ArchiveCodec.isArchive("a.zip"));
        assertTrue(ComicArchive.supports("a.cbz"));
        assertTrue(ExternalApps.rar("a.RAR"));
        assertTrue(ExternalApps.rar("a.cbr"));
        assertEquals("application/vnd.rar",ExternalApps.archiveMime("a.rar"));
        assertEquals("application/x-cbr",ExternalApps.archiveMime("a.cbr"));
    }
    @Test public void excludedImplementationsCannotBeLoaded() {
        for(String name:new String[]{"com.github.junrar.Archive",
                "io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory",
                "com.nasmount.mobile.OtaReleasePolicy",
                "com.nasmount.mobile.CodecAddonManager",
                "com.nasmount.mobile.ApkInstallStaging"}) {
            try { Class.forName(name); fail("Prohibited Play dependency: "+name); }
            catch(ClassNotFoundException expected) {}
        }
    }
}
