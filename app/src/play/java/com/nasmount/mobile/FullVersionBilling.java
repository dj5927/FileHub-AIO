package com.nasmount.mobile;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import com.android.billingclient.api.*;
import java.util.Collections;
import java.util.List;

/** Permanent purchase and renewable annual subscription have separate ownership.
 * Subscription access is confirmed by Play, never by an indefinitely cached receipt. */
final class FullVersionBilling {
    private final Application app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final PlayReceiptVerifier verifier;
    private final Runnable changed;
    private BillingClient client;
    private boolean connecting,querying,productQuerying,restoreRequested,acknowledging;
    private long purchaseGeneration,lastQuery=-300000L;
    private volatile long subscriptionVerifiedAt=-1;
    private volatile boolean permanent,subscription,checked;
    private boolean ownershipConfirmed,checkoutPending;
    private ProductDetails permanentDetails,annualDetails;
    private ProductDetails.OneTimePurchaseOfferDetails permanentOffer;
    private ProductDetails.SubscriptionOfferDetails annualOffer;
    private String annualPrice="";
    FullVersionBilling(Application app,Runnable changed){
        this.app=app;this.changed=changed;
        prefs=app.getSharedPreferences("filehub_play_receipt",0);
        verifier=new PlayReceiptVerifier(BuildConfig.PLAY_BILLING_PUBLIC_KEY);
        permanent=verifier.valid(prefs.getString("json",""),prefs.getString("signature",""),app.getPackageName());
        checked=!verifier.configured();
    }
    private boolean ensureClient(){
        if(!configured())return false;
        if(client!=null)return true;
        try{
            client=BillingClient.newBuilder(app)
                .setListener((result,purchases)->main.post(()->onPurchases(result,purchases)))
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection().build();
            return true;
        }catch(RuntimeException|LinkageError failure){
            android.util.Log.w("FileHubPlay","Billing initialization unavailable: "+failure.getClass().getSimpleName());return false;
        }
    }
    boolean full(){return permanent||(subscription&&SystemClock.elapsedRealtime()-subscriptionVerifiedAt<15*60*1000L);}
    boolean checked(){return checked;}
    boolean configured(){return verifier.configured();}
    String price(){return permanentOffer==null?"":permanentOffer.getFormattedPrice();}
    String annualPrice(){return annualPrice;}
    boolean ready(boolean annual){return !checkoutPending&&ownershipConfirmed&&configured()&&client!=null&&client.isReady()&&
        (annual?annualDetails!=null&&annualOffer!=null:permanentDetails!=null&&permanentOffer!=null);}
    private void publish(){changed.run();}
    private void message(String korean){Toast.makeText(app,L10n.t(korean),Toast.LENGTH_LONG).show();}
    void tick(){
        long now=SystemClock.elapsedRealtime();
        if(subscription&&now-subscriptionVerifiedAt>=15*60*1000L){subscription=false;publish();}
        if(configured()&&now-lastQuery>=5*60*1000L&&!querying&&!acknowledging){lastQuery=now;resume();}
    }
    void resume(){
        if(!ensureClient()){checked=true;restoreMessage(false);return;}
        try{
            if(!client.isReady()){
                if(connecting)return;connecting=true;
                client.startConnection(new BillingClientStateListener(){
                    @Override public void onBillingSetupFinished(BillingResult result){
                        main.post(()->{connecting=false;
                            if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){queryOwned();queryProduct();}
                            else{checked=true;publish();restoreMessage(false);}
                        });
                    }
                    @Override public void onBillingServiceDisconnected(){main.post(()->{connecting=false;publish();});}
                });
            }else{queryOwned();queryProduct();}
        }catch(RuntimeException|LinkageError failure){
            connecting=false;querying=false;productQuerying=false;checked=true;
            android.util.Log.w("FileHubPlay","Billing resume unavailable: "+failure.getClass().getSimpleName());
            publish();restoreMessage(false);
        }
    }
    void restore(){
        if(!configured()){message("결제 설정이 완료되지 않았습니다.");return;}
        restoreRequested=true;resume();
    }
    private void restoreMessage(boolean confirmed){
        if(!restoreRequested)return;restoreRequested=false;
        message(confirmed?(full()?"풀버전 구매를 복원했습니다.":"복원할 구매 내역이 없습니다."):
                "Google Play 구매 내역을 확인할 수 없습니다.");
    }
    private void queryOwned(){
        if(querying||acknowledging||!client.isReady())return;
        querying=true;lastQuery=SystemClock.elapsedRealtime();
        ownershipConfirmed=false;publish();
        final long generation=purchaseGeneration;
        // Separate results: failure to query subscriptions must not erase lifetime ownership.
        try{client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            (result,permanentPurchases)->main.post(()->{
                if(generation!=purchaseGeneration){querying=false;queryOwned();return;}
                try{client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build(),
                    (subsResult,subs)->main.post(()->{
                        querying=false;
                        if(generation!=purchaseGeneration){queryOwned();return;}
                        boolean pOk=result.getResponseCode()==BillingClient.BillingResponseCode.OK;
                        boolean sOk=subsResult.getResponseCode()==BillingClient.BillingResponseCode.OK;
                        finishOwned(pOk,permanentPurchases,sOk,subs);
                    }));}catch(RuntimeException|LinkageError error){
                        querying=false;checked=true;publish();restoreMessage(false);
                    }
            }));}catch(RuntimeException|LinkageError error){querying=false;checked=true;ownershipConfirmed=false;publish();restoreMessage(false);}
    }
    private boolean hasPending(List<Purchase> purchases,String product){
        if(purchases==null)return false;
        for(Purchase p:purchases)if(p.getProducts().contains(product)&&p.getPurchaseState()==Purchase.PurchaseState.PENDING)return true;
        return false;
    }
    private Purchase findValid(List<Purchase> purchases,String product){
        if(purchases==null)return null;
        Purchase unacknowledged=null;
        for(Purchase p:purchases){
            if(!p.getProducts().contains(product)||p.getPurchaseState()!=Purchase.PurchaseState.PURCHASED||
                    !verifier.validForProduct(p.getOriginalJson(),p.getSignature(),app.getPackageName(),product))continue;
            if(p.isAcknowledged())return p;
            unacknowledged=p;
        }
        return unacknowledged;
    }
    private void finishOwned(boolean pOk,List<Purchase> pList,boolean sOk,List<Purchase> sList){
        boolean was=full();
        Purchase p=pOk?findValid(pList,MonetizationPolicy.PRODUCT_ID):null;
        Purchase s=sOk?findValid(sList,MonetizationPolicy.ANNUAL_PRODUCT_ID):null;
        if(pOk){
            permanent=p!=null&&p.isAcknowledged()&&prefs.edit().putString("json",p.getOriginalJson())
                .putString("signature",p.getSignature()).commit();
            if(!permanent)prefs.edit().clear().apply();
        }
        if(sOk){
            subscription=s!=null&&s.isAcknowledged();
            subscriptionVerifiedAt=subscription?SystemClock.elapsedRealtime():-1;
        }
        ownershipConfirmed=pOk&&sOk&&
            (p==null||p.isAcknowledged())&&(s==null||s.isAcknowledged())&&
            !hasPending(pList,MonetizationPolicy.PRODUCT_ID)&&!hasPending(sList,MonetizationPolicy.ANNUAL_PRODUCT_ID);
        checked=true;publish();
        Purchase toAck=p!=null&&!p.isAcknowledged()?p:s!=null&&!s.isAcknowledged()?s:null;
        if(toAck!=null){
            acknowledging=true;
            try{client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(toAck.getPurchaseToken()).build(),
                result->main.post(()->{
                    acknowledging=false;
                    if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){
                        queryOwned();
                    }else{restoreMessage(false);message("구매 확인에 실패했습니다. 구매 복원을 다시 시도하세요.");}
                }));}catch(RuntimeException|LinkageError error){acknowledging=false;restoreMessage(false);}
            return;
        }
        if(restoreRequested)restoreMessage(pOk&&sOk);
        else if(!was&&full())message("풀버전 구매가 완료되었습니다.");
    }
    private void queryProduct(){
        if(!client.isReady()||productQuerying)return;
        productQuerying=true;
        QueryProductDetailsParams.Product p=QueryProductDetailsParams.Product.newBuilder()
                .setProductId(MonetizationPolicy.PRODUCT_ID).setProductType(BillingClient.ProductType.INAPP).build();
        // Billing queries use one product type per request.
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(p)).build(),(result,products)->main.post(()->{
                    permanentDetails=null;permanentOffer=null;
                    if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){
                        for(ProductDetails d:products.getProductDetailsList()){
                            if(!MonetizationPolicy.PRODUCT_ID.equals(d.getProductId()))continue;
                            ProductDetails.OneTimePurchaseOfferDetails o=d.getOneTimePurchaseOfferDetails();
                            if(o!=null){permanentDetails=d;permanentOffer=o;break;}
                        }
                    }
                    publish();queryAnnualProduct();
                }));
    }
    private void queryAnnualProduct(){
        if(!client.isReady()){productQuerying=false;publish();return;}
        QueryProductDetailsParams.Product p=QueryProductDetailsParams.Product.newBuilder()
                .setProductId(MonetizationPolicy.ANNUAL_PRODUCT_ID).setProductType(BillingClient.ProductType.SUBS).build();
        try{client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(p)).build(),(result,products)->main.post(()->{
                productQuerying=false;annualDetails=null;annualOffer=null;annualPrice="";
                if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){
                    for(ProductDetails d:products.getProductDetailsList()){
                        if(!MonetizationPolicy.ANNUAL_PRODUCT_ID.equals(d.getProductId())||d.getSubscriptionOfferDetails()==null)continue;
                        for(ProductDetails.SubscriptionOfferDetails o:d.getSubscriptionOfferDetails()){
                            if(!MonetizationPolicy.ANNUAL_BASE_PLAN.equals(o.getBasePlanId())||o.getOfferId()!=null||
                                o.getInstallmentPlanDetails()!=null)continue;
                            List<ProductDetails.PricingPhase> phases=o.getPricingPhases().getPricingPhaseList();
                            if(phases.size()!=1)continue;
                            ProductDetails.PricingPhase phase=phases.get(0);
                            if(!"P1Y".equals(phase.getBillingPeriod())||phase.getRecurrenceMode()!=ProductDetails.RecurrenceMode.INFINITE_RECURRING)continue;
                            annualDetails=d;annualOffer=o;annualPrice=phase.getFormattedPrice();break;
                        }
                    }
                }
                publish();
            }));}catch(RuntimeException|LinkageError error){productQuerying=false;annualDetails=null;annualOffer=null;annualPrice="";publish();}
    }
    void buy(Activity activity,boolean annual){
        if(full()){message("이미 풀버전을 사용 중입니다.");return;}
        if(!configured()){message("결제 설정이 완료되지 않았습니다.");return;}
        if(!ensureClient()||!ready(annual)){message("Google Play 상품 정보를 불러올 수 없습니다.");resume();return;}
        try{
            BillingFlowParams.ProductDetailsParams.Builder product=BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(annual?annualDetails:permanentDetails);
            String token=annual?annualOffer.getOfferToken():permanentOffer.getOfferToken();
            if(token!=null&&!token.isEmpty())product.setOfferToken(token);
            BillingResult result=client.launchBillingFlow(activity,BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(product.build())).build());
            if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){checkoutPending=true;publish();}
            else if(result.getResponseCode()==BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)restore();
            else if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK&&result.getResponseCode()!=BillingClient.BillingResponseCode.USER_CANCELED)
                message("Google Play 결제 화면을 열 수 없습니다.");
        }catch(RuntimeException|LinkageError error){message("Google Play 결제 화면을 열 수 없습니다.");}
    }
    private void onPurchases(BillingResult result,List<Purchase> purchases){
        checkoutPending=false;publish();
        if(result.getResponseCode()==BillingClient.BillingResponseCode.OK&&purchases!=null){
            purchaseGeneration++;queryOwned();
            for(Purchase p:purchases)if(p.getPurchaseState()==Purchase.PurchaseState.PENDING){
                message("결제 확인 대기중입니다. 확인 완료 후 풀버전이 열립니다.");break;
            }
        }else if(result.getResponseCode()==BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)restore();
        else if(result.getResponseCode()!=BillingClient.BillingResponseCode.USER_CANCELED)
            message("Google Play 결제를 완료하지 못했습니다.");
    }
}
