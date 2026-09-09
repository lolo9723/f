package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class LearningMemoryTaskGoalGuardTest {
    @Test public void exactCurrentGoalMayRecord() {
        assertTrue(ExperienceMemoryRepository.sameTaskGoal(
                "Instagram postunu düzenle",
                "Instagram postunu düzenle"));
    }

    @Test public void normalizedEquivalentGoalMayRecord() {
        assertTrue(ExperienceMemoryRepository.sameTaskGoal(
                "  İnstagram   Postunu Düzenle ",
                "instagram postunu düzenle"));
    }

    @Test public void staleDifferentGoalCannotRecord() {
        assertFalse(ExperienceMemoryRepository.sameTaskGoal(
                "Instagram postunu düzenle",
                "Sunumu düzenle"));
    }

    @Test public void emptyGoalCannotRecord() {
        assertFalse(ExperienceMemoryRepository.sameTaskGoal("", "Sunumu düzenle"));
        assertFalse(ExperienceMemoryRepository.sameTaskGoal("Sunumu düzenle", ""));
    }
}
