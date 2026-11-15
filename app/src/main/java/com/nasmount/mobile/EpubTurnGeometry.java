package com.nasmount.mobile;

/** Pure geometry for a right/left paper-edge lift and page curl. */
final class EpubTurnGeometry {
    static final int NONE=0,SLIDE=1,FADE=2,CURL=3;
    private EpubTurnGeometry(){}
    static float clamp(float v){return Math.max(0f,Math.min(1f,v));}
    static float smooth(float v){float x=clamp(v);return x*x*(3f-2f*x);}
    static float bottomEdge(float width,float progress,boolean forward){
        float p=smooth(progress);
        return width*(forward?1f-p:p);
    }
    static float topEdge(float width,float progress,boolean forward){
        float p=smooth(clamp((progress-.16f)/.84f));
        return width*(forward?1f-p:p);
    }
    static float foldWidth(float width,float progress){
        return Math.max(0f,width*.33f*(float)Math.sin(Math.PI*clamp(progress)));
    }
    static int duration(int style,boolean slow){
        int base=style==CURL?540:style==SLIDE?260:style==FADE?210:0;
        return slow?Math.round(base*1.35f):base;
    }
}
