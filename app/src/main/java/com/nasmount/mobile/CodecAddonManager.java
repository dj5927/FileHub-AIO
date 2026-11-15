package com.nasmount.mobile;

import android.content.Context;
import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;

/**
 * Imports user-supplied codec archives to the NAS Mount app-owned folder.
 *
 * These files are NOT dynamically linked or executed. Only the reviewed
 * FFmpeg renderers bundled in the signed APK can be used for playback.
 */
final class CodecAddonManager {
    private static final long MAX_ITEM_BYTES = 192L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 512L * 1024 * 1024;
    private static final int MAX_FILES = 30;
    interface Progress {
        void onFile(String name, int number, int total);
    }
    static final class Result {
        int moved;
        int copied;
        int skipped;
        int failed;
        String directory;
        String summary() {
            return "NAS Mount 코덱 폴더: " + directory + "\n" +
                    "이동: " + moved + "개\n복사(원본 유지): " + copied +
                    "개\n건너뜀: " + skipped + "개\n실패: " + failed +
                    "개\n\n가져온 외부 파일은 보관용입니다. " +
                    "재생에는 APK에 내장된 FFmpeg 디코더가 자동 사용됩니다.";
        }
    }

    private CodecAddonManager() {}

    static boolean acceptedFilename(String name) {
        if (name == null || name.isEmpty()) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".aar") || lower.endsWith(".zip")
                || lower.endsWith(".so") || lower.matches(".*\\.so\\.[a-z0-9_]+");
    }

    static String safeFilename(String name) {
        if (name == null) return "";
        return name.replaceAll("[^\\p{L}\\p{N}.()_ -]", "_")
                .replaceAll("^\\.+", "_");
    }

    static File codecDirectory(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File directory = new File(base, "NASMount/codecs");
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("코덱 보관 폴더를 만들 수 없습니다.");
        return directory;
    }

    static String inventory(Context context) {
        File[] files = codecDirectory(context).listFiles();
        if (files == null || files.length == 0)
            return "NAS Mount 전용 폴더에 가져온 코덱 파일 없음";
        Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        StringBuilder summary = new StringBuilder();
        int count = 0;
        for (File f : files) {
            if (!f.isFile() || !acceptedFilename(f.getName())) continue;
            if (count++ < 8) {
                if (summary.length() > 0) summary.append("\n");
                summary.append(f.getName()).append(" (")
                        .append(f.length() / 1024).append(" KB)");
            }
        }
        return count == 0 ? "가져온 코덱 파일 없음" :
                count + "개 보관 중\n" + summary;
    }

    static Result importFromFolder(Context context, Uri treeUri, Progress progress)
            throws Exception {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.isDirectory() || !tree.canRead())
            throw new java.io.IOException("선택한 코덱 폴더를 읽을 수 없습니다.");
        DocumentFile[] items = tree.listFiles();
        File destination = codecDirectory(context);
        Result result = new Result();
        result.directory = destination.getAbsolutePath();
        long totalBytes = 0;
        int attempted = 0;
        for (DocumentFile item : items) {
            String originalName = item.getName();
            if (!item.isFile() || !acceptedFilename(originalName)) {
                result.skipped++;
                continue;
            }
            if (++attempted > MAX_FILES) {
                result.skipped++;
                continue;
            }
            long declared = item.length();
            if (declared > MAX_ITEM_BYTES ||
                    (declared > 0 && totalBytes + declared > MAX_TOTAL_BYTES)) {
                result.skipped++;
                continue;
            }
            if (progress != null) progress.onFile(originalName, attempted, items.length);
            String clean = safeFilename(originalName);
            if (clean.isEmpty()) { result.skipped++; continue; }
            File target = new File(destination, clean);
            int suffix = 2;
            while (target.exists()) {
                int extension = clean.lastIndexOf('.');
                target = new File(destination,
                        (extension > 0 ? clean.substring(0, extension) : clean) +
                                "_" + suffix++ +
                                (extension > 0 ? clean.substring(extension) : ""));
            }
            File part = File.createTempFile("codec_import_", ".part", destination);
            boolean completed = false;
            try {
                MessageDigest original = MessageDigest.getInstance("SHA-256");
                long copied = 0;
                try (InputStream input = context.getContentResolver()
                        .openInputStream(item.getUri());
                     FileOutputStream output = new FileOutputStream(part)) {
                    if (input == null) throw new java.io.IOException("파일 열기 실패");
                    byte[] chunk = new byte[32768];
                    int count;
                    while ((count = input.read(chunk)) != -1) {
                        copied += count;
                        if (copied > MAX_ITEM_BYTES ||
                                totalBytes + copied > MAX_TOTAL_BYTES)
                            throw new java.io.IOException("코덱 복사 용량 제한 초과");
                        output.write(chunk, 0, count);
                        original.update(chunk, 0, count);
                    }
                    output.getFD().sync();
                }
                MessageDigest duplicate = MessageDigest.getInstance("SHA-256");
                try (FileInputStream verify = new FileInputStream(part)) {
                    byte[] chunk = new byte[32768];
                    int count;
                    while ((count = verify.read(chunk)) != -1)
                        duplicate.update(chunk, 0, count);
                }
                if (!Arrays.equals(original.digest(), duplicate.digest()))
                    throw new java.io.IOException("파일 무결성 검사 실패");
                if (!part.renameTo(target))
                    throw new java.io.IOException("NAS Mount 폴더에 저장하지 못했습니다.");
                completed = true;
                totalBytes += copied;
                // The user requested a move. Delete the SAF source only after
                // a verified copy, and only if its provider grants write.
                if (item.canWrite() && item.delete()) result.moved++;
                else result.copied++;
            } catch (Exception problem) {
                result.failed++;
            } finally {
                if (!completed) part.delete();
            }
        }
        return result;
    }
}
