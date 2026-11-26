package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/** Extendable, categorized settings screen for the Android file manager. */
public final class SettingsActivity extends LocalizedActivity {
    static final String EXTRA_CURRENT_SCOPE = "current_folder_scope";
    static final int PICK_CODECS = 701;
    private SettingsContent content;
    @Override public void onCreate(Bundle state){
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Ui.BG));
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=Ui.vertical(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(EditionMonetization.content(this,root));
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0,bars.top,0,bars.bottom);return insets;
        });
        LinearLayout toolbar=Ui.horizontal(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setBackgroundColor(Ui.DARK);
        root.addView(toolbar,Ui.lp(this,-1,56));
        TextView back=Ui.text(this,"‹",28,Color.WHITE,true);back.setGravity(Gravity.CENTER);
        toolbar.addView(back,Ui.lp(this,50,-1));back.setOnClickListener(v->finish());
        toolbar.addView(Ui.text(this,L10n.t("설정"),19,Color.WHITE,true));
        ScrollView scroll=new ScrollView(this);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout page=Ui.vertical(this);
        page.setPadding(Ui.dp(this,15),Ui.dp(this,12),Ui.dp(this,15),Ui.dp(this,24));
        scroll.addView(page,new FrameLayout.LayoutParams(-1,-2));
        content=new SettingsContent(this,page,scroll,getIntent().getStringExtra(EXTRA_CURRENT_SCOPE),
                state==null?0:state.getInt("tab",0),()->{setResult(RESULT_OK);recreate();},null);
    }
    @Override protected void onSaveInstanceState(Bundle state){
        super.onSaveInstanceState(state);if(content!=null)state.putInt("tab",content.selectedTab());
    }
    void render(){if(content!=null)content.render();}
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(content!=null)content.onActivityResult(code,result,data);
    }
    @Override protected void onDestroy(){if(content!=null)content.deactivate();super.onDestroy();}
}
