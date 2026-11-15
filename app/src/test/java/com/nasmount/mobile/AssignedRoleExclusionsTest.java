package com.nasmount.mobile;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AssignedRoleExclusionsTest {
    @Test public void childIsActuallyDisabledWithoutDisablingItsSiblings(){
        Set<String> exceptions=new HashSet<>();
        exceptions.add(AssignedRoleExclusions.key("video","remote","nas1",
                "/Movies/Excluded"));
        assertTrue(AssignedRoleExclusions.blocked(exceptions,"video","remote",
                "nas1","/Movies/Excluded/Part1","/Movies".length()));
        assertFalse(AssignedRoleExclusions.blocked(exceptions,"video","remote",
                "nas1","/Movies/Other","/Movies".length()));
        assertFalse(AssignedRoleExclusions.blocked(exceptions,"video","remote",
                "nas2","/Movies/Excluded","/Movies".length()));
    }
    @Test public void aMoreSpecificDirectMarkRestoresAnExcludedDescendant(){
        Set<String> exceptions=new HashSet<>();
        exceptions.add(AssignedRoleExclusions.key("comic","local","",
                "/storage/emulated/0/Books/Hidden"));
        assertTrue(AssignedRoleExclusions.blocked(exceptions,
                "comic","local","","/storage/emulated/0/Books/Hidden",
                "/storage/emulated/0/Books".length()));
        assertFalse(AssignedRoleExclusions.blocked(exceptions,
                "comic","local","","/storage/emulated/0/Books/Hidden/Reopened",
                "/storage/emulated/0/Books/Hidden/Reopened".length()));
    }
    @Test public void disablingComicDoesNotDisableTextOnSameFolder(){
        Set<String> exceptions=new HashSet<>();
        exceptions.add(AssignedRoleExclusions.key("comic","local","","/Books"));
        assertTrue(AssignedRoleExclusions.blocked(exceptions,
                "comic","local","","/Books","/Books".length()));
        assertFalse(AssignedRoleExclusions.blocked(exceptions,
                "text","local","","/Books","/Books".length()));
    }
    @Test public void segmentBoundariesAndStorageVolumeRootsAreSafe(){
        assertFalse(AssignedRoleExclusions.within("/Movies","/Movies2"));
        assertTrue(AssignedRoleExclusions.within("/Movies","/Movies/Drama"));
        assertTrue(AssignedRoleExclusions.within("primary:","primary:Books"));
        assertFalse(AssignedRoleExclusions.within(
                "primary:Movies","primary:Movies2"));
    }
}
