package com.nasmount.mobile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Cooperative Excel editor ownership for Android's staged WebDAV editors.
 * Unlike Windows, Android has no WinFsp handle or native Excel process.
 * The owner is acquired with a conditional WebDAV create and the workbook's
 * original ETag is retained for protection against stale offline edits.
 *
 * Never auto-expire a remote owner: after a device crash the user decides
 * whether a stale ~$ file can safely be removed.
 */
final class WebDavExcelLease {
    private static final String BUSY =
            "다른 기기에서 Excel 파일을 사용 중이어서 읽기 전용으로 열었습니다.";
    private final Models.RemoteProfile profile;
    private final String document;
    private final String owner;
    private final String alternate;
    private String ownerEtag;
    private final byte[] ownMarker;
    private String documentEtag;
    private final boolean readOnly;
    private final String reason;
    private boolean released;

    private WebDavExcelLease(Models.RemoteProfile p, String document,
                             String owner, String alternate, String ownerEtag,
                             String documentEtag, boolean readOnly, String reason,
                             byte[] ownMarker) {
        this.profile = p;
        this.document = document;
        this.owner = owner;
        this.alternate = alternate;
        this.ownerEtag = ownerEtag;
        this.documentEtag = documentEtag;
        this.readOnly = readOnly;
        this.reason = reason;
        this.ownMarker = ownMarker;
    }

    static boolean applies(Models.RemoteProfile p, String name) {
        if (p == null || !"WebDAV".equals(p.protocol) || name == null ||
                name.startsWith("~$")) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".xlsx") || lower.endsWith(".xls") ||
                lower.endsWith(".xlsm") || lower.endsWith(".xlsb");
    }

    static String[] candidateOwners(String path) {
        String normalized = RemoteProvider.relativePath(path);
        String parent = RemoteProvider.parent(normalized);
        String file = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (file.isEmpty()) throw new IllegalArgumentException("Excel filename missing");
        Set<String> variants = new LinkedHashSet<>();
        variants.add(RemoteProvider.child(parent, "~$" + file));
        if (file.length() > 2)
            variants.add(RemoteProvider.child(parent, "~$" + file.substring(2)));
        return variants.toArray(new String[0]);
    }

    /** Excel-style 165-byte binary owner metadata; no credentials or PII. */
    static byte[] ownerBytes() {
        return ownerBytes("FileHub Android");
    }

    private static byte[] ownerBytes(String user) {
        byte[] name = user.getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[165];
        result[0] = 0;
        result[1] = (byte) name.length;
        Arrays.fill(result, 2, 59, (byte) 0x20);
        System.arraycopy(name, 0, result, 2, name.length);
        result[59] = (byte) name.length;
        for (int i = 60; i + 1 < result.length; i += 2) {
            result[i] = 0;
            result[i + 1] = 0x20;
        }
        for (int i = 0; i < name.length; i++)
            result[61 + 2 * i] = name[i];
        return result;
    }

    private static RemoteProvider.WebDavRemoteVersion head(
            Models.RemoteProfile p, String path) throws Exception {
        // A read-only HEAD must not require a strong ETag; edit attempts do.
        return RemoteProvider.webdavDestinationState(p, path, true);
    }

    static WebDavExcelLease acquire(Models.RemoteProfile p, String path)
            throws Exception {
        String normalized = RemoteProvider.relativePath(path);
        String filename = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (!applies(p, filename))
            throw new IllegalArgumentException("Not a WebDAV Excel document");

        String[] names = candidateOwners(normalized);
        String primary = names[0];
        String alternative = names.length > 1 ? names[1] : null;
        RemoteProvider.WebDavRemoteVersion original = head(p, normalized);
        if (!original.exists) throw new IOException("NAS에 Excel 원본 파일이 없습니다.");
        boolean occupied = head(p, primary).exists ||
                (alternative != null && head(p, alternative).exists);
        if (occupied || p.readOnly || original.etag == null)
            return new WebDavExcelLease(p, normalized, primary, alternative,
                    null, original.etag, true,
                    occupied ? BUSY : "NAS에서 안전한 쓰기 버전을 확인할 수 없어 읽기 전용으로 표시합니다.",
                    null);

        // A distinct session marker lets us confirm OUR own binary file by
        // content even if the NAS omits owner-file ETag headers.
        byte[] ownBytes = ownerBytes("FileHub Android " +
                java.util.UUID.randomUUID().toString().substring(0, 8));
        Request create = RemoteProvider.webRequest(p, primary, false)
                .header("If-None-Match", "*")
                .put(RequestBody.create(ownBytes,
                        MediaType.parse("application/octet-stream"))).build();
        try (Response resp = RemoteProvider.webClient(p).newCall(create).execute()) {
            if (resp.code() == 409 || resp.code() == 412 ||
                    resp.code() == 423)
                return new WebDavExcelLease(p, normalized, primary, alternative,
                        null, original.etag, true, BUSY, null);
            if (!resp.isSuccessful())
                return new WebDavExcelLease(p, normalized, primary, alternative,
                        null, original.etag, true,
                        "NAS에서 Excel 잠금 파일 생성을 허용하지 않아 읽기 전용입니다.", null);
        }
        // The owner is now visible on NAS. Refuse edit if the server cannot
        // give us a strong ETag for safe conditional owner cleanup.
        WebDavExcelLease lease = new WebDavExcelLease(p, normalized, primary,
                alternative, null, original.etag, false, null, ownBytes);
        try {
            RemoteProvider.WebDavRemoteVersion written = head(p, primary);
            lease.ownerEtag = written.etag;
            lease.verifyBeforeSave();
            return lease;
        } catch (Exception failure) {
            lease.release();
            throw failure;
        }
    }

    boolean readOnly() { return readOnly; }
    String reason() { return reason; }
    synchronized String originalEtag() { return documentEtag; }

    /** Validate the exact session's remote marker, even if HEAD has no ETag. */
    private boolean ownsCurrentMarker() throws Exception {
        RemoteProvider.WebDavRemoteVersion marker = head(profile, owner);
        if (!marker.exists) return false;
        Request read = RemoteProvider.webRequest(profile, owner, false).get().build();
        try (Response response = RemoteProvider.webClient(profile).newCall(read).execute()) {
            if (!response.isSuccessful() || response.body() == null ||
                    response.body().contentLength() > 1024) return false;
            byte[] content = response.body().bytes();
            if (!java.util.Arrays.equals(ownMarker, content)) return false;
            // The unique session bytes, not a NAS-specific ETag calculation,
            // prove ownership. Always use the latest strong ETag for DELETE.
            ownerEtag = marker.etag;
            return true;
        }
    }

    /** Called after staging too, and before EVERY NAS upload. */
    synchronized void verifyBeforeSave() throws Exception {
        if (readOnly || released)
            throw new IOException(reason != null ? reason : "Excel 편집 잠금이 종료되었습니다.");
        if (!ownsCurrentMarker())
            throw new IOException("Excel 잠금 소유권이 변경되었습니다. 원본을 덮어쓰지 않았습니다.");
        if (alternate != null && head(profile, alternate).exists)
            throw new IOException("다른 기기의 Excel 잠금 파일이 발견되었습니다.");
        RemoteProvider.WebDavRemoteVersion latest = head(profile, document);
        if (!latest.exists)
            throw new IOException("Excel 원본이 다른 기기에서 삭제되었습니다.");
        WebDavWriteGuard.sameVersion(documentEtag, latest.etag);
    }

    /** Refresh the document snapshot only after the confirmed commit. */
    synchronized void saved() throws Exception {
        if (readOnly || released) throw new IOException("Excel 편집 잠금이 종료되었습니다.");
        RemoteProvider.WebDavRemoteVersion current = head(profile, document);
        if (!current.exists) throw new IOException("저장된 Excel 파일을 찾을 수 없습니다.");
        documentEtag = WebDavWriteGuard.checkedEtag(current.etag);
    }

    /** Do not delete another client's owner or a modified owner. */
    synchronized void release() {
        if (released) return;
        if (readOnly || ownMarker == null) {
            released = true;
            return;
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                if (!ownsCurrentMarker()) {
                    // Not ours anymore, or already removed. Never delete it.
                    released = true;
                    return;
                }
                Request.Builder remove = RemoteProvider.webRequest(profile, owner, false);
                if (ownerEtag != null) remove.header("If-Match", ownerEtag);
                try (Response response = RemoteProvider.webClient(profile)
                        .newCall(remove.delete().build()).execute()) {
                    if (response.isSuccessful() || response.code() == 404) {
                        released = true;
                        return;
                    }
                }
            } catch (Exception ignored) {
                // One transient NAS failure must not leave a stale owner.
            }
            if (attempt < 2) try { Thread.sleep(125L * (attempt + 1)); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        android.util.Log.w("FileHubAIO", "Excel owner cleanup not confirmed; manual cleanup may be required");
    }
}
