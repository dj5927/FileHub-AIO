package com.nasmount.mobile;

import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.*;
import org.junit.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class GoogleDriveApiTest {
    private static final String TOKEN="fake-test-token";
    private static GoogleDriveApi api(MockWebServer s){
        return api(s,new GoogleDriveApi.Tokens(){public String token(){return TOKEN;}public void rejected(String t){}});
    }
    private static GoogleDriveApi api(MockWebServer s,GoogleDriveApi.Tokens tokens){
        return new GoogleDriveApi(new OkHttpClient.Builder().followRedirects(false).retryOnConnectionFailure(false).build(),
            s.url("/drive/v3/"),s.url("/upload/drive/v3/"),tokens);
    }
    private static JSONObject node(String id,String name,String mime)throws Exception{
        return new JSONObject().put("id",id).put("name",name).put("mimeType",mime).put("size","3");
    }
    private static String files(JSONObject...nodes)throws Exception{
        JSONArray a=new JSONArray();for(JSONObject n:nodes)a.put(n);
        return new JSONObject().put("files",a).toString();
    }
    private static void respond(MockWebServer s,String body){s.enqueue(new MockResponse().setBody(body).setHeader("Content-Type","application/json"));}
    private static RecordedRequest request(MockWebServer s)throws Exception{
        RecordedRequest r=s.takeRequest(2,TimeUnit.SECONDS);assertNotNull(r);
        assertEquals("Bearer "+TOKEN,r.getHeader("Authorization"));return r;
    }
    @Test public void rootListingPaginatesAndKeepsUniquePaths()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();
            JSONObject page=new JSONObject(files(node("id1","same.txt","text/plain"),node("d","Folder","application/vnd.google-apps.folder")));
            page.put("nextPageToken","next");respond(s,page.toString());
            respond(s,files(node("id2","same.txt","text/plain"),node("a","a/b.txt","text/plain"),node("g","Sheet","application/vnd.google-apps.spreadsheet")));
            List<Models.Entry> entries=api(s).list("/");
            assertEquals(5,entries.size());assertTrue(entries.get(0).directory);
            Set<String> paths=new HashSet<>();for(Models.Entry e:entries)assertTrue(paths.add(e.path));
            assertTrue(entries.stream().anyMatch(e->e.name.equals("Sheet.xlsx")));
            RecordedRequest r=request(s);assertEquals("'root' in parents and trashed=false",r.getRequestUrl().queryParameter("q"));
            assertEquals("next",request(s).getRequestUrl().queryParameter("pageToken"));
        }
    }
    @Test public void rangedDownloadAndNativeExport()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();String list=files(node("id","video.mp4","video/mp4"),node("doc","Letter","application/vnd.google-apps.document"));
            respond(s,list);s.enqueue(new MockResponse().setResponseCode(206).setHeader("Content-Range","bytes 1-2/3").setBody("bc"));
            try(InputStream in=api(s).open("/video.mp4",1)){assertEquals("bc",new String(in.readAllBytes()));}
            request(s);RecordedRequest media=request(s);assertEquals("/drive/v3/files/id?alt=media",media.getPath());assertEquals("bytes=1-",media.getHeader("Range"));
            respond(s,list);respond(s,"export");try(InputStream in=api(s).open("/Letter.docx",0)){assertEquals("export",new String(in.readAllBytes()));}
            request(s);assertTrue(request(s).getPath().contains("/doc/export?mimeType="));
        }
    }
    @Test public void rejectWrongRangeAndCrossOriginUploadSession()throws Exception{
        try(MockWebServer s=new MockWebServer();MockWebServer other=new MockWebServer()){
            s.start();other.start();respond(s,files(node("id","video.mp4","video/mp4")));
            s.enqueue(new MockResponse().setBody("abc"));
            assertThrows(IOException.class,()->api(s).open("/video.mp4",1));request(s);request(s);
            respond(s,files());s.enqueue(new MockResponse().setHeader("Location",other.url("/upload/drive/v3/files?upload_id=x")));
            assertThrows(IOException.class,()->api(s).upload("/","x",new ByteArrayInputStream(new byte[]{1}),1,false));
            request(s);request(s);assertEquals(0,other.getRequestCount());
        }
    }
    @Test public void resumableCreateAndOverwritePreserveIdentity()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();respond(s,files());String url=s.url("/upload/drive/v3/files?upload_id=x").toString();
            s.enqueue(new MockResponse().setHeader("Location",url));respond(s,"{\"id\":\"new\",\"size\":\"3\"}");
            api(s).upload("/","file.txt",new ByteArrayInputStream(new byte[]{1,2,3}),3,false);
            request(s);RecordedRequest init=request(s);assertEquals("POST",init.getMethod());
            assertEquals("file.txt",new JSONObject(init.getBody().readUtf8()).getString("name"));
            RecordedRequest data=request(s);assertEquals("PUT",data.getMethod());assertEquals("3",data.getHeader("Content-Length"));
            assertArrayEquals(new byte[]{1,2,3},data.getBody().readByteArray());
            respond(s,files(node("old","file.txt","text/plain")));s.enqueue(new MockResponse().setHeader("Location",url));
            respond(s,"{\"id\":\"old\",\"size\":\"1\"}");
            api(s).upload("/","file.txt",new ByteArrayInputStream(new byte[]{8}),1,true);
            request(s);init=request(s);assertEquals("PATCH",init.getMethod());assertTrue(init.getPath().contains("/files/old?"));
            assertFalse(new JSONObject(init.getBody().readUtf8()).has("name"));request(s);
        }
    }
    @Test public void duplicateOriginalNameCannotBeOverwrittenByGuessing()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();respond(s,files(node("a","same.txt","text/plain"),node("b","same.txt","text/plain")));
            assertThrows(FileTransferEngine.NameConflict.class,()->api(s).upload("/","same.txt",new ByteArrayInputStream(new byte[]{1}),1,true));
            request(s);assertEquals(1,s.getRequestCount());
        }
    }
    @Test public void deleteUsesTrashNotPermanentDeleteAndMoveExportIsBlocked()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();respond(s,files(node("id","file.txt","text/plain")));respond(s,"{\"id\":\"id\"}");
            api(s).delete("/file.txt");request(s);RecordedRequest r=request(s);assertEquals("PATCH",r.getMethod());
            assertTrue(new JSONObject(r.getBody().readUtf8()).getBoolean("trashed"));
            respond(s,files(node("g","Letter","application/vnd.google-apps.document")));
            assertThrows(IOException.class,()->api(s).requireLosslessMove("/Letter.docx"));request(s);
        }
    }
    @Test public void unauthorizedReadRefreshesButMutationDoesNotReplay()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();int[] invalidations={0};
            GoogleDriveApi drive=api(s,new GoogleDriveApi.Tokens(){
                public String token(){return TOKEN;}
                public void rejected(String t){invalidations[0]++;}
            });
            s.enqueue(new MockResponse().setResponseCode(401));respond(s,files());assertTrue(drive.list("/").isEmpty());
            assertEquals(1,invalidations[0]);request(s);request(s);
            respond(s,files(node("id","file.txt","text/plain")));s.enqueue(new MockResponse().setResponseCode(401));
            assertThrows(IOException.class,()->drive.delete("/file.txt"));assertEquals(2,invalidations[0]);request(s);request(s);
            assertEquals(4,s.getRequestCount());
        }
    }
    @Test public void readListingReusedForMediaButMutationResolvesFresh()throws Exception{
        try(MockWebServer s=new MockWebServer()){
            s.start();GoogleDriveApi drive=api(s);
            respond(s,files(node("old","one.jpg","image/jpeg"),node("two","two.jpg","image/jpeg")));
            drive.list("/");request(s);
            respond(s,"abc");try(InputStream in=drive.open("/one.jpg",0)){assertEquals(3,in.readAllBytes().length);}
            assertTrue(request(s).getPath().contains("/files/old?"));
            respond(s,"def");try(InputStream in=drive.open("/two.jpg",0)){assertEquals(3,in.readAllBytes().length);}
            assertTrue(request(s).getPath().contains("/files/two?"));
            respond(s,files(node("new","one.jpg","image/jpeg")));respond(s,"{\"id\":\"new\"}");
            drive.delete("/one.jpg");request(s);assertTrue(request(s).getPath().contains("/files/new?"));
        }
    }
    @Test public void onlyNativeAccountLinkedProfilesAreAccepted(){
        Models.RemoteProfile p=new Models.RemoteProfile();p.protocol="GoogleDrive";p.host="drive.google.com";p.username="test@example.test";
        assertFalse(GoogleDriveAuth.linked(p));p.fingerprint=GoogleDriveAuth.marker("permission-id");assertTrue(GoogleDriveAuth.linked(p));
        p.password="legacy-token";assertFalse(GoogleDriveAuth.linked(p));
    }
}
