package com.nasmount.mobile;

import androidx.core.content.FileProvider;

/**
 * Dedicated FileProvider subclass for reliable content-URI grants to Android
 * system package installer across Android vendor implementations.
 */
public final class NasMountFileProvider extends FileProvider {
    public NasMountFileProvider() { super(); }
}
