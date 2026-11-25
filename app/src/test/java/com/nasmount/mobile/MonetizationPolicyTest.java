package com.nasmount.mobile;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class MonetizationPolicyTest {
    static Models.RemoteProfile p(String id,String type){
        Models.RemoteProfile p=new Models.RemoteProfile();p.id=id;p.protocol=type;return p;
    }
    @Test public void onePerProtocolAndStableRestore(){
        List<Models.RemoteProfile> saved=Arrays.asList(p("1","SMB"),p("2","smb"),
            p("3","FTP"),p("4","SFTP"),p("5","WebDAV"),p("6","TeraBox"),p("7","MYBOX"));
        assertEquals(6,MonetizationPolicy.accessible(saved,false).size());
        assertEquals(7,MonetizationPolicy.accessible(saved,true).size());
        assertTrue(MonetizationPolicy.allowed(saved,"1",false));
        assertFalse(MonetizationPolicy.allowed(saved,"2",false));
        assertTrue(MonetizationPolicy.canSave(saved,"1","SMB",false));
        assertFalse(MonetizationPolicy.canSave(saved,"2","SMB",false));
        assertFalse(MonetizationPolicy.canSave(saved,"new","ftp",false));
        assertFalse(MonetizationPolicy.canSave(saved,"1","WebDAV",false));
        assertTrue(MonetizationPolicy.canSave(saved,"new","SMB",true));
        assertEquals(7,saved.size());
    }
    @Test public void tenMinutesExcludeBackgroundAndResetOnlyWhenShown(){
        MonetizationPolicy.UsageClock c=new MonetizationPolicy.UsageClock(0);
        c.update(1000,true);c.update(301000,false);
        c.update(1000000,true);c.update(1299999,true);
        assertFalse(c.due());c.update(1300000,false);assertTrue(c.due());
        c.update(2000000,true);assertTrue(c.due());
        c.shown();assertEquals(0,c.accumulated());assertFalse(c.due());
        c.update(2000000,true);c.update(2600000,false);assertTrue(c.due());
        assertTrue(new MonetizationPolicy.UsageClock(c.accumulated()).due());
    }
}
