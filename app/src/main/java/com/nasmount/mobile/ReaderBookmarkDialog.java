package com.nasmount.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntUnaryOperator;

/** Multiple bookmarks, direct jump and explicit deletion for both readers. */
final class ReaderBookmarkDialog {
    private ReaderBookmarkDialog(){}

    static void show(Activity activity,String bookId,int current,
                     boolean comic,int total,IntConsumer jump) {
        show(activity,bookId,current,comic,total,jump,
                point->ReaderHistory.percentFromUnits(point,total));
    }
    static void show(Activity activity,String bookId,int current,
                     boolean comic,int total,IntConsumer jump,
                     IntUnaryOperator progressPercentage) {
        List<Integer> markers=ReaderHistory.bookmarks(activity,bookId);
        boolean saved=markers.contains(current);
        List<String> rows=new ArrayList<>();
        rows.add(saved?"★ 현재 위치 책갈피 해제":"☆ 현재 위치에 책갈피 추가");
        for(int position:markers) {
            String spot=comic?(position+1)+"쪽":
                    progressPercentage.applyAsInt(position)+"%";
            rows.add("이동  ·  "+spot);
            rows.add("삭제  ·  "+spot);
        }
        new AlertDialog.Builder(activity)
                .setTitle("책갈피  ·  "+markers.size()+"개")
                .setItems(rows.toArray(new String[0]),(dialog,chosen)->{
                    if(chosen==0) {
                        ReaderHistory.setBookmark(activity,bookId,current,!saved);
                        android.widget.Toast.makeText(activity,saved?
                                "책갈피를 삭제했습니다.":"책갈피를 저장했습니다.",
                                android.widget.Toast.LENGTH_SHORT).show();
                    }else {
                        int marker=markers.get((chosen-1)/2);
                        if(chosen%2==1)jump.accept(marker);
                        else {
                            ReaderHistory.setBookmark(activity,bookId,marker,false);
                            show(activity,bookId,current,comic,total,jump,
                                    progressPercentage);
                        }
                    }
                }).setNegativeButton(L10n.t("닫기"),null).show();
    }
}
