package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native PDF page reader: never interprets PDF as plain text.
 * Local/SAF documents open from their file descriptor; remote documents
 * are staged in private cache on a worker and deleted when reading ends.
 */
public final class PdfBookActivity extends Activity {
    private static final long MAX_REMOTE_BYTES=512L*1024*1024;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicInteger generation=new AtomicInteger();
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private File temporary;
    private Bitmap picture;
    private ImageView image;
    private TextView count;
    private TextView status;
    private TextView heading;
    private SeekBar pages;
    private HorizontalScrollView horizontal;
    private ScrollView vertical;
    private String historyId;
    private int currentPage,pageCount,zoomPercent=100;
    private boolean updatingPageBar;
    private volatile boolean closing;

    @Override public void onCreate(Bundle state){
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        String filename=getIntent().getStringExtra(ReaderSource.NAME);
        if(filename==null || !filename.toLowerCase(java.util.Locale.ROOT)
                .endsWith(".pdf")){finish();return;}
        historyId=ReaderHistory.id(getIntent());
        currentPage=ReaderHistory.position(this,historyId,0);
        buildScreen(filename);
        loadDocument();
    }

    private void buildScreen(String filename){
        LinearLayout root=Ui.vertical(this);
        root.setBackgroundColor(0xFF0C1014);
        root.setPadding(0,Ui.dp(this,25),0,0);
        setContentView(root);

        LinearLayout toolbar=Ui.horizontal(this);
        toolbar.setPadding(Ui.dp(this,10),0,Ui.dp(this,8),0);
        toolbar.setBackgroundColor(0xFF10151B);
        root.addView(toolbar,Ui.lp(this,-1,53));
        TextView back=Ui.text(this,"‹",32,Color.WHITE,true);
        back.setGravity(Gravity.CENTER);
        toolbar.addView(back,Ui.lp(this,44,-1));
        back.setOnClickListener(v->finish());
        heading=Ui.text(this,filename,15,Color.WHITE,true);
        heading.setMaxLines(1);
        heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        toolbar.addView(heading,new LinearLayout.LayoutParams(0,-1,1));
        TextView zoomDown=Ui.action(this,"−");
        toolbar.addView(zoomDown,Ui.lp(this,39,39));
        zoomDown.setOnClickListener(v->setZoom(zoomPercent-25));
        TextView zoomUp=Ui.action(this,"＋");
        LinearLayout.LayoutParams plus=Ui.lp(this,39,39);
        plus.leftMargin=Ui.dp(this,7);
        toolbar.addView(zoomUp,plus);
        zoomUp.setOnClickListener(v->setZoom(zoomPercent+25));

        status=Ui.text(this,"PDF 문서를 여는 중...",12,0xFFA4B0BB,false);
        status.setGravity(Gravity.CENTER);
        root.addView(status,Ui.lp(this,-1,28));

        horizontal=new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        horizontal.setHorizontalScrollBarEnabled(false);
        root.addView(horizontal,new LinearLayout.LayoutParams(-1,0,1));
        vertical=new ScrollView(this);
        vertical.setFillViewport(true);
        horizontal.addView(vertical,new android.widget.FrameLayout.LayoutParams(-1,-2));
        image=new ImageView(this);
        image.setBackgroundColor(0xFFF2F2F2);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setAdjustViewBounds(true);
        image.setMinimumHeight(Ui.dp(this,200));
        vertical.addView(image,new android.widget.FrameLayout.LayoutParams(-1,-2));

        LinearLayout pager=Ui.horizontal(this);
        pager.setPadding(Ui.dp(this,8),Ui.dp(this,4),
                Ui.dp(this,8),Ui.dp(this,6));
        pager.setBackgroundColor(0xFF10151B);
        root.addView(pager,Ui.lp(this,-1,56));
        TextView prev=Ui.action(this,"❮");
        pager.addView(prev,Ui.lp(this,42,45));
        prev.setOnClickListener(v->selectPage(currentPage-1));
        count=Ui.text(this,"- / -",14,0xFFE5EDF3,true);
        count.setGravity(Gravity.CENTER);
        pager.addView(count,new LinearLayout.LayoutParams(0,-1,1));
        TextView next=Ui.action(this,"❯");
        pager.addView(next,Ui.lp(this,42,45));
        next.setOnClickListener(v->selectPage(currentPage+1));

        pages=new SeekBar(this);
        pages.setEnabled(false);
        root.addView(pages,Ui.lp(this,-1,35));
        pages.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onProgressChanged(SeekBar b,int value,boolean fromUser){
                if(fromUser && !updatingPageBar && pageCount>0)
                    count.setText((value+1)+" / "+pageCount+"쪽");
            }
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){
                if(!updatingPageBar)selectPage(b.getProgress());
            }
        });
    }

    private void setZoom(int value){
        int next=Math.max(75,Math.min(250,value));
        if(next==zoomPercent)return;
        zoomPercent=next;
        if(renderer!=null)drawPage();
    }

    private void loadDocument(){
        worker.execute(()->{
            File staged=null;
            ParcelFileDescriptor fd=null;
            PdfRenderer opened=null;
            try{
                Intent request=getIntent();
                String kind=request.getStringExtra(ReaderSource.KIND);
                if("local".equals(kind)){
                    fd=ParcelFileDescriptor.open(new File(
                            request.getStringExtra(ReaderSource.PATH)),
                            ParcelFileDescriptor.MODE_READ_ONLY);
                }else if("saf".equals(kind)){
                    fd=getContentResolver().openFileDescriptor(
                            Uri.parse(request.getStringExtra(ReaderSource.URI)),"r");
                }else{
                    staged=ReaderSource.stage(this,request,MAX_REMOTE_BYTES);
                    fd=ParcelFileDescriptor.open(staged,
                            ParcelFileDescriptor.MODE_READ_ONLY);
                }
                if(fd==null)throw new IOException("PDF 파일을 읽을 수 없습니다.");
                opened=new PdfRenderer(fd);
                int total=opened.getPageCount();
                if(total<1)throw new IOException("페이지가 없는 PDF입니다.");
                if(closing){opened.close();fd.close();if(staged!=null)staged.delete();return;}
                renderer=opened;descriptor=fd;temporary=staged;
                runOnUiThread(()->{
                    if(closing || isFinishing())return;
                    pageCount=total;
                    currentPage=PdfPageSizing.page(currentPage,total);
                    pages.setMax(total-1);
                    pages.setEnabled(total>1);
                    drawPage();
                });
            }catch(Exception error){
                if(opened!=null){try{opened.close();}catch(Exception ignored){}}
                if(fd!=null){try{fd.close();}catch(Exception ignored){}}
                if(staged!=null)staged.delete();
                runOnUiThread(()->{
                    if(closing || isFinishing())return;
                    new AlertDialog.Builder(this)
                            .setTitle("PDF 열기 실패")
                            .setMessage("PDF가 손상됐거나 암호가 설정됐을 수 있습니다.\n"+
                                    "파일 읽기 권한과 네트워크 연결도 확인해 주세요.")
                            .setPositiveButton("확인",(dialog,which)->finish())
                            .show();
                });
            }
        });
    }

    private void selectPage(int desired){
        if(renderer==null || pageCount<1)return;
        int selected=PdfPageSizing.page(desired,pageCount);
        if(selected==currentPage && picture!=null)return;
        currentPage=selected;
        saveProgress();
        drawPage();
    }

    private void saveProgress(){
        if(historyId!=null && pageCount>0)
            ReaderHistory.save(this,historyId,currentPage,currentPage+1,pageCount);
    }

    private void drawPage(){
        if(renderer==null || pageCount<1 || closing)return;
        final int requestedPage=currentPage;
        final int requestedZoom=zoomPercent;
        final int task=generation.incrementAndGet();
        count.setText((requestedPage+1)+" / "+pageCount+"쪽");
        status.setText("페이지 준비 중... · "+requestedZoom+"%");
        updatingPageBar=true;
        pages.setProgress(requestedPage);
        updatingPageBar=false;
        final int viewport=Math.max(Ui.dp(this,280),getResources()
                .getDisplayMetrics().widthPixels-Ui.dp(this,12));
        worker.execute(()->{
            Bitmap bitmap=null;
            try(PdfRenderer.Page page=renderer.openPage(requestedPage)){
                int[] size=PdfPageSizing.fit(
                        page.getWidth(),page.getHeight(),viewport,requestedZoom/100f);
                bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                final Bitmap ready=bitmap;
                runOnUiThread(()->{
                    if(closing || isFinishing() || task!=generation.get()){
                        ready.recycle();return;
                    }
                    Bitmap previous=picture;
                    picture=ready;
                    image.setImageBitmap(ready);
                    image.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                            size[0],size[1]));
                    if(previous!=null && previous!=ready)previous.recycle();
                    horizontal.scrollTo(0,0);
                    vertical.scrollTo(0,0);
                    status.setText("PDF · "+requestedZoom+"% · "+pageCount+"쪽");
                });
            }catch(Exception failure){
                if(bitmap!=null)bitmap.recycle();
                runOnUiThread(()->{
                    if(closing || task!=generation.get())return;
                    status.setText("페이지 렌더링 실패 · 다시 넘겨보세요.");
                });
            }
        });
    }

    @Override protected void onPause(){
        saveProgress();
        super.onPause();
    }

    @Override protected void onDestroy(){
        closing=true;
        generation.incrementAndGet();
        worker.execute(()->{
            if(renderer!=null){try{renderer.close();}catch(Exception ignored){}}
            if(descriptor!=null){try{descriptor.close();}catch(Exception ignored){}}
            if(temporary!=null)temporary.delete();
        });
        worker.shutdown();
        image.setImageDrawable(null);
        if(picture!=null){picture.recycle();picture=null;}
        super.onDestroy();
    }
}
