package com.nasmount.mobile;

import java.util.Locale;

/** UI-only formatting; never invent a percentage for sources with unknown size. */
final class TransferProgressFormat {
    private TransferProgressFormat() {}

    static int percent(long transferred, long total, boolean completed) {
        if (completed) return 100;
        if (total <= 0 || transferred < 0) return -1;
        return (int) Math.min(99L, (transferred / (double) total) * 100d);
    }

    static String bytes(long count) {
        if (count < 0) return "확인 중";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double size = count;
        int unit = 0;
        while (size >= 1024d && unit < units.length - 1) {
            size /= 1024d;
            unit++;
        }
        return unit == 0 ? count + " B" :
                String.format(Locale.KOREA, "%.1f %s", size, units[unit]);
    }

    static String amount(long transferred, long total) {
        return bytes(transferred) + " / " +
                (total < 0 ? "전체 용량 확인 불가" : bytes(total));
    }
}
