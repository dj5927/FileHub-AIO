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
public final class ComicBookActivity extends LocalizedActivity {
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
        setContentView(EditionMonetization.content(this,column));
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
        bookmark.setContentDescription(L10n.t("책갈피 추가, 이동 또는 삭제"));
        top.addView(bookmark,Ui.lp(this,48,-1));
        bookmark.setOnClickListener(v->{
            if(logicalPages!=null)ReaderBookmarkDialog.show(this,historyId,page,
                    true,logicalPages.size(),this::openPage);
        });
        TextView options=button("⚙",23);
        options.setContentDescription(L10n.t("만화책 보기 설정"));
        top.addView(options,Ui.lp(this,49,-1));
        options.setOnClickListener(v->showOptions());
        FrameLayout stage=new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        column.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        picture=new PageImage(this);
        stage.addView(picture,new FrameLayout.LayoutParams(-1,-1));
        pageTransitions=new ReaderPageTransitions(this,stage,picture);
        pageTransitions.configure(turnStyle,slowTurn,Color.WHITE);
        status=Ui.text(this,L10n.t("만화책 여는 중..."),13,0xFFBBBBBB,false);
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
        pageLabel.setContentDescription(L10n.t("만화책 전체 쪽수와 진행률. 터치하면 0~100% 빠른 이동"));
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
                seekComic(progress.getProgress());
            }
        });
        installViewerChrome(bottom,()->{
            boolean ready=logicalPages!=null&&!logicalPages.isEmpty()&&!pageTransitions.busy()&&progress.isEnabled();
            return ViewerUiPolicy.state("readerSlider",pageLabel.getText().toString(),
                    0xFF101923,Color.WHITE,progress.getProgress(),false,
                    ready?"previous":null,ready?"next":null,ready?"quick":null,ready?"seek":null);
        },()->chromeVisible,(action,value)->{
            if("previous".equals(action))move(-1);
            else if("next".equals(action))move(1);
            else if("quick".equals(action))showQuickSeek();
            else if("seek".equals(action))seekComic(value);
        });
        openBook();
    }

    private void seekComic(int value){
        pageTransitions.cancel();
        if(logicalPages!=null&&!logicalPages.isEmpty())
            openPage(Math.round(value*(logicalPages.size()-1)/1000f));
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
                main.post(()->showError(L10n.t("만화책을 열지 못했습니다: ")+error.getMessage()));
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
            status.setText(L10n.t("페이지 불러오는 중..."));
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
                if(older!=null)for(File candidate:older){
                    int indexInCache;
                    try{indexInCache=Integer.parseInt(
                            candidate.getName().replace(".page",""));}
                    catch(NumberFormatException bad){continue;}
                    if(Math.abs(indexInCache-
                            logicalPages.get(indexToLoad).sourceIndex)>3)
                        candidate.delete();
                }
            }catch(Exception error){
                main.post(()->{
                    if(generation==requestGeneration)
                        showError(L10n.t("페이지를 표시하지 못했습니다: ")+error.getMessage());
                });
            }
        });
    }
    private void savePageState(int index){
        page=index;
        getSharedPreferences("comic_logical_page_migration",MODE_PRIVATE)
                .edit().putBoolean(historyId,true).apply();
        history.edit().putInt("page_"+bookKey,
                logicalPages.get(page).sourceIndex).apply();
        int done=lastDisplayedPage();
        ReaderHistory.save(this,historyId,page,done,logicalPages.size());
        updatePageLabel();
        if(!seeking)progress.setProgress(logicalPages.size()<=1?0:
                Math.round(page*1000f/(logicalPages.size()-1)));
    }
    private boolean spreadEnabled(){
        return ReaderSpreadPolicy.comic(
                getResources().getConfiguration().screenWidthDp,twoPage,fitWidth);
    }
    private void updatePageLabel(){
        if(logicalPages==null)return;
        int done=lastDisplayedPage();
        pageLabel.setText((page+1)+(spreadEnabled()&&done>page+1?
                "–"+done:"")+" / "+logicalPages.size()+
                " · "+ReaderHistory.percentFromUnits(done,logicalPages.size())+
                "%"+(rtl?"  ·  일본식":"  ·  한국식"));
    }
    private void showQuickSeek(){
        if(logicalPages==null||logicalPages.isEmpty()||pageTransitions.busy()){
            Toast.makeText(this,L10n.t("만화책 페이지를 준비한 뒤 이동할 수 있습니다."),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final int count=logicalPages.size();
        ReaderQuickSeek.show(this,"만화책",
                ReaderQuickSeek.fromPage(page,count),
                position->{
                    int destination=ReaderQuickSeek.toPage(position,count);
                    int visual=spreadEnabled()?
                            ComicPageLayout.spreadStart(spreadGroups,destination):
                            destination;
                    return (visual+1)+" / "+count+"쪽";
                },position->{
                    pageTransitions.cancel();
                    openPage(ReaderQuickSeek.toPage(position,count));
                });
    }
    private int lastDisplayedPage(){
        return spreadEnabled()?ComicPageLayout.spreadEnd(
                spreadGroups,page,logicalPages.size()):page+1;
    }
    private Bitmap decodeSingle(int number,int targetEdge)throws Exception{
        File image=new File(sessionDir,number+".page");
        if(!image.isFile())ComicArchive.extractPage(
                archiveFile,fileName,pages.get(number),image);
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(image.getAbsolutePath(),bounds);
        if(bounds.outWidth<=0||bounds.outHeight<=0)
            throw new java.io.IOException("이미지 형식이 올바르지 않습니다.");
        BitmapFactory.Options options=new BitmapFactory.Options();
        int larger=Math.max(bounds.outWidth,bounds.outHeight);
        options.inSampleSize=1;
        while(larger/options.inSampleSize>targetEdge)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeFile(image.getAbsolutePath(),options);
        if(bitmap==null)throw new java.io.IOException("페이지 이미지 읽기 실패");
        return bitmap;
    }
    private Bitmap decodeLogicalPage(int logicalIndex,int edge)throws Exception{
        ComicPageLayout.Page logical=logicalPages.get(logicalIndex);
        Bitmap whole=decodeSingle(logical.sourceIndex,edge);
        if(logical.slice==ComicPageLayout.WHOLE)return whole;
        int middle=whole.getWidth()/2;
        if(middle<1)return whole;
        int x=logical.slice==ComicPageLayout.LEFT?0:middle;
        int width=logical.slice==ComicPageLayout.LEFT?
                middle:whole.getWidth()-middle;
        Bitmap half=Bitmap.createBitmap(whole,x,0,width,whole.getHeight());
        if(half!=whole)whole.recycle();
        return half;
    }
    private Bitmap decodeSpread(int first)throws Exception{
        int end=ComicPageLayout.spreadEnd(
                spreadGroups,first,logicalPages.size());
        if(end==first+1)return decodeLogicalPage(first,4096);
        // Two halves originating from a single landscape scan must be
        // displayed as the original complete spread on unfolded devices.
        if(ComicPageLayout.twoHalvesTogether(logicalPages,first))
            return decodeSingle(logicalPages.get(first).sourceIndex,4096);
        Bitmap a=decodeLogicalPage(first,2048);
        Bitmap b=null;
        try{
            b=decodeLogicalPage(first+1,2048);
            int width=a.getWidth()+b.getWidth();
            int height=Math.max(a.getHeight(),b.getHeight());
            Bitmap output=Bitmap.createBitmap(width,height,Bitmap.Config.RGB_565);
            Canvas canvas=new Canvas(output);
            canvas.drawColor(Color.WHITE);
            Bitmap left=rtl?b:a,right=rtl?a:b;
            canvas.drawBitmap(left,0,(height-left.getHeight())/2f,null);
            canvas.drawBitmap(right,left.getWidth(),
                    (height-right.getHeight())/2f,null);
            return output;
        }finally{
            a.recycle();
            if(b!=null)b.recycle();
        }
    }
    private void offerNextVolume(){
        if(endPromptVisible || logicalPages==null)return;
        EditionMonetization.contentBreak(this,this::showNextVolumePrompt);
    }
    private void showNextVolumePrompt(){
        if(isFinishing()||isDestroyed()||endPromptVisible||logicalPages==null)return;
        String currentLocation=getIntent().getStringExtra(ReaderSource.URI);
        if(currentLocation==null)currentLocation=
                getIntent().getStringExtra(ReaderSource.PATH);
        BookSeriesQueue.Volume following=BookSeriesQueue.next(
                volumeQueue,fileName,currentLocation);
        if(following==null){
            Toast.makeText(this,L10n.t("마지막 페이지입니다."),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        endPromptVisible=true;
        new AlertDialog.Builder(this).setTitle(L10n.t("다음 만화책 이어 보기"))
                .setMessage(L10n.f("현재 권을 끝까지 읽었습니다.\n\n%1$s\n\n다음 권을 바로 열까요?",following.name))
                .setNegativeButton(L10n.t("아니요"),(dialog,which)->endPromptVisible=false)
                .setPositiveButton(L10n.t("다음 권 보기"),(dialog,which)->{
                    android.content.Intent next=BookSeriesQueue.nextIntent(
                            getIntent(),following);
                    next.setClass(this,ComicBookActivity.class);
                    startActivity(next);
                    finish();
                }).setOnCancelListener(dialog->endPromptVisible=false).show();
    }
    private void applyImageAdjustments(){
        ColorMatrix matrix=new ColorMatrix();
        if(gray)matrix.setSaturation(0f);
        float factor=Math.max(0.5f,Math.min(2f,contrast/100f));
        float offset=128f*(1-factor)+brightness;
        ColorMatrix adjust=new ColorMatrix(new float[]{
                factor,0,0,0,offset,
                0,factor,0,0,offset,
                0,0,factor,0,offset,
                0,0,0,1,0
        });
        matrix.postConcat(adjust);
        picture.setColorFilter(new ColorMatrixColorFilter(matrix));
    }
    private void showOptions(){
        LinearLayout form=Ui.vertical(this);
        form.setPadding(dp(20),dp(5),dp(20),0);
        TextView direction=Ui.text(this,L10n.t("읽는 방향"),14,Ui.TEXT,true);
        form.addView(direction,Ui.lp(this,-1,28));
        RadioGroup read=new RadioGroup(this);
        RadioButton korean=new RadioButton(this);
        korean.setText(L10n.t("한국식 · 왼쪽에서 오른쪽으로 넘기기"));
        RadioButton japanese=new RadioButton(this);
        japanese.setText(L10n.t("일본식 · 오른쪽에서 왼쪽으로 넘기기"));
        korean.setId(View.generateViewId());japanese.setId(View.generateViewId());
        read.addView(korean);read.addView(japanese);
        read.check(rtl?japanese.getId():korean.getId());
        form.addView(read);
        SeekBar contrastBar=ReaderNumericSlider.add(this,form,
                "명암 대비 · 50~200%",50,200,contrast,
                ReaderNumericSlider::percent);
        SeekBar brightBar=ReaderNumericSlider.add(this,form,
                "밝기 · -100~+100",-100,100,brightness,
                ReaderNumericSlider::brightness);
        CheckBox grayscale=new CheckBox(this);
        grayscale.setText(L10n.t("흑백 모드"));grayscale.setChecked(gray);
        form.addView(grayscale);
        CheckBox width=new CheckBox(this);
        width.setText(L10n.t("가로 폭 맞춤 (기본: 페이지 전체 맞춤)"));
        width.setChecked(fitWidth);
        form.addView(width);
        CheckBox spreadMode=new CheckBox(this);
        spreadMode.setText(L10n.t("폴드 펼침 화면에서 두 페이지 펼쳐 보기"));
        spreadMode.setChecked(twoPage);
        form.addView(spreadMode);
        form.addView(Ui.text(this,L10n.t("좌우 합본 이미지 자동 분할"),14,Ui.TEXT,true));
        Spinner splitting=new Spinner(this);
        splitting.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                L10n.labels(new String[]{"자동 감지 (권장)","분할하지 않음","민감하게 감지"})));
        splitting.setSelection(Math.max(0,Math.min(2,splitMode)));
        form.addView(splitting,Ui.lp(this,-1,48));
        form.addView(Ui.text(this,L10n.t("책 넘김 효과"),14,Ui.TEXT,true));
        Spinner turn=new Spinner(this);
        turn.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                L10n.labels(ReaderTurnPreferences.STYLES)));
        turn.setSelection(ReaderTurnPreferences.toIndex(turnStyle));
        form.addView(turn,Ui.lp(this,-1,48));
        form.addView(Ui.text(this,L10n.t("페이지 넘김 속도"),14,Ui.TEXT,true));
        Spinner speed=new Spinner(this);
        speed.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                L10n.labels(ReaderTurnPreferences.SPEEDS)));
        speed.setSelection(slowTurn?1:0);
        form.addView(speed,Ui.lp(this,-1,48));
        ScrollView optionsScroll=new ScrollView(this);
        optionsScroll.addView(form);
        new AlertDialog.Builder(this).setTitle(L10n.t("만화책 보기 설정"))
                .setView(optionsScroll).setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("적용"),(dialog,which)->{
                    pageTransitions.cancel();
                    boolean hadSpread=spreadEnabled();
                    boolean previousRtl=rtl;
                    int previousSplit=splitMode;
                    int oldContrast=contrast,oldBrightness=brightness;
                    boolean oldGray=gray,oldFit=fitWidth,oldTwo=twoPage;
                    int oldTurn=turnStyle;
                    boolean oldSlow=slowTurn;
                    int sourcePage=logicalPages==null?0:
                            logicalPages.get(page).sourceIndex;
                    rtl=read.getCheckedRadioButtonId()==japanese.getId();
                    contrast=contrastBar.getProgress()+50;
                    brightness=brightBar.getProgress()-100;
                    gray=grayscale.isChecked();fitWidth=width.isChecked();
                    twoPage=spreadMode.isChecked();
                    splitMode=splitting.getSelectedItemPosition();
                    turnStyle=ReaderTurnPreferences.fromIndex(
                            turn.getSelectedItemPosition());
                    slowTurn=speed.getSelectedItemPosition()==1;
                    // An in-book change belongs to the book's DIRECTORY,
                    // not to the global default for other comics or readers.
                    pageTransitions.configure(turnStyle,slowTurn,Color.WHITE);
                    if(previousRtl!=rtl)comicSaveBool("rtl",rtl);
                    if(oldContrast!=contrast)comicSaveInt("contrast",contrast);
                    if(oldBrightness!=brightness)comicSaveInt("brightness",brightness);
                    if(oldGray!=gray)comicSaveBool("grayscale",gray);
                    if(oldFit!=fitWidth)comicSaveBool("fit_width",fitWidth);
                    if(oldTwo!=twoPage)comicSaveBool("fold_two_pages",twoPage);
                    if(previousSplit!=splitMode)
                        comicSaveInt("double_page_split",splitMode);
                    if(oldTurn!=turnStyle)comicSaveInt("turn_effect",turnStyle);
                    if(oldSlow!=slowTurn)comicSaveBool("slow_page_turn",slowTurn);
                    picture.fit();applyImageAdjustments();
                    if(pages!=null && (previousSplit!=splitMode ||
                            previousRtl!=rtl)){
                        logicalPages=ComicPageLayout.create(
                                imageDimensions,splitMode,rtl);
                        spreadGroups=ComicPageLayout.spreadStarts(logicalPages);
                        openPage(ComicPageLayout.firstPageOfSource(
                                logicalPages,sourcePage));
                    }else if(logicalPages!=null && hadSpread!=spreadEnabled()){
                        openPage(page);
                    }
                    updatePageLabel();
                }).setNeutralButton(L10n.t("폴더 설정 초기화"),(dialog,which)->{
                    FolderMediaSettings.clear(this,FolderMediaSettings.COMIC,
                            folderSettingsScope,"rtl","contrast","brightness",
                            "grayscale","fit_width","fold_two_pages",
                            "double_page_split","turn_effect","slow_page_turn");
                    recreate();
                }).show();
    }
    private void showError(String message){
        ++interactiveDecodeToken;
        pageTransitions.cancel();
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }
    private void toggleChrome(){
        chromeVisible=!chromeVisible;
        top.setVisibility(chromeVisible?View.VISIBLE:View.GONE);
        bottom.setVisibility(chromeVisible?View.VISIBLE:View.GONE);
    }
    @Override public void onConfigurationChanged(Configuration config){
        super.onConfigurationChanged(config);
        ++interactiveDecodeToken;
        pageTransitions.cancel();
        // Reuse the already staged archive and decoded-page cache on Fold
        // changes; reopening the Activity here would re-download a large NAS
        // archive. The image view will remeasure and re-fit automatically.
        if(logicalPages!=null&&!logicalPages.isEmpty())
            picture.post(()->{
                if(!isFinishing()&&!isDestroyed())openPage(page);
            });
    }
    @Override protected void onResume(){
        super.onResume();
        turnStyle=comicInt("turn_effect",
                ReaderDefaultSettings.turn(this,FolderMediaSettings.COMIC));
        slowTurn=comicBool("slow_page_turn",
                ReaderDefaultSettings.slow(this,FolderMediaSettings.COMIC));
        if(pageTransitions!=null)
            pageTransitions.configure(turnStyle,slowTurn,Color.WHITE);
    }
    @Override protected void onPause(){
        ++interactiveDecodeToken;
        if(pageTransitions!=null)pageTransitions.cancel();
        super.onPause();
    }
    @Override protected void onDestroy(){
        ++interactiveDecodeToken;
        pageTransitions.cancel();
        requestGeneration++;
        worker.shutdownNow();
        if(stagedArchive&&archiveFile!=null)archiveFile.delete();
        if(sessionDir!=null){
            File[] list=sessionDir.listFiles();
            if(list!=null)for(File f:list)f.delete();
            sessionDir.delete();
        }
        super.onDestroy();
    }

    private final class PageImage extends ImageView {
        private final Matrix matrix=new Matrix();
        private final ScaleGestureDetector scaler;
        private final GestureDetector detector;
        private float zoom=1,offsetX,offsetY,lastX,lastY,downX,downY;
        private boolean multi;
        private boolean canPan() {
            Drawable d=getDrawable();
            return zoom>1.04f || (fitWidth && d!=null && getWidth()>0 &&
                    d.getIntrinsicWidth()>0 &&
                    (float)getWidth()*d.getIntrinsicHeight()/d.getIntrinsicWidth()>
                            getHeight());
        }
        RectF visiblePageBounds(){
            Drawable drawable=getDrawable();
            if(drawable==null)return new RectF();
            RectF bounds=new RectF(0,0,drawable.getIntrinsicWidth(),
                    drawable.getIntrinsicHeight());
            Matrix actual=new Matrix(getImageMatrix());
            actual.mapRect(bounds);
            if(!bounds.intersect(0,0,getWidth(),getHeight()))
                return new RectF();
            return bounds;
        }
        PageImage(Activity activity){
            super(activity);
            setBackgroundColor(Color.BLACK);
            setScaleType(ScaleType.MATRIX);
            scaler=new ScaleGestureDetector(activity,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                        @Override public boolean onScale(ScaleGestureDetector event){
                            zoom=Math.min(6f,Math.max(1f,zoom*event.getScaleFactor()));
                            updateMatrix();return true;
                        }
                    });
            detector=new GestureDetector(activity,
                    new GestureDetector.SimpleOnGestureListener(){
                        @Override public boolean onDown(MotionEvent e){return true;}
                        @Override public boolean onDoubleTap(MotionEvent e){
                            zoom=zoom>1.1f?1f:2.4f;
                            offsetX=offsetY=0;
                            updateMatrix();return true;
                        }
                        @Override public boolean onSingleTapConfirmed(MotionEvent e){
                            // One tap anywhere shows/hides both bars.
                            // Swipes (not taps) change pages.
                            toggleChrome();
                            return true;
                        }
                    });
        }
        void fit(){zoom=1;offsetX=offsetY=0;post(this::updateMatrix);}
        @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){
            super.onSizeChanged(w,h,oldW,oldH);
            post(this::updateMatrix);
        }
        private void updateMatrix(){
            Drawable d=getDrawable();
            if(d==null||getWidth()<1||getHeight()<1)return;
            float iw=Math.max(1,d.getIntrinsicWidth()),ih=Math.max(1,d.getIntrinsicHeight());
            float base=fitWidth?getWidth()/iw:
                    Math.min(getWidth()/iw,getHeight()/ih);
            float scale=base*zoom,w=iw*scale,h=ih*scale;
            float boundX=Math.max(0,(w-getWidth())/2f);
            float boundY=Math.max(0,(h-getHeight())/2f);
            offsetX=Math.max(-boundX,Math.min(boundX,offsetX));
            offsetY=Math.max(-boundY,Math.min(boundY,offsetY));
            matrix.reset();matrix.setScale(scale,scale);
            matrix.postTranslate((getWidth()-w)/2+offsetX,(getHeight()-h)/2+offsetY);
            setImageMatrix(matrix);
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            scaler.onTouchEvent(e);
            detector.onTouchEvent(e);
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    downX=lastX=e.getX();downY=lastY=e.getY();multi=false;return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multi=true;
                    if(pageTransitions.fingerActive())pageTransitions.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(e.getPointerCount()>1)multi=true;
                    if(!multi&&pageTransitions.fingerActive()){
                        pageTransitions.updateFinger(e.getX(),e.getY());
                        lastX=e.getX();lastY=e.getY();
                        return true;
                    }
                    if(!multi&&!scaler.isInProgress()&&!canPan()&&
                            turnStyle==EpubTurnGeometry.CURL&&
                            ReaderDragPhysics.shouldStart(
                                    e.getX()-downX,e.getY()-downY,dp(13))){
                        float dx=e.getX()-downX;
                        int visualDirection=dx<0?1:-1;
                        int delta=rtl?-visualDirection:visualDirection;
                        if(startComicFold(delta,downX,downY)){
                            pageTransitions.updateFinger(e.getX(),e.getY());
                            lastX=e.getX();lastY=e.getY();
                            return true;
                        }
                    }
                    if(e.getPointerCount()==1&&!scaler.isInProgress()&&canPan()){
                        offsetX+=e.getX()-lastX;offsetY+=e.getY()-lastY;
                        updateMatrix();
                    }
                    lastX=e.getX();lastY=e.getY();return true;
                case MotionEvent.ACTION_UP:
                    if(pageTransitions.fingerActive()){
                        pageTransitions.updateFinger(e.getX(),e.getY());
                        boolean quick=e.getEventTime()-e.getDownTime()<230 &&
                                Math.abs(e.getX()-downX)>dp(75);
                        pageTransitions.releaseFinger(quick);
                        return true;
                    }
                    if(!multi&&zoom<=1.04f){
                        float dx=e.getX()-downX,dy=e.getY()-downY;
                        if(Math.abs(dx)>dp(65)&&Math.abs(dx)>Math.abs(dy)*1.4f)
                            move(dx<0?(rtl?-1:1):(rtl?1:-1));
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if(pageTransitions.fingerActive()){
                        pageTransitions.updateFinger(downX,downY);
                        pageTransitions.releaseFinger(false);
                    }
                    return true;
            }
            return true;
        }
    }
}
