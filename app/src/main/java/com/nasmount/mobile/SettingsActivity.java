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
public final class SettingsActivity extends Activity {
    static final String EXTRA_CURRENT_SCOPE = "current_folder_scope";
    private static final int PICK_CODECS = 701;
    private static final int PICK_HIDE_FOLDER = 702;
    private static final int PICK_TTF = 703;
    private int DARK = 0xFF202326;
    private int FOREGROUND = 0xFF273139;
    private int MUTED = 0xFF77828C;
    private int TEAL = 0xFF148D82;
    private LinearLayout content;
    private ScrollView settingsScroll;
    private LinearLayout categoryTabs;
    private final Map<String, View> categoryAnchors = new LinkedHashMap<>();
    private static final String[] SETTING_TABS={
            "일반","동영상·자막","만화책","텍스트뷰어"};
    private int selectedTab=0;
    private String openedScope;

    @Override public void onCreate(Bundle state) {
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode ? R.style.AppThemeDark : R.style.AppTheme);
        super.onCreate(state);
        DARK = Ui.DARK;
        FOREGROUND = Ui.TEXT;
        MUTED = Ui.MUTED;
        TEAL = Ui.TEAL;
        openedScope = getIntent().getStringExtra(EXTRA_CURRENT_SCOPE);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode ?
                0 : View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setBackgroundColor(DARK);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView back = label("‹", 30, Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("설정 닫기");
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(52), -1));
        back.setOnClickListener(v -> finish());
        TextView title = label("설정", 19, Color.WHITE);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        toolbar.addView(title);
        HorizontalScrollView categories = new HorizontalScrollView(this);
        categories.setHorizontalScrollBarEnabled(false);
        categories.setFillViewport(true);
        categories.setBackgroundColor(Ui.SURFACE);
        categoryTabs = new LinearLayout(this);
        categoryTabs.setGravity(Gravity.CENTER_VERTICAL);
        categoryTabs.setOrientation(LinearLayout.HORIZONTAL);
        categories.addView(categoryTabs);
        root.addView(categories, new LinearLayout.LayoutParams(-1, dp(43)));
        settingsScroll = new ScrollView(this);
        root.addView(settingsScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(15), dp(14), dp(15), dp(26));
        settingsScroll.addView(content, adaptiveSettingsBounds());
        categoryTabs.setGravity(expandedSettings() ? Gravity.CENTER :
                Gravity.CENTER_VERTICAL);
        render();
    }

    private boolean expandedSettings() {
        Configuration config = getResources().getConfiguration();
        return FoldPlayerLayoutPolicy.isExpanded(
                config.smallestScreenWidthDp, config.screenWidthDp,
                config.screenHeightDp);
    }

    private FrameLayout.LayoutParams adaptiveSettingsBounds() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        if (widthDp <= 0) widthDp = 380;
        int bounded = expandedSettings() ? Math.min(850, widthDp - 48) : widthDp;
        return new FrameLayout.LayoutParams(dp(Math.max(1, bounded)), -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (settingsScroll != null && content != null)
            content.setLayoutParams(adaptiveSettingsBounds());
        if (categoryTabs != null)
            categoryTabs.setGravity(expandedSettings() ? Gravity.CENTER :
                    Gravity.CENTER_VERTICAL);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }
    private TextView label(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private void section(String title, String info) {
        TextView heading = label(title, 17, FOREGROUND);
        heading.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, dp(44));
        p.topMargin = dp(10);
        content.addView(heading, p);
        if (info != null && !info.isEmpty()) note(info);
    }
    private void buildCategoryTabs() {
        categoryTabs.removeAllViews();
        for (int i=0;i<SETTING_TABS.length;i++) {
            final int selected=i;
            String category=SETTING_TABS[i];
            boolean active=i==selectedTab;
            TextView item = label(category, 13, active?Color.WHITE:TEAL);
            item.setGravity(Gravity.CENTER);
            item.setPadding(dp(14), 0, dp(14), 0);
            item.setTypeface(null,active?android.graphics.Typeface.BOLD:
                    android.graphics.Typeface.NORMAL);
            item.setBackground(Ui.bg(this, active?TEAL:Ui.SURFACE,
                    9,active?0:Ui.BORDER));
            LinearLayout.LayoutParams itemParams =
                    new LinearLayout.LayoutParams(-2, dp(34));
            itemParams.leftMargin = dp(5);
            itemParams.rightMargin = dp(5);
            categoryTabs.addView(item, itemParams);
            item.setSelected(active);
            item.setOnClickListener(v -> {
                if(selected==selectedTab)return;
                selectedTab=selected;
                render();
                settingsScroll.scrollTo(0,0);
            });
        }
    }
    private void note(String value) {
        TextView hint = label(value, 12, MUTED);
        hint.setPadding(dp(3), dp(3), dp(3), dp(9));
        content.addView(hint, new LinearLayout.LayoutParams(-1, -2));
    }
    private void action(String title, String description, Runnable handler) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(9), dp(14), dp(9));
        card.setBackground(Ui.bg(this, Ui.SURFACE, 11, Ui.BORDER));
        card.addView(label(title, 14.5f, FOREGROUND),
                new LinearLayout.LayoutParams(-1, dp(27)));
        if (description != null && !description.isEmpty()) {
            TextView hint = label(description, 11.5f, MUTED);
            hint.setLineSpacing(dp(2), 1.0f);
            card.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.bottomMargin = dp(8);
        content.addView(card, p);
        card.setOnClickListener(v -> handler.run());
    }
    private void select(String title, String[] options, int current,
                        java.util.function.IntConsumer changed) {
        String currentValue = options[Math.min(Math.max(current, 0), options.length - 1)];
        action(title, currentValue, () -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, current, (dialog, which) -> {
                    changed.accept(which);
                    dialog.dismiss();
                    render();
                })
                .setNegativeButton("취소", null).show());
    }
    private void render() {
        content.removeAllViews();
        categoryAnchors.clear();
        buildCategoryTabs();
        if(selectedTab==2){renderComicDefaults();return;}
        if(selectedTab==3){renderTextDefaults();return;}
        if(selectedTab==0){
        section("인터페이스", "FileHub AIO 파일관리자와 설정 화면의 밝기 및 색상 테마를 선택합니다.");
        action("화면 테마", AppearanceSettings.LABELS[AppearanceSettings.mode(this)],
                () -> new AlertDialog.Builder(this)
                        .setTitle("인터페이스 · 화면 테마")
                        .setSingleChoiceItems(AppearanceSettings.LABELS,
                                AppearanceSettings.mode(this), (dialog, which) -> {
                                    dialog.dismiss();
                                    AppearanceSettings.choose(this, which);
                                    recreate();
                                }).setNegativeButton("취소", null).show());
        note("시스템 설정에 맞춤을 선택하면 Android의 라이트/다크 설정을 따릅니다.");
        action("현재 앱","FileHub AIO v0.1.99",
                ()->new AlertDialog.Builder(this).setTitle("FileHub AIO")
                        .setMessage("개별 폴더 설정과 기존 원격 저장소 연결 정보는 유지됩니다.")
                        .setPositiveButton("확인",null).show());
        note("일반 설정은 새로운 공통 기능이 추가될 때 이 탭에 확장됩니다.");
        return;
        }

        section("동영상 플레이어 · 기본값", "해당 폴더에서 별도로 지정하지 않은 영상에 적용합니다.");
        select("화면 비율", new String[]{
                "비율 유지 · 영상 전체 표시", "비율 유지 · 꽉 채우기 (일부 잘림)",
                "화면 강제 채우기 (비율 변형)"
        }, PlayerSettings.ratio(this), mode -> PlayerSettings.prefs(this)
                .edit().putInt(PlayerSettings.RATIO, mode).apply());
        int[] seconds = {5, 10, 15, 20, 30, 60};
        int selected = 1;
        for (int i = 0; i < seconds.length; i++)
            if (seconds[i] == PlayerSettings.skipSeconds(this)) selected = i;
        select("좌우 더블탭 탐색 시간", new String[]{
                "5초", "10초", "15초", "20초", "30초", "60초"
        }, selected, index -> PlayerSettings.prefs(this).edit()
                .putInt(PlayerSettings.SKIP_SECONDS, seconds[index]).apply());
        note("가로·세로 영상에 맞춰 자동 회전하며, 수동 전환도 가능합니다. 화면을 2초 누르거나 자물쇠 버튼으로 터치를 잠그고 해제합니다. 영상 왼쪽 세로 스와이프는 화면 밝기, 오른쪽 세로 스와이프는 미디어 볼륨을 조절합니다. 영상 종료 시 시청 위치를 저장하여 다음에 이어보기 여부를 묻습니다.");

        section("자막 기본 설정", "영상 폴더별로 변경하지 않았다면 여기의 값이 적용됩니다.");
        int[] defaultSizes = PlayerSettings.SUBTITLE_SIZES;
        String[] sizeChoices = new String[defaultSizes.length];
        int defaultIndex = 0;
        for (int i = 0; i < defaultSizes.length; i++) {
            sizeChoices[i] = defaultSizes[i] + "sp";
            if (defaultSizes[i] == PlayerSettings.defaultSubtitleSize(this))
                defaultIndex = i;
        }
        select("기본 자막 크기", sizeChoices, defaultIndex,
                index -> PlayerSettings.setDefaultSubtitleSize(this, defaultSizes[index]));
        String[] languages = PlayerSettings.SUBTITLE_LANGUAGE_LABELS;
        String[] codes = PlayerSettings.SUBTITLE_LANGUAGE_CODES;
        int languageIndex = 0;
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(PlayerSettings.defaultSubtitleLanguage(this))) {
                languageIndex = i;
                break;
            }
        }
        select("자막 기본 언어", languages, languageIndex,
                index -> PlayerSettings.setDefaultSubtitleLanguage(this, codes[index]));
        note("기본 언어: 한국어. 영상에 원하는 언어가 없으면 가능한 자막 트랙만 선택할 수 있습니다. 영상별 글꼴 크기 변경은 재생 중 '자막 → 자막 크기'에서 설정합니다.");

        select("기본 자막 소스", SubtitleSourcePolicy.LABELS,
                PlayerSettings.defaultSubtitleSource(this),index->
                        PlayerSettings.prefs(this).edit()
                                .putInt(PlayerSettings.DEFAULT_SUBTITLE_SOURCE,index)
                                .apply());
        section("자막 글꼴", "PC에서 받은 TTF 글꼴을 이 앱의 전용 저장소로 가져옵니다.");
        action("TTF 글꼴 가져오기", "휴대폰에서 .ttf 파일 선택 · 최대 16MB",
                () -> chooseDocument(PICK_TTF));
        String activeFont = SubtitleFonts.current(this);
        action("기본 Android 글꼴" + (activeFont.isEmpty() ? "  ✓" : ""),
                "등록 글꼴 대신 시스템 글꼴 사용", () -> {
            SubtitleFonts.choose(this, "");
            render();
        });
        for (String name : SubtitleFonts.installed(this)) {
            action(name + (name.equals(activeFont) ? "  ✓" : ""),
                    "선택하면 동영상 자막에 적용", () -> {
                SubtitleFonts.choose(this, name);
                render();
            });
        }
        TextView sample = label("자막 미리보기: 안녕하세요! Hello 123",
                15, FOREGROUND);
        sample.setGravity(Gravity.CENTER);
        sample.setTypeface(SubtitleFonts.selectedTypeface(this));
        content.addView(sample, new LinearLayout.LayoutParams(-1, dp(54)));

        section("코덱 · 호환성", "Media3 FFmpeg 확장 디코더가 APK에 포함됩니다. 기기 기본 디코더가 지원하지 않는 코덱은 FFmpeg를 자동으로 사용합니다.");
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "알 수 없음";
        String codecFolder = PlayerSettings.prefs(this).getString(
                PlayerSettings.CODEC_TREE_LABEL, "미지정");
        action("외부 코덱 폴더 가져오기", codecFolder +
                "\n선택하면 .aar / .so / .zip을 NAS Mount 전용 폴더로 이동합니다.",
                () -> chooseFolder(PICK_CODECS));
        note("기기 CPU: " + abi +
                "\n기본 탑재: Media3 1.11.1 + NextLib FFmpeg 1.11.1-0.18.0" +
                "\n내장 FFmpeg 코덱: AC3, E-AC3, DTS, TrueHD, FLAC, ALAC, AAC 등" +
                "\nNAS Mount 보관 위치: " +
                CodecAddonManager.codecDirectory(this).getAbsolutePath() +
                "\n" + CodecAddonManager.inventory(this));
        note("기기 E-AC3 디코더: " + detectNativeDecoder("audio/eac3") +
                "\n기기 Dolby TrueHD 디코더: " + detectNativeDecoder("audio/true-hd") +
                "\nMX Player 전용 파일을 가져오더라도 해당 파일을 실행하지 않습니다. 자동 재생에 쓰이는 FFmpeg는 검증된 APK 내장 모듈입니다. 선택 폴더의 파일은 복사 후 SHA256 검증을 거쳐 이동하고, 삭제 권한이 없으면 원본은 유지합니다.");

        section("동영상 코덱 라이선스", "동영상 플레이어에 포함된 확장 코덱의 라이선스입니다.");
        action("오픈소스 FFmpeg · NextLib 라이선스",
                "NextLib FFmpeg 1.11.1-0.18.0 · GPLv3",
                () -> new AlertDialog.Builder(this).setTitle("FFmpeg 오픈소스 고지")
                        .setMessage("NAS Mount의 확장 디코더는 NextLib GPLv3 기반입니다.\n\n" +
                                "프로젝트: https://github.com/anilbeesetti/nextlib\n\n" +
                                "라이선스: GNU General Public License version 3\n\n" +
                                "이 코덱이 포함된 앱을 다른 사람에게 배포할 때는 " +
                                "GPLv3의 소스 제공 및 저작권 고지 의무를 확인해야 합니다. " +
                                "네이티브 의존성의 라이선스도 APK에 포함되어 있습니다.")
                        .setPositiveButton("확인", null).show());
    }

    private void defaultNumber(String category,String key,int value){
        ReaderDefaultSettings.prefs(this,category).edit().putInt(key,value).apply();
    }
    private void defaultBoolean(String category,String key,boolean value){
        ReaderDefaultSettings.prefs(this,category).edit().putBoolean(key,value).apply();
    }
    private void appendReaderPreview(String category){
        ReaderSettingsPreview preview=new ReaderSettingsPreview(this,category);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);
        params.topMargin=dp(10);
        params.bottomMargin=dp(13);
        content.addView(preview,params);
    }
    /** Reader-only picker: selecting an option previews it before Apply. */
    private void readerSelect(String title,String[] choices,int current,
                              String category,String key,
                              java.util.function.IntConsumer changed){
        int safe=Math.max(0,Math.min(choices.length-1,current));
        action(title,choices[safe],()->{
            LinearLayout pane=Ui.vertical(this);
            pane.setPadding(dp(18),dp(10),dp(18),dp(12));
            TextView instruction=label("값을 선택하면 아래 예시가 바로 바뀝니다.",12,MUTED);
            pane.addView(instruction,new LinearLayout.LayoutParams(-1,dp(34)));
            Spinner picker=new Spinner(this);
            picker.setAdapter(new ArrayAdapter<>(this,
                    android.R.layout.simple_spinner_dropdown_item,choices));
            picker.setSelection(safe);
            pane.addView(picker,new LinearLayout.LayoutParams(-1,dp(54)));
            ReaderSettingsPreview preview=new ReaderSettingsPreview(this,category);
            LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,-2);
            pp.topMargin=dp(13);
            pane.addView(preview,pp);
            picker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
                @Override public void onItemSelected(AdapterView<?> parent,View selected,
                        int position,long id){
                    boolean choiceIsTrueAtZero=
                            "grayscale".equals(key)||"fit_width".equals(key)||
                            "fold_two_pages".equals(key)||
                            "fold_columns".equals(key);
                    preview.change(key,choiceIsTrueAtZero?1-position:position);
                }
                @Override public void onNothingSelected(AdapterView<?> parent){}
            });
            ScrollView scroll=new ScrollView(this);
            scroll.setFillViewport(false);
            scroll.addView(pane);
            new AlertDialog.Builder(this).setTitle(title)
                    .setView(scroll).setNegativeButton("취소",null)
                    .setPositiveButton("적용",(dialog,which)->{
                        changed.accept(picker.getSelectedItemPosition());
                        render();
                    }).show();
        });
    }
    private void readerToggle(String title,String description,boolean current,
                              String category,String key,
                              java.util.function.Consumer<Boolean> changed){
        readerSelect(title,new String[]{"사용","사용 안 함"},
                current?0:1,category,key,index->changed.accept(index==0));
    }
    private void toggle(String title,String description,boolean current,
                        java.util.function.Consumer<Boolean> changed){
        action(title,description+" · "+(current?"켜짐":"꺼짐"),()->
                new AlertDialog.Builder(this).setTitle(title)
                        .setSingleChoiceItems(new String[]{"사용","사용 안 함"},
                                current?0:1,(dialog,index)->{
                                    changed.accept(index==0);
                                    dialog.dismiss();
                                    render();
                                }).setNegativeButton("취소",null).show());
    }
    private void number(String title,String suffix,int min,int max,
                        int current,String category,String key,
                        java.util.function.IntConsumer changed){
        int safe=Math.max(min,Math.min(max,current));
        action(title,safe+suffix,()->{
            LinearLayout pane=new LinearLayout(this);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setPadding(dp(24),dp(14),dp(24),dp(6));
            TextView text=label(safe+suffix,18,FOREGROUND);
            text.setGravity(Gravity.CENTER);
            pane.addView(text,new LinearLayout.LayoutParams(-1,dp(48)));
            android.widget.SeekBar bar=new android.widget.SeekBar(this);
            bar.setMax(max-min);
            bar.setProgress(safe-min);
            pane.addView(bar,new LinearLayout.LayoutParams(-1,dp(56)));
            ReaderSettingsPreview preview=new ReaderSettingsPreview(this,category);
            LinearLayout.LayoutParams previewParams=
                    new LinearLayout.LayoutParams(-1,-2);
            previewParams.topMargin=dp(8);
            pane.addView(preview,previewParams);
            preview.change(key,safe);
            bar.setOnSeekBarChangeListener(
                    new android.widget.SeekBar.OnSeekBarChangeListener(){
                        @Override public void onProgressChanged(
                                android.widget.SeekBar s,int n,boolean user){
                            text.setText((min+n)+suffix);
                            preview.change(key,min+n);
                        }
                        @Override public void onStartTrackingTouch(
                                android.widget.SeekBar s){}
                        @Override public void onStopTrackingTouch(
                                android.widget.SeekBar s){}
                    });
            ScrollView scroll=new ScrollView(this);
            scroll.addView(pane);
            new AlertDialog.Builder(this).setTitle(title)
                    .setView(scroll).setNegativeButton("취소",null)
                    .setPositiveButton("적용",(dialog,index)->{
                        changed.accept(min+bar.getProgress());
                        render();
                    }).show();
        });
    }
    private void readerTurnDefaults(String category){
        readerSelect("기본 페이지 넘김 효과",ReaderTurnPreferences.STYLES,
                ReaderTurnPreferences.toIndex(
                        ReaderDefaultSettings.turn(this,category)),
                category,FolderMediaSettings.EPUB.equals(category)?
                        "page_turn_effect":"turn_effect",value->
                        ReaderDefaultSettings.setTurn(this,category,
                                ReaderTurnPreferences.fromIndex(value),
                                ReaderDefaultSettings.slow(this,category)));
        readerSelect("기본 책 넘김 속도",ReaderTurnPreferences.SPEEDS,
                ReaderDefaultSettings.slow(this,category)?1:0,
                category,"slow_page_turn",value->
                        ReaderDefaultSettings.setTurn(this,category,
                                ReaderDefaultSettings.turn(this,category),
                                value==1));
    }
    private void renderComicDefaults(){
        String category=FolderMediaSettings.COMIC;
        section("만화책 뷰어 · 기본값",
                "책이나 폴더에서 따로 설정하지 않은 만화책에만 적용됩니다.");
        appendReaderPreview(category);
        readerSelect("기본 읽기 방향",
                new String[]{"한국식 · 왼쪽→오른쪽",
                        "일본식 · 오른쪽→왼쪽"},
                ReaderDefaultSettings.bool(this,category,"rtl",false)?1:0,
                category,"rtl",
                value->defaultBoolean(category,"rtl",value==1));
        readerSelect("좌우 합본 이미지 자동 분할",
                new String[]{"자동 감지 (권장)","분할하지 않음","민감하게 감지"},
                ReaderDefaultSettings.integer(this,category,
                        "double_page_split",ComicPageLayout.AUTO),
                category,"double_page_split",
                value->defaultNumber(category,"double_page_split",value));
        readerToggle("폴드 펼침 두 페이지 보기","펼친 화면에서 좌우 두 쪽",
                ReaderDefaultSettings.bool(this,category,"fold_two_pages",true),
                category,"fold_two_pages",
                value->defaultBoolean(category,"fold_two_pages",value));
        readerToggle("기본 가로 폭 맞춤","원본 페이지가 화면 너비에 맞게 확대됨",
                ReaderDefaultSettings.bool(this,category,"fit_width",false),
                category,"fit_width",
                value->defaultBoolean(category,"fit_width",value));
        readerToggle("기본 흑백 모드","색상 대신 흑백으로 표시",
                ReaderDefaultSettings.bool(this,category,"grayscale",false),
                category,"grayscale",
                value->defaultBoolean(category,"grayscale",value));
        number("기본 명암 대비","%",50,200,
                ReaderDefaultSettings.integer(this,category,"contrast",100),
                category,"contrast",
                value->defaultNumber(category,"contrast",value));
        number("기본 밝기","", -100,100,
                ReaderDefaultSettings.integer(this,category,"brightness",0),
                category,"brightness",
                value->defaultNumber(category,"brightness",value));
        readerTurnDefaults(category);
        note("예: 만화책/A만화의 1권에서 일본식으로 변경하면 A만화 폴더의 "+
                "2~10권도 일본식으로 열립니다. 다른 만화 폴더는 기본 방향을 사용합니다.");
    }
    private void renderTextDefaults(){
        String category=FolderMediaSettings.TEXT;
        section("TXT 텍스트뷰어 · 기본값",
                "TXT 소설의 기본 글꼴·줄간격·여백·독서 모드입니다.");
        appendReaderPreview(category);
        note("TXT 빈 줄 자동 정리: 기본 적용 · 규칙적으로 늘어난 줄바꿈만 감지해 "+
                "독서 화면에서 복원합니다. 원본 파일은 수정하지 않습니다.");
        readerSelect("TXT 기본 읽기 방식",
                new String[]{"좌우 페이지 넘김","세로 연속 스크롤"},
                ReaderDefaultSettings.integer(this,category,"view_mode",0),
                category,"view_mode",
                value->defaultNumber(category,"view_mode",value));
        readerSelect("TXT 기본 글꼴",
                new String[]{"명조 · 책 본문","고딕 · 선명한 글씨","고정폭"},
                ReaderDefaultSettings.integer(this,category,"font",0),
                category,"font",
                value->defaultNumber(category,"font",value));
        number("TXT 기본 글씨 크기","sp",12,38,
                ReaderDefaultSettings.integer(this,category,"size",20),
                category,"size",
                value->defaultNumber(category,"size",value));
        number("TXT 기본 줄간격","%",100,240,
                ReaderDefaultSettings.integer(this,category,"spacing",145),
                category,"spacing",
                value->defaultNumber(category,"spacing",value));
        number("TXT 기본 좌우 여백","dp",9,44,
                ReaderDefaultSettings.integer(this,category,"margin",19),
                category,"margin",
                value->defaultNumber(category,"margin",value));
        readerSelect("TXT 기본 독서 배경",
                new String[]{"아이보리 종이","흰 종이","오래된 세피아 종이","야간 독서"},
                ReaderDefaultSettings.integer(this,category,"theme",0),
                category,"theme",
                value->defaultNumber(category,"theme",value));
        readerToggle("TXT 폴드 두 페이지 보기","펼침 화면에서 좌우로 나누어 읽기",
                ReaderDefaultSettings.bool(this,category,"fold_columns",true),
                category,"fold_columns",
                value->defaultBoolean(category,"fold_columns",value));
        readerTurnDefaults(category);

        // EPUB has its own defaults, independent of TXT within this tab.
        section("EPUB 전자책 · 기본값",
                "같은 텍스트뷰어 탭에서 EPUB의 읽기 방식과 본문 설정도 관리합니다.");
        appendReaderPreview(FolderMediaSettings.EPUB);
        readerSelect("EPUB 기본 읽기 방식",
                new String[]{"좌우 페이지 넘김","세로 연속 스크롤"},
                ReaderDefaultSettings.integer(this,FolderMediaSettings.EPUB,
                        "mode",0),
                FolderMediaSettings.EPUB,"mode",
                value->defaultNumber(FolderMediaSettings.EPUB,"mode",value));
        number("EPUB 기본 글씨 크기","sp",12,36,
                ReaderDefaultSettings.integer(this,FolderMediaSettings.EPUB,
                        "font_size",19),
                FolderMediaSettings.EPUB,"font_size",
                value->defaultNumber(FolderMediaSettings.EPUB,"font_size",value));
        number("EPUB 기본 줄간격","%",110,230,
                ReaderDefaultSettings.integer(this,FolderMediaSettings.EPUB,
                        "line_spacing",155),
                FolderMediaSettings.EPUB,"line_spacing",
                value->defaultNumber(FolderMediaSettings.EPUB,"line_spacing",value));
        number("EPUB 기본 좌우 여백","dp",8,50,
                ReaderDefaultSettings.integer(this,FolderMediaSettings.EPUB,
                        "side_margin",18),
                FolderMediaSettings.EPUB,"side_margin",
                value->defaultNumber(FolderMediaSettings.EPUB,"side_margin",value));
        readerSelect("EPUB 기본 독서 배경",
                new String[]{"아이보리","흰색","세피아","야간"},
                ReaderDefaultSettings.integer(this,FolderMediaSettings.EPUB,
                        "theme",0),
                FolderMediaSettings.EPUB,"theme",
                value->defaultNumber(FolderMediaSettings.EPUB,"theme",value));
        readerTurnDefaults(FolderMediaSettings.EPUB);
        note("위 설정은 새 폴더에 적용되는 기본값입니다. 책 안의 Aa 설정을 "+
                "변경하면 해당 소설이 들어 있는 폴더의 TXT 또는 EPUB에만 적용됩니다.");
        section("PDF 문서 · 책 뷰어",
                "PDF도 FileHub 안에서 바로 열 수 있습니다. 로컬·SD/USB·NAS 파일을 지원합니다.");
        note("PDF는 원본 문서의 글과 그림 위치를 유지합니다. 읽는 중 상단 +/−로 "+
                "확대하고 하단에서 페이지를 이동할 수 있으며, 읽던 위치를 저장합니다.");
    }

    private String detectEac3() {
        return detectNativeDecoder("audio/eac3");
    }

    private String detectNativeDecoder(String mimeType) {
        try {
            for (MediaCodecInfo codec :
                    new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (codec.isEncoder()) continue;
                for (String mime : codec.getSupportedTypes())
                    if (mimeType.equalsIgnoreCase(mime))
                        return "지원 · " + codec.getName();
            }
            return "기기 디코더 미발견";
        } catch (Exception ex) {
            return "정보 확인 불가";
        }
    }
    private String describeCodecFiles() {
        String tree = PlayerSettings.prefs(this).getString(PlayerSettings.CODEC_TREE, "");
        if (tree.isEmpty()) return "코덱 파일 폴더 미지정";
        try {
            DocumentFile folder = DocumentFile.fromTreeUri(this, Uri.parse(tree));
            if (folder == null || !folder.canRead()) return "폴더 읽기 권한 없음";
            int count = 0;
            List<String> names = new ArrayList<>();
            for (DocumentFile item : folder.listFiles()) {
                String name = item.getName();
                if (name == null) continue;
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".so") || lower.endsWith(".aar")) {
                    count++;
                    if (names.size() < 3) names.add(name);
                }
            }
            if (count == 0) return "선택 폴더에서 .so/.aar 파일 미발견";
            return "코덱 후보 " + count + "개: " + String.join(", ", names) +
                    "\n파일 발견만으로 설치·호환성 확인이 완료된 것은 아닙니다.";
        } catch (Exception ignored) {
            return "코덱 폴더를 읽을 수 없습니다.";
        }
    }
    private void chooseFolder(int code) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (code == PICK_CODECS)
            intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, code);
    }
    private void importCodecFolder(Uri folderUri) {
        TextView progress = label("선택 폴더의 코덱 파일을 확인 중...", 13, FOREGROUND);
        progress.setPadding(dp(24), dp(24), dp(24), dp(24));
        AlertDialog progressDialog = new AlertDialog.Builder(this)
                .setTitle("NAS Mount로 코덱 파일 가져오기")
                .setView(progress)
                .setCancelable(false)
                .create();
        progressDialog.show();
        Thread worker = new Thread(() -> {
            try {
                CodecAddonManager.Result outcome = CodecAddonManager.importFromFolder(
                        getApplicationContext(), folderUri, (filename, index, total) ->
                                runOnUiThread(() -> progress.setText(
                                        index + "번째 파일 확인 중: " + filename)));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    progressDialog.dismiss();
                    render();
                    new AlertDialog.Builder(this).setTitle("코덱 가져오기 결과")
                            .setMessage(outcome.summary())
                            .setPositiveButton("확인", null).show();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    progressDialog.dismiss();
                    new AlertDialog.Builder(this).setTitle("코덱 가져오기 실패")
                            .setMessage(error.getMessage())
                            .setPositiveButton("확인", null).show();
                });
            }
        }, "nas-codec-import");
        worker.start();
    }
    private void chooseDocument(int code) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, code);
    }
    private String fileName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst())
                return cursor.getString(0);
        } catch (Exception ignored) {}
        return uri.getLastPathSegment();
    }
    private void chooseRemoteFolder() {
        try {
            List<Models.RemoteProfile> saved = new SecureStore(this).activeRemotes();
            if (saved.isEmpty()) { toast("등록된 원격 저장소가 없습니다."); return; }
            String[] names = new String[saved.size()];
            for (int i=0;i<names.length;i++) names[i] = saved.get(i).label();
            new AlertDialog.Builder(this).setTitle("네트워크 저장소")
                    .setItems(names, (d, which) -> {
                        Models.RemoteProfile profile = saved.get(which);
                        EditText path = new EditText(this);
                        path.setSingleLine();
                        path.setText("/");
                        path.setHint("/영화/해외영화");
                        new AlertDialog.Builder(this).setTitle(profile.label() + " 폴더 경로")
                                .setView(path)
                                .setNegativeButton("취소", null)
                                .setPositiveButton("등록", (dialog, action) -> {
                                    try {
                                        String route = RemoteProvider.relativePath(
                                                path.getText().toString());
                                        PlayerSettings.addHiddenFolder(this,
                                                "remote:" + profile.id + ":" + route);
                                        render();
                                    } catch (Exception error) { toast("경로 형식을 확인하세요."); }
                                }).show();
                    }).show();
        } catch (Exception ex) { toast("연결 목록을 읽을 수 없습니다."); }
    }
    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (code == PICK_TTF) {
            String name = fileName(uri);
            try {
                String imported = SubtitleFonts.importTtf(this, uri, name);
                SubtitleFonts.choose(this, imported);
                render();
                toast("폰트를 등록하고 선택했습니다.");
            } catch (Exception problem) {
                new AlertDialog.Builder(this).setTitle("폰트 가져오기 실패")
                        .setMessage(problem.getMessage())
                        .setPositiveButton("확인", null).show();
            }
        } else if (code == PICK_CODECS || code == PICK_HIDE_FOLDER) {
            try {
                if ((data.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } else {
                    getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
                if (code == PICK_CODECS) {
                    String folder = fileName(uri);
                    if (folder == null) folder = uri.toString();
                    PlayerSettings.prefs(this).edit()
                            .putString(PlayerSettings.CODEC_TREE, uri.toString())
                            .putString(PlayerSettings.CODEC_TREE_LABEL, folder).apply();
                    importCodecFolder(uri);
                } else {
                    String documentId = DocumentsContract.getTreeDocumentId(uri);
                    PlayerSettings.addHiddenFolder(this, "storage:" + documentId);
                    if (documentId.startsWith("primary:")) {
                        PlayerSettings.addHiddenFolder(this,
                                "local:/storage/emulated/0/" + documentId.substring(8));
                    }
                    render();
                }
            } catch (Exception error) { toast("선택한 폴더를 저장할 수 없습니다."); }
        }
    }
    private void toast(String message) {
        android.widget.Toast.makeText(this, message,
                android.widget.Toast.LENGTH_LONG).show();
    }
}
