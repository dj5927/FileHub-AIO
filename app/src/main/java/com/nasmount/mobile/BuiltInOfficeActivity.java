package com.nasmount.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * Offline in-app document editor host. Unlike OfficeBridgeActivity this never
 * dispatches an ACTION_VIEW / ACTION_EDIT to another app.
 * The HWP engine and spreadsheet engine are bundled local WebView assets.
 *
 * Remote credentials are kept in Java and only document bytes enter WebView.
 */
public final class BuiltInOfficeActivity extends Activity {
    static final String EXTRA_KIND = "built_in_kind";
    static final String EXTRA_NAME = "built_in_name";
    static final String EXTRA_PATH = "built_in_path";
    static final String EXTRA_URI = "built_in_uri";
    static final String EXTRA_PROFILE = "built_in_profile";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    private static final String ASSET_ORIGIN = "https://" + ASSET_HOST;
    private static final int MAX_DOCUMENT_BYTES = 64 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final String saveToken = UUID.randomUUID().toString();
    private String kind, name, path, contentUri, profileId;
    private Models.RemoteProfile profile;
    private volatile WebDavExcelLease excelLease;
    private File workDirectory, sourceFile;
    private WebView web;
    private TextView status;
    private TextView documentTitle;
    private volatile boolean busy, loaded, saved;
    private OfficeJsBridge bridge;

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        kind = getIntent().getStringExtra(EXTRA_KIND);
        name = getIntent().getStringExtra(EXTRA_NAME);
        path = getIntent().getStringExtra(EXTRA_PATH);
        contentUri = getIntent().getStringExtra(EXTRA_URI);
        profileId = getIntent().getStringExtra(EXTRA_PROFILE);
        if (!OfficeFileTypes.supports(name) ||
                !("local".equals(kind) || "saf".equals(kind) || "remote".equals(kind))) {
            finish();
            return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        LinearLayout root = Ui.vertical(this);
        root.setBackgroundColor(Ui.BG);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.statusBars() |
                    WindowInsetsCompat.Type.navigationBars() |
                    WindowInsetsCompat.Type.displayCutout());
            root.setPadding(0, safe.top, 0, safe.bottom);
            return insets;
        });
        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        TextView title = Ui.text(this, "‹  " + name, 16, Ui.TEXT, true);
        documentTitle = title;
        title.setTextSize(14);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        title.setGravity(android.view.Gravity.CENTER_VERTICAL);
        root.addView(title, Ui.lp(this, -1, 32));
        title.setOnClickListener(view -> askClose());
        status = Ui.text(this, "내장 문서 엔진 준비 중...", 12, Ui.MUTED, false);
        status.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        root.addView(status, Ui.lp(this, -1, 24));

        web = new WebView(this);
        root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,
                                                                  WebResourceRequest request) {
                Uri url = request.getUrl();
                return !ASSET_HOST.equalsIgnoreCase(url.getHost()) ||
                        !"https".equalsIgnoreCase(url.getScheme());
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,
                                                                          WebResourceRequest request) {
                return internalResource(request.getUrl());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                // Only diagnostics, never include raw document contents or auth tokens.
                if (msg.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR)
                    android.util.Log.w("NASOffice", "Editor JS: " + msg.message());
                return true;
            }
        });
        bridge = new OfficeJsBridge();
        web.addJavascriptInterface(bridge, "NasOfficeBridge");
        stageOriginal();
    }

    private void showStatus(String message) {
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) status.setText(message);
        });
    }

    private InputStream openOriginal() throws Exception {
        if ("local".equals(kind)) return new FileInputStream(path);
        if ("saf".equals(kind))
            return getContentResolver().openInputStream(Uri.parse(contentUri));
        return RemoteProvider.openStream(profile, RemoteProvider.relativePath(path), 0);
    }

    private void stageOriginal() {
        worker.execute(() -> {
            try {
                if ("remote".equals(kind)) {
                    for (Models.RemoteProfile p : new SecureStore(this).activeRemotes()) {
                        if (p.id.equals(profileId)) { profile = p; break; }
                    }
                    if (profile == null)
                        throw new IOException("연결된 원격 저장소를 찾지 못했습니다.");
                    if (WebDavExcelLease.applies(profile, name))
                        excelLease = WebDavExcelLease.acquire(profile,
                                RemoteProvider.relativePath(path));
                }
                workDirectory = new File(getCacheDir(),
                        "office_builtin/" + UUID.randomUUID());
                if (!workDirectory.mkdirs()) throw new IOException("임시 문서 폴더 생성 실패");
                sourceFile = new File(workDirectory, "source");
                try (InputStream from = openOriginal();
                     OutputStream to = new FileOutputStream(sourceFile)) {
                    if (from == null) throw new IOException("문서를 읽을 권한이 없습니다.");
                    byte[] bytes = new byte[65536];
                    int count, sum = 0;
                    while ((count = from.read(bytes)) != -1) {
                        if (count < 1) continue;
                        if (sum > MAX_DOCUMENT_BYTES - count)
                            throw new IOException("64MB 초과 문서는 현재 편집 제한을 넘습니다.");
                        to.write(bytes, 0, count);
                        sum += count;
                    }
                }
                if (sourceFile.length() == 0)
                    throw new IOException("빈 문서는 편집할 수 없습니다.");
                if (excelLease != null && !excelLease.readOnly())
                    excelLease.verifyBeforeSave();
                loaded = true;
                showStatus("내장 문서 편집기를 여는 중...");
                runOnUiThread(() -> {
                    if (!isDestroyed() && !isFinishing()) {
                        if (excelLease != null && excelLease.readOnly()) {
                            documentTitle.setText("‹ " + name + "  🔒");
                            documentTitle.setContentDescription(name + ", 읽기 전용");
                        }
                        status.setVisibility(android.view.View.GONE);
                        web.loadUrl(ASSET_ORIGIN + "/office-host.html");
                    }
                });
            } catch (Exception e) {
                if (excelLease != null) {
                    excelLease.release();
                    excelLease = null;
                }
                runOnUiThread(() -> status.setVisibility(android.view.View.VISIBLE));
                showStatus("문서 준비 실패: " +
                        (e.getMessage() == null ? "알 수 없는 오류" : e.getMessage()));
            }
        });
    }

    /** No remote URL is fetched by the embedded editor: only our packaged
     * local assets and one temporary document are served to this origin. */
    private WebResourceResponse internalResource(Uri uri) {
        if (!ASSET_HOST.equalsIgnoreCase(uri.getHost()) ||
                !"https".equalsIgnoreCase(uri.getScheme()))
            return unavailable();
        String route = uri.getPath();
        if (route == null || route.contains("..") || route.contains("\\") ||
                route.indexOf('\0') >= 0) return unavailable();
        if ("/office-input".equals(route)) {
            try {
                if (!loaded || sourceFile == null) return unavailable();
                return new WebResourceResponse("application/octet-stream", null,
                        new BufferedInputStream(new FileInputStream(sourceFile)));
            } catch (IOException e) { return unavailable(); }
        }
        String pathName = route.startsWith("/") ? route.substring(1) : route;
        if (pathName.equals("wasm/rhwp_bg.wasm"))
            pathName = "assets/rhwp_bg-CsZhyUHZ.wasm";
        if (pathName.isEmpty() || pathName.contains(":")) return unavailable();
        String mime = pathName.endsWith(".html") ? "text/html" :
                pathName.endsWith(".js") ? "application/javascript" :
                pathName.endsWith(".css") ? "text/css" :
                pathName.endsWith(".wasm") ? "application/wasm" :
                pathName.endsWith(".svg") ? "image/svg+xml" :
                pathName.endsWith(".json") ? "application/json" :
                pathName.endsWith(".ico") ? "image/x-icon" :
                "application/octet-stream";
        try {
            return new WebResourceResponse(mime,
                    mime.startsWith("text/") || mime.equals("application/javascript") ||
                            mime.equals("application/json") ? "UTF-8" : null,
                    getAssets().open("office_hwp/" + pathName));
        } catch (IOException notBundled) {
            return unavailable();
        }
    }

    private WebResourceResponse unavailable() {
        WebResourceResponse missing = new WebResourceResponse("text/plain", "UTF-8",
                new ByteArrayInputStream(new byte[0]));
        missing.setStatusCodeAndReasonPhrase(404, "Not Found");
        return missing;
    }

    private void executeSave(File data) {
        worker.execute(() -> {
            String failure = null;
            try {
                if (data == null || data.length() == 0 ||
                        data.length() > MAX_DOCUMENT_BYTES)
                    throw new IOException("편집 문서 크기가 올바르지 않습니다.");
                if ("remote".equals(kind)) {
                    String relative = RemoteProvider.relativePath(path);
                    if (excelLease != null) excelLease.verifyBeforeSave();
                    try (InputStream src = new FileInputStream(data)) {
                        RemoteProvider.upload(profile, RemoteProvider.parent(relative),
                                name, src, data.length(), true,
                                excelLease == null ? null : excelLease.originalEtag());
                    }
                    if (excelLease != null) excelLease.saved();
                } else if ("local".equals(kind)) {
                    File output = new File(path);
                    File pending = new File(output.getParentFile(),
                            ".nasmount-office-" + UUID.randomUUID() + ".tmp");
                    try {
                        try (InputStream in = new FileInputStream(data);
                             OutputStream out = new FileOutputStream(pending)) {
                            copy(in, out);
                        }
                        try {
                            Files.move(pending.toPath(), output.toPath(),
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        } catch (java.nio.file.AtomicMoveNotSupportedException missing) {
                            Files.move(pending.toPath(), output.toPath(),
                                    StandardCopyOption.REPLACE_EXISTING);
                        }
                    } finally { if (pending.exists()) pending.delete(); }
                } else {
                    try (InputStream in = new FileInputStream(data);
                         OutputStream out = getContentResolver().openOutputStream(
                                 Uri.parse(contentUri), "rwt")) {
                        if (out == null) throw new IOException("SAF 파일 쓰기 권한이 없습니다.");
                        copy(in, out);
                    }
                }
                saved = true;
                setResult(RESULT_OK);
            } catch (Exception error) {
                failure = error.getMessage() == null ? "원본 저장에 실패했습니다." :
                        error.getMessage();
            }
            final String message = failure;
            runOnUiThread(() -> {
                busy = false;
                if (isFinishing() || isDestroyed()) return;
                if (message == null) {
                    status.setText("문서를 원본에 저장했습니다.");
                    web.evaluateJavascript("window.OfficeNativeSaveResult(true,'')", null);
                } else {
                    status.setText("원본 저장 실패. 편집 화면을 유지하세요.");
                    web.evaluateJavascript("window.OfficeNativeSaveResult(false," +
                            JSONObject.quote(message) + ")", null);
                }
            });
        });
    }

    private static void copy(InputStream from, OutputStream to) throws IOException {
        byte[] block = new byte[65536];
        int count;
        while ((count = from.read(block)) != -1) {
            if (count > 0) to.write(block, 0, count);
        }
        to.flush();
    }

    private final class OfficeJsBridge {
        private OutputStream receiver;
        private File edited;
        private long received, expected;
        private boolean accepting;

        @JavascriptInterface public String getConfiguration() {
            try {
                JSONObject config = new JSONObject();
                config.put("token", saveToken);
                config.put("name", name);
                config.put("mode", OfficeFileTypes.mode(name));
                config.put("readOnly", excelLease != null && excelLease.readOnly());
                return config.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public synchronized String beginSave(String token,
                                                                   long length) {
            if (!saveToken.equals(token) || busy || !loaded ||
                    (excelLease != null && excelLease.readOnly()) ||
                    length < 1 || length > MAX_DOCUMENT_BYTES ||
                    workDirectory == null) return "ERROR";
            try {
                if (receiver != null) return "ERROR";
                edited = new File(workDirectory, "export-" + UUID.randomUUID() + ".tmp");
                receiver = new BufferedOutputStream(new FileOutputStream(edited));
                received = 0;
                expected = length;
                accepting = true;
                busy = true;
                return "OK";
            } catch (IOException e) { return "ERROR"; }
        }

        @JavascriptInterface public synchronized String appendSave(String token,
                                                                    String chunk) {
            if (!saveToken.equals(token) || !accepting || receiver == null ||
                    chunk == null || chunk.length() > 48000) return "ERROR";
            try {
                byte[] bytes = Base64.decode(chunk, Base64.NO_WRAP);
                if (received > expected - bytes.length) return "ERROR";
                receiver.write(bytes);
                received += bytes.length;
                return "OK";
            } catch (Exception e) { return "ERROR"; }
        }

        @JavascriptInterface public synchronized String finishSave(String token) {
            if (!saveToken.equals(token) || !accepting || receiver == null ||
                    received != expected) return "ERROR";
            try {
                receiver.close();
                receiver = null;
                accepting = false;
                executeSave(edited);
                return "OK";
            } catch (IOException e) { return "ERROR"; }
        }

        @JavascriptInterface public synchronized void cancelSave(String token) {
            if (!saveToken.equals(token)) return;
            if (receiver != null) try { receiver.close(); } catch (Exception ignored) {}
            receiver = null;
            accepting = false;
            busy = false;
            if (edited != null && edited.exists()) edited.delete();
        }
    }

    private void askClose() {
        if (busy) {
            new AlertDialog.Builder(this).setTitle("저장 진행 중")
                    .setMessage("원본 저장이 완료된 뒤 닫으세요.")
                    .setPositiveButton("확인", null).show();
            return;
        }
        new AlertDialog.Builder(this).setTitle("문서 편집 종료")
                .setMessage("저장하지 않은 변경 내용은 사라질 수 있습니다. 종료하시겠습니까?")
                .setNegativeButton("계속 편집", null)
                .setPositiveButton("종료", (dialog, which) -> finish()).show();
    }

    @Override public void onBackPressed() { askClose(); }

    @Override protected void onDestroy() {
        // Release after any in-flight upload, not during it. A killed process
        // cannot run this callback; its remote ~$ marker remains intentionally.
        WebDavExcelLease lease = excelLease;
        if (lease != null) worker.execute(lease::release);
        if (web != null) {
            web.removeJavascriptInterface("NasOfficeBridge");
            web.loadUrl("about:blank");
            web.destroy();
        }
        worker.shutdown();
        super.onDestroy();
    }
}
