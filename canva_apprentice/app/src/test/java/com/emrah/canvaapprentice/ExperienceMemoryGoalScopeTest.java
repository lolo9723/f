package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ExperienceMemoryGoalScopeTest {
    @Test public void equivalentGoalTextSharesExactGoalScope() {
        assertEquals(
                ExperienceMemoryRepository.goalScopeKey("  Kampüs   Tanıtımını Düzenle  "),
                ExperienceMemoryRepository.goalScopeKey("kampus tanitimini duzenle")
        );
    }

    @Test public void differentGoalsNeverShareReplayScope() {
        assertNotEquals(
                ExperienceMemoryRepository.goalScopeKey("Başlığı değiştir"),
                ExperienceMemoryRepository.goalScopeKey("Yeni sayfa ekle")
        );
    }
}
