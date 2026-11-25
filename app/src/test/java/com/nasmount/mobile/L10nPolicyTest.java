package com.nasmount.mobile;
import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;
public class L10nPolicyTest {
    @Test public void followsDeviceLanguageWithEnglishFallback() {
        assertEquals("ko",L10n.language(Locale.KOREA));
        assertEquals("en",L10n.language(Locale.US));
        assertEquals("ja",L10n.language(Locale.JAPAN));
        assertEquals("zh",L10n.language(Locale.SIMPLIFIED_CHINESE));
        assertEquals("en",L10n.language(Locale.GERMANY));
    }
    @Test public void untranslatedUserDataAndPersistedKeysRemainUnchanged() {
        assertEquals("여행사진.jpg",L10n.t("여행사진.jpg"));
        assertEquals("remote",L10n.t("remote"));
    }
}
