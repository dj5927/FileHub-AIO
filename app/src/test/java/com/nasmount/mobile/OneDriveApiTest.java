package com.nasmount.mobile;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class OneDriveApiTest {
    private static final String TOKEN="fake-test-token";
    private static OneDriveApi api(MockWebServer s){
        return new OneDriveApi(new OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build(),s.url("/v1.0/"),new OneDriveApi.Tokens(){
            public String token(){return TOKEN;}public void rejected(String t){}
        });
    }
    private static JSONObject item(String id,String name,long size,boolean folder)throws Exception{
        return new JSONObject().put("id",id).put("name",name).put("size",size).put("eTag","revision-1").put(folder?"folder":"file",new JSONObject());
    }
    private static JSONObject listing(JSONObject...nodes)throws Exception{
        JSONArray a=new JSONArray();for(JSONObject n:nodes)a.put(n);return new JSONObject().put("value",a);
    }
    private static void reply(MockWebServer s,JSONObject o){s.enqueue(new MockResponse().setBody(o.toString()).setHeader("Content-Type","application/json"));}
    private static RecordedRequest req(MockWebServer s,boolean authorized)throws Exception{
        RecordedRequest r=s.takeRequest(2,TimeUnit.SECONDS);assertNotNull(r);
        assertEquals(authorized?"Bearer "+TOKEN:null,r.getHeader("Authorization"));return r;
    }
    @Test public void listFollowsGraphPagesAndPreservesPaths()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();
            reply(s,listing(item("a","folder",0,true).put("folder",new JSONObject().put("childCount",42))).put("@odata.nextLink",s.url("/v1.0/next").toString()));
            reply(s,listing(item("b","hello.txt",3,false)));
            List<Models.Entry> result=api(s).list("/");
            assertEquals(2,result.size());assertEquals("/hello.txt",result.get(1).path);
            assertEquals(42,result.get(0).serverChildCount);
            assertTrue(req(s,true).getPath().contains("/me/drive/root/children?"));
            assertEquals("/v1.0/next",req(s,true).getPath());assertEquals(2,s.getRequestCount());
        }
    }
    @Test public void paginationCannotSendBearerToAnotherOrigin()throws Exception{
        try(MockWebServer s=new MockWebServer();MockWebServer other=new MockWebServer()){
            s.start();other.start();
            reply(s,listing().put("@odata.nextLink",other.url("/v1.0/next").toString()));
            assertThrows(IOException.class,()->api(s).list("/"));
            req(s,true);assertEquals(0,other.getRequestCount());
        }
    }
    @Test public void rangedContentUsesSignedUrlWithoutBearer()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,item("id","video.mp4",3,false).put("@microsoft.graph.downloadUrl",s.url("/signed").toString()));
            s.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 1-2/3").setBody("bc"));
            try(InputStream in=api(s).open("/video.mp4",1)){assertEquals("bc",new String(in.readAllBytes()));}
            assertTrue(req(s,true).getPath().contains("/root:/video.mp4?"));
            RecordedRequest r=req(s,false);assertEquals("/signed",r.getPath());assertEquals("bytes=1-",r.getHeader("Range"));
        }
    }
    @Test public void smallNewUploadUsesOneContentPutAndConflictGuard()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,item("root","root",0,true));s.enqueue(new MockResponse().setResponseCode(404));
            reply(s,item("new","data.txt",3,false));
            api(s).upload("/","data.txt",new ByteArrayInputStream(new byte[]{1,2,3}),3,false);
            req(s,true);
            assertTrue(req(s,true).getPath().contains("/items/root:/data.txt?"));
            RecordedRequest put=req(s,true);assertEquals("PUT",put.getMethod());
            assertTrue(put.getPath().contains("/items/root:/data.txt:/content?"));
            assertEquals("fail",put.getRequestUrl().queryParameter("@microsoft.graph.conflictBehavior"));
            assertEquals("*",put.getHeader("If-None-Match"));
            assertArrayEquals(new byte[]{1,2,3},put.getBody().readByteArray());
            assertEquals(3,s.getRequestCount());
        }
    }
    @Test public void largeNewUploadUsesConflictProtectedSession()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            int length=4*1024*1024+1;
            s.start();reply(s,item("root","root",0,true));s.enqueue(new MockResponse().setResponseCode(404));
            reply(s,new JSONObject().put("uploadUrl",s.url("/signed-upload").toString()));
            reply(s,item("new","data.bin",length,false));
            api(s).upload("/","data.bin",new ByteArrayInputStream(new byte[length]),length,false);
            req(s,true);req(s,true);RecordedRequest init=req(s,true);
            assertTrue(init.getPath().contains("/items/root:/data.bin:/createUploadSession"));
            assertEquals("fail",new JSONObject(init.getBody().readUtf8()).getJSONObject("item").getString("@microsoft.graph.conflictBehavior"));
            RecordedRequest data=req(s,false);assertEquals("bytes 0-"+(length-1)+"/"+length,data.getHeader("Content-Range"));
            assertEquals(length,data.getBodySize());
        }
    }
    @Test public void nestedListAndCountDoNotResolveParentFirst()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,listing(item("f","a.txt",2,false)));
            List<Models.Entry> result=api(s).list("/A B/한글");
            assertEquals("/A B/한글/a.txt",result.get(0).path);
            RecordedRequest list=req(s,true);
            assertEquals(Arrays.asList("v1.0","me","drive","root:","A B","한글:","children"),list.getRequestUrl().pathSegments());
            reply(s,listing(new JSONObject().put("name","a.txt"),new JSONObject().put("name",".hidden")));
            assertEquals(1,api(s).countChildren("/A B/한글",false));
            assertEquals("name",req(s,true).getRequestUrl().queryParameter("$select"));
            assertEquals(2,s.getRequestCount());
        }
    }
    @Test public void readRetriesOnlyBoundedRetryAfterAndNeverReplaysWrite()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();s.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","0"));
            reply(s,listing());assertTrue(api(s).list("/").isEmpty());req(s,true);req(s,true);
            s.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","600"));
            assertThrows(CloudProvider.CloudFailure.class,()->api(s).list("/"));req(s,true);
            reply(s,item("root","root",0,true));s.enqueue(new MockResponse().setResponseCode(429).setHeader("Retry-After","0"));
            assertThrows(CloudProvider.CloudFailure.class,()->api(s).mkdir("/folder"));req(s,true);assertEquals("POST",req(s,true).getMethod());
            assertEquals(5,s.getRequestCount());
            assertEquals(5000,OneDriveApi.retryDelayMillis("5"));assertEquals(-1,OneDriveApi.retryDelayMillis("6"));
        }
    }
    @Test public void smallUploadRaceFailsWithoutReplacingExistingFile()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,item("root","root",0,true));s.enqueue(new MockResponse().setResponseCode(404));
            s.enqueue(new MockResponse().setResponseCode(409));
            assertThrows(FileTransferEngine.NameConflict.class,()->api(s).upload("/","data.txt",new ByteArrayInputStream(new byte[]{1}),1,false));
            req(s,true);req(s,true);assertEquals("PUT",req(s,true).getMethod());assertEquals(3,s.getRequestCount());
        }
    }
    @Test public void mkdirUsesServerConflictGuardWithoutDirectoryScan()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,item("root","root",0,true));reply(s,item("new","folder",0,true));
            api(s).mkdir("/folder");req(s,true);RecordedRequest post=req(s,true);
            assertEquals("POST",post.getMethod());
            assertEquals("fail",new JSONObject(post.getBody().readUtf8()).getString("@microsoft.graph.conflictBehavior"));
            assertEquals(2,s.getRequestCount());
        }
    }
    @Test public void overwriteAndDeleteUseRevisionGuard()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();JSONObject old=item("old","data.txt",3,false);
            reply(s,item("root","root",0,true));reply(s,old);
            reply(s,new JSONObject().put("uploadUrl",s.url("/signed-upload").toString()));
            reply(s,item("old","data.txt",1,false));
            api(s).upload("/","data.txt",new ByteArrayInputStream(new byte[]{9}),1,true);
            req(s,true);req(s,true);RecordedRequest init=req(s,true);assertEquals("revision-1",init.getHeader("If-Match"));req(s,false);
            reply(s,old);s.enqueue(new MockResponse().setResponseCode(204));api(s).delete("/data.txt");
            req(s,true);RecordedRequest del=req(s,true);assertEquals("DELETE",del.getMethod());assertEquals("revision-1",del.getHeader("If-Match"));
        }
    }
    @Test public void existingNameWithoutOverwriteDoesNotCreateSession()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,item("root","root",0,true));reply(s,item("x","DATA.TXT",2,false));
            assertThrows(FileTransferEngine.NameConflict.class,()->api(s).upload("/","data.txt",new ByteArrayInputStream(new byte[]{1}),1,false));
            req(s,true);req(s,true);assertEquals(2,s.getRequestCount());
        }
    }
    @Test public void registeredPublicClientUsesExactNativeRedirects(){
        assertEquals("com.nasmount.mobile://oauth2redirect",OneDriveAuth.redirectForPackage("com.nasmount.mobile"));
        assertEquals("com.filehubaio.mobile://oauth2redirect",OneDriveAuth.redirectForPackage("com.filehubaio.mobile"));
        assertTrue(BuildConfig.ONEDRIVE_CLIENT_ID.isEmpty() || BuildConfig.ONEDRIVE_CLIENT_ID.matches("[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}"));
        assertTrue(OneDriveAuth.SCOPES.contains("https://graph.microsoft.com/Files.ReadWrite"));
        assertTrue(OneDriveAuth.SCOPES.contains("https://graph.microsoft.com/User.Read"));
        assertTrue(OneDriveAuth.SCOPES.contains("offline_access"));
    }
    @Test public void identityAcceptsConsumerWithoutMailButRequiresStableIds()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start();reply(server,new JSONObject().put("id","user").put("mail",JSONObject.NULL).put("userPrincipalName",JSONObject.NULL));
            reply(server,new JSONObject().put("id","drive"));
            OneDriveApi.Identity result=api(server).identity();
            assertEquals("OneDrive",result.email);
            assertEquals("user",result.userId);assertEquals("drive",result.driveId);
            req(server,true);req(server,true);
            reply(server,new JSONObject().put("id",JSONObject.NULL));reply(server,new JSONObject().put("id","drive"));
            assertThrows(IOException.class,()->api(server).identity());
        }
    }
    @Test public void retryUsesExistingConnectionIdentity()throws Exception{
        Models.RemoteProfile existing=new Models.RemoteProfile();existing.protocol="OneDrive";existing.host="graph.microsoft.com";
        existing.username="test";existing.fingerprint=OneDriveAuth.marker("user","drive");
        String marker=existing.fingerprint;
        java.util.List<Models.RemoteProfile> profiles=java.util.Collections.singletonList(existing);
        assertSame(existing,OneDriveLinkPolicy.choose(profiles,null,marker));
        assertSame(existing,OneDriveLinkPolicy.choose(profiles,existing.id,marker));
        assertTrue(OneDriveLinkPolicy.visible(profiles,existing.id,marker));
        assertNotEquals(existing.id,OneDriveLinkPolicy.choose(profiles,null,OneDriveAuth.marker("other","drive")).id);
        assertThrows(IOException.class,()->OneDriveLinkPolicy.choose(profiles,existing.id,"onedrive:other:drive"));
        assertFalse(OneDriveLinkPolicy.visible(profiles,existing.id,"onedrive:other:drive"));
    }
    @Test public void diagnosticsNeverIncludeExceptionMessages(){
        String code=OneDriveDiagnostics.code("OD22_SAVE",new IOException("private@email.test token=secret"));
        assertEquals("OD22_SAVE IOException",code);
        assertEquals("OD99_UNKNOWN",OneDriveDiagnostics.code("private@email.test",null));
        assertFalse(code.contains("secret"));
    }
    @Test public void linkedProfilesRejectLegacyPasswords(){
        Models.RemoteProfile p=new Models.RemoteProfile();p.protocol="OneDrive";p.host="graph.microsoft.com";p.username="test@example.test";
        assertFalse(OneDriveAuth.linked(p));p.fingerprint=OneDriveAuth.marker("user","drive");assertTrue(OneDriveAuth.linked(p));
        p.password="legacy";assertFalse(OneDriveAuth.linked(p));
    }
}
