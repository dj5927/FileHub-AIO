package com.nasmount.mobile;

import android.content.Context;
import java.io.*;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Stages archive work in private cache, then commits through the same
 * provider-aware upload/transfer engine used by NAS Mount file manager.
 * No remote originals are deleted by this helper.
 */
final class ArchiveWorkflows {
    private ArchiveWorkflows() {}
    static final class Result {
        final int entries;
        final String destinationName;
        Result(int entries, String destinationName) {
            this.entries = entries;
            this.destinationName = destinationName;
        }
    }

    static Result extract(Context ctx, FileTransferEngine.Item input,
                          FileTransferEngine.Destination target) throws Exception {
        if (input.directory || !ArchiveCodec.isArchive(input.name))
            throw new IOException("ZIP·7z·RAR 압축 파일만 해제할 수 있습니다.");
        String outputName = chooseAvailableFolderName(
                ArchiveCodec.baseFolder(input.name), existingNames(target));
        File job = temporaryRoot(ctx);
        try {
            File stagedArchive = new File(job, "source" +
                    input.name.substring(input.name.lastIndexOf('.')));
            try (InputStream in = FileTransferEngine.openInput(ctx, input);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(stagedArchive))) {
                boundedCopy(in, out, ArchiveCodec.MAX_INPUT_BYTES);
            }
            File output = new File(job, outputName);
            int count = ArchiveCodec.extract(stagedArchive, output);
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    FileTransferEngine.transfer(ctx,
                            java.util.Collections.singletonList(FileTransferEngine.local(output)),
                            target, false, false);
                    return new Result(count, outputName);
                } catch (FileTransferEngine.NameConflict race) {
                    // Another device may create the target during extraction.
                    Set<String> occupied = existingNames(target);
                    occupied.add(outputName);
                    String next = chooseAvailableFolderName(
                            ArchiveCodec.baseFolder(input.name), occupied);
                    File renamed = new File(job, next);
                    if (!output.renameTo(renamed))
                        throw new IOException("압축 임시 폴더 이름을 바꾸지 못했습니다.");
                    output = renamed;
                    outputName = next;
                }
            }
            throw new IOException("대상 폴더가 여러 번 동시에 생성되어 중단했습니다.");
        } finally {
            cleanup(job);
        }
    }

    // Resolves "문서", "문서 (1)", "문서 (2)" ... without changing existing
    // data. Case-insensitive just like the existing NAS transfer preflight.
    static String chooseAvailableFolderName(String baseName, Set<String> existing)
            throws IOException {
        ArchiveCodec.safeSingleName(baseName);
        Set<String> occupied = new HashSet<>();
        for (String name : existing) occupied.add(name.toLowerCase(Locale.ROOT));
        if (!occupied.contains(baseName.toLowerCase(Locale.ROOT))) return baseName;
        for (int i = 1; i < 100000; i++) {
            String candidate = baseName + " (" + i + ")";
            if (!occupied.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
        throw new IOException("압축해제 폴더 이름의 빈 번호를 찾지 못했습니다.");
    }

    private static Set<String> existingNames(FileTransferEngine.Destination target)
            throws Exception {
        Set<String> names = new HashSet<>();
        switch (target.kind) {
            case "local":
                File[] children = target.local.listFiles();
                if (children == null) throw new IOException("대상 폴더 목록을 읽지 못했습니다.");
                for (File f : children) names.add(f.getName());
                break;
            case "saf":
                for (androidx.documentfile.provider.DocumentFile doc :
                        target.saf.listFiles())
                    if (doc.getName() != null) names.add(doc.getName());
                break;
            case "remote":
                for (Models.Entry e : RemoteProvider.list(target.remote, target.path))
                    names.add(e.name);
                break;
            default:
                throw new IOException("현재 위치에는 압축을 해제할 수 없습니다.");
        }
        return names;
    }

    static Result compress(Context ctx, List<FileTransferEngine.Item> files,
                           FileTransferEngine.Destination target,
                           String outputName, String format) throws Exception {
        if (files == null || files.isEmpty())
            throw new IOException("압축할 파일을 먼저 선택하세요.");
        if (!ArchiveCodec.canCreate(format))
            throw new IOException("새 압축파일은 ZIP 또는 7z로 만들 수 있습니다.");
        String name = ArchiveCodec.safeSingleName(outputName);
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith("." + format))
            name += "." + format.toLowerCase(java.util.Locale.ROOT);
        File job = temporaryRoot(ctx);
        try {
            File sources = new File(job, "selected");
            if (!sources.mkdir()) throw new IOException("압축 임시 폴더 생성 실패");
            int[] budget = {0};
            long[] copied = {0};
            for (FileTransferEngine.Item item : files)
                stageOne(ctx, item, sources, 0, budget, copied);
            File output = new File(job, name);
            ArchiveCodec.create(sources, output, format);
            FileTransferEngine.transfer(ctx,
                    java.util.Collections.singletonList(FileTransferEngine.local(output)),
                    target, false, false);
            return new Result(budget[0], name);
        } finally {
            cleanup(job);
        }
    }

    private static void stageOne(Context ctx, FileTransferEngine.Item src,
                                 File directory, int depth, int[] count,
                                 long[] total) throws Exception {
        if (++count[0] > ArchiveCodec.MAX_ENTRIES || depth > 48)
            throw new IOException("압축 대상 항목이 너무 많거나 깊습니다.");
        String name = ArchiveCodec.safeSingleName(src.name);
        File dest = new File(directory, name);
        String base = directory.getCanonicalPath() + File.separator;
        if (!dest.getCanonicalPath().startsWith(base))
            throw new IOException("압축 대상 경로가 안전하지 않습니다.");
        if (dest.exists()) throw new IOException("압축 대상에 중복 파일명이 있습니다: " + name);
        if (src.local != null && Files.isSymbolicLink(src.local.toPath()))
            throw new IOException("심볼릭 링크는 압축할 수 없습니다.");
        if (src.directory) {
            if (!dest.mkdir()) throw new IOException("압축 임시 폴더 생성 실패");
            for (FileTransferEngine.Item child : FileTransferEngine.children(src))
                stageOne(ctx, child, dest, depth + 1, count, total);
        } else {
            try (InputStream input = FileTransferEngine.openInput(ctx, src);
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] bytes = new byte[65536];
                int read;
                while ((read = input.read(bytes)) != -1) {
                    total[0] += read;
                    if (total[0] > ArchiveCodec.MAX_OUTPUT_BYTES)
                        throw new IOException("압축할 전체 원본 크기가 1GB를 초과합니다.");
                    output.write(bytes, 0, read);
                }
            }
        }
    }

    private static long boundedCopy(InputStream input, OutputStream out, long max)
            throws IOException {
        long total = 0;
        byte[] bytes = new byte[65536];
        int len;
        while ((len = input.read(bytes)) != -1) {
            total += len;
            if (total > max)
                throw new IOException("압축 원본이 512MB 제한을 초과합니다.");
            out.write(bytes, 0, len);
        }
        return total;
    }

    private static File temporaryRoot(Context context) throws IOException {
        File parent = new File(context.getCacheDir(), "archive_work");
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("임시 작업 폴더 접근 실패");
        File job = new File(parent, UUID.randomUUID().toString());
        if (!job.mkdir()) throw new IOException("임시 압축 작업을 시작하지 못했습니다.");
        return job;
    }

    private static void cleanup(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) cleanup(child);
        }
        if (!file.delete()) file.deleteOnExit();
    }
}
