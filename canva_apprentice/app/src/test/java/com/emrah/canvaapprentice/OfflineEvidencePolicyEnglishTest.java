package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfflineEvidencePolicyEnglishTest {
    @Test public void noInternetConnectionEnglishBannerIsRecognized() {
        assertTrue(OfflineEvidencePolicy.isOfflineBanner("No internet connection", ""));
        assertEquals(OfflineEvidencePolicy.Verdict.CONFLICT,
                OfflineEvidencePolicy.classify(true, Boolean.TRUE));
    }
}
