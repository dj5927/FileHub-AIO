package com.nasmount.mobile;

import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class ExplorerPageIndexTest {
    @Test public void boundsAndImmutableDirectoryRevision(){
        List<Integer> source=new ArrayList<>();
        for(int i=0;i<10003;i++)source.add(i);
        ExplorerPageIndex<Integer> index=new ExplorerPageIndex<>("revision",source);
        source.clear();
        assertEquals(10003,index.size());
        assertEquals(60,index.end(0));
        assertEquals(120,index.end(60));
        assertEquals(10003,index.end(10000));
        assertEquals(10003,index.end(10003));
        assertEquals(Integer.valueOf(10002),index.get(index.resolve(index.id(10002))));
        assertEquals(-1,index.resolve("old:1"));
        assertEquals(-1,index.resolve("revision:10003"));
        assertEquals(-1,index.resolve("revision:01"));
        assertEquals(-1,index.resolve("revision:-1"));
        assertEquals(-1,index.resolve("revision:99999999999"));
        assertEquals(-1,index.resolve(null));
    }
    @Test public void pageValidation(){
        ExplorerPageIndex<String> index=new ExplorerPageIndex<>("r",Collections.emptyList());
        assertEquals(0,index.end(0));
        for(int offset:new int[]{-1,1}){
            try{index.end(offset);fail("invalid offset");}
            catch(IllegalArgumentException expected){}
        }
    }
    @Test public void actionContractRejectsPathsAndUnknownCommands(){
        String id="01234567-89ab-cdef-0123-456789abcdef:1";
        assertTrue(FlutterUiContract.validNavigationAction("explorer","entryOpen",id));
        assertTrue(FlutterUiContract.validNavigationAction("explorer","entrySelect",id));
        assertTrue(FlutterUiContract.validNavigationAction("explorer","crumb",id));
        assertTrue(FlutterUiContract.validNavigationAction("explorer","classicExplorer",null));
        assertFalse(FlutterUiContract.validNavigationAction("explorer","entryOpen","/sdcard"));
        assertFalse(FlutterUiContract.validNavigationAction("explorer","delete",id));
        assertFalse(FlutterUiContract.validNavigationAction("explorer","classicExplorer",id));
    }
}
