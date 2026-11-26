package com.nasmount.mobile;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class RemoteEditorPolicyTest {
    private static Map<String,Object> draft(String protocol) {
        Map<String,Object> m=new HashMap<>();
        m.put("protocol",protocol);m.put("name","NAS");m.put("host","nas.example.test");
        m.put("port",Integer.toString(RemoteEditorPolicy.PORTS[Arrays.asList(RemoteEditorPolicy.TYPES).indexOf(protocol)]));
        m.put("root","share/folder");m.put("username","user");m.put("secretMode","replace");m.put("secret","test-value-123");
        m.put("ssl",true);m.put("ftpTls",0);m.put("ftpCharset","UTF-8");m.put("sftpCharset","UTF-8");
        m.put("ftpPasv",true);m.put("readOnly",false);return m;
    }
    private static void rejects(String code,Models.RemoteProfile p,Map<String,Object> m,String session) {
        try { RemoteEditorPolicy.apply(p,m,session);fail("accepted invalid draft "+code); }
        catch(RemoteEditorPolicy.Invalid error){assertEquals(code,error.code);}
    }
    @Test public void allSixProtocolsAndDefaults() {
        for(String type:RemoteEditorPolicy.TYPES) {
            Map<String,Object> m=draft(type);
            if("MYBOX".equals(type)) m.put("secret","mbx_pat_test_value_123456789");
            Models.RemoteProfile p=RemoteEditorPolicy.apply(null,m,null);
            assertEquals(type,p.protocol);
            assertEquals(RemoteEditorPolicy.PORTS[Arrays.asList(RemoteEditorPolicy.TYPES).indexOf(type)],p.port);
            assertEquals("MYBOX".equals(type)?"/":"share/folder",p.root);
            if("MYBOX".equals(type)||"TeraBox".equals(type))assertEquals("",p.username);
            else assertEquals("nas.example.test",p.host);
        }
    }
    @Test public void keepsExistingSecretAndAdvancedSettingsWithoutExportingThem() {
        Models.RemoteProfile p=RemoteEditorPolicy.apply(null,draft("WebDAV"),null);
        p.fingerprint="fingerprint";p.nasVendor="Synology";p.nasApiPassword="api-secret";
        p.ftpTls12Only=true;p.autoConnect=true;
        Map<String,Object> snapshot=RemoteEditorPolicy.fields(p);
        assertFalse(snapshot.toString().contains("test-value"));
        assertFalse(snapshot.toString().contains("api-secret"));
        assertFalse(snapshot.containsKey("password"));
        assertFalse(snapshot.containsKey("secret"));
        Map<String,Object> m=draft("WebDAV");
        m.put("secretMode","keep");m.remove("secret");m.put("name","Renamed");m.put("readOnly",true);
        Models.RemoteProfile edited=RemoteEditorPolicy.apply(p,m,null);
        assertSame(p,edited);assertEquals("test-value-123",edited.password);
        assertEquals("api-secret",edited.nasApiPassword);assertEquals("fingerprint",edited.fingerprint);
        assertTrue(edited.ftpTls12Only);assertTrue(edited.autoConnect);assertTrue(edited.readOnly);
    }
    @Test public void refusesSecretReuseAcrossServerAccountProtocolOrSecurityChanges() {
        Models.RemoteProfile p=RemoteEditorPolicy.apply(null,draft("WebDAV"),null);
        for(String field:Arrays.asList("host","port","username","ssl","protocol")){
            Map<String,Object> m=draft("WebDAV");m.put("secretMode","keep");
            switch(field){
                case "host":m.put(field,"other.example.test");break;
                case "port":m.put(field,"8443");break;
                case "username":m.put(field,"other");break;
                case "ssl":m.put(field,false);break;
                case "protocol":m.put(field,"SMB");break;
            }
            rejects("replaceRequired",p,m,null);
        }
        Map<String,Object> m=draft("WebDAV");m.put("host","new.example.test");m.put("secret","");
        rejects("replaceRequired",p,m,null);
        assertEquals("nas.example.test",p.host);
        p.fingerprint="prior";m.put("secret","explicit-new-password");
        assertEquals("",RemoteEditorPolicy.apply(p,m,null).fingerprint);
        m.put("secretMode","empty");
        assertEquals("",RemoteEditorPolicy.apply(p,m,null).password);
    }
    @Test public void validatesProtocolSpecificFieldsAndNativeSession() {
        Map<String,Object> m=draft("SMB");m.put("root","/");rejects("share",null,m,null);
        m=draft("SMB");m.put("root","../folder");rejects("path",null,m,null);
        m=draft("SMB");m.put("host","https://nas.example.test");rejects("host",null,m,null);
        m=draft("FTP");m.put("port","65536");rejects("port",null,m,null);
        m=draft("SFTP");m.put("username"," ");rejects("user",null,m,null);
        m=draft("SFTP");m.put("sftpCharset","unknown");rejects("charset",null,m,null);
        m=draft("FTP");m.put("ftpTls",2);m.put("port","990");m.put("ftpPasv",false);
        Models.RemoteProfile ftp=RemoteEditorPolicy.apply(null,m,null);
        assertTrue(ftp.ftpImplicitTls);assertFalse(ftp.ftpExplicitTls);assertFalse(ftp.ftpPreferPasv);
        m=draft("TeraBox");m.put("secretMode","session");m.remove("secret");
        rejects("session",null,m,null);
        assertEquals("ndus=test_value_123456",RemoteEditorPolicy.apply(null,m,"ndus=test_value_123456").password);
        m=draft("MYBOX");rejects("pat",null,m,null);
        m.put("secretMode","session");rejects("session",null,m,"ndus=test_value_123456");
    }
    @Test public void staleEditorRevisionRejectsChangedCredentialOrName() {
        Models.RemoteProfile a=RemoteEditorPolicy.apply(null,draft("SMB"),null);
        Models.RemoteProfile b=RemoteEditorPolicy.apply(null,draft("SMB"),null);
        b.id=a.id;
        assertTrue(RemoteEditorPolicy.sameEditableRevision(a,b));
        b.password="changed";
        assertFalse(RemoteEditorPolicy.sameEditableRevision(a,b));
        b.password=a.password;b.name="changed";
        assertFalse(RemoteEditorPolicy.sameEditableRevision(a,b));
    }
}
