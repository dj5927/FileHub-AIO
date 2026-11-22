package com.nasmount.mobile;

import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.util.Locale;

/** Safe SFTP error codes: no host address, username or credentials in UI. */
final class SftpConnectionDiagnostics extends IOException {
    final String phase;
    final String code;
    final String hint;

    private SftpConnectionDiagnostics(String phase, String code, String hint,
                                      Exception original) {
        super("SFTP " + code, original);
        this.phase = phase;
        this.code = code;
        this.hint = hint;
    }

    String userMessage() {
        return "SFTP " + phase + " 단계에서 연결하지 못했습니다.\n" +
                hint + "\n\n진단 코드: " + code;
    }

    static SftpConnectionDiagnostics from(String phase, Exception cause) {
        String text = "";
        Throwable last = cause;
        for (Throwable e = cause; e != null; e = e.getCause()) {
            if (e instanceof NoRouteToHostException)
                return new SftpConnectionDiagnostics(phase, "SFTP-NET-ROUTE",
                        "휴대폰에서 서버에 접근할 수 없습니다. " +
                        "동일 Wi-Fi 및 공유기 기기 간 격리 설정을 확인하세요.", cause);
            if (e instanceof ConnectException)
                return new SftpConnectionDiagnostics(phase, "SFTP-NET-REFUSED",
                        "서버가 SSH 연결을 거부했습니다. SSH 서비스 및 포트를 확인하세요.",
                        cause);
            if (e instanceof SocketTimeoutException)
                return new SftpConnectionDiagnostics(phase, "SFTP-NET-TIMEOUT",
                        "서버가 응답하지 않습니다. 휴대폰 네트워크 연결을 확인하세요.", cause);
            if (e instanceof net.schmizz.sshj.userauth.UserAuthException)
                return new SftpConnectionDiagnostics(phase, "SFTP-AUTH-PASSWORD",
                        "사용자명 또는 비밀번호 인증이 거부됐습니다.", cause);
            if (e.getMessage() != null)
                text += " " + e.getMessage().toLowerCase(Locale.ROOT);
            last = e;
        }
        if (text.contains("don't know how to encode key") ||
                text.contains("openssleddsapublickey"))
            return new SftpConnectionDiagnostics(phase, "SFTP-KEY-ANDROID",
                    "Android SSH 공개키 호환성 오류입니다. " +
                    "ECDSA 우선 연결이 적용됐는지 확인하세요.", cause);
        if (text.contains("no matching") || text.contains("negotiat"))
            return new SftpConnectionDiagnostics(phase, "SFTP-KEX-ALGORITHM",
                    "SSH 키 교환 또는 호스트 키 알고리즘 협상이 일치하지 않습니다.",
                    cause);
        if (text.contains("connection reset") || text.contains("broken pipe") ||
                text.contains("connection closed"))
            return new SftpConnectionDiagnostics(phase, "SFTP-NET-CLOSED",
                    "서버 또는 네트워크에서 SSH 연결이 종료됐습니다.", cause);
        String type = last.getClass().getSimpleName();
        if (!type.matches("[A-Za-z0-9_$]{1,75}")) type = "Unknown";
        return new SftpConnectionDiagnostics(phase,
                "SFTP-" + phase + "-" + type,
                "SSH 연결에 실패했습니다. 비밀번호나 서버 주소 대신 이 진단 코드를 알려주세요.",
                cause);
    }
}
