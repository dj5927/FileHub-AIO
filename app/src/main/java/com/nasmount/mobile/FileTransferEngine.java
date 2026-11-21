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
                    (remote == null || (!remote.readOnly &&
                            !CloudProvider.isReadOnly(remote)));
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
                (destination.remote.readOnly ||
                        CloudProvider.isReadOnly(destination.remote)))
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
                boolean exists = existingLocal != null ?
                        existingLocal.exists() : existingSaf != null;
                if (exists && !overwrite) throw new NameConflict();
                if (exists && overwrite) {
                    boolean isFolder = existingLocal != null ?
                            existingLocal.isDirectory() : existingSaf.isDirectory();
                    if (isFolder != item.directory)
                        throw new IOException("파일과 폴더 종류가 달라 덮어쓸 수 없습니다: " +
                                item.name);
                }
            }
        }
    }

    static int transfer(Context context, List<Item> sources, Destination destination,
                        boolean move) throws Exception {
        return transfer(context, sources, destination, move, false);
    }

    static List<String> conflictingNames(List<Item> sources, Destination destination,
                                         boolean move) throws Exception {
        preflight(sources, destination, move, true);
        Set<String> names = new HashSet<>();
        if ("local".equals(destination.kind)) {
            File[] files = destination.local.listFiles();
            if (files == null) throw new IOException("대상 폴더 목록 조회 실패");
            for (File f : files) names.add(f.getName().toLowerCase(Locale.ROOT));
        } else if ("saf".equals(destination.kind)) {
            for (DocumentFile f : destination.saf.listFiles())
                names.add(f.getName().toLowerCase(Locale.ROOT));
        } else if ("remote".equals(destination.kind)) {
            for (Models.Entry f : RemoteProvider.list(destination.remote, destination.path))
                names.add(f.name.toLowerCase(Locale.ROOT));
        }
        List<String> conflicts = new ArrayList<>();
        for (Item item : sources)
            if (names.contains(item.name.toLowerCase(Locale.ROOT)))
                conflicts.add(item.name);
        return conflicts;
    }

    static int transfer(Context context, List<Item> sources, Destination destination,
                        boolean move, boolean overwrite) throws Exception {
        return transfer(context, sources, destination, move, overwrite, null);
    }

    static int transfer(Context context, List<Item> sources, Destination destination,
                        boolean move, boolean overwrite,
                        ProgressListener listener) throws Exception {
        return transfer(context, sources, destination, move, overwrite, listener, null);
    }

    static int transfer(Context context, List<Item> sources, Destination destination,
                        boolean move, boolean overwrite,
                        ProgressListener listener, TransferControl control) throws Exception {
        if (control != null) { control.bindCurrentThread(); control.check(); }
        preflight(sources, destination, move, overwrite);
        if (control != null) control.check();
        TransferMeter meter = listener == null ? null :
                new TransferMeter(listener, totalSourceBytes(sources), sources.size());
        int[] budget = {0};
        int success = 0;
        for (Item item : sources) {
            try {
                // Cloud backends support server-side copy AND move, including folders.
                if (control != null) control.check();
                if (meter != null) meter.begin(item.name);
                if ("remote".equals(item.kind) &&
                        "remote".equals(destination.kind) &&
                        item.remote.id.equals(destination.remote.id) &&
                        ("MYBOX".equals(destination.remote.protocol) ||
                         "TeraBox".equals(destination.remote.protocol))) {
                    if ("TeraBox".equals(destination.remote.protocol))
                        TeraBoxWriteApi.LIVE.copyOrMove(item.remote, item.path,
                                destination.path, move, overwrite);
                    else
                        MyBoxWriteApi.LIVE.copyOrMove(item.remote, item.path,
                                destination.path, move, overwrite);
                    if (meter != null && !item.directory) meter.directComplete(item.size);
                } else if (move && !overwrite && "remote".equals(item.kind) &&
                        "remote".equals(destination.kind) &&
                        item.remote.id.equals(destination.remote.id)) {
                    String to = RemoteProvider.child(destination.path, item.name);
                    RemoteProvider.rename(item.remote, item.path, to, item.directory);
                    if (meter != null && !item.directory) meter.directComplete(item.size);
                } else if (move && "local".equals(item.kind) &&
                        "local".equals(destination.kind) &&
                        tryLocalMove(item.local, new File(destination.local, item.name),
                                overwrite)) {
                    // A rename bypasses copyOne; announce its completed media
                    // destination to Android's shared Gallery/Video index too.
                    MediaIndexing.scanLocal(context, new File(destination.local, item.name));
                    if (meter != null && !item.directory) meter.directComplete(item.size);
                } else {
                    copyOne(context, item, destination, budget, 0, overwrite, meter, control);
                    if (control != null) control.check();
                    if (move) deleteOne(item, budget, 0);
                }
                success++;
                if (meter != null) meter.completedItem();
            } catch (Exception failure) {
                throw new BatchFailure(success, sources.size(), false, failure);
            }
        }
        if (meter != null) meter.finished();
        return success;
    }

    private static boolean tryLocalMove(File source, File target, boolean overwrite)
            throws IOException {
        if (target.exists() && !overwrite)
            throw new NameConflict();
        if (source.isDirectory() && target.exists()) return false;
        try {
            // Unlike File.renameTo, NIO move without REPLACE_EXISTING will
            // never overwrite a file created after the initial preflight.
            if (overwrite)
                Files.move(source.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            else
                Files.move(source.toPath(), target.toPath());
            return true;
        } catch (IOException e) {
            if (target.exists() && !overwrite)
                throw new NameConflict();
            // Cross-device moves fall back to copy+verified close+delete.
            return false;
        }
    }

    static int delete(Context context, List<Item> sources) throws Exception {
        int[] budget = {0};
        int success = 0;
        for (Item item : sources) {
            try {
                if (!item.writable())
                    throw new IOException("읽기 전용 파일은 삭제할 수 없습니다: " + item.name);
                deleteOne(item, budget, 0);
                success++;
            } catch (Exception failure) {
                throw new BatchFailure(success, sources.size(), true, failure);
            }
        }
        return success;
    }

    private static void visit(int[] budget, int depth) throws IOException {
        if (depth > MAX_DEPTH || ++budget[0] > MAX_ENTRIES)
            throw new IOException("폴더가 너무 깊거나 항목이 많아 작업을 중단했습니다.");
    }

    static List<Item> children(Item parent) throws Exception {
        List<Item> out = new ArrayList<>();
        switch (parent.kind) {
            case "local":
                File[] listing = parent.local.listFiles();
                if (listing == null) throw new IOException("하위 파일 목록을 읽지 못했습니다.");
                for (File child : listing) {
                    if (java.nio.file.Files.isSymbolicLink(child.toPath()))
                        throw new IOException("심볼릭 링크 폴더는 안전을 위해 제외합니다.");
                    out.add(local(child));
                }
                return out;
            case "saf":
                for (DocumentFile child : parent.saf.listFiles())
                    out.add(saf(child, RemoteProvider.child(parent.path,
                            child.getName()), parent.tree));
                return out;
            case "remote":
                for (Models.Entry child : RemoteProvider.list(parent.remote, parent.path))
                    out.add(remote(parent.remote, child));
                return out;
            default: throw new IOException("이 화면의 폴더는 지원하지 않습니다.");
        }
    }

    private static Destination createFolder(Destination target, String name,
                                            boolean overwrite)
            throws Exception {
        switch (target.kind) {
            case "local":
                File local = new File(target.local, name);
                if (local.exists()) {
                    if (!overwrite || !local.isDirectory()) throw new NameConflict();
                } else if (!local.mkdir())
                    throw new IOException("폴더를 생성하지 못했습니다: " + name);
                return localFolder(local);
            case "saf":
                DocumentFile doc = target.saf.findFile(name);
                if (doc != null && (!overwrite || !doc.isDirectory()))
                    throw new NameConflict();
                if (doc == null) doc = target.saf.createDirectory(name);
                if (doc == null) throw new IOException("SD 폴더 생성 실패: " + name);
                return safFolder(doc, RemoteProvider.child(target.path, name),
                        target.tree);
            case "remote":
                String remoteChild = RemoteProvider.child(target.path, name);
                boolean foundFolder = false;
                if (overwrite)
                    for (Models.Entry entry : RemoteProvider.list(target.remote, target.path))
                        if (entry.name.equalsIgnoreCase(name)) {
                            if (!entry.directory) throw new NameConflict();
                            foundFolder = true;
                            break;
                        }
                if (!foundFolder) RemoteProvider.mkdir(target.remote, remoteChild);
                return remoteFolder(target.remote, remoteChild);
            default: throw new IOException("폴더를 만들 수 없는 대상입니다.");
        }
    }

    private static void copyOne(Context ctx, Item item, Destination destination,
                                int[] budget, int depth, boolean overwrite,
                                TransferMeter meter, TransferControl control) throws Exception {
        if (control != null) control.check();
        visit(budget, depth);
        if (item.directory) {
            Destination childDestination = createFolder(destination, item.name, overwrite);
            for (Item child : children(item))
                copyOne(ctx, child, childDestination, budget, depth + 1, overwrite, meter, control);
            return;
        }
        if (meter != null) meter.begin(item.name);
        try (InputStream in = openInput(ctx, item)) {
            if (control != null) control.track(in);
            if (destination.kind.equals("remote")) {
                InputStream measured = meter == null && control == null ? in : new FilterInputStream(in) {
                    @Override public int read() throws IOException {
                        if (control != null) control.check();
                        int value = super.read();
                        if (value >= 0 && meter != null) meter.bytes(1);
                        return value;
                    }
                    @Override public int read(byte[] b, int offset, int length) throws IOException {
                        if (control != null) control.check();
                        int n = in.read(b, offset, length);
                        if (n > 0 && meter != null) meter.bytes(n);
                        return n;
                    }
                };
                RemoteProvider.upload(destination.remote, destination.path,
                        item.name, measured, item.size, overwrite);
            } else if (destination.kind.equals("local")) {
                File created = new File(destination.local, item.name);
                if (created.exists() && !overwrite) throw new NameConflict();
                File staged = overwrite ? new File(destination.local,
                        ".nasmount-" + UUID.randomUUID().toString() + ".staging") : created;
                boolean createdHere = false;
                try {
                    try (OutputStream out = Files.newOutputStream(staged.toPath(),
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                        createdHere = true;
                        copyBytes(in, out, meter, control);
                    }
                    if (overwrite) {
                        try {
                            Files.move(staged.toPath(), created.toPath(),
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                            Files.move(staged.toPath(), created.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                } catch (Exception failure) {
                    if (createdHere && staged.exists() && !staged.delete())
                        throw new IOException("복사 실패 후 임시 파일을 정리하지 못했습니다: " +
                                created.getName(), failure);
                    throw failure;
                }
                // The final file is now fully closed and visible. Without
                // MediaScanner registration a NAS -> phone paste is visible
                // in file managers but not in Gallery or video pickers.
                MediaIndexing.scanLocal(ctx, created);
            } else if (destination.kind.equals("saf")) {
                DocumentFile previous = destination.saf.findFile(item.name);
                if (previous != null && !overwrite) throw new NameConflict();
                String stagedName = previous == null ? item.name :
                        ".nasmount-" + UUID.randomUUID().toString() + ".staging";
                DocumentFile created = destination.saf.createFile(
                        previous == null ? mime(item.name) :
                                "application/octet-stream", stagedName);
                if (created == null) throw new IOException("SD 파일 생성 실패: " + item.name);
                try {
                    if (!stagedName.equals(created.getName()))
                        throw new IOException("SD 공급자가 파일명을 변경하여 저장을 중단했습니다.");
                    try (OutputStream out = ctx.getContentResolver().openOutputStream(
                            created.getUri(), "w")) {
                        if (out == null) throw new IOException("SD 파일 쓰기 실패");
                        copyBytes(in, out, meter, control);
                    }
                    if (previous != null) {
                        if (!previous.delete())
                            throw new IOException("SD 기존 파일 교체를 허용하지 않았습니다.");
                        if (!created.renameTo(item.name))
                            throw new IOException("SD 파일은 새 임시 이름으로 저장됐으나 " +
                                    "원래 이름 복원에 실패했습니다: " + stagedName);
                    }
                } catch (Exception failure) {
                    if (previous == null || previous.exists()) {
                        if (!created.delete())
                            throw new IOException("SD 전송 실패 및 불완전 파일 정리 실패: " +
                                    item.name, failure);
                    }
                    throw failure;
                }
            } else throw new IOException("붙여넣을 수 없는 폴더입니다.");
        }
        if (meter != null) meter.update(true);
    }

    static InputStream openInput(Context ctx, Item item) throws Exception {
        switch (item.kind) {
            case "local": return new FileInputStream(item.local);
            case "saf": {
                InputStream in = ctx.getContentResolver().openInputStream(item.saf.getUri());
                if (in == null) throw new IOException("SD 파일을 열 수 없습니다.");
                return in;
            }
            case "media": {
                InputStream in = ctx.getContentResolver().openInputStream(item.media);
                if (in == null) throw new IOException("미디어 파일을 열 수 없습니다.");
                return in;
            }
            case "remote": return RemoteProvider.openStream(item.remote, item.path, 0);
            default: throw new IOException("지원하지 않는 소스입니다.");
        }
    }

    private static String mime(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String known = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
                name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return known == null ? "application/octet-stream" : known;
    }

    private static void copyBytes(InputStream from, OutputStream to) throws IOException {
        copyBytes(from, to, null);
    }

    private static void copyBytes(InputStream from, OutputStream to,
                                  TransferMeter meter) throws IOException {
        copyBytes(from, to, meter, null);
    }

    private static void copyBytes(InputStream from, OutputStream to,
                                  TransferMeter meter, TransferControl control) throws IOException {
        byte[] buffer = new byte[65536];
        int read;
        while (true) {
            if (control != null) control.check();
            read = from.read(buffer);
            if (read == -1) break;
            to.write(buffer, 0, read);
            if (meter != null) meter.bytes(read);
        }
        to.flush();
    }

    private static void deleteOne(Item item, int[] budget, int depth)
            throws Exception {
        visit(budget, depth);
        if (!item.writable()) throw new IOException("읽기 전용 파일은 삭제할 수 없습니다.");
        if (item.directory && item.remote != null &&
                !"MYBOX".equals(item.remote.protocol) &&
                !"TeraBox".equals(item.remote.protocol))
            for (Item child : children(item))
                deleteOne(child, budget, depth + 1);
        switch (item.kind) {
            case "local":
                if (item.directory)
                    for (Item child : children(item))
                        deleteOne(child, budget, depth + 1);
                if (!item.local.delete())
                    throw new IOException("삭제에 실패했습니다: " + item.name);
                return;
            case "saf":
                if (!item.saf.delete())
                    throw new IOException("SD 삭제에 실패했습니다: " + item.name);
                return;
            case "remote":
                RemoteProvider.delete(item.remote, item.path, item.directory);
                return;
            default: throw new IOException("삭제할 수 없는 파일입니다.");
        }
    }
}
