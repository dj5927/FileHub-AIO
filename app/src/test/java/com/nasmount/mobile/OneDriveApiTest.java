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
            s.start();reply(s,item("root","root",0,true));
            reply(s,listing(item("a","folder",0,true)).put("@odata.nextLink",s.url("/v1.0/next").toString()));
            reply(s,listing(item("b","hello.txt",3,false)));
            List<Models.Entry> result=api(s).list("/");
            assertEquals(2,result.size());assertEquals("/hello.txt",result.get(1).path);
            assertTrue(req(s,true).getPath().contains("/me/drive/root?"));
            assertTrue(req(s,true).getPath().contains("/items/root/children?"));
            assertEquals("/v1.0/next",req(s,true).getPath());
        }
    }
    @Test public void paginationCannotSendBearerToAnotherOrigin()throws Exception{
        try(MockWebServer s=new MockWebServer();MockWebServer other=new MockWebServer()){
            s.start();other.start();reply(s,item("root","root",0,true));
            reply(s,listing().put("@odata.nextLink",other.url("/v1.0/next").toString()));
            assertThrows(IOException.class,()->api(s).list("/"));
            req(s,true);req(s,true);assertEquals(0,other.getRequestCount());
        }
    }
    @Test public void rangedContentUsesSignedUrlWithoutBearer()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,item("id","video.mp4",3,false));
            reply(s,new JSONObject().put("id","id").put("@microsoft.graph.downloadUrl",s.url("/signed").toString()));
            s.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 1-2/3").setBody("bc"));
            try(InputStream in=api(s).open("/video.mp4",1)){assertEquals("bc",new String(in.readAllBytes()));}
            assertTrue(req(s,true).getPath().contains("/root:/video.mp4?"));req(s,true);
            RecordedRequest r=req(s,false);assertEquals("/signed",r.getPath());assertEquals("bytes=1-",r.getHeader("Range"));
        }
    }
    @Test public void uploadCreatesConflictProtectedSessionAndVerifiesResponse()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();reply(s,item("root","root",0,true));reply(s,listing());
            reply(s,new JSONObject().put("uploadUrl",s.url("/signed-upload").toString()));
            reply(s,item("new","data.txt",3,false));
            api(s).upload("/","data.txt",new ByteArrayInputStream(new byte[]{1,2,3}),3,false);
            req(s,true);req(s,true);RecordedRequest init=req(s,true);
            assertTrue(init.getPath().contains("/items/root:/data.txt:/createUploadSession"));
            assertEquals("fail",new JSONObject(init.getBody().readUtf8()).getJSONObject("item").getString("@microsoft.graph.conflictBehavior"));
            RecordedRequest data=req(s,false);assertEquals("bytes 0-2/3",data.getHeader("Content-Range"));
            assertArrayEquals(new byte[]{1,2,3},data.getBody().readByteArray());
        }
    }
    @Test public void overwriteAndDeleteUseRevisionGuard()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();JSONObject old=item("old","data.txt",3,false);
            reply(s,item("root","root",0,true));reply(s,listing(old));
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
            s.start();reply(s,item("root","root",0,true));reply(s,listing(item("x","DATA.TXT",2,false)));
            assertThrows(FileTransferEngine.NameConflict.class,()->api(s).upload("/","data.txt",new ByteArrayInputStream(new byte[]{1}),1,false));
            req(s,true);req(s,true);assertEquals(2,s.getRequestCount());
        }
    }
    @Test public void linkedProfilesRejectLegacyPasswords(){
        Models.RemoteProfile p=new Models.RemoteProfile();p.protocol="OneDrive";p.host="graph.microsoft.com";p.username="test@example.test";
        assertFalse(OneDriveAuth.linked(p));p.fingerprint=OneDriveAuth.marker("user","drive");assertTrue(OneDriveAuth.linked(p));
        p.password="legacy";assertFalse(OneDriveAuth.linked(p));
    }
}
