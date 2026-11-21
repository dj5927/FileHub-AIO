package com.nasmount.mobile;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * SSHJ's setRemoteCharset controls SFTP path/name packets, not merely a UI
 * label. Legacy NAS servers may send CP949/EUC-KR instead of SFTP UTF-8.
 */
final class SftpFilenameCharset {
    static final String[] OPTIONS = {
            "UTF-8",
            "CP949 / EUC-KR (한국어)",
            "EUC-KR / CP51949 (한국어)",
            "Shift-JIS / CP932 (일본어)",
            "EUC-JP (일본어)",
            "GBK / CP936 (중국어 간체)",
            "GB18030 (중국어 간체)",
            "Big5 / CP950 (중국어 번체)",
            "KOI8-R (키릴어)",
            "KOI8-U (우크라이나어)",
            "Windows-1250 (중부 유럽)",
            "Windows-1251 (키릴어)",
            "Windows-1252 (서유럽)",
            "ISO-8859-1",
            "ISO-8859-2"
    };

    private SftpFilenameCharset() {}

    static Charset resolve(String requested) {
        if (requested == null || requested.trim().isEmpty() ||
                "UTF-8".equalsIgnoreCase(requested.trim()))
            return StandardCharsets.UTF_8;
        String name = requested.trim().split(" ", 2)[0];
        switch (name.toUpperCase(Locale.ROOT)) {
            case "CP949":
            case "MS949":
                return firstSupported("x-windows-949", "MS949", "CP949");
            case "EUC-KR":
                return Charset.forName("EUC-KR");
            case "SHIFT-JIS":
                return Charset.forName("Shift_JIS");
            case "EUC-JP":
                return Charset.forName("EUC-JP");
            case "GBK":
                return Charset.forName("GBK");
            case "GB18030":
                return Charset.forName("GB18030");
            case "BIG5":
                return Charset.forName("Big5");
            case "KOI8-R":
                return Charset.forName("KOI8-R");
            case "KOI8-U":
                return Charset.forName("KOI8-U");
            default:
                if (name.startsWith("Windows-") || name.startsWith("ISO-8859-"))
                    return Charset.forName(name);
                throw new IllegalArgumentException("지원하지 않는 SFTP 파일명 문자셋: " +
                        requested);
        }
    }

    private static Charset firstSupported(String... names) {
        for (String name : names)
            if (Charset.isSupported(name)) return Charset.forName(name);
        throw new IllegalArgumentException("이 기기는 CP949 문자셋을 지원하지 않습니다.");
    }
}
