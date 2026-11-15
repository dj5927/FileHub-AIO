package com.nasmount.mobile;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * PDF document backend for the existing EPUB reading screen.
 * EPUB and PDF share the reading chrome/page transitions, but a PDF still
 * needs its own renderer because it is not an EPUB XHTML archive.
 *
 * All open/render/close calls must run on the EPUB reader's single worker.
 */
final class PdfEpubPageSource implements Closeable {
    static final long MAX_STAGED_PDF_BYTES=512L*1024*1024;
    private final PdfRenderer pdf;
    private final ParcelFileDescriptor fd;
    private final File temporary;
    private boolean closed;

    private PdfEpubPageSource(PdfRenderer pdf,ParcelFileDescriptor fd,
                              File temporary){
        this.pdf=pdf;
        this.fd=fd;
        this.temporary=temporary;
    }

    static PdfEpubPageSource open(Activity context,Intent request)throws Exception{
        File staged=null;
        ParcelFileDescriptor fileDescriptor=null;
        try {
            String kind=request.getStringExtra(ReaderSource.KIND);
            String local=request.getStringExtra(ReaderSource.PATH);
            if("local".equals(kind) && local!=null){
                fileDescriptor=ParcelFileDescriptor.open(
                        new File(local),ParcelFileDescriptor.MODE_READ_ONLY);
            }else{
                // SAF providers may return a non-seekable descriptor.
                // Copy only to the app's private temporary storage.
                staged=ReaderSource.stage(context,request,MAX_STAGED_PDF_BYTES);
                fileDescriptor=ParcelFileDescriptor.open(
                        staged,ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if(fileDescriptor==null)throw new IOException("PDF를 열 수 없습니다.");
            PdfRenderer reader=new PdfRenderer(fileDescriptor);
            if(reader.getPageCount()<1)throw new IOException("PDF 페이지가 없습니다.");
            return new PdfEpubPageSource(reader,fileDescriptor,staged);
        }catch(Exception error){
            if(fileDescriptor!=null){
                try{fileDescriptor.close();}catch(IOException ignored){}
            }
            if(staged!=null)staged.delete();
            throw error;
        }
    }

    int pageCount(){return pdf.getPageCount();}

    byte[] renderPng(int index,int viewportWidth,int zoomPercent)throws IOException{
        if(closed)throw new IOException("닫힌 PDF 문서");
        int page=PdfPageSizing.page(index,pageCount());
        Bitmap frame=null;
        try(PdfRenderer.Page source=pdf.openPage(page)){
            int[] dimensions=PdfPageSizing.fit(source.getWidth(),
                    source.getHeight(),Math.max(280,viewportWidth),
                    zoomPercent/100f);
            frame=Bitmap.createBitmap(dimensions[0],dimensions[1],
                    Bitmap.Config.ARGB_8888);
            frame.eraseColor(Color.WHITE);
            source.render(frame,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            ByteArrayOutputStream output=new ByteArrayOutputStream(128*1024);
            if(!frame.compress(Bitmap.CompressFormat.PNG,100,output))
                throw new IOException("PDF 이미지 렌더링 실패");
            return output.toByteArray();
        }catch(OutOfMemoryError tooLarge){
            throw new IOException("PDF 페이지 메모리 부족",tooLarge);
        }finally {
            if(frame!=null)frame.recycle();
        }
    }

    static String pageHtml(int zoomPercent){
        int zoom=Math.max(75,Math.min(250,zoomPercent));
        // At 100% fit one PDF sheet within the reader viewport and centre
        // it like a printed book. Zoomed pages can scroll in both directions.
        String imageStyle=zoom==100?
                "max-width:100vw;max-height:100vh;max-height:100dvh;"+
                        "width:auto;height:auto;":
                "width:"+zoom+"vw;height:auto;max-width:none;max-height:none;";
        String bodyLayout=zoom==100?
                "display:flex;align-items:center;justify-content:center;":
                "display:block;";
        return "<!doctype html><html lang='ko'><head>"+
                "<meta name='viewport' content='width=device-width, initial-scale=1.0, user-scalable=no'>"+
                "<meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; img-src 'self'; style-src 'unsafe-inline'\">"+
                "<style>*{box-sizing:border-box}html,body{padding:0;margin:0;"+
                "width:100%;height:100%;background:#171b20}"+
                "body{min-height:100vh;overflow:auto;"+bodyLayout+"}"+
                "img{display:block;margin:auto;object-fit:contain;flex-shrink:0;"+
                imageStyle+"}</style></head>"+
                "<body><img alt='PDF 책 페이지' src='/pdf-current-page.png'></body></html>";
    }

    @Override public void close()throws IOException {
        if(closed)return;
        closed=true;
        try{pdf.close();}
        finally{
            try{fd.close();}
            finally{if(temporary!=null)temporary.delete();}
        }
    }
}
