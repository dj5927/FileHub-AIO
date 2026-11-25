package com.nasmount.mobile;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.android.billingclient.api.*;
import java.util.Collections;
import java.util.List;

/** Non-consumable Google Play purchase. No local boolean or debug unlock. */
final class FullVersionBilling {
    private final Application app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final PlayReceiptVerifier verifier;
    private final Runnable changed;
    private BillingClient client;
    private boolean connecting,querying,productQuerying,restoreRequested,acknowledging;
    private long purchaseGeneration;
    private volatile boolean full,checked;
    private ProductDetails details;
    private ProductDetails.OneTimePurchaseOfferDetails offer;

    FullVersionBilling(Application app,Runnable changed){
        this.app=app;this.changed=changed;
        prefs=app.getSharedPreferences("filehub_play_receipt",0);
        verifier=new PlayReceiptVerifier(BuildConfig.PLAY_BILLING_PUBLIC_KEY);
        full=verifier.valid(prefs.getString("json",""),prefs.getString("signature",""),app.getPackageName());
        client=BillingClient.newBuilder(app)
                .setListener((result,purchases)->main.post(()->onPurchases(result,purchases)))
                .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
                .enableAutoServiceReconnection()
                .build();
    }
    boolean full(){return full;}
    boolean checked(){return checked;}
    boolean configured(){return verifier.configured();}
    String price(){return offer==null?"":offer.getFormattedPrice();}
    private void publish(){changed.run();}
    private void message(String korean){
        Toast.makeText(app,L10n.t(korean),Toast.LENGTH_LONG).show();
    }
    void resume(){
        if(!client.isReady()){
            if(connecting)return;
            connecting=true;
            client.startConnection(new BillingClientStateListener(){
                @Override public void onBillingSetupFinished(BillingResult result){
                    main.post(()->{
                        connecting=false;
                        if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){
                            queryOwned();queryProduct();
                        }else{checked=true;publish();restoreMessage(false);}
                    });
                }
                @Override public void onBillingServiceDisconnected(){
                    main.post(()->{connecting=false;});
                }
            });
        }else{queryOwned();if(details==null)queryProduct();}
    }
    void restore(){
        restoreRequested=true;resume();
    }
    private void restoreMessage(boolean confirmed){
        if(!restoreRequested)return;
        restoreRequested=false;
        message(confirmed?(full?"풀버전 구매를 복원했습니다.":"복원할 구매 내역이 없습니다."):
                "Google Play 구매 내역을 확인할 수 없습니다.");
    }
    private void queryOwned(){
        if(querying||acknowledging||!client.isReady())return;
        querying=true;
        long generation=purchaseGeneration;
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.INAPP).build(),
                (result,purchases)->main.post(()->{
                    querying=false;
                    if(generation!=purchaseGeneration){queryOwned();return;}
                    if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK){
                        checked=true;publish();restoreMessage(false);return;
                    }
                    processOwned(purchases,true);
                }));
    }
    private void queryProduct(){
        if(!client.isReady()||productQuerying)return;
        productQuerying=true;
        QueryProductDetailsParams.Product product=QueryProductDetailsParams.Product.newBuilder()
                .setProductId(MonetizationPolicy.PRODUCT_ID)
                .setProductType(BillingClient.ProductType.INAPP).build();
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(product)).build(),(result,products)->main.post(()->{
                    productQuerying=false;
                    details=null;offer=null;
                    if(result.getResponseCode()==BillingClient.BillingResponseCode.OK){
                        for(ProductDetails p:products.getProductDetailsList()){
                            if(!MonetizationPolicy.PRODUCT_ID.equals(p.getProductId()))continue;
                            // The console must contain a single permanent buy option.
                            ProductDetails.OneTimePurchaseOfferDetails o=p.getOneTimePurchaseOfferDetails();
                            if(o!=null){details=p;offer=o;break;}
                        }
                    }
                    publish();
                }));
    }
    void buy(Activity activity){
        if(full){message("이미 풀버전을 사용 중입니다.");return;}
        if(!configured()){
            message("결제 설정이 완료되지 않았습니다.");return;
        }
        if(!client.isReady()||details==null||offer==null){
            message("Google Play 상품 정보를 불러올 수 없습니다.");resume();return;
        }
        BillingFlowParams.ProductDetailsParams.Builder product=BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(details);
        String token=offer.getOfferToken();
        if(token!=null&&!token.isEmpty())product.setOfferToken(token);
        BillingResult result=client.launchBillingFlow(activity,BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(Collections.singletonList(product.build())).build());
        if(result.getResponseCode()==BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)restore();
        else if(result.getResponseCode()!=BillingClient.BillingResponseCode.OK&&
                result.getResponseCode()!=BillingClient.BillingResponseCode.USER_CANCELED)
            message("Google Play 결제 화면을 열 수 없습니다.");
    }
    private void onPurchases(BillingResult result,List<Purchase> purchases){
        if(result.getResponseCode()==BillingClient.BillingResponseCode.OK&&purchases!=null){
            purchaseGeneration++;
            processOwned(purchases,false);
        }
        else if(result.getResponseCode()==BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED)restore();
        else if(result.getResponseCode()!=BillingClient.BillingResponseCode.USER_CANCELED)
            message("Google Play 결제를 완료하지 못했습니다.");
    }
    private void processOwned(List<Purchase> purchases,boolean authoritative){
        if(acknowledging)return;
        boolean pending=false,invalid=false;
        for(Purchase purchase:purchases){
            if(!purchase.getProducts().contains(MonetizationPolicy.PRODUCT_ID))continue;
            if(purchase.getPurchaseState()==Purchase.PurchaseState.PENDING){pending=true;continue;}
            if(purchase.getPurchaseState()!=Purchase.PurchaseState.PURCHASED)continue;
            if(!verifier.valid(purchase.getOriginalJson(),purchase.getSignature(),app.getPackageName())){
                invalid=true;continue;
            }
            if(purchase.isAcknowledged()){grant(purchase,authoritative);return;}
            acknowledging=true;
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.getPurchaseToken()).build(),result->main.post(()->{
                        acknowledging=false;
                        if(result.getResponseCode()==BillingClient.BillingResponseCode.OK)grant(purchase,authoritative);
                        else{checked=true;publish();restoreMessage(false);message("구매 확인에 실패했습니다. 구매 복원을 다시 시도하세요.");}
                    }));
            return;
        }
        if(authoritative){
            // A successful authoritative empty/invalid result revokes cached access.
            prefs.edit().clear().apply();
            full=false;checked=true;publish();restoreMessage(!invalid);
        }
        if(pending)message("결제 승인 대기 중입니다. 승인 완료 후 풀버전이 열립니다.");
        else if(invalid)message("구매 서명을 확인할 수 없습니다.");
    }
    private void grant(Purchase purchase,boolean authoritative){
        boolean was=full;
        if(!prefs.edit().putString("json",purchase.getOriginalJson())
                .putString("signature",purchase.getSignature()).commit()){
            checked=true;publish();restoreMessage(false);return;
        }
        full=true;checked=true;publish();
        if(restoreRequested)restoreMessage(true);
        else if(!was)message("풀버전 구매가 완료되었습니다.");
    }
}
