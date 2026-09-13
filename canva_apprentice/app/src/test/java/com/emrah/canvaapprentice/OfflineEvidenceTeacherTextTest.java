package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OfflineEvidenceTeacherTextTest {
    @Test public void englishOfflineBannerIsAlsoTreatedAsConflictOnValidatedNetwork() {
        assertTrue(OfflineEvidencePolicy.isOfflineBanner("You're offline", ""));
        assertEquals("CONNECTIVITY_BANNER_CONFLICT_ANDROID_VALIDATED (stale candidate; NOT offline proof)",
                OfflineEvidencePolicy.teacherSafeText("You're offline", "", Boolean.TRUE));
    }
}
