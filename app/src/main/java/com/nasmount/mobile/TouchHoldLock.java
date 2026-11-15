package com.nasmount.mobile;

/**
 * Holds are timed separately from taps and swipes. A deliberate uninterrupted
 * two-second press toggles the video touch lock, never ordinary double taps.
 * This class has no Android dependencies so its behavior can be unit tested.
 */
final class TouchHoldLock {
    static final long HOLD_MILLIS = 2000L;
    private boolean locked;
    private boolean pressing;
    private boolean toggledThisPress;
    private long began;
    private float initialX;
    private float initialY;

    void begin(long timeMillis, float x, float y) {
        pressing = true;
        toggledThisPress = false;
        began = timeMillis;
        initialX = x;
        initialY = y;
    }

    /** Returns true when the press is still stationary. */
    boolean move(float x, float y, float touchSlop) {
        if (!pressing) return false;
        float dx = x - initialX;
        float dy = y - initialY;
        if (dx * dx + dy * dy > touchSlop * touchSlop) pressing = false;
        return pressing;
    }

    boolean toggleIfHeld(long timeMillis) {
        if (!pressing || toggledThisPress || timeMillis - began < HOLD_MILLIS)
            return false;
        locked = !locked;
        toggledThisPress = true;
        return true;
    }

    void end() {
        pressing = false;
    }
    boolean isLocked() { return locked; }
    boolean isPressing() { return pressing; }
    boolean wasToggledThisPress() { return toggledThisPress; }

    void toggleFromButton() {
        locked = !locked;
        pressing = false;
        toggledThisPress = false;
    }
}
