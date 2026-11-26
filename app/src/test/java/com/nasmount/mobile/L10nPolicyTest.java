package com.nasmount.mobile;
import org.junit.Test;
import java.util.Locale;
import static org.junit.Assert.*;
public class L10nPolicyTest {
    @Test public void manualChoiceOverridesSystemAndInvalidChoiceReturnsToSystem(){
        assertEquals("ja",L10n.resolve("ja",Locale.KOREA));
        assertEquals("ko",L10n.resolve("ko",Locale.JAPAN));
        assertEquals("zh",L10n.resolve("zh",Locale.US));
        assertEquals("en",L10n.resolve("en",Locale.KOREA));
        assertEquals("ja",L10n.resolve("system",Locale.JAPAN));
        assertEquals("en",L10n.resolve("system",Locale.GERMANY));
        assertEquals("system",L10n.normalizeMode("unsupported"));
        assertEquals("system",L10n.normalizeMode(null));
    }
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
    @Test public void dynamicPromptsNeverInterpretUserFileNamesAsFormatStrings() {
        String filename="여행 100% %1$d.mp4";
        assertEquals("서버의 ‘"+filename+"’을(를) 삭제할까요?\n이 작업은 되돌릴 수 없을 수 있습니다.",
                L10n.f("서버의 ‘%1$s’을(를) 삭제할까요?\n이 작업은 되돌릴 수 없을 수 있습니다.",filename));
        assertEquals("3개 공유 링크를 해제할까요?",L10n.f("%1$d개 공유 링크를 해제할까요?",3));
    }
}
