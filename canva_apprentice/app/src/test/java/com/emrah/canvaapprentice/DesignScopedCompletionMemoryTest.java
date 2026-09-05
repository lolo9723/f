package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class DesignScopedCompletionMemoryTest {
    @Test public void sameDesignAnchorNormalizesToSameScope() {
        String a = ExperienceMemoryRepository.completionScopeKey("  Summer Campaign  ");
        String b = ExperienceMemoryRepository.completionScopeKey("summer   campaign");
        assertEquals(a, b);
    }

    @Test public void differentDesignAnchorsNeverShareCompletionScope() {
        String first = ExperienceMemoryRepository.completionScopeKey("Client A - Instagram Launch");
        String second = ExperienceMemoryRepository.completionScopeKey("Client B - Instagram Launch");
        assertNotEquals(first, second);
    }

    @Test public void turkishCaseAndDiacriticsAreStableWithinSameAnchor() {
        String a = ExperienceMemoryRepository.completionScopeKey("İlkbahar Çalışması");
        String b = ExperienceMemoryRepository.completionScopeKey("ilkbahar calısması");
        assertEquals(a, b);
    }

    @Test public void missingDesignAnchorFailsClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> ExperienceMemoryRepository.completionScopeKey("   "));
    }
}
