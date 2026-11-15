package com.nasmount.mobile;

import android.app.Activity;
import android.os.Build;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.IntFunction;

/**
 * In-book reader settings: the selected exact value is visible beside its
 * slider label at all times and follows the user's finger in real time.
 * The existing persisted values and slider increments stay unchanged.
 */
final class ReaderNumericSlider {
    private ReaderNumericSlider() {}

    static SeekBar add(Activity activity, LinearLayout parent, String caption,
                       int min, int max, int selected,
                       IntFunction<String> valueFormat) {
        if (min > max)
            throw new IllegalArgumentException("Reader slider range");
        // Preserve the old SeekBar's clamping behavior for stale preferences.
        int selectedValue = Math.max(min, Math.min(max, selected));

        LinearLayout labels = Ui.horizontal(activity);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = Ui.text(activity, caption, 14, Ui.TEXT, true);
        LinearLayout.LayoutParams titleLayout =
                new LinearLayout.LayoutParams(0, -2, 1);
        titleLayout.rightMargin = Ui.dp(activity, 8);
        labels.addView(title, titleLayout);

        TextView number = Ui.text(activity, valueFormat.apply(selectedValue),
                15, Ui.TEAL, true);
        number.setSingleLine(true);
        number.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        number.setMinWidth(Ui.dp(activity, 58));
        labels.addView(number, Ui.lp(activity, -2, 34));
        parent.addView(labels, Ui.lp(activity, -1, -2));

        SeekBar slider = new SeekBar(activity);
        slider.setMax(max - min);
        slider.setProgress(selectedValue - min);
        slider.setContentDescription(caption);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress,
                                                     boolean fromUser) {
                String formatted = valueFormat.apply(min + progress);
                number.setText(formatted);
                if (Build.VERSION.SDK_INT >= 30)
                    bar.setStateDescription(caption + " " + formatted);
                else
                    bar.setContentDescription(caption + " " + formatted);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        if (Build.VERSION.SDK_INT >= 30)
            slider.setStateDescription(caption + " " +
                    valueFormat.apply(selectedValue));
        else
            slider.setContentDescription(caption + " " +
                    valueFormat.apply(selectedValue));
        parent.addView(slider, Ui.lp(activity, -1, 44));
        return slider;
    }

    static String points(int value) { return value + "pt"; }
    static String dp(int value) { return value + "dp"; }
    static String percent(int value) { return value + "%"; }
    static String spacing(int percent) {
        return String.format(Locale.KOREA, "%.2f배", percent / 100.0);
    }
    static String brightness(int value) {
        return value > 0 ? "+" + value : Integer.toString(value);
    }
}
