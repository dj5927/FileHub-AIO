package com.nasmount.mobile;

import android.content.Context;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FilterInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/**
 * Bounded-memory file manager operations across local, SAF and NAS providers.
 * Each source is removed only AFTER its complete destination has been created.
 * Existing names are never silently overwritten. Caller runs this off the UI thread.
 */
final class FileTransferEngine {
    private FileTransferEngine() {}
    private static final int MAX_DEPTH = 60;
    private static final int MAX_ENTRIES = 250_000;

    interface ProgressListener {
        void onProgress(long transferredBytes, long totalBytes,
                        String currentFile, int completedItems, int totalItems);
    }

    static final class TransferControl {
        private volatile boolean cancelled;
        private volatile Thread worker;
        private volatile InputStream activeInput;

        void bindCurrentThread() { worker = Thread.currentThread(); }
        void check() throws InterruptedIOException {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("파일 전송을 취소했습니다.");
        }
        void track(InputStream stream) throws InterruptedIOException {
            activeInput = stream;
            check();
        }
        void untrack(InputStream stream) {
            if (activeInput == stream) activeInput = null;
        }
        void cancel() {
            cancelled = true;
            Thread running = worker;
            if (running != null) running.interrupt();
            InputStream stream = activeInput;
            if (stream != null) {
                Thread closer = new Thread(() -> {
                    try { stream.close(); } catch (IOException ignored) {}
                }, "FileHub-transfer-cancel-close");
                closer.setDaemon(true);
                closer.start();
            }
        }
        boolean isCancelled() { return cancelled; }
    }

    private static final class TransferMeter {
        final ProgressListener listener;
        final long total;
        final int items;
        long transferred;
        long lastUpdateNanos;
        int completed;
        String fileName = "전송 준비 중...";

        TransferMeter(ProgressListener listener, long total, int items) {
            this.listener = listener;
            this.total = total;
            this.items = items;
            update(true);
        }

        void begin(String name) {
            fileName = name;
            update(true);
        }

        void bytes(long n) {
            if (n <= 0) return;
            transferred = safeAdd(transferred, n);
            update(false);
        }

        void directComplete(long size) {
            if (size > 0) bytes(size);
            update(true);
        }

        void completedItem() {
            completed++;
            update(true);
        }

        void finished() {
            if (total >= 0) transferred = total;
            completed = items;
            fileName = "전송 완료";
            update(true);
        }

        void update(boolean force) {
            if (listener == null) return;
            long now = System.nanoTime();
            if (!force && now - lastUpdateNanos < 100_000_000L) return;
            lastUpdateNanos = now;
            listener.onProgress(transferred, total, fileName, completed, items);
        }
    }

    private static long safeAdd(long a, long b) {
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    private static long totalSourceBytes(List<Item> sources) throws Exception {
        long sum = 0;
        int[] budget = {0};
        for (Item item : sources) {
            long size = sourceBytes(item, budget, 0);
            if (size < 0) return -1;
            sum = safeAdd(sum, size);
        }
        return sum;
    }

    private static long sourceBytes(Item item, int[] budget, int depth) throws Exception {
        visit(budget, depth);
        if (!item.directory) return item.size;
        long sum = 0;
        for (Item child : children(item)) {
            long n = sourceBytes(child, budget, depth + 1);
            if (n < 0) return -1;
            sum = safeAdd(sum, n);
        }
        return sum;
    }

    static final class BatchFailure extends IOException {
        final int completed;
        final int total;
        final boolean deleting;
        BatchFailure(int completed, int total, boolean deleting, Exception cause) {
            super("File operation stopped after " + completed + "/" + total, cause);
            this.completed = completed;
            this.total = total;
            this.deleting = deleting;
        }
    }

    static final class NameConflict extends IOException {
        NameConflict() { super("An item with this name already exists"); }
    }

    static final class Item {
        final String kind;
        final String name;
        final String path;
        final boolean directory;
        final File local;
        final DocumentFile saf;
        final Uri media;
        final Models.RemoteProfile remote;
        final String tree;
        final long size;

        Item(String kind, String name, String path, boolean directory,
             File local, DocumentFile saf, Uri media,
             Models.RemoteProfile remote, String tree, long size) {
            this.kind = kind;
            this.name = name;
            this.path = path;
            this.directory = directory;
            this.local = local;
            this.saf = saf;
            this.media = media;
            this.remote = remote;
            this.tree = tree;
            this.size = size;
        }

        String key() {
            return kind + ":" + (remote == null ? tree : remote.id) + ":" +
                    (media != null ? media.toString() :
                            local != null ? local.getAbsolutePath() : path);
        }

        boolean writable() {
            return !"media".equals(kind) &&
                    (remote == null || !CloudProvider.isReadOnly(remote));
        }
    }

    static final class Destination {
        final String kind;
        final File local;
        final DocumentFile saf;
        final Models.RemoteProfile remote;
        final String path;
        final String tree;

        Destination(String kind, File local, DocumentFile saf,
                    Models.RemoteProfile remote, String path, String tree) {
            this.kind = kind;
            this.local = local;
            this.saf = saf;
            this.remote = remote;
            this.path = path;
            this.tree = tree;
        }
    }

    static Item local(File file) {
        return new Item("local", file.getName(), file.getAbsolutePath(),
                file.isDirectory(), file, null, null, null, "", file.length());
    }
    static Item saf(DocumentFile file, String path, String tree) {
        return new Item("saf", file.getName(), path, file.isDirectory(),
                null, file, null, null, tree,
                file.length() > 0 ? file.length() : -1);
    }
    static Item remote(Models.RemoteProfile profile, Models.Entry file) {
        return new Item("remote", file.name, file.path, file.directory,
                null, null, null, profile, "", file.size);
    }
    static Item media(Uri uri, String name) {
        return new Item("media", name, uri.toString(), false,
                null, null, uri, null, "", -1);
    }

    static Destination localFolder(File folder) {
        return new Destination("local", folder, null, null,
                folder.getAbsolutePath(), "");
    }
    static Destination safFolder(DocumentFile folder, String path, String tree) {
        return new Destination("saf", null, folder, null, path, tree);
    }
    static Destination remoteFolder(Models.RemoteProfile profile, String path) {
        return new Destination("remote", null, null, profile,
                RemoteProvider.relativePath(path), "");
    }

    static void preflight(List<Item> sources, Destination destination, boolean move)
            throws Exception {
        preflight(sources, destination, move, false);
    }

    static void preflight(List<Item> sources, Destination destination,
                          boolean move, boolean overwrite) throws Exception {
        List<String> names = new ArrayList<>();
        for (Item item : sources) {
            names.add(item.name);
            if (item.local != null && Files.isSymbolicLink(item.local.toPath()))
                throw new IOException("심볼릭 링크 파일은 안전을 위해 복사하지 않습니다.");
            if (move && !item.writable())
                throw new IOException("읽기 전용 저장소에서는 이동할 수 없습니다: " + item.name);
            if (destination.kind.equals("local") && item.local != null &&
                    item.directory &&
                    FileOperationPolicy.childOrSame(
                            item.local.getCanonicalPath(),
                            destination.local.getCanonicalPath()))
                throw new IOException("폴더 자체 또는 하위 폴더로 복사/이동할 수 없습니다.");
            if (destination.kind.equals("saf") && item.saf != null &&
                    destination.tree.equals(item.tree) && item.directory &&
                    FileOperationPolicy.childOrSame(item.path, destination.path))
                throw new IOException("선택한 SD 폴더의 하위 폴더로 복사할 수 없습니다.");
            if (destination.kind.equals("remote") && item.remote != null &&
                    item.remote.id.equals(destination.remote.id) && item.directory &&
                    FileOperationPolicy.childOrSame(
                            RemoteProvider.relativePath(item.path), destination.path))
                throw new IOException("네트워크 폴더를 자신의 하위에 복사/이동할 수 없습니다.");
            if (destination.kind.equals("remote") && item.remote != null &&
                    item.remote.id.equals(destination.remote.id) &&
                    RemoteProvider.relativePath(item.path).equals(
                            RemoteProvider.child(destination.path, item.name)))
                throw new IOException("원본과 대상이 같은 파일입니다: " + item.name);
            if (destination.kind.equals("local") && item.local != null &&
                    item.local.getCanonicalPath().equals(
                            new File(destination.local, item.name).getCanonicalPath()))
                throw new IOException("원본과 대상이 같은 파일입니다: " + item.name);
            if (destination.kind.equals("saf") && item.saf != null &&
                    destination.tree.equals(item.tree) &&
                    item.path.equals(RemoteProvider.child(destination.path, item.name)))
                throw new IOException("원본과 대상이 같은 파일입니다: " + item.name);
        }
        FileOperationPolicy.validateBatchNames(names);
        if (destination.kind.equals("remote") &&
                CloudProvider.isReadOnly(destination.remote))
            throw new IOException("이 클라우드는 읽기 전용이어서 붙여넣을 수 없습니다.");
        if (destination.kind.equals("local")) {
            if (!destination.local.isDirectory() || !destination.local.canWrite())
                throw new IOException("대상 폴더에 쓰기 권한이 없습니다.");
        } else if (destination.kind.equals("saf")) {
            if (!destination.saf.isDirectory() || !destination.saf.canWrite())
                throw new IOException("SD 폴더에 쓰기 권한이 없습니다.");
        } else if (destination.kind.equals("remote")) {
            List<Models.Entry> existing = RemoteProvider.list(
                    destination.remote, destination.path);
            LinkedHashMap<String, Boolean> namesInDestination = new LinkedHashMap<>();
            for (Models.Entry e : existing)
                namesInDestination.put(e.name.toLowerCase(Locale.ROOT), e.directory);
            for (Item item : sources) {
                Boolean existingDir = namesInDestination.get(
                        item.name.toLowerCase(Locale.ROOT));
                if (existingDir == null) continue;
                if (!overwrite) throw new NameConflict();
                if (existingDir != item.directory)
                    throw new IOException("파일과 폴더 종류가 달라 덮어쓸 수 없습니다: " +
                            item.name);
            }
        } else throw new IOException("이 화면에는 파일을 붙여넣을 수 없습니다.");
        if (!destination.kind.equals("remote")) {
            for (Item item : sources) {
                File existingLocal = destination.kind.equals("local") ?
                        new File(destination.local, item.name) : null;
                DocumentFile existingSaf = destination.kind.equals("saf") ?
                        destination.saf.findFile(item.name) : null;
                boolean exists = e