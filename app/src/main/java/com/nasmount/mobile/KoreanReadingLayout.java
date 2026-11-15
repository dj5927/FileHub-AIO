package com.nasmount.mobile;

import android.graphics.text.LineBreakConfig;
import android.graphics.text.LineBreaker;
import android.os.Build;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.widget.TextView;
import java.util.Locale;

/**
 * Keep Android's locale-aware phrase/word wrapping IDENTICAL for the real
 * TextView and for the per-page measuring StaticLayout. Android 13+ has
 * native Korean phrase boundaries; use normal fallback on older devices.
 *
 * Do not switch page mode back to HIGH_QUALITY: it re-optimizes whole
 * paragraphs when a page substring ends and can silently hide lines.
 */
final class KoreanReadingLayout {
    private KoreanReadingLayout(){}

    static void configure(TextView view,boolean paged){
        view.setTextLocale(Locale.KOREAN);
        view.setBreakStrategy(paged?
                LineBreaker.BREAK_STRATEGY_SIMPLE:
                LineBreaker.BREAK_STRATEGY_HIGH_QUALITY);
        view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if(Build.VERSION.SDK_INT>=33){
            view.setLineBreakWordStyle(
                    LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE);
            view.setLineBreakStyle(LineBreakConfig.LINE_BREAK_STYLE_NORMAL);
        }
    }
    static StaticLayout.Builder configure(StaticLayout.Builder builder){
        builder.setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE);
        builder.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
        if(Build.VERSION.SDK_INT>=33){
            builder.setLineBreakConfig(new LineBreakConfig.Builder()
                    .setLineBreakWordStyle(
                            LineBreakConfig.LINE_BREAK_WORD_STYLE_PHRASE)
                    .setLineBreakStyle(
                            LineBreakConfig.LINE_BREAK_STYLE_NORMAL)
                    .build());
        }
        return builder;
    }
    static TextPaint bookPaint(TextPaint old){
        TextPaint result=new TextPaint(old);
        result.setTextLocale(Locale.KOREAN);
        return result;
    }
}
