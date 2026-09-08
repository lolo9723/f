package com.emrah.canvaapprentice;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import org.junit.Test;
import static org.junit.Assert.*;

public class LearningMemoryFailureAggregationRegressionTest {
    private static String source() throws Exception {
        byte[] bytes = Files.readAllBytes(Paths.get(
                "src/main/java/com/emrah/canvaapprentice/ExperienceMemoryRepository.java"));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    @Test public void repeatedFailuresReduceTrustOfPreviouslySuccessfulOutcome() {
        assertEquals(2.0 / 3.0, ExperienceMemoryRepository.transitionTrust(1,0), 0.000001);
        assertEquals(2.0 / 13.0, ExperienceMemoryRepository.transitionTrust(1,10), 0.000001);
        assertTrue(ExperienceMemoryRepository.transitionTrust(5,1)
                > ExperienceMemoryRepository.transitionTrust(1,5));
    }

    @Test public void invalidCountsFailClosed() {
        assertEquals(0.0, ExperienceMemoryRepository.transitionTrust(-1,0), 0.0);
        assertEquals(0.0, ExperienceMemoryRepository.transitionTrust(0,-1), 0.0);
    }

    @Test public void persistencePropagatesActionLevelFailuresIntoSuccessfulOutcomes() throws Exception {
        String src = source();
        assertTrue(src.contains("private static final int VERSION = 5"));
        assertTrue(src.contains("WHERE goal_key=? AND design_key=? AND before_fp=? AND action_type=? AND target=?"));
        assertTrue(src.contains("UPDATE experiences SET failure_count=failure_count+1,last_at=?"));
        assertTrue(src.contains("SELECT failure_count FROM experiences"));
        assertTrue(src.contains("WHERE after_fp<>''"));
    }
}
