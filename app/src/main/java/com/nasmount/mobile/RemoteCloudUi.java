package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.Toast;
import java.io.IOException;

/** The original native cloud-login dialog, shared by both UI implementations. */
final class RemoteCloudUi {
    private final Activity activity;
    RemoteCloudUi(Activity activity) { this.activity=activity; }
    private void toast(String message) { Toast.makeText(activity,message,Toast.LENGTH_SHORT).show(); }
    private void showError(String message) {
        new AlertDialog.Builder(activity).setMessage(message)
                .setPositiveButton(L10n.t("확인"),null).show();
    }
    void login(java.util.function.Consumer<String> onCookie, Runnable onDismiss) {
        WebView web = new WebView(activity);
        // AlertDialog's embedded WebView is not a native EditText, so make
        // sure its dynamic HTML editors receive focus and keyboard access.
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN)
                view.requestFocus();
            if (event.getAction() == MotionEvent.ACTION_UP) {
                view.postDelayed(() -> {
                    if (!view.hasFocus() || !view.onCheckIsTextEditor()) return;
                    InputMethodManager ime = (InputMethodManager)
                            activity.getSystemService(Context.INPUT_METHOD_SERVICE);
                    if (ime != null)
                        ime.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
                }, 120);
            }
            return false; // Preserve the original WebView HTML tap.
        });
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(TeraBoxWebPolicy.DESKTOP_USER_AGENT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setWebChromeClient(new WebChromeClient());
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view,
                    android.webkit.WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                if (TeraBoxWebPolicy.isGoogleAuthNavigation(
                        request.getUrl().toString())) {
                    // Android Chrome cannot return its session cookies to FileHub.
                    // Never offer a login button that cannot complete linking.
                    showTeraBoxPcHelp();
                    return true;
                }
                if (!TeraBoxWebPolicy.isAllowedWebNavigation(
                        request.getUrl().toString())) {
                    toast(L10n.t("외부 앱/허용되지 않은 주소로 이동하지 않습니다."));
                    return true;
                }
                // Return false to keep the official HTTPS page inside this
                // WebView, rather than launching an installed Android app.
                return false;
            }
        });
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(L10n.t("TeraBox 웹 로그인 · 세션 가져오기"))
                .setMessage(L10n.t("TeraBox 자체 계정으로 로그인한 후 세션 가져오기를 누르세요. ") +
                        L10n.t("Google 계정 인증은 세션을 가져올 수 없어 지원하지 않습니다. ") +
                        L10n.t("그 경우 PC에서 ndus 값을 확인해 입력하세요."))
                .setView(web)
                .setNegativeButton(L10n.t("닫기"), null)
                .setNeutralButton(L10n.t("PC 웹 새로고침"), null)
                .setPositiveButton(L10n.t("세션 가져오기"), null).create();
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                // Clear after show: AlertController may set this flag when
                // it sees a WebView rather than a native EditText.
                dialog.getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM |
                                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE |
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
                dialog.getWindow().setLayout(
                        (int)(activity.getResources().getDisplayMetrics().widthPixels * .96f),
                        (int)(activity.getResources().getDisplayMetrics().heightPixels * .75f));
            }
            web.requestFocus();
            web.loadUrl(TeraBoxWebPolicy.LOGIN_URL);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(
                    v -> web.loadUrl(TeraBoxWebPolicy.LOGIN_URL));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    CookieManager.getInstance().flush();
                    String session = null;
                    for (String site : new String[]{
                            "https://www.terabox.com",
                            "https://www.terabox.app",
                            "https://www.teraboxapp.com"}) {
                        String cookies = CookieManager.getInstance().getCookie(site);
                        if (cookies == null) continue;
                        try { session = CloudProvider.ndusCookie(cookies); break; }
                        catch (IOException missingSession) { /* check next domain */ }
                    }
                    if (session == null) throw new IOException("ndus 쿠키가 없습니다.");
                    onCookie.accept(session);
                    dialog.dismiss();
                } catch (Exception e) {
                    showError(L10n.t("로그인 세션(ndus)을 아직 찾지 못했습니다. ") +
                            L10n.t("PC 웹에서 로그인을 완료했는지 확인해 주세요. ") +
                            L10n.t("웹사이트가 로그인을 차단하면 수동 ndus 입력을 사용할 수 있습니다."));
                }
            });
        });
        dialog.setOnDismissListener(d -> {
            web.stopLoading();
            web.destroy();
            onDismiss.run();
        });
        dialog.show();
    }

    void showTeraBoxPcHelp() {
        new AlertDialog.Builder(activity)
                .setTitle(L10n.t("PC에서 TeraBox 세션 가져오는 방법"))
                .setMessage(L10n.t("① PC Chrome에서 TeraBox 공식 웹사이트에 로그인합니다. ") +
                        "Google 계정으로 로그인해도 됩니다.\n\n" +
                        "② 키보드 F12를 눌러 개발자 도구를 열고 " +
                        "Application(애플리케이션) → Cookies(쿠키)를 선택합니다.\n\n" +
                        "③ 로그인한 TeraBox 주소를 선택한 뒤 'ndus' 항목의 " +
                        "Value(값)를 확인합니다. 계정마다 값이 다릅니다.\n\n" +
                        "④ 세션값을 본인 휴대폰으로 안전하게 옮겨 " +
                        "NAS Mount의 'TeraBox 로그인 세션(ndus)' 칸에 붙여넣습니다. " +
                        "표시할 이름을 적고 저장한 뒤 연결하세요.\n\n" +
                        "※ ndus는 계정에 접근 가능한 민감한 세션 키입니다. " +
                        "스크린샷이나 채팅에 올리지 말고, 타인과 공유하지 마세요. " +
                        "세션이 만료되면 다시 가져와야 할 수 있습니다.")
                .setPositiveButton(L10n.t("확인"), null).show();
    }

    void showMyBoxPatHelp() {
        // Mirrors the Windows ModernProfileDialog MYBOX PAT instructions.
        new AlertDialog.Builder(activity)
                .setTitle(L10n.t("네이버 MYBOX PAT 발급 방법"))
                .setMessage(L10n.t("① 네이버 MYBOX에 로그인합니다.\n\n") +
                        "② 화면 하단의 [설정]을 누릅니다.\n\n" +
                        "③ [계정 및 개인 액세스 토큰 관리]로 이동합니다.\n\n" +
                        "④ [토큰 생성]을 눌러 개인 액세스 토큰(PAT)을 발급합니다.\n\n" +
                        "⑤ 생성된 토큰을 복사해 FileHub AIO의 [PAT] 입력칸에 붙여넣고 저장하세요.\n\n" +
                        "※ PAT는 비밀번호처럼 중요한 로그인 정보입니다. " +
                        "스크린샷이나 채팅으로 다른 사람에게 공유하지 마세요.")
                .setPositiveButton(L10n.t("확인"), null)
                .show();
    }
}
