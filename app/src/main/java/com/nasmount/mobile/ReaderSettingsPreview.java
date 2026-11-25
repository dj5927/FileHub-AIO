package com.nasmount.mobile;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Non-persistent sample for settings dialogs and the global reader tabs.
 * Pending edits affect this preview only until the user presses Apply.
 */
final class ReaderSettingsPreview extends LinearLayout {
    private static final int[] TXT_PAPER={
            0xFFF8F1E2,Color.WHITE,0xFFE8D7B3,0xFF121719};
    private static final int[] TXT_INK={
            0xFF26231E,0xFF202124,0xFF3C3020,0xFFE3E7E5};
    private static final int[] EPUB_PAPER={
            0xFFF8F1E4,Color.WHITE,0xFFDFD2B2,0xFF161A1C};
    private static final int[] EPUB_INK={
            0xFF29251F,0xFF202328,0xFF3C2F20,0xFFE5E9E8};
    private static final String SAMPLE_TEXT=
            "책장을 넘기면 새로운 이야기가 시작됩니다.\n"+
            "따뜻한 오후, 창가에 앉아 조용히 책을 읽었습니다.";

    private final Activity activity;
    private final String category;
    private final Map<String,Integer> temporary=new HashMap<>();
    private final LinearLayout sampleSurface;
    private final TextView sampleText;
    private final ImageView sampleComic;
    private final TextView summary;

    ReaderSettingsPreview(Activity activity,String category) {
        super(activity);
        this.activity=activity;
        this.category=category;
        setOrientation(VERTICAL);
        setPadding(px(12),px(10),px(12),px(12));
        setBackground(Ui.bg(activity,Ui.SURFACE,12,Ui.BORDER));
        TextView header=Ui.text(activity,L10n.t("실시간 미리보기"),13,Ui.TEAL,true);
        addView(header,new LayoutParams(-1,px(26)));
        sampleSurface=new LinearLayout(activity);
        sampleSurface.setOrientation(VERTICAL);
        sampleSurface.setGravity(Gravity.CENTER_VERTICAL);
        addView(sampleSurface,new LayoutParams(-1,-2));
        if(FolderMediaSettings.COMIC.equals(category)){
            sampleComic=new ImageView(activity);
            sampleComic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            sampleComic.setImageBitmap(comicIllustration());
            sampleSurface.addView(sampleComic,new LayoutParams(-1,px(145)));
            sampleText=null;
        }else{
            sampleComic=null;
            sampleText=Ui.text(activity,SAMPLE_TEXT,19,TXT_INK[0],false);
            sampleText.setGravity(Gravity.TOP);
            sampleText.setMaxLines(4);
            sampleText.setIncludeFontPadding(false);
            sampleSurface.addView(sampleText,new LayoutParams(-1,-2));
        }
        summary=Ui.text(activity,"",11,Ui.MUTED,false);
        LayoutParams notes=new LayoutParams(-1,-2);
        notes.topMargin=px(8);
        addView(summary,notes);
        renderPreview();
    }

    void change(String key,int candidate) {
        temporary.put(key,candidate);
        renderPreview();
    }

    private int px(int dp){return Ui.dp(activity,dp);}
    private int integer(String key,int fallback){
        Integer change=temporary.get(key);
        return change!=null?change:
                ReaderDefaultSettings.integer(activity,category,key,fallback);
    }
    private boolean checked(String key,boolean fallback) {
        Integer change=temporary.get(key);
        return change!=null?change==1:
                ReaderDefaultSettings.bool(activity,category,key,fallback);
    }
    private static int bound(int number,int min,int max) {
        return Math.max(min,Math.min(max,number));
    }

    private void renderPreview(){
        if(sampleText!=null){
            boolean epub=FolderMediaSettings.EPUB.equals(category);
            int theme=bound(integer("theme",0),0,3);
            int[] paper=epub?EPUB_PAPER:TXT_PAPER;
            int[] ink=epub?EPUB_INK:TXT_INK;
            int size=bound(integer(epub?"font_size":"size",epub?19:20),
                    12,epub?36:38);
            int line=bound(integer(epub?"line_spacing":"spacing",
                    epub?155:145),epub?110:100,epub?230:240);
            int margin=bound(integer(epub?"side_margin":"margin",
                    epub?18:19),epub?8:9,epub?50:44);
            int typeface=epub?0:bound(integer("font",0),0,2);
            sampleSurface.setBackground(Ui.bg(activity,paper[theme],8,0));
            sampleText.setTextColor(ink[theme]);
            sampleText.setTypeface(typeface==1?Typeface.SANS_SERIF:
                    typeface==2?Typeface.MONOSPACE:Typeface.SERIF);
            if(epub)sampleText.setTextSize(TypedValue.COMPLEX_UNIT_DIP,size);
            else sampleText.setTextSize(TypedValue.COMPLEX_UNIT_SP,size);
            sampleText.setLineSpacing(0,line/100f);
            sampleText.setPadding(px(margin),px(12),px(margin),px(12));
            String state=integer(epub?"mode":"view_mode",0)==0?
                    "페이지 넘김":"세로 스크롤";
            String effectKey=epub?"page_turn_effect":"turn_effect";
            int rawStyle=integer(effectKey,
                    ReaderTurnPreferences.style(activity));
            int effectIndex=temporary.containsKey(effectKey)?
                    bound(rawStyle,0,3):ReaderTurnPreferences.toIndex(rawStyle);
            boolean slow=checked("slow_page_turn",
                    ReaderDefaultSettings.slow(activity,category));
            summary.setText(state+" · "+size+"sp · "+
                    String.format(Locale.KOREA,"%.2f배",line/100.0)+
                    " · 여백 "+margin+"dp"+
                    "\n넘김: "+ReaderTurnPreferences.STYLES[effectIndex]+
                    " · "+(slow?"천천히":"자연스럽게")+
                    (!epub?(checked("fold_columns",true)?
                            " · 폴드 두 페이지":" · 폴드 한 페이지"):"")+
                    "\n예시 문단이며 책의 실제 페이지 수는 변경되지 않습니다.");
        }else{
            int contrast=bound(integer("contrast",100),50,200);
            int brightness=bound(integer("brightness",0),-100,100);
            boolean gray=checked("grayscale",false);
            ColorMatrix matrix=new ColorMatrix();
            if(gray)matrix.setSaturation(0f);
            float factor=contrast/100f;
            float offset=128f*(1-factor)+brightness;
            ColorMatrix adjust=new ColorMatrix(new float[]{
                    factor,0,0,0,offset,
                    0,factor,0,0,offset,
                    0,0,factor,0,offset,
                    0,0,0,1,0
            });
            matrix.postConcat(adjust);
            sampleComic.setColorFilter(new ColorMatrixColorFilter(matrix));
            sampleSurface.setBackgroundColor(0xFFEBEBEB);
            summary.setText(L10n.t("명암 ")+contrast+"% · 밝기 "+
                    (brightness>0?"+":"")+brightness+" · "+
                    (gray?"흑백":"컬러")+" · "+
                    (checked("rtl",false)?"일본식":"한국식")+
                    (checked("fit_width",false)?" · 화면 폭 맞춤":" · 전체 맞춤")+
                    (checked("fold_two_pages",true)?" · 펼침 두 페이지":" · 펼침 한 페이지")+
                    "\n합본 자동 분할: "+new String[]{"자동","끄기","민감"}[
                            bound(integer("double_page_split",0),0,2)]+
                    "\n예시 그림으로 색감과 화면 설정을 확인합니다.");
        }
    }

    /** Original illustration, generated in-app; no user image or network. */
    private static Bitmap comicIllustration(){
        Bitmap bitmap=Bitmap.createBitmap(460,210,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG);
        brush.setColor(Color.WHITE);canvas.drawColor(Color.WHITE);
        brush.setColor(0xFF20252B);brush.setStrokeWidth(5);
        brush.setStyle(Paint.Style.STROKE);
        canvas.drawRect(5,5,455,205,brush);
        canvas.drawLine(225,5,225,205,brush);
        brush.setStyle(Paint.Style.FILL);
        brush.setColor(0xFF96D7F5);
        canvas.drawRect(8,8,222,202,brush);
        brush.setColor(0xFFFFD45B);
        canvas.drawCircle(172,50,27,brush);
        brush.setColor(0xFF70BC7E);
        canvas.drawOval(-30,105,260,280,brush);
        brush.setColor(0xFFEEB5A2);
        canvas.drawCircle(112,102,28,brush);
        brush.setColor(0xFF343743);
        canvas.drawCircle(103,99,3,brush);
        canvas.drawCircle(121,99,3,brush);
        brush.setStyle(Paint.Style.STROKE);
        brush.setStrokeWidth(3);
        canvas.drawArc(100,102,123,119,15,150,false,brush);
        brush.setStyle(Paint.Style.FILL);
        brush.setColor(0xFFFFE8A4);
        canvas.drawRect(228,8,452,202,brush);
        brush.setColor(0xFFD9AC8A);
        canvas.drawRoundRect(272,85,406,190,8,8,brush);
        brush.setColor(0xFFF5FAFC);
        canvas.drawRoundRect(300,103,380,174,3,3,brush);
        brush.setColor(0xFF5175AD);
        canvas.drawRect(332,104,346,173,brush);
        canvas.drawRect(300,135,380,146,brush);
        brush.setColor(0xFF55A26B);
        canvas.drawCircle(256,161,24,brush);
        canvas.drawCircle(425,163,26,brush);
        return bitmap;
    }
}
