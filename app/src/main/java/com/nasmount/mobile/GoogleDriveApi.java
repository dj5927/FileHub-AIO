package com.nasmount.mobile;

import okhttp3.*;
import okio.BufferedSink;
import org.json.*;
import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Drive v3, direct device-to-Google REST. Only the Google API origin gets bearer tokens.
 * My Drive path adapter: ambiguous or unrepresentable names receive deterministic ID aliases.
 */
final class GoogleDriveApi {
    private static final HttpUrl BASE=Objects.requireNonNull(HttpUrl.parse("https://www.googleapis.com/drive/v3/"));
    private static final HttpUrl UPLOAD=Objects.requireNonNull(HttpUrl.parse("https://www.googleapis.com/upload/drive/v3/"));
    private static final MediaType JSON=MediaType.get("application/json; charset=utf-8"), OCTETS=MediaType.get("application/octet-stream");
    private static final String FOLDER="application/vnd.google-apps.folder", GOOGLE="application/vnd.google-apps.";
    private static final String FIELDS="id,name,mimeType,size,modifiedTime,parents,trashed";
    private static final OkHttpClient HTTP=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15,TimeUnit.SECONDS).readTimeout(60,TimeUnit.SECONDS)
        .writeTimeout(120,TimeUnit.SECONDS).build();
    static final class Identity{
        final String email,permissionId;
        Identity(String e,String id){email=e;permissionId=id;}
    }
    interface Tokens {String token()throws Exception;void rejected(String token);}
    private final OkHttpClient http;
    private final HttpUrl base,upload;
    private final Tokens tokens;
    private static final LinkedHashMap<String,GoogleDriveApi> profiles=new LinkedHashMap<>(16,0.75f,true);
    private static final class Cached {
        final List<Node> nodes;final long until;
        Cached(List<Node> nodes){this.nodes=nodes;until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);}
    }
    private final LinkedHashMap<String,Cached> lookup=new LinkedHashMap<>(16,0.75f,true);
    GoogleDriveApi(OkHttpClient http,HttpUrl base,HttpUrl upload,Tokens tokens){
        this.http=http;this.base=base;this.upload=upload;this.tokens=tokens;
    }
    static synchronized GoogleDriveApi forProfile(Models.RemoteProfile p){
        String key=p.id+"|"+p.username+"|"+p.fingerprint;
        GoogleDriveApi api=profiles.get(key);
        if(api==null){
            api=new GoogleDriveApi(HTTP,BASE,UPLOAD,new Tokens(){
                public String token()throws Exception{return GoogleDriveAuth.token(p);}
                public void rejected(String t){GoogleDriveAuth.invalidate(p,t);}
            });
            profiles.put(key,api);
            while(profiles.size()>12)profiles.remove(profiles.keySet().iterator().next());
        }
        return api;
    }
    static synchronized void forget(Models.RemoteProfile p){
        profiles.keySet().removeIf(k->k.startsWith(p.id+"|"));
        GoogleDriveAuth.forget(p);
    }
    private synchronized void clearLookup(){lookup.clear();}
    private synchronized List<Node> cached(String id){
        Cached c=lookup.get(id);
        if(c==null)return null;
        if(System.nanoTime()>=c.until){lookup.remove(id);return null;}
        return c.nodes;
    }
    private synchronized void cache(String id,List<Node> list){
        // Do not keep very large directories in this short-lived cache.
        if(list.size()>5000)return;
        lookup.put(id,new Cached(list));
        while(lookup.size()>16)lookup.remove(lookup.keySet().iterator().next());
    }
    static Identity identity(String token)throws Exception {
        return new GoogleDriveApi(HTTP,BASE,UPLOAD,new Tokens(){
            public String token(){return token;}public void rejected(String t){}
        }).identity();
    }
    Identity identity()throws Exception{
        JSONObject u=json(new Request.Builder().url(base.newBuilder().addPathSegment("about")
            .addQueryParameter("fields","user(emailAddress,permissionId)").build()).get()).getJSONObject("user");
        String email=u.optString("emailAddress"),id=u.optString("permissionId");
        if(email.isEmpty()||id.isEmpty())throw new IOException("Google Drive account identity unavailable");
        return new Identity(email,id);
    }
    private IOException failure(int status){
        if(status==401)return GoogleDriveAuth.required();
        return new CloudProvider.CloudFailure("Google Drive",L10n.t("API 요청"),status);
    }
    // GET/HEAD can be retried after a rejected token; mutations are never blindly replayed.
    private Response call(Request.Builder b)throws Exception {
        Request request=b.build();
        HttpUrl u=request.url();
        if(!allowed(u,base)&&!allowed(u,upload))throw new IOException("Invalid Google Drive API origin");
        boolean safe="GET".equals(request.method())||"HEAD".equals(request.method());
        for(int attempt=0;attempt<(safe?2:1);attempt++){
            String t=tokens.token();
            Response r=http.newCall(request.newBuilder().header("Authorization","Bearer "+t).build()).execute();
            if(r.code()==401){r.close();tokens.rejected(t);if(safe&&attempt==0)continue;throw failure(401);}
            if(!r.isSuccessful()){int code=r.code();r.close();throw failure(code);}
            return r;
        }
        throw failure(401);
    }
    private static boolean allowed(HttpUrl u,HttpUrl root){
        return u.scheme().equals(root.scheme())&&u.host().equals(root.host())&&u.port()==root.port()&&
            u.username().isEmpty()&&u.password().isEmpty()&&u.encodedPath().startsWith(root.encodedPath());
    }
    private JSONObject json(Request.Builder b)throws Exception{
        try(Response r=call(b)){
            if(r.body()==null)throw new IOException("Google Drive response missing");
            String body=r.body().string();
            try{return new JSONObject(body);}catch(JSONException e){throw new IOException("Google Drive response invalid");}
        }
    }
    private HttpUrl.Builder file(String id){return base.newBuilder().addPathSegment("files").addPathSegment(id);}
    private static RequestBody body(JSONObject data){return RequestBody.create(data.toString(),JSON);}
    static final class Node {
        final String id,name,mime;final long size,time;String label;
        Node(JSONObject x)throws Exception{
            id=x.getString("id");name=x.getString("name");mime=x.optString("mimeType");
            if(id.isEmpty()||id.length()>512)throw new IOException("Google Drive file ID invalid");
            size=x.optLong("size",-1);long modified=0;
            try{modified=Instant.parse(x.optString("modifiedTime")).toEpochMilli();}catch(Exception ignored){}
            time=modified;label=name;
        }
        boolean folder(){return FOLDER.equals(mime);}
        boolean nativeFile(){return mime.startsWith(GOOGLE)&&!folder();}
        String exportMime(){
            switch(mime){
                case GOOGLE+"document":return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case GOOGLE+"spreadsheet":return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case GOOGLE+"presentation":return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case GOOGLE+"drawing":return "application/pdf";
                default:return "";
            }
        }
        String extension(){
            switch(mime){
                case GOOGLE+"document":return ".docx";
                case GOOGLE+"spreadsheet":return ".xlsx";
                case GOOGLE+"presentation":return ".pptx";
                case GOOGLE+"drawing":return ".pdf";
                default:return "";
            }
        }
    }
    private static String literal(String s){return "'"+s.replace("\\","\\\\").replace("'","\\'")+"'";}
    private List<Node> children(String id)throws Exception{return children(id,false);}
    private List<Node> children(String id,boolean useCache)throws Exception{
        if(useCache){List<Node> hit=cached(id);if(hit!=null)return hit;}
        List<Node> all=new ArrayList<>();Set<String> seen=new HashSet<>();String token="";
        for(int page=0;page<1000;page++){
            HttpUrl.Builder u=base.newBuilder().addPathSegment("files")
                .addQueryParameter("q",literal(id)+" in parents and trashed=false")
                .addQueryParameter("fields","nextPageToken,files("+FIELDS+")")
                .addQueryParameter("pageSize","1000").addQueryParameter("spaces","drive");
            if(!token.isEmpty())u.addQueryParameter("pageToken",token);
            JSONObject r=json(new Request.Builder().url(u.build()).get());
            JSONArray a=r.getJSONArray("files");
            for(int i=0;i<a.length();i++)all.add(new Node(a.getJSONObject(i)));
            token=r.optString("nextPageToken","");
            if(token.isEmpty()){labels(all);cache(id,all);return all;}
            if(!seen.add(token))throw new IOException("Google Drive repeated page token");
        }
        throw new IOException("Google Drive listing page limit exceeded");
    }
    /** Stable alias preserves extension; never route a duplicate or slash-containing name to an arbitrary ID. */
    static void labels(List<Node> all)throws Exception{
        Map<String,Integer> counts=new HashMap<>();Set<String> used=new HashSet<>();
        for(Node n:all){
            n.label=n.name+n.extension();
            counts.merge(n.label,1,Integer::sum);
        }
        for(Node n:all)if(counts.get(n.label)==1&&validName(n.label))used.add(n.label);
        for(Node n:all)if(counts.get(n.label)!=1||!validName(n.label)){
            String raw=n.label.replace('/','_').replace('\\','_').replace('\0','_');
            if(raw.trim().isEmpty()||raw.equals(".")||raw.equals(".."))raw="file";
            int dot=n.folder()?-1:raw.lastIndexOf('.');
            String stem=dot>0?raw.substring(0,dot):raw,ext=dot>0?raw.substring(dot):"";
            // Full URL-safe ID prevents collisions without relying on a short hash.
            String id=Base64.getUrlEncoder().withoutPadding().encodeToString(n.id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String label=stem+" [Drive "+id+"]"+ext;
            while(!used.add(label))label="_"+label;
            n.label=label;
        }
    }
    private static boolean validName(String n){
        try{return RemoteProvider.child("/",n).equals("/"+n);}catch(IllegalArgumentException e){return false;}
    }
    private Node named(String parent,String label,boolean useCache)throws Exception{
        for(Node n:children(parent,useCache))if(n.label.equals(label))return n;return null;
    }
    private Node destination(String parent,String name)throws Exception{
        List<Node> list=children(parent);
        for(Node n:list)if(n.label.equals(name))return n;
        for(Node n:list)if((n.name+n.extension()).equals(name))
            throw new FileTransferEngine.NameConflict();
        return null;
    }
    /** A Google-native document export is not the original document.
     * Reject copy-then-delete moves of such documents, including nested folders. */
    void requireLosslessMove(String path)throws Exception{
        requireLosslessMove(resolve(path),new HashSet<>(),0);
    }
    private void requireLosslessMove(Node n,Set<String> seen,int depth)throws Exception{
        if(n.nativeFile())throw new IOException(L10n.t("Google 원본 문서는 다른 저장소로 이동할 수 없습니다. 복사만 가능합니다."));
        if(!n.folder())return;
        if(depth>128||!seen.add(n.id)||seen.size()>100000)
            throw new IOException("Google Drive folder tree limit exceeded");
        for(Node child:children(n.id))requireLosslessMove(child,seen,depth+1);
    }
    private Node resolve(String path)throws Exception{return resolve(path,false);}
    private Node resolve(String path,boolean useCache)throws Exception{
        String safe=RemoteProvider.relativePath(path);
        if(safe.equals("/"))throw new IOException("Google Drive root cannot be modified");
        String parent="root";Node node=null;String[] parts=safe.substring(1).split("/");
        for(int i=0;i<parts.length;i++){
            node=named(parent,parts[i],useCache);
            if(node==null)throw new FileNotFoundException("Google Drive file not found");
            if(i+1<parts.length&&!node.folder())throw new IOException("Google Drive parent is not a folder");
            parent=node.id;
        }
        return node;
    }
    private String directory(String path)throws Exception{return directory(path,false);}
    private String directory(String path,boolean useCache)throws Exception{
        if(RemoteProvider.relativePath(path).equals("/"))return "root";
        Node n=resolve(path,useCache);if(!n.folder())throw new IOException("Google Drive parent is not a folder");return n.id;
    }
    List<Models.Entry> list(String path)throws Exception{
        List<Models.Entry> out=new ArrayList<>();
        for(Node n:children(directory(path,true))){
            Models.Entry e=new Models.Entry(n.label,RemoteProvider.child(path,n.label),n.folder());
            e.remoteId=n.id;e.modified=n.time;e.size=n.size;out.add(e);
        }
        out.sort((a,b)->a.directory!=b.directory?(a.directory?-1:1):a.name.compareToIgnoreCase(b.name));
        return out;
    }
    InputStream open(String path,long position)throws Exception{
        if(position<0)throw new IOException("Invalid range");
        Node n=resolve(path,true);if(n.folder())throw new IOException("Google Drive folder");
        Request.Builder req=new Request.Builder();
        if(n.nativeFile()){
            if(position!=0||n.exportMime().isEmpty())throw new IOException(L10n.t("이 Google 문서는 다운로드할 수 없습니다."));
            req.url(file(n.id).addPathSegment("export").addQueryParameter("mimeType",n.exportMime()).build());
        }else{
            req.url(file(n.id).addQueryParameter("alt","media").build());
            if(position>0)req.header("Range","bytes="+position+"-");
        }
        Response r=call(req.get());
        if(r.body()==null||(position>0&&(r.code()!=206||!r.header("Content-Range","").startsWith("bytes "+position+"-")))){
            r.close();throw new IOException("Google Drive returned incorrect range");
        }
        return new FilterInputStream(r.body().byteStream()){
            @Override public void close()throws IOException{try{super.close();}finally{r.close();}}
        };
    }
    void download(String path,OutputStream out)throws Exception{
        try(InputStream in=open(path,0)){copy(in,out);}
    }
    private static void copy(InputStream in,OutputStream out)throws IOException{
        byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1){if(n>0)out.write(b,0,n);}out.flush();
    }
    void mkdir(String path)throws Exception{
        clearLookup();
        String parent=directory(RemoteProvider.parent(path)),name=basename(path);
        if(destination(parent,name)!=null)throw new FileTransferEngine.NameConflict();
        try{json(new Request.Builder().url(base.newBuilder().addPathSegment("files").addQueryParameter("fields","id").build())
            .post(body(new JSONObject().put("name",name).put("mimeType",FOLDER).put("parents",new JSONArray().put(parent)))));}
        finally{clearLookup();}
    }
    static String basename(String path){String s=RemoteProvider.relativePath(path);if(s.equals("/"))throw new IllegalArgumentException("root");return s.substring(s.lastIndexOf('/')+1);}
    void rename(String source,String destination)throws Exception{
        clearLookup();
        if(RemoteProvider.relativePath(source).equals(RemoteProvider.relativePath(destination)))return;
        Node n=resolve(source);
        if(n.nativeFile())throw new IOException(L10n.t("Google 문서의 이름 변경 및 덮어쓰기는 지원하지 않습니다."));
        String from=directory(RemoteProvider.parent(source)),to=directory(RemoteProvider.parent(destination)),name=basename(destination);
        if(destination(to,name)!=null)throw new FileTransferEngine.NameConflict();
        HttpUrl.Builder u=file(n.id).addQueryParameter("fields","id");
        if(!from.equals(to))u.addQueryParameter("addParents",to).addQueryParameter("removeParents",from);
        try{json(new Request.Builder().url(u.build()).patch(body(new JSONObject().put("name",name))));}
        finally{clearLookup();}
    }
    void delete(String path)throws Exception{
        clearLookup();
        Node n=resolve(path);
        try{json(new Request.Builder().url(file(n.id).addQueryParameter("fields","id").build())
            .patch(body(new JSONObject().put("trashed",true))));}
        finally{clearLookup();}
    }
    void upload(String folder,String name,InputStream input,long length,boolean overwrite)throws Exception{
        clearLookup();
        if(length<0){
            File temporary=GoogleDriveAuth.tempFile();
            try{
                try(OutputStream out=new FileOutputStream(temporary)){copy(input,out);}
                try(InputStream stored=new FileInputStream(temporary)){upload(folder,name,stored,temporary.length(),overwrite);}
            }finally{temporary.delete();}
            return;
        }
        RemoteProvider.child(folder,name);String parent=directory(folder);
        Node existing=destination(parent,name);
        if(existing!=null&&(!overwrite||existing.folder()))throw new FileTransferEngine.NameConflict();
        if(existing!=null&&existing.nativeFile())throw new IOException(L10n.t("Google 문서의 이름 변경 및 덮어쓰기는 지원하지 않습니다."));
        // Alias is an explicit selection, not a new original name. Do not rename an existing file on overwrite.
        JSONObject metadata=existing==null?new JSONObject().put("name",name).put("parents",new JSONArray().put(parent)):new JSONObject();
        if(length==0){
            if(input.read()!=-1)throw new IOException("Upload length mismatch");
            HttpUrl.Builder u=upload.newBuilder().addPathSegment("files").addQueryParameter("uploadType","multipart").addQueryParameter("fields","id,size");
            if(existing!=null)u.addPathSegment(existing.id);
            RequestBody multipart=new MultipartBody.Builder().setType(MediaType.get("multipart/related"))
                .addPart(body(metadata)).addPart(RequestBody.create(new byte[0],OCTETS)).build();
            JSONObject done;
            try{done=json(new Request.Builder().url(u.build()).method(existing==null?"POST":"PATCH",multipart));}
            finally{clearLookup();}
            if(done.optString("id").isEmpty()||(existing!=null&&!existing.id.equals(done.optString("id")))||done.optLong("size",-1)!=0)throw new IOException("Google Drive upload was not verified");
            return;
        }
        HttpUrl.Builder start=upload.newBuilder().addPathSegment("files").addQueryParameter("uploadType","resumable").addQueryParameter("fields","id,size");
        if(existing!=null)start.addPathSegment(existing.id);
        Request.Builder init=new Request.Builder().url(start.build()).header("X-Upload-Content-Type","application/octet-stream")
            .method(existing==null?"POST":"PATCH",body(metadata));
        if(length>=0)init.header("X-Upload-Content-Length",Long.toString(length));
        HttpUrl session;
        try(Response r=call(init)){
            session=HttpUrl.parse(r.header("Location",""));
            if(session==null||!session.scheme().equals(upload.scheme())||!session.host().equals(upload.host())||
                session.port()!=upload.port()||!session.username().isEmpty()||!session.password().isEmpty()||
                !session.encodedPath().startsWith(upload.encodedPath()))throw new IOException("Invalid Google Drive upload session");
        }
        RequestBody data=new RequestBody(){
            public MediaType contentType(){return OCTETS;}
            public long contentLength(){return length;}
            public boolean isOneShot(){return true;}
            public void writeTo(BufferedSink sink)throws IOException{
                byte[] b=new byte[65536];long written=0;int n;
                while((n=input.read(b))!=-1){if(n==0)continue;written+=n;if(length>=0&&written>length)throw new IOException("Upload length mismatch");sink.write(b,0,n);}
                if(length>=0&&written!=length)throw new IOException("Upload length mismatch");
            }
        };
        JSONObject finished;
        try{finished=json(new Request.Builder().url(session).put(data));}
        finally{clearLookup();}
        if(finished.optString("id").isEmpty()||(existing!=null&&!existing.id.equals(finished.optString("id")))||finished.optLong("size",-1)!=length)
            throw new IOException("Google Drive upload was not verified");
    }
}
