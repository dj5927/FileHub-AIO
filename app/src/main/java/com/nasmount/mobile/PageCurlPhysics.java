package com.nasmount.mobile;

/**
 * Elastic paper fold model in page-local coordinates. A finger at the top
 * moves the top edge first; a finger near the bottom moves the bottom first.
 * The remaining edge is elastically pulled along and catches up at the end.
 * Every geometric value is mirrored for a backward (rightward) page turn.
 */
final class PageCurlPhysics {
    private PageCurlPhysics(){}
    static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
    static float activeGrab(float downY,float diagonal){
        return clamp(downY+Math.max(-1f,Math.min(1f,diagonal))*.24f);
    }
    /** Fold distance as a fraction of page width at a given vertical point. */
    static float distance(float progress,float downY,float diagonal,float y){
        float p=clamp(progress);
        if(p<=0f||p>=1f)return p;
        float grab=activeGrab(downY,diagonal);
        float separation=(float)Math.pow(Math.abs(clamp(y)-grab),.82);
        // The lag grows as the corner first lifts, then vanishes as the
        // whole sheet completes a turn. No hard top/bottom preference.
        float elasticLag=.62f*(float)Math.sin(Math.PI*p)*separation;
        // Multiply rather than subtracting an absolute lag. Every point
        // travels monotonically as the finger advances, so the distant edge
        // never briefly folds backward while the near corner moves forward.
        return clamp(p*(1f-elasticLag));
    }
    /** Left/right mirrored fold x: next peels from right, previous from left. */
    static float edgeX(float width,float progress,float downY,
                       float diagonal,float y,boolean next){
        float d=width*distance(progress,downY,diagonal,y);
        return next?width-d:d;
    }
    /** Receding paper underside is thickest near the dragged point. */
    static float backWidth(float width,float progress,float downY,
                           float diagonal,float y){
        float p=clamp(progress);
        if(p<=0f||p>=1f)return 0f;
        float near=1f-Math.abs(clamp(y)-activeGrab(downY,diagonal));
        float radius=width*.28f*(float)Math.sin(Math.PI*p);
        return radius*(.35f+.65f*near);
    }
}
