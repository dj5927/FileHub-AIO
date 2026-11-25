package com.nasmount.mobile;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.view.View;
final class EditionMonetization {
    static void init(Application app){}
    static boolean full(Context c){return true;}
    static View content(Activity a,View root){return root;}
    static void settings(SettingsActivity a){}
    static void showUpgrade(Activity a){}
    static void videoPlaying(Activity a,boolean playing){}
    static void contentBreak(Activity a,Runnable next){next.run();}
    static void pictureInPicture(Activity a,boolean pip){}
}
