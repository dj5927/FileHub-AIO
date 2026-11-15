package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.*;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full-screen, offline archive comic reader; never unpacks the whole book. */
public final class ComicBookActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private LinearLayout column,top,bottom;
    private PageImage picture;
    private ReaderPageTransitions pageTransitions;
    private TextView title,status,pageLabel;
    private SeekBar progress;
    private boolean seeking,chromeVisible=true,rtl,fitWidth,gray,twoPage;
    private boolean endPromptVisible;
    private int contrast=100,brightness=0;
    private int interactiveDecodeToken;
    private Drawable imageBeforeDrag;
    private int page=0,requestGeneration=0,turnStyle=EpubTurnGeometry.CURL;
    private boolean slowTurn;
    private int splitMode=ComicPageLayout.AUTO;
    private String folderSettingsScope="";
    private String fileName,bookKey,historyId;
    private File archiveFile,sessionDir;
    private boolean stagedArchive;
    private List<String> pages;
    private List<int[]> imageDimensions;
    private List<ComicPageLayout.Page> logicalPages;
    private List<Integer> spreadGroups;
    private List<BookSeriesQueue.Volume> volumeQueue;
    private SharedPreferences settings,history;

    @Override public void onCreate(Bundle state){
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        fileName=getIntent().getStringExtra(ReaderSource.NAME);
        if(!ComicArchive.supports(fileName)){finish();return;}
        bookKey=Integer.toHexString(ReaderSource.bookKey(getIntent()).hashCode());
        historyId=ReaderHistory.id(getIntent());
        settings=getSharedPreferences("comic_reader_preferences",MODE_PRIVATE);
        history=getSharedPreferences("comic_reader_progress",MODE_PRIVATE);
        folderSettingsScope=FolderMediaSettings.reader(getIntent());
        rtl=comicBool("rtl",false);
        fitWidth=comicBool("fit_width",false);
        contrast=comicInt("contrast",100);
        brightness=comicInt("brightness",0);
        gray=comicBool("grayscale",false);
        twoPage=comicBool("fold_two_pages",true);
        turnStyle=comicInt("turn_effect",
                ReaderDefaultSettings.turn(this,FolderMediaSettings.COMIC));
        slowTurn=comicBool("slow_page_turn",
                ReaderDefaultSettings.slow(this,FolderMediaSettings.COMIC));
        splitMode=comicInt("double_page_split",ComicPageLayout.AUTO);
        volumeQueue=BookSeriesQueue.decode(
                getIntent().getStringExtra(BookSeriesQueue.EXTRA));
        column=Ui.vertical(this);
        column.setBackgroundColor(0xFF0A0C0F);
        ViewCompat.setOnApplyWindowInsetsListener(column,(v,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.statusBars()|
                    WindowInsetsCompat.Type.navigationBars()|
                    WindowInsetsCompat.Type.displayCutout());
            column.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        setContentView(column);
        top=Ui.horizontal(this);
        top.setPadding(dp(7),0,dp(8),0);
        top.setBackgroundColor(0xFF101923);
        column.addView(top,Ui.lp(this,-1,48));
        TextView back=button("‹",32);
        top.addView(back,Ui.lp(this,46,-1));
        back.setOnClickListener(v->finish());
        title=Ui.text(this,fileName,15,Color.WHITE,true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        top.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        TextView bookmark=button("☆",24);
        bookmark.setContentDescription("책갈피 추가, 이동 또는 삭제");
        top.addView(bookmark,Ui.lp(this,48,-1));
        bookmark.setOnClickListener(v->{
            if(logicalPages!=null)ReaderBookmarkDialog.show(this,historyId,page,
                    true,logicalPages.size(),this::openPage);
        });
        TextView options=button("⚙",23);
        options.setContentDescription("만화책 보기 설정");
        top.addView(options,Ui.lp(this,49,-1));
        options.setOnClickListener(v->showOptions());
        FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        column.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        picture=new PageImage(this);
        stage.addView(picture,new FrameLayout.LayoutParams(-1,-1));
        pageTransitions=new ReaderPageTransitions(this,stage,picture);
        pageTransitions.configure(turnStyle,slowTurn,Color.WHITE);
        status=Ui.text(this,"만화책 여는 중...",13,0xFFBBBBBB,false);
        status.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(-1,dp(38),Gravity.CENTER);
        stage.addView(status,sp);
        bottom=Ui.vertical(this);
        bottom.setBackgroundColor(0xFF101923);
        column.addView(bottom,Ui.lp(this,-1,80));
        LinearLayout actions=Ui.horizontal(this);
        bottom.addView(actions,Ui.lp(this,-1,43));
        TextView previous=button("〈",24);
        actions.addView(previous,Ui.lp(this,63,-1));
        previous.setOnClickListener(v->move(-1));
        pageLabel=Ui.text(this,"0 / 0",14,Color.WHITE,true);
        pageLabel.setGravity(Gravity.CENTER);
        pageLabel.setContentDescription("만화책 전체 쪽수와 진행률. 터치하면 0~100% 빠른 이동");
        pageLabel.setOnClickListener(v->showQuickSeek());
        actions.addView(pageLabel,new LinearLayout.LayoutParams(0,-1,1));
        TextView next=button("〉",24);
        actions.addView(next,Ui.lp(this,63,-1));
        next.setOnClickListener(v->move(1));
        progress=new SeekBar(this);
        progress.setMax(1000);
        bottom.addView(progress,Ui.lp(this,-1,34));
        progress.setEnabled(false);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar v){seeking=true;}
            @Override public void onProgressChanged(SeekBar v,int value,boolean fromUser){}
            @Override public void onStopTrackingTouch(SeekBar v){
                seeking=false;
                pageTransitions.cancel();
                if(logicalPages!=null&&!logicalPages.isEmpty())
                    openPage(Math.round(progress.getProgress()*
                            (logicalPages.size()-1)/1000f));
            }
        });
        openBook();
    }

    private int dp(float px){return Ui.dp(this,px);}
    private int comicInt(String key,int fallback){
        return FolderMediaSettings.integer(this,FolderMediaSettings.COMIC,
                folderSettingsScope,key,settings.getInt(key,fallback));
    }
    private boolean comicBool(String key,boolean fallback){
        return FolderMediaSettings.bool(this,FolderMediaSettings.COMIC,
                folderSettingsScope,key,settings.getBoolean(key,fallback));
    }
    private void comicSaveInt(String key,int value){
        FolderMediaSettings.integerSet(this,FolderMediaSettings.COMIC,
                folderSettingsScope,key,value);
    }
    private void comicSaveBool(String key,boolean value){
        FolderMediaSettings.boolSet(this,FolderMediaSettings.COMIC,
                folderSettingsScope,key,value);
    }
    private TextView button(String value,int sp){
        TextView b=Ui.text(this,value,sp,Color.WHITE,false);
        b.setGravity(Gravity.CENTER);
        return b;
    }
    private void openBook(){
        worker.execute(()->{
            try{
                String type=getIntent().getStringExtra(ReaderSource.KIND);
                String path=getIntent().getStringExtra(ReaderSource.PATH);
                if("local".equals(type)&&path!=null){
                    archiveFile=new File(path);
                    stagedArchive=false;
                }else{
                    archiveFile=ReaderSource.stage(this,getIntent(),ReaderSource.MAX_ARCHIVE);
                    stagedArchive=true;
                }
                if(archiveFile.length()>ReaderSource.MAX_ARCHIVE)
                    throw new java.io.IOException("1.5GB를 넘는 만화책은 지원하지 않습니다.");
                List<String> indexed=ComicArchive.index(archiveFile,fileName);
                sessionDir=new File(getCacheDir(),"comic_pages_"+UUID.randomUUID());
                if(!sessionDir.mkdir())throw new java.io.IOException("페이지 캐시 생성 실패");
                List<int[]> dimensions=ComicArchive.dimensions(
                        archiveFile,fileName,indexed,sessionDir);
                main.post(()->{
                    if(isFinishing()||isDestroyed())return;
                    pages=indexed;
                    imageDimensions=dimensions;
                    logicalPages=ComicPageLayout.create(dimensions,splitMode,rtl);
                    spreadGroups=ComicPageLayout.spreadStarts(logicalPages);
                    progress.setEnabled(true);
                    int saved=ReaderHistory.position(this,historyId,-1);
                    boolean logicalHistory=getSharedPreferences(
                            "comic_logical_page_migration",MODE_PRIVATE)
                            .getBoolean(historyId,false);
                    if(!logicalHistory){
                        // v0.1.44 stored physical image indexes. They must
                        // be mapped to the logical split-page coordinates.
                        int sourceIndex=saved>=0?saved:
                                history.getInt("page_"+bookKey,0);
                        saved=ComicPageLayout.firstPageOfSource(
                                logicalPages,sourceIndex);
                    }
                    openPage(Math.max(0,Math.min(logicalPages.size()-1,saved)));
                });
            }catch(Exception error){
                main.post(()->showError("만화책을 열지 못했습니다: "+error.getMessage()));
            }
        });
    }
    private void move(int delta){
        if(logicalPages==null||pageTransitions.busy())return;
        pageTransitions.setBounds(picture.visiblePageBounds());
        int next;
        if(spreadEnabled()){
            int place=spreadGroups.indexOf(
                    ComicPageLayout.spreadStart(spreadGroups,page));
            int nextPlace=place+delta;
            if(nextPlace<0)return;
            if(nextPlace>=spreadGroups.size()){offerNextVolume();return;}
            next=spreadGroups.get(nextPlace);
        }else next=page+delta;
        if(next>=logicalPages.size()){offerNextVolume();return;}
        if(next<0)return;
        pageTransitions.begin(rtl?-delta:delta,()->openPage(next),true);
    }
    private int adjacentPage(int delta){
        if(logicalPages==null)return -1;
        if(spreadEnabled()){
            int place=spreadGroups.indexOf(
                    ComicPageLayout.spreadStart(spreadGroups,page));
            int next=place+delta;
            return next>=0&&next<spreadGroups.size()?
                    spreadGroups.get(next):-1;
        }
        int next=page+delta;
        return next>=0&&next<logicalPages.size()?next:-1;
    }
    private boolean startComicFold(int delta,float startX,float startY){
        if(pageTransitions.busy()||logicalPages==null||picture.canPan())return false;
        final int target=adjacentPage(delta);
        if(target<0||picture.getDrawable()==null)return false;
        final RectF bounds=picture.visiblePageBounds();
        if(bounds.width()<2||bounds.height()<2)return false;
        if(!bounds.contains(startX,startY))return false;
        pageTransitions.setBounds(bounds);
        imageBeforeDrag=picture.getDrawable();
        final Drawable original=imageBeforeDrag;
        final int generation=++interactiveDecodeToken;
        return pageTransitions.startFinger(rtl?-delta:delta,startX,startY,
                ()->worker.execute(()->{
                    try{
                        Bitmap next=spreadEnabled()?decodeSpread(target):
                                decodeLogicalPage(target,4096);
                        main.post(()->{
                            if(generation!=interactiveDecodeToken||
                                    !pageTransitions.previewWanted()||
                                    isFinishing()||isDestroyed()){
                                next.recycle();return;
                            }
                            picture.setImageBitmap(next);
                            picture.fit();
                            applyImageAdjustments();
                            pageTransitions.ready();
                        });
                    }catch(Exception ignored){
                        main.post(()->{
                            if(generation==interactiveDecodeToken)
                                pageTransitions.cancel();
                        });
                    }
                }),
                ()->{
                    ++interactiveDecodeToken;
                    picture.setImageDrawable(original);
                    picture.fit();
                    applyImageAdjustments();
                    imageBeforeDrag=null;
                },
                ()->{
                    imageBeforeDrag=null;
                    savePageState(target);
                });
    }
    private void openPage(int index){
        if(logicalPages==null||index<0||index>=logicalPages.size())return;
        if(spreadEnabled())
            index=ComicPageLayout.spreadStart(spreadGroups,index);
        savePageState(index);
        if(pageTransitions!=null&&!pageTransitions.busy())
            pageTransitions.setBounds(picture.visiblePageBounds());
        final int indexToLoad=index;
        final int generation=++requestGeneration;
        final boolean spread=spreadEnabled();
        if(pageTransitions.busy())status.setVisibility(View.GONE);
        else{
            status.setText("페이지 불러오는 중...");
            status.setVisibility(View.VISIBLE);
        }
        worker.execute(()->{
            try{
                Bitmap bitmap=spread?decodeSpread(indexToLoad):
                        decodeLogicalPage(indexToLoad,4096);
                main.post(()->{
                    if(generation!=requestGeneration||isFinishing()||isDestroyed()){
                        bitmap.recycle();return;
                    }
                    picture.setImageBitmap(bitmap);
                    picture.fit();
                    applyImageAdjustments();
                    status.setVisibility(View.GONE);
                    pageTransitions.ready();
                });
                File[] older=sessionDir.listFiles();
                if(older!=null