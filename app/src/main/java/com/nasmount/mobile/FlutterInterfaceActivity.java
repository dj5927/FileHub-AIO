package com.nasmount.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import io.flutter.embedding.android.FlutterActivity;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** Shared Flutter routes; original Activities own storage and all write operations. */
public final class FlutterInterfaceActivity extends FlutterActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private MethodChannel channel;
    private FlutterRemoteEditor editor;
    static Intent intent(Activity caller, String route) {
        if (!FlutterUiContract.validRoute(route)) throw new IllegalArgumentException("route");
        return new Intent(caller, FlutterInterfaceActivity.class)
                .putExtra(FlutterUiContract.ROUTE, route);
    }
    private String route() {
        String route = getIntent().getStringExtra(FlutterUiContract.ROUTE);
        return FlutterUiContract.validRoute(route) ? route : FlutterUiContract.INTERFACE;
    }
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(L10n.localizedContext(base));
    }
    @Override protected void onCreate(Bundle state) {
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        if(FlutterUiContract.EDITOR.equals(route())) {
            getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            editor=new FlutterRemoteEditor(this,worker);
        }
        super.onCreate(state);
        if(android.os.Build.VERSION.SDK_INT>=34){
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN,0,0);
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE,0,0);
        }else overridePendingTransition(0,0);
    }
    @Override public io.flutter.embedding.android.RenderMode getRenderMode(){
        return io.flutter.embedding.android.RenderMode.texture;
    }
    @Override public io.flutter.embedding.android.TransparencyMode getTransparencyMode(){
        return io.flutter.embedding.android.TransparencyMode.transparent;
    }
    @Override public void setContentView(View root) {
        // Keep the same native advertising slot and consent lifecycle.
        root.setBackgroundColor(Ui.BG);
        super.setContentView(EditionMonetization.content(this, root));
    }
    @Override public void finish(){
        super.finish();
        if(android.os.Build.VERSION.SDK_INT<34)overridePendingTransition(0,0);
    }
    @Override public void configureFlutterEngine(FlutterEngine engine) {
        super.configureFlutterEngine(engine);
        channel = new MethodChannel(engine.getDartExecutor().getBinaryMessenger(), FlutterUiContract.CHANNEL);
        channel.setMethodCallHandler(this::handle);
    }
    @Override public void cleanUpFlutterEngine(FlutterEngine engine) {
        if (channel != null) channel.setMethodCallHandler(null);
        channel = null;
        super.cleanUpFlutterEngine(engine);
    }
    @Override protected void onDestroy() {
        if(editor!=null)editor.close();
        worker.shutdownNow();
        super.onDestroy();
    }
    private void handle(MethodCall call, MethodChannel.Result result) {
        if(editor!=null && editor.handle(call,result))return;
        switch (call.method) {
            case "snapshot":
                snapshot(result); return;
            case "setLanguage":
                if (!FlutterUiContract.INTERFACE.equals(route()) ||
                        !FlutterUiContract.validLanguage(call.arguments)) {
                    result.error("INVALID_ARGUMENT", null, null); return;
                }
                if (!L10n.choose(this, (Integer)call.arguments)) {
                    result.error("SAVE_FAILED", null, null); return;
                }
                setResult(RESULT_OK);
                snapshot(result); return;
            case "setTheme":
                if (!FlutterUiContract.INTERFACE.equals(route()) ||
                        !FlutterUiContract.validTheme(call.arguments)) {
                    result.error("INVALID_ARGUMENT", null, null); return;
                }
                AppearanceSettings.choose(this, (Integer)call.arguments);
                setResult(RESULT_OK);
                snapshot(result); return;
            case "action":
                Object actionArg = call.argument("name"), idArg = call.argument("id");
                String action = actionArg instanceof String ? (String)actionArg : null;
                String id = idArg instanceof String ? (String)idArg : null;
                if (!FlutterUiContract.validAction(route(), action, id)) {
                    result.error("INVALID_ACTION", null, null); return;
                }
                setResult(RESULT_OK, new Intent().putExtra(FlutterUiContract.ACTION, action)
                        .putExtra(FlutterUiContract.PROFILE_ID, id));
                result.success(null);
                finish();
                return;
            default: result.notImplemented();
        }
    }
    private void snapshot(MethodChannel.Result result) {
        final String currentRoute = route();
        final boolean full = EditionMonetization.full(this);
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("route", currentRoute);
        data.put("language", L10n.language());
        data.put("languageIndex", L10n.selectedIndex(this));
        data.put("themeIndex", AppearanceSettings.mode(this));
        data.put("dark", AppearanceSettings.isDark(this));
        data.put("direct", BuildConfig.DIRECT_EDITION);
        data.put("full", full);
        data.put("version", BuildConfig.VERSION_NAME);
        data.put("languages", Arrays.asList(L10n.choices()));
        data.put("themes", Arrays.asList(L10n.labels(AppearanceSettings.LABELS)));
        Map<String,String> labels = new LinkedHashMap<>();
        labels.put("interface", L10n.t("인터페이스"));
        labels.put("language", L10n.t("표시 언어"));
        labels.put("languageNote", L10n.t("시스템 언어를 따르거나 직접 표시 언어를 선택할 수 있습니다. 지원하지 않는 시스템 언어는 영어로 표시합니다."));
        labels.put("theme", L10n.t("화면 테마"));
        labels.put("themeNote", L10n.t("시스템 설정에 맞춤을 선택하면 Android의 라이트/다크 설정을 따릅니다."));
        labels.put("connections", L10n.t("원격 저장소"));
        labels.put("add", L10n.t("원격 저장소 추가"));
        labels.put("backup", L10n.t("설정 파일 내보내기 · 가져오기"));
        labels.put("upgrade", L10n.t("풀버전으로 업그레이드"));
        labels.put("limit", L10n.t("무료 버전에서는 연결 종류별로 1개만 사용할 수 있습니다."));
        labels.put("locked", L10n.t("풀버전 필요"));
        labels.put("empty", L10n.t("연결을 추가하면 여기에 표시돼요."));
        labels.put("profileError", L10n.t("저장된 연결 설정을 읽을 수 없습니다. 암호화 키를 확인하세요."));
        labels.put("manage", L10n.t("설정 수정"));
        labels.put("readOnly", L10n.t("읽기 전용"));
        labels.put("refresh", L10n.t("새로고침"));
        labels.put("error", L10n.t("설정을 확인해 주세요"));
        labels.put("nativeList", L10n.t("기존 연결 관리"));
        if(editor!=null)FlutterRemoteEditor.labels(labels);
        data.put("labels", labels);
        data.put("profiles", Collections.emptyList());
        data.put("profileError", false);
        if(editor!=null){editor.snapshot(data,result);return;}
        if (!FlutterUiContract.CONNECTIONS.equals(currentRoute)) {
            result.success(data); return;
        }
        worker.execute(() -> {
            try {
                data.put("profiles", FlutterUiContract.summaries(
                        new SecureStore(this).supportedRemotes(), full));
            } catch (Exception error) {
                data.put("profileError", true);
            }
            runOnUiThread(() -> {
                if (!isDestroyed() && !isFinishing()) result.success(data);
            });
        });
    }
}
