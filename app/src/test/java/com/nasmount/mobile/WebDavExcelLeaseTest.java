package com.nasmount.mobile;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Realistic WebDAV HEAD + PROPFIND + owner GET/PUT/DELETE simulation. */
public class WebDavExcelLeaseTest {
    private static final String FILE="/11\uC6D4\uB9E4\uCD9C.xlsx";
    private static final String PRIMARY="/~$11\uC6D4\uB9E4\uCD9C.xlsx";
    private static final String ALT="/~$\uC6D4\uB9E4\uCD9C.xlsx";

    private static class NAS extends Dispatcher {
        final Map<String,byte[]> owners=new ConcurrentHashMap<>();
        final AtomicInteger putCount=new AtomicInteger(),deleteCount=new AtomicInteger();
        String documentEtag="\"doc-1\"";
        boolean exposeDocumentHeadEtag=true;
        boolean exposeDocumentPropfindEtag=true;
        boolean exposeOwnerEtag=true;

        private MockResponse notFound(){return new MockResponse().setResponseCode(404);}
        private MockResponse head(String eTag) {
            MockResponse answer=new MockResponse().setResponseCode(200);
            if(eTag!=null)answer.addHeader("ETag",eTag);
            return answer;
        }
        private MockResponse propfind(String eTag) {
            String value=eTag==null?
                    "<d:status>HTTP/1.1 404 Not Found</d:status>":
                    "<d:propstat><d:prop><d:getetag>"+eTag+
                    "</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>";
            return new MockResponse().setResponseCode(207)
                    .addHeader("Content-Type","application/xml")
                    .setBody("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">" +
                            "<d:response>"+value+"</d:response></d:multistatus>");
        }
        @Override public MockResponse dispatch(RecordedRequest request) {
            String path;
            try {
                path=URLDecoder.decode(request.getRequestUrl().encodedPath()
                        .replaceFirst("^/dav",""),"UTF-8");
            } catch(Exception invalid){return new MockResponse().setResponseCode(400);}
            String method=request.getMethod();
            if(path.equals(FILE)){
                if(method.equals("HEAD"))
                    return head(exposeDocumentHeadEtag?documentEtag:null);
                if(method.equals("PROPFIND"))
                    return propfind(exposeDocumentPropfindEtag?documentEtag:null);
                return new MockResponse().setResponseCode(405);
            }
            if(path.startsWith("/~$")){
                byte[] bytes=owners.get(path);
                String ownerTag=exposeOwnerEtag?"\"owner-version\"":null;
                switch(method){
                    case "HEAD": return bytes==null?notFound():head(ownerTag);
                    case "PROPFIND": return bytes==null?notFound():propfind(ownerTag);
                    case "GET": return bytes==null?notFound():
                            new MockResponse().setResponseCode(200)
                                    .setBody(new okio.Buffer().write(bytes));
                    case "PUT":
                        if(bytes!=null)return new MockResponse().setResponseCode(412);
                        if(!"*".equals(request.getHeader("If-None-Match")))
                            return new MockResponse().setResponseCode(428);
                        owners.put(path,request.getBody().readByteArray());
                        putCount.incrementAndGet();
                        return new MockResponse().setResponseCode(201);
                    case "DELETE":
                        if(bytes==null)return notFound();
                        String expected=request.getHeader("If-Match");
                        if(expected!=null && !expected.equals(ownerTag))
                            return new MockResponse().setResponseCode(412);
                        owners.remove(path);
                        deleteCount.incrementAndGet();
                        return new MockResponse().setResponseCode(204);
                }
            }
            return notFound();
        }
    }

    private static Models.RemoteProfile profile(MockWebServer server){
        Models.RemoteProfile p=new Models.RemoteProfile();
        p.protocol="WebDAV";p.host="127.0.0.1";p.port=server.getPort();
        p.root="/dav";p.ssl=false;
        return p;
    }
    private static MockWebServer server(NAS nas) throws IOException {
        MockWebServer server=new MockWebServer();
        server.setDispatcher(nas);server.start();
        return server;
    }

    @Test public void koreanOwnerNameFormsAndBinaryLayout() {
        assertArrayEquals(new String[]{PRIMARY,ALT},
                WebDavExcelLease.candidateOwners(FILE));
        assertArrayEquals(new String[]{"/~$book.xlsx","/~$ok.xlsx"},
                WebDavExcelLease.candidateOwners("/book.xlsx"));
        byte[] bytes=WebDavExcelLease.ownerBytes();
        assertEquals(165,bytes.length);
        assertEquals("FileHub Android",
                new String(bytes,2,15,StandardCharsets.US_ASCII));
    }

    @Test public void ownerFromWindowsOrRaiDriveForcesReadOnly() throws Exception {
        NAS nas=new NAS();
        nas.owners.put(PRIMARY,new byte[]{9,8,7});
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            assertTrue(lease.readOnly());
            assertThrows(IOException.class,lease::verifyBeforeSave);
            lease.release();
            assertArrayEquals(new byte[]{9,8,7},nas.owners.get(PRIMARY));
            assertEquals(0,nas.putCount.get());
        }
    }

    @Test public void alternateOwnerFromExcelForcesReadOnly() throws Exception {
        NAS nas=new NAS();
        nas.owners.put(ALT,new byte[]{1,2,3});
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            assertTrue(lease.readOnly());lease.release();
            assertEquals(0,nas.putCount.get());
        }
    }

    @Test public void noDocumentEtagOpensReadOnlyWithoutCreatingOwner() throws Exception {
        NAS nas=new NAS();
        nas.exposeDocumentHeadEtag=false;
        nas.exposeDocumentPropfindEtag=false;
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            assertTrue(lease.readOnly());
            lease.release();
            assertEquals(0,nas.putCount.get());
            assertFalse(nas.owners.containsKey(PRIMARY));
        }
    }

    @Test public void propfindRecoversMissingHeadEtag() throws Exception {
        NAS nas=new NAS();
        nas.exposeDocumentHeadEtag=false;
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            assertFalse(lease.readOnly());
            assertEquals("\"doc-1\"",lease.originalEtag());
            lease.release();
            assertEquals(1,nas.deleteCount.get());
        }
    }

    @Test public void ownerWithoutHeadEtagStillClosesWithoutOrphan() throws Exception {
        NAS nas=new NAS();
        nas.exposeOwnerEtag=false;
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            assertFalse(lease.readOnly());
            assertEquals(165,nas.owners.get(PRIMARY).length);
            lease.verifyBeforeSave();
            lease.release();
            assertFalse(nas.owners.containsKey(PRIMARY));
            assertEquals(1,nas.deleteCount.get());
        }
    }

    @Test public void twoAndroidEditorsRespectOwnerAndRelease() throws Exception {
        NAS nas=new NAS();
        try(MockWebServer server=server(nas)){
            Models.RemoteProfile p=profile(server);
            WebDavExcelLease first=WebDavExcelLease.acquire(p,FILE);
            WebDavExcelLease second=WebDavExcelLease.acquire(p,FILE);
            assertFalse(first.readOnly());
            assertTrue(second.readOnly());
            first.release();second.release();
            WebDavExcelLease next=WebDavExcelLease.acquire(p,FILE);
            assertFalse(next.readOnly());next.release();
            assertEquals(2,nas.putCount.get());
            assertEquals(2,nas.deleteCount.get());
        }
    }

    @Test public void changedWorkbookBlocksSaveButCleansOwnMarker() throws Exception {
        NAS nas=new NAS();
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            assertFalse(lease.readOnly());
            nas.documentEtag="\"doc-external-change\"";
            assertThrows(IOException.class,lease::verifyBeforeSave);
            lease.release();
            assertFalse(nas.owners.containsKey(PRIMARY));
        }
    }

    @Test public void changedOwnerContentIsNeverDeleted() throws Exception {
        NAS nas=new NAS();
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            nas.owners.put(PRIMARY,new byte[]{4,5,6});
            assertThrows(IOException.class,lease::verifyBeforeSave);
            lease.release();
            assertArrayEquals(new byte[]{4,5,6},nas.owners.get(PRIMARY));
            assertEquals(0,nas.deleteCount.get());
        }
    }

    @Test public void confirmedSaveRefreshesOriginalVersion() throws Exception {
        NAS nas=new NAS();
        try(MockWebServer server=server(nas)){
            WebDavExcelLease lease=WebDavExcelLease.acquire(profile(server),FILE);
            nas.documentEtag="\"doc2\"";
            lease.saved();
            assertEquals("\"doc2\"",lease.originalEtag());
            lease.verifyBeforeSave();
            lease.release();
            assertEquals(1,nas.deleteCount.get());
        }
    }
}
