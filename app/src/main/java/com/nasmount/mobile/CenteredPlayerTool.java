package com.nasmount.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.widget.TextView;

/**
 * The icon is painted by the button's BACKGROUND drawable, not TextView.onDraw.
 *
 * Samsung One UI skipped the old empty TextView custom onDraw on real hardware
 * (the background rendered, but all three icon circles were empty). A single
 * Drawable now renders the circle and its symbol within the same draw(Canvas)
 * call, so the symbol cannot disappear while the circle stays visible.
 */
final class CenteredPlayerTool extends TextView {
    static final int ROTATE = 1;
    static final int PLUS = 2;
    static final int MINUS = 3;

    CenteredPlayerTool(Context context, int symbol) {
        super(context);
        setText("");
        setGravity(Gravity.CENTER);
        setIncludeFontPadding(false);
        setPadding(0, 0, 0, 0);
        // Android always paints the background for a visible view, even when
        // its text is empty and it skips its content onDraw callback.
        setBackground(new IconBackground(context, symbol));
    }

    private static final class IconBackground extends Drawable {
        private final int symbol;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bounds = new RectF();
        private final RectF arc = new RectF();
        private final Path arrow = new Path();
        private final float density;
        private int opacity = 255;
        private ColorFilter colorFilter;

        IconBackground(Context context, int symbol) {
            this.symbol = symbol;
            this.density = context.getResources().getDisplayMetrics().density;
        }

        @Override public void draw(Canvas canvas) {
            Rect rect = getBounds();
            if (rect.isEmpty()) return;
            float cx = rect.exactCenterX();
            float cy = rect.exactCenterY();
            float diameter = Math.min(rect.width(), rect.height());
            float corner = diameter * 0.5f;
            bounds.set(rect.left, rect.top, rect.right, rect.bottom);

            paint.setColor(0xBC202831);
            paint.setAlpha(Math.round(188f * opacity / 255f));
            paint.setStrokeWidth(0);
            paint.setStyle(Paint.Style.FILL);
            paint.setColorFilter(colorFilter);
            canvas.drawRoundRect(bounds, corner, corner, paint);

            float outline = Math.max(1f, density);
            paint.setColor(0x48FFFFFF);
            paint.setAlpha(Math.round(72f * opacity / 255f));
            paint.setStrokeWidth(outline);
            paint.setStyle(Paint.Style.STROKE);
            bounds.inset(outline * 0.5f, outline * 0.5f);
            canvas.drawRoundRect(bounds, corner, corner, paint);

            float radius = diameter * 0.275f;
            if (radius < 1f) return;
            paint.setColor(0xFFFFFFFF);
            paint.setAlpha(opacity);
            paint.setStrokeWidth(radius * 0.24f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);

            if (symbol == PLUS || symbol == MINUS) {
                float halfLength = radius * 0.89f;
                canvas.drawLine(cx - halfLength, cy,
                        cx + halfLength, cy, paint);
                if (symbol == PLUS)
                    canvas.drawLine(cx, cy - halfLength,
                            cx, cy + halfLength, paint);
            } else if (symbol == ROTATE) {
                arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
                float start = 33f;
                float sweep = 285f;
                canvas.drawArc(arc, start, sweep, false, paint);
                double angle = Math.toRadians(start + sweep);
                float x = cx + radius * (float) Math.cos(angle);
                float y = cy + radius * (float) Math.sin(angle);
                float tx = -(float) Math.sin(angle);
                float ty = (float) Math.cos(angle);
                float nx = -ty;
                float ny = tx;
                float length = radius * 0.65f;
                float width = radius * 0.42f;
                arrow.reset();
                arrow.moveTo(x + tx * length * 0.45f,
                        y + ty * length * 0.45f);
                arrow.lineTo(x - tx * length * 0.56f + nx * width,
                        y - ty * length * 0.56f + ny * width);
                arrow.lineTo(x - tx * length * 0.56f - nx * width,
                        y - ty * length * 0.56f - ny * width);
                arrow.close();
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(arrow, paint);
            }
        }

        @Override public void setAlpha(int alpha) {
            opacity = Math.max(0, Math.min(255, alpha));
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter filter) {
            colorFilter = filter;
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
