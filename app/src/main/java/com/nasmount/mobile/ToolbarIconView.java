package com.nasmount.mobile;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.TypedValue;
import android.view.View;

/**
 * Uniform 24dp vector glyphs on consistent 40dp touch targets.
 * No baseline or variable font metrics: every icon is centered on the
 * exact same 24 x 24 local coordinate system.
 */
final class ToolbarIconView extends View {
    static final String MENU = "menu";
    static final String HOME = "home";
    static final String FAVORITE = "favorite";
    static final String REFRESH = "refresh";
    static final String SEARCH = "search";
    static final String VIEW = "view";
    static final String MORE = "more";
    static final String CROWN = "crown";
    static final String BACK = "back";
    static final String TRASH = "trash";
    static final String RESTORE = "restore";
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final String glyph;
    private boolean checked;
    private int tint = Color.WHITE;

    ToolbarIconView(Context context, String glyph) {
        super(context);
        this.glyph = glyph;
        setClickable(true);
        setFocusable(true);
        TypedValue value = new TypedValue();
        if (context.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, value, true))
            setBackgroundResource(value.resourceId);
    }

    void setChecked(boolean checked) {
        if (this.checked == checked) return;
        this.checked = checked;
        invalidate();
    }
    void setTint(int tint) {
        this.tint = tint;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = getResources().getDisplayMetrics().density * 24f;
        float unit = Math.min(size / 24f, Math.min(getWidth(), getHeight()) / 24f);
        int save = canvas.save();
        canvas.translate((getWidth() - unit * 24) / 2f,
                (getHeight() - unit * 24) / 2f);
        canvas.scale(unit, unit);
        paint.setColor(checked && FAVORITE.equals(glyph) ? 0xFFFFC452 : tint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.95f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        switch (glyph) {
            case BACK:
                canvas.drawLine(20,12,4,12,paint);
                canvas.drawLine(4,12,11,5,paint);canvas.drawLine(4,12,11,19,paint);
                break;
            case RESTORE:
                canvas.drawArc(new RectF(4,4,20,20),-130,300,false,paint);
                canvas.drawLine(3,4,3,11,paint);canvas.drawLine(3,11,10,11,paint);
                canvas.drawLine(12,7,12,12,paint);canvas.drawLine(12,12,16,14,paint);
                break;
            case TRASH:
                canvas.drawLine(4,6,20,6,paint);
                canvas.drawLine(9,3,15,3,paint);
                Path bin=new Path();bin.moveTo(6,6);bin.lineTo(7,21);
                bin.lineTo(17,21);bin.lineTo(18,6);canvas.drawPath(bin,paint);
                canvas.drawLine(10,10,10,17,paint);canvas.drawLine(14,10,14,17,paint);
                break;
            case MENU:
                for (int y : new int[]{5, 12, 19})
                    canvas.drawLine(3.5f, y, 20.5f, y, paint);
                break;
            case HOME: {
                Path roof = new Path();
                roof.moveTo(2.7f, 10.2f);
                roof.lineTo(12f, 2.8f);
                roof.lineTo(21.3f, 10.2f);
                canvas.drawPath(roof, paint);
                canvas.drawLine(5.5f, 9.3f, 5.5f, 21, paint);
                canvas.drawLine(18.5f, 9.3f, 18.5f, 21, paint);
                canvas.drawLine(5.5f, 21, 18.5f, 21, paint);
                break;
            }
            case CROWN: {
                Path crown = new Path();
                crown.moveTo(2.5f,7); crown.lineTo(7.5f,12);
                crown.lineTo(12,4); crown.lineTo(16.5f,12);
                crown.lineTo(21.5f,7); crown.lineTo(19,19);
                crown.lineTo(5,19); crown.close();
                canvas.drawPath(crown,paint);
                break;
            }
            case FAVORITE: {
                Path star = new Path();
                for (int i = 0; i < 10; i++) {
                    double a = Math.PI * i / 5d - Math.PI / 2d;
                    float radius = i % 2 == 0 ? 10f : 4.6f;
                    float x = 12f + radius * (float)Math.cos(a);
                    float y = 12f + radius * (float)Math.sin(a);
                    if (i == 0) star.moveTo(x, y);
                    else star.lineTo(x, y);
                }
                star.close();
                if (checked) paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(star, paint);
                break;
            }
            case REFRESH: {
                RectF arc = new RectF(4.5f, 4.5f, 19.5f, 19.5f);
                canvas.drawArc(arc, -54, 305, false, paint);
                Path head = new Path();
                head.moveTo(20.5f, 4.7f);
                head.lineTo(20.5f, 10.1f);
                head.lineTo(15.2f, 9.1f);
                canvas.drawPath(head, paint);
                break;
            }
            case SEARCH:
                canvas.drawCircle(10.6f, 10.5f, 6.5f, paint);
                canvas.drawLine(15.5f, 15.5f, 21.1f, 21.1f, paint);
                break;
            case VIEW:
                canvas.drawLine(4, 5, 20, 5, paint);
                canvas.drawLine(4, 12, 20, 12, paint);
                canvas.drawLine(4, 19, 20, 19, paint);
                for (int y : new int[]{5, 12, 19})
                    canvas.drawCircle(8, y, 2f, paint);
                break;
            case MORE:
                paint.setStyle(Paint.Style.FILL);
                for (int y : new int[]{5, 12, 19})
                    canvas.drawCircle(12, y, 2f, paint);
                break;
        }
        canvas.restoreToCount(save);
    }
}
