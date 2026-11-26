package com.nasmount.mobile;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Recycle-bin UI uses the shared Flutter theme/file icons. Native fallback
 * owns the same journal commands and is fully usable without Flutter. */
public final class TrashActivity extends LocalizedActivity implements FlutterNavigationSurface.Host {
    private LinearLayout rows,selectBar;
    private TextView summary,selectionLabel;
    private CheckBox selectAll;
    private TextView restore,erase,empty;
    private ToolbarIconView refresh;
    private FrameLayout frame;
    private LinearLayout fallback;
    private FlutterNavigationSurface surface;
    private final Set<String> selected=new LinkedHashSet<>();
    private List<LocalTrash.Entry> items=Collections.emptyList();
    private String revision=UUID.randomUUID().toString();
    private boolean busy,mutating,rendering;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state){
        Ui.applyAppearance(this);setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        LinearLayout root=Ui.vertical(this);root.setBackgroundColor(Ui.BG);
        LinearLayout header=Ui.horizontal(this);header.setBackgroundColor(Ui.DARK);
        header.setPadding(dp(8),0,dp(8),0);
        ToolbarIconView back=new ToolbarIconView(this,ToolbarIconView.BACK);
        back.setContentDescription(L10n.t("뒤로"));back.setOnClickListener(v->onBackPressed());
        header.addView(back,Ui.lp(this,40,-1));
        TextView title=Ui.text(this,L10n.t("휴지통"),19,Color.WHITE,true);
        title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        root.addView(header,Ui.lp(this,-1,FileHubBrandLayout.HEADER_HEIGHT_DP));
        frame=new FrameLayout(this);
        root.addView(frame,new LinearLayout.LayoutParams(-1,0,1));
        fallback=Ui.vertical(this);
        frame.addView(fallback,new FrameLayout.LayoutParams(-1,-1));
        buildFallback();
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        // The ad wrapper gives the banner its own measured sibling slot, so
        // both the footer and native fallback remain inside the content bounds.
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left,0,bars.right,bars.bottom);
            header.setPadding(dp(8),bars.top,dp(8),0);
            header.getLayoutParams().height=dp(FileHubBrandLayout.HEADER_HEIGHT_DP)+bars.top;
            header.requestLayout();return insets;
        });
        getWindow().setStatusBarColor(Ui.DARK);getWindow().setNavigationBarColor(Ui.BG);


        frame.setBackgroundColor(Ui.BG);
        setContentView(EditionMonetization.content(this,root));
        getWindow().getDecorView();
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(),root).setAppearanceLightNavigationBars(!Ui.darkMode);
        ViewCompat.requestApplyInsets(root);
        begin("refresh",Collections.emptySet(),null);
    }
    private int dp(int n){return Ui.dp(this,n);}
    private boolean alive(){return !isFinishing()&&!isDestroyed();}
    private void line(LinearLayout parent){
        View separator=new View(this);separator.setBackgroundColor(Ui.BORDER);parent.addView(separator,Ui.lp(this,-1,1));
    }
    private TextView action(LinearLayout bar,String label,String icon,Runnable handler){
        LinearLayout button=Ui.vertical(this);button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(dp(58));button.setPadding(dp(3),dp(7),dp(3),dp(7));
        ToolbarIconView glyph=new ToolbarIconView(this,icon);
        glyph.setClickable(false);glyph.setFocusable(false);button.addView(glyph,Ui.lp(this,24,24));
        TextView text=Ui.text(this,L10n.t(label),11,Color.WHITE,false);
        text.setGravity(Gravity.CENTER);text.setMaxLines(2);button.addView(text,Ui.lp(this,-1,-2));
        button.setContentDescription(L10n.t(label));button.setOnClickListener(v->handler.run());
        bar.addView(button,new LinearLayout.LayoutParams(0,-1,1));
        text.setTag(button);return text;
    }
    private void enabled(TextView text,boolean on){
        View parent=(View)text.getTag();parent.setEnabled(on);parent.setAlpha(on?1f:.36f);
    }
    private void buildFallback(){
        LinearLayout info=Ui.horizontal(this);info.setBackgroundColor(Ui.SURFACE);
        info.setPadding(dp(14),dp(10),dp(8),dp(10));
        summary=Ui.text(this,"",13,Ui.TEXT,false);
        info.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
        refresh=new ToolbarIconView(this,ToolbarIconView.REFRESH);refresh.setTint(Ui.TEXT);
        refresh.setContentDescription(L10n.t("새로고침"));refresh.setOnClickListener(v->begin("refresh",Collections.emptySet(),null));
        info.addView(refresh,Ui.lp(this,48,48));fallback.addView(info,Ui.lp(this,-1,-2));line(fallback);
        selectBar=Ui.horizontal(this);selectBar.setBackgroundColor(Ui.SURFACE);selectBar.setPadding(dp(10),0,dp(10),0);
        selectAll=new CheckBox(this);selectAll.setButtonTintList(ColorStateList.valueOf(Ui.TEAL));
        selectAll.setContentDescription(L10n.t("전체 선택"));selectBar.addView(selectAll,Ui.lp(this,38,44));
        selectionLabel=Ui.text(this,L10n.t("전체 선택"),12,Ui.MUTED,false);
        selectBar.addView(selectionLabel,new LinearLayout.LayoutParams(0,-1,1));
        selectAll.setOnCheckedChangeListener((v,checked)->{if(rendering||busy)return;
            selected.clear();if(checked)selected.addAll(availableIds());renderFallback();});
        fallback.addView(selectBar,Ui.lp(this,-1,44));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        rows=Ui.vertical(this);scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));
        fallback.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout bar=Ui.horizontal(this);bar.setBackgroundColor(0xFF003544);
        restore=action(bar,"복원",ToolbarIconView.RESTORE,()->confirmNative("restore"));
        erase=action(bar,"완전히 삭제",ToolbarIconView.TRASH,()->confirmNative("erase"));
        empty=action(bar,"휴지통 비우기",ToolbarIconView.TRASH,()->confirmNative("empty"));
        fallback.addView(bar,Ui.lp(this,-1,-2));
    }
    private Set<String> availableIds(){
        Set<String> ids=new LinkedHashSet<>();
        for(LocalTrash.Entry e:items)if(e.available)ids.add(e.id);return ids;
    }
    private String origin(LocalTrash.Entry e){
        String volume="primary".equalsIgnoreCase(e.volume)?L10n.t("메인 저장소"):e.volume;
        int slash=e.relative.lastIndexOf('/');
        return slash<0?volume:volume+" / "+e.relative.substring(0,slash);
    }
    private String detail(LocalTrash.Entry e){
        String time=java.text.DateFormat.getDateTimeInstance().format(new Date(e.removed));
        return time+" · "+(e.available?(e.size>=0?Formatter.formatFileSize(this,e.size):L10n.t("미확인 용량")):
                L10n.t("저장소가 분리되었거나 접근 권한이 없습니다."));
    }
    private String icon(LocalTrash.Entry e){return e.directory?"folder":FileIconType.kind(e.name);}
    private String summaryText(){
        if(busy&&items.isEmpty())return L10n.t("휴지통을 확인하는 중...");
        long size=0;boolean unknown=false;
        for(LocalTrash.Entry e:items)if(e.available&&e.size>=0){
            if(Long.MAX_VALUE-size<e.size){size=Long.MAX_VALUE;unknown=true;}else size+=e.size;
        }else unknown=true;
        return L10n.f("%d개 항목",items.size())+" · "+Formatter.formatFileSize(this,size)
                +(unknown?" + "+L10n.t("미확인 용량"):"");
    }
    private String retentionText(){
        int days=LocalTrash.retention(this);
        return days==0?L10n.t("자동 삭제 안 함"):L10n.f("%d일 후 자동 삭제",days);
    }
    private void updateSelection(){
        rendering=true;Set<String> available=availableIds();
        selected.retainAll(available);
        selectAll.setChecked(!available.isEmpty()&&selected.containsAll(available));
        selectAll.setEnabled(!busy&&!available.isEmpty());
        selectionLabel.setText(selected.isEmpty()?L10n.t("전체 선택"):L10n.f("%d개 항목 선택됨",selected.size()));
        enabled(restore,!busy&&!selected.isEmpty());enabled(erase,!busy&&!selected.isEmpty());enabled(empty,!busy&&!available.isEmpty());
        refresh.setEnabled(!busy);refresh.setAlpha(busy?.36f:1f);rendering=false;
    }
    private void renderFallback(){
        if(!alive()||fallback==null||surface!=null&&surface.isShown())return;
        summary.setText(summaryText()+"\n"+retentionText());rows.removeAllViews();
        selectBar.setVisibility(items.isEmpty()?View.GONE:View.VISIBLE);
        if(items.isEmpty()){
            TextView message=Ui.text(this,L10n.t(busy?"휴지통을 확인하는 중...":"휴지통이 비어 있습니다."),14,Ui.MUTED,false);
            message.setGravity(Gravity.CENTER);rows.addView(message,Ui.lp(this,-1,150));
        }
        for(LocalTrash.Entry e:items){
            LinearLayout row=Ui.horizontal(this);row.setPadding(dp(10),dp(10),dp(8),dp(10));
            row.setBackgroundColor(selected.contains(e.id)?Ui.PALE:Ui.SURFACE);
            row.addView(new Ui.Icon(this,icon(e),FileIconType.color(icon(e))),Ui.lp(this,42,42));
            LinearLayout labels=Ui.vertical(this);labels.setPadding(dp(10),0,dp(6),0);
            TextView name=Ui.text(this,e.name,14,Ui.TEXT,false);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(name,Ui.lp(this,-1,-2));Ui.gap(labels,this,3);
            TextView origin=Ui.text(this,origin(e),11,Ui.MUTED,false);origin.setSingleLine();origin.setEllipsize(TextUtils.TruncateAt.END);
            labels.addView(origin,Ui.lp(this,-1,-2));Ui.gap(labels,this,2);
            TextView detail=Ui.text(this,detail(e),11,e.available?Ui.MUTED:0xFFBA1A1A,false);
            detail.setMaxLines(2);detail.setEllipsize(TextUtils.TruncateAt.END);labels.addView(detail,Ui.lp(this,-1,-2));
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            CheckBox cb=new CheckBox(this);cb.setButtonTintList(ColorStateList.valueOf(Ui.TEAL));
            cb.setChecked(selected.contains(e.id));cb.setEnabled(e.available&&!busy);cb.setContentDescription(e.name);
            row.addView(cb,Ui.lp(this,44,44));
            cb.setOnCheckedChangeListener((v,checked)->{if(checked)selected.add(e.id);else selected.remove(e.id);
                row.setBackgroundColor(checked?Ui.PALE:Ui.SURFACE);updateSelection();});
            row.setOnClickListener(v->{if(cb.isEnabled())cb.setChecked(!cb.isChecked());});
            rows.addView(row,Ui.lp(this,-1,-2));line(rows);
        }
        updateSelection();
    }
    private void confirmNative(String command){
        if(busy)return;Set<String> ids="empty".equals(command)?availableIds():new LinkedHashSet<>(selected);
        if(ids.isEmpty())return;
        boolean returning="restore".equals(command);
        String title=L10n.t(returning?"복원":"empty".equals(command)?"휴지통 비우기":"완전히 삭제");
        String message=L10n.f(returning?"%d개 항목을 원래 위치로 복원할까요?":"%d개 항목을 완전히 삭제할까요? 삭제 후에는 복원할 수 없습니다.",ids.size());
        final String expected=revision;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton(L10n.t("취소"),null).setPositiveButton(title,(d,w)->{
                if(expected.equals(revision)&&!busy)begin(command,ids,null);
            }).show();
    }
    private Map<String,Object> data(String error){
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("revision",revision);data.put("loading",busy);data.put("summary",summaryText());
        data.put("retention",retentionText());data.put("error",error);
        List<Map<String,Object>> values=new ArrayList<>();
        for(LocalTrash.Entry e:items){
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("id",e.id);row.put("name",e.name);row.put("origin",origin(e));row.put("detail",detail(e));
            row.put("available",e.available);row.put("icon",icon(e));values.add(row);
        }
        data.put("rows",values);return data;
    }
    private static Map<String,String> labels(){
        Map<String,String> l=new LinkedHashMap<>();
        String[][] pairs={{"restore","복원"},{"erase","완전히 삭제"},{"empty","휴지통 비우기"},{"emptyMessage","휴지통이 비어 있습니다."},
            {"loading","휴지통을 확인하는 중..."},{"refresh","새로고침"},{"selectAll","전체 선택"},{"selectedCount","%d개 항목 선택됨"},
            {"cancel","취소"},{"ok","확인"},{"error","일부 항목 처리 실패"},
            {"restoreConfirm","%d개 항목을 원래 위치로 복원할까요?"},
            {"eraseConfirm","%d개 항목을 완전히 삭제할까요? 삭제 후에는 복원할 수 없습니다."}};
        for(String[] p:pairs)l.put(p[0],L10n.t(p[1]));return l;
    }
    @Override public Map<String,Object> snapshot(String route){
        if(!alive()||!FlutterUiContract.TRASH.equals(route))throw new IllegalStateException("closed trash");
        Map<String,Object> snapshot=FlutterOperationSnapshot.base(this,route,labels());
        snapshot.put("trash",data(""));return snapshot;
    }
    @Override public void action(String route,String action,String id){
        if(FlutterUiContract.TRASH.equals(route)&&"trashClose".equals(action)&&!mutating)finish();
    }
    @Override public void fallbackShown(){renderFallback();}
    @Override public void reorderFavorites(Object order){throw new IllegalStateException("trash");}
    @Override public void explorerCall(MethodCall call,MethodChannel.Result result){
        if(!alive()||busy||!"trashCommand".equals(call.method)||!(call.arguments instanceof Map)
                ||!revision.equals(call.argument("revision"))){
            result.error("STALE_TRASH",null,null);return;
        }
        Object cmd=call.argument("command"),raw=call.argument("ids");
        if(!(cmd instanceof String)||!(raw instanceof List)){
            result.error("INVALID_TRASH",null,null);return;
        }
        String command=(String)cmd;
        Set<String> ids=new LinkedHashSet<>(),available=availableIds();
        for(Object value:(List<?>)raw)
            if(!(value instanceof String)||!available.contains(value)||!ids.add((String)value)){
                result.error("INVALID_TRASH",null,null);return;
            }
        if("refresh".equals(command)?!ids.isEmpty():
            (!Arrays.asList("restore","erase","empty").contains(command)||ids.isEmpty()||
                ("empty".equals(command)&&!ids.equals(available)))){
            result.error("INVALID_TRASH",null,null);return;
        }
        begin(command,ids,result);
    }
    private void begin(String command,Set<String> ids,MethodChannel.Result reply){
        if(busy||!alive()){if(reply!=null)reply.error("BUSY",null,null);return;}
        mutating=!"refresh".equals(command);busy=true;renderFallback();
        final boolean changed=mutating;
        worker.execute(()->{
            LocalTrash.Outcome outcome=new LocalTrash.Outcome();List<LocalTrash.Entry> next=null;
            try{
                if(changed)outcome=LocalTrash.act(getApplicationContext(),ids,"restore".equals(command));
                else LocalTrash.cleanup(getApplicationContext());
                next=LocalTrash.list(getApplicationContext());
            }catch(Exception e){outcome.fail(e);}
            final LocalTrash.Outcome done=outcome;final List<LocalTrash.Entry> loaded=next;
            runOnUiThread(()->{
                if(!alive()){if(reply!=null)reply.error("CLOSED",null,null);return;}
                busy=false;mutating=false;
                if(loaded!=null)items=loaded;
                selected.clear();revision=UUID.randomUUID().toString();
                if(done.count>0||!changed)setResult(RESULT_OK);
                renderFallback();
                String error=done.failed>0?L10n.t("일부 항목 처리 실패")+"\n"+done.first:"";
                if(reply!=null)reply.success(data(error));
                else{
                    if(surface!=null)surface.refresh();
                    if(!error.isEmpty())new AlertDialog.Builder(this).setTitle(L10n.t("일부 항목 처리 실패"))
                        .setMessage(error).setPositiveButton(L10n.t("확인"),null).show();
                }
            });
        });
    }
    @Override protected void onResume(){
        super.onResume();
        if(surface==null&&FlutterUiMode.enabled(this))surface=new FlutterNavigationSurface(this,frame,fallback,FlutterUiContract.TRASH,this,true);
        else if(surface!=null)surface.resume();
    }
    @Override protected void onPause(){if(surface!=null)surface.pause();super.onPause();}
    @Override public void onBackPressed(){
        if(mutating)return;
        if(surface!=null&&surface.isShown()){surface.push("trashBack",null);return;}
        super.onBackPressed();
    }
    @Override protected void onDestroy(){
        if(surface!=null){surface.close();surface=null;}
        worker.shutdownNow();super.onDestroy();
    }
}
