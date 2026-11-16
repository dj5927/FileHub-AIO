package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class VideoPipPolicyTest {
    @Test public void homePipRequiresOptInAndActualPlayback(){
        assertFalse(VideoPipPolicy.autoEnter(false,true,false,true,false));
        assertFalse(VideoPipPolicy.autoEnter(true,true,false,false,false));
        assertFalse(VideoPipPolicy.autoEnter(true,false,false,true,false));
        assertFalse(VideoPipPolicy.autoEnter(true,true,true,true,false));
        assertFalse(VideoPipPolicy.autoEnter(true,true,false,true,true));
        assertTrue(VideoPipPolicy.autoEnter(true,true,false,true,false));
    }
    @Test public void closingPipMustReleaseEvenBeforeModeFlagClears(){
        assertTrue(VideoPipPolicy.releaseWhenStopped(true,false));
        assertTrue(VideoPipPolicy.releaseWhenStopped(false,true));
        assertFalse(VideoPipPolicy.releaseWhenStopped(false,false));
    }
    @Test public void portraitAndLandscapeVideoKeepCorrectShape(){
        assertArrayEquals(new int[]{16,9},VideoPipPolicy.aspect(0,0));
        assertArrayEquals(new int[]{1920,1080},
                VideoPipPolicy.aspect(1920,1080));
        assertArrayEquals(new int[]{1080,1920},
                VideoPipPolicy.aspect(1080,1920));
        assertArrayEquals(new int[]{239,100},
                VideoPipPolicy.aspect(3840,1000));
        assertArrayEquals(new int[]{100,239},
                VideoPipPolicy.aspect(600,2400));
    }
}
