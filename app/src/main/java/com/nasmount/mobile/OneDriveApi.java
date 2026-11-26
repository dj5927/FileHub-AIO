package com.nasmount.mobile;

import okhttp3.*;
import org.json.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Microsoft Graph v1.0 personal drive. Preauthenticated content/session URLs never receive bearer tokens. */
final class OneDriveApi {
    private static final HttpUrl ROOT=Objects.requireNonNull(HttpUrl.parse("https://graph.microsoft.com/v1.0/"));
    private static final MediaType JSON=MediaType.get("application/json; charset=utf-8"), BIN=MediaType.get("application/octet-stream");
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(90,TimeUnit.SECONDS).writeTimeout(120,TimeUnit.SECONDS).build();
    private static final String FIELDS="id,name,size,folder,file,package,remoteItem,lastModifiedDateTime,eTag";
    private static final int SMALL_NEW_UPLOAD=4*1024*1024;
    private static final int CHUNK=10*1024*1024; // multiple of 320 KiB
    interface Tokens {String token()throws Exception;void rejected(String token);}
    static final class Identity{
        final String email,userId,driveId;
        Identity(String e,String u,String d){email=e;userId=u;driveId=d;}
    }
    private final OkHttpClient http;private final HttpUrl base;private final Tokens tokens;
    OneDriveApi(OkHttpClient h,HttpUrl b,Tokens t){http=h;base=b;tokens=t;}
    static OneDriveApi forProfile(Models.RemoteProfile p){
        return new OneDriveApi(HTTP,ROOT,new Tokens(){
            public String token()throws Exception{return OneDriveAuth.token(p);}
            public void rejected(String t){OneDriveAuth.invalidate(p,t);}
        });
    }
    static Identity identity(String token)throws Exception{
        return new OneDriveApi(HTTP,ROOT,new Tokens(){
            public String token(){return token;}public void rejected(String t){}
        }).identity();
    }
    Identity identity()throws Exception{
        JSONObject user=json(new Request.Builder().url(base.newBuilder().addPathSegment("me").addQueryParameter("$select","id,mail,userPrincipalName").build()).get());
        JSONObject drive=json(new Request.Builder().url(drive().addQueryParameter("$select","id").build()).get());
        String email=nonNullString(user,"mail");
        if(email.isEmpty())email=nonNullString(user,"userPrincipalName");
        String uid=nonNullString(user,"id"),did=nonNullString(drive,"id");
        if(uid.isEmpty()||did.isEmpty())throw new IOException("OneDrive account identity unavailable");
        if(email.isEmpty())email="OneDrive";
        return new Identity(email,uid,did);
    }
    private static String nonNullString(JSONObject o,String key){
        return o.isNull(key)?"":o.optString(key,"").trim();
    }
    private HttpUrl.Builder drive(){return base.newBuilder().addPathSegment("me").addPathSegment("drive");}
    private HttpUrl.Builder item(String id){return drive().addPathSegment("items").addPathSegment(id);}
    private boolean graphUrl(HttpUrl u){
        return u.scheme().equals(base.scheme())&&u.host().equals(base.host())&&u.port()==base.port()&&
            u.username().isEmpty()&&u.password().isEmpty()&&u.encodedPath().startsWith(base.encodedPath());
    }
    private boolean dataUrl(HttpUrl u){
        // Microsoft returns service-specific signed URLs. Require HTTPS; only
        // loopback tests may use the configured local HTTP origin.
        return u!=null&&u.username().isEmpty()&&u.password().isEmpty()&&
            (u.isHttps()||(base.host().equals("localhost")&&u.host().equals("localhost")&&u.port()==base.port()));
    }
    private IOException failure(int code){
        return code==401?OneDriveAuth.required():new CloudProvider.CloudFailure("OneDrive",L10n.t("API 요청"),code);
    }
    private Response graph(Request.Builder b)throws Exception{
        Request req=b.build();if(!graphUrl(req.url()))throw new IOException("Invalid Graph API URL");
        boolean read=req.method().equals("GET"),refreshed=false,retried=false;
        for(;;){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            String t=tokens.token();Response r=http.newCall(req.newBuilder().header("Authorization","Bearer "+t).build()).execute();
            int c=r.code();
            if(c==401){r.close();tokens.rejected(t);if(read&&!refreshed){refreshed=true;continue;}throw new CloudProvider.CloudFailure("OneDrive",L10n.t("API 요청"),401);}
            if(read&&!retried&&(c==429||c==503)){
                long delay=retryDelayMillis(r.header("Retry-After"));
                if(delay>=0){r.close();retried=true;Thread.sleep(delay);continue;}
            }
            if(!r.isSuccessful()){r.close();throw new CloudProvider.CloudFailure("OneDrive",L10n.t("API 요청"),c);}
            return r;
        }
    }
    static long retryDelayMillis(String value){
        if(value==null)return -1;
        try{long seconds=Long.parseLong(value.trim());return seconds>=0&&seconds<=5?seconds*1000:-1;}
        catch(NumberFormatException e){return -1;}
    }
    private static JSONObject parse(Response r)throws Exception{
        if(r.body()==null)throw new IOException("OneDrive response missing");
        try{return new JSONObject(r.body().string());}catch(JSONException e){throw new IOException("OneDrive response invalid");}
    }
    private JSONObject json(Request.Builder b)throws Exception{try(Response r=graph(b)){return parse(r);}}
    private static RequestBody body(JSONObject obj){return RequestBody.create(obj.toString(),JSON);}
    static final class Node{
        final String id,name,etag;final boolean folder,unsupported;final int childCount;final long size,modified;
        Node(JSONObject o)throws Exception{
            id=o.getString("id");name=o.getString("name");etag=o.optString("eTag","");
            if(id.isEmpty()||id.length()>1024)throw new IOException("OneDrive invalid item ID");
            JSONObject facet=o.optJSONObject("folder");folder=facet!=null;
            childCount=folder?Math.max(-1,facet.optInt("childCount",-1)):-1;
            unsupported=o.optJSONObject("remoteItem")!=null||o.optJSONObject("package")!=null;
            size=o.optLong("size",-1);long time=0;
            try{time=Instant.parse(o.optString("lastModifiedDateTime")).toEpochMilli();}catch(Exception ignored){}
            modified=time;
        }
    }
    private static boolean validName(String n){
        try{return n.indexOf(0)<0&&RemoteProvider.child("/",n).equals("/"+n);}catch(IllegalArgumentException e){return false;}
    }
    private static String name(String path){String p=RemoteProvider.relativePath(path);if(p.equals("/"))throw new IllegalArgumentException("root");return p.substring(p.lastIndexOf('/')+1);}
    private HttpUrl.Builder pathItem(String path,boolean children){
        String normalized=RemoteProvider.relativePath(path);
        HttpUrl.Builder u=drive();
        if(normalized.equals("/"))u.addPathSegment("root");
        else{
            u.addPathSegment("root:");
            String[] parts=normalized.substring(1).split("/");
            for(int i=0;i<parts.length;i++)u.addPathSegment(parts[i]+(children&&i==parts.length-1?":":""));
        }
        if(children)u.addPathSegment("children");
        return u;
    }
    private JSONObject resolveMetadata(String path,String fields)throws Exception{
        return json(new Request.Builder().url(pathItem(path,false).addQueryParameter("$select",fields).build()).get());
    }
    private Node checkedNode(JSONObject metadata)throws Exception{
        Node n=new Node(metadata);
        if(n.unsupported)throw new IOException(L10n.t("OneDrive 바로가기 및 패키지는 지원하지 않습니다."));
        return n;
    }
    private Node resolve(String path)throws Exception{return checkedNode(resolveMetadata(path,FIELDS));}
    private Node folder(String path)throws Exception{Node n=resolve(path);if(!n.folder)throw new IOException("OneDrive parent is not a folder");return n;}
    interface ChildConsumer{void accept(JSONObject item)throws Exception;}
    private void walkChildren(HttpUrl u,ChildConsumer consume)throws Exception{
        Set<String> seen=new HashSet<>();
        for(int page=0;page<1000;page++){
            if(!graphUrl(u)||!seen.add(u.toString()))throw new IOException("OneDrive invalid pagination");
            JSONObject data=json(new Request.Builder().url(u).get());
            JSONArray items=data.getJSONArray("value");
            for(int i=0;i<items.length();i++)consume.accept(items.getJSONObject(i));
            String next=data.optString("@odata.nextLink","");
            if(next.isEmpty()||"null".equals(next))return;
            u=HttpUrl.parse(next);if(u==null)throw new IOException("OneDrive invalid pagination");
        }
        throw new IOException("OneDrive listing page limit exceeded");
    }
    private HttpUrl listingUrl(String path,String fields){
        return pathItem(path,true).addQueryParameter("$select",fields).addQueryParameter("$top","200").build();
    }
    // Look up only the destination, rather than scanning every page in its parent.
    private Node destination(String parent,String name)throws Exception{
        HttpUrl u=item(parent+":").addPathSegment(name).addQueryParameter("$select",FIELDS).build();
        try{
            Node n=new Node(json(new Request.Builder().url(u).get()));
            if(!n.name.equalsIgnoreCase(name))throw new IOException("OneDrive destination name mismatch");
            return n;
        }catch(CloudProvider.CloudFailure e){if(e.status==404)return null;throw e;}
    }
    int countChildren(String path,boolean hidden)throws Exception{
        int[] count={0};
        walkChildren(listingUrl(path,"name"),o->{String n=o.getString("name");
            if(!validName(n))throw new IOException("OneDrive item name cannot be represented safely");
            if(FileViewOptions.visible(n,false,hidden))count[0]++;
        });
        return count[0];
    }
    List<Models.Entry> list(String path)throws Exception{
        ArrayList<Models.Entry> out=new ArrayList<>();
        // Direct path-addressed children are supported by Graph; no parent
        // metadata round trip. Every mutation still resolves fresh metadata
        // and rejects shortcut/package targets before it changes anything.
        walkChildren(listingUrl(path,FIELDS),o->{
            Node n=new Node(o);
            if(!validName(n.name))throw new IOException("OneDrive item name cannot be represented safely");
            Models.Entry e=new Models.Entry(n.name,RemoteProvider.child(path,n.name),n.folder);
            e.remoteId=n.id;e.size=n.folder?0:n.size;e.modified=n.modified;
            e.serverChildCount=n.unsupported?-1:n.childCount;
            out.add(e);
        });
        out.sort((a,b)->a.directory!=b.directory?(a.directory?-1:1):a.name.compareToIgnoreCase(b.name));return out;
    }
    private Response data(Request request,boolean allowRedirect)throws Exception{
        Request req=request;
        for(int i=0;i<5;i++){
            if(!dataUrl(req.url())||req.header("Authorization")!=null)throw new IOException("Invalid OneDrive content URL");
            Response r;try{r=http.newCall(req).execute();}catch(IOException e){throw new IOException("OneDrive content transfer failed");}
            if(allowRedirect&&(r.code()==301||r.code()==302||r.code()==307||r.code()==308)){
                HttpUrl u=req.url().resolve(r.header("Location",""));r.close();
                if(!dataUrl(u))throw new IOException("Invalid OneDrive content redirect");
                req=req.newBuilder().url(u).build();continue;
            }
            if(!r.isSuccessful()){int c=r.code();r.close();throw failure(c);}return r;
        }
        throw new IOException("OneDrive content redirect limit");
    }
    InputStream open(String path,long position)throws Exception{
        if(position<0)throw new IOException("Invalid range");
        JSONObject meta=resolveMetadata(path,FIELDS+",@microsoft.graph.downloadUrl");
        Node n=checkedNode(meta);if(n.folder)throw new IOException("OneDrive folder");
        HttpUrl u=HttpUrl.parse(meta.optString("@microsoft.graph.downloadUrl",""));
        if(!dataUrl(u))throw new IOException("Invalid OneDrive download URL");
        Request.Builder request=new Request.Builder().url(u);
        if(position>0)request.header("Range","bytes="+position+"-");
        Response r=data(request.get().build(),true);
        if(r.body()==null||(position>0&&(r.code()!=206||!r.header("Content-Range","").startsWith("bytes "+position+"-")))){
            r.close();throw new IOException("OneDrive incorrect range");
        }
        return new FilterInputStream(r.body().byteStream()){
            @Override public void close()throws IOException{try{super.close();}finally{r.close();}}
        };
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)if(n>0)out.write(b,0,n);out.flush();
    }
    void download(String path,OutputStream out)throws Exception{try(InputStream in=open(path,0)){copy(in,out);}}
    private static void etag(Request.Builder b,Node n)throws IOException{
        if(n.etag.isEmpty()||"null".equals(n.etag))throw new IOException("OneDrive revision unavailable");
        b.header("If-Match",n.etag);
    }
    void mkdir(String path)throws Exception{
        String label=name(path);Node p=folder(RemoteProvider.parent(path));
        JSONObject metadata=new JSONObject().put("name",label).put("folder",new JSONObject()).put("@microsoft.graph.conflictBehavior","fail");
        JSONObject done;
        try{done=json(new Request.Builder().url(item(p.id).addPathSegment("children").build()).post(body(metadata)));}
        catch(CloudProvider.CloudFailure e){if(e.status==409)throw new FileTransferEngine.NameConflict();throw e;}
        if(done.optString("id").isEmpty()||!label.equals(done.optString("name"))||done.optJSONObject("folder")==null)throw new IOException("OneDrive folder creation unverified");
    }
    void rename(String source,String target)throws Exception{
        if(RemoteProvider.relativePath(source).equals(RemoteProvider.relativePath(target)))return;
        if(RemoteProvider.relativePath(source).equals("/")||RemoteProvider.relativePath(target).equals("/"))throw new IOException("OneDrive root cannot be changed");
        Node n=resolve(source),parent=folder(RemoteProvider.parent(target));String label=name(target);
        Node other=destination(parent.id,label);if(other!=null&&!other.id.equals(n.id))throw new FileTransferEngine.NameConflict();
        JSONObject metadata=new JSONObject().put("name",label).put("parentReference",new JSONObject().put("id",parent.id));
        Request.Builder b=new Request.Builder().url(item(n.id).build()).patch(body(metadata));etag(b,n);
        JSONObject done=json(b);if(!n.id.equals(done.optString("id"))||!label.equals(done.optString("name")))throw new IOException("OneDrive rename unverified");
    }
    void delete(String path)throws Exception{
        if(RemoteProvider.relativePath(path).equals("/"))throw new IOException("OneDrive root cannot be deleted");
        Node n=resolve(path);Request.Builder b=new Request.Builder().url(item(n.id).build()).delete();etag(b,n);
        try(Response ignored=graph(b)){}
    }
    private static void verifyUpload(JSONObject o,Node previous,String name,long size)throws Exception{
        if(o.optString("id").isEmpty()||previous!=null&&!previous.id.equals(o.optString("id"))||
            !name.equalsIgnoreCase(o.optString("name"))||o.optLong("size",-1)!=size||o.optJSONObject("file")==null)
            throw new IOException("OneDrive upload result was not verified");
    }
    void upload(String folder,String name,InputStream in,long size,boolean overwrite)throws Exception{
        if(!validName(name))throw new IllegalArgumentException("OneDrive invalid file name");
        if(size<0){
            File local=OneDriveAuth.tempFile();try{
                try(OutputStream out=new FileOutputStream(local)){copy(in,out);}
                try(InputStream input=new FileInputStream(local)){upload(folder,name,input,local.length(),overwrite);}
            }finally{local.delete();}return;
        }
        Node p=folder(folder),old=destination(p.id,name);
        if(old!=null&&(!overwrite||old.folder||old.unsupported))throw new FileTransferEngine.NameConflict();
        if(size==0||old==null&&size<=SMALL_NEW_UPLOAD){
            byte[] payload=new byte[(int)size];int got=0;
            while(got<payload.length){int n=in.read(payload,got,payload.length-got);if(n<0)throw new IOException("OneDrive upload length mismatch");if(n>0)got+=n;}
            if(in.read()!=-1)throw new IOException("OneDrive upload length mismatch");
            HttpUrl.Builder u=old==null?item(p.id+":").addPathSegment(name+":"):item(old.id);
            u.addPathSegment("content").addQueryParameter("@microsoft.graph.conflictBehavior",old==null?"fail":"replace");
            Request.Builder b=new Request.Builder().url(u.build()).put(RequestBody.create(payload,BIN));
            if(old!=null)etag(b,old);else b.header("If-None-Match","*");
            try{verifyUpload(json(b),old,name,size);}
            catch(CloudProvider.CloudFailure e){if(old==null&&(e.status==409||e.status==412))throw new FileTransferEngine.NameConflict();throw e;}
            return;
        }
        HttpUrl.Builder u=old==null?item(p.id+":").addPathSegment(name+":"):item(old.id);
        u.addPathSegment("createUploadSession");
        JSONObject metadata=new JSONObject().put("item",new JSONObject().put("@microsoft.graph.conflictBehavior",old==null?"fail":"replace").put("name",name));
        Request.Builder init=new Request.Builder().url(u.build()).post(body(metadata));
        if(old!=null)etag(init,old);
        HttpUrl session=HttpUrl.parse(json(init).optString("uploadUrl",""));
        if(!dataUrl(session))throw new IOException("Invalid OneDrive upload URL");
        boolean complete=false;
        try{
            long pos=0;byte[] buffer=new byte[(int)Math.min(CHUNK,size)];
            while(pos<size){
                int need=(int)Math.min(buffer.length,size-pos),got=0;
                while(got<need){int n=in.read(buffer,got,need-got);if(n<0)throw new IOException("OneDrive upload length mismatch");if(n>0)got+=n;}
                if(pos+need==size&&in.read()!=-1)throw new IOException("OneDrive upload length mismatch");
                Request chunk=new Request.Builder().url(session).header("Content-Range","bytes "+pos+"-"+(pos+need-1)+"/"+size)
                    .put(RequestBody.create(buffer,BIN,0,need)).build();
                try(Response r=data(chunk,false)){
                    JSONObject answer=parse(r);
                    if(pos+need<size){
                        if(r.code()!=202)throw new IOException("OneDrive unexpected upload completion");
                        JSONArray ranges=answer.optJSONArray("nextExpectedRanges");
                        if(ranges==null||ranges.length()==0||!ranges.getString(0).startsWith((pos+need)+"-"))throw new IOException("OneDrive unexpected upload offset");
                    }else{
                        if(r.code()!=200&&r.code()!=201)throw new IOException("OneDrive upload incomplete");
                        verifyUpload(answer,old,name,size);complete=true;
                    }
                }
                pos+=need;
            }
        }finally{
            if(!complete)try(Response ignored=data(new Request.Builder().url(session).delete().build(),false)){}catch(Exception ignored){}
        }
    }
}
