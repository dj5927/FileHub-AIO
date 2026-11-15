package com.nasmount.mobile;

/**
 * Zoom changes the PDF document, never the screen-edge tap area.
 */
final class PdfZoomTouchPolicy {
    static final int EDGE_WIDTH_DP=50;
    private PdfZoomTouchPolicy(){}

    static boolean showFixedEdges(boolean pdfDocument,int zoomPercent){
        return pdfDocument&&zoomPercent>100;
    }
}
