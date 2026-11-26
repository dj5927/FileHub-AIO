package com.nasmount.mobile;

import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

/** Play-only, full screen purchase choices. Never launch a purchase at a guessed price. */
public final class PremiumActivity extends LocalizedActivity {
    private LinearLayout body;
    private TextView annualPrice,permanentPrice,annualBuy,permanentBuy,status;
    private FullVersionBilling billing;
    @Override public void onCreate(Bundle saved){
        Ui.applyAppearance(this);
        setTheme(Ui.darkMode?R.style.AppThemeDark:R.style.AppTheme);
        super.onCreate(saved);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().getDecorView().setSystemUiVisibility(Ui.darkMode?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=Ui.vertical(this);root.setBackgroundColor(Ui.BG);
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        ViewCompat.requestApplyInsets(root);
        LinearLayout bar=Ui.horizontal(this);bar.setBackgroundColor(Ui.DARK);
        root.addView(bar,Ui.lp(this,-1,56));
        TextView close=Ui.text(this,"×",28,Color.WHITE,false);close.setGravity(Gravity.CENTER);
        close.setContentDescription(L10n.t("닫기"));bar.addView(close,Ui.lp(this,52,-1));
        close.setOnClickListener(v->finish());
        TextView title=Ui.text(this,L10n.t("프리미엄 업그레이드"),18,Color.WHITE,true);
        title.setSingleLine(true);title.setAutoSizeTextTypeUniformWithConfiguration(12,18,1,android.util.TypedValue.COMPLEX_UNIT_SP);bar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        ScrollView scroll=new ScrollView(this);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        body=Ui.vertical(this);body.setPadding(Ui.dp(this,20),Ui.dp(this,12),Ui.dp(this,20),Ui.dp(this,24));
        scroll.addView(body);
        annualPrice=offer(true,L10n.t("연간 구독"),L10n.t("매년 자동 결제됩니다. Google Play에서 언제든 구독을 해지할 수 있으며, 결제한 기간까지 이용할 수 있습니다."));
        separator();
        permanentPrice=offer(false,L10n.t("영구 구매"),L10n.t("한 번만 결제하면 프리미엄 기능을 계속 이용할 수 있습니다. 자동 갱신되지 않습니다."));
        separator();
        status=Ui.text(this,"",12,Ui.MUTED,false);status.setPadding(0,Ui.dp(this,8),0,Ui.dp(this,8));
        body.addView(status,Ui.lp(this,-1,-2));
        TextView restore=Ui.action(this,L10n.t("구매 복원"));body.addView(restore,Ui.lp(this,-1,42));
        restore.setOnClickListener(v->{if(billing!=null)billing.restore();});
        TextView manage=Ui.text(this,L10n.t("Google Play 구독 관리"),13,Ui.TEAL,true);
        manage.setGravity(Gravity.CENTER);body.addView(manage,Ui.lp(this,-1,44));
        manage.setOnClickListener(v->{
            try{startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://play.google.com/store/account/subscriptions?sku="+MonetizationPolicy.ANNUAL_PRODUCT_ID+"&package="+getPackageName())));}
            catch(Exception ignored){}
        });
        separator();
        TextView heading=Ui.text(this,L10n.t("프리미엄 기능"),20,Ui.TEXT,true);
        body.addView(heading,Ui.lp(this,-1,48));
        feature("no_ads",L10n.t("광고 없음"),L10n.t("배너 및 전면 광고 제거"));
        feature("remote",L10n.t("무제한 원격 연결"),L10n.t("프로토콜별 연결 개수 제한 해제"));
        feature("file",L10n.t("문서 뷰어"),L10n.t("내장 오피스 및 한글 뷰어 제한 해제"));
        billing=EditionMonetization.premiumBilling();refreshOffers();
    }
    private TextView offer(boolean annual,String title,String description){
        LinearLayout row=Ui.horizontal(this);
        TextView name=Ui.text(this,title,20,Ui.TEXT,true);name.setSingleLine(true);
        name.setAutoSizeTextTypeUniformWithConfiguration(12,20,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        row.addView(name,new LinearLayout.LayoutParams(0,Ui.dp(this,44),1));
        TextView price=Ui.text(this,"",19,Ui.TEXT,true);price.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        row.addView(price,Ui.lp(this,-2,44));body.addView(row,Ui.lp(this,-1,48));
        TextView desc=Ui.text(this,description,13,Ui.MUTED,false);
        desc.setLineSpacing(Ui.dp(this,3),1f);body.addView(desc,Ui.lp(this,-1,-2));
        Ui.gap(body,this,14);
        TextView buy=Ui.text(this,L10n.t("지금 구매"),15,Color.WHITE,true);
        buy.setGravity(Gravity.CENTER);body.addView(buy,Ui.lp(this,-1,44));
        buy.setOnClickListener(v->{if(billing!=null)billing.buy(this,annual);});
        if(annual)annualBuy=buy;else permanentBuy=buy;
        return price;
    }
    private void separator(){
        Ui.gap(body,this,18);View line=new View(this);line.setBackgroundColor(Ui.BORDER);
        body.addView(line,Ui.lp(this,-1,1));Ui.gap(body,this,12);
    }
    private void feature(String icon,String title,String detail){
        LinearLayout row=Ui.horizontal(this);row.setPadding(0,Ui.dp(this,6),0,Ui.dp(this,6));
        row.addView(new Ui.Icon(this,icon,Ui.TEAL),Ui.lp(this,34,34));
        LinearLayout texts=Ui.vertical(this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=Ui.dp(this,12);row.addView(texts,lp);
        texts.addView(Ui.text(this,title,15,Ui.TEXT,true),Ui.lp(this,-1,-2));
        texts.addView(Ui.text(this,detail,12,Ui.MUTED,false),Ui.lp(this,-1,-2));
        body.addView(row,Ui.lp(this,-1,-2));
    }
    private void setButton(TextView view,boolean enabled){
        view.setEnabled(enabled);
        view.setBackground(Ui.bg(this,enabled?0xff0b8f83:Ui.BORDER,6,0));
        view.setTextColor(enabled?Color.WHITE:Ui.MUTED);
    }
    void refreshOffers(){
        if(status==null||isFinishing()||isDestroyed())return;
        boolean full=billing!=null&&billing.full();
        String a=billing==null?"":billing.annualPrice(),p=billing==null?"":billing.price();
        annualPrice.setText(L10n.f("%1$s/년",a.isEmpty()?"₩2,900":a));
        permanentPrice.setText(L10n.f("%1$s/1회",p.isEmpty()?"₩9,900":p));
        setButton(annualBuy,!full&&billing!=null&&billing.ready(true));
        setButton(permanentBuy,!full&&billing!=null&&billing.ready(false));
        if(full)status.setText(L10n.t("풀버전 사용 중"));
        else if(a.isEmpty()||p.isEmpty())status.setText(L10n.t("표시된 원화는 한국 출시 예정가입니다. 실제 상품 정보가 확인되면 구매할 수 있습니다."));
        else status.setText("");
        status.setVisibility(status.length()==0?View.GONE:View.VISIBLE);
    }
    @Override protected void onResume(){super.onResume();if(billing!=null){billing.resume();refreshOffers();}}
}
