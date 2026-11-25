package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Safe local staging and explicit commit bridge to an installed Android
 * LibreOffice-compatible or HWP-compatible editor. Does NOT bundle/pretend
 * to be LibreOffice, Collabora or rhwp; those editors run as external apps.
 *
 * User edits a private staged copy exposed through write-granted content URI.
 * The remote original is touched only on explicit "NAS에 저장".
 */
public final class OfficeBridgeActivity extends LocalizedActivity {
    static final String EXTRA_KIND = "office_kind";
    static final String EXTRA_NAME = "office_name";
    static final String EXTRA_PATH = "office_path";
    static final String EXTRA_URI = "office_uri";
    static final String EXTRA_PROFILE = "office_profile";
    private static final int MAX_OFFICE_BYTES = 256 * 1024 * 1024;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private String kind, filename, path, uri, profileId;
    private Models.RemoteProfile remote;
    private volatile WebDavExcelLease excelLease;
    private File staged;
    private byte[] baseline;
    private boolean loading = true, saving, launched;
    private TextView status, editButton, saveButton;

    @Override public void onCreate(Bundle state) {
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        if(!EditionMonetization.full(this)){
            finish();return;
        }
        kind = getIntent().getStringExtra(EXTRA_KIND);
        filename = getIntent().getStringExtra(EXTRA_NAME);
        path = getIntent().getStringExtra(EXTRA_PATH);
        uri = getIntent().getStringExtra(EXTRA_URI);
        profileId = getIntent().getStringExtra(EXTRA_PROFILE);
        if (!OfficeFileTypes.supports(filename) ||
                !("local".equals(kind) || "saf".equals(kind) || "remote".equals(kind))) {
            finish(); return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout vertical = Ui.vertical(this);
        vertical.setPadding(dp(16), dp(22), dp(16), dp(18));
        ViewCompat.setOnApplyWindowInsetsListener(vertical, (v, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.statusBars() |
                    WindowInsetsCompat.Type.navigationBars() |
                    WindowInsetsCompat.Type.displayCutout());
            vertical.setPadding(dp(16), safe.top + dp(18), dp(16), safe.bottom + dp(18));
            return insets;
        });
        root.addView(vertical, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        ViewCompat.requestApplyInsets(vertical);

        TextView title = Ui.text(this, filename, 19, Ui.TEXT, true);
        title.setMaxLines(2);
        vertical.addView(title, Ui.lp(this, -1, 78));
        status = Ui.text(this, L10n.t("편집할 파일을 안전한 임시 공간으로 복사하는 중..."), 15,
                Ui.TEXT, false);
        status.setPadding(dp(5), dp(12), dp(5), dp(12));
        vertical.addView(status, Ui.lp(this, -1, 160));
        editButton = action(L10n.t("1. 문서 편집기 열기"), vertical, this::launchEditor);
        saveButton = action(L10n.t("2. 변경 내용을 원본에 저장"), vertical, this::commitChanges);
        action(L10n.t("호환 편집기 설치 안내"), vertical, this::showEditorInstructions);
        action(L10n.t("닫기"), vertical, this::confirmClose);
        TextView note = Ui.text(this,
                L10n.t("엑셀: LibreOffice 기반 Collabora Office 등 설치된 호환 편집기 사용\n") +
                "한글: Android HWP/HWPX 편집 앱이 별도로 필요합니다.\n\n" +
                "외부 편집 앱에서 저장한 뒤 이 화면으로 돌아와 '원본에 저장'을 누르세요. " +
                "외부 앱이 사본으로 저장하면 원본에는 적용되지 않습니다.",
                13, Ui.MUTED, false);
        note.setPadding(dp(4), dp(24), dp(4), 0);
        vertical.addView(note, Ui.lp(this, -1, -2));
        updateButtons();
        stageSource();
    }

    private int dp(int p) { return Ui.dp(this, p); }

    private TextView action(String label, LinearLayout parent, Runnable callback) {
        TextView button = Ui.text(this, label, 16, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(Ui.bg(this, Ui.darkMode ? 0xFF136C69 : 0xFF127A74,
                12, 0xFF127A74));
        LinearLayout.LayoutParams row = Ui.lp(this, -1, 60);
        row.setMargins(0, dp(9), 0, dp(9));
        parent.addView(button, row);
        button.setOnClickListener(v -> callback.run());
        return button;
    }

    private void updateButtons() {
        if (editButton == null) return;
        editButton.setEnabled(!loading && !saving);
        saveButton.setEnabled(!loading && !saving &&
                (excelLease == null || !excelLease.readOnly()));
        editButton.setAlpha(editButton.isEnabled() ? 1f : 0.45f);
        saveButton.setAlpha(saveButton.isEnabled() ? 1f : 0.45f);
    }

    private void showError(String reason) {
        new AlertDialog.Builder(this).setTitle(L10n.t("오피스 문서"))
                .setMessage(reason).setPositiveButton(L10n.t("확인"), null).show();
    }

    private InputStream originalInput() throws Exception {
        if ("local".equals(kind)) return new FileInputStream(path);
        if ("saf".equals(kind)) return getContentResolver().openInputStream(Uri.parse(uri));
        return RemoteProvider.openStream(remote,
                RemoteProvider.relativePath(path), 0);
    }

    private void stageSource() {
        worker.execute(() -> {
            String error = null;
            try {
                if ("remote".equals(kind)) {
                    for (Models.RemoteProfile p : new SecureStore(this).activeRemotes())
                        if (p.id.equals(profileId)) { remote = p; break; }
                    if (remote == null) throw new IOException(L10n.t("연결된 원격 저장소를 찾지 못했습니다."));
                }
                if (WebDavExcelLease.applies(remote, filename))
                    excelLease = WebDavExcelLease.acquire(remote,
                            RemoteProvider.relativePath(path));
                File folder = new File(getCacheDir(),
                        "office_bridge/" + UUID.randomUUID());
                if (!folder.mkdirs()) throw new IOException("임시 편집 폴더 생성 실패");
                staged = new File(folder, filename);
                try (InputStream in = originalInput();
                     OutputStream out = new BufferedOutputStream(new FileOutputStream(staged))) {
                    if (in == null) throw new IOException("원본 파일에 읽기 권한이 없습니다.");
                    byte[] bytes = new byte[65536];
                    int read, length = 0;
                    while ((read = in.read(bytes)) != -1) {
                        length += read;
                        if (length > MAX_OFFICE_BYTES)
                            throw new IOException("256MB 초과 문서는 편집 중계 제한을 넘습니다.");
                        out.write(bytes, 0, read);
                    }
                }
                baseline = digest(staged);
                if (excelLease != null && !excelLease.readOnly())
                    excelLease.verifyBeforeSave();
            } catch (Exception ex) {
                if (excelLease != null) {
                    excelLease.release();
                    excelLease = null;
                }
                error = ex.getMessage() == null ? "문서를 준비하지 못했습니다." : ex.getMessage();
            }
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                loading = false;
                updateButtons();
                if (failure != null) {
                    status.setText(L10n.t("문서를 열지 못했습니다."));
                    showError(failure);
                } else if (excelLease != null && excelLease.readOnly()) {
                    status.setText(excelLease.reason() +
                            "\n원본 저장이 차단된 읽기 전용 문서입니다.");
                } else {
                    status.setText(L10n.t("파일 준비 완료 (") + (staged.length()/1024) +
                            "KB)\n편집 앱을 연 뒤 앱에서 저장하고 돌아오세요.");
                }
            });
        });
    }

    private static byte[] digest(File f) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buffer = new byte[65536];
            int len;
            while ((len = in.read(buffer)) != -1) hash.update(buffer, 0, len);
        }
        return hash.digest();
    }

    private void launchEditor() {
        if (staged == null || !staged.isFile()) {
            showError(L10n.t("편집용 문서가 준비되지 않았습니다.")); return;
        }
        try {
            Uri file = FileProvider.getUriForFile(this, getPackageName() + ".files", staged);
            // Both Collabora and mobile HWP editors are discoverable via
            // ACTION_VIEW when opened from an Android file manager.
            // A write grant is still supplied for editors that can save back.
            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setDataAndType(file, OfficeFileTypes.mime(filename));
            open.setClipData(ClipData.newUri(getContentResolver(), filename, file));
            open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (excelLease == null || !excelLease.readOnly())
                open.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            launched = true;
            startActivityForResult(Intent.createChooser(open, L10n.t("편집할 오피스 앱 선택")), 812);
            status.setText(L10n.t("편집 앱에서 문서를 저장한 다음 이 화면으로 돌아와\n") +
                    "'변경 내용을 원본에 저장'을 눌러 주세요.");
        } catch (ActivityNotFoundException e) {
            launched = false;
            showError(OfficeFileTypes.isSpreadsheet(filename) ?
                    "편집 가능한 앱이 없습니다. LibreOffice 기반 Collabora Office를 설치해 주세요." :
                    "편집 가능한 HWP 앱이 없습니다. Android용 HWP/HWPX 편집기를 설치해 주세요.");
        } catch (Exception e) {
            launched = false;
            showError(e.getMessage() == null ? "편집 앱을 열지 못했습니다." : e.getMessage());
        }
    }

    private void commitChanges() {
        if (excelLease != null && excelLease.readOnly()) {
            showError(excelLease.reason());
            return;
        }
        if (saving || loading || staged == null || baseline == null) return;
        saving = true;
        updateButtons();
        status.setText(L10n.t("편집된 문서 확인 및 원본 저장 중..."));
        worker.execute(() -> {
            String error = null;
            boolean changed = false;
            try {
                byte[] latest = digest(staged);
                changed = !MessageDigest.isEqual(baseline, latest);
                if (changed) {
                    if ("remote".equals(kind)) {
                        String relative = RemoteProvider.relativePath(path);
                        String parent = RemoteProvider.parent(relative);
                        if (excelLease != null) excelLease.verifyBeforeSave();
                        try (InputStream input = new FileInputStream(staged)) {
                            RemoteProvider.upload(remote, parent, filename, input,
                                    staged.length(), true,
                                    excelLease == null ? null : excelLease.originalEtag());
                        }
                        if (excelLease != null) excelLease.saved();
                    } else if ("saf".equals(kind)) {
                        try (InputStream input = new FileInputStream(staged);
                             OutputStream output = getContentResolver().openOutputStream(
                                     Uri.parse(uri), "rwt")) {
                            if (output == null) throw new IOException("문서 저장 권한이 없습니다.");
                            copy(input, output);
                        }
                    } else {
                        File original = new File(path);
                        File pending = new File(original.getParentFile(),
                                ".nasmount-office-" + UUID.randomUUID() + ".tmp");
                        try {
                            try (InputStream input = new FileInputStream(staged);
                                 OutputStream output = new FileOutputStream(pending)) {
                                copy(input, output);
                            }
                            try {
                                Files.move(pending.toPath(), original.toPath(),
                                        StandardCopyOption.ATOMIC_MOVE,
                                        StandardCopyOption.REPLACE_EXISTING);
                            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                                Files.move(pending.toPath(), original.toPath(),
                                        StandardCopyOption.REPLACE_EXISTING);
                            }
                        } finally { if (pending.exists()) pending.delete(); }
                    }
                    baseline = digest(staged);
                }
            } catch (Exception failure) {
                error = failure.getMessage() == null ? "저장에 실패했습니다." :
                        failure.getMessage();
            }
            final String err = error;
            final boolean updated = changed;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                saving = false;
                updateButtons();
                if (err != null) {
                    status.setText(L10n.t("원본 저장에 실패했습니다. 편집본은 임시 공간에 유지됩니다."));
                    showError(err);
                } else if (updated) {
                    status.setText(L10n.t("원본 파일에 변경 내용을 저장했습니다."));
                    setResult(RESULT_OK);
                } else {
                    status.setText(launched ?
                            "임시 편집 파일이 변경되지 않았습니다. 편집기에서 '저장'했는지 확인하세요." :
                            "변경된 내용이 없습니다.");
                }
            });
        });
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] bytes = new byte[65536];
        int read;
        while ((read = in.read(bytes)) != -1) out.write(bytes, 0, read);
        out.flush();
    }

    private void showEditorInstructions() {
        boolean spreadsheet = OfficeFileTypes.isSpreadsheet(filename);
        String website = spreadsheet ?
                "https://play.google.com/store/apps/details?id=com.collabora.libreoffice" :
                "https://github.com/dalgona039/hop_android/releases";
        String label = spreadsheet ? "Collabora Office (LibreOffice 기반)" :
                "HOP Android (rhwp 기반, 베타)";
        new AlertDialog.Builder(this)
                .setTitle(L10n.t("호환 문서 편집기"))
                .setMessage(label + " 설치 페이지를 열까요?\n\n" +
                        "이 앱은 별도로 설치해야 하며, NAS Mount가 설치를 자동 진행하지 않습니다. " +
                        "편집기의 실제 파일 저장 지원 여부는 해당 앱 버전에 따라 다릅니다.")
                .setNegativeButton(L10n.t("취소"), null)
                .setPositiveButton(L10n.t("페이지 열기"), (d,w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(website)));
                    } catch (ActivityNotFoundException error) {
                        showError(L10n.t("링크를 열 브라우저가 없습니다."));
                    }
                }).show();
    }

    private void confirmClose() {
        new AlertDialog.Builder(this).setTitle(L10n.t("편집 화면 닫기"))
                .setMessage(L10n.t("변경 내용이 있으면 '원본에 저장'을 눌러야 반영됩니다. ") +
                        "저장하지 않고 닫으시겠습니까?")
                .setNegativeButton(L10n.t("계속 편집"), null)
                .setPositiveButton(L10n.t("닫기"), (d, w) -> finish()).show();
    }

    @Override public void onBackPressed() { confirmClose(); }
    @Override protected void onDestroy() {
        WebDavExcelLease lease = excelLease;
        if (lease != null) worker.execute(lease::release);
        // Complete any pending commit before releasing the remote owner.
        worker.shutdown();
        super.onDestroy();
    }
}
