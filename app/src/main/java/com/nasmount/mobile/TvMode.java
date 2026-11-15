package com.nasmount.mobile;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

/** Shared Android TV detection and focus treatment for existing file screens. */
final class TvMode {
    private TvMode() {}

    static boolean isTv(Context context) {
        UiModeManager manager=(UiModeManager)context.getSystemService(Context.UI_MODE_SERVICE);
        PackageManager pm=context.getPackageManager();
        return (manager != null && manager.getCurrentModeType()==
                Configuration.UI_MODE_TYPE_TELEVISION) ||
                pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                pm.hasSystemFeature("android.hardware.type.television");
    }

    static void decorate(View view, Context context) {
        if (view instanceof ViewGroup) {
            ViewGroup parent=(ViewGroup)view;
            for(int i=0;i<parent.getChildCount();i++)
                decorate(parent.getChildAt(i),context);
        }
        if (view instanceof EditText || !view.isClickable() || view.isFocusable())return;
        view.setFocusable(true);
        view.setFocusableInTouchMode(false);
        GradientDrawable focused=new GradientDrawable();
        focused.setColor(Color.TRANSPARENT);
        focused.setCornerRadius(Ui.dp(context,12));
        focused.setStroke(Ui.dp(context,3),Ui.TEAL);
        GradientDrawable normal=new GradientDrawable();
        normal.setColor(Color.TRANSPARENT);
        StateListDrawable outlines=new StateListDrawable();
        outlines.addState(new int[]{android.R.attr.state_focused},focused);
        outlines.addState(new int[]{},normal);
        view.setForeground(outlines);
        view.setOnFocusChangeListener((v,has)->{
            v.animate().scaleX(has?1.025f:1f).scaleY(has?1.025f:1f)
                    .setDuration(110).start();
        });
    }
}
