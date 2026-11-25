package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Canvas;
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
import android.widget.CheckBox;
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
    /** PDF uses its own renderer, but the same EPUB reading screen. */
    private boolean pdfMode;
    private PdfEpubPageSource pdfSource;
    private volatile byte[] pdfPageImage;
    private volatile byte[] pdfRightPageImage;
    private int pdfPageIndex,pdfPageCount,pdfZoomPercent=100;
    private boolean pdfTwoPageFold=true;
    private TextView pdfZoomPrevZone,pdfZoomNextZone;
    private SharedPreferences pdfPrefs;
    private File stagedFile;
    private boolean removeStaged;
    private int chapterIndex,restoreFraction,mode,fontSize,theme,lineSpacing,sideMargin;
    private boolean restoreChapterEnd;
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
    private int barReflowGeneration;
    private int previousRevealGeneration;
    private int settlingChapterGeneration=-1;
    private int finishedChapterGeneration=-1;
    private long settlingChapterSince;
    private EpubLayoutSettlePolicy settlingChapterPolicy;
    private final EpubEndPositionGuard endPositionGuard=
            new EpubEndPositionGuard();
    private boolean pinUpdatePosted;
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
        pdfMode=PdfBookTypes.supports(name);
        if(name==null||(!pdfMode&&
                !name.toLowerCase(Locale.ROOT).endsWith(".epub"))){
            finish();return;
        }
        historyId=ReaderHistory.id(getIntent());
        prefs=getSharedPreferences("epub_book_settings",MODE_PRIVATE);
        pdfPrefs=getSharedPreferences("pdf_book_reader_preferences",MODE_PRIVATE);
        folderSettingsScope=FolderMediaSettings.reader(getIntent());
        mode=pdfMode?0:epubInt("mode",0);
        fontSize=Math.max(12,Math.min(36,epubInt("font_size",19)));
        theme=Math.max(0,Math.min(3,epubInt("theme",0)));
        lineSpacing=Math.max(110,Math.min(230,epubInt("line_spacing",155)));
        sideMargin=Math.max(8,Math.min(50,epubInt("side_margin",18)));
        pageEffect=epubInt("page_turn_effect",
                ReaderDefaultSettings.turn(this,FolderMediaSettings.EPUB));
        slowPageTurn=epubBool("slow_page_turn",
                ReaderDefaultSettings.slow(this,FolderMediaSettings.EPUB));
        if(pdfMode){
            pageEffect=pdfPrefs.getInt("turn_effect",pageEffect);
            slowPageTurn=pdfPrefs.getBoolean("slow_turn",slowPageTurn);
            pdfZoomPercent=Math.max(75,Math.min(250,
                    pdfPrefs.getInt("zoom",100)));
            pdfTwoPageFold=pdfPrefs.getBoolean("two_page_fold",true);
        }
        int remembered=ReaderHistory.position(this,historyId,0);
        if(pdfMode)pdfPageIndex=Math.max(0,remembered);
        else{
            chapterIndex=Math.max(0,remembered/10000);
            restoreFraction=Math.max(0,remembered%10000);
        }
        column=Ui.vertical(this);
        column.setBackgroundColor(paper[theme]);
        ViewCompat.setOnApplyWindowInsetsListener(column,(view,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.statusBars()|
                    WindowInsetsCompat.Type.navigationBars()|
                    WindowInsetsCompat.Type.displayCutout());
            column.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        setContentView(EditionMonetization.content(this,column));
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
            if(pdfMode){
                if(pdfPageCount>0)ReaderBookmarkDialog.show(this,historyId,
                        pdfPageIndex,true,pdfPageCount,this::jumpToPdf);
                return;
            }
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
        if(pdfMode)webSettings.setCacheMode(WebSettings.LOAD_NO_CACHE);
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
                if(pdfMode){
                    if("https".equals(uri.getScheme())&&HOST.equals(uri.getHost())&&
                            "/pdf-current-page.png".equals(uri.getPath())&&
                            pdfPageImage!=null)
                        return new WebResourceResponse("image/png",null,
                                new ByteArrayInputStream(pdfPageImage));
                    if("https".equals(uri.getScheme())&&HOST.equals(uri.getHost())&&
                            "/pdf-right-page.png".equals(uri.getPath())&&
                            pdfRightPageImage!=null)
                        return new WebResourceResponse("image/png",null,
                                new ByteArrayInputStream(pdfRightPageImage));
                    return blocked();
                }
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
                if(pdfMode)return true;
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
                if(pdfMode){
                    web.postVisualStateCallback(generation,
                            new WebView.VisualStateCallback(){
                                @Override public void onComplete(long id){
                                    web.postDelayed(()->finishPdfLayout(
                                            url,generation),45);
                                }
                            });
                    return;
                }
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
        setupPdfZoomEdgeNavigation(stage);
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
        web.setContentDescription((pdfMode?"PDF":"EPUB")+
                " 본문: 왼쪽 터치 이전 페이지, 오른쪽 터치 다음 페이지, 가운데 터치 메뉴 표시·숨김");
        web.setOnTouchListener(new View.OnTouchListener(){
            float startX,startY;
            long downTime;
            boolean multiTouch;
            @Override public boolean onTouch(View v,MotionEvent event){
                // At enlarged PDF zoom, allow normal WebView panning.
                // Page-turn buttons and the seek dialog remain available.
                if(pdfMode&&pdfZoomPercent>100)return false;
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
                        if(!pdfMode&&mode==0&&pageEffect==EpubTurnGeometry.CURL&&
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
        if(pdfMode){
            openPdfDocument();
            return;
        }
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
            }catch(Exception error){
                runOnUiThread(()->showReaderError(
                        "EPUB을 열지 못했습니다: "+error.getMessage()));
            }
        });
    }
    private boolean pdfSpread(){
        return pdfMode&&PdfSpreadPolicy.active(pdfTwoPageFold,
                getResources().getConfiguration().screenWidthDp);
    }
    /** PDF uses PdfRenderer as the source but all EPUB reader chrome/controls. */
    private void openPdfDocument(){
        loadingChapter=true;
        status.setText("PDF 책을 여는 중...");
        applyReaderStatus();
        worker.execute(()->{
            try{
                PdfEpubPageSource opened=PdfEpubPageSource.open(this,getIntent());
                if(isFinishing()||isDestroyed()){
                    opened.close();
                    return;
                }
                pdfSource=opened;
                int total=opened.pageCount();
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed())return;
                    pdfPageCount=total;
                    pdfPageIndex=PdfPageSizing.page(pdfPageIndex,total);
                    loadPdfPage(pdfPageIndex);
                });
            }catch(Exception error){
                runOnUiThread(()->showReaderError(
                        "PDF 책을 열지 못했습니다: "+error.getMessage()));
            }
        });
    }

    private void loadPdfPage(int destination){
        if(pdfPageCount<1||pdfSource==null)return;
        final boolean spread=pdfSpread();
        final int selected=PdfSpreadPolicy.start(
                destination,pdfPageCount,spread);
        final int layout=++chapterLayoutGeneration;
        final int zoom=pdfZoomPercent;
        final int viewport=Math.max(280,
                getResources().getDisplayMetrics().widthPixels/
                        (spread?2:1));
        pdfPageIndex=selected;
        loadingChapter=true;
        readerErrorVisible=false;
        status.setText("");
        applyReaderStatus();
        worker.execute(()->{
            try {
                byte[] data=pdfSource.renderPng(selected,viewport,zoom);
                byte[] right=spread&&selected+1<pdfPageCount?
                        pdfSource.renderPng(selected+1,viewport,zoom):null;
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()||
                            layout!=chapterLayoutGeneration)return;
                    pdfPageImage=data;
                    pdfRightPageImage=right;
                    String url="https://"+HOST+"/pdf/page/"+layout+".html";
                    String html=(spread?
                            PdfEpubPageSource.spreadHtml(zoom,right!=null):
                            PdfEpubPageSource.pageHtml(zoom))
                            .replace("pdf-current-page.png",
                                    "pdf-current-page.png?rev="+layout)
                            .replace("pdf-right-page.png",
                                    "pdf-right-page.png?rev="+layout);
                    web.setBackgroundColor(0xFF171B20);
                    web.loadDataWithBaseURL(url,html,"text/html","UTF-8",url);
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(layout!=chapterLayoutGeneration||isFinishing()||
                            isDestroyed())return;
                    loadingChapter=false;
                    showReaderError("PDF 페이지를 표시하지 못했습니다: "+
                            error.getMessage());
                });
            }
        });
    }

    private void finishPdfLayout(String url,int generation){
        if(!pdfMode||isFinishing()||isDestroyed()||
                generation!=chapterLayoutGeneration||pdfPageCount<1)return;
        if(url==null||!url.equals("https://"+HOST+
                "/pdf/page/"+generation+".html"))return;
        loadingChapter=false;
        status.setText("");
        applyReaderStatus();
        recordProgress();
        if(pendingChapterTurn){
            pendingChapterTurn=false;
            web.postDelayed(this::completeTurn,50);
        }
    }

    private void jumpToPdf(int selected){
        if(!pdfMode||pdfPageCount<1)return;
        cancelPageTurn();
        loadPdfPage(selected);
    }

    private void turnPdf(int direction){
        if(pdfPageCount<1||pdfSource==null||loadingChapter||turnBusy)return;
        int target=PdfSpreadPolicy.next(pdfPageIndex,direction,
                pdfPageCount,pdfSpread());
        if(target<0)return;
        if(target>=pdfPageCount){
            new AlertDialog.Builder(this)
                    .setTitle(L10n.t("책을 모두 읽었습니다."))
                    .setPositiveButton(L10n.t("확인"),null).show();
            return;
        }
        animatePageTurn(direction,()->loadPdfPage(target),true);
    }

    private void showPdfQuickSeek(){
        if(pdfPageCount<1)return;
        if(loadingChapter||turnBusy){
            android.widget.Toast.makeText(this,
                    "페이지를 불러온 뒤 이동해 주세요.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout sheet=Ui.vertical(this);
        sheet.setPadding(Ui.dp(this,20),Ui.dp(this,12),
                Ui.dp(this,20),Ui.dp(this,8));
        TextView preview=Ui.text(this,"",17,Ui.TEXT,true);
        preview.setGravity(Gravity.CENTER);
        sheet.addView(preview,Ui.lp(this,-1,48));
        SeekBar seek=new SeekBar(this);
        seek.setMax(Math.max(0,pdfPageCount-1));
        seek.setProgress(pdfPageIndex);
        sheet.addView(seek,Ui.lp(this,-1,60));
        final java.util.function.IntConsumer update=value->preview.setText(
                (value+1)+" / "+pdfPageCount+"쪽 · "+
                        Math.round(100f*(value+1)/pdfPageCount)+"%");
        update.accept(pdfPageIndex);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar s,int value,
                                                     boolean fromUser){
                update.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar s){}
            @Override public void onStopTrackingTouch(SeekBar s){}
        });
        new AlertDialog.Builder(this).setTitle("PDF 책 전체 이동")
                .setView(sheet)
                .setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("이동"),(dialog,which)->
                        jumpToPdf(seek.getProgress())).show();
    }

    private void pdfSettingsDialog(){
        LinearLayout controls=Ui.vertical(this);
        controls.setPadding(Ui.dp(this,18),Ui.dp(this,8),
                Ui.dp(this,18),0);
        controls.addView(Ui.text(this,
                "PDF는 원본 페이지를 유지하며 EPUB과 같은 독서 화면으로 표시합니다.",
                13,Ui.MUTED,false));
        controls.addView(Ui.text(this,L10n.t("책 넘김 효과"),14,Ui.TEXT,true));
        Spinner effect=new Spinner(this);
        effect.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                ReaderTurnPreferences.STYLES));
        effect.setSelection(ReaderTurnPreferences.toIndex(pageEffect));
        controls.addView(effect,Ui.lp(this,-1,48));
        controls.addView(Ui.text(this,L10n.t("페이지 넘김 속도"),14,Ui.TEXT,true));
        Spinner speed=new Spinner(this);
        speed.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                ReaderTurnPreferences.SPEEDS));
        speed.setSelection(slowPageTurn?1:0);
        controls.addView(speed,Ui.lp(this,-1,48));
        SeekBar zoom=ReaderNumericSlider.add(this,controls,
                "페이지 확대 · 75~250%",75,250,pdfZoomPercent,
                ReaderNumericSlider::percent);
        CheckBox foldSpread=new CheckBox(this);
        foldSpread.setText("폴드 펼침 화면에서 두 페이지 나란히 보기");
        foldSpread.setChecked(pdfTwoPageFold);
        controls.addView(foldSpread,Ui.lp(this,-1,50));
        ScrollView scroll=new ScrollView(this);
        scroll.addView(controls);
        new AlertDialog.Builder(this).setTitle("PDF 독서 설정")
                .setView(scroll).setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("적용"),(dialog,which)->{
                    pageEffect=ReaderTurnPreferences.fromIndex(
                            effect.getSelectedItemPosition());
                    slowPageTurn=speed.getSelectedItemPosition()==1;
                    int nextZoom=75+zoom.getProgress();
                    boolean zoomChanged=pdfZoomPercent!=nextZoom;
                    boolean spreadChanged=pdfTwoPageFold!=foldSpread.isChecked();
                    pdfZoomPercent=nextZoom;
                    pdfTwoPageFold=foldSpread.isChecked();
                    pdfPrefs.edit().putInt("turn_effect",pageEffect)
                            .putBoolean("slow_turn",slowPageTurn)
                            .putInt("zoom",pdfZoomPercent)
                            .putBoolean("two_page_fold",pdfTwoPageFold).apply();
                    updatePdfZoomEdgeNavigation();
                    if(zoomChanged||spreadChanged)jumpToPdf(pdfPageIndex);
                }).show();
    }

    private void showReaderError(String message){
        if(isFinishing()||isDestroyed())return;
        readerErrorVisible=true;
        status.setText(message);
        cancelPageTurn();
        applyReaderStatus();
    }
    private void applyReaderStatus(){
        if(pdfMode){
            status.setVisibility(pdfPageCount==0||loadingChapter||readerErrorVisible?
                    View.VISIBLE:View.GONE);
            return;
        }
        // The decision is independent of page/chapter loading. This explicit
        // policy has regression tests so the old loading flash cannot return.
        status.setVisibility(EpubReaderStatus.showCenterMessage(
                archive==null,loadingChapter,readerErrorVisible)?
                View.VISIBLE:View.GONE);
    }
    private WebResourceResponse blocked(){
        return new WebResourceResponse("text/plain","UTF-8",
                new ByteArrayInputStream(new byte[0]));
    }
    private TextView button(String text,int size){
        TextView view=Ui.text(this,text,size,Color.WHITE,false);
        view.setGravity(Gravity.CENTER);
        return view;
    }
    /** PDF page-turn strips are pinned to the viewport, never zoom with HTML. */
    private void setupPdfZoomEdgeNavigation(FrameLayout stage){
        if(!pdfMode)return;
        pdfZoomPrevZone=pdfZoomEdgeButton("〈","이전 PDF 페이지");
        pdfZoomNextZone=pdfZoomEdgeButton("〉","다음 PDF 페이지");
        int width=Ui.dp(this,PdfZoomTouchPolicy.EDGE_WIDTH_DP);
        stage.addView(pdfZoomPrevZone,new FrameLayout.LayoutParams(
                width,-1,Gravity.START));
        stage.addView(pdfZoomNextZone,new FrameLayout.LayoutParams(
                width,-1,Gravity.END));
        pdfZoomPrevZone.setOnClickListener(v->turnPdf(-1));
        pdfZoomNextZone.setOnClickListener(v->turnPdf(1));
        updatePdfZoomEdgeNavigation();
    }
    private TextView pdfZoomEdgeButton(String arrow,String description){
        TextView zone=Ui.text(this,arrow,22,0xFFB9CAD2,true);
        zone.setGravity(Gravity.CENTER);
        zone.setAlpha(.82f);
        zone.setContentDescription(description);
        zone.setClickable(true);
        return zone;
    }
    private void updatePdfZoomEdgeNavigation(){
        if(!pdfMode||pdfZoomPrevZone==null||pdfZoomNextZone==null)return;
        int visibility=PdfZoomTouchPolicy.showFixedEdges(
                pdfMode,pdfZoomPercent)?View.VISIBLE:View.GONE;
        pdfZoomPrevZone.setVisibility(visibility);
        pdfZoomNextZone.setVisibility(visibility);
    }
    private void toggleReaderBars(){
        if(pdfMode){
            visibleBars=!visibleBars;
            toolbar.setVisibility(visibleBars?View.VISIBLE:View.GONE);
            bottom.setVisibility(visibleBars?View.VISIBLE:View.GONE);
            return;
        }
        // Removing chrome changes the available WebView height. The XHTML
        // multi-columns repaginate; never scroll to an arbitrary fraction
        // in the middle of a column, or adjacent pages appear together.
        final int chapter=chapterIndex;
        final int fraction=currentPosition()%10000;
        final int generation=++barReflowGeneration;
        visibleBars=!visibleBars;
        toolbar.setVisibility(visibleBars?View.VISIBLE:View.GONE);
        bottom.setVisibility(visibleBars?View.VISIBLE:View.GONE);
        if(archive!=null&&!loadingChapter){
            web.postDelayed(()->{
                if(isFinishing()||isDestroyed()||archive==null||
                        chapterIndex!=chapter||loadingChapter||
                        generation!=barReflowGeneration)return;
                web.postVisualStateCallback(generation,
                        new WebView.VisualStateCallback(){
                            @Override public void onComplete(long requestId){
                                web.postDelayed(()->{
                                    if(isFinishing()||isDestroyed()||
                                            archive==null||
                                            chapterIndex!=chapter||
                                            loadingChapter||
                                            generation!=barReflowGeneration)return;
                                    if(mode==0)
                                        web.scrollTo(
                                                endPositionGuard.activeFor(chapterIndex)?
                                                        web.maximumX():
                                                        EpubChromeReflow.horizontalSnap(
                                                                fraction,web.maximumX(),
                                                                web.getWidth()),0);
                                    else web.scrollTo(0,
                                            EpubChromeReflow.verticalRestore(
                                                    fraction,web.maximumY()));
                                    recordProgress();
                                },55);
                            }
                        });
            },75);
        }
    }
    private boolean wide(){
        return getResources().getConfiguration().screenWidthDp>=600;
    }
    private String css(){
        String paperHex=String.format(Locale.ROOT,"#%06x",paper[theme]&0xffffff);
        String inkHex=String.format(Locale.ROOT,"#%06x",ink[theme]&0xffffff);
        String pageMode=mode==0?
                EpubPaginationPolicy.css(wide(),sideMargin):
                "html,body{overflow-y:auto;}body{max-width:820px;margin:auto;}";
        return "<meta name='viewport' content='width=device-width, initial-scale=1.0'>"+
                "<meta http-equiv='Content-Security-Policy' content=\""+
                "default-src 'none'; script-src 'none'; connect-src 'none';"+
                " img-src 'self' data:; style-src 'self' 'unsafe-inline';"+
                " font-src 'self' data:; frame-src 'none'; form-action 'none'\">"+
                "<style>html,body{background:"+paperHex+" !important;"+
                "color:"+inkHex+" !important;}body{box-sizing:border-box;"+
                "font-size:"+fontSize+"px !important;"+
                "line-height:"+(lineSpacing/100f)+" !important;"+
                "padding:10px "+(mode==0?0:sideMargin)+"px !important;"+
                "overflow-wrap:break-word;}"+
                "p,li,div{line-height:inherit;}"+
                "img,svg{max-width:100%;height:auto;}"+pageMode+"</style>";
    }
    private String htmlWithSettings(byte[] bytes){
        String content=new String(bytes,StandardCharsets.UTF_8);
        content=content.replaceFirst("(?is)<script\\b[^>]*>.*?</script>","");
        content=content.replaceAll("(?is)<iframe\\b[^>]*>.*?</iframe>","");
        content=content.replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*(['\"]).*?\\1","");
        return EpubPaginationPolicy.injectAfterBookStyles(content,css());
    }
    private void openChapter(int number,int fraction){
        if(archive==null||number<0||number>=archive.chapters.size())return;
        ++chapterLayoutGeneration;
        ++barReflowGeneration;
        settlingChapterGeneration=-1;
        settlingChapterPolicy=null;
        finishedChapterGeneration=-1;
        endPositionGuard.clear();
        chapterIndex=number;
        restoreChapterEnd=fraction<0;
        restoreFraction=Math.max(0,Math.min(9999,fraction));
        loadingChapter=true;
        // No loading overlay or "body rendering" message is ever displayed,
        // including the first page and each cross-chapter page turn.
        // Keep the current WebView visible while the next chapter loads.
        readerErrorVisible=false;
        status.setText("");
        applyReaderStatus();
        worker.execute(()->{
            try{
                String chapter=archive.chapters.get(number).path;
                String html=htmlWithSettings(archive.chapter(number));
                String base="https://"+HOST+"/"+Uri.encode(chapter,"/");
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()||chapterIndex!=number)return;
                    web.setBackgroundColor(paper[theme]);
                    web.loadDataWithBaseURL(base,html,"text/html","UTF-8",base);
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    loadingChapter=false;
                    showReaderError("EPUB 본문 오류: "+error.getMessage());
                });
            }
        });
    }
    private void finishChapterLayout(String url,int generation){
        if(isFinishing()||isDestroyed()||generation!=chapterLayoutGeneration||
                archive==null||chapterIndex<0||
                chapterIndex>=archive.chapters.size())return;
        Uri loaded=Uri.parse(url);
        if(!HOST.equals(loaded.getHost())||
                !("/"+archive.chapters.get(chapterIndex).path)
                        .equals(loaded.getPath()))return;
        if(settlingChapterGeneration==generation||
                finishedChapterGeneration==generation)return;
        // Font-face resources and large paragraphs can change page counts
        // AFTER onPageFinished and the first visual-state callback.
        settlingChapterGeneration=generation;
        settlingChapterSince=SystemClock.uptimeMillis();
        settlingChapterPolicy=EpubLayoutSettlePolicy.create();
        pollChapterLayout(generation);
    }
    private void pollChapterLayout(int generation){
        if(isFinishing()||isDestroyed()||generation!=chapterLayoutGeneration||
                settlingChapterGeneration!=generation||archive==null)return;
        int extent=mode==0?web.maximumX():web.maximumY();
        int viewport=mode==0?web.getWidth():web.getHeight();
        boolean atEnd=restoreChapterEnd;
        if(atEnd&&viewport>0){
            if(mode==0)web.scrollTo(extent,0);
            else web.scrollTo(0,extent);
        }
        long elapsed=SystemClock.uptimeMillis()-settlingChapterSince;
        if(!settlingChapterPolicy.observe(extent,viewport,elapsed,atEnd)){
            web.postDelayed(()->pollChapterLayout(generation),
                    EpubLayoutSettlePolicy.POLL_MS);
            return;
        }
        // Check once more after WebView has actually committed the new
        // frame, not merely after its scroll range looked unchanged.
        final int acceptedExtent=extent,acceptedViewport=viewport;
        web.postVisualStateCallback(generation,new WebView.VisualStateCallback(){
            @Override public void onComplete(long requestId){
                web.postDelayed(()->{
                    if(isFinishing()||isDestroyed()||
                            generation!=chapterLayoutGeneration||
                            settlingChapterGeneration!=generation)return;
                    int currentExtent=mode==0?web.maximumX():web.maximumY();
                    int currentViewport=mode==0?web.getWidth():web.getHeight();
                    if(currentExtent!=acceptedExtent||
                            currentViewport!=acceptedViewport){
                        pollChapterLayout(generation);
                        return;
                    }
                    finishStableChapterLayout();
                },50);
            }
        });
    }
    private void finishStableChapterLayout(){
        boolean pinEnd=restoreChapterEnd&&mode==0;
        finishedChapterGeneration=chapterLayoutGeneration;
        settlingChapterGeneration=-1;
        settlingChapterPolicy=null;
        loadingChapter=false;
        restorePosition();
        if(pinEnd){
            // Keep this destination pinned even if a late font layout
            // invalidation happens after we have released the page animation.
            endPositionGuard.pin(chapterIndex,web.getWidth(),web.maximumX());
        }
        applyReaderStatus();
        recordProgress(); // Rewrite the old chapter-count-based cached %.
        if(pendingChapterTurn){
            pendingChapterTurn=false;
            web.postDelayed(this::completeTurn,70);
        }
        if(fingerCurl&&fingerCrossChapter){
            if(!fingerReturning&&fingerDirection<0)
                capturePreviousSheetForFinger();
            else{
                fingerReady=true;
                if(fingerReleased)settleEpubFinger();
            }
        }
    }
    private void restorePosition(){
        if(archive==null)return;
        float fraction=restoreFraction/10000f;
        if(mode==0){
            int max=web.maximumX();
            int target=restoreChapterEnd?
                    EpubPaginationPolicy.lastPageStart(web.getWidth(),max):
                    EpubPaginationPolicy.restoreNearest(
                            Math.round(max*fraction),web.getWidth(),max);
            web.scrollTo(target,0);
        }else{
            int max=web.maximumY();
            web.scrollTo(0,restoreChapterEnd?max:Math.round(max*fraction));
        }
        restoreChapterEnd=false;
        updateLabel();
    }
    private int currentPosition(){
        if(archive==null)return 0;
        int max=mode==0?web.maximumX():web.maximumY();
        int at=mode==0?web.getScrollX():web.getScrollY();
        int fraction=max<=0?0:Math.max(0,Math.min(9999,
                Math.round(9999f*at/max)));
        return chapterIndex*10000+fraction;
    }
    private void recordProgress(){
        if(pdfMode){
            if(pdfPageCount>0&&!loadingChapter){
                ReaderHistory.save(this,historyId,pdfPageIndex,
                        PdfSpreadPolicy.lastVisible(pdfPageIndex,
                                pdfPageCount,pdfSpread()),pdfPageCount);
                updateLabel();
            }
            return;
        }
        if(archive==null||loadingChapter)return;
        int location=currentPosition();
        int estimatedPermille=progressMap==null?
                ReaderHistory.percentFromUnits(location,
                        archive.chapters.size()*10000)*10:
                progressMap.permilleAt(location);
        ReaderHistory.save(this,historyId,location,
                estimatedPermille,1000);
        updateLabel();
    }
    private void updateLabel(){
        if(pdfMode){
            if(pdfPageCount==0)return;
            TextView info=(TextView)bottom.getTag();
            int percent=Math.round(100f*
                    PdfSpreadPolicy.lastVisible(pdfPageIndex,
                            pdfPageCount,pdfSpread())/pdfPageCount);
            info.setText(PdfSpreadPolicy.pageLabel(pdfPageIndex,
                    pdfPageCount,pdfSpread())+" · "+percent+"%");
            return;
        }
        if(archive==null)return;
        TextView info=(TextView)bottom.getTag();
        int location=currentPosition();
        int percent=progressMap==null?
                ReaderHistory.percentFromUnits(location,
                        archive.chapters.size()*10000):
                progressMap.percentAt(location);
        String chapterPage="";
        if(mode==0&&web.getWidth()>0){
            int currentPage=1+EpubPaginationPolicy.pageIndex(
                    web.getScrollX(),web.getWidth());
            int totalPages=EpubPaginationPolicy.pageCount(
                    web.getWidth(),web.maximumX());
            chapterPage=" · "+Math.min(totalPages,currentPage)+"/"+
                    Math.max(1,totalPages)+"쪽";
        }
        info.setText((chapterIndex+1)+"/"+archive.chapters.size()+
                "장"+chapterPage+" · 약 "+percent+"%");
    }
    private void showQuickSeek(){
        if(pdfMode){
            showPdfQuickSeek();
            return;
        }
        if(archive==null||progressMap==null)return;
        if(loadingChapter||turnBusy){
            android.widget.Toast.makeText(this,"현재 페이지를 불러온 뒤 이동할 수 있습니다.",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        LinearLayout sheet=Ui.vertical(this);
        sheet.setPadding(Ui.dp(this,20),Ui.dp(this,12),
                Ui.dp(this,20),Ui.dp(this,8));
        TextView preview=Ui.text(this,"",19,Ui.TEXT,true);
        preview.setGravity(Gravity.CENTER);
        sheet.addView(preview,Ui.lp(this,-1,52));
        SeekBar seek=new SeekBar(this);
        seek.setMax(1000);
        int initial=progressMap.permilleAt(currentPosition());
        seek.setProgress(initial);
        sheet.addView(seek,Ui.lp(this,-1,62));
        TextView guidance=Ui.text(this,
                "슬라이더를 좌우로 움직이면 책 전체 0~100% 위치를 선택합니다."+
                        "\n'이동'을 누르면 선택한 위치에서 독서를 이어갑니다.",
                12,Ui.MUTED,false);
        guidance.setGravity(Gravity.CENTER);
        sheet.addView(guidance,Ui.lp(this,-1,-2));
        final java.util.function.IntConsumer update=p->{
            int target=progressMap.positionAt(p);
            preview.setText(String.format(Locale.KOREA,
                    "전체 약 %.1f%%  ·  %d / %d장",
                    p/10f,target/10000+1,archive.chapters.size()));
        };
        update.accept(initial);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(
                    SeekBar bar,int value,boolean fromUser){
                update.accept(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar){}
            @Override public void onStopTrackingTouch(SeekBar bar){}
        });
        new AlertDialog.Builder(this)
                .setTitle("EPUB 전체 빠른 이동")
                .setView(sheet)
                .setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("이동"),(dialog,which)->{
                    if(archive==null||progressMap==null)return;
                    int destination=progressMap.positionAt(seek.getProgress());
                    cancelPageTurn();
                    jumpTo(destination);
                }).show();
    }
    /** Follow the finger only when another page exists in the same XHTML
     * chapter. Cross-chapter navigation retains its safe async transition. */
    private boolean beginEpubFinger(int direction,float x,float y){
        if(turnBusy||loadingChapter||mode!=0||
                pageEffect!=EpubTurnGeometry.CURL)return false;
        int old=web.getScrollX();
        int target=EpubPaginationPolicy.target(old,direction,
                web.getWidth(),web.maximumX());
        boolean cross=old==target;
        if(cross&&(archive==null||chapterIndex+direction<0||
                chapterIndex+direction>=archive.chapters.size()))return false;
        endPositionGuard.clear(); // User action wins over late-layout pin.
        fingerCurl=true;turnBusy=true;
        fingerReleased=false;fingerCommit=false;fingerReady=false;
        fingerCrossChapter=cross;fingerReturning=false;
        fingerDirection=direction;
        fingerStartX=fingerLastX=x;
        fingerStartY=fingerLastY=y;
        fingerOldChapter=chapterIndex;
        fingerOldFraction=currentPosition()%10000;
        fingerOldScrollX=old;fingerTargetScrollX=target;
        final int generation=++fingerGeneration;
        EpubPageCapture.capture(this,web,bitmap->{
            if(generation!=fingerGeneration||!fingerCurl||
                    isFinishing()||isDestroyed()){
                if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();
                return;
            }
            if(bitmap!=null){
                pageTurnOverlay.hold(bitmap,direction,pageEffect,paper[theme]);
                pageTurnOverlay.setGrabHeight(
                        PageCurlPhysics.clamp(fingerStartY/
                                Math.max(1f,web.getHeight())));
                updateEpubFinger(fingerLastX,fingerLastY);
            }
            if(fingerCrossChapter){
                openChapter(fingerOldChapter+direction,direction<0?-1:0);
            }else{
                web.scrollTo(fingerTargetScrollX,0);
                if(fingerDirection<0)capturePreviousSheetForFinger();
                else{
                    fingerReady=true;
                    if(fingerReleased)settleEpubFinger();
                }
            }
        });
        return true;
    }
    private void capturePreviousSheetForFinger(){
        final int generation=fingerGeneration;
        // PixelCopy requests copy the entire composited window rectangle,
        // including the page-turn overlay covering the WebView. Previously
        // "incoming" was therefore often another copy of the OLD text,
        // which made the page jump after the curl completed.
        // Reveal the real previously-rendered WebView underneath instead.
        afterVisiblePreviousPage(()->{
            if(generation!=fingerGeneration||!fingerCurl||
                    isFinishing()||isDestroyed())return;
            fingerReady=true;
            if(fingerReleased)settleEpubFinger();
        });
    }
    /** Ensure the new WebView frame is ready before uncovering the old sheet. */
    private void afterVisiblePreviousPage(Runnable action){
        final int chapterGeneration=chapterLayoutGeneration;
        final int revealGeneration=++previousRevealGeneration;
        final boolean[] started={false};
        Runnable deliver=()->{
            if(started[0]||isFinishing()||isDestroyed()||
                    chapterGeneration!=chapterLayoutGeneration||
                    revealGeneration!=previousRevealGeneration)return;
            started[0]=true;
            action.run();
        };
        web.postVisualStateCallback(chapterGeneration,
                new WebView.VisualStateCallback(){
                    @Override public void onComplete(long id){
                        web.post(deliver);
                    }
                });
        // Some vendor WebViews don't complete visual callbacks after a
        // hardware frame is already available. Never freeze a turn forever.
        web.postDelayed(deliver,220);
    }
    private void updateEpubFinger(float x,float y){
        if(!fingerCurl||fingerReleased)return;
        fingerLastX=x;fingerLastY=y;
        float p=ReaderDragPhysics.progress(
                fingerStartX,x,Math.max(1,web.getWidth()),fingerDirection);
        float tilt=ReaderDragPhysics.diagonal(
                fingerStartY,y,Math.max(1,web.getHeight()));
        pageTurnOverlay.followFinger(p,tilt);
    }
    private void releaseEpubFinger(boolean fastFlick){
        if(!fingerCurl||fingerReleased)return;
        fingerReleased=true;
        float p=ReaderDragPhysics.progress(
                fingerStartX,fingerLastX,Math.max(1,web.getWidth()),
                fingerDirection);
        fingerCommit=ReaderDragPhysics.shouldCommit(p)||
                (fastFlick&&p>.16f);
        if(fingerReady)settleEpubFinger();
        else if(!fingerCommit){
            if(fingerCrossChapter&&pageTurnOverlay.holding())
                settleEpubFinger();
            else cancelPageTurn();
        }
    }
    private void settleEpubFinger(){
        if(!fingerCurl)return;
        if(fingerCrossChapter&&!fingerCommit&&!fingerReturning){
            fingerReturning=true;fingerReady=false;
            openChapter(fingerOldChapter,fingerOldFraction);
            return;
        }
        if(!fingerReady)return;
        final boolean commit=fingerCommit;
        if(!commit&&!fingerCrossChapter)web.scrollTo(fingerOldScrollX,0);
        pageTurnOverlay.settle(commit,slowPageTurn,()->{
            fingerCurl=false;fingerReleased=false;
            fingerCrossChapter=false;fingerReturning=false;
            turnBusy=false;
            if(!isFinishing()&&!isDestroyed())recordProgress();
        });
    }
    private void animatePageTurn(int direction,Runnable advance,
                                 boolean loadsNewChapter){
        if(mode!=0||pageEffect==EpubTurnGeometry.NONE ||
                web.getWidth()<=0||web.getHeight()<=0){
            advance.run();
            return;
        }
        if(turnBusy)return;
        turnBusy=true;
        EpubPageCapture.capture(this,web,screenshot->{
            if(isFinishing()||isDestroyed()){
                if(screenshot!=null)screenshot.recycle();
                turnBusy=false;
                return;
            }
            if(screenshot==null){
                turnBusy=false;
                advance.run();
                return;
            }
            pageTurnOverlay.hold(screenshot,direction,pageEffect,
                    pdfMode?Color.WHITE:paper[theme]);
            pageTurnOverlay.setGrabHeight(.5f);
            pendingTurnDirection=direction;
            pendingChapterTurn=loadsNewChapter;
            advance.run();
            if(!loadsNewChapter){
                web.postDelayed(this::completeTurn,60);
            }
        });
    }
    /** Previous-page animation unfolds the incoming page from the LEFT.
     * The snapshot of the actual previous XHTML page is acquired after
     * WebView has completed its paint, not before scrolling. */
    private void completeTurn(){
        if(pendingTurnDirection<0&&pageEffect==EpubTurnGeometry.CURL&&!pdfMode){
            // Old sheet is in the overlay, NEW page already lives behind it.
            // Do not PixelCopy that rectangle: it captures the overlay, not
            // the underlying WebView, causing a sudden text replacement.
            afterVisiblePreviousPage(()->{
                if(turnBusy&&pageTurnOverlay.holding())playPageTurn();
            });
            return;
        }
        playPageTurn();
    }
    private void playPageTurn(){
        if(isFinishing()||isDestroyed()){
            cancelPageTurn();
            return;
        }
        if(!pageTurnOverlay.holding()){
            turnBusy=false;
            return;
        }
        pageTurnOverlay.animateTurn(slowPageTurn,()->{
            turnBusy=false;
            if(!isFinishing()&&!isDestroyed())recordProgress();
        });
    }
    private void cancelPageTurn(){
        ++fingerGeneration;
        ++previousRevealGeneration;
        if(fingerCurl&&web!=null){
            if(fingerCrossChapter&&chapterIndex!=fingerOldChapter&&
                    archive!=null&&!isFinishing()&&!isDestroyed())
                openChapter(fingerOldChapter,fingerOldFraction);
            else if(!fingerCrossChapter&&!fingerCommit)
                web.scrollTo(fingerOldScrollX,0);
        }
        fingerCurl=false;fingerReleased=false;fingerReady=false;
        fingerCrossChapter=false;fingerReturning=false;
        pendingChapterTurn=false;
        turnBusy=false;
        if(pageTurnOverlay!=null)pageTurnOverlay.clear();
    }
    private void turn(int direction){
        if(pdfMode){
            turnPdf(direction);
            return;
        }
        if(archive==null||loadingChapter||turnBusy)return;
        endPositionGuard.clear(); // Do not snap back after a manual turn.
        if(mode==0){
            int max=web.maximumX();
            int target=EpubPaginationPolicy.target(
                    web.getScrollX(),direction,web.getWidth(),max);
            if(target!=web.getScrollX()){
                animatePageTurn(direction,()->{
                    web.scrollTo(target,0);
                    recordProgress();
                },false);
                return;
            }
        }else{
            int max=web.maximumY();
            int target=Math.max(0,Math.min(max,
                    web.getScrollY()+Math.round(direction*web.getHeight()*.9f)));
            if(target!=web.getScrollY()){
                web.scrollTo(0,target);
                recordProgress();return;
            }
        }
        if(chapterIndex+direction>=0 &&
                chapterIndex+direction<archive.chapters.size()){
            recordProgress();
            final int nextChapter=chapterIndex+direction;
            final int nextLocation=direction<0?-1:0;
            Runnable next=()->animatePageTurn(direction,
                    ()->openChapter(nextChapter,nextLocation),true);
            if(direction>0)EditionMonetization.contentBreak(this,next);
            else next.run();
        }else if(direction>0){
            new AlertDialog.Builder(this).setTitle(L10n.t("책을 모두 읽었습니다."))
                    .setPositiveButton(L10n.t("확인"),null).show();
            ReaderHistory.save(this,historyId,currentPosition(),
                    archive.chapters.size()*10000,archive.chapters.size()*10000);
        }
    }
    private void jumpTo(int position){
        if(archive==null)return;
        openChapter(Math.min(archive.chapters.size()-1,
                Math.max(0,position/10000)),position%10000);
    }
    private int epubInt(String key,int fallback){
        return FolderMediaSettings.integer(this,FolderMediaSettings.EPUB,
                folderSettingsScope,key,prefs.getInt(key,fallback));
    }
    private boolean epubBool(String key,boolean fallback){
        return FolderMediaSettings.bool(this,FolderMediaSettings.EPUB,
                folderSettingsScope,key,prefs.getBoolean(key,fallback));
    }
    private void epubSave(String key,int value){
        FolderMediaSettings.integerSet(this,FolderMediaSettings.EPUB,
                folderSettingsScope,key,value);
    }
    private void epubSave(String key,boolean value){
        FolderMediaSettings.boolSet(this,FolderMediaSettings.EPUB,
                folderSettingsScope,key,value);
    }
    private void settingsDialog(){
        if(pdfMode){
            pdfSettingsDialog();
            return;
        }
        LinearLayout settings=Ui.vertical(this);
        settings.setPadding(Ui.dp(this,18),Ui.dp(this,8),Ui.dp(this,18),0);
        settings.addView(Ui.text(this,L10n.t("읽기 방식"),14,Ui.TEXT,true));
        Spinner readerMode=new Spinner(this);
        readerMode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"좌우 책 페이지","세로 연속 스크롤"}));
        readerMode.setSelection(mode);
        settings.addView(readerMode,Ui.lp(this,-1,45));
        settings.addView(Ui.text(this,"책 넘김 효과 (페이지형)",14,Ui.TEXT,true));
        Spinner turnEffect=new Spinner(this);
        turnEffect.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                ReaderTurnPreferences.STYLES));
        turnEffect.setSelection(ReaderTurnPreferences.toIndex(pageEffect));
        settings.addView(turnEffect,Ui.lp(this,-1,45));
        settings.addView(Ui.text(this,L10n.t("페이지 넘김 속도"),14,Ui.TEXT,true));
        Spinner turnSpeed=new Spinner(this);
        turnSpeed.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                ReaderTurnPreferences.SPEEDS));
        turnSpeed.setSelection(slowPageTurn?1:0);
        settings.addView(turnSpeed,Ui.lp(this,-1,45));
        SeekBar size=ReaderNumericSlider.add(this,settings,
                "글자 크기 · 12~36pt",12,36,fontSize,
                ReaderNumericSlider::points);
        SeekBar spacing=ReaderNumericSlider.add(this,settings,
                "줄 간격 · 1.10~2.30배",110,230,lineSpacing,
                ReaderNumericSlider::spacing);
        SeekBar margin=ReaderNumericSlider.add(this,settings,
                "좌우 여백 · 8~50dp",8,50,sideMargin,
                ReaderNumericSlider::dp);
        settings.addView(Ui.text(this,"독서 배경",14,Ui.TEXT,true));
        Spinner themeSpinner=new Spinner(this);
        themeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"아이보리","흰색","세피아","야간"}));
        themeSpinner.setSelection(theme);
        settings.addView(themeSpinner,Ui.lp(this,-1,45));
        ScrollView scroll=new ScrollView(this);
        scroll.addView(settings);
        new AlertDialog.Builder(this).setTitle("EPUB 독서 설정")
                .setView(scroll).setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("적용"),(dialog,which)->{
                    int keep=currentPosition();
                    cancelPageTurn();
                    int oldMode=mode,oldEffect=pageEffect,oldSize=fontSize;
                    int oldSpacing=lineSpacing,oldMargin=sideMargin,oldTheme=theme;
                    boolean oldSlow=slowPageTurn;
                    mode=readerMode.getSelectedItemPosition();
                    pageEffect=ReaderTurnPreferences.fromIndex(
                            turnEffect.getSelectedItemPosition());
                    slowPageTurn=turnSpeed.getSelectedItemPosition()==1;
                    // Save this novel folder, not the app-wide defaults.
                    fontSize=size.getProgress()+12;
                    lineSpacing=spacing.getProgress()+110;
                    sideMargin=margin.getProgress()+8;
                    theme=themeSpinner.getSelectedItemPosition();
                    if(oldMode!=mode)epubSave("mode",mode);
                    if(oldSize!=fontSize)epubSave("font_size",fontSize);
                    if(oldSpacing!=lineSpacing)
                        epubSave("line_spacing",lineSpacing);
                    if(oldMargin!=sideMargin)epubSave("side_margin",sideMargin);
                    if(oldTheme!=theme)epubSave("theme",theme);
                    if(oldEffect!=pageEffect)
                        epubSave("page_turn_effect",pageEffect);
                    if(oldSlow!=slowPageTurn)
                        epubSave("slow_page_turn",slowPageTurn);
                    jumpTo(keep);
                }).setNeutralButton(L10n.t("폴더 설정 초기화"),(dialog,which)->{
                    FolderMediaSettings.clear(this,FolderMediaSettings.EPUB,
                            folderSettingsScope,"mode","font_size","line_spacing",
                            "side_margin","theme","page_turn_effect",
                            "slow_page_turn");
                    recreate();
                }).show();
    }
    @Override protected void onResume(){
        super.onResume();
        if(pdfMode){
            pageEffect=pdfPrefs.getInt("turn_effect",
                    ReaderDefaultSettings.turn(this,FolderMediaSettings.EPUB));
            slowPageTurn=pdfPrefs.getBoolean("slow_turn",
                    ReaderDefaultSettings.slow(this,FolderMediaSettings.EPUB));
            return;
        }
        pageEffect=epubInt("page_turn_effect",
                ReaderDefaultSettings.turn(this,FolderMediaSettings.EPUB));
        slowPageTurn=epubBool("slow_page_turn",
                ReaderDefaultSettings.slow(this,FolderMediaSettings.EPUB));
    }
    @Override protected void onPause(){
        cancelPageTurn();
        recordProgress();
        super.onPause();
    }
    @Override protected void onDestroy(){
        if(pdfMode){
            // Close after queued page renders on the same worker thread.
            worker.execute(()->{
                if(pdfSource!=null){
                    try{pdfSource.close();}catch(Exception ignored){}
                    pdfSource=null;
                }
                pdfPageImage=null;
                pdfRightPageImage=null;
            });
            worker.shutdown();
        }else worker.shutdownNow();
        cancelPageTurn();
        web.stopLoading();
        web.destroy();
        if(archive!=null)try{archive.close();}catch(Exception ignored){}
        if(removeStaged&&stagedFile!=null)stagedFile.delete();
        super.onDestroy();
    }
    private final class BookWebView extends WebView {
        BookWebView(){super(EpubReaderActivity.this);}
        int maximumX(){return Math.max(0,computeHorizontalScrollRange()-getWidth());}
        int maximumY(){return Math.max(0,computeVerticalScrollRange()-getHeight());}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            // A late font-face or image can grow the column count even AFTER
            // the first stable frames. While this chapter was reached via
            // PREVIOUS, remain on its actual last page as the range changes.
            if(!pdfMode&&!loadingChapter&&archive!=null&&mode==0&&
                    endPositionGuard.observe(chapterIndex,getWidth(),
                            maximumX())&&!pinUpdatePosted){
                pinUpdatePosted=true;
                post(()->{
                    pinUpdatePosted=false;
                    if(isFinishing()||isDestroyed()||loadingChapter||
                            !endPositionGuard.activeFor(chapterIndex))return;
                    scrollTo(maximumX(),0);
                    recordProgress();
                });
            }
        }
        @Override protected void onScrollChanged(int x,int y,int oldX,int oldY){
            super.onScrollChanged(x,y,oldX,oldY);
            long now=SystemClock.uptimeMillis();
            if(!loadingChapter&&now-lastProgressWrite>650){
                lastProgressWrite=now;
                recordProgress();
            }
        }
    }
}
