package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesignAnchorPersistencePolicyTest {
    @Test public void allowsSameRunningTeacherSession() {
        assertTrue(DesignAnchorPersistencePolicy.mayCommit(
                TaskState.Mode.RUNNING,"session-a","session-a","Existing design"));
    }

    @Test public void rejectsTeacherSessionRollover() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(
                TaskState.Mode.RUNNING,"session-a","session-b","Existing design"));
    }

    @Test public void rejectsNonRunningMode() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(
                TaskState.Mode.HUMAN_TAKEOVER,"session-a","session-a","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(
                TaskState.Mode.STOPPED,"session-a","session-a","Existing design"));
    }

    @Test public void rejectsMissingLeaseEvidence() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(
                TaskState.Mode.RUNNING,"","session-a","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(
                TaskState.Mode.RUNNING,"session-a","","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(
                TaskState.Mode.RUNNING,"session-a","session-a","   "));
    }
}
