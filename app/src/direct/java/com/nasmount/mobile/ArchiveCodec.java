package com.nasmount.mobile;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import java.io.*;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Bounded, path-safe ZIP/7z extract-and-create and RAR extract-only codec. */
final class ArchiveCodec {
    private ArchiveCodec() {}
    static final long MAX_INPUT_BYTES = 512L * 1024 * 1024;
    static final long MAX_OUTPUT_BYTES = 1024L * 1024 * 1024;
    static final int MAX_ENTRIES = 10000;
    private static final int MAX_DEPTH = 48;

    static boolean isArchive(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".zip") || lower.endsWith(".7z") ||
                lower.endsWith(".rar") || lower.endsWith(".cbz") ||
                lower.endsWith(".cb7") || lower.endsWith(".cbr");
    }

    static boolean canCreate(String format) {
        return "zip".equalsIgnoreCase(format) || "7z".equalsIgnoreCase(format);
    }

    static String baseFolder(String filename) {
        if (filename == null) return "압축해제";
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        return safeSingleName(stem);
    }

    static String safeSingleName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") ||
                name.contains("/") || name.contains("\\") || name.contains(":") ||
                name.indexOf('\0') >= 0)
            throw new IllegalArgumentException(L10n.t("올바르지 않은 파일명입니다."));
        return name;
    }

    private static String normalizedEntry(String name) throws IOException {
        if (name == null || name.isEmpty() || name.indexOf('\0') >= 0)
            throw new IOException("압축 파일에 잘못된 경로가 포함됐습니다.");
        String portable = name.replace('\\', '/');
        if (portable.startsWith("/") || portable.startsWith("//") ||
                portable.matches("(?i)^[a-z]:.*"))
            throw new IOException("압축 파일에 절대 경로가 포함됐습니다.");
        String[] segments = portable.split("/", -1);
        int depth = 0;
        StringBuilder clean = new StringBuilder();
        for (String segment : segments) {
            if (segment.isEmpty()) continue;
            if (segment.equals(".") || segment.equals("..") ||
                    segment.indexOf(':') >= 0 || segment.length() > 255)
                throw new IOException("압축 파일에 상위 폴더 이동 경로가 포함됐습니다.");
            if (++depth > MAX_DEPTH)
                throw new IOException("압축 파일 폴더 깊이가 제한을 넘었습니다.");
            if (clean.length() != 0) clean.append('/');
            clean.append(segment);
        }
        if (clean.length() == 0) throw new IOException("압축 파일 항목명이 비어 있습니다.");
        return clean.toString();
    }

    static final class Budget {
        long total;
        int entries;
        final Set<String> seen = new HashSet<>();
        void register(String path) throws IOException {
            if (++entries > MAX_ENTRIES)
                throw new IOException("압축 파일 항목이 너무 많습니다.");
            // Do not allow two archive entries to silently overwrite one another.
            if (!seen.add(path.toLowerCase(Locale.ROOT)))
                throw new IOException("압축 안에 중복 항목이 있습니다: " + path);
        }
        void add(int bytes) throws IOException {
            total += bytes;
            if (total > MAX_OUTPUT_BYTES)
                throw new IOException("압축해제 용량이 1GB 제한을 넘었습니다.");
        }
    }

    private static File resolve(File folder, String path) throws IOException {
        File target = new File(folder, normalizedEntry(path));
        String base = folder.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(base))
            throw new IOException("압축 파일 경로가 대상 폴더를 벗어납니다.");
        return target;
    }

    private static void writeEntry(InputStream input, File target, Budget budget)
            throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException(L10n.t("압축해제 폴더를 만들지 못했습니다."));
        if (target.exists())
            throw new IOException("중복된 압축 파일 경로입니다.");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] block = new byte[65536];
            int len;
            while ((len = input.read(block)) != -1) {
                if (len > 0) { budget.add(len); out.write(block, 0, len); }
            }
        }
    }

    private static void writeSevenZ(SevenZFile reader, File target, Budget budget)
            throws IOException {
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("압축해제 폴더 생성 실패");
        if (target.exists()) throw new IOException("중복 압축 항목입니다.");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
            byte[] block = new byte[65536];
            int len;
            while ((len = reader.read(block)) != -1) {
                if (len > 0) { budget.add(len); out.write(block, 0, len); }
            }
        }
    }

    static int extract(File archive, File outputDir) throws Exception {
        if (archive == null || !archive.isFile() || archive.length() > MAX_INPUT_BYTES)
            throw new IOException("압축 파일을 읽을 수 없거나 512MB 제한을 넘었습니다.");
        if (!outputDir.isDirectory() && !outputDir.mkdirs())
            throw new IOException(L10n.t("압축해제 폴더를 만들 수 없습니다."));
        Budget budget = new Budget();
        String name = archive.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".zip")||name.endsWith(".cbz")) {
            try (ZipInputStream zip = new ZipInputStream(
                    new BufferedInputStream(new FileInputStream(archive)))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String safe = normalizedEntry(entry.getName());
                    budget.register(safe);
                    File target = resolve(outputDir, safe);
                    if (entry.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs())
                            throw new IOException(L10n.t("압축 폴더 생성 실패"));
                    } else {
                        writeEntry(zip, target, budget);
                    }
                    zip.closeEntry();
                }
            }
        } else if (name.endsWith(".7z")||name.endsWith(".cb7")) {
            try (SevenZFile seven = SevenZFile.builder().setFile(archive).get()) {
                SevenZArchiveEntry entry;
                while ((entry = seven.getNextEntry()) != null) {
                    String safe = normalizedEntry(entry.getName());
                    budget.register(safe);
                    File target = resolve(outputDir, safe);
                    if (entry.isAntiItem())
                        throw new IOException("7z 삭제 항목은 지원하지 않습니다.");
                    if (entry.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs())
                            throw new IOException(L10n.t("압축 폴더 생성 실패"));
                    } else {
                        writeSevenZ(seven, target, budget);
                    }
                }
            }
        } else if (name.endsWith(".rar")||name.endsWith(".cbr")) {
            try (Archive rar = new Archive(archive)) {
                if (rar.isEncrypted() || rar.isPasswordProtected())
                    throw new IOException("암호화된 RAR은 아직 지원하지 않습니다.");
                FileHeader header;
                while ((header = rar.nextFileHeader()) != null) {
                    String safe = normalizedEntry(header.getFileName());
                    budget.register(safe);
                    File target = resolve(outputDir, safe);
                    if (header.isDirectory()) {
                        if (!target.isDirectory() && !target.mkdirs())
                            throw new IOException(L10n.t("압축 폴더 생성 실패"));
                    } else {
                        if (header.getUnpSize() > MAX_OUTPUT_BYTES - budget.total)
                            throw new IOException("압축해제 용량 제한을 넘었습니다.");
                        try (InputStream in = rar.getInputStream(header)) {
                            if (in == null) throw new IOException("RAR 파일을 읽지 못했습니다.");
                            writeEntry(in, target, budget);
                        }
                    }
                }
            }
        } else throw new IOException(L10n.t("지원하지 않는 압축 형식입니다."));
        return budget.entries;
    }

    static void create(File sourceFolder, File output, String format) throws Exception {
        if (!canCreate(format)) throw new IOException("RAR 압축 생성은 지원하지 않습니다.");
        if (!sourceFolder.isDirectory()) throw new IOException(L10n.t("압축 대상 폴더가 없습니다."));
        Budget budget = new Budget();
        if ("zip".equalsIgnoreCase(format)) {
            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(output)))) {
                for (File child : sortedChildren(sourceFolder))
                    writeZip(child, child.getName(), zip, budget, 0);
            }
        } else {
            try (SevenZOutputFile seven = new SevenZOutputFile(output)) {
                for (File child : sortedChildren(sourceFolder))
                    write7z(child, child.getName(), seven, budget, 0);
            }
        }
        if (output.length() > MAX_INPUT_BYTES)
            throw new IOException("생성된 압축 파일이 512MB를 넘었습니다.");
    }

    private static File[] sortedChildren(File directory) throws IOException {
        File[] list = directory.listFiles();
        if (list == null) throw new IOException("압축 대상 폴더를 읽을 수 없습니다.");
        java.util.Arrays.sort(list, (a,b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    private static void validateSource(File file, Budget budget, int depth, String path)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("폴더가 너무 깊습니다.");
        if (Files.isSymbolicLink(file.toPath()))
            throw new IOException("심볼릭 링크는 압축하지 않습니다.");
        budget.register(normalizedEntry(path));
        if (file.isFile()) {
            if (!file.canRead()) throw new IOException(L10n.t("파일을 읽을 수 없습니다."));
            if (budget.total + file.length() > MAX_OUTPUT_BYTES)
                throw new IOException("압축할 원본 데이터가 1GB 제한을 넘었습니다.");
            budget.total += file.length();
        } else if (!file.isDirectory()) {
            throw new IOException("알 수 없는 파일 종류가 포함됐습니다.");
        }
    }

    private static void writeZip(File file, String path, ZipOutputStream zip,
                                 Budget budget, int depth) throws IOException {
        validateSource(file, budget, depth, path);
        ZipEntry entry = new ZipEntry(path.replace('\\', '/') +
                (file.isDirectory() ? "/" : ""));
        zip.putNextEntry(entry);
        if (file.isFile()) try (InputStream in = new BufferedInputStream(
                new FileInputStream(file))) {
            byte[] block = new byte[65536];
            int len;
            while ((len = in.read(block)) != -1) zip.write(block, 0, len);
        }
        zip.closeEntry();
        if (file.isDirectory())
            for (File child : sortedChildren(file))
                writeZip(child, path + "/" + child.getName(), zip, budget, depth + 1);
    }

    private static void write7z(File file, String path, SevenZOutputFile seven,
                                Budget budget, int depth) throws IOException {
        validateSource(file, budget, depth, path);
        SevenZArchiveEntry entry = new SevenZArchiveEntry();
        entry.setName(path.replace('\\', '/'));
        entry.setDirectory(file.isDirectory());
        entry.setSize(file.isDirectory() ? 0 : file.length());
        seven.putArchiveEntry(entry);
        if (file.isFile()) try (InputStream in = new BufferedInputStream(
                new FileInputStream(file))) {
            byte[] block = new byte[65536];
            int len;
            while ((len = in.read(block)) != -1) seven.write(block, 0, len);
        }
        seven.closeArchiveEntry();
        if (file.isDirectory())
            for (File child : sortedChildren(file))
                write7z(child, path + "/" + child.getName(), seven, budget, depth + 1);
    }
}
