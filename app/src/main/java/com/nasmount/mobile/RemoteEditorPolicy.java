package com.nasmount.mobile;

import java.util.*;
import java.io.IOException;

/** Validate untrusted UI input in Java, before touching the encrypted store. */
final class RemoteEditorPolicy {
    static final String[] TYPES={"SMB","FTP","SFTP","WebDAV","TeraBox","MYBOX"};
    static final int[] PORTS={445,21,22,443,0,0};
    static final String[] FTP_CHARSETS={"UTF-8","CP949 / EUC-KR (한국어)","EUC-KR","서버 기본"};
    private RemoteEditorPolicy() {}
    static final class Invalid extends IllegalArgumentException {
        final String code;
        Invalid(String code) { super(code); this.code=code; }
    }
    static Map<String,Object> fields(Models.RemoteProfile p) {
        boolean edit=p!=null;
        if (!edit) p=new Models.RemoteProfile();
        Map<String,Object> m=new LinkedHashMap<>();
        m.put("editing",edit);
        m.put("protocol",p.protocol);
        m.put("name",p.name);
        m.put("host",p.host);
        m.put("port",p.port);
        m.put("root",p.root);
        m.put("username",p.username);
        m.put("hasSecret",!p.password.isEmpty());
        m.put("ssl",p.ssl);
        m.put("ftpTls",p.ftpImplicitTls?2:p.ftpExplicitTls?1:0);
        m.put("ftpCharset",normalizeCharset(p.ftpCharset,FTP_CHARSETS));
        m.put("sftpCharset",normalizeCharset(p.sftpCharset,SftpFilenameCharset.OPTIONS));
        m.put("ftpPasv",p.ftpPreferPasv);
        m.put("readOnly",p.readOnly);
        return m;
    }
    private static String normalizeCharset(String value,String[] options) {
        if ("CP949".equalsIgnoreCase(value)||"MS949".equalsIgnoreCase(value))
            return options[1];
        return Arrays.asList(options).contains(value)?value:options[0];
    }
    static boolean sameEditableRevision(Models.RemoteProfile a, Models.RemoteProfile b) {
        return a!=null && b!=null && Objects.equals(a.id,b.id)
                && fields(a).equals(fields(b)) && Objects.equals(a.password,b.password)
                && Objects.equals(a.ftpCharset,b.ftpCharset)
                && Objects.equals(a.sftpCharset,b.sftpCharset);
    }
    private static String string(Map<?,?> m,String key,int max) {
        Object value=m.get(key);
        if (!(value instanceof String) || ((String)value).length()>max ||
                ((String)value).indexOf('\0')>=0) throw new Invalid("invalid");
        return (String)value;
    }
    private static boolean bool(Map<?,?> m,String key) {
        Object value=m.get(key);
        if (!(value instanceof Boolean)) throw new Invalid("invalid");
        return (Boolean)value;
    }
    private static int integer(Map<?,?> m,String key,int min,int max) {
        Object value=m.get(key);
        if (!(value instanceof Integer) || (Integer)value<min || (Integer)value>max)
            throw new Invalid("invalid");
        return (Integer)value;
    }
    static Models.RemoteProfile apply(Models.RemoteProfile existing,Map<?,?> draft,String session) {
        String type=string(draft,"protocol",16);
        int index=Arrays.asList(TYPES).indexOf(type);
        if(index<0) throw new Invalid("invalid");
        boolean cloud=index>=4;
        String name=string(draft,"name",512).trim();
        String host=cloud?(index==4?"terabox.com":"mybox.naver.com"):string(draft,"host",1024).trim();
        if(!cloud && (host.isEmpty() || host.indexOf('/')>=0 || host.indexOf('\\')>=0 ||
                host.indexOf('@')>=0 || host.indexOf('?')>=0 || host.indexOf('#')>=0 ||
                host.chars().anyMatch(c->Character.isWhitespace(c)||Character.isISOControl(c))))
            throw new Invalid("host");
        int port=0;
        if(!cloud) {
            try { port=Integer.parseInt(string(draft,"port",8).trim()); }
            catch(NumberFormatException e) { throw new Invalid("port"); }
            if(port<1||port>65535) throw new Invalid("port");
        }
        String root=index==5?"/":string(draft,"root",8192).trim();
        boolean anonymous=Boolean.TRUE.equals(draft.get("anonymous"));
        if(anonymous&&index!=0&&index!=1)throw new Invalid("invalid");
        String user=cloud||anonymous?"":string(draft,"username",1024).trim();
        if((index==0||index==1)&&Boolean.FALSE.equals(draft.get("anonymous"))&&user.isEmpty())
            throw new Invalid("user");
        try {
            if(index==0 && RemoteProvider.relativePath(root).equals("/")) throw new Invalid("share");
            RemoteProvider.relativePath(root);
        } catch(Invalid e) { throw e; }
        catch(IllegalArgumentException e) { throw new Invalid("path"); }
        if(index==2 && user.isEmpty()) throw new Invalid("user");
        boolean ssl=bool(draft,"ssl"),pasv=bool(draft,"ftpPasv"),ro=bool(draft,"readOnly");
        int tls=integer(draft,"ftpTls",0,2);
        String ftp=string(draft,"ftpCharset",128),sftp=string(draft,"sftpCharset",128);
        if(!Arrays.asList(FTP_CHARSETS).contains(ftp) || !Arrays.asList(SftpFilenameCharset.OPTIONS).contains(sftp))
            throw new Invalid("charset");
        if(index==2) try { SftpFilenameCharset.resolve(sftp); }
        catch(IllegalArgumentException e) { throw new Invalid("charset"); }
        String mode=anonymous?"empty":string(draft,"secretMode",16), secret;
        boolean sameEndpoint=existing!=null && type.equals(existing.protocol)
                && host.equalsIgnoreCase(existing.host) && port==existing.port
                && user.equals(existing.username)
                && (index!=3 || ssl==existing.ssl)
                && (index!=1 || (tls==1)==existing.ftpExplicitTls && (tls==2)==existing.ftpImplicitTls);
        switch(mode) {
            case "keep":
                if(!sameEndpoint) throw new Invalid("replaceRequired");
                secret=existing.password; break;
            case "replace":
                secret=string(draft,"secret",16384);
                if(existing!=null && !existing.password.isEmpty() && secret.isEmpty())
                    throw new Invalid("replaceRequired");
                break;
            case "empty":
                if(cloud) throw new Invalid("invalid");
                secret=""; break;
            case "session":
                if(index!=4 || session==null || session.isEmpty()) throw new Invalid("session");
                secret=session; break;
            default: throw new Invalid("invalid");
        }
        if(index==4) try { CloudProvider.ndusCookie(secret); }
        catch(IOException e) { throw new Invalid("session"); }
        if(index==5 && !CloudProvider.validMyBoxToken(secret)) throw new Invalid("pat");
        // All validation succeeded. The existing object is a freshly loaded
        // revision; preserve advanced NAS link settings and non-editor options.
        Models.RemoteProfile p=existing==null?new Models.RemoteProfile():existing;
        if(existing!=null && (!type.equals(existing.protocol) ||
                !host.equalsIgnoreCase(existing.host) || port!=existing.port))
            p.fingerprint="";
        p.protocol=type; p.host=host; p.port=port; p.name=name; p.root=root;
        p.username=user; p.password=secret; p.ssl=ssl; p.readOnly=ro;
        p.ftpExplicitTls=index==1&&tls==1; p.ftpImplicitTls=index==1&&tls==2;
        p.ftpPreferPasv=pasv;
        if(index==1) p.ftpCharset=ftp;
        if(index==2) p.sftpCharset=sftp;
        p.useNativeWebDavLocks=false;
        return p;
    }
}
