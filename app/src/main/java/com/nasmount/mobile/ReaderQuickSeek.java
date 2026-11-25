package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/** Shared 0-100% whole-book navigation for comics and TXT viewers.
 * Actual page/character offsets remain with the owning reader. */
final class ReaderQuickSeek {
    private ReaderQuickSeek(){}

    static void show(Activity activity,String title,int currentPermille,
                     IntFunction<String> detail,IntConsumer jump){
        LinearLayout sheet=Ui.vertical(activity);
        sheet.setPadding(Ui.dp(activity,20),Ui.dp(activity,12),
                Ui.dp(activity,20),Ui.dp(activity,8));
        TextView preview=Ui.text(activity,"",19,Ui.TEXT,true);
        preview.setGravity(Gravity.CENTER);
        sheet.addView(preview,Ui.lp(activity,-1,52));
        SeekBar slider=new SeekBar(activity);
        slider.setMax(1000);
        slider.setProgress(Math.max(0,Math.min(1000,currentPermille)));
        sheet.addView(slider,Ui.lp(activity,-1,62));
        TextView instruction=Ui.text(activity,
                L10n.t("슬라이더를 좌우로 움직이면 책 전체 0~100% 위치를 선택합니다.")+
                "\n'이동'을 누르면 그 위치로 이동하고 '취소'는 현재 위치를 유지합니다.",
                12,Ui.MUTED,false);
        instruction.setGravity(Gravity.CENTER);
        sheet.addView(instruction,Ui.lp(activity,-1,-2));
        final IntConsumer refresh=value->preview.setText(String.format(
                Locale.KOREA,"전체 %.1f%%  ·  %s",value/10f,
                detail.apply(value)));
        refresh.accept(slider.getProgress());
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(
                    SeekBar view,int value,boolean fromUser){refresh.accept(value);}
            @Override public void onStartTrackingTouch(SeekBar view){}
            @Override public void onStopTrackingTouch(SeekBar view){}
        });
        new AlertDialog.Builder(activity)
                .setTitle(title+" 전체 빠른 이동")
                .setView(sheet)
                .setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("이동"),(dialog,which)->
                        jump.accept(slider.getProgress()))
                .show();
    }
    static int fromOffset(long offset,long length){
        if(length<=0)return 0;
        return Math.max(0,Math.min(1000,
                (int)Math.round(1000d*offset/length)));
    }
    static long toOffset(int permille,long length){
        if(length<=0)return 0;
        return Math.max(0,Math.min(length,
                Math.round(length*(Math.max(0,Math.min(1000,
                        permille))/1000d))));
    }
    static int fromPage(int page,int count){
        if(count<2)return 0;
        return Math.max(0,Math.min(1000,
                Math.round(1000f*Math.max(0,Math.min(count-1,page))/
                        (count-1))));
    }
    static int toPage(int permille,int count){
        if(count<=1)return 0;
        return Math.max(0,Math.min(count-1,
                Math.round((count-1)*
                        (Math.max(0,Math.min(1000,permille))/1000f))));
    }
}
