package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesignAnchorPersistencePolicyTest {
    @Test public void allowsSameRunningTeacherSession() {
        assertTrue(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Existing design"));
    }
    @Test public void rejectsTeacherSessionRollover() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-b","Existing design"));
    }
    @Test public void originatingActionSessionMustMatchObservationAndCommit() {
        assertTrue(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","session-a","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"stale-action","session-a","session-a","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","session-b","Existing design"));
    }
    @Test public void rejectsNonRunningMode() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.HUMAN_TAKEOVER,"session-a","session-a","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.STOPPED,"session-a","session-a","Existing design"));
    }
    @Test public void rejectsMissingLeaseEvidence() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"","session-a","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","","Existing design"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","   "));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"","session-a","session-a","Existing design"));
    }
    @Test public void rejectsUnboundSentinelAndControlCharacters() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","UNBOUND"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a"," unbound "));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\nInjected"));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("", "UNBOUND"));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", "Existing design\tspoof"));
    }
    @Test public void rejectsUnicodeFormatAndSeparatorSpoofing() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\u202Espoof"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\u200Bspoof"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\uFEFFspoof"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\u2028spoof"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\u2029spoof"));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", "Existing\u2066 design\u2069"));
    }
    @Test public void rejectsMalformedSurrogateAnchors() {
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\uD800spoof"));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Design\uDC00spoof"));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", "Existing\uD800design"));
    }
    @Test public void rejectsCanonicallyEquivalentButNonNfcAnchors() {
        String nfc = "Caf\u00e9 Poster";
        String decomposed = "Cafe\u0301 Poster";
        assertTrue(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a",nfc));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a",decomposed));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity(nfc, decomposed));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity(decomposed, nfc));
    }
    @Test public void invalidRestoredRunningAnchorForcesHumanTakeoverAndDropsCheckpoint() {
        TaskState restored = new TaskState("goal","fp","Cafe\u0301 Poster","safe","",TaskState.Mode.RUNNING,false,4);
        assertEquals("", restored.designAnchor);
        assertEquals("", restored.lastSafeSnapshotHash);
        assertEquals(TaskState.Mode.HUMAN_TAKEOVER, restored.mode);
        assertTrue(restored.humanReason.contains("DEVAM ET"));
    }
    @Test public void validRestoredAnchorKeepsRunningAuthority() {
        TaskState restored = new TaskState("goal","fp","Caf\u00e9 Poster","safe","",TaskState.Mode.RUNNING,false,4);
        assertEquals("Caf\u00e9 Poster", restored.designAnchor);
        assertEquals("safe", restored.lastSafeSnapshotHash);
        assertEquals(TaskState.Mode.RUNNING, restored.mode);
    }
    @Test public void allowsFirstBindAndIdempotentRebind() {
        assertTrue(DesignAnchorPersistencePolicy.preservesBoundIdentity("", "Existing design"));
        assertTrue(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", " Existing design "));
    }
    @Test public void rejectsRetargetingAlreadyBoundDesign() {
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", "Different design"));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", "   "));
    }
}
