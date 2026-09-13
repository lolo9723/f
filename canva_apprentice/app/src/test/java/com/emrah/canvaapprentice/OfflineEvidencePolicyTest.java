package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfflineEvidencePolicyTest {
    @Test public void staleOfflineNodeCannotOverrideValidatedAndroidNetwork() {
        assertEquals(OfflineEvidencePolicy.Verdict.CONFLICT,
                OfflineEvidencePolicy.classify(true, Boolean.TRUE));
        String evidence = OfflineEvidencePolicy.teacherEvidence(true, Boolean.TRUE);
        assertTrue(evidence.contains("potentially stale"));
        assertTrue(evidence.contains("MUST NOT by itself cause NOOP/HUMAN"));
    }

    @Test public void validatedNetworkRewritesOfflineBannerForTeacherWithoutChangingRowCount() {
        assertTrue(OfflineEvidencePolicy.isOfflineBanner("İnternete bağlı değilsiniz", ""));
        assertEquals("CONNECTIVITY_BANNER_CONFLICT_ANDROID_VALIDATED (stale candidate; NOT offline proof)",
                OfflineEvidencePolicy.teacherSafeText("İnternete bağlı değilsiniz", "", Boolean.TRUE));
        assertTrue(OfflineEvidencePolicy.teacherSafeDescription("İnternete bağlı değilsiniz", "", Boolean.TRUE)
                .contains("do not NOOP/HUMAN solely"));
    }

    @Test public void realOfflineBannerIsNotRedactedWhenAndroidIsUnvalidated() {
        assertEquals("İnternete bağlı değilsiniz",
                OfflineEvidencePolicy.teacherSafeText("İnternete bağlı değilsiniz", "", Boolean.FALSE));
        assertEquals(OfflineEvidencePolicy.Verdict.OFFLINE_CONFIRMED,
                OfflineEvidencePolicy.classify(true, Boolean.FALSE));
        assertTrue(OfflineEvidencePolicy.teacherEvidence(true, Boolean.FALSE)
                .contains("OFFLINE_CONFIRMED"));
    }

    @Test public void offlineNodeWithoutNetworkAuthorityIsNotConfirmedOffline() {
        assertEquals(OfflineEvidencePolicy.Verdict.UNKNOWN,
                OfflineEvidencePolicy.classify(true, null));
        String evidence = OfflineEvidencePolicy.teacherEvidence(true, null);
        assertFalse(evidence.contains("OFFLINE_CONFIRMED"));
        assertTrue(evidence.contains("request SCREENSHOT/revalidation"));
    }

    @Test public void validatedNetworkWithoutBannerIsOnline() {
        assertEquals(OfflineEvidencePolicy.Verdict.ONLINE,
                OfflineEvidencePolicy.classify(false, Boolean.TRUE));
    }
}
