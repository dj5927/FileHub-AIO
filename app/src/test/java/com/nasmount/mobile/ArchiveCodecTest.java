package com.nasmount.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public final class ArchiveCodecTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File files() throws Exception {
        File source = tmp.newFolder("source");
        File nested = new File(source, "문서");
        assertTrue(nested.mkdir());
        Files.write(new File(nested, "테스트.txt").toPath(),
                "가나다 Hangul 123".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(source, "book.csv").toPath(),
                "a,b\n3,4\n".getBytes(StandardCharsets.UTF_8));
        return source;
    }

    @Test public void zipRoundTripPreservesTreeAndKoreanNames() throws Exception {
        File input = files();
        File archive = new File(tmp.getRoot(), "result.zip");
        ArchiveCodec.create(input, archive, "zip");
        assertTrue(archive.isFile());
        File output = tmp.newFolder("extracted");
        assertEquals(3, ArchiveCodec.extract(archive, output));
        assertArrayEquals(
                Files.readAllBytes(new File(input, "문서/테스트.txt").toPath()),
                Files.readAllBytes(new File(output, "문서/테스트.txt").toPath()));
        assertArrayEquals(
                Files.readAllBytes(new File(input, "book.csv").toPath()),
                Files.readAllBytes(new File(output, "book.csv").toPath()));
    }

    @Test public void sevenZipRoundTripPreservesContent() throws Exception {
        File input = files();
        File archive = new File(tmp.getRoot(), "result.7z");
        ArchiveCodec.create(input, archive, "7z");
        assertTrue(archive.length() > 0);
        File output = tmp.newFolder("7z-extracted");
        assertEquals(3, ArchiveCodec.extract(archive, output));
        assertEquals("가나다 Hangul 123", new String(Files.readAllBytes(
                new File(output, "문서/테스트.txt").toPath()), StandardCharsets.UTF_8));
    }

    private File maliciousZip(String... names) throws Exception {
        File z = new File(tmp.getRoot(), "unsafe.zip");
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(z))) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(new byte[]{17});
                zip.closeEntry();
            }
        }
        return z;
    }

    @Test public void zipTraversalIsBlockedAndNeverWritesOutsideDestination()
            throws Exception {
        File z = maliciousZip("../outside.txt");
        File output = tmp.newFolder("safe");
        assertThrows(IOException.class, () -> ArchiveCodec.extract(z, output));
        assertFalse(new File(tmp.getRoot(), "outside.txt").exists());
    }

    @Test public void zipAbsoluteWindowsPathIsBlocked() throws Exception {
        File z = maliciousZip("C:\\Windows\\evil.txt");
        assertThrows(IOException.class, () ->
                ArchiveCodec.extract(z, tmp.newFolder("safe-2")));
    }

    @Test public void duplicateZipItemsCannotSilentlyOverwrite() throws Exception {
        File z = maliciousZip("FILE.TXT", "file.txt");
        assertThrows(IOException.class, () ->
                ArchiveCodec.extract(z, tmp.newFolder("safe-3")));
    }

    @Test public void fileTypeRoutingAndRarWriteRestriction() throws Exception {
        assertTrue(ArchiveCodec.isArchive("사진.ZIP"));
        assertTrue(ArchiveCodec.isArchive("리스트.7Z"));
        assertTrue(ArchiveCodec.isArchive("자료.rAr"));
        assertFalse(ArchiveCodec.isArchive("사진.jpg"));
        assertEquals("문서", ArchiveCodec.baseFolder("문서.rar"));
        assertFalse(ArchiveCodec.canCreate("rar"));
        assertThrows(IOException.class, () ->
                ArchiveCodec.create(tmp.newFolder("for-rar"),
                        new File(tmp.getRoot(), "not-supported.rar"), "rar"));
    }

    @Test public void unsafeSelectedArchiveNameIsRejected() {
        for (String bad : new String[]{"../escape", "/tmp/x", "C:\\data", "..", ""})
            assertThrows(IllegalArgumentException.class,
                    () -> ArchiveCodec.safeSingleName(bad));
    }
}
