package com.nasmount.mobile;

import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import androidx.documentfile.provider.DocumentFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;

/** Private journal; payloads remain on the original storage volume or granted tree. */
final class LocalTrash {
    static final int[] DAYS={3,5,7,15,30,0};
    private static final String AUTH="com.android.externalstorage.documents";
    static final class Entry{
        String id,kind,volume,tree,relative,name;
        long removed,size;
        boolean available;
        JSONObject json()throws Exception{return new JSONObject().put("id",id).put("kind",kind).put("volume",volume)
            .put("tree",tree).put("relative",relative).put("name",name).put("removed",removed).put("size",size);}
        static Entry read(JSONObject o)throws Exception{
            Entry e=new Entry();e.id=TrashFileOps.slotId(o.getString("id"));e.kind=o.getString("kind");
            e.volume=o.optString("volume");e.tree=o.optString("tree");e.relative=TrashFileOps.safeRelative(o.getString("relative"));
            e.name=o.getString("name");e.removed=o.getLong("removed");e.size=o.optLong("size",-1);
            if(!e.name.equals(new File(e.relative).getName())||e.removed<=0||(!"local".equals(e.kind)&&!"saf".equals(e.kind)))throw new IOException("Invalid trash journal");
            return e;
        }
    }
    static final class Outcome{
        int count,failed;String first="";
        void fail(Exception e){failed++;if(first.isEmpty())first=e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    }
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences("filehub_local_trash",Context.MODE_PRIVATE);}
    static int retention(Context c){int d=prefs(c).getInt("days",7);for(int n:DAYS)if(d==n)return d;return 7;}
    static int retentionIndex(Context c){int d=retention(c);for(int i=0;i<DAYS.length;i++)if(DAYS[i]==d)return i;return 2;}
    static void retention(Context c,int index){if(index>=0&&index<DAYS.length)prefs(c).edit().putInt("days",DAYS[index]).apply();TrashJobService.schedule(c);}
    static boolean eligible(FileTransferEngine.Item i){
        return i!=null&&("local".equals(i.kind)||("saf".equals(i.kind)&&i.saf!=null&&AUTH.equals(i.saf.getUri().getAuthority())));
    }
    static boolean reserved(String name){return TrashFileOps.reserved(name);}
    static boolean reservedPath(String path){
        if(path==null)return false;
        for(String segment:path.split("/"))if(reserved(segment))return true;
        return false;
    }
    private static boolean contains(String parent,String path){
        return path.equals(parent)||path.startsWith(parent.endsWith("/")||parent.endsWith(":")?parent:parent+"/");
    }
    private static IOException protectedBin(){return new IOException(L10n.t("휴지통은 휴지통 화면에서 관리해 주세요."));}
    static synchronized void guard(Context c,FileTransferEngine.Item item)throws Exception{
        if(!eligible(item))return;
        if(reserved(item.name)||reservedPath(item.path))throw protectedBin();
        if(item.local!=null){
            String source=item.local.getCanonicalPath();
            if(reservedPath(source))throw protectedBin();
            if(!item.directory||c==null)return;
            for(Entry e:readAll(c))try{
                String bin;
                if("local".equals(e.kind))bin=new File(volumeRoot(c,e.volume),trashName(c)).getCanonicalPath();
                else{
                    String id=DocumentsContract.getTreeDocumentId(Uri.parse(e.tree));int colon=id.indexOf(':');
                    if(colon<0)continue;
                    File volume=volumeRoot(c,id.substring(0,colon));
                    String rel=id.substring(colon+1);
                    File base=rel.isEmpty()?volume:TrashFileOps.checked(volume,rel);
                    bin=new File(base,trashName(c)).getCanonicalPath();
                }
                if(contains(source,bin))throw protectedBin();
            }catch(IOException failure){if(L10n.t("휴지통은 휴지통 화면에서 관리해 주세요.").equals(failure.getMessage()))throw failure;}
        }else if(item.saf!=null){
            String source=DocumentsContract.getDocumentId(item.saf.getUri());
            if(reservedPath(source.substring(source.indexOf(':')+1)))throw protectedBin();
            if(!item.directory||c==null)return;
            for(Entry e:readAll(c)){
                String tree="saf".equals(e.kind)?DocumentsContract.getTreeDocumentId(Uri.parse(e.tree)):e.volume+":";
                String bin=tree+(tree.endsWith(":")?"":"/")+trashName(c);
                if(contains(source,bin))throw protectedBin();
            }
        }
    }
    private static String trashName(Context c){return TrashFileOps.PREFIX+c.getPackageName();}
    private static File journal(Context c,String id)throws IOException{
        File dir=new File(c.getFilesDir(),"local-trash-v1");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Trash index unavailable");
        return new File(dir,TrashFileOps.slotId(id)+".json");
    }
    private static void write(Context c,Entry e)throws Exception{
        AtomicFile f=new AtomicFile(journal(c,e.id));FileOutputStream out=f.startWrite();
        try{out.write(e.json().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));f.finishWrite(out);}
        catch(Exception ex){f.failWrite(out);throw ex;}
    }
    private static void forget(Context c,Entry e)throws IOException{new AtomicFile(journal(c,e.id)).delete();}
    private static List<Entry> readAll(Context c){
        ArrayList<Entry> entries=new ArrayList<>();
        File[] files=new File(c.getFilesDir(),"local-trash-v1").listFiles((d,n)->n.endsWith(".json")||n.endsWith(".json.bak"));
        if(files==null)return entries;
        HashSet<String> seen=new HashSet<>();
        for(File f:files)try{
            String id=f.getName().replaceFirst("\\.json(?:\\.bak)?$","");
            if(!seen.add(id))continue;
            Entry e=Entry.read(new JSONObject(new String(new AtomicFile(journal(c,id)).readFully(),java.nio.charset.StandardCharsets.UTF_8)));
            if(id.equals(e.id))entries.add(e);
        }catch(Exception ignored){}
        entries.sort((a,b)->Long.compare(b.removed,a.removed));return entries;
    }
    private static String volumeId(StorageVolume v){return v.isPrimary()?"primary":v.getUuid();}
    private static File root(Context c,StorageVolume v)throws IOException{
        if(!Environment.MEDIA_MOUNTED.equals(v.getState()))throw unavailable();
        File f=v.isPrimary()?Environment.getExternalStorageDirectory():Build.VERSION.SDK_INT>=30?v.getDirectory():null;
        if(f==null){
            StorageManager m=(StorageManager)c.getSystemService(Context.STORAGE_SERVICE);
            for(File scoped:c.getExternalFilesDirs(null)){
                if(scoped==null||m==null||!v.equals(m.getStorageVolume(scoped)))continue;
                String marker=File.separator+"Android"+File.separator+"data"+File.separator;
                int idx=scoped.getAbsolutePath().indexOf(marker);
                if(idx>0){f=new File(scoped.getAbsolutePath().substring(0,idx));break;}
            }
        }
        if(f==null||!f.isDirectory())throw unavailable();return f.getCanonicalFile();
    }
    private static IOException unavailable(){return new IOException(L10n.t("저장소가 분리되었거나 접근 권한이 없습니다."));}
    private static File volumeRoot(Context c,String id)throws IOException{
        StorageManager m=(StorageManager)c.getSystemService(Context.STORAGE_SERVICE);
        if(m!=null)for(StorageVolume v:m.getStorageVolumes())
            if(id!=null&&!id.isEmpty()&&id.equals(volumeId(v)))return root(c,v);
        throw unavailable();
    }
    private static File slot(Context c,Entry e,boolean create)throws Exception{
        File root=volumeRoot(c,e.volume),bin=new File(root,trashName(c));
        if(Files.isSymbolicLink(bin.toPath()))throw new IOException("Invalid trash folder");
        if(create){
            if(!bin.isDirectory()&&!bin.mkdir())throw unavailable();
            File nomedia=new File(bin,".nomedia");
            if(!Files.isSymbolicLink(nomedia.toPath()))nomedia.createNewFile();
        }
        File s=new File(bin,TrashFileOps.slotId(e.id));
        if(Files.isSymbolicLink(s.toPath()))throw new IOException("Invalid trash slot");
        if(create&&!s.mkdir())throw new IOException("Cannot create trash slot");
        return s;
    }
    private static DocumentFile treeRoot(Context c,String tree)throws Exception{
        Uri uri=Uri.parse(tree);
        if(!AUTH.equals(uri.getAuthority())||!DocumentsContract.isTreeUri(uri))throw unavailable();
        boolean grant=false;
        for(UriPermission p:c.getContentResolver().getPersistedUriPermissions())
            if(p.getUri().equals(uri)&&p.isReadPermission()&&p.isWritePermission()){grant=true;break;}
        if(!grant)throw unavailable();
        DocumentFile f=DocumentFile.fromTreeUri(c,uri);
        if(f==null||!f.isDirectory()||!f.canWrite())throw unavailable();return f;
    }
    static String relativeDocument(String treeId,String docId)throws IOException{
        String prefix=treeId.endsWith(":")?treeId:treeId+"/";
        if(!docId.startsWith(prefix))throw new IOException("Document outside selected storage");
        return TrashFileOps.safeRelative(docId.substring(prefix.length()));
    }
    private static DocumentFile descend(DocumentFile root,String relative,boolean create)throws Exception{
        if(relative.isEmpty())return root;
        DocumentFile f=root;
        for(String n:TrashFileOps.safeRelative(relative).split("/")){
            DocumentFile next=f.findFile(n);
            if(next==null&&create)next=f.createDirectory(n);
            if(next==null||!next.isDirectory()||!n.equals(next.getName()))throw unavailable();
            f=next;
        }return f;
    }
    private static String parent(String rel){int at=rel.lastIndexOf('/');return at<0?"":rel.substring(0,at);}
    private static DocumentFile safSlot(Context c,Entry e,boolean create)throws Exception{
        DocumentFile root=treeRoot(c,e.tree),bin=root.findFile(trashName(c));
        if(bin==null&&create)bin=root.createDirectory(trashName(c));
        if(bin==null||!bin.isDirectory()||!trashName(c).equals(bin.getName()))throw unavailable();
        DocumentFile s=bin.findFile(e.id);
        if(s==null&&create)s=bin.createDirectory(e.id);
        if(s==null||!s.isDirectory()||!e.id.equals(s.getName()))throw unavailable();return s;
    }
    private static Entry from(Context c,FileTransferEngine.Item i)throws Exception{
        if(!eligible(i)||!i.writable())throw unavailable();
        Entry e=new Entry();e.id=UUID.randomUUID().toString();e.removed=System.currentTimeMillis();e.name=i.name;e.size=i.directory?-1:i.size;
        e.kind=i.kind;e.tree="";e.volume="";
        if(i.local!=null){
            StorageManager m=(StorageManager)c.getSystemService(Context.STORAGE_SERVICE);
            StorageVolume v=m==null?null:m.getStorageVolume(i.local);
            if(v==null)throw unavailable();
            e.volume=volumeId(v);if(e.volume==null||e.volume.isEmpty())throw unavailable();
            File root=root(c,v),src=i.local.getAbsoluteFile();
            String path=src.getCanonicalPath();
            if(!path.startsWith(root.getPath()+File.separator)||Files.isSymbolicLink(src.toPath()))throw unavailable();
            e.relative=TrashFileOps.safeRelative(path.substring(root.getPath().length()+1));
            if(!TrashFileOps.checked(root,e.relative).equals(src.getCanonicalFile()))throw unavailable();
        }else{
            e.tree=i.tree;
            Uri t=Uri.parse(e.tree);
            e.relative=relativeDocument(DocumentsContract.getTreeDocumentId(t),DocumentsContract.getDocumentId(i.saf.getUri()));
            e.volume=DocumentsContract.getTreeDocumentId(t).split(":",2)[0];
        }
        if("saf".equals(e.kind)&&Build.VERSION.SDK_INT>=30&&Environment.isExternalStorageManager()){
            try{
                String doc=DocumentsContract.getDocumentId(i.saf.getUri());
                int colon=doc.indexOf(':');
                String volume=doc.substring(0,colon),relative=TrashFileOps.safeRelative(doc.substring(colon+1));
                File file=TrashFileOps.checked(volumeRoot(c,volume),relative);
                if(file.exists()&&file.getName().equals(e.name)&&file.getParentFile().canWrite()){
                    e.kind="local";e.tree="";e.relative=relative;e.volume=volume;
                }
            }catch(Exception ignored){}
        }
        if(!e.name.equals(new File(e.relative).getName()))throw new IOException("File name changed");
        return e;
    }
    private static void moveSameDevice(File src,File dst)throws Exception{
        if(android.system.Os.stat(src.getAbsolutePath()).st_dev!=android.system.Os.stat(dst.getParent()).st_dev)
            throw new IOException("Cannot move between volumes");
        TrashFileOps.move(src,dst);
    }
    private static void put(Context c,FileTransferEngine.Item i)throws Exception{
        guard(c,i);
        Entry e=from(c,i);
        // Persist before move so an interruption cannot strand a moved file without an index.
        write(c,e);
        if("local".equals(e.kind)){
            File s=slot(c,e,true),src=TrashFileOps.checked(volumeRoot(c,e.volume),e.relative);
            try{moveSameDevice(src,new File(s,e.name));}
            catch(Exception ex){if(src.exists()&&s.list()!=null&&s.list().length==0){s.delete();forget(c,e);}throw ex;}
            scan(c,src);
        }else{
            DocumentFile p=descend(treeRoot(c,e.tree),parent(e.relative),false),s=safSlot(c,e,true);
            DocumentFile source=p.findFile(e.name);
            if(source==null||!source.getUri().equals(i.saf.getUri()))throw unavailable();
            Uri moved=DocumentsContract.moveDocument(c.getContentResolver(),source.getUri(),p.getUri(),s.getUri());
            if(moved==null||s.findFile(e.name)==null)throw new IOException(L10n.t("휴지통으로 이동할 수 없습니다. 원본을 확인해 주세요."));
        }
    }
    static synchronized Outcome move(Context c,List<FileTransferEngine.Item> items){
        Outcome out=new Outcome();
        for(FileTransferEngine.Item i:items)try{put(c,i);out.count++;}catch(Exception ex){out.fail(ex);}
        TrashJobService.schedule(c);return out;
    }
    private static void scan(Context c,File f){
        try{android.media.MediaScannerConnection.scanFile(c,new String[]{f.getAbsolutePath()},null,null);}catch(Exception ignored){}
    }
    private static long safBytes(DocumentFile root,int[] count)throws Exception{
        if(Thread.currentThread().isInterrupted()||++count[0]>20000)throw new IOException("Size limit");
        if(!root.isDirectory())return root.length();
        long size=0;for(DocumentFile child:root.listFiles())size=Math.addExact(size,safBytes(child,count));return size;
    }
    static synchronized List<Entry> list(Context c){
        ArrayList<Entry> out=new ArrayList<>();
        for(Entry e:readAll(c)){
            if(Thread.currentThread().isInterrupted())break;
            try{
                if("local".equals(e.kind)){
                    File f=new File(slot(c,e,false),e.name);
                    if(Files.isSymbolicLink(f.toPath())||!f.exists())throw unavailable();
                    e.available=true;if(e.size<0)try{e.size=TrashFileOps.bytes(f);}catch(Exception ignored){}
                }else{
                    DocumentFile f=safSlot(c,e,false).findFile(e.name);if(f==null)throw unavailable();
                    e.available=true;if(e.size<0)try{e.size=safBytes(f,new int[]{0});}catch(Exception ignored){}
                }
            }catch(Exception ignored){e.available=false;}
            out.add(e);
        }return out;
    }
    private static void restore(Context c,Entry e)throws Exception{
        if("local".equals(e.kind)){
            File s=slot(c,e,false),src=new File(s,e.name),dst=TrashFileOps.checked(volumeRoot(c,e.volume),e.relative);
            File p=dst.getParentFile();if(!p.isDirectory()&&!p.mkdirs())throw unavailable();
            if(Files.exists(dst.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException(L10n.t("원래 위치에 같은 이름이 있습니다. 먼저 이름을 변경해 주세요."));
            moveSameDevice(src,dst);forget(c,e);s.delete();scan(c,dst);
        }else{
            DocumentFile s=safSlot(c,e,false),src=s.findFile(e.name);
            if(src==null)throw unavailable();
            DocumentFile p=descend(treeRoot(c,e.tree),parent(e.relative),true);
            if(p.findFile(e.name)!=null)throw new IOException(L10n.t("원래 위치에 같은 이름이 있습니다. 먼저 이름을 변경해 주세요."));
            Uri moved=DocumentsContract.moveDocument(c.getContentResolver(),src.getUri(),s.getUri(),p.getUri());
            if(moved==null||p.findFile(e.name)==null)throw unavailable();
            forget(c,e);s.delete();
        }
    }
    private static void purge(Context c,Entry e)throws Exception{
        if("local".equals(e.kind)){
            File s=slot(c,e,false);if(!s.isDirectory())throw unavailable();
            TrashFileOps.purge(s);forget(c,e);
        }else{
            DocumentFile s=safSlot(c,e,false);if(!s.delete())throw unavailable();forget(c,e);
        }
    }
    static synchronized Outcome act(Context c,Set<String> ids,boolean restore){
        Outcome out=new Outcome();for(Entry e:readAll(c))if(ids.contains(e.id)){
            try{if(restore)restore(c,e);else purge(c,e);out.count++;}catch(Exception ex){out.fail(ex);}
        }return out;
    }
    static synchronized void cleanup(Context c){
        int days=retention(c);if(days==0)return;
        long now=System.currentTimeMillis();
        for(Entry e:readAll(c)){
            if(Thread.currentThread().isInterrupted())return;
            if(TrashFileOps.expired(e.removed,now,days))try{purge(c,e);}catch(Exception ignored){}
        }
    }
    private LocalTrash(){}
}
