package com.nasmount.mobile;

import android.content.Context;
import java.util.*;

/** Shared UI-only snapshot for short-lived embedded dialogs. */
final class FlutterOperationSnapshot {
    private FlutterOperationSnapshot(){}
    static Map<String,Object> base(Context context,String route,Map<String,String> labels){
        Map<String,Object> data=new LinkedHashMap<>();
        data.put("route",route);data.put("language",L10n.language());
        data.put("languageIndex",L10n.selectedIndex(context));data.put("themeIndex",AppearanceSettings.mode(context));
        data.put("dark",AppearanceSettings.isDark(context));data.put("direct",BuildConfig.DIRECT_EDITION);
        data.put("full",EditionMonetization.full(context));data.put("version",BuildConfig.VERSION_NAME);
        data.put("languages",Collections.emptyList());data.put("themes",Collections.emptyList());
        data.put("profiles",Collections.emptyList());data.put("profileError",false);data.put("labels",labels);
        return data;
    }
    static Map<String,Object> transfer(){
        FileTransferService.State s=FileTransferService.snapshot();
        Map<String,Object> data=FileOperationUiPolicy.transfer(s.active,s.cancelling,s.finished,s.failed,
                s.cancelled,s.done,s.total,"전송 준비 중...".equals(s.name)?L10n.t("전송 준비 중..."):s.name,
                s.completed,s.count,s.moving);
        data.put("amount",L10n.t(TransferProgressFormat.bytes(s.done))+" / "+
                (s.total<0?L10n.t("전체 용량 확인 불가"):L10n.t(TransferProgressFormat.bytes(s.total))));
        return data;
    }
    static Map<String,String> labels(){
        Map<String,String> m=new LinkedHashMap<>();
        m.put("copy",L10n.t("복사"));m.put("move",L10n.t("이동"));
        m.put("rename",L10n.t("이름 변경"));m.put("delete",L10n.t("삭제"));
        m.put("operationMore",L10n.t("추가 작업"));m.put("paste",L10n.t("붙여넣기"));
        m.put("cancel",L10n.t("취소"));m.put("selectAll",L10n.t("전체 선택"));
        m.put("clearSelection",L10n.t("파일 선택 취소"));
        m.put("clearAll",L10n.t("전체 선택 해제"));m.put("selected",L10n.t("선택"));
        m.put("copyWaiting",L10n.t("복사 대기"));m.put("moveWaiting",L10n.t("이동 대기"));
        m.put("items",L10n.t("항목"));m.put("hide",L10n.t("숨기기"));
        m.put("copying",L10n.t("파일 복사 중..."));m.put("moving",L10n.t("파일 이동 중..."));
        m.put("cancelling",L10n.t("취소 중..."));m.put("preparing",L10n.t("전송 준비 중..."));
        m.put("unknown",L10n.t("전체 용량 확인 불가"));m.put("classicTransfer",L10n.t("기존 전송창"));
        m.put("confirm",L10n.t("확인"));m.put("refresh",L10n.t("새로고침"));
        m.put("error",L10n.t("설정을 확인해 주세요"));
        return m;
    }
}
