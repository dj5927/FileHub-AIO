package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class EpubEndPositionGuardTest {
    @Test public void lateFontReflowRepinsLastPageEvenAfterInitialSettle(){
        EpubEndPositionGuard g=new EpubEndPositionGuard();
        g.pin(11,1100,12*1100);
        assertFalse(g.observe(11,1100,12*1100));
        // The user repro: first looks like 13 pages; later actually 15.
        assertTrue(g.observe(11,1100,14*1100));
        assertFalse(g.observe(11,1100,14*1100));
        // Fold resize also changes the last-page boundary.
        assertTrue(g.observe(11,780,18*780));
        assertFalse(g.observe(12,780,19*780));
    }
    @Test public void manualTurnImmediatelyStopsAutoRepinning(){
        EpubEndPositionGuard g=new EpubEndPositionGuard();
        g.pin(11,1100,14*1100);
        assertTrue(g.activeFor(11));
        g.clear();
        assertFalse(g.activeFor(11));
        assertFalse(g.observe(11,1100,15*1100));
    }
}
