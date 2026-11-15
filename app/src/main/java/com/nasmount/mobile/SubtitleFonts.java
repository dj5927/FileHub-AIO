package com.nasmount.mobile;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Only validated, app-owned TTF files are used for subtitle rendering. */
final class SubtitleFonts {
    static final String SELECTED = "subtitle_font_name";
    private static final long MAX_FONT_BYTES = 16L * 1024 * 1024;
    private SubtitleFonts() {}

    private static File directory(Context context) {
        File folder = new File(context.getFilesDir(), "subtitle_fonts");
        if (!folder.exists() && !folder.mkdirs())
            throw new IllegalStateException("글꼴 저장 폴더를 만들 수 없습니다.");
        return folder;
    }

    static List<String> installed(Context context) {
        List<String> names = new ArrayList<>();
        File[] files = directory(context).listFiles();
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName,
                    String.CASE_INSENSITIVE_ORDER));
            for (File file : files) {
                if (file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".ttf")) names.add(file.getName());
            }
        }
        return names;
    }

    static String current(Context context) {
        String saved = PlayerSettings.prefs(context).getString(SELECTED, "");
        return installed(context).contains(saved) ? saved : "";
    }

    static void choose(Context context, String name) {
        if (!name.isEmpty() && !installed(context).contains(name))
            throw new IllegalArgumentException("등록되지 않은 폰트입니다.");
        PlayerSettings.prefs(context).edit().putString(SELECTED, name).apply();
    }

    static Typeface selectedTypeface(Context context) {
        String selected = current(context);
        if (selected.isEmpty()) return Typeface.DEFAULT;
        try {
            return Typeface.createFromFile(new File(directory(context), selected));
        } catch (RuntimeException invalid) {
            return Typeface.DEFAULT;
        }
    }

    static String current(Context context,String folderScope){
        String chosen=FolderMediaSettings.string(context,FolderMediaSettings.VIDEO,
                folderScope,"subtitle_font",current(context));
        return installed(context).contains(chosen)?chosen:"";
    }
    static Typeface selectedTypeface(Context context,String folderScope){
        String name=current(context,folderScope);
        if(name.isEmpty())return Typeface.DEFAULT;
        try{return Typeface.createFromFile(new File(directory(context),name));}
        catch(RuntimeException unavailable){return Typeface.DEFAULT;}
    }
    static void setFolderFont(Context context,String folderScope,String name){
        if(name!=null&&!name.isEmpty()&&!installed(context).contains(name))
            throw new IllegalArgumentException("등록되지 않은 폰트입니다.");
        FolderMediaSettings.stringSet(context,FolderMediaSettings.VIDEO,
                folderScope,"subtitle_font",name);
    }
    static void resetFolderFont(Context context,String folderScope){
        FolderMediaSettings.remove(context,FolderMediaSettings.VIDEO,
                folderScope,"subtitle_font");
    }
    static String importTtf(Context context, Uri source, String requestedName)
            throws IOException {
        if (requestedName == null ||
                !requestedName.toLowerCase(java.util.Locale.ROOT).endsWith(".ttf"))
            throw new IOException("TTF 글꼴만 가져올 수 있습니다.");
        String safeName = requestedName.replaceAll("[^\\p{L}\\p{N}._ -]", "_")
                .replaceAll("^\\.+", "_");
        if (safeName.isEmpty() || safeName.length() > 110)
            safeName = "subtitle_font.ttf";
        File dir = directory(context);
        File target = new File(dir, safeName);
        int suffix = 2;
        while (target.exists()) {
            String base = safeName.substring(0, safeName.length() - 4);
            target = new File(dir, base + " (" + (suffix++) + ").ttf");
        }
        File temporary = File.createTempFile("font_", ".tmp", dir);
        boolean finished = false;
        try {
            try (InputStream input = context.getContentResolver().openInputStream(source);
                 FileOutputStream out = new FileOutputStream(temporary)) {
                if (input == null) throw new IOException("폰트를 읽을 수 없습니다.");
                byte[] block = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(block)) != -1) {
                    total += count;
                    if (total > MAX_FONT_BYTES)
                        throw new IOException("폰트 파일은 최대 16MB까지 지원합니다.");
                    out.write(block, 0, count);
                }
                if (total < 16) throw new IOException("TTF 파일이 너무 작습니다.");
            }
            try (java.io.FileInputStream verify =
                         new java.io.FileInputStream(temporary)) {
                byte[] header = new byte[4];
                if (verify.read(header) != 4)
                    throw new IOException("TTF 헤더 오류");
                boolean sfnt = header[0] == 0 && header[1] == 1
                        && header[2] == 0 && header[3] == 0;
                boolean trueType = header[0] == 't' && header[1] == 'r'
                        && header[2] == 'u' && header[3] == 'e';
                if (!sfnt && !trueType)
                    throw new IOException("TrueType(TTF) 헤더가 아닙니다.");
            }
            try {
                Typeface.createFromFile(temporary);
            } catch (RuntimeException error) {
                throw new IOException("Android에서 읽을 수 없는 글꼴입니다.", error);
            }
            if (!temporary.renameTo(target))
                throw new IOException("글꼴 저장에 실패했습니다.");
            finished = true;
            return target.getName();
        } finally {
            if (!finished) temporary.delete();
        }
    }
}
