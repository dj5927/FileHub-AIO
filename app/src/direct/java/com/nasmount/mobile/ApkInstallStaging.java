package com.nasmount.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipFile;

/**
 * Stages package bytes in the app's private cache for a FileProvider URI.
 * Never executes APK contents or bypasses Android's system package installer.
 */
final class ApkInstallStaging {
    static final long MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private ApkInstallStaging() {}

    static File stage(InputStream source, File cacheDir) throws IOException {
        File dir = new File(cacheDir, "apk_install");
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("APK 설치용 임시 폴더를 만들 수 없습니다.");
        File output = File.createTempFile("nasmount-", ".apk", dir);
        boolean success = false;
        try {
            long total = 0;
            try (OutputStream to = new FileOutputStream(output)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (count == 0) continue;
                    total += count;
                    if (total > MAX_BYTES)
                        throw new IOException("설치 APK 크기가 2GB를 넘습니다.");
                    to.write(buffer, 0, count);
                }
            }
            verifyArchive(output);
            success = true;
            return output;
        } finally {
            if (!success && output.exists()) output.delete();
        }
    }

    static void verifyArchive(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() < 128 ||
                file.length() > MAX_BYTES)
            throw new IOException("올바른 APK 설치 파일이 아닙니다.");
        try (ZipFile archive = new ZipFile(file)) {
            if (archive.getEntry("AndroidManifest.xml") == null)
                throw new IOException("AndroidManifest.xml이 없는 APK입니다.");
        } catch (IOException error) {
            throw new IOException("APK 파일 형식 또는 다운로드 무결성을 확인할 수 없습니다.", error);
        }
    }
}
