package com.nasmount.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public final class ApkInstallStagingTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] fakeArchive(boolean manifest) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(manifest ?
                    "AndroidManifest.xml" : "not_a_manifest"));
            zip.write(new byte[160]);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("classes.dex"));
            zip.write(new byte[170]);
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    @Test public void sourceBytesAreStagedForPrivateFileProvider() throws Exception {
        byte[] archive = fakeArchive(true);
        File staged = ApkInstallStaging.stage(new ByteArrayInputStream(archive),
                tmp.newFolder("cache"));
        assertTrue(staged.isFile());
        assertTrue(staged.getName().endsWith(".apk"));
        assertArrayEquals(archive, Files.readAllBytes(staged.toPath()));
        ApkInstallStaging.verifyArchive(staged);
        assertEquals("apk_install", staged.getParentFile().getName());
    }

    @Test public void rejectNonApkAndRemoveTemporaryFile() throws Exception {
        File cache = tmp.newFolder("cache-invalid");
        assertThrows(IOException.class, () ->
                ApkInstallStaging.stage(new ByteArrayInputStream(
                        "not a zip archive".getBytes()), cache));
        assertEquals(0, new File(cache,"apk_install").listFiles().length);
    }

    @Test public void zipWithoutAndroidManifestIsNotAnApk() throws Exception {
        File cache = tmp.newFolder("cache-without-manifest");
        assertThrows(IOException.class, () ->
                ApkInstallStaging.stage(new ByteArrayInputStream(fakeArchive(false)), cache));
        assertEquals(0, new File(cache,"apk_install").listFiles().length);
    }

    @Test public void binaryFromRemoteCanBeStreamedWithoutFullMemoryCopy()
            throws Exception {
        byte[] archive = fakeArchive(true);
        ByteArrayInputStream deliberatelySmallPieces = new ByteArrayInputStream(archive) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(7, len));
            }
        };
        File staged = ApkInstallStaging.stage(deliberatelySmallPieces,
                tmp.newFolder("cache-network"));
        assertArrayEquals(archive, Files.readAllBytes(staged.toPath()));
    }
}
