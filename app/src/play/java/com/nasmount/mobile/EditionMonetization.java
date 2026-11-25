package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.gms.ads.*;
import com.google.android.gms.ads.interstitial.*;
import com.google.android.ump.*;
import java.util.WeakHashMap;

/** Play-only ads and purchase entitlement. All UI operations run on the main thread. */
final class EditionMonetization implements Application.ActivityLifecycleCallbacks {
    private static final String TEST_BANNER="ca-app-pub-3940256099942544/9214589741";
    private static final String TEST_INTERSTITIAL="ca-app-pub-3940256099942544/1033173712";
    private static EditionMonetization instance;
    private final Application app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences usagePrefs;
    private final MonetizationPolicy.UsageClock usage;
    private final FullVersionBilling billing;
    private final ConsentInformation consent;
    private final WeakHashMap<Activity,BannerHost> banners=new WeakHashMap<>();
    private final WeakHashMap<Activity,Boolean> videosPlaying=new WeakHashMap<>();
    private final WeakHashMap<Activity,Boolean> entitlementSeen=new WeakHashMap<>();
    private Activity resumed;
    private boolean observedFull, consentRequested, consentInProgress, adsInitializing, adsReady;
    private String observedPrice="";
    private boolean interstitialLoading,adShowing;
    private long interstitialLoadedAt,lastInterstitialAttempt=-60000,lastContentExit=-100000;
    private InterstitialAd interstitial;
    private boolean pip;
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            updateUsage();
            if(resumed!=null){
                ensureInterstitial();
                BannerHost host=banners.get(resumed);
                if(host!=null&&host.ad==null)host.refresh();
                main.postDelayed(this,3000);
            }
        }
    };

    private EditionMonetization(Application app){
        this.app=app;
        usagePrefs=app.getSharedPreferences("filehub_ad_usage",0);
        usage=new MonetizationPolicy.UsageClock(usagePrefs.getLong("elapsed_ms",0));
        consent=UserMessagingPlatform.getConsentInformation(app);
        billing=new FullVersionBilling(app,this::billingChanged);
        observedFull=billing.full();
    }
    static void init(Application app){
        instance=new EditionMonetization(app);
        app.registerActivityLifecycleCallbacks(instance);
    }
    static boolean full(Context context){return instance!=null&&instance.billing.full();}
    static View content(Activity a,View root){
        EditionMonetization s=instance;
        if(s==null)return root;
        // Content is never covered by advertising. Reserve a separate slot
        // and keep the original screen hierarchy/inset listeners intact.
        BannerHost host=new BannerHost(s,a,root);
        s.banners.put(a,host);
        return host.layout;
    }
    static void settings(SettingsActivity a){
        EditionMonetization s=instance;
        if(s==null)return;
        a.section(L10n.t("FileHub 풀버전"),L10n.t("한 번 구매하면 모든 광고가 제거되고 Play 버전의 제한 기능이 열립니다."));
        String price=s.billing.price();
        a.action(s.billing.full()?L10n.t("풀버전 사용 중"):L10n.t("풀버전 구매"),
                s.billing.full()?L10n.t("광고 없음 · 기능 제한 없음"):
                    (price.isEmpty()?L10n.t("한국 기준 출시 예정가 ₩3,900"):price)+" · "+L10n.t("일회성 구매"),
                ()->showUpgrade(a));
        a.action(L10n.t("구매 복원"),L10n.t("현재 Google Play 계정의 구매 내역을 확인합니다."),()->s.billing.restore());
        a.action(L10n.t("광고 개인정보 설정"),L10n.t("광고 개인정보 보호 선택을 확인하거나 변경합니다."),()->s.privacy(a));
        if(!s.billing.full()){
            a.note(L10n.t("무료: 종류별 원격 연결 1개, 배너 광고, 콘텐츠 이용 10분 후 전환 시 전면 광고, 내장 오피스·한글 뷰어 제한."));
        }
    }
    static void showUpgrade(Activity a){
        EditionMonetization s=instance;
        if(s==null||a.isFinishing()||a.isDestroyed())return;
        if(s.billing.full()){
            new AlertDialog.Builder(a).setTitle(L10n.t("풀버전 사용 중"))
                .setMessage(L10n.t("광고 없음 · 기능 제한 없음"))
                .setPositiveButton(L10n.t("확인"),null)
                .setNeutralButton(L10n.t("구매 복원"),(d,w)->s.billing.restore()).show();
            return;
        }
        String price=s.billing.price();
        new AlertDialog.Builder(a).setTitle(L10n.t("FileHub 풀버전"))
                .setMessage(L10n.t("모든 광고 제거\n종류별 원격 연결 제한 해제\n내장 오피스·한글 뷰어 및 편집 기능 사용")+
                    "\n\n"+(price.isEmpty()?L10n.t("한국 기준 출시 예정가 ₩3,900"):price)+
                    "\n"+L10n.t("일회성 구매 · 구독 아님"))
                .setNegativeButton(L10n.t("나중에"),null)
                .setNeutralButton(L10n.t("구매 복원"),(d,w)->s.billing.restore())
                .setPositiveButton(L10n.t("풀버전 구매"),(d,w)->s.billing.buy(a)).show();
    }
    static void videoPlaying(Activity a,boolean playing){
        EditionMonetization s=instance;if(s==null)return;
        s.videosPlaying.put(a,playing);s.updateUsage();
    }
    static void pictureInPicture(Activity a,boolean pip){
        EditionMonetization s=instance;if(s==null)return;
        if(a instanceof VideoPlayerActivity){
            if(s.resumed==a){s.pip=pip;s.updateUsage();}
            BannerHost host=s.banners.get(a);
            if(host!=null){if(pip)host.hide();else host.refresh();}
        }
    }
    static void contentBreak(Activity a,Runnable next){
        EditionMonetization s=instance;
        if(s==null){next.run();return;}
        s.showAtBreak(a,next);
    }
    private boolean testAds(){return BuildConfig.DEBUG||BuildConfig.PLAY_INTERNAL_TEST;}
    private boolean adsConfigured(){return testAds()||BuildConfig.LIVE_ADS_CONFIGURED;}
    private boolean adsAllowed(){return adsConfigured()&&billing.checked()&&!billing.full()&&
            !consentInProgress&&consent.canRequestAds()&&adsReady;}
    private boolean eligible(Activity a){
        return a instanceof VideoPlayerActivity || a instanceof ComicBookActivity ||
                a instanceof BookTextActivity ||
                (a instanceof EpubReaderActivity&&!PdfBookTypes.supports(a.getIntent().getStringExtra(ReaderSource.NAME)));
    }
    private boolean visible(Activity a){
        return a!=null&&a==resumed&&!a.isFinishing()&&!a.isDestroyed()&&
                !a.isInPictureInPictureMode()&&a.hasWindowFocus();
    }
    private void updateUsage(){
        Activity a=resumed;
        boolean counting=!billing.full()&&!adShowing&&!consentInProgress&&!pip&&
                visible(a)&&eligible(a)&&(! (a instanceof VideoPlayerActivity)||Boolean.TRUE.equals(videosPlaying.get(a)));
        usage.update(SystemClock.elapsedRealtime(),counting);
        usagePrefs.edit().putLong("elapsed_ms",usage.accumulated()).apply();
    }
    private void billingChanged(){
        boolean full=billing.full(),changed=full!=observedFull;
        String price=billing.price();
        boolean priceChanged=!price.equals(observedPrice);
        observedPrice=price;
        observedFull=full;
        if(full){
            usage.shown();usagePrefs.edit().remove("elapsed_ms").apply();
            interstitial=null;
            for(BannerHost host:banners.values())host.clear();
        }else{
            maybeConsent();
            refreshBanner();ensureInterstitial();
        }
        if(resumed instanceof SettingsActivity&&(changed||priceChanged)){
            // Product price and entitlement are resolved asynchronously.
            ((SettingsActivity)resumed).render();
        }
        if(changed&&resumed instanceof MainActivity){
            entitlementSeen.put(resumed,full);
            ((MainActivity)resumed).onEditionEntitlementChanged();
        }
    }
    private void maybeConsent(){
        Activity a=resumed;
        if(!adsConfigured()||!billing.checked()||billing.full()||consentRequested||
                a==null||a.isFinishing()||a.isDestroyed()||
                !(a instanceof MainActivity||a instanceof SettingsActivity)||TvMode.isTv(a))return;
        consentRequested=true;consentInProgress=true;updateUsage();
        consent.requestConsentInfoUpdate(a,new ConsentRequestParameters.Builder().build(),()->{
            if(a.isFinishing()||a.isDestroyed()){
                consentRequested=false;consentInProgress=false;return;
            }
            UserMessagingPlatform.loadAndShowConsentFormIfRequired(a,error->{
                consentInProgress=false;initializeAds();
            });
        },error->{consentInProgress=false;initializeAds();});
    }
    private void initializeAds(){
        if(!adsConfigured()||billing.full()||!consent.canRequestAds()||consentInProgress)return;
        if(adsReady){refreshBanner();ensureInterstitial();return;}
        if(adsInitializing)return;adsInitializing=true;
        MobileAds.initialize(app,status->main.post(()->{
            adsInitializing=false;adsReady=true;
            refreshBanner();ensureInterstitial();
        }));
    }
    private void privacy(Activity a){
        if(!adsConfigured()||billing.full()){
            Toast.makeText(a,L10n.t("광고가 비활성화되어 있습니다."),Toast.LENGTH_SHORT).show();return;
        }
        if(consent.getPrivacyOptionsRequirementStatus()!=ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED){
            Toast.makeText(a,L10n.t("현재 추가 광고 개인정보 설정이 필요하지 않습니다."),Toast.LENGTH_LONG).show();return;
        }
        consentInProgress=true;updateUsage();
        for(BannerHost host:banners.values())host.clear();
        interstitial=null;
        UserMessagingPlatform.showPrivacyOptionsForm(a,error->{
            consentInProgress=false;initializeAds();
        });
    }
    private void refreshBanner(){
        if(resumed!=null){BannerHost host=banners.get(resumed);if(host!=null)host.refresh();}
    }
    private void ensureInterstitial(){
        if(!adsAllowed()||resumed==null||TvMode.isTv(resumed)||adShowing||interstitialLoading)return;
        long now=SystemClock.elapsedRealtime();
        if(interstitial!=null&&now-interstitialLoadedAt<55*60*1000L)return;
        interstitial=null;
        if(now-lastInterstitialAttempt<30000)return;
        lastInterstitialAttempt=now;interstitialLoading=true;
        InterstitialAd.load(app,testAds()?TEST_INTERSTITIAL:BuildConfig.AD_INTERSTITIAL_ID,
                new AdRequest.Builder().build(),new InterstitialAdLoadCallback(){
                    @Override public void onAdLoaded(InterstitialAd ad){
                        interstitialLoading=false;
                        if(adsAllowed()){interstitial=ad;interstitialLoadedAt=SystemClock.elapsedRealtime();}
                    }
                    @Override public void onAdFailedToLoad(LoadAdError error){interstitialLoading=false;}
                });
    }
    private void showAtBreak(Activity a,Runnable next){
        updateUsage();
        if(!adsAllowed()||!visible(a)||TvMode.isTv(a)||!usage.due()||interstitial==null||
                adShowing||SystemClock.elapsedRealtime()-interstitialLoadedAt>=55*60*1000L){
            ensureInterstitial();next.run();return;
        }
        InterstitialAd ad=interstitial;interstitial=null;adShowing=true;updateUsage();
        ad.setFullScreenContentCallback(new FullScreenContentCallback(){
            private boolean completed;
            @Override public void onAdShowedFullScreenContent(){
                usage.shown();usagePrefs.edit().putLong("elapsed_ms",0).apply();
            }
            @Override public void onAdDismissedFullScreenContent(){complete();}
            @Override public void onAdFailedToShowFullScreenContent(AdError error){complete();}
            private void complete(){
                if(completed)return;completed=true;adShowing=false;ensureInterstitial();
                if(!a.isFinishing()&&!a.isDestroyed())next.run();
                updateUsage();
            }
        });
        try{ad.show(a);}catch(RuntimeException error){
            adShowing=false;next.run();ensureInterstitial();updateUsage();
        }
    }
    @Override public void onActivityResumed(Activity a){
        if(!banners.containsKey(a)&&!(a instanceof BuiltInOfficeActivity)&&!(a instanceof OfficeBridgeActivity))return;
        resumed=a;pip=a.isInPictureInPictureMode();
        if(!billing.full()&&eligible(a)&&
                !usagePrefs.getBoolean("explained",false)){
            usagePrefs.edit().putBoolean("explained",true).apply();
            Toast.makeText(a,L10n.t("콘텐츠 이용 10분 후 전환 시 광고가 표시됩니다."),
                    Toast.LENGTH_LONG).show();
        }
        billing.resume();maybeConsent();refreshBanner();ensureInterstitial();updateUsage();
        main.removeCallbacks(ticker);main.postDelayed(ticker,1000);
        if(a instanceof MainActivity){
            Boolean previous=entitlementSeen.put(a,billing.full());
            if(previous!=null&&previous!=billing.full())
                ((MainActivity)a).onEditionEntitlementChanged();
            if(SystemClock.elapsedRealtime()-lastContentExit<2000){
                lastContentExit=-100000;
                main.postDelayed(()->{if(visible(a))showAtBreak(a,()->{});},350);
            }
        }
    }
    @Override public void onActivityPaused(Activity a){
        if(a!=resumed)return;
        if(eligible(a)&&a.isFinishing())lastContentExit=SystemClock.elapsedRealtime();
        resumed=null;updateUsage();main.removeCallbacks(ticker);
        BannerHost host=banners.get(a);if(host!=null)host.pause();
    }
    @Override public void onActivityDestroyed(Activity a){
        BannerHost host=banners.remove(a);if(host!=null)host.clear();
        videosPlaying.remove(a);
        entitlementSeen.remove(a);
        if(a==resumed){resumed=null;updateUsage();main.removeCallbacks(ticker);}
    }
    @Override public void onActivityCreated(Activity a,Bundle state){}
    @Override public void onActivityStarted(Activity a){}
    @Override public void onActivityStopped(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle state){}

    private static final class BannerHost {
        final EditionMonetization owner;
        final Activity activity;
        final LinearLayout layout;
        final FrameLayout slot;
        AdView ad;
        boolean loaded;
        int width;
        long attempted=-60000;
        BannerHost(EditionMonetization owner,Activity activity,View root){
            this.owner=owner;this.activity=activity;
            layout=new LinearLayout(activity);layout.setOrientation(LinearLayout.VERTICAL);
            layout.addView(root,new LinearLayout.LayoutParams(-1,0,1));
            slot=new FrameLayout(activity);slot.setVisibility(View.GONE);
            slot.setBackgroundColor(0xFF15181B);
            layout.addView(slot,new LinearLayout.LayoutParams(-1,-2));
            ViewCompat.setOnApplyWindowInsetsListener(slot,(v,insets)->{
                int bottom=insets.getInsets(WindowInsetsCompat.Type.navigationBars()|
                        WindowInsetsCompat.Type.displayCutout()).bottom;
                slot.setPadding(0,0,0,bottom);return insets;
            });
            layout.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
                if(r-l!=or-ol)refresh();
            });
        }
        void refresh(){
            if(activity!=owner.resumed||activity.isFinishing()||activity.isDestroyed()||
                    activity.isInPictureInPictureMode()||TvMode.isTv(activity)||!owner.adsAllowed()){
                hide();if(owner.billing.full())clear();return;
            }
            int dp=(int)(layout.getWidth()/activity.getResources().getDisplayMetrics().density);
            if(dp<100)return;
            if(ad!=null&&width!=dp)clear();
            if(ad!=null){ad.resume();slot.setVisibility(loaded?View.VISIBLE:View.GONE);return;}
            long now=SystemClock.elapsedRealtime();
            if(width==dp&&now-attempted<30000)return;
            attempted=now;width=dp;
            final AdView candidate=new AdView(activity);ad=candidate;loaded=false;
            candidate.setAdUnitId(owner.testAds()?TEST_BANNER:BuildConfig.AD_BANNER_ID);
            candidate.setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(activity,dp));
            slot.addView(candidate,new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
            candidate.setAdListener(new AdListener(){
                @Override public void onAdLoaded(){
                    if(ad!=candidate)return;
                    if(!owner.adsAllowed()){clear();return;}
                    loaded=true;
                    if(owner.resumed==activity&&!activity.isInPictureInPictureMode()){
                        slot.setVisibility(View.VISIBLE);ViewCompat.requestApplyInsets(slot);
                    }
                }
                @Override public void onAdFailedToLoad(LoadAdError error){if(ad==candidate&&!loaded)clear();}
            });
            candidate.loadAd(new AdRequest.Builder().build());
        }
        void hide(){slot.setVisibility(View.GONE);}
        void pause(){if(ad!=null)ad.pause();}
        void clear(){
            AdView previous=ad;ad=null;loaded=false;
            slot.removeAllViews();hide();if(previous!=null)previous.destroy();
        }
    }
}
