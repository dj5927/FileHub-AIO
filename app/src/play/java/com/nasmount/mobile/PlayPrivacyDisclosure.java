package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

/** In-app disclosure; production policy URL is supplied by the publisher. */
final class PlayPrivacyDisclosure {
    private PlayPrivacyDisclosure() {}

    static void show(Activity activity) {
        String url = BuildConfig.PRIVACY_POLICY_URL;
        String body = activity.getString(R.string.play_privacy_body);
        if (url.isEmpty()) body = activity.getString(R.string.play_privacy_internal) + "\n\n" + body;
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.play_privacy_title)
                .setMessage(body)
                .setNegativeButton(L10n.t("닫기"), null);
        if (!url.isEmpty()) {
            dialog.setPositiveButton(R.string.play_privacy_open, (d, w) -> {
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException | SecurityException error) {
                    Toast.makeText(activity, R.string.play_privacy_open_failed, Toast.LENGTH_LONG).show();
                }
            });
        }
        dialog.show();
    }
}
