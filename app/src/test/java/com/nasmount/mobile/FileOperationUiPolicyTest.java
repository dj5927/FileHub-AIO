package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Map;

public class FileOperationUiPolicyTest {
    private static final String REV="01234567-89ab-cdef-0123-456789abcdef";
    @Test public void operationCommandsUseOnlyFolderRevision(){
        for(String action:FileOperationUiPolicy.ACTIONS){
            assertTrue(FlutterUiContract.validNavigationAction("explorer",action,REV));
            assertFalse(FlutterUiContract.validNavigationAction("explorer",action,"/sdcard/DCIM"));
            assertFalse(FlutterUiContract.validNavigationAction("explorer",action,REV+":0"));
        }
        assertFalse(FlutterUiContract.validNavigationAction("explorer","deletePath",REV));
        assertTrue(FlutterUiContract.validNavigationAction("transfer","transferCancel",null));
        assertFalse(FlutterUiContract.validNavigationAction("transfer","transferCancel",REV));
        assertFalse(FlutterUiContract.validNavigationAction("filePrompt","deleteSelected",REV));
    }
    @Test public void readOnlyAndMediaSelectionCannotMoveDeleteOrRename(){
        assertTrue(FileOperationUiPolicy.permits("copySelected",1,4,0,false,false,false,false));
        for(String action:new String[]{"moveSelected","renameSelected","deleteSelected"})
            assertFalse(FileOperationUiPolicy.permits(action,1,4,0,false,false,false,false));
        assertFalse(FileOperationUiPolicy.permits("renameSelected",2,4,0,true,true,true,false));
        assertTrue(FileOperationUiPolicy.permits("renameSelected",1,4,0,true,true,true,false));
    }
    @Test public void clipboardAndActiveTransferConstraints(){
        assertTrue(FileOperationUiPolicy.permits("pasteClipboard",0,4,2,true,true,true,false));
        assertFalse(FileOperationUiPolicy.permits("pasteClipboard",0,4,2,true,true,false,false));
        assertFalse(FileOperationUiPolicy.permits("pasteClipboard",0,4,2,true,true,true,true));
        assertFalse(FileOperationUiPolicy.permits("pasteClipboard",0,4,0,true,true,true,false));
        assertFalse(FileOperationUiPolicy.permits("pasteClipboard",1,4,2,true,true,true,false));
        assertTrue(FileOperationUiPolicy.permits("clearClipboard",0,4,2,true,true,false,true));
        assertFalse(FileOperationUiPolicy.permits("copySelected",1,4,0,true,true,true,true));
    }
    @Test public void progressCannotReach100BeforeRealSuccess(){
        Map<String,Object> busy=FileOperationUiPolicy.transfer(true,false,false,false,false,100,100,"test",1,1,false);
        assertEquals(99,busy.get("percent"));
        assertEquals(100,FileOperationUiPolicy.transfer(false,false,true,false,false,100,100,"test",1,1,false).get("percent"));
        assertEquals(-1,FileOperationUiPolicy.transfer(true,false,false,false,false,0,-1,"",0,1,true).get("percent"));
        assertEquals(99,FileOperationUiPolicy.transfer(false,false,true,true,false,100,100,"",1,1,false).get("percent"));
        assertEquals(99,FileOperationUiPolicy.transfer(false,false,false,false,true,100,100,"",1,1,false).get("percent"));
        assertFalse(busy.containsKey("path"));
    }
}
