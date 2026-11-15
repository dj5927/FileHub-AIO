package com.nasmount.mobile;

/** Screen-independent finger-following paper folding math. */
final class ReaderDragPhysics {
    static final float COMMIT_THRESHOLD=.43f;
    private ReaderDragPhysics(){}
    static float progress(float startX,float fingerX,float width,int visualDirection){
        if(width<=0)return 0;
        // Direction +1: a right-hand sheet is peeled toward the left.
        // Direction -1: a left-hand sheet is peeled toward the right.
        float delta=(startX-fingerX)*(visualDirection>=0?1f:-1f);
        return EpubTurnGeometry.clamp(delta/(width*.86f));
    }
    static float diagonal(float startY,float fingerY,float height){
        if(height<=0)return 0;
        // Negative vertical displacement: bottom corner is pulled up.
        return Math.max(-1f,Math.min(1f,(fingerY-startY)/(height*.52f)));
    }
    static boolean shouldCommit(float progress){
        return progress>=COMMIT_THRESHOLD;
    }
    static boolean shouldStart(float dx,float dy,float slop){
        return Math.hypot(dx,dy)>slop &&
                Math.abs(dx)>=Math.abs(dy)*.40f;
    }
}
