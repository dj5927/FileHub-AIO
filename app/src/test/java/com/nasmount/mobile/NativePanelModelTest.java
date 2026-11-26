package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class NativePanelModelTest {
    @Test public void shelfOpenAndRemoveUseSeparateRevisionBoundCommands(){
        AtomicInteger calls=new AtomicInteger();
        NativePanelModel panel=new NativePanelModel("",new String[0],0,null);
        panel.folder("Movies","NAS / Movies",()->calls.set(1),()->calls.set(2));
        panel.action("Next","",true,()->calls.set(3));
        Object revision=panel.snapshot().get("revision");
        assertNull(panel.command("old","r0:remove",null));
        assertNull(panel.command(revision,"r0:remove",1));
        assertNull(panel.command(revision,"r1:remove",null));
        panel.command(revision,"r0",null).run();assertEquals(1,calls.get());
        panel.command(revision,"r0:remove",null).run();assertEquals(2,calls.get());
    }
    @Test public void actionsAndTabsMustMatchCurrentRender(){
        AtomicInteger calls=new AtomicInteger();
        NativePanelModel panel=new NativePanelModel("",new String[]{"General","Video"},0,calls::set);
        panel.section("Title");panel.note("Details");
        panel.action("Open","",true,calls::incrementAndGet);
        panel.action("Locked","",false,calls::incrementAndGet);
        Object revision=panel.snapshot().get("revision");
        assertNull(panel.command("previous","r2",null));
        assertNull(panel.command(revision,"r0",null));
        assertNull(panel.command(revision,"r1",null));
        assertNull(panel.command(revision,"r3",null));
        assertNull(panel.command(revision,"r2",1));
        assertNull(panel.command(revision,"/sdcard",null));
        panel.command(revision,"r2",null).run();
        assertEquals(1,calls.get());
        assertNull(panel.command(revision,"tab",-1));
        assertNull(panel.command(revision,"tab",2));
        assertNull(panel.command(revision,"tab",1L));
        panel.command(revision,"tab",0).run();
        assertEquals(0,calls.get());
        assertFalse(new NativePanelModel("",new String[0],0,null).matches(revision));
    }
    @Test public void choicesUseBoundedNativeCallbackOnly(){
        AtomicInteger calls=new AtomicInteger(-1);
        NativePanelModel panel=new NativePanelModel("",new String[0],0,null);
        panel.action("Speed","Normal",true,()->fail("wrong callback"));
        panel.lastChoice(new String[]{"Slow","Normal","Fast"},1,calls::set);
        Map<String,Object> snapshot=panel.snapshot();
        Object revision=snapshot.get("revision");
        assertNull(panel.command(revision,"r0",null));
        assertNull(panel.command(revision,"r0","2"));
        assertNull(panel.command(revision,"r0",3));
        assertNull(panel.command(revision,"tab",0));
        panel.command(revision,"r0",2).run();
        assertEquals(2,calls.get());
        Map<?,?> row=(Map<?,?>)((List<?>)snapshot.get("rows")).get(0);
        assertEquals("choice",row.get("kind"));
        assertEquals(Arrays.asList("Slow","Normal","Fast"),row.get("choices"));
        assertEquals(1,row.get("selected"));
        assertFalse(row.containsKey("callback"));
    }
    @Test public void documentSaveIsOnlyEnabledWhenReadyWritableAndIdle(){
        for(boolean ready:new boolean[]{false,true})
            for(boolean busy:new boolean[]{false,true})
                for(boolean readOnly:new boolean[]{false,true})
                    for(boolean hasSave:new boolean[]{false,true}){
                        Map<String,Object> state=ViewerUiPolicy.document("file.xlsx",0,0,hasSave,ready,busy,readOnly);
                        Collection<?> enabled=(Collection<?>)state.get("enabled");
                        assertEquals(hasSave&&ready&&!busy&&!readOnly,
                                ViewerUiPolicy.accepts("v","v","save",null,enabled));
                        assertEquals(hasSave&&ready&&!busy,
                                ViewerUiPolicy.accepts("v","v","search",null,enabled));
                        assertFalse(ViewerUiPolicy.accepts("v","old","search",null,enabled));
                        assertFalse(ViewerUiPolicy.accepts("v","v","search","payload",enabled));
                        assertFalse(ViewerUiPolicy.accepts("v","old","save",null,enabled));
                        assertFalse(ViewerUiPolicy.accepts("v","v","save","payload",enabled));
                        assertTrue(enabled.contains("back"));
                    }
    }
}
