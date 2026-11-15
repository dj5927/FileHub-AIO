package com.nasmount.mobile;

import android.view.View;

/** GPU-only, short page turn transitions. No animated full-resolution bitmap
 * copies, to avoid memory churn with large comics and folded displays. */
final class BookTurnEffects {
    static final int NONE=0, SLIDE=1, FADE=2;
    private BookTurnEffects(){}
    static void show(View page,int direction,int style) {
        page.animate().cancel();
        page.setTranslationX(0f);
        page.setAlpha(1f);
        if(style==NONE)return;
        if(style==SLIDE) {
            float shift=Math.min(page.getWidth()*0.14f,140f);
            page.setTranslationX(direction>=0?shift:-shift);
            page.setAlpha(.72f);
        }else page.setAlpha(.45f);
        page.animate().translationX(0f).alpha(1f)
                .setDuration(style==SLIDE?200:170).start();
    }
}
