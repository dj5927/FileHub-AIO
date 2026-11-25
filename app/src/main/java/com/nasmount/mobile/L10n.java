package com.nasmount.mobile;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline UI catalogue. Only authored UI literals are passed here, not file names or keys. */
final class L10n {
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
        return app==null?"ko":language(app.getResources().getConfiguration().getLocales().get(0));
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
