package com.nasmount.mobile;

/** Memory bounded PDF page rasterization: keep each decoded page under 25MB. */
final class PdfPageSizing {
    static final int MAX_EDGE=2400;
    static final long MAX_PIXELS=6_000_000L;
    private PdfPageSizing(){}

    static int[] fit(float pageWidth,float pageHeight,int viewportWidth,float zoom){
        if(!(pageWidth>0)||!(pageHeight>0)||Float.isInfinite(pageWidth)||
                Float.isInfinite(pageHeight)||Float.isNaN(pageWidth)||
                Float.isNaN(pageHeight))
            throw new IllegalArgumentException("PDF 페이지 크기가 잘못됐습니다.");
        float ratio=pageHeight/pageWidth;
        double scale=Math.max(1d,viewportWidth)*Math.max(0.7,Math.min(3.0,zoom));
        double targetW=scale,targetH=scale*ratio;
        double cap=Math.min(1d,Math.min(MAX_EDGE/Math.max(targetW,targetH),
                Math.sqrt(MAX_PIXELS/Math.max(1d,targetW*targetH))));
        return new int[]{Math.max(1,(int)Math.round(targetW*cap)),
                Math.max(1,(int)Math.round(targetH*cap))};
    }

    static int page(int desired,int pageCount){
        return Math.max(0,Math.min(Math.max(1,pageCount)-1,desired));
    }
}
