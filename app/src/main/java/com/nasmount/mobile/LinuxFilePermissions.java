package com.nasmount.mobile;

import java.util.Locale;

/** Pure POSIX mode helpers: permission bits only, no file type or network I/O. */
final class LinuxFilePermissions {
    static final int[] RWX_BITS = {
            0400, 0200, 0100, 0040, 0020, 0010, 0004, 0002, 0001
    };
    private LinuxFilePermissions() {}

    static int checkedMode(int mode) {
        if (mode < 0 || mode > 07777)
            throw new IllegalArgumentException("chmod 권한은 0000~7777 범위입니다.");
        return mode;
    }

    static int parseOctal(String text) {
        String candidate = text == null ? "" : text.trim();
        if (!candidate.matches("[0-7]{3,4}"))
            throw new IllegalArgumentException("권한은 755, 644, 0755처럼 3~4자리 8진수로 입력하세요.");
        return checkedMode(Integer.parseInt(candidate, 8));
    }

    static String octal(int mode) {
        return String.format(Locale.ROOT, "%04o", checkedMode(mode));
    }

    static int withRwx(int original, boolean[] checked) {
        if (checked == null || checked.length != 9)
            throw new IllegalArgumentException("소유자·그룹·기타 권한 9개가 필요합니다.");
        int result = checkedMode(original) & 07000;
        for (int i = 0; i < checked.length; i++)
            if (checked[i]) result |= RWX_BITS[i];
        return result;
    }

    static String symbolic(int mode) {
        mode = checkedMode(mode);
        char[] out = new char[9];
        for (int i = 0; i < out.length; i++)
            out[i] = (mode & RWX_BITS[i]) == 0 ? '-' : "rwx".charAt(i % 3);
        if ((mode & 04000) != 0) out[2] = out[2] == 'x' ? 's' : 'S';
        if ((mode & 02000) != 0) out[5] = out[5] == 'x' ? 's' : 'S';
        if ((mode & 01000) != 0) out[8] = out[8] == 'x' ? 't' : 'T';
        return new String(out);
    }
}
