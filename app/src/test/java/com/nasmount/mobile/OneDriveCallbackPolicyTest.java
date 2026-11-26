package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class OneDriveCallbackPolicyTest {
    private static final String DIRECT="com.nasmount.mobile://oauth2redirect";
    private static final String PLAY="com.filehubaio.mobile://oauth2redirect";
    @Test public void exactRedirectAndEncodedCode()throws Exception{
        OneDriveCallbackPolicy.Result r=OneDriveCallbackPolicy.parse(DIRECT+"?code=a%2Bb%2Fc&state=state&session_state=x",DIRECT);
        assertEquals("a+b/c",r.code);assertEquals("state",r.state);assertFalse(r.denied);
        assertEquals("code",OneDriveCallbackPolicy.parse(PLAY+"?state=s&code=code",PLAY).code);
    }
    @Test public void microsoftHostOnlyRedirectMayGainTrailingSlashAndEmptyFragment()throws Exception{
        for(String base:new String[]{DIRECT,PLAY}){
            for(String suffix:new String[]{"","/"}){
                for(String fragment:new String[]{"","#"}){
                    OneDriveCallbackPolicy.Result r=OneDriveCallbackPolicy.parse(base+suffix+"?state=state&code=a%2Bb"+fragment,base);
                    assertEquals("a+b",r.code);assertEquals("state",r.state);
                    assertTrue(OneDriveCallbackPolicy.parse(base+suffix+"?error=access_denied&state=state"+fragment,base).denied);
                }
            }
            assertFalse(OneDriveCallbackPolicy.matchesEndpoint(new java.net.URI(base+"/other?state=s"),new java.net.URI(base)));
            assertFalse(OneDriveCallbackPolicy.matchesEndpoint(new java.net.URI(base+":123/?state=s"),new java.net.URI(base)));
            assertFalse(OneDriveCallbackPolicy.matchesEndpoint(new java.net.URI(base.replace("://","://user@")+"/?state=s"),new java.net.URI(base)));
        }
    }
    @Test public void rejectsForeignSchemePathFragmentAndDuplicateParameters(){
        String[] invalid={PLAY+"?state=s&code=c",DIRECT+"/other?state=s&code=c",DIRECT+"//?state=s&code=c",
            DIRECT+"?state=s&code=c#fragment",DIRECT+"?state=s&state=t&code=c",
            DIRECT+"?state=s&code=c&code=d",DIRECT+"?code=c",DIRECT+"?state=s",
            DIRECT+"?state=s&code=c&error=access_denied",DIRECT+"?state=s&code="};
        for(String u:invalid)assertThrows(Exception.class,()->OneDriveCallbackPolicy.parse(u,DIRECT));
    }
    @Test public void denialRequiresState()throws Exception{
        assertTrue(OneDriveCallbackPolicy.parse(DIRECT+"?error=access_denied&state=s",DIRECT).denied);
        assertThrows(Exception.class,()->OneDriveCallbackPolicy.parse(DIRECT+"?error=access_denied",DIRECT));
    }
    @Test public void stateAndExpiryChecked(){
        long created=1000L;
        assertTrue(OneDriveCallbackPolicy.matches("state","state",created,created+100));
        assertFalse(OneDriveCallbackPolicy.matches("state","different",created,created+100));
        assertFalse(OneDriveCallbackPolicy.matches("state","state",created,created-1));
        assertFalse(OneDriveCallbackPolicy.matches("state","state",created,created+OneDriveCallbackPolicy.MAX_AGE+1));
        assertFalse(OneDriveCallbackPolicy.matches(null,"state",created,created+100));
    }
    @Test public void anrTraceOnlyContainsMainJavaFrames()throws Exception{
        String sample="private data\n\"main\" prio=5\n  at com.example.App.run(App.java:42)\n  hidden url token=private\n\"worker\"\n  at com.example.Other.run(Other.java:4)\n";
        String result=StartupCrashNote.mainFrames(new java.io.ByteArrayInputStream(sample.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(result.contains("App.run"));assertFalse(result.contains("private"));assertFalse(result.contains("Other.run"));
    }
}
