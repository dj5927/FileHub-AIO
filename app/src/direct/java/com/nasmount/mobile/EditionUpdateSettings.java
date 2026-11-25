package com.nasmount.mobile;

import android.app.AlertDialog;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;

final class EditionUpdateSettings {
    static void render(SettingsActivity a) {
        a.section(L10n.t("Direct 업데이트"),L10n.t("비공개 서버를 지정하면 공개 GitHub 대신 해당 서버에서만 업데이트합니다."));
        a.action(L10n.t("비공개 OTA 설정"),PrivateOtaChannel.enabled(a)?
                L10n.t("비공개 업데이트 서버 사용 중"):L10n.t("기존 공개 OTA 사용 중"),()->edit(a));
    }
    private static void edit(SettingsActivity a) {
        LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(a.dp(20),a.dp(8),a.dp(20),0);
        EditText url=new EditText(a);url.setSingleLine(true);
        url.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://server.example/filehub/direct/");
        url.setText(PrivateOtaChannel.endpoint(a));panel.addView(url);
        EditText token=new EditText(a);token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setHint(L10n.t("개인 접근 코드"));panel.addView(token);
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle(L10n.t("비공개 OTA 설정"))
                .setView(panel).setNegativeButton(L10n.t("취소"),null)
                .setNeutralButton(L10n.t("공개 OTA 사용"),(d,w)->{
                    PrivateOtaChannel.clear(a);a.render();
                }).setPositiveButton(L10n.t("저장"),null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try {
                PrivateOtaChannel.save(a,url.getText().toString(),token.getText().toString());
                dialog.dismiss();a.render();
            } catch(Exception e) { token.setError(L10n.t("서버 주소 또는 접근 코드를 확인하세요.")); }
        }));
        dialog.show();
    }
}
