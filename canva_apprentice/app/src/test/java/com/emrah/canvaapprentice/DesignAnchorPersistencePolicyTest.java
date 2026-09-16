package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesignAnchorPersistencePolicyTest {
    @Test public void allowsSameRunningTeacherSession() { assertTrue(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Existing design")); }
    @Test public void rejectsTeacherSessionRollover() { assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-b","Existing design")); }
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
    @Test public void rejectsUndefinedUnicodeDesignIdentities() {
        String undefined = "Poster " + new String(Character.toChars(0x0378)) + " identity";
        assertEquals(Character.UNASSIGNED, Character.getType(0x0378));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a",undefined));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("", undefined));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity(undefined, undefined));
    }
    @Test public void rejectsCanonicallyEquivalentButNonNfcAnchors() {
        String nfc = "Caf\u00e9 Poster";
        String decomposed = "Cafe\u0301 Poster";
        assertTrue(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a",nfc));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a",decomposed));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity(nfc, decomposed));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity(decomposed, nfc));
    }
    @Test public void rejectsOversizedAndPrivateUseDesignIdentities() {
        StringBuilder oversized = new StringBuilder(); for (int i = 0; i < 513; i++) oversized.append('A');
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a",oversized.toString()));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("", oversized.toString()));
        assertFalse(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a","Poster \uE000 identity"));
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Poster \uE000 identity", "Poster \uE000 identity"));
    }
    @Test public void acceptsLargeButBoundedUnicodeIdentity() {
        StringBuilder bounded = new StringBuilder(); for (int i = 0; i < 511; i++) bounded.append('A'); bounded.appendCodePoint(0x1F3A8);
        assertEquals(512, bounded.codePointCount(0, bounded.length()));
        assertTrue(DesignAnchorPersistencePolicy.mayCommit(TaskState.Mode.RUNNING,"session-a","session-a",bounded.toString()));
    }
    @Test public void invalidAnchorIsQuarantinedWithoutReenteringHumanTakeoverAfterExplicitResume() {
        TaskState resumed = new TaskState("goal","fp","Cafe\u0301 Poster","safe","",TaskState.Mode.RUNNING,false,4);
        assertEquals("", resumed.designAnchor); assertEquals("", resumed.lastSafeSnapshotHash); assertEquals(TaskState.Mode.RUNNING, resumed.mode); assertEquals("", resumed.humanReason);
    }
    @Test public void invalidAnchorStaysQuarantinedDuringHumanTakeover() {
        TaskState waiting = new TaskState("goal","fp","Cafe\u0301 Poster","safe","verify",TaskState.Mode.HUMAN_TAKEOVER,false,4);
        assertEquals("", waiting.designAnchor); assertEquals("", waiting.lastSafeSnapshotHash); assertEquals(TaskState.Mode.HUMAN_TAKEOVER, waiting.mode); assertEquals("verify", waiting.humanReason);
    }
    @Test public void validRestoredAnchorKeepsRunningAuthority() {
        TaskState restored = new TaskState("goal","fp","Caf\u00e9 Poster","safe","",TaskState.Mode.RUNNING,false,4);
        assertEquals("Caf\u00e9 Poster", restored.designAnchor); assertEquals("safe", restored.lastSafeSnapshotHash); assertEquals(TaskState.Mode.RUNNING, restored.mode);
    }
    @Test public void allowsFirstBindAndIdempotentRebind() {
        assertTrue(DesignAnchorPersistencePolicy.preservesBoundIdentity("", "Existing design")); assertTrue(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", " Existing design "));
    }
    @Test public void rejectsRetargetingAlreadyBoundDesign() {
        assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", "Different design")); assertFalse(DesignAnchorPersistencePolicy.preservesBoundIdentity("Existing design", "   "));
    }
}
