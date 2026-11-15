package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public final class TouchHoldLockTest {
    @Test public void shortPressNeverLocks() {
        TouchHoldLock lock = new TouchHoldLock();
        lock.begin(100, 50, 50);
        assertFalse(lock.toggleIfHeld(2099));
        lock.end();
        assertFalse(lock.toggleIfHeld(2200));
        assertFalse(lock.isLocked());
    }

    @Test public void twoSecondPressLocksExactlyOnce() {
        TouchHoldLock lock = new TouchHoldLock();
        lock.begin(100, 0, 0);
        assertTrue(lock.toggleIfHeld(2100));
        assertTrue(lock.isLocked());
        assertFalse(lock.toggleIfHeld(4100));
        lock.end();
        assertTrue(lock.isLocked());
    }

    @Test public void secondTwoSecondPressUnlocks() {
        TouchHoldLock lock = new TouchHoldLock();
        lock.begin(0, 2, 2);
        assertTrue(lock.toggleIfHeld(2000));
        lock.end();
        lock.begin(7000, 2, 2);
        assertFalse(lock.toggleIfHeld(8999));
        assertTrue(lock.toggleIfHeld(9000));
        lock.end();
        assertFalse(lock.isLocked());
    }

    @Test public void swipeCancelsHoldAndKeepsLockState() {
        TouchHoldLock lock = new TouchHoldLock();
        lock.begin(0, 5, 5);
        assertFalse(lock.move(18, 6, 10));
        assertFalse(lock.toggleIfHeld(4000));
        assertFalse(lock.isLocked());
        lock.begin(5000, 5, 5);
        assertTrue(lock.toggleIfHeld(7000));
        lock.end();
        lock.begin(9000, 0, 0);
        assertFalse(lock.move(100, 10, 12));
        assertFalse(lock.toggleIfHeld(13000));
        assertTrue(lock.isLocked());
    }

    @Test public void smallFingerJitterIsAllowed() {
        TouchHoldLock lock = new TouchHoldLock();
        lock.begin(200, 100, 100);
        assertTrue(lock.move(103, 104, 8));
        assertTrue(lock.toggleIfHeld(2200));
        assertTrue(lock.isLocked());
    }

    @Test public void buttonCanLockAndUnlockIndependentOfHold() {
        TouchHoldLock lock = new TouchHoldLock();
        lock.begin(100, 5, 5);
        lock.toggleFromButton();
        assertTrue(lock.isLocked());
        assertFalse(lock.toggleIfHeld(5000));
        lock.toggleFromButton();
        assertFalse(lock.isLocked());
    }
}
