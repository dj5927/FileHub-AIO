package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/** One-shot native-owned confirmation or filename prompt. The classic
 * dialog is available until Flutter has rendered and on initialization failure. */
final class FlutterFilePrompt implements FlutterNavigationSurface.Host {
    private final Activity activity;
    private final String nonce=UUID.randomUUID().toString(),title,message,initial,positive;
    private final Consumer<String> confirm;
    private final BooleanSupplier valid;
    private final Consumer<FlutterFilePrompt> dismissed;
    private AlertDialog dialog;
    private FlutterNavigationSurface surface;
    private EditText input;
    private boolean consumed;
    FlutterFilePrompt(Activity activity,String title,String message,String initial,String positive,
                      Consumer<String> confirm,BooleanSupplier valid,Consumer<FlutterFilePrompt> dismissed) {
        this.activity=activity;this.title=title;this.message=message;this.initial=initial;
        this.positive=positive;this.confirm=confirm;this.valid=valid;this.dismissed=dismissed;
    }
    void show(boolean resumed){
        // Confirmations have no editable content. Android's native alert
        // measures its title/message/buttons instead of sizing a full Flutter
        // surface to the available window height. Long messages still scroll.
        if(initial==null){
            AlertDialog.Builder builder=new AlertDialog.Builder(activity).setTitle(title)
                .setMessage(message).setPositiveButton(positive,null);
            if(confirm!=null)builder.setNegativeButton(L10n.t("취소"),null);
            dialog=builder.create();
            dialog.setOnDismissListener(d->{consumed=true;dismissed.accept(this);});
            dialog.show();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->submit(""));
            return;
        }
        FrameLayout frame=new FrameLayout(activity);
        int height=Ui.dp(activity,initial!=null?275:315);
        frame.setMinimumHeight(height);
        LinearLayout content=Ui.vertical(activity);
        content.setPadding(Ui.dp(activity,22),Ui.dp(activity,15),Ui.dp(activity,22),Ui.dp(activity,8));
        content.addView(Ui.text(activity,title,18,Ui.TEXT,true));
        if(!message.isEmpty())content.addView(Ui.text(activity,message,14,Ui.TEXT,false));
        if(initial!=null){
            input=new EditText(activity);input.setSingleLine(true);input.setText(initial);input.selectAll();
            content.addView(input,Ui.lp(activity,-1,52));
        }
        ScrollView fallback=new ScrollView(activity);fallback.addView(content);
        frame.addView(fallback,new FrameLayout.LayoutParams(-1,height));
        AlertDialog.Builder builder=new AlertDialog.Builder(activity).setView(frame)
                .setPositiveButton(positive,null);
        if(confirm!=null)builder.setNegativeButton(L10n.t("취소"),null);
        dialog=builder.create();
        dialog.setOnDismissListener(d->{
            consumed=true;
            if(surface!=null){surface.close();surface=null;}
            dismissed.accept(this);
        });
        dialog.show();
        if(dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->submit(input==null?"":input.getText().toString()));
        surface=new FlutterNavigationSurface(activity,frame,fallback,FlutterUiContract.FILE_PROMPT,this,resumed);
    }
    private boolean live(){return !consumed&&dialog!=null&&dialog.isShowing()&&
            !activity.isFinishing()&&!activity.isDestroyed();}
    private void submit(String value){
        if(!live()||value.length()>4096||value.indexOf('\0')>=0)return;
        consumed=true;
        boolean allowed=valid.getAsBoolean();
        dialog.dismiss();
        if(allowed&&confirm!=null)confirm.accept(value);
    }
    @Override public Map<String,Object> snapshot(String route){
        if(!live())throw new IllegalStateException("closed prompt");
        Map<String,String> labels=FlutterOperationSnapshot.labels();
        labels.put("classicPrompt",L10n.t("기존 확인창"));
        Map<String,Object> data=FlutterOperationSnapshot.base(activity,route,labels);
        Map<String,Object> prompt=new LinkedHashMap<>();
        prompt.put("nonce",nonce);prompt.put("title",title);prompt.put("message",message);
        prompt.put("initial",initial);prompt.put("positive",positive);prompt.put("cancel",confirm!=null);
        data.put("prompt",prompt);return data;
    }
    @Override public void action(String route,String action,String id){
        if(!live())return;
        if("promptCancel".equals(action))dialog.dismiss();
        else if("promptClassic".equals(action)&&surface!=null){surface.close();surface=null;}
    }
    @Override public void reorderFavorites(Object order){throw new IllegalStateException("prompt");}
    @Override public void explorerCall(MethodCall call,MethodChannel.Result result){
        if(!live()||!"promptSubmit".equals(call.method)||!(call.arguments instanceof Map)||
                !nonce.equals(call.argument("nonce"))||!(call.argument("value") instanceof String)){
            result.error("STALE_PROMPT",null,null);return;
        }
        String value=call.argument("value");
        if(value.length()>4096||value.indexOf('\0')>=0||(initial==null&&!value.isEmpty())){
            result.error("INVALID_INPUT",null,null);return;
        }
        result.success(null);
        new android.os.Handler(android.os.Looper.getMainLooper()).post(()->submit(value));
    }
    private void buttons(boolean visible){
        if(dialog==null||!dialog.isShowing())return;
        for(int id:new int[]{AlertDialog.BUTTON_POSITIVE,AlertDialog.BUTTON_NEGATIVE}){
            Button button=dialog.getButton(id);
            if(button!=null&&(id==AlertDialog.BUTTON_POSITIVE||confirm!=null))
                button.setVisibility(visible?View.VISIBLE:View.GONE);
        }
    }
    @Override public void flutterShown(){buttons(false);}
    @Override public void fallbackShown(){buttons(true);}
    void pause(){if(surface!=null)surface.pause();}
    void resume(){if(surface!=null)surface.resume();}
    void close(){if(dialog!=null&&dialog.isShowing())dialog.dismiss();}
}
