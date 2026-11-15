package com.nasmount.mobile;

/** Match main and expanded drawer exactly at the system inset edges. */
final class FileHubBrandLayout {
    static final int HEADER_HEIGHT_DP=65;
    static final int LEADING_SLOT_WIDTH_DP=40;
    private FileHubBrandLayout(){}

    /** Position a fixed-size child in the common 65dp header row. */
    static float centeredTopDp(float childHeightDp){
        return (HEADER_HEIGHT_DP-childHeightDp)/2f;
    }

    /** Mirror Android ImageView FIT_CENTER's intrinsic bitmap placement. */
    static float visibleImageCenterDp(float viewWidthDp,float viewHeightDp,
                                      float bitmapWidth,float bitmapHeight,
                                      float alphaTop,float alphaBottom){
        float scale=Math.min(viewWidthDp/bitmapWidth,viewHeightDp/bitmapHeight);
        float bitmapTop=(viewHeightDp-bitmapHeight*scale)/2f;
        return centeredTopDp(viewHeightDp)+bitmapTop+
                ((alphaTop+alphaBottom)/2f)*scale;
    }

    static int wordmarkWidthDp(int screenDp){
        // The v0.1.62 180dp wordmark filled almost the entire title row.
        // Half-size (90dp) fits all Fold postures; the asset has its own
        // transparent padding to protect the reconstructed final "O".
        return Math.max(60, Math.min(90, screenDp-106));
    }
    static int drawerWordmarkWidthDp(int drawerDp){
        // Half of the former 174dp drawer wordmark. The icon is now 30dp.
        return Math.max(60, Math.min(87, drawerDp-50));
    }

    static int drawerTopPx(int statusBarInsetPx){
        return Math.max(0,statusBarInsetPx);
    }
    static int drawerBottomPx(int navInsetPx,int actualSafeBottomPx){
        return Math.max(0, Math.max(navInsetPx,actualSafeBottomPx));
    }
}
