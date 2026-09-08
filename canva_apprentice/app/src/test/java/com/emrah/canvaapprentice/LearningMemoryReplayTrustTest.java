package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LearningMemoryReplayTrustTest {
    @Test public void failureOnlyLedgerIsNeverReplayable() {
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(0, 4, ""));
    }

    @Test public void tiedEvidenceStandsDown() {
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(2, 2, "after"));
    }

    @Test public void failuresDominatingSuccessesStandDown() {
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(2, 3, "after"));
    }

    @Test public void successfulRouteNeedsVerifiedAfterState() {
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(4, 0, "  "));
    }

    @Test public void clearlySuccessfulRouteMayBeShownAsEvidence() {
        assertTrue(ExperienceMemoryRepository.mayReplayTransition(3, 1, "after"));
    }
}
