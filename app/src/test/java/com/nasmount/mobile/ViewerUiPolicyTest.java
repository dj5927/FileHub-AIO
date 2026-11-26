package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class ViewerUiPolicyTest {
    @Test public void commandsMustBelongToCurrentViewerAndEnabledControls(){
        List<String> enabled=Arrays.asList("previous","seek","classic");
        assertTrue(ViewerUiPolicy.accepts("v","v","previous",null,enabled));
        assertFalse(ViewerUiPolicy.accepts("v","old","previous",null,enabled));
        assertFalse(ViewerUiPolicy.accepts("v","v","next",null,enabled));
        assertFalse(ViewerUiPolicy.accepts("v","v","openFile",null,enabled));
        assertFalse(ViewerUiPolicy.accepts("v","v","previous","/sdcard/other",enabled));
        assertFalse(ViewerUiPolicy.accepts("v","v","previous",1,enabled));
        assertTrue(ViewerUiPolicy.accepts("v","v","classic",null,enabled));
    }
    @Test public void seekOnlyAcceptsBoundedIntegerAndEnabledAction(){
        List<String> enabled=Collections.singletonList("seek");
        assertTrue(ViewerUiPolicy.accepts("v","v","seek",0,enabled));
        assertTrue(ViewerUiPolicy.accepts("v","v","seek",1000,enabled));
        for(Object invalid:Arrays.asList(-1,1001,1000000000,2L,2.0,"500",null))
            assertFalse(ViewerUiPolicy.accepts("v","v","seek",invalid,enabled));
        assertFalse(ViewerUiPolicy.accepts("v","v","seek",500,Collections.emptyList()));
    }
    @Test public void snapshotsExposeOnlyPresentationAndUnsignedColors(){
        Map<String,Object> data=ViewerUiPolicy.state("readerSlider","3 / 10",0xff101923,
                0xffffffff,300,false,"next",null,"seek","deleteFile");
        assertEquals(0xff101923L,data.get("bg"));
        assertEquals(0xffffffffL,data.get("fg"));
        assertEquals(300,data.get("progress"));
        assertEquals(Arrays.asList("next","seek","classic"),data.get("enabled"));
        assertFalse(data.containsKey("path"));
        assertFalse(data.containsKey("bytes"));
        assertFalse(data.containsKey("password"));
        assertEquals(-1,ViewerUiPolicy.state("reader",null,0,0,-999,false).get("progress"));
        assertEquals(1000,ViewerUiPolicy.state("readerSlider","",0,0,1001,false).get("progress"));
    }
}
