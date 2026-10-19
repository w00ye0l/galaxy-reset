package com.forholiday.galaxyreset;

import java.io.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.SSLProtocolException;
import org.junit.Test;
import static org.junit.Assert.*;

public class WirelessPolicyTest {
    @Test public void closedShellIsAcceptedOnlyAfterItsSuccessfulExitMarker() throws Exception {
        assertEquals("2000",WirelessPolicy.commandOutput("2000\r\n\r\nGALAXY_ADB_EXIT:0\r\n"));
        assertThrows(IOException.class,()->WirelessPolicy.commandOutput("2000\n"));
        assertThrows(IOException.class,()->WirelessPolicy.commandOutput("failed\nGALAXY_ADB_EXIT:1\n"));
    }
    @Test public void automaticStartRequiresPriorConsentAndStopsWhenShizukuIsAlreadyRunning(){
        assertFalse(WirelessPolicy.shouldStart(false,false,false,100,0,true));
        assertFalse(WirelessPolicy.shouldStart(true,true,false,100,0,true));
        assertFalse(WirelessPolicy.shouldStart(true,false,true,100,0,true));
        assertTrue(WirelessPolicy.shouldStart(true,false,false,100,0,false));
    }
    @Test public void automaticRetriesBackOffButManualRetryCanProceed(){
        assertFalse(WirelessPolicy.shouldStart(true,false,false,100,200,false));
        assertTrue(WirelessPolicy.shouldStart(true,false,false,100,200,true));
        assertTrue(WirelessPolicy.shouldStart(true,false,false,200,200,false));
    }
    @Test public void networkFailuresDoNotErasePairing(){
        assertFalse(WirelessPolicy.authenticationFailure(new IOException("Connection refused")));
        assertFalse(WirelessPolicy.authenticationFailure(new InterruptedException("Timed out")));
        assertTrue(WirelessPolicy.authenticationFailure(new IOException(new SSLProtocolException("SSLV3_ALERT_CERTIFICATE_UNKNOWN"))));
    }
    @Test public void identityCertificateSurvivesSerializationAndMatchesTheSavedKey() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair pair=generator.generateKeyPair();
        long now=System.currentTimeMillis();X509Certificate original=AdbCredentials.certificate(pair,now);
        X509Certificate restored=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(original.getEncoded()));
        restored.checkValidity(new java.util.Date(now));restored.verify(pair.getPublic());
        assertArrayEquals(pair.getPublic().getEncoded(),restored.getPublicKey().getEncoded());
        Signature signature=Signature.getInstance("SHA256withRSA");signature.initSign(pair.getPrivate());signature.update(new byte[]{1,2,3});byte[] signed=signature.sign();
        signature.initVerify(restored);signature.update(new byte[]{1,2,3});assertTrue(signature.verify(signed));
    }
}
