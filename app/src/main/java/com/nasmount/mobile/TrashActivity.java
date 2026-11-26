package com.nasmount.mobile;

import android.app.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import android.text.format.Formatter;
import java.util.*;

public final class TrashActivity extends LocalizedActivity {
    private LinearLayout rows;
    private TextView summary;
    private Button restore,erase,empty,all;
    private final Set<String> selected=new HashSet<>();
    private List<LocalTrash.Entry> items=Collections.emptyList();
    private boolean busy;
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){
        Ui.applyAppearance(this);setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        LinearLayout root=Ui.vertical(this);root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,8));
        LinearLayout header=Ui.horizontal(this);header.setGravity(Gravity.CENTER_VERTICAL);
        Button back=new Button(this);back.setText("‹");back.setContentDescription(L10n.t("뒤로"));back.setOnClickListener(v->finish());
        header.addView(back,Ui.lp(this,48,48));
        TextView title=Ui.text(this,L10n.t("휴지통"),22,Ui.TEXT,true);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(header,Ui.lp(this,-1,54));
        summary=Ui.text(this,"",14,Ui.MUTED,false);root.addView(summary,Ui.lp(this,-1,-2));
        ScrollView scroll=new ScrollView(this);rows=Ui.vertical(this);scroll.addView(rows);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bar=Ui.horizontal(this);
        all=button(bar,"전체 선택",()->{
            int available=0;for(LocalTrash.Entry e:items)if(e.available)available++;
            if(selected.size()==available)selected.clear();else for(LocalTrash.Entry e:items)if(e.available)selected.add(e.id);
            render();
        });
        restore=button(bar,"복원",()->confirm(true,false));
        erase=button(bar,"완전히 삭제",()->confirm(false,false));
        root.addView(bar,Ui.lp(this,-1,-2));
        empty=button(root,"휴지통 비우기",()->confirm(false,true));
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        // Reserve status/navigation bars on Android versions enforcing edge-to-edge.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());
            view.setPadding(Ui.dp(this,12)+bars.left,Ui.dp(this,8)+bars.top,Ui.dp(this,12)+bars.right,Ui.dp(this,8)+bars.bottom);
            return insets;
        });
        getWindow().setStatusBarColor(Ui.BG);getWindow().setNavigationBarColor(Ui.BG);
        getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        setContentView(root);load();
    }
    private Button button(LinearLayout parent,String label,Runnable click){
        Button b=new Button(this);b.setText(L10n.t(label));b.setAllCaps(false);b.setOnClickListener(v->click.run());
        if(parent.getOrientation()==LinearLayout.HORIZONTAL)parent.addView(b,new LinearLayout.LayoutParams(0,-2,1));
        else parent.addView(b,Ui.lp(this,-1,-2));return b;
    }
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    private void load(){
        busy=true;updateButtons();summary.setText(L10n.t("휴지통을 확인하는 중..."));
        worker.execute(()->{LocalTrash.cleanup(getApplicationContext());List<LocalTrash.Entry> list=LocalTrash.list(getApplicationContext());
            runOnUiThread(()->{if(!alive())return;items=list;selected.clear();busy=false;render();});});
    }
    private void updateButtons(){
        boolean available=false;for(LocalTrash.Entry e:items)if(e.available){available=true;break;}
        all.setEnabled(!busy&&available);restore.setEnabled(!busy&&!selected.isEmpty());
        erase.setEnabled(!busy&&!selected.isEmpty());empty.setEnabled(!busy&&available);
    }
    private void render(){
        rows.removeAllViews();long size=0;boolean unknown=false;
        for(LocalTrash.Entry e:items){if(e.available&&e.size>=0)size+=e.size;else unknown=true;}
        String retention=LocalTrash.retention(this)==0?L10n.t("자동 삭제 안 함"):L10n.f("%d일 후 자동 삭제",LocalTrash.retention(this));
        summary.setText(L10n.f("%d개 항목",items.size())+" · "+Formatter.formatFileSize(this,size)+(unknown?" + "+L10n.t("미확인 용량"):"")+"\n"+retention);
        if(items.isEmpty())rows.addView(Ui.text(this,L10n.t("휴지통이 비어 있습니다."),16,Ui.MUTED,false),Ui.lp(this,-1,64));
        java.text.DateFormat dates=java.text.DateFormat.getDateTimeInstance();
        for(LocalTrash.Entry e:items){
            LinearLayout row=Ui.horizontal(this);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,6));
            CheckBox cb=new CheckBox(this);cb.setChecked(selected.contains(e.id));cb.setEnabled(e.available&&!busy);row.addView(cb,Ui.lp(this,44,-2));
            LinearLayout text=Ui.vertical(this);
            text.addView(Ui.text(this,e.name,15,Ui.TEXT,true));
            String volume="primary".equalsIgnoreCase(e.volume)?L10n.t("메인 저장소"):e.volume;
            text.addView(Ui.text(this,volume+" / "+e.relative,12,Ui.MUTED,false));
            text.addView(Ui.text(this,dates.format(new Date(e.removed))+" · "+(e.available?(e.size>=0?Formatter.formatFileSize(this,e.size):L10n.t("미확인 용량")):L10n.t("저장소가 분리되었거나 접근 권한이 없습니다.")),12,Ui.MUTED,false));
            row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
            cb.setOnCheckedChangeListener((b,checked)->{if(checked)selected.add(e.id);else selected.remove(e.id);updateButtons();});
            row.setOnClickListener(v->{if(cb.isEnabled())cb.setChecked(!cb.isChecked());});
            rows.addView(row,Ui.lp(this,-1,-2));
            View line=new View(this);line.setBackgroundColor(Ui.BORDER);rows.addView(line,Ui.lp(this,-1,1));
        }
        updateButtons();
    }
    private void confirm(boolean returning,boolean every){
        if(busy)return;Set<String> ids=new HashSet<>();
        if(every){for(LocalTrash.Entry e:items)if(e.available)ids.add(e.id);}else ids.addAll(selected);
        if(ids.isEmpty())return;
        new AlertDialog.Builder(this).setTitle(L10n.t(returning?"복원":every?"휴지통 비우기":"완전히 삭제"))
            .setMessage(L10n.f(returning?"%d개 항목을 원래 위치로 복원할까요?":"%d개 항목을 완전히 삭제할까요? 삭제 후에는 복원할 수 없습니다.",ids.size()))
            .setNegativeButton(L10n.t("취소"),null).setPositiveButton(L10n.t(returning?"복원":"완전히 삭제"),(d,w)->{
                busy=true;updateButtons();worker.execute(()->{
                    LocalTrash.Outcome o=LocalTrash.act(getApplicationContext(),ids,returning);
                    runOnUiThread(()->{if(!alive())return;
                        if(o.count>0)setResult(RESULT_OK);
                        if(o.failed>0)new AlertDialog.Builder(this).setTitle(L10n.t("일부 항목 처리 실패")).setMessage(o.first).setPositiveButton(L10n.t("확인"),null).show();
                        load();
                    });
                });
            }).show();
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
