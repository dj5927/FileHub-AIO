package com.nasmount.mobile;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Office-only payload checks. Ordinary zero-byte file transfers remain legal. */
final class OfficeSaveIntegrity {
    private OfficeSaveIntegrity() {}

    static long requirePayload(File file, long max) throws IOException {
        long length = file == null ? 0 : file.length();
        if (file == null || !file.isFile() || length <= 0 || length > max)
            throw new IOException("Office save rejected: empty or invalid document payload");
        return length;
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static byte[] digest(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest hash = sha256();
            byte[] block = new byte[65536];
            int n;
            while ((n = in.read(block)) != -1) if (n > 0) hash.update(block, 0, n);
            return hash.digest();
        }
    }

    static void verify(InputStream input, long expected, byte[] hash) throws IOException {
        if (input == null || expected <= 0) throw new IOException("Office verification has no payload");
        MessageDigest actual = sha256();
        long length = 0;
        byte[] block = new byte[65536];
        int n;
        while ((n = input.read(block)) != -1) {
            if (n == 0) continue;
            length += n;
            if (length > expected) throw new IOException("Office payload length mismatch");
            actual.update(block, 0, n);
        }
        if (length != expected || !MessageDigest.isEqual(hash, actual.digest()))
            throw new IOException("Office payload length or checksum mismatch");
    }

    static void copyExact(InputStream input, OutputStream output, long expected) throws IOException {
        if (input == null || output == null || expected <= 0)
            throw new IOException("Office save rejected: empty document");
        long length = 0;
        byte[] block = new byte[65536];
        int n;
        while ((n = input.read(block)) != -1) {
            if (n == 0) continue;
            length += n;
            if (length > expected) throw new IOException("Office payload grew during save");
            output.write(block, 0, n);
        }
        if (length != expected) throw new IOException("Office payload ended before expected length");
        output.flush();
    }

    // External editors can still hold a write grant. Never upload their
    // mutable staging file directly; snapshot and check it first.
    static File snapshot(File source, File directory, long max) throws IOException {
        long length = requirePayload(source, max);
        File copy = File.createTempFile("office-save-", ".tmp", directory);
        boolean complete = false;
        try {
            try (InputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(copy)) {
                copyExact(in, out, length);
                out.getFD().sync();
            }
            if (source.length() != length ||
                    !MessageDigest.isEqual(digest(source), digest(copy)))
                throw new IOException("Office edit changed while capturing its save payload");
            complete = true;
            return copy;
        } finally { if (!complete) copy.delete(); }
    }

    // The same-filesystem atomic replacement leaves the old file untouched
    // if the staged write fails. Never fall back to truncating the original.
    static void writeLocal(File source, File destination, long max) throws IOException {
        long length = requirePayload(source, max);
        byte[] hash = digest(source);
        File pending = File.createTempFile(".filehub-office-", ".tmp", destination.getParentFile());
        try {
            try (InputStream in = new FileInputStream(source);
                 FileOutputStream out = new FileOutputStream(pending)) {
                copyExact(in, out, length);
                out.getFD().sync();
            }
            try (InputStream in = new FileInputStream(pending)) { verify(in, length, hash); }
            Files.move(pending.toPath(), destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (InputStream in = new FileInputStream(destination)) { verify(in, length, hash); }
        } finally { if (pending.exists()) pending.delete(); }
    }
}
