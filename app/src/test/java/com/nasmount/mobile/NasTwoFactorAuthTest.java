package com.nasmount.mobile;

import org.junit.Test;
import static org.junit.Assert.*;

public class NasTwoFactorAuthTest {
    @Test public void otpChallengePointsAtCorrectProfileWithoutCodeLeak() {
        Models.RemoteProfile profile = new Models.RemoteProfile();
        profile.nasVendor = "Synology";
        profile.nasOtpCode = "123456";
        NasShareApi.OtpRequired required = new NasShareApi.OtpRequired(profile, false);
        assertSame(profile, required.profile);
        assertFalse(required.rejected);
        assertTrue(required.userMessage().contains("403"));
        assertFalse(required.userMessage().contains("123456"));
        NasShareApi.OtpRequired rejected = new NasShareApi.OtpRequired(profile, true);
        assertTrue(rejected.userMessage().contains("404"));
        assertTrue(rejected.rejected);
        assertFalse(rejected.userMessage().contains("123456"));
    }

    @Test public void officialSynologyCodeMeaningsAreCorrect() {
        assertTrue(NasShareApi.synologyErrorMessage("SYNO.API.Auth", "login", 403)
                .contains("인증코드"));
        assertTrue(NasShareApi.synologyErrorMessage("SYNO.API.Auth", "login", 404)
                .contains("올바르지"));
        assertTrue(NasShareApi.synologyErrorMessage("SYNO.API.Auth", "login", 407)
                .contains("IP"));
    }
}
