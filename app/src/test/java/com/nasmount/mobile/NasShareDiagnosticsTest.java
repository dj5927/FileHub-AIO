package com.nasmount.mobile;

import org.junit.Test;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLHandshakeException;
import static org.junit.Assert.*;

public class NasShareDiagnosticsTest {
    @Test public void loginErrorContainsActionableCodeWithoutSecrets() {
        String description = NasShareApi.synologyErrorMessage("SYNO.API.Auth", "login", 400);
        assertTrue(description.contains("400"));
        assertTrue(description.contains("로그인"));
    }

    @Test public void listSessionAndPermissionErrorsDiffer() {
        String session = NasShareApi.synologyErrorMessage("SYNO.FileStation.List",
                "list_share", 119);
        String permission = NasShareApi.synologyErrorMessage("SYNO.FileStation.List",
                "list_share", 403);
        assertTrue(session.contains("세션"));
        assertTrue(permission.contains("권한"));
    }

    @Test public void sslDnsTimeoutAndConnectionErrorsAreDistinct() {
        assertTrue(NasShareApi.networkMessage(new SSLHandshakeException("private"))
                .contains("인증서"));
        assertTrue(NasShareApi.networkMessage(new UnknownHostException("private"))
                .contains("도메인"));
        assertTrue(NasShareApi.networkMessage(new SocketTimeoutException("private"))
                .contains("초과"));
        assertTrue(NasShareApi.networkMessage(new ConnectException("private"))
                .contains("포트"));
        assertFalse(NasShareApi.networkMessage(new UnknownHostException("secret.example"))
                .contains("secret.example"));
    }

    @Test public void typedFailureHasSafePublicMessage() {
        NasShareApi.Failure failure = new NasShareApi.Failure(
                "DSM 로그인 실패(400)", new java.io.IOException("secret"));
        assertEquals("DSM 로그인 실패(400)", failure.userMessage());
        assertFalse(failure.userMessage().contains("secret"));
    }
}
