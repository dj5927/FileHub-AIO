package com.nasmount.mobile;

import org.junit.Test;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import static org.junit.Assert.*;

public class SftpConnectionDiagnosticsTest {
    @Test public void errorsExplainNetworkStageWithoutExposingCredentials() {
        SftpConnectionDiagnostics exception = SftpConnectionDiagnostics.from(
                "HANDSHAKE", new NoRouteToHostException(
                        "172.30.1.42 deck secret password"));
        assertEquals("SFTP-NET-ROUTE", exception.code);
        assertTrue(exception.userMessage().contains("HANDSHAKE"));
        assertFalse(exception.userMessage().contains("172.30.1.42"));
        assertFalse(exception.userMessage().contains("password"));
        assertEquals("SFTP-NET-TIMEOUT", SftpConnectionDiagnostics.from(
                "HANDSHAKE", new SocketTimeoutException()).code);
        assertEquals("SFTP-NET-REFUSED", SftpConnectionDiagnostics.from(
                "HANDSHAKE", new ConnectException()).code);
    }

    @Test public void androidConscryptAndAlgorithmErrorsHaveDistinctCodes() {
        assertEquals("SFTP-KEY-ANDROID", SftpConnectionDiagnostics.from(
                "HANDSHAKE", new java.io.IOException(
                        "Don't know how to encode key: com.android.org.conscrypt.OpenSslEdDsaPublicKey")).code);
        assertEquals("SFTP-KEX-ALGORITHM", SftpConnectionDiagnostics.from(
                "HANDSHAKE", new java.io.IOException(
                        "No matching host key algorithm")).code);
    }

    @Test public void genericTransportDoesNotLeakRawExceptionDetails() {
        SftpConnectionDiagnostics e = SftpConnectionDiagnostics.from("FOLDER",
                new java.io.IOException("remote /home/deck/SECRET private file"));
        assertTrue(e.code.startsWith("SFTP-FOLDER-"));
        assertFalse(e.userMessage().contains("SECRET"));
    }
}
