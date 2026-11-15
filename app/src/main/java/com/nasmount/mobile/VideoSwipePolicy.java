package com.nasmount.mobile;

/**
 * Recognizes deliberate vertical swipes. Horizontal seeking, ordinary taps,
 * the player controls and the screen-lock gesture remain independent.
 */
final class VideoSwipePolicy {
    static final int NONE = 0;
    static final int BRIGHTNESS = 1;
    static final int VOLUME = 2;

    private int side = NONE;
    private boolean active;
    private boolean rejected;
    private float startX;
    private float startY;
    private float height;

    void begin(float x, float y, float screenWidth, float screenHeight,
               float topInset, float bottomInset) {
        side = NONE;
        active = false;
        rejected = false;
        if (screenWidth <= 0 || screenHeight <= 0 ||
                y < topInset || y > screenHeight - bottomInset) return;
        side = x < screenWidth / 2f ? BRIGHTNESS : VOLUME;
        startX = x;
        startY = y;
        height = screenHeight;
    }

    boolean update(float x, float y, float touchSlop) {
        if (side == NONE || rejected) return false;
        float dx = x - startX;
        float dy = y - startY;
        if (!active) {
            if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy) * 1.25f) {
                rejected = true;
                return false;
            }
            if (Math.abs(dy) <= touchSlop ||
                    Math.abs(dy) < Math.abs(dx) * 1.25f) return false;
            active = true;
        }
        return true;
    }

    float amount(float y) {
        if (!active || height <= 0) return 0;
        // Swipe upwards increases volume/brightness.
        return Math.max(-1f, Math.min(1f, (startY - y) / (height * 0.7f)));
    }

    /** Reserve controls area only when controls are actually shown. */
    static float bottomProtectionPx(float screenHeight, float requestedPx,
                                    boolean controlsVisible) {
        if (screenHeight <= 0 || requestedPx <= 0) return 0;
        return Math.min(requestedPx, screenHeight * (controlsVisible ? 0.19f : 0.075f));
    }

    static float topProtectionPx(float screenHeight, float requestedPx,
                                 boolean controlsVisible) {
        if (screenHeight <= 0 || requestedPx <= 0) return 0;
        return Math.min(requestedPx, screenHeight * (controlsVisible ? 0.18f : 0.10f));
    }

    int side() { return side; }
    boolean isActive() { return active; }
    void reset() { side = NONE; active = false; rejected = false; }

    static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
