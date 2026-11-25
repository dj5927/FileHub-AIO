package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
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
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Paged, offline TXT reader with actual text-layout pagination. */
public final class BookTextActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    // Full-book indexing must never block the page currently being opened.
    private final ExecutorService pageIndexWorker=
            Executors.newSingleThreadExecutor();
    private java.util.concurrent.Future<?> pageIndexTask;
    private List<Integer> completeBookPages;
    private String pageIndexGeometry="";
    private int pageIndexGeneration;
    private static final int WINDOWED_TEXT_THRESHOLD=120000;
    private SharedPreferences prefs,history;
    private String folderSettingsScope="";
    private String fullText="",bookKey,fileName,historyId;
    private LargeTextBook largeBook;
    /** Restores original-file offsets for saved progress and bookmarks. */
    private TxtBlankLineAutoCleaner.OffsetMap blankLineMap;
    private long largeAnchor,largeWindowStart,largeWindowEnd,largePageStart;
    private String largePageText="";
    private List<Integer> pages;
    private LinearLayout column,header,footer,pageColumns;
    private FrameLayout stage;
    private ReaderPageTransitions pageTransitions;
    private ReadingPage textPage,secondPage;
    private ScrollView scrollView;
    private TextView scrollText;
    private TextView pageCount,loadMessage;
    private SeekBar progress;
    private int pageIndex,anchor,layoutGeneration,themeIndex,fontIndex,fontSize,
            margin,spacingPercent;
    private int extraPageReservePx;
    private boolean seekDragging,chromeVisible=true,restoringScroll,scrollRebasePending;
    private int scrollWindowStart,scrollWindowEnd;
    private int displayMode,turnStyle;
    private boolean slowTurn;
    private boolean foldColumns;
    private int[] backgrounds={0xFFF8F1E2,Color.WHITE,0xFFE8D7B3,0xFF121719};
    private int[] foregrounds={0xFF26231E,0xFF202124,0xFF3C3020,0xFFE3E7E5};

    @Override public void onCreate(Bundle state){
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        fileName=getIntent().getStringExtra(ReaderSource.NAME);
        if(fileName==null||!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".txt")){
            finish();return;
        }
        bookKey=Integer.toHexString(ReaderSource.bookKey(getIntent()).hashCode());
        historyId=ReaderHistory.id(getIntent());
        prefs=getSharedPreferences("text_book_reader_settings",MODE_PRIVATE);
        history=getSharedPreferences("text_book_reader_progress",MODE_PRIVATE);
        folderSettingsScope=FolderMediaSettings.reader(getIntent());
        themeIndex=Math.max(0,Math.min(3,textInt("theme",0)));
        fontIndex=Math.max(0,Math.min(2,textInt("font",0)));
        fontSize=Math.max(12,Math.min(38,textInt("size",20)));
        spacingPercent=Math.max(100,Math.min(240,textInt("spacing",145)));
        margin=Math.max(9,Math.min(44,textInt("margin",19)));
        anchor=ReaderHistory.migratedTextPosition(this,historyId,bookKey);
        largeAnchor=getSharedPreferences("large_txt_read_positions",MODE_PRIVATE)
                .getLong(historyId,Math.max(0,anchor));
        displayMode=textInt("view_mode",0)==1?1:0;
        turnStyle=textInt("turn_effect",ReaderDefaultSettings.turn(this,
                FolderMediaSettings.TEXT));
        slowTurn=textBool("slow_page_turn",ReaderDefaultSettings.slow(this,
                FolderMediaSettings.TEXT));
        foldColumns=textBool("fold_columns",true);
        column=Ui.vertical(this);
        column.setBackgroundColor(backgrounds[themeIndex]);
        ViewCompat.setOnApplyWindowInsetsListener(column,(view,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.statusBars()|
                    WindowInsetsCompat.Type.navigationBars()|
                    WindowInsetsCompat.Type.displayCutout());
            column.setPadding(0,safe.top,0,safe.bottom);
            return insets;
        });
        setContentView(column);
        header=Ui.horizontal(this);
        header.setPadding(dp(6),0,dp(6),0);
        column.addView(header,Ui.lp(this,-1,47));
        TextView back=Ui.text(this,"‹",31,foregrounds[themeIndex],false);
        back.setGravity(Gravity.CENTER);
        header.addView(back,Ui.lp(this,45,-1));
        back.setOnClickListener(v->finish());
        TextView name=Ui.text(this,fileName,14,foregrounds[themeIndex],true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        header.addView(name,new LinearLayout.LayoutParams(0,-1,1));
        TextView bookmark=Ui.text(this,"☆",24,foregrounds[themeIndex],false);
        bookmark.setGravity(Gravity.CENTER);
        bookmark.setContentDescription(L10n.t("책갈피 추가, 이동 또는 삭제"));
        header.addView(bookmark,Ui.lp(this,48,-1));
        bookmark.setOnClickListener(v->{
            if(largeBook!=null){
                long sourceLength=originalLength();
                int fraction=(int)Math.min(1000000L,
                        Math.round(1000000d*originalOffset(largeAnchor)/sourceLength));
                ReaderBookmarkDialog.show(this,historyId,fraction,false,
                        1000000,marker->jumpToLargeOffset(
                                displayOffset(sourceLength*marker/1000000L)));
            }else if(!fullText.isEmpty())ReaderBookmarkDialog.show(this,
                    historyId,(int)originalOffset(anchor),false,
                    (int)originalLength(),
                    position->jumpToOffset((int)displayOffset(position)));
        });
        TextView customize=Ui.text(this,"Aa",19,foregrounds[themeIndex],true);
        customize.setContentDescription("폰트, 글씨 크기, 줄간격, 배경 설정");
        customize.setGravity(Gravity.CENTER);
        header.addView(customize,Ui.lp(this,57,-1));
        customize.setOnClickListener(v->showSettings());
        stage=new FrameLayout(this);
        column.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        pageColumns=Ui.horizontal(this);
        pageColumns.setGravity(Gravity.TOP);
        stage.addView(pageColumns,new FrameLayout.LayoutParams(-1,-1));
        textPage=new ReadingPage(this);
        textPage.setGravity(Gravity.TOP|Gravity.START);
        textPage.setIncludeFontPadding(false);
        pageColumns.addView(textPage,new LinearLayout.LayoutParams(0,-1,1));
        if(isFoldWide()){
            secondPage=new ReadingPage(this);
            secondPage.setGravity(Gravity.TOP|Gravity.START);
            secondPage.setIncludeFontPadding(false);
            pageColumns.addView(secondPage,new LinearLayout.LayoutParams(0,-1,1));
        }
        scrollView=new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setVerticalScrollBarEnabled(true);
        LinearLayout scrollWrapper=Ui.vertical(this);
        scrollWrapper.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        scrollText=Ui.text(this,"",fontSize,foregrounds[themeIndex],false);
        scrollText.setGravity(Gravity.TOP|Gravity.START);
        scrollText.setIncludeFontPadding(false);
        int available=getResources().getDisplayMetrics().widthPixels-dp(28);
        scrollWrapper.addView(scrollText,new LinearLayout.LayoutParams(
                Math.min(dp(820),Math.max(dp(200),available)),-2));
        scrollView.addView(scrollWrapper);
        stage.addView(scrollView,new FrameLayout.LayoutParams(-1,-1));
        pageTransitions=new ReaderPageTransitions(this,stage,pageColumns);
        pageTransitions.configure(turnStyle,slowTurn,backgrounds[themeIndex]);
        scrollView.setOnScrollChangeListener((View v,int sx,int sy,
                                               int oldSx,int oldSy)->{
            if(displayMode==1 && !restoringScroll)updateScrollProgress();
        });
        textPage.addOnLayoutChangeListener((view,left,top,right,bottom,
                                             oldLeft,oldTop,oldRight,oldBottom)->{
            if(hasBook() &&
                    (right-left!=oldRight-oldLeft || bottom-top!=oldBottom-oldTop))
                textPage.post(this::paginate);
        });
        loadMessage=Ui.text(this,"책을 여는 중...",14,foregrounds[themeIndex],false);
        loadMessage.setGravity(Gravity.CENTER);
        stage.addView(loadMessage,new FrameLayout.LayoutParams(-1,dp(58),Gravity.CENTER));
        footer=Ui.vertical(this);
        column.addView(footer,Ui.lp(this,-1,71));
        LinearLayout controls=Ui.horizontal(this);
        footer.addView(controls,Ui.lp(this,-1,35));
        TextView prev=Ui.text(this,"〈",21,foregrounds[themeIndex],false);
        prev.setGravity(Gravity.CENTER);
        controls.addView(prev,Ui.lp(this,52,-1));
        prev.setOnClickListener(v->move(-1));
        pageCount=Ui.text(this,"0 / 0",12,foregrounds[themeIndex],false);
        pageCount.setGravity(Gravity.CENTER);
        pageCount.setContentDescription("텍스트책 전체 진행률과 페이지. 터치하면 0~100% 빠른 이동");
        pageCount.setOnClickListener(v->showQuickSeek());
        controls.addView(pageCount,new LinearLayout.LayoutParams(0,-1,1));
        TextView next=Ui.text(this,"〉",21,foregrounds[themeIndex],false);
        next.setGravity(Gravity.CENTER);
        controls.addView(next,Ui.lp(this,52,-1));
        next.setOnClickListener(v->move(1));
        progress=new SeekBar(this);
        footer.addView(progress,Ui.lp(this,-1,33));
        progress.setMax(1000);
        progress.setEnabled(false);
        progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar bar){seekDragging=true;}
            @Override public void onProgressChanged(SeekBar bar,int value,boolean user){}
            @Override public void onStopTrackingTouch(SeekBar bar){
                seekDragging=false;
                pageTransitions.cancel();
                if(largeBook!=null){
                    jumpToLargeOffset(ReaderQuickSeek.toOffset(
                            bar.getProgress(),largeBook.length()));
                }else if(windowedBook()){
                    jumpToOffset((int)ReaderQuickSeek.toOffset(
                            bar.getProgress(),fullText.length()));
                }else if(displayMode==1){
                    jumpToOffset(Math.round(fullText.length()*
                            bar.getProgress()/1000f));
                }else if(pages!=null&&!pages.isEmpty())
                    showPage(Math.round(bar.getProgress()*(pages.size()-1)/1000f));
            }
        });
        applyStyle();
        useReadingMode();
        textPage.post(()->{
            if(hasBook())paginate();
        });
        worker.execute(()->{
            try(InputStream input=ReaderSource.open(this,getIntent())){
                // Convert and index on disk in a stream: no 12MB byte cap,
                // and very large books do not occupy a giant String.
                LargeTextBook raw=LargeTextBook.stage(input,getCacheDir());
                final LargeTextBook staged=raw.autoCleanBlankLines();
                final TxtBlankLineAutoCleaner.OffsetMap mapping=staged.offsets();
                final boolean small=staged.length()<=5000000L;
                final String loaded=small?
                        staged.slice(0,(int)staged.length()):null;
                if(small)staged.close();
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()){
                        if(!small)try{staged.close();}catch(Exception ignored){}
                        return;
                    }
                    blankLineMap=mapping;
                    anchor=(int)displayOffset(anchor);
                    largeAnchor=displayOffset(largeAnchor);
                    if(small)fullText=loaded;
                    else largeBook=staged;
                    if(displayMode==1)useReadingMode();
                    else textPage.post(this::paginate);
                });
            }catch(Exception error){
                runOnUiThread(()->loadMessage.setText(
                        "텍스트책을 열지 못했습니다: "+error.getMessage()));
            }
        });
    }
    private int dp(int value){return Ui.dp(this,value);}
    private int textInt(String key,int fallback){
        return FolderMediaSettings.integer(this,FolderMediaSettings.TEXT,
                folderSettingsScope,key,prefs.getInt(key,fallback));
    }
    private boolean textBool(String key,boolean fallback){
        return FolderMediaSettings.bool(this,FolderMediaSettings.TEXT,
                folderSettingsScope,key,prefs.getBoolean(key,fallback));
    }
    private void textSave(String key,int value){
        FolderMediaSettings.integerSet(this,FolderMediaSettings.TEXT,
                folderSettingsScope,key,value);
    }
    private void textSave(String key,boolean value){
        FolderMediaSettings.boolSet(this,FolderMediaSettings.TEXT,
                folderSettingsScope,key,value);
    }
    private boolean hasBook(){
        return largeBook!=null||!fullText.isEmpty();
    }
    private boolean windowedBook(){
        return largeBook!=null||fullText.length()>WINDOWED_TEXT_THRESHOLD;
    }
    private long bookLength(){
        return largeBook!=null?largeBook.length():fullText.length();
    }
    private long originalLength(){
        return blankLineMap==null?bookLength():blankLineMap.rawLength();
    }
    private long originalOffset(long display){
        return blankLineMap==null?display:blankLineMap.toOriginal(display);
    }
    private long displayOffset(long original){
        return blankLineMap==null?original:blankLineMap.toDisplay(original);
    }
    private boolean isFoldWide(){
        return getResources().getConfiguration().screenWidthDp>=600;
    }
    private boolean twoColumn(){
        return secondPage!=null && ReaderSpreadPolicy.text(
                getResources().getConfiguration().screenWidthDp,
                foldColumns,displayMode);
    }
    private Typeface font(){
        if(fontIndex==1)return Typeface.SANS_SERIF;
        if(fontIndex==2)return Typeface.MONOSPACE;
        return Typeface.SERIF;
    }
    private void applyStyle(){
        extraPageReservePx=0;
        int bg=backgrounds[themeIndex],fg=foregrounds[themeIndex];
        column.setBackgroundColor(bg);
        header.setBackgroundColor(bg);footer.setBackgroundColor(bg);
        for(TextView reader:new TextView[]{textPage,secondPage,scrollText}){
            if(reader==null)continue;
            reader.setBackgroundColor(bg);
            reader.setTextColor(fg);
            reader.setTextSize(TypedValue.COMPLEX_UNIT_SP,fontSize);
            reader.setTypeface(font());
            // Korean phrase-based line breaking on Android 13+ and the same
            // paragraph-independent wrapping rules as BookPaginator in page
            // mode; this avoids a skipped 1-2 lines at the page boundary.
            KoreanReadingLayout.configure(reader,reader!=scrollText);
            reader.setLineSpacing(0,spacingPercent/100f);
            // Keep a little bottom breathing room for descenders even on
            // Fold dual columns and fonts with unusually deep glyphs.
            reader.setPadding(dp(margin),dp(14),dp(margin),dp(12));
        }
        for(int i=0;i<header.getChildCount();i++)
            if(header.getChildAt(i) instanceof TextView)
                ((TextView)header.getChildAt(i)).setTextColor(fg);
        for(int i=0;i<footer.getChildCount();i++) {
            View child=footer.getChildAt(i);
            if(child instanceof LinearLayout) {
                LinearLayout row=(LinearLayout)child;
                for(int j=0;j<row.getChildCount();j++)
                    if(row.getChildAt(j) instanceof TextView)
                        ((TextView)row.getChildAt(j)).setTextColor(fg);
            }
        }
        loadMessage.setTextColor(fg);
    }
    private void useReadingMode(){
        pageTransitions.cancel();
        if(secondPage!=null)secondPage.setVisibility(twoColumn()?View.VISIBLE:View.GONE);
        boolean scroll=displayMode==1;
        pageColumns.setVisibility(scroll?View.GONE:View.VISIBLE);
        scrollView.setVisibility(scroll?View.VISIBLE:View.GONE);
        if(scroll){
            // Text is never modified; the scroll viewer only owns layout.
            restoringScroll=true;
            if(hasBook()){
                // Never assign an entire novel to one TextView: Android
                // measures every line on the UI thread and becomes frozen.
                if(largeBook!=null)buildLargeScrollWindow(largeAnchor);
                else buildScrollWindow(anchor);
                progress.setEnabled(true);
                loadMessage.setVisibility(View.GONE);
            }
        }else{
            scrollText.setText("");
            restoringScroll=false;
            if(hasBook())textPage.post(this::paginate);
        }
    }
    private void restoreScrollPosition(){
        if(displayMode!=1||!hasBook())return;
        android.text.Layout layout=scrollText.getLayout();
        if(layout==null){
            scrollText.post(this::restoreScrollPosition);
            return;
        }
        int safe=largeBook!=null?
                (int)Math.max(0,Math.min(largeWindowEnd-largeWindowStart,
                        largeAnchor-largeWindowStart)):
                Math.max(0,Math.min(scrollWindowEnd-scrollWindowStart,
                        anchor-scrollWindowStart));
        int line=layout.getLineForOffset(safe);
        scrollView.scrollTo(0,Math.max(0,layout.getLineTop(line)));
        restoringScroll=false;
        scrollRebasePending=false;
        updateScrollProgress();
    }
    private void buildScrollWindow(int desiredOffset){
        if(fullText.isEmpty())return;
        restoringScroll=true;
        scrollWindowStart=TextScrollWindow.start(fullText,desiredOffset);
        scrollWindowEnd=TextScrollWindow.end(fullText,scrollWindowStart);
        scrollText.setText(fullText.substring(scrollWindowStart,scrollWindowEnd));
        scrollView.post(this::restoreScrollPosition);
    }
    private void buildLargeScrollWindow(long desiredOffset){
        if(largeBook==null)return;
        try{
            largeAnchor=Math.max(0,Math.min(largeBook.length(),desiredOffset));
            largeWindowStart=Math.max(0,largeAnchor-TextScrollWindow.BACKTRACK);
            String window=largeBook.slice(largeWindowStart,TextScrollWindow.SPAN);
            largeWindowEnd=largeWindowStart+window.length();
            restoringScroll=true;
            scrollText.setText(window);
            scrollView.post(this::restoreScrollPosition);
        }catch(Exception failed){
            loadMessage.setText("큰 책의 본문을 읽지 못했습니다: "+failed.getMessage());
            loadMessage.setVisibility(View.VISIBLE);
        }
    }
    private void updateScrollProgress(){
        if(displayMode!=1||!hasBook()||scrollText.getLayout()==null)return;
        android.text.Layout layout=scrollText.getLayout();
        int y=Math.max(0,scrollView.getScrollY());
        int topLine=layout.getLineForVertical(y);
        int bottomLine=layout.getLineForVertical(
                Math.min(layout.getHeight(),y+scrollView.getHeight()));
        if(largeBook!=null){
            largeAnchor=largeWindowStart+layout.getLineStart(topLine);
            long end=largeWindowStart+layout.getLineEnd(bottomLine);
            storeLargePosition(largeAnchor,end);
            if(!scrollRebasePending &&
                    ((largeAnchor-largeWindowStart<TextScrollWindow.EDGE &&
                            largeWindowStart>0) ||
                     (largeWindowEnd-end<TextScrollWindow.EDGE &&
                            largeWindowEnd<largeBook.length()))){
                scrollRebasePending=true;
                final long point=largeAnchor;
                scrollView.post(()->{
                    if(displayMode==1&&!isFinishing()&&!isDestroyed()){
                        buildLargeScrollWindow(point);
                    }else scrollRebasePending=false;
                });
            }
            return;
        }
        anchor=scrollWindowStart+layout.getLineStart(topLine);
        int end=scrollWindowStart+layout.getLineEnd(bottomLine);
        history.edit().putInt("offset_"+bookKey,
                (int)originalOffset(anchor)).apply();
        ReaderHistory.save(this,historyId,
                (int)originalOffset(anchor),(int)originalOffset(end),
                (int)originalLength());
        int percent=Math.min(100,Math.round(100f*end/fullText.length()));
        pageCount.setText("스크롤 읽기  ·  "+percent+"%");
        if(!seekDragging)progress.setProgress(
                Math.round(1000f*anchor/fullText.length()));
        if(!scrollRebasePending && TextScrollWindow.shift(
                fullText,scrollWindowStart,scrollWindowEnd,anchor,end)){
            scrollRebasePending=true;
            final int at=anchor;
            scrollView.post(()->{
                if(displayMode==1 && !isFinishing() && !isDestroyed()){
                    anchor=at;
                    buildScrollWindow(at);
                }else scrollRebasePending=false;
            });
        }
    }
    private void storeLargePosition(long position,long visibleEnd){
        if(largeBook==null)return;
        largeAnchor=Math.max(0,Math.min(largeBook.length(),position));
        getSharedPreferences("large_txt_read_positions",MODE_PRIVATE)
                .edit().putLong(historyId,originalOffset(largeAnchor)).apply();
        long total=originalLength();
        int scaled=(int)Math.min(1000000L,
                Math.min(total,originalOffset(visibleEnd))*1000000.0/total);
        ReaderHistory.save(this,historyId,scaled,scaled,1000000);
        int percent=(int)Math.min(100,
                Math.min(total,visibleEnd)*100.0/total);
        pageCount.setText((displayMode==1?"스크롤 읽기":"페이지 읽기")+
                "  ·  "+percent+"%");
        if(!seekDragging)progress.setProgress(
                (int)Math.min(1000L,largeAnchor*1000.0/total));
    }
    private void jumpToLargeOffset(long position){
        if(largeBook==null)return;
        largeAnchor=Math.max(0,Math.min(position,largeBook.length()));
        if(displayMode==1)buildLargeScrollWindow(largeAnchor);
        else paginate();
    }
    private void jumpToOffset(int charPosition){
        if(fullText.isEmpty())return;
        anchor=Math.max(0,Math.min(charPosition,fullText.length()));
        if(displayMode==1){
            buildScrollWindow(anchor);
        }else if(windowedBook()){
            paginateLarge(anchor,false);
        }else if(pages!=null && !pages.isEmpty()){
            showPage(BookPaginator.pageFor(pages,anchor));
        }
    }
    private void showQuickSeek(){
        if(!hasBook()||pageTransitions.busy()||
                (displayMode==0&&(pages==null||pages.isEmpty()))){
            Toast.makeText(this,"본문 페이지를 준비한 뒤 이동할 수 있습니다.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final long length=largeBook!=null?largeBook.length():fullText.length();
        final long at=largeBook!=null?largeAnchor:anchor;
        ReaderQuickSeek.show(this,"텍스트책",
                ReaderQuickSeek.fromOffset(at,length),
                position->{
                    long offset=ReaderQuickSeek.toOffset(position,length);
                    if(largeBook==null&&displayMode==0){
                        List<Integer> index=windowedBook()?completeBookPages:pages;
                        if(index!=null&&!index.isEmpty()){
                            int target=BookPaginator.pageFor(index,(int)offset);
                            int last=Math.min(index.size(),target+(twoColumn()?2:1));
                            return (target+1)+(twoColumn()?"–"+last:"")+
                                    " / "+index.size()+"쪽";
                        }
                    }
                    return "글자 위치 "+String.format(java.util.Locale.KOREA,
                            "%,d / %,d",offset,length);
                },
                position->{
                    pageTransitions.cancel();
                    long offset=ReaderQuickSeek.toOffset(position,length);
                    if(largeBook!=null)jumpToLargeOffset(offset);
                    else jumpToOffset((int)offset);
                });
    }
    private void paginate(){
        // Long TXT opens only a nearby segment first. Computing thousands
        // of page layouts blocks the first screen for 10-20 seconds.
        if(displayMode==1)return;
        if(windowedBook()){
            paginateLarge(largeBook!=null?largeAnchor:anchor,false);
            return;
        }
        if(fullText.isEmpty()||textPage.getWidth()<40||textPage.getHeight()<40)return;
        final int generation=++layoutGeneration;
        int width=textPage.getWidth()-textPage.getPaddingLeft()-textPage.getPaddingRight();
        int height=Math.max(32,BookPageSafety.usableHeight(
                textPage.getHeight()-textPage.getPaddingTop()-
                        textPage.getPaddingBottom(),
                textPage.getPaint().getFontSpacing(),
                spacingPercent/100f,dp(8))-extraPageReservePx);
        final int requestedWidth=textPage.getWidth();
        final int requestedHeight=textPage.getHeight();
        final boolean requestedChrome=chromeVisible;
        final int lastAnchor=anchor;
        final TextPaint paint=new TextPaint(textPage.getPaint());
        final float line=spacingPercent/100f;
        // Only show the center loading text BEFORE the first page. During
        // typography/viewport reflows keep the last readable page visible;
        // re-showing the overlay after showPage caused it to stick over text.
        if(textPage.length()==0){
            loadMessage.setText("페이지를 나누는 중...");
            loadMessage.setVisibility(View.VISIBLE);
        }
        worker.execute(()->{
            try{
                List<Integer> result=BookPaginator.starts(
                        fullText,paint,width,height,line);
                runOnUiThread(()->{
                    if(generation!=layoutGeneration||isFinishing()||isDestroyed())return;
                    if(requestedWidth!=textPage.getWidth()||
                            requestedHeight!=textPage.getHeight()||
                            requestedChrome!=chromeVisible){
                        textPage.post(this::paginate);
                        return;
                    }
                    pages=result;
                    progress.setEnabled(true);
                    loadMessage.setVisibility(View.GONE);
                    showPage(BookPaginator.pageFor(result,lastAnchor));
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation==layoutGeneration)loadMessage.setText(
                            "페이지 구성 실패: "+error.getMessage());
                });
            }
        });
    }
    private void paginateLarge(long desired,boolean alignAtStart){
        if(!windowedBook()||textPage.getWidth()<40||textPage.getHeight()<40)return;
        final int generation=++layoutGeneration;
        final int width=textPage.getWidth()-textPage.getPaddingLeft()-
                textPage.getPaddingRight();
        final int height=Math.max(32,BookPageSafety.usableHeight(
                textPage.getHeight()-textPage.getPaddingTop()-
                        textPage.getPaddingBottom(),
                textPage.getPaint().getFontSpacing(),
                spacingPercent/100f,dp(8))-extraPageReservePx);
        final int requestedWidth=textPage.getWidth();
        final int requestedHeight=textPage.getHeight();
        final boolean requestedChrome=chromeVisible;
        final TextPaint paint=new TextPaint(textPage.getPaint());
        final float line=spacingPercent/100f;
        final long bookEnd=bookLength();
        // Never display an empty single-character tail when seeking 100%.
        final long point=TextPageWindow.point(desired,bookEnd);
        final long base=TextPageWindow.base(desired,bookEnd,alignAtStart);
        if(textPage.length()==0){
            loadMessage.setText("페이지를 구성하는 중...");
            loadMessage.setVisibility(View.VISIBLE);
        }
        worker.execute(()->{
            try{
                String part=largeBook!=null?
                        largeBook.slice(base,TextPageWindow.span(base,bookEnd)):
                        fullText.substring((int)base,
                                (int)(base+TextPageWindow.span(base,bookEnd)));
                List<Integer> starts=BookPaginator.starts(
                        part,paint,width,height,line);
                runOnUiThread(()->{
                    if(generation!=layoutGeneration||isFinishing()||isDestroyed())return;
                    if(requestedWidth!=textPage.getWidth()||
                            requestedHeight!=textPage.getHeight()||
                            requestedChrome!=chromeVisible){
                        textPage.post(this::paginate);
                        return;
                    }
                    largePageStart=base;
                    largePageText=part;
                    pages=starts;
                    progress.setEnabled(true);
                    loadMessage.setVisibility(View.GONE);
                    showLargePage(BookPaginator.pageFor(starts,
                            (int)Math.max(0,Math.min(part.length(),point-base))));
                    pageTransitions.ready();
                    if(largeBook==null)indexCompleteBookInBackground(
                            width,height,paint,line);
                });
            }catch(Exception error){
                runOnUiThread(()->{
                    if(generation==layoutGeneration){
                        pageTransitions.cancel();
                        loadMessage.setText("페이지 구성 실패: "+error.getMessage());
                        loadMessage.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
    }
    /** Background-only full-book index for exact page totals. Never delay
     * the visible 48K-character reading window or a page-turn. */
    private void indexCompleteBookInBackground(
            int width,int height,TextPaint paint,float spacing){
        if(largeBook!=null||fullText.length()<=WINDOWED_TEXT_THRESHOLD)
            return;
        String key=width+":"+height+":"+fontSize+":"+fontIndex+":"+
                spacingPercent+":"+margin+":"+extraPageReservePx;
        if(key.equals(pageIndexGeometry))return;
        pageIndexGeometry=key;
        completeBookPages=null;
        if(pageIndexTask!=null)pageIndexTask.cancel(true);
        final int generation=++pageIndexGeneration;
        final String indexedText=fullText;
        pageIndexTask=pageIndexWorker.submit(()->{
            try{
                List<Integer> entire=BookPaginator.starts(
                        indexedText,paint,width,height,spacing);
                runOnUiThread(()->{
                    if(isFinishing()||isDestroyed()||
                            generation!=pageIndexGeneration||
                            !key.equals(pageIndexGeometry))return;
                    completeBookPages=entire;
                    if(displayMode==0&&windowedBook()){
                        long start=largePageStart+
                                (pages==null||pages.isEmpty()?0:
                                        pages.get(pageIndex));
                        int next=pageIndex+(twoColumn()?2:1);
                        long shownEnd=largePageStart+
                                (pages==null||pages.isEmpty()?0:
                                        next<pages.size()?pages.get(next):
                                                largePageText.length());
                        updateWindowPageLabel(start,shownEnd);
                    }
                });
            }catch(Exception interrupted){
                // A new Fold/portrait/font layout replaces this index.
            }
        });
    }
    private void updateWindowPageLabel(long start,long shownEnd){
        long total=bookLength();
        if(total<=0)return;
        int done=(int)Math.round(100d*Math.max(0,Math.min(total,shownEnd))/total);
        if(largeBook!=null||completeBookPages==null){
            pageCount.setText("페이지 읽기  ·  "+done+"%");
        }else{
            int index=BookPaginator.pageFor(completeBookPages,(int)start);
            int end=Math.min(completeBookPages.size(),
                    index+(twoColumn()?2:1));
            pageCount.setText((index+1)+(twoColumn()&&end>index+1?
                    "–"+end:"")+" / "+completeBookPages.size()+
                    "  ·  "+done+"%");
        }
        if(!seekDragging)progress.setProgress(ReaderQuickSeek.fromOffset(start,total));
    }
    private void showLargePage(int target){
        if(!windowedBook()||pages==null||pages.isEmpty())return;
        int previous=pageIndex;
        pageIndex=Math.max(0,Math.min(pages.size()-1,target));
        pageIndex=ReaderSpreadPolicy.start(pageIndex,twoColumn());
        int start=pages.get(pageIndex);
        int end=pageIndex+1<pages.size()?pages.get(pageIndex+1):
                largePageText.length();
        int last=end;
        if(twoColumn()&&secondPage!=null){
            if(pageIndex+1<pages.size()){
                int nextEnd=pageIndex+2<pages.size()?pages.get(pageIndex+2):
                        largePageText.length();
                secondPage.setText(largePageText.substring(end,nextEnd));
                last=nextEnd;
            }else secondPage.setText("");
        }
        textPage.setText(largePageText.substring(start,Math.max(start,end)));
        if(largeBook!=null){
            largeAnchor=largePageStart+start;
            storeLargePosition(largeAnchor,largePageStart+last);
        }else{
            anchor=(int)(largePageStart+start);
            int shownEnd=(int)(largePageStart+last);
            history.edit().putInt("offset_"+bookKey,
                    (int)originalOffset(anchor)).apply();
            ReaderHistory.save(this,historyId,
                    (int)originalOffset(anchor),(int)originalOffset(shownEnd),
                    (int)originalLength());
            updateWindowPageLabel(anchor,shownEnd);
        }
        loadMessage.setVisibility(View.GONE);
        schedulePageFitVerification();
    }
    private void showPage(int target){
        if(windowedBook()){
            showLargePage(target);
            return;
        }
        if(pages==null||pages.isEmpty())return;
        int previous=pageIndex;
        pageIndex=Math.max(0,Math.min(pages.size()-1,target));
        pageIndex=ReaderSpreadPolicy.start(pageIndex,twoColumn());
        int start=pages.get(pageIndex);
        int end=pageIndex+1<pages.size()?pages.get(pageIndex+1):fullText.length();
        int lastDisplayed=end;
        if(twoColumn() && secondPage!=null){
            if(pageIndex+1<pages.size()){
                int secondEnd=pageIndex+2<pages.size()?
                        pages.get(pageIndex+2):fullText.length();
                secondPage.setText(fullText.substring(end,secondEnd));
                lastDisplayed=secondEnd;
            }else secondPage.setText("");
        }
        anchor=start;
        history.edit().putInt("offset_"+bookKey,
                (int)originalOffset(start)).apply();
        ReaderHistory.save(this,historyId,
                (int)originalOffset(start),(int)originalOffset(lastDisplayed),
                (int)originalLength());
        textPage.setText(fullText.substring(start,Math.max(start,end)));
        schedulePageFitVerification();
        int displayedLast=Math.min(pages.size(),pageIndex+(twoColumn()?2:1));
        pageCount.setText((pageIndex+1)+(twoColumn()&&displayedLast>pageIndex+1?
                "–"+displayedLast:"")+" / "+pages.size()+"  ·  "+
                Math.round(100f*lastDisplayed/Math.max(1,fullText.length()))+"%");
        if(!seekDragging)progress.setProgress(pages.size()<2?0:
                Math.round(pageIndex*1000f/(pages.size()-1)));
    }
    private void move(int direction){
        if(displayMode==1){
            scrollView.smoothScrollBy(0,Math.round(
                    direction*scrollView.getHeight()*.87f));
        }else if(windowedBook()){
            if(pages==null||pages.isEmpty()||pageTransitions.busy())return;
            int next=pageIndex+direction*(twoColumn()?2:1);
            if(next<0){
                if(largePageStart>0)pageTransitions.begin(direction,
                        ()->paginateLarge(largePageStart-1,false),true);
            }else if(next>=pages.size() ||
                    (next>=pages.size()-1 &&
                     largePageStart+largePageText.length()<bookLength())){
                long target=largePageStart+(next<pages.size()?
                        pages.get(next):largePageText.length());
                if(target<bookLength())pageTransitions.begin(direction,
                        ()->paginateLarge(target,true),true);
            }else pageTransitions.begin(direction,
                    ()->showLargePage(next),false);
        }else if(pages!=null&&!pages.isEmpty()&&!pageTransitions.busy()){
            int next=pageIndex+direction*(twoColumn()?2:1);
            if(next>=0&&next<pages.size())pageTransitions.begin(direction,
                    ()->showPage(next),false);
        }
    }
    /** Interactive TXT fold: partial page pulls are reversible. */
    private boolean startTextFold(int direction,float touchX,float touchY){
        if(displayMode!=0||turnStyle!=EpubTurnGeometry.CURL||
                pageTransitions.busy()||pages==null||pages.isEmpty())return false;
        final int oldIndex=pageIndex;
        final int next=oldIndex+direction*(twoColumn()?2:1);
        final boolean crossingWindow=windowedBook()&&
                (next<0||next>=pages.size()||
                 (next>=pages.size()-1&&
                  largePageStart+largePageText.length()<bookLength()));
        if(!crossingWindow&&(next<0||next>=pages.size()))return false;
        if(crossingWindow){
            if(next<0&&largePageStart<=0)return false;
            if(next>=0&&largePageStart+largePageText.length()>=
                    bookLength())return false;
        }
        final List<Integer> beforePages=pages;
        final String beforeText=largePageText;
        final long beforeStart=largePageStart;
        final int previous=pageIndex;
        pageTransitions.setBounds(null);
        return pageTransitions.startFinger(direction,touchX,touchY,
                ()->{
                    if(crossingWindow){
                        if(next<0)paginateLarge(beforeStart-1,false);
                        else{
                            long point=beforeStart+(next<beforePages.size()?
                                    beforePages.get(next):beforeText.length());
                            paginateLarge(point,true);
                        }
                    }else{
                        if(windowedBook())showLargePage(next);
                        else showPage(next);
                        pageTransitions.ready();
                    }
                },
                ()->{
                    if(crossingWindow){
                        ++layoutGeneration; // invalidate pending re-pagination
                        pages=beforePages;
                        largePageText=beforeText;
                        largePageStart=beforeStart;
                        showLargePage(previous);
                    }else if(windowedBook())showLargePage(oldIndex);
                    else showPage(oldIndex);
                },
                ()->{});
    }
    private void toggleChrome(){
        pageTransitions.cancel();
        chromeVisible=!chromeVisible;
        extraPageReservePx=0;
        header.setVisibility(chromeVisible?View.VISIBLE:View.GONE);
        footer.setVisibility(chromeVisible?View.VISIBLE:View.GONE);
        // The reading surface changed height, so repaginate from the same
        // text offset. Position stays stable across full-screen transitions.
        if(displayMode==1)scrollView.post(this::updateScrollProgress);
        else{
            // Reflow after this visibility change has been laid out; stale
            // worker results are rejected using layout dimensions above.
            textPage.requestLayout();
            textPage.postOnAnimation(this::paginate);
        }
    }
    /** The actual TextView knows its final glyph bounds. If a device font
     * renders deeper than StaticLayout predicted, reflow from the SAME
     * character offset with extra space instead of silently clipping and
     * discarding those characters on the next page. */
    private void schedulePageFitVerification(){
        final int expectedPage=pageIndex;
        final int expectedGeneration=layoutGeneration;
        textPage.postOnAnimation(()->verifyPageFit(
                expectedPage,expectedGeneration,0));
    }
    private void verifyPageFit(int expectedPage,int expectedGeneration,int retry){
        if(isFinishing()||isDestroyed()||displayMode!=0||
                expectedPage!=pageIndex||expectedGeneration!=layoutGeneration)
            return;
        if(pageTransitions.busy()){
            if(retry<3)textPage.postDelayed(()->verifyPageFit(
                    expectedPage,expectedGeneration,retry+1),300);
            return;
        }
        int overflow=0;
        TextView[] visible=twoColumn()?
                new TextView[]{textPage,secondPage}:
                new TextView[]{textPage};
        for(TextView view:visible){
            if(view==null||view.getText().length()==0)continue;
            android.text.Layout layout=view.getLayout();
            if(layout==null||layout.getLineCount()==0)continue;
            int available=view.getHeight()-
                    view.getPaddingTop()-view.getPaddingBottom();
            int bottom=layout.getLineBottom(layout.getLineCount()-1);
            overflow=Math.max(overflow,bottom-available);
        }
        int maxReserve=Math.max(0,textPage.getHeight()/3);
        if(overflow>dp(1)&&extraPageReservePx<maxReserve){
            extraPageReservePx=Math.min(maxReserve,
                    extraPageReservePx+overflow+dp(12));
            paginate();
        }
    }
    private void showSettings(){
        LinearLayout form=Ui.vertical(this);
        form.setPadding(dp(18),dp(8),dp(18),0);
        form.addView(Ui.text(this,L10n.t("읽기 방식"),14,Ui.TEXT,true));
        Spinner readingMode=new Spinner(this);
        readingMode.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"책 넘김 · 좌우 페이지","세로 스크롤 · 연속 읽기"}));
        readingMode.setSelection(displayMode);
        form.addView(readingMode,Ui.lp(this,-1,46));
        form.addView(Ui.text(this,"페이지 넘김 효과",14,Ui.TEXT,true));
        Spinner animation=new Spinner(this);
        animation.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                ReaderTurnPreferences.STYLES));
        animation.setSelection(ReaderTurnPreferences.toIndex(turnStyle));
        form.addView(animation,Ui.lp(this,-1,46));
        form.addView(Ui.text(this,L10n.t("페이지 넘김 속도"),14,Ui.TEXT,true));
        Spinner animationSpeed=new Spinner(this);
        animationSpeed.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                ReaderTurnPreferences.SPEEDS));
        animationSpeed.setSelection(slowTurn?1:0);
        form.addView(animationSpeed,Ui.lp(this,-1,46));
        CheckBox foldSpread=new CheckBox(this);
        foldSpread.setText("폴드 펼침 화면에서 양쪽 두 페이지");
        foldSpread.setChecked(foldColumns);
        form.addView(foldSpread);
        form.addView(Ui.text(this,"글꼴",14,Ui.TEXT,true));
        Spinner fontSelect=new Spinner(this);
        fontSelect.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"명조 · 책 본문","고딕 · 선명한 글씨","고정폭"}));
        fontSelect.setSelection(fontIndex);
        form.addView(fontSelect,Ui.lp(this,-1,46));
        SeekBar size=ReaderNumericSlider.add(this,form,
                "글씨 크기 · 12~38pt",12,38,fontSize,ReaderNumericSlider::points);
        SeekBar spacing=ReaderNumericSlider.add(this,form,
                "줄간격 · 1.0~2.4배",100,240,spacingPercent,
                ReaderNumericSlider::spacing);
        SeekBar margins=ReaderNumericSlider.add(this,form,
                "좌우 여백 · 9~44dp",9,44,margin,ReaderNumericSlider::dp);
        form.addView(Ui.text(this,"책 배경",14,Ui.TEXT,true));
        Spinner background=new Spinner(this);
        background.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                new String[]{"아이보리 종이","흰 종이","오래된 세피아 종이","야간 독서"}));
        background.setSelection(themeIndex);
        form.addView(background,Ui.lp(this,-1,46));
        ScrollView optionsScroll=new ScrollView(this);
        optionsScroll.addView(form);
        new AlertDialog.Builder(this).setTitle("텍스트책 보기 설정")
                .setView(optionsScroll).setNegativeButton(L10n.t("취소"),null)
                .setPositiveButton(L10n.t("적용"),(dialog,which)->{
                    pageTransitions.cancel();
                    int oldMode=displayMode,oldTurn=turnStyle,oldFont=fontIndex;
                    int oldSize=fontSize,oldSpacing=spacingPercent,oldMargin=margin;
                    int oldTheme=themeIndex;
                    boolean oldSlow=slowTurn,oldFold=foldColumns;
                    displayMode=readingMode.getSelectedItemPosition();
                    turnStyle=ReaderTurnPreferences.fromIndex(
                            animation.getSelectedItemPosition());
                    slowTurn=animationSpeed.getSelectedItemPosition()==1;
                    // Save this directory's overrides, NOT the app defaults.
                    foldColumns=foldSpread.isChecked();
                    fontIndex=fontSelect.getSelectedItemPosition();
                    fontSize=size.getProgress()+12;
                    spacingPercent=spacing.getProgress()+100;
                    margin=margins.getProgress()+9;
                    themeIndex=background.getSelectedItemPosition();
                    pageTransitions.configure(turnStyle,slowTurn,
                            backgrounds[themeIndex]);
                    if(oldFont!=fontIndex)textSave("font",fontIndex);
                    if(oldSize!=fontSize)textSave("size",fontSize);
                    if(oldSpacing!=spacingPercent)
                        textSave("spacing",spacingPercent);
                    if(oldMargin!=margin)textSave("margin",margin);
                    if(oldTheme!=themeIndex)textSave("theme",themeIndex);
                    if(oldMode!=displayMode)textSave("view_mode",displayMode);
                    if(oldTurn!=turnStyle)textSave("turn_effect",turnStyle);
                    if(oldSlow!=slowTurn)textSave("slow_page_turn",slowTurn);
                    if(oldFold!=foldColumns)
                        textSave("fold_columns",foldColumns);
                    applyStyle();
                    useReadingMode();
                }).setNeutralButton(L10n.t("폴더 설정 초기화"),(dialog,which)->{
                    FolderMediaSettings.clear(this,FolderMediaSettings.TEXT,
                            folderSettingsScope,"font","size","spacing",
                            "margin","theme","view_mode","turn_effect",
                            "slow_page_turn","fold_columns");
                    recreate();
                }).show();
    }
    @Override protected void onResume(){
        super.onResume();
        turnStyle=textInt("turn_effect",ReaderDefaultSettings.turn(this,
                FolderMediaSettings.TEXT));
        slowTurn=textBool("slow_page_turn",ReaderDefaultSettings.slow(this,
                FolderMediaSettings.TEXT));
        if(pageTransitions!=null)pageTransitions.configure(
                turnStyle,slowTurn,backgrounds[themeIndex]);
    }
    @Override protected void onDestroy(){
        pageTransitions.cancel();
        layoutGeneration++;worker.shutdownNow();
        pageIndexGeneration++;
        if(pageIndexTask!=null)pageIndexTask.cancel(true);
        pageIndexWorker.shutdownNow();
        if(largeBook!=null)try{largeBook.close();}catch(Exception ignored){}
        super.onDestroy();
    }
    @Override protected void onPause(){
        pageTransitions.cancel();
        // Preserve the exact scroll location before an unfolding/rotation
        // recreation rebuilds the reading surface at a different width.
        if(displayMode==1 && !restoringScroll)updateScrollProgress();
        super.onPause();
    }

    private final class ReadingPage extends TextView {
        private final GestureDetector tapper;
        private final ScaleGestureDetector scaler;
        private float startX,startY;
        private float accumulatedPinch=1f;
        private boolean multi;
        ReadingPage(Activity activity){
            super(activity);
            tapper=new GestureDetector(activity,new GestureDetector.SimpleOnGestureListener(){
                @Override public boolean onDown(MotionEvent e){return true;}
                @Override public boolean onSingleTapConfirmed(MotionEvent e){
                    float x=e.getX()/Math.max(1,getWidth());
                    if(x<.27f)move(-1);
                    else if(x>.73f)move(1);
                    else toggleChrome();
                    return true;
                }
            });
            scaler=new ScaleGestureDetector(activity,
                    new ScaleGestureDetector.SimpleOnScaleGestureListener(){
                        @Override public boolean onScaleBegin(ScaleGestureDetector detector){
                            accumulatedPinch=1f;
                            return true;
                        }
                        @Override public boolean onScale(ScaleGestureDetector event){
                            accumulatedPinch*=event.getScaleFactor();
                            return true;
                        }
                        @Override public void onScaleEnd(ScaleGestureDetector event){
                            int delta=Math.round((accumulatedPinch-1f)*8f);
                            if(delta==0)return;
                            fontSize=Math.max(12,Math.min(38,fontSize+delta));
                            textSave("size",fontSize);
                            applyStyle();
                            post(BookTextActivity.this::paginate);
                        }
                    });
        }
        @Override public boolean onTouchEvent(MotionEvent e){
            scaler.onTouchEvent(e);
            tapper.onTouchEvent(e);
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    startX=e.getX();startY=e.getY();multi=false;return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    multi=true;
                    if(pageTransitions.fingerActive())pageTransitions.cancel();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(e.getPointerCount()>1)multi=true;
                    if(!multi&&pageTransitions.fingerActive()){
                        pageTransitions.updateFinger(getLeft()+e.getX(),
                                getTop()+e.getY());
                        return true;
                    }
                    if(!multi&&!scaler.isInProgress()&&
                            turnStyle==EpubTurnGeometry.CURL&&
                            ReaderDragPhysics.shouldStart(
                                    e.getX()-startX,e.getY()-startY,dp(13))){
                        int direction=e.getX()<startX?1:-1;
                        if(startTextFold(direction,getLeft()+startX,
                                getTop()+startY)){
                            pageTransitions.updateFinger(getLeft()+e.getX(),
                                    getTop()+e.getY());
                            return true;
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if(pageTransitions.fingerActive()){
                        pageTransitions.updateFinger(getLeft()+e.getX(),
                                getTop()+e.getY());
                        boolean quick=e.getEventTime()-e.getDownTime()<230 &&
                                Math.abs(e.getX()-startX)>dp(75);
                        pageTransitions.releaseFinger(quick);
                        return true;
                    }
                    if(!multi){
                        float dx=e.getX()-startX,dy=e.getY()-startY;
                        if(Math.abs(dx)>dp(65)&&Math.abs(dx)>Math.abs(dy)*1.4f)
                            move(dx<0?1:-1);
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if(pageTransitions.fingerActive()){
                        pageTransitions.updateFinger(getLeft()+startX,
                                getTop()+startY);
                        pageTransitions.releaseFinger(false);
                    }
                    return true;
            }
            return true;
        }
    }
}
