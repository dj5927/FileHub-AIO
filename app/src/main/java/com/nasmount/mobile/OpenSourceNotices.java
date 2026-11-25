package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Offline notices generated from this edition's resolved libraries. */
final class OpenSourceNotices {
    private OpenSourceNotices() {}
    static void show(Activity activity){
        try{
            String[] names=activity.getAssets().list("licenses");
            if(names==null||names.length==0)throw new IllegalStateException("Missing notices");
            Arrays.sort(names);
            new AlertDialog.Builder(activity).setTitle(L10n.t("오픈소스 및 라이선스"))
                    .setItems(names,(d,index)->open(activity,names[index]))
                    .setNeutralButton("GitHub",(d,w)->{
                        try{
                            activity.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(
                                    "https://github.com/dj5927/FileHub-AIO/tree/android/play")));
                        }catch(Exception ignored){}
                    }).setNegativeButton(L10n.t("닫기"),null).show();
        }catch(Exception error){failure(activity);}
    }
    private static void open(Activity activity,String name){
        try(InputStream input=activity.getAssets().open("licenses/"+name);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;
            while((n=input.read(buffer))!=-1){
                if(out.size()+n>4*1024*1024)throw new IllegalStateException("Notice too large");
                out.write(buffer,0,n);
            }
            TextView body=new TextView(activity);
            body.setText(new String(out.toByteArray(),StandardCharsets.UTF_8));
            body.setTextSize(12);body.setTextIsSelectable(true);
            body.setPadding(Ui.dp(activity,16),Ui.dp(activity,12),Ui.dp(activity,16),Ui.dp(activity,12));
            ScrollView scroll=new ScrollView(activity);
            scroll.addView(body);
            new AlertDialog.Builder(activity).setTitle(name).setView(scroll)
                    .setNegativeButton(L10n.t("닫기"),null).show();
        }catch(Exception error){failure(activity);}
    }
    private static void failure(Activity activity){
        new AlertDialog.Builder(activity).setMessage(L10n.t("라이선스 내용을 열 수 없습니다."))
                .setPositiveButton(L10n.t("확인"),null).show();
    }
}
