package com.nasmount.mobile;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline UI catalogue. Only authored UI literals are passed here, not file names or keys. */
final class L10n {
    private static final String PREFS="filehub_interface_language";
    private static final String KEY="language";
    static final String SYSTEM="system";
    private static final String[] MODES={SYSTEM,"ko","en","ja","zh"};
    private static final String[] NAMES={"시스템 언어에 맞춤","한국어","English","日本語","简体中文"};
    private static volatile Context context;
    private static volatile Map<String,String[]> catalog;
    private L10n() {}
    static void init(Context app) { context=app.getApplicationContext(); }
    static String language(Locale locale) {
        if(locale==null)return "en";
        switch(locale.getLanguage()) {
            case "ko": return "ko";
            case "ja": return "ja";
            case "zh": return "zh";
            default: return "en";
        }
    }
    static String language() {
        Context app=context;
        return app==null?"ko":resolve(mode(app),deviceLocale());
    }
    static String normalizeMode(String mode) {
        for(String value:MODES)if(value.equals(mode))return value;
        return SYSTEM;
    }
    static String resolve(String mode,Locale device) {
        String value=normalizeMode(mode);
        return SYSTEM.equals(value)?language(device):value;
    }
    private static Locale deviceLocale() {
        android.os.LocaleList locales=Resources.getSystem().getConfiguration().getLocales();
        return locales.isEmpty()?Locale.ENGLISH:locales.get(0);
    }
    static String mode(Context c) {
        return normalizeMode(c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).getString(KEY,SYSTEM));
    }
    static int selectedIndex(Context c) {
        String value=mode(c);
        for(int i=0;i<MODES.length;i++)if(MODES[i].equals(value))return i;
        return 0;
    }
    static String[] choices() {
        String[] result=NAMES.clone();
        result[0]=t(result[0]);
        return result;
    }
    static boolean choose(Context c,int index) {
        if(index<0||index>=MODES.length)return false;
        return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY,MODES[index]).commit();
    }
    static Context localizedContext(Context base) {
        String value=resolve(mode(base),deviceLocale());
        Locale locale="zh".equals(value)?Locale.SIMPLIFIED_CHINESE:Locale.forLanguageTag(value);
        // Override only the locale; leave orientation, density and Fold size
        // inherited from the current device configuration.
        Configuration config=new Configuration();
        config.setLocale(locale);
        config.setLayoutDirection(locale);
        return base.createConfigurationContext(config);
    }
    private static synchronized Map<String,String[]> load() {
        if(catalog!=null)return catalog;
        Map<String,String[]> rows=new HashMap<>();
        Context app=context;
        if(app==null)return rows;
        try(BufferedReader in=new BufferedReader(new InputStreamReader(
                app.getAssets().open("locale/ui.tsv"),StandardCharsets.UTF_8))) {
            String line;
            while((line=in.readLine())!=null) {
                if(line.isEmpty()||line.startsWith("#"))continue;
                String[] fields=line.split("\\t",-1);
                if(fields.length!=4)continue;
                for(int i=0;i<fields.length;i++)fields[i]=fields[i].replace("\\n","\n");
                rows.put(fields[0],new String[]{fields[1],fields[2],fields[3]});
            }
        }catch(IOException ignored){}
        catalog=Collections.unmodifiableMap(rows);
        return catalog;
    }
    static String t(String korean) {
        if(korean==null)return null;
        String lang=language();
        if("ko".equals(lang))return korean;
        String[] values=load().get(korean);
        if(values==null)return korean;
        int index="ja".equals(lang)?1:"zh".equals(lang)?2:0;
        return values[index].isEmpty()?values[0]:values[index];
    }
    static String[] labels(String[] labels) {
        String[] result=labels.clone();
        for(int i=0;i<result.length;i++)result[i]=t(result[i]);
        return result;
    }
}
