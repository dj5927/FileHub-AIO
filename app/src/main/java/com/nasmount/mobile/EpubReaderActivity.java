package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in offline EPUB 2/3 viewer. The chapter XHTML is rendered without
 * JavaScript, local-file access, browser network access or external intents.
 */
public final class EpubReaderActivity extends Activity {
    private static final String HOST="reader.nasmount.invalid";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private String folderSettingsScope="";
    private LinearLayout column,toolbar,bottom;
    private BookWebView web;
    private EpubPageTurnOverlay pageTurnOverlay;
    private TextView label,status;
    private EpubArchive archive;
    private EpubProgressMap progressMap;
    private File stagedFile;
    private boolean removeStaged;
    private int chapterIndex,restoreFraction,mode,fontSize,theme,lineSpacing,sideMargin;
    private int pageEffect;
    private int pendingTurnDirection;
    private boolean slowPageTurn,turnBusy,pendingChapterTurn;
    private boolean fingerCurl,fingerReleased,fingerCommit,fingerReady;
    private boolean fingerCrossChapter,fingerReturning;
    private float fingerStartX,fingerStartY,fingerLastX,fingerLastY;
    private int fingerDirection,fingerOldScrollX,fingerTargetScrollX,
            fingerGeneration,fingerOldChapter,fingerOldFraction;
    private boolean loadingChapter,visibleBars=true,readerErrorVisible;
    private int chapterLayoutGeneration;
    private long lastProgressWrite;
    private String historyId;
    private final int[] paper={0xFFF8F1E4,Color.WHITE,0xFFDFD2B2,0xFF161A1C};
    private final int[] ink={0xFF29251F,0xFF202328,0xFF3C2F20,0xFFE5E9E8};

    @Override public void onCreate(Bundle bundle){
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(bundle);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        String name=getIntent().getStringExtra(ReaderSource.NAME);
        if(name==null||!name.toLowerCase(Locale.ROOT).endsWith(".epub")){
            finish();return;
        }
        historyId=ReaderHistory.id(getIntent());
        prefs=getSharedPreferences("epub_book_settings",MODE_PRIVATE);
        folderSettingsScope=FolderMediaSettings.reader(getIntent());
        mode=epubInt("mode",0);
        fontSize=Math.max(12,Math.min(36,epubInt("font_size",19)));
        theme=Math.max(0,Math.min(3,epubInt("theme",0)));
        lineSpacing=Math.max(110,Math.min(230,epubInt("line_spacing",155)));
        sideMargin=Math.max(8,Math.min(50,epubInt("side_margin",18)));
        pageEffect=epubInt("page_turn_effect",
                ReaderDefaultSettings.turn(this,FolderMediaSettings.EPUB));
        slowPageTurn=epubBool("slow_page_turn",
                ReaderDefaultSettings.slow(this,FolderMediaSettings.EPUB));
        int remembered=ReaderHistory.position(this,historyId,0);
        chapterIndex=Math.max(0,remembered/10000);
        restoreFraction=Math.max(0,remembered%10000);
        column=Ui.vertical(this);
        column.setBackgroundColor(paper[theme]);
        ViewCompat.setOnApplyWindowInsetsListener(column,(view,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.statusBars()|
                    WindowInsetsCompat.Type.navigationBars()|
                    WindowInsetsCompat.Type.displayCutout());
            column.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        setContentView(column);
        toolbar=Ui.horizontal(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(0xFF101923);
        column.addView(toolbar,Ui.lp(this,-1,50));
        TextView close=button("‹",29);
        toolbar.addView(close,Ui.lp(this,46,-1));
        close.setOnClickListener(v->finish());
        label=button(name,14);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        toolbar.addView(label,new LinearLayout.LayoutParams(0,-1,1));
        TextView star=button("☆",23);
        toolbar.addView(star,Ui.lp(this,46,-1));
        star.setOnClickListener(v->{
            if(archive!=null)ReaderBookmarkDialog.show(this,historyId,
                    currentPosition(),false,archive.chapters.size()*10000,
                    this::jumpTo,point->progressMap==null?
                            ReaderHistory.percentFromUnits(point,
                                    archive.chapters.size()*10000):
                            progressMap.percentAt(point));
        });
        TextView configure=button("Aa",18);
        toolbar.addView(configure,Ui.lp(this,50,-1));
        configure.setOnClickListener(v->settingsDialog());
        FrameLayout stage=new FrameLayout(this);
        column.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        web=new BookWebView();
        WebSettings webSettings=web.getSettings();
        webSettings.setJavaScriptEnabled(false);
        webSettings.setAllowContentAccess(false);
        webSettings.setAllowFileAccess(false);
        webSettings.setDomStorageEnabled(false);
        webSettings.setSupportZoom(false);
        webSettings.setBuiltInZoomControls(false);
        webSettings.setMediaPlaybackRequiresUserGesture(true);
        webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setVerticalScrollBarEnabled(true);
        web.setHorizontalScrollBarEnabled(false);
        web.setBackgroundColor(paper[theme]);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if(!"https".equals(uri.getScheme()) ||
                        !HOST.equals(uri.getHost()) || archive==null)
                    return blocked();
                try{
                    String path=uri.getPath();
                    if(path==null||path.length()<2)return blocked();
                    path=path.substring(1);
                    byte[] bytes=archive.resource(path);
                    return new WebResourceResponse(EpubArchive.mime(path),
                            path.endsWith(".css")?"UTF-8":null,
                            new ByteArrayInputStream(bytes));
                }catch(Exception denied){return blocked();}
            }
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view,WebResourceRequest request){
                Uri uri=request.getUrl();
                if("https".equals(uri.getScheme()) &&
                        HOST.equals(uri.getHost()) && archive!=null){
                    String target=uri.getPath();
                    if(target!=null){
                        for(int i=0;i<archive.chapters.size();i++){
                            if(("/"+archive.chapters.get(i).path).equals(target)){
                                boolean changed=i!=chapterIndex;
                                if(changed)openChapter(i,0);
                                return changed;
                            }
                        }
                    }
                    return false; // same-chapter anchor navigation
                }
                return true; // never open an untrusted external link
            }
            @Override public void onPageFinished(WebView view,String url){
                if(isFinishing()||isDestroyed())return;
                final int generation=chapterLayoutGeneration;
                // onPageFinished is earlier than the first committed visual
                // multicolumn layout. A zero scroll range at this moment
                // previously made long chapters look like a single page.
                web.postVisualStateCallback(generation,
                        new WebView.VisualStateCallback(){
                            @Override public void onComplete(long requestId){
                                web.postDelayed(()->finishChapterLayout(
                                        url,generation),60);
                            }
                        });
            }
        });
        stage.addView(web,new FrameLayout.LayoutParams(-1,-1));
        pageTurnOverlay=new EpubPageTurnOverlay(this);
        stage.addView(pageTurnOverlay,new FrameLayout.LayoutParams(-1,-1));
        status=Ui.text(this,"",14,ink[theme],false);
        status.setGravity(Gravity.CENTER);
        applyReaderStatus();
        stage.addView(status,new FrameLayout.LayoutParams(-1,Ui.dp(this,65),Gravity.CENTER));
        bottom=Ui.horizontal(this);
        bottom.setBackgroundColor(0xFF101923);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        column.addView(bottom,Ui.lp(this,-1,53));
        TextView previous=button("〈",22);
        bottom.addView(previous,Ui.lp(this,64,-1));
        previous.setOnClickListener(v->turn(-1));
        TextView pageInfo=button("0 / 0",13);
        bottom.addView(pageInfo,new LinearLayout.LayoutParams(0,-1,1));
        pageInfo.setContentDescription("현재 챕터, 현재 페이지와 본문 전체 진행률. 터치해서 0부터 100퍼센트까지 빠른 이동");
        pageInfo.setOnClickListener(v->showQuickSeek());
        TextView next=button("〉",22);
        bottom.addView(next,Ui.lp(this,64,-1));
        next.setOnClickListener(v->turn(1));
        bottom.setTag(pageInfo);
        web.setContentDescription("EPUB 본문: 왼쪽 터치 이전 페이지, 오른쪽 터치 다음 페이지, 가운데 터치 메뉴 숨김 또는 표시");
        web.setOnTouchListener(new View.OnTouchListener(){
            float startX,startY;
            long downTime;
            boolean multiTouch;
            @Override public boolean onTouch(View v,MotionEvent event){
                switch(event.getActionMasked()){
                    case MotionEvent.ACTION_DOWN:
                        startX=event.getX();
                        startY=event.getY();
                        downTime=event.getEventTime();
                        multiTouch=false;
                        // Intercept paged horizontal swipes before WebView
                        // scrolls; the outgoing page must remain capturable.
                        return mode==0;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        multiTouch=true;
                        if(fingerCurl)cancelPageTurn();
                        return mode==0;
                    case MotionEvent.ACTION_CANCEL:
                        multiTouch=true;
                        if(fingerCurl)cancelPageTurn();
                        return mode==0;
                    case MotionEvent.ACTION_MOVE:
                        if(fingerCurl){
                            updateEpubFinger(event.getX(),event.getY());
                            return true;
                        }
                        if(mode==0&&pageEffect==EpubTurnGeometry.CURL&&
                                !multiTouch&&ReaderDragPhysics.shouldStart(
                                    event.getX()-startX,event.getY()-startY,
                                    Ui.dp(EpubReaderActivity.this,13))){
                            int visualDirection=event.getX()<startX?1:-1;
                            if(beginEpubFinger(visualDirection,startX,startY)){
                                updateEpubFinger(event.getX(),event.getY());
                                return true;
                            }
                        }
                        return mode==0;
                    case MotionEvent.ACTION_UP:
                        if(fingerCurl){
                            updateEpubFinger(event.getX(),event.getY());
                            boolean quick=event.getEventTime()-downTime<230&&
                                    Math.abs(event.getX()-startX)>
                                            Ui.dp(EpubReaderActivity.this,75);
                            releaseEpubFinger(quick);
                            return true;
                        }
                        int action=EpubTouchZones.classify(
                                startX,startY,event.getX(),event.getY(),
                                v.getWidth(),event.getEventTime()-downTime,
                                Math.max(ViewConfiguration.get(
                                        EpubReaderActivity.this).getScaledTouchSlop(),
                                        Ui.dp(EpubReaderActivity.this,13)),
                                Ui.dp(EpubReaderActivity.this,65),
                                mode==0,multiTouch);
                        if(action==EpubTouchZones.PREVIOUS){
                            turn(-1);
                            v.performClick();
                            return true;
                        }
                        if(action==EpubTouchZones.NEXT){
                            turn(1);
                            v.performClick();
                            return true;
                        }
                        if(action==EpubTouchZones.TOGGLE_BARS){
                            toggleReaderBars();
                            v.performClick();
                            return true;
                        }
                        if(action==EpubTouchZones.SWIPE_NEXT ||
                                action==EpubTouchZones.SWIPE_PREVIOUS){
                            // Preserve the existing swipe navigation.
                            turn(action==EpubTouchZones.SWIPE_NEXT?1:-1);
                            return true;
                        }
                        return mode==0;
                    default:
                        return mode==0;
                }
            }
        });
        worker.execute(()->{
            try{
                String kind=getIntent().getStringExtra(ReaderSource.KIND);
                String path=getIntent().getStringExtra(ReaderSource.PATH);
                if("local".equals(kind)&&path!=null)stagedFile=new File(path);
                else {
                    stagedFile=ReaderSource.stage(
                            this,getIntent(),Long.MAX_VALUE);
                    removeStaged=true;
                }
                EpubArchive parsed=new EpubArchive(stagedFile);
                EpubProgressMap readingProgress=new EpubProgressMap(
                        parsed.readingWeights());
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()){
                        try{parsed.close();}catch(Exception ignored){}
                        return;
                    }
                    archive=parsed;
                    progressMap=readingProgress;
                    label.setText(parsed.title);
                    openChapter(Math.min(chapterIndex,
                            parsed.chapters.size()-1),restoreFraction);
                });
            }catch(Exception error