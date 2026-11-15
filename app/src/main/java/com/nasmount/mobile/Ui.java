package com.nasmount.mobile;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static int BG = Color.rgb(248, 249, 251);
    static int DARK = Color.rgb(30, 34, 36);
    static int TEXT = Color.rgb(34, 41, 46);
    static int MUTED = Color.rgb(125, 135, 143);
    static int BORDER = Color.rgb(230, 234, 237);
    static int TEAL = Color.rgb(11, 143, 131);
    static int PALE = Color.rgb(226, 247, 242);
    static int SURFACE = Color.WHITE;
    static boolean darkMode;

    static void applyAppearance(Context context) {
        darkMode = AppearanceSettings.isDark(context);
        BG = darkMode ? 0xFF14181D : 0xFFF8F9FB;
        DARK = darkMode ? 0xFF10151B : 0xFF1E2224;
        TEXT = darkMode ? 0xFFE8EDF1 : 0xFF22292E;
        MUTED = darkMode ? 0xFFA5B0BA : 0xFF7D878F;
        BORDER = darkMode ? 0xFF343F49 : 0xFFE6EAED;
        TEAL = darkMode ? 0xFF4CC8B8 : 0xFF0B8F83;
        PALE = darkMode ? 0xFF193A38 : 0xFFE2F7F2;
        SURFACE = darkMode ? 0xFF222930 : Color.WHITE;
    }
    private Ui() {}
    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
    static GradientDrawable bg(Context c, int color, int radius, int stroke) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(color);
        out.setCornerRadius(dp(c, radius));
        if (stroke != 0) out.setStroke(dp(c, 1), stroke);
        return out;
    }
    static TextView text(Context c, String content, float size, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(content);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    static LinearLayout vertical(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    static LinearLayout horizontal(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    static LinearLayout.LayoutParams lp(Context c, int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(c, w),
                h < 0 ? h : dp(c, h));
    }
    static TextView action(Context c, String label) {
        TextView b = text(c, label, 15, TEAL, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8));
        b.setBackground(bg(c, PALE, 13, 0));
        b.setClickable(true);
        return b;
    }
    static void gap(LinearLayout parent, Context c, int h) {
        View v = new View(c);
        parent.addView(v, lp(c, 1, h));
    }

    /** Simple vector artwork: no external icon service or image downloads. */
    static final class Icon extends View {
        final String kind;
        final int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context context, String kind, int color) {
            super(context);
            this.kind = kind;
            this.color = color;
        }
        private void stroke(Canvas c, float width) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }
        private void fill(Canvas c) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
        }
        private void line(Canvas c, float x1, float y1, float x2, float y2) {
            c.drawLine(x1, y1, x2, y2, paint);
        }
        @Override protected void onDraw(Canvas raw) {
            super.onDraw(raw);
            int save = raw.save();
            float width = getWidth(), height = getHeight();
            float scale = Math.min(width, height) / 64f;
            raw.translate((width - 64 * scale) / 2, (height - 64 * scale) / 2);
            raw.scale(scale, scale);
            Canvas c = raw;
            switch (kind) {
                case "drive":
                    stroke(c, 3.5f); c.drawRoundRect(10, 16, 54, 47, 6, 6, paint);
                    fill(c); c.drawCircle(19, 38, 3, paint);
                    paint.setAlpha(120); c.drawRoundRect(14, 51, 50, 55, 2, 2, paint);
                    break;
                case "sd":
                    stroke(c, 3.7f);
                    Path card = new Path();
                    card.moveTo(21, 9); card.lineTo(46, 9); card.lineTo(54, 18);
                    card.lineTo(54, 55); card.lineTo(14, 55); card.lineTo(14, 19);
                    card.close(); c.drawPath(card, paint);
                    fill(c); c.drawRoundRect(22, 18, 28, 31, 1, 1, paint);
                    c.drawRoundRect(34, 18, 40, 31, 1, 1, paint);
                    break;
                case "download":
                    stroke(c, 3.7f); c.drawRoundRect(12, 16, 52, 52, 5, 5, paint);
                    line(c, 32, 20, 32, 39); line(c, 23, 31, 32, 40); line(c, 41, 31, 32, 40);
                    break;
                case "image":
                    stroke(c, 3.7f); c.drawRoundRect(10, 13, 54, 51, 5, 5, paint);
                    c.drawCircle(43, 25, 4, paint);
                    Path mountains = new Path();
                    mountains.moveTo(13, 45); mountains.lineTo(27, 30);
                    mountains.lineTo(36, 38); mountains.lineTo(42, 32);
                    mountains.lineTo(53, 44); c.drawPath(mountains, paint);
                    break;
                case "audio":
                    fill(c);
                    Path music = new Path();
                    music.moveTo(31, 14); music.lineTo(51, 10);
                    music.lineTo(51, 42); music.lineTo(46, 42);
                    music.lineTo(46, 19); music.lineTo(36, 21);
                    music.lineTo(36, 47); music.lineTo(31, 47);
                    music.close(); c.drawPath(music, paint);
                    c.drawOval(18, 43, 36, 53, paint); c.drawOval(35, 38, 53, 48, paint);
                    break;
                case "video":
                    stroke(c, 3.5f); c.drawRoundRect(11, 13, 53, 51, 5, 5, paint);
                    line(c, 19, 14, 19, 50); line(c, 45, 14, 45, 50);
                    fill(c);
                    Path play = new Path();
                    play.moveTo(26, 23); play.lineTo(26, 41);
                    play.lineTo(40, 32); play.close(); c.drawPath(play, paint);
                    break;
                case "comic":
                    stroke(c,3.3f);
                    c.drawRoundRect(11,9,53,55,4,4,paint);
                    line(c,32,10,32,54);
                    line(c,16,20,27,20);
                    line(c,16,28,26,28);
                    c.drawRoundRect(36,20,47,34,3,3,paint);
                    line(c,40,34,39,39);
                    break;
                case "book":
                    stroke(c,3.5f);
                    Path openBook=new Path();
                    openBook.moveTo(32,17);
                    openBook.quadTo(23,11,10,16);
                    openBook.lineTo(10,49);
                    openBook.quadTo(22,45,32,53);
                    openBook.quadTo(42,45,54,49);
                    openBook.lineTo(54,16);
                    openBook.quadTo(41,11,32,17);
                    openBook.close();
                    c.drawPath(openBook,paint);
                    line(c,32,17,32,53);
                    break;
                case "remote":
                case "folder":
                    fill(c);
                    Path folder = new Path();
                    folder.moveTo(7, 17); folder.lineTo(25, 17);
                    folder.lineTo(31, 24); folder.lineTo(56, 24);
                    folder.quadTo(59, 24, 59, 29);
                    folder.lineTo(57, 49); folder.quadTo(57, 53, 53, 53);
                    folder.lineTo(10, 53); folder.quadTo(6, 53, 6, 49);
                    folder.close(); c.drawPath(folder, paint);
                    if (kind.equals("remote")) {
                        paint.setColor(Color.WHITE);
                        c.drawRoundRect(22, 32, 45, 45, 2, 2, paint);
                        paint.setColor(color); paint.setStrokeWidth(2);
                        paint.setStyle(Paint.Style.STROKE);
                        c.drawRect(26, 35, 41, 41, paint);
                    }
                    break;
                case "star":
                    fill(c);
                    Path star = new Path();
                    for (int i=0;i<10;i++) {
                        double a = Math.PI * i / 5 - Math.PI / 2;
                        float r = i % 2 == 0 ? 25 : 11;
                        float x = 32 + (float)Math.cos(a)*r;
                        float y = 32 + (float)Math.sin(a)*r;
                        if (i==0) star.moveTo(x,y); else star.lineTo(x,y);
                    } star.close(); c.drawPath(star,paint);
                    break;
                case "settings":
                    stroke(c, 4.0f);
                    c.drawCircle(32, 32, 13, paint);
                    c.drawCircle(32, 32, 5, paint);
                    for (int i=0; i<8; i++) {
                        float a = (float)(i * Math.PI / 4);
                        c.drawLine(32 + (float)Math.cos(a)*17,
                                32 + (float)Math.sin(a)*17,
                                32 + (float)Math.cos(a)*25,
                                32 + (float)Math.sin(a)*25, paint);
                    }
                    break;
                case "file":
                    stroke(c, 3.4f);
                    Path page = new Path();
                    page.moveTo(18, 10); page.lineTo(39, 10);
                    page.lineTo(49, 21); page.lineTo(49, 54);
                    page.lineTo(18, 54); page.close(); c.drawPath(page, paint);
                    line(c, 24, 34, 43, 34); line(c, 24, 42, 39, 42);
                    break;
                case "cloud":
                    fill(c); c.drawRoundRect(9, 34, 55, 50, 10, 10, paint);
                    c.drawCircle(22, 36, 12, paint);
                    c.drawCircle(36, 29, 17, paint);
                    c.drawCircle(48, 38, 9, paint);
                    break;
                default:
                    fill(c); c.drawCircle(32, 32, 18, paint);
            }
            raw.restoreToCount(save);
        }
    }
}
