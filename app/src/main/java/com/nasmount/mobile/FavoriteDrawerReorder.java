package com.nasmount.mobile;

import android.animation.LayoutTransition;
import android.animation.ObjectAnimator;
import android.content.ClipData;
import android.content.Context;
import android.os.Build;
import android.view.DragEvent;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Long-press to lift a drawer favorite, drag vertically to reorder, save on drop. */
final class FavoriteDrawerReorder {
    interface OnSave {void save(List<String> keys) throws Exception;}
    interface OnFailure {void failed(Exception error);}

    private final LinearLayout rows;
    private final ScrollView scroll;
    private final OnSave saver;
    private final OnFailure failure;
    private final List<String> keys=new ArrayList<>();
    private final IdentityHashMap<View,String> itemKeys=new IdentityHashMap<>();
    private View dragging;
    private ObjectAnimator wobble;
    private boolean dropped;
    private int dropIndex=-1;

    FavoriteDrawerReorder(LinearLayout rows,ScrollView scroll,
                          OnSave saver,OnFailure failure) {
        this.rows=rows;
        this.scroll=scroll;
        this.saver=saver;
        this.failure=failure;
        LayoutTransition transitions=new LayoutTransition();
        transitions.enableTransitionType(LayoutTransition.CHANGING);
        transitions.setDuration(LayoutTransition.CHANGING,140);
        transitions.setDuration(LayoutTransition.DISAPPEARING,95);
        transitions.setDuration(LayoutTransition.APPEARING,95);
        rows.setLayoutTransition(transitions);
        rows.setOnDragListener(this::onDrag);
    }

    void add(View row,String key) {
        if(itemKeys.containsValue(key))
            throw new IllegalArgumentException("즐겨찾기가 중복되었습니다.");
        keys.add(key);
        itemKeys.put(row,key);
        row.setOnLongClickListener(v -> start(row));
        row.setContentDescription(L10n.t("길게 눌러 순서 이동, 오른쪽 메뉴에서 이름 변경 또는 삭제"));
    }

    private boolean start(View row) {
        if(dragging!=null || keys.size()<2)return false;
        dragging=row;
        dropped=false;
        dropIndex=rows.indexOfChild(row);
        row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        row.setAlpha(.34f);
        row.setScaleX(1.035f);
        row.setScaleY(1.035f);
        wobble=ObjectAnimator.ofFloat(row,View.ROTATION,-1.3f,1.3f);
        wobble.setDuration(130);
        wobble.setRepeatCount(ObjectAnimator.INFINITE);
        wobble.setRepeatMode(ObjectAnimator.REVERSE);
        wobble.setInterpolator(new LinearInterpolator());
        wobble.start();
        View.DragShadowBuilder shadow=new View.DragShadowBuilder(row);
        boolean started=Build.VERSION.SDK_INT>=24
                ? row.startDragAndDrop(ClipData.newPlainText("favorite", "reorder"),
                        shadow,row,0)
                : row.startDrag(ClipData.newPlainText("favorite", "reorder"),
                        shadow,row,0);
        if(!started)finish(false);
        return started;
    }

    private boolean onDrag(View target,DragEvent event) {
        if(dragging==null || event.getLocalState()!=dragging)return false;
        switch(event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED:
                return true;
            case DragEvent.ACTION_DRAG_LOCATION:
                moveToward(event.getY());
                return true;
            case DragEvent.ACTION_DROP:
                dropped=true;
                return true;
            case DragEvent.ACTION_DRAG_ENDED:
                finish(dropped && event.getResult());
                return true;
            default:
                return true;
        }
    }

    private void moveToward(float pointerY) {
        if(dragging==null)return;
        // Moving over the top/bottom of a long list also scrolls the drawer.
        int[] location=new int[2];
        rows.getLocationOnScreen(location);
        int[] viewport=new int[2];
        scroll.getLocationOnScreen(viewport);
        float screenY=location[1]+pointerY;
        if(screenY<viewport[1]+dp(rows.getContext(),74))scroll.smoothScrollBy(0,-dp(rows.getContext(),22));
        else if(screenY>viewport[1]+scroll.getHeight()-dp(rows.getContext(),74))
            scroll.smoothScrollBy(0,dp(rows.getContext(),22));

        int current=rows.indexOfChild(dragging);
        if(current<0)return;
        int dest=current;
        for(int i=0;i<rows.getChildCount();i++) {
            if(i==current)continue;
            View other=rows.getChildAt(i);
            float middle=other.getTop()+other.getHeight()/2f;
            if(pointerY>middle && i>dest)dest=i;
            else if(pointerY<middle && i<dest)dest=i;
        }
        if(dest==dropIndex)return;
        dropIndex=dest;
        // Keep the source View attached throughout Android's drag operation.
        // Adjacent rows slide into the projected gap with a short animation.
        for(int i=0;i<rows.getChildCount();i++) {
            View item=rows.getChildAt(i);
            if(item==dragging)continue;
            float offset=0f;
            if(dest>current && i>current && i<=dest)
                offset=-dragging.getHeight();
            else if(dest<current && i>=dest && i<current)
                offset=dragging.getHeight();
            item.animate().translationY(offset).setDuration(125).start();
        }
        dragging.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void finish(boolean accepted) {
        if(dragging==null)return;
        View lifted=dragging;
        dragging=null;
        if(wobble!=null){wobble.cancel();wobble=null;}
        lifted.animate().cancel();
        lifted.setRotation(0f);
        lifted.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start();
        for(int i=0;i<rows.getChildCount();i++) {
            View item=rows.getChildAt(i);
            if(item==lifted)continue;
            item.animate().cancel();
            item.setTranslationY(0f);
        }
        if(!accepted || dropIndex<0){dropIndex=-1;return;}
        int source=rows.indexOfChild(lifted);
        int target=dropIndex;
        dropIndex=-1;
        if(source<0 || source==target)return;
        List<String> order=FavoriteOrderPolicy.move(keys,source,target);
        try {
            saver.save(order);
            ViewGroup.LayoutParams layout=lifted.getLayoutParams();
            rows.removeView(lifted);
            rows.addView(lifted,target,layout);
            keys.clear();keys.addAll(order);
        }catch(Exception error){failure.failed(error);}
    }

    private static int dp(Context context,int value) {
        return Math.round(value*context.getResources().getDisplayMetrics().density);
    }
}
