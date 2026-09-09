package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExperienceMemoryReplayEvidenceTest {
    @Test public void singleSuccessIsNotEnoughForReplayEvidence() {
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(1,0,"after-fp"));
    }

    @Test public void repeatedVerifiedSuccessesCanBecomeReplayEvidence() {
        assertTrue(ExperienceMemoryRepository.mayReplayTransition(2,0,"after-fp"));
    }

    @Test public void failuresStillSuppressRepeatedSuccesses() {
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(2,2,"after-fp"));
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(3,2,"after-fp"));
    }

    @Test public void verifiedAfterStateRemainsMandatory() {
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(10,0,""));
        assertFalse(ExperienceMemoryRepository.mayReplayTransition(10,0,"   "));
    }
}
