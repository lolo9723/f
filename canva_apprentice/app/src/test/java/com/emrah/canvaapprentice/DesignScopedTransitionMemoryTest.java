package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

public final class DesignScopedTransitionMemoryTest {

    @Test public void sameDesignNormalizesToSameTransitionScope() {
        assertEquals(
                ExperienceMemoryRepository.transitionScopeKey("  Kampüs   Tanıtım  "),
                ExperienceMemoryRepository.transitionScopeKey("kampus tanitim")
        );
    }

    @Test public void differentBoundDesignsNeverShareTransitionScope() {
        assertNotEquals(
                ExperienceMemoryRepository.transitionScopeKey("2026 Rekreasyon Afişi"),
                ExperienceMemoryRepository.transitionScopeKey("2026 Erasmus Afişi")
        );
    }

    @Test public void unboundNavigationHasDedicatedScope() {
        assertEquals(
                ExperienceMemoryRepository.transitionScopeKey(""),
                ExperienceMemoryRepository.transitionScopeKey("   ")
        );
        assertNotEquals(
                ExperienceMemoryRepository.transitionScopeKey(""),
                ExperienceMemoryRepository.transitionScopeKey("Mevcut Tasarım")
        );
    }

    @Test public void transitionAndCompletionUseSameBoundDesignIdentity() {
        String design = "Öğrenci Oryantasyon Tasarımı";
        assertEquals(
                ExperienceMemoryRepository.completionScopeKey(design),
                ExperienceMemoryRepository.transitionScopeKey(design)
        );
    }
}
