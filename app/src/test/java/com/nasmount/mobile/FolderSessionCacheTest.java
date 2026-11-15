package com.nasmount.mobile;

import org.junit.Test;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public final class FolderSessionCacheTest {
    @Test public void oneSuccessfulListRequestIsReusedUntilRefresh() throws Exception {
        FolderSessionCache cache = new FolderSessionCache();
        Models.RemoteProfile profile = new Models.RemoteProfile();
        profile.id = "test-nas";
        AtomicInteger called = new AtomicInteger();
        FolderSessionCache.Loader loader = () -> {
            called.incrementAndGet();
            return Collections.singletonList(
                    new Models.Entry("Film.mkv", "/Films/Film.mkv", false));
        };
        assertEquals(1, cache.load(profile, "/Films", loader).size());
        assertEquals(1, cache.load(profile, "/Films", loader).size());
        assertEquals(1, called.get());
        cache.invalidateProfile(profile);
        assertNull(cache.peek(profile, "/Films"));
        assertEquals(1, cache.load(profile, "/Films", loader).size());
        assertEquals(2, called.get());
    }
    @Test public void rememberedCountIsClearedOnlyByExplicitInvalidation() {
        FolderSessionCache cache = new FolderSessionCache();
        Models.RemoteProfile profile = new Models.RemoteProfile();
        profile.id = "profile-count";
        String key = FolderSessionCache.key(profile, "/Movies") + "|count|hidden:true";
        cache.rememberCount(key, 23);
        assertEquals(Integer.valueOf(23), cache.count(key));
        assertEquals(Integer.valueOf(23), cache.count(key));
        cache.invalidateProfile(profile);
        assertNull(cache.count(key));
    }
    @Test public void failedFetchIsNotCached() {
        FolderSessionCache cache = new FolderSessionCache();
        Models.RemoteProfile profile = new Models.RemoteProfile();
        profile.id = "retry";
        try {
            cache.load(profile, "/dir", () -> {
                throw new java.io.IOException("network down");
            });
            fail("expected a failed fetch");
        } catch (Exception expected) {
            assertEquals("network down", expected.getMessage());
        }
        assertNull(cache.peek(profile, "/dir"));
    }
}
