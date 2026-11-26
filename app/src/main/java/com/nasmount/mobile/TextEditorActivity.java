package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Internal text editor for local, SAF and remote files. Never exported.
 * Reads/writes run off the UI thread. No online editing server or webview.
 */
public final class TextEditorActivity extends LocalizedActivity {
    static final String EXTRA_KIND = "text_kind";
    static final String EXTRA_NAME = "text_name";
    static final String EXTRA_PATH = "text_path";
    static final String EXTRA_PROFILE = "text_profile";
    static final String EXTRA_URI = "text_uri";
    private static final String KIND_LOCAL = "local";
    private static final String KIND_SAF = "saf";
    private static final String KIND_REMOTE = "remote";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText editor;
    private TextView saveButton, status;
    private ProgressBar spinner;
    private String sourceKind, fileName, sourcePath, profileId, contentUri;
    private Models.RemoteProfile profile;
    private TextDocumentCodec.Document document;
    private String savedText = "";
    private boolean loaded, saving;

    @Override public void onCreate(Bundle state) {
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= 29)
            getWindow().setNavigationBarContrastEnforced(false);
        getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode ?
                0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        sourceKind = getIntent().getStringExtra(EXTRA_KIND);
        fileName = getIntent().getStringExtra(EXTRA_NAME);
        sourcePath = getIntent().getStringExtra(EXTRA_PATH);
        profileId = getIntent().getStringExtra(EXTRA_PROFILE);
        contentUri = getIntent().getStringExtra(EXTRA_URI);
        if (!EditableFileTypes.isEditableText(fileName) ||
                !(KIND_LOCAL.equals(sourceKind) || KIND_SAF.equals(sourceKind) ||
                  KIND_REMOTE.equals(sourceKind))) {
            finish();
            return;
        }

        FrameLayout window = new FrameLayout(this);
        window.setBackgroundColor(Ui.BG);
        LinearLayout column = Ui.vertical(this);
        ViewCompat.setOnApplyWindowInsetsListener(column, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.statusBars() |
                    WindowInsetsCompat.Type.navigationBars() |
                    WindowInsetsCompat.Type.ime() |
                    WindowInsetsCompat.Type.displayCutout());
            // Insets are applied as absolute padding rather than accumulated.
            column.setPadding(0, safe.top, 0, safe.bottom);
            return insets;
        });
        window.addView(column, new FrameLayout.LayoutParams(-1, -1));
        setContentView(EditionMonetization.content(this,window));
        ViewCompat.requestApplyInsets(column);

        LinearLayout toolbar = Ui.horizontal(this);
        toolbar.setBackgroundColor(Ui.DARK);
        toolbar.setPadding(dp(8), 0, dp(12), 0);
        column.addView(toolbar, Ui.lp(this, -1, 57));
        TextView back = Ui.text(this, "‹", 33, Color.WHITE, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(L10n.t("편집기 닫기"));
        toolbar.addView(back, Ui.lp(this, 45, -1));
        back.setOnClickListener(v -> onBackPressed());

        TextView title = Ui.text(this, fileName, 16, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -1, 1));

        saveButton = Ui.text(this, L10n.t("저장"), 16, 0xFF4CC8B8, true);
        saveButton.setGravity(Gravity.CENTER);
        saveButton.setContentDescription(L10n.t("문서 저장"));
        saveButton.setEnabled(false);
        toolbar.addView(saveButton, Ui.lp(this, 64, -1));
        saveButton.setOnClickListener(v -> saveDocument());

        status = Ui.text(this, L10n.t("문서를 읽는 중..."), 12, Ui.MUTED, false);
        status.setPadding(dp(15), 0, dp(15), 0);
        column.addView(status, Ui.lp(this, -1, 34));
        editor = new EditText(this);
        editor.setTextColor(Ui.TEXT);
        editor.setHintTextColor(Ui.MUTED);
        editor.setBackgroundColor(Ui.SURFACE);
        editor.setTextSize(15);
        editor.setTypeface(Typeface.MONOSPACE);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setPadding(dp(14), dp(14), dp(14), dp(20));
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE |
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setHorizontallyScrolling(false);
        editor.setEnabled(false);
        column.addView(editor, new LinearLayout.LayoutParams(-1, 0, 1));

        spinner = new ProgressBar(this);
        FrameLayout.LayoutParams spin = new FrameLayout.LayoutParams(
                dp(50), dp(50), Gravity.CENTER);
        window.addView(spinner, spin);
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count,
                                                    int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before,
                                                int count) {
                if (loaded && !saving) updateStatus();
            }
            @Override public void afterTextChanged(Editable e) {}
        });
        installViewerChrome(toolbar,()->ViewerUiPolicy.document(fileName,Ui.DARK,Color.WHITE,
                true,loaded&&saveButton.isEnabled(),saving,
                profile!=null&&(profile.readOnly||CloudProvider.isReadOnly(profile))),
                ()->!isFinishing(),(action,value)->{
                    if("back".equals(action))onBackPressed();
                    else if("save".equals(action)&&loaded&&!saving&&saveButton.isEnabled()&&
                            (profile==null||(!profile.readOnly&&!CloudProvider.isReadOnly(profile))))saveDocument();
                });
        readDocument();
    }

    private int dp(int value) { return Ui.dp(this, value); }

    private void showFailure(String message) {
        new AlertDialog.Builder(this).setTitle(L10n.t("문서 편집"))
                .setMessage(message).setPositiveButton(L10n.t("확인"), null).show();
    }

    private boolean dirty() {
        return loaded && !savedText.equals(editor.getText().toString());
    }

    private void updateStatus() {
        if (!loaded) return;
        status.setText(document.encodingLabel() + "  ·  " +
                (dirty() ? "● 저장하지 않은 변경사항" : "저장됨") +
                "  ·  최대 4MB");
        saveButton.setAlpha(saving ? 0.45f : 1f);
        saveButton.setEnabled(!saving);
    }

    private void readDocument() {
        worker.execute(() -> {
            TextDocumentCodec.Document loadedDoc;
            String error = null;
            try {
                if (KIND_REMOTE.equals(sourceKind)) {
                    for (Models.RemoteProfile p : new SecureStore(this).activeRemotes())
                        if (p.id.equals(profileId)) { profile = p; break; }
                    if (profile == null) throw new IOException("저장소 연결이 삭제됐습니다.");
                }
                try (InputStream source = openSource()) {
                    if (source == null)
                        throw new IOException("문서의 읽기 권한이 없습니다.");
                    loadedDoc = TextDocumentCodec.decode(
                            TextDocumentCodec.readBounded(source));
                }
            } catch (Exception failure) {
                loadedDoc = null;
                error = failure.getMessage() == null ?
                        "문서를 읽을 수 없습니다." : failure.getMessage();
            }
            TextDocumentCodec.Document ready = loadedDoc;
            String errorMessage = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                spinner.setVisibility(View.GONE);
                if (ready == null) {
                    showFailure(errorMessage);
                    status.setText(L10n.t("문서를 열지 못했습니다."));
                    return;
                }
                document = ready;
                savedText = ready.content;
                editor.setText(ready.content);
                editor.setSelection(0);
                editor.setEnabled(true);
                loaded = true;
                updateStatus();
            });
        });
    }

    private InputStream openSource() throws Exception {
        if (KIND_LOCAL.equals(sourceKind)) return new FileInputStream(sourcePath);
        if (KIND_SAF.equals(sourceKind))
            return getContentResolver().openInputStream(Uri.parse(contentUri));
        return RemoteProvider.openStream(profile,
                RemoteProvider.relativePath(sourcePath), 0);
    }

    private void saveDocument() {
        if (!loaded || saving) return;
        final String text = editor.getText().toString();
        if (text.equals(savedText)) {
            status.setText(document.encodingLabel() + "  ·  변경사항 없음");
            return;
        }
        saving = true;
        saveButton.setEnabled(false);
        status.setText(L10n.t("원본 파일에 저장하는 중..."));
        worker.execute(() -> {
            String error = null;
            try {
                byte[] output = TextDocumentCodec.encode(document, text);
                if (KIND_LOCAL.equals(sourceKind)) {
                    TextDocumentCodec.atomicWrite(new File(sourcePath), output);
                } else if (KIND_SAF.equals(sourceKind)) {
                    try (OutputStream dest = getContentResolver().openOutputStream(
                            Uri.parse(contentUri), "rwt")) {
                        if (dest == null) throw new IOException("문서 쓰기 권한이 없습니다.");
                        dest.write(output);
                        dest.flush();
                    }
                } else {
                    String path = RemoteProvider.relativePath(sourcePath);
                    String parent = RemoteProvider.parent(path);
                    String name = path.substring(path.lastIndexOf('/') + 1);
                    try (InputStream src = new ByteArrayInputStream(output)) {
                        RemoteProvider.upload(profile, parent, name, src,
                                output.length, true);
                    }
                }
            } catch (Exception failure) {
                error = failure.getMessage() == null ?
                        "문서를 저장하지 못했습니다." : failure.getMessage();
            }
            String saveError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                saving = false;
                if (saveError != null) {
                    updateStatus();
                    showFailure(saveError);
                    return;
                }
                savedText = text;
                setResult(RESULT_OK);
                updateStatus();
                android.widget.Toast.makeText(this, L10n.t("저장했습니다."),
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override public void onBackPressed() {
        if (saving) {
            showFailure("저장 작업이 끝난 뒤 편집기를 닫아 주세요.");
            return;
        }
        if (dirty()) {
            new AlertDialog.Builder(this).setTitle(L10n.t("저장하지 않은 변경사항"))
                    .setMessage(L10n.t("문서를 수정했지만 아직 저장하지 않았습니다."))
                    .setNegativeButton(L10n.t("계속 편집"), null)
                    .setNeutralButton(L10n.t("저장하지 않고 닫기"), (d, w) -> finish())
                    .setPositiveButton(L10n.t("저장"), (d, w) -> saveDocument()).show();
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
