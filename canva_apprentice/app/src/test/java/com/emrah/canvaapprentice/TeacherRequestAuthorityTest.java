package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class TeacherRequestAuthorityTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void beginAtomicallyBindsMarkerLeaseAndSnapshot() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("abc123", "snapshot-A");

        assertTrue(authority.isValid());
        assertEquals("abc123", authority.requestId);
        assertEquals("CAA1_REPLY_abc123|", authority.marker);
        assertEquals("snapshot-A", authority.snapshotFingerprint);
        assertTrue(authority.stillOwnsTransport());

        CheckpointRequestGuard.RequestLease consumed = CheckpointRequestGuard.consume(authority.marker);
        assertTrue(consumed.checkpointCurrent);
        assertEquals(authority.executionLeaseToken, consumed.executionLeaseToken);
        assertEquals("snapshot-A", consumed.snapshotFingerprint);
    }

    @Test public void legacyStructuralMarkerCannotReconstructAuthorityEvenWhenFullyGrounded() {
        String marker = TeacherProtocol.markerFor("legacy123");
        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "snapshot-L"));
        String originalLease = CheckpointRequestGuard.currentBoundExecutionLease(marker);
        assertFalse(originalLease.isEmpty());

        assertLegacyReconstructionRemoved();

        assertEquals(originalLease, CheckpointRequestGuard.currentBoundExecutionLease(marker));
        assertTrue(TeacherExecutionLease.isGlobalCurrent(originalLease));
    }

    @Test public void structuralAuthorityStopsOwningTransportAfterBindingIsConsumed() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("consume1", "snapshot-C");
        assertTrue(authority.stillOwnsTransport());

        CheckpointRequestGuard.RequestLease consumed = CheckpointRequestGuard.consume(authority.marker);
        assertTrue(consumed.checkpointCurrent);
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(authority.executionLeaseToken));

        assertFalse(authority.stillOwnsTransport());
    }

    @Test public void structuralAuthorityRejectsSnapshotRebindingEvenWithSameExecutionLease() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("rebind1", "snapshot-A");
        assertTrue(authority.stillOwnsTransport());

        assertFalse(CheckpointRequestGuard.bindSnapshot(authority.marker, "snapshot-B"));
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(authority.executionLeaseToken));

        assertFalse(authority.stillOwnsTransport());
    }

    @Test public void legacyReconstructionSurfaceStaysAbsentForUngroundedAndStaleMarkers() {
        String marker = TeacherProtocol.markerFor("ungrounded");
        assertLegacyReconstructionRemoved();

        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "snapshot-U"));
        CheckpointRequestGuard.onCheckpointCommitted();

        assertLegacyReconstructionRemoved();
    }

    @Test public void visualAuthorityOwnsFreshLeaseAndExactSnapshot() {
        String structural = TeacherExecutionLease.beginGlobal();

        TeacherRequestAuthority visual = TeacherRequestAuthority.beginVisual("visual1", "snapshot-V");

        assertTrue(visual.isValid());
        assertEquals("CAA1_REPLY_visual1|", visual.marker);
        assertEquals("snapshot-V", visual.snapshotFingerprint);
        assertNotEquals(structural, visual.executionLeaseToken);
        assertFalse(TeacherExecutionLease.isGlobalCurrent(structural));
        assertTrue(visual.stillOwnsTransport());
    }

    @Test public void visualReplyConsumesItsOwnBoundLeaseAndSnapshot() {
        TeacherRequestAuthority visual = TeacherRequestAuthority.beginVisual("visualreply", "snapshot-VR");
        assertTrue(visual.stillOwnsTransport());

        AgentAction parsed = TeacherProtocol.parse(
                visual.marker + "NOOP|||1.0|visual target unclear",
                visual.marker,
                true
        );

        assertEquals(AgentAction.Type.NOOP, parsed.type);
        assertTrue(parsed.visualGrounded);
        assertEquals(visual.executionLeaseToken, parsed.executionLeaseToken);
        assertFalse(parsed.executionLeaseToken.isEmpty());
        assertFalse(visual.stillOwnsTransport());
        assertTrue(CheckpointRequestGuard.consumeExecutionSnapshotIfMatches(
                parsed.executionLeaseToken,
                "snapshot-VR"
        ));
    }

    @Test public void newerVisualAuthorityImmediatelyRevokesOlderVisualTransport() {
        TeacherRequestAuthority oldVisual = TeacherRequestAuthority.beginVisual("oldv", "snapshot-old");
        TeacherRequestAuthority newVisual = TeacherRequestAuthority.beginVisual("newv", "snapshot-new");

        assertFalse(oldVisual.stillOwnsTransport());
        assertTrue(newVisual.stillOwnsTransport());
    }

    @Test public void newerAuthorityImmediatelyRevokesOlderTransport() {
        TeacherRequestAuthority oldAuthority = TeacherRequestAuthority.begin("old", "snapshot-old");
        TeacherRequestAuthority newAuthority = TeacherRequestAuthority.begin("new", "snapshot-new");

        assertTrue(newAuthority.stillOwnsTransport());
        assertFalse(oldAuthority.stillOwnsTransport());
    }

    @Test public void invalidInputsFailClosedWithoutUsableAuthority() {
        assertFalse(TeacherRequestAuthority.begin("", "snapshot").isValid());
        assertFalse(TeacherRequestAuthority.begin("request", "").isValid());
        assertFalse(TeacherRequestAuthority.begin(null, "snapshot").isValid());
        assertFalse(TeacherRequestAuthority.begin("request", null).isValid());
        assertFalse(TeacherRequestAuthority.beginVisual("", "snapshot").isValid());
        assertFalse(TeacherRequestAuthority.beginVisual("request", "").isValid());
        assertLegacyReconstructionRemoved();
    }

    @Test public void unsafeRequestIdsCannotInjectProtocolMarkersOrRotateExistingAuthority() {
        TeacherRequestAuthority current = TeacherRequestAuthority.begin("safe_123", "snapshot-safe");
        assertTrue(current.isValid());
        assertTrue(current.stillOwnsTransport());
        String lease = current.executionLeaseToken;

        assertFalse(TeacherRequestAuthority.begin("evil|NOOP", "snapshot-evil").isValid());
        assertFalse(TeacherRequestAuthority.begin("evil\nDONE", "snapshot-evil").isValid());
        assertFalse(TeacherRequestAuthority.beginVisual("evil request", "snapshot-evil").isValid());
        assertLegacyReconstructionRemoved();

        assertEquals(lease, current.executionLeaseToken);
        assertTrue(current.stillOwnsTransport());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(lease));
    }

    @Test public void surroundingWhitespaceCannotBeCanonicalizedIntoTeacherAuthority() {
        TeacherRequestAuthority current = TeacherRequestAuthority.begin("safe-before", "snapshot-safe");
        assertTrue(current.stillOwnsTransport());
        String lease = current.executionLeaseToken;

        assertFalse(TeacherRequestAuthority.begin(" safe", "snapshot-leading").isValid());
        assertFalse(TeacherRequestAuthority.begin("safe ", "snapshot-trailing").isValid());
        assertFalse(TeacherRequestAuthority.beginVisual("\tsafe", "snapshot-tab").isValid());
        assertLegacyReconstructionRemoved();

        assertTrue(current.stillOwnsTransport());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(lease));
    }

    @Test public void embeddedWhitespaceOrControlCannotBecomeSnapshotAuthorityOrRotateLease() {
        TeacherRequestAuthority current = TeacherRequestAuthority.begin("safe-before", "snapshot-safe");
        assertTrue(current.stillOwnsTransport());
        String lease = current.executionLeaseToken;

        assertFalse(TeacherRequestAuthority.begin("bad-snapshot-1", "snapshot\nother").isValid());
        assertFalse(TeacherRequestAuthority.beginVisual("bad-snapshot-2", "snapshot\tother").isValid());
        assertFalse(TeacherRequestAuthority.begin("bad-snapshot-3", "snapshot\u0000other").isValid());

        assertTrue(current.stillOwnsTransport());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(lease));
    }

    @Test public void requestIdLengthIsBoundedBeforeAnyExecutionLeaseRotation() {
        TeacherRequestAuthority current = TeacherRequestAuthority.begin("safe-before", "snapshot-safe");
        assertTrue(current.stillOwnsTransport());
        String lease = current.executionLeaseToken;
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 65; i++) tooLong.append('a');

        assertFalse(TeacherRequestAuthority.begin(tooLong.toString(), "snapshot-long").isValid());
        assertTrue(current.stillOwnsTransport());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(lease));
    }

    @Test public void snapshotFingerprintLengthIsBoundedBeforeAnyExecutionLeaseRotation() {
        TeacherRequestAuthority current = TeacherRequestAuthority.begin("safe-before", "snapshot-safe");
        assertTrue(current.stillOwnsTransport());
        String lease = current.executionLeaseToken;
        StringBuilder tooLong = new StringBuilder();
        for (int i = 0; i < 257; i++) tooLong.append('a');

        assertFalse(TeacherRequestAuthority.begin("oversized-structural", tooLong.toString()).isValid());
        assertFalse(TeacherRequestAuthority.beginVisual("oversized-visual", tooLong.toString()).isValid());
        assertTrue(current.stillOwnsTransport());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(lease));
    }

    @Test public void invalidationRevokesTransportAuthority() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("abc123", "snapshot-A");
        TeacherExecutionLease.invalidateGlobal();

        assertFalse(authority.stillOwnsTransport());
    }

    private static void assertLegacyReconstructionRemoved() {
        try {
            TeacherRequestAuthority.class.getDeclaredMethod("fromBoundStructural", String.class);
            fail("legacy marker-based authority reconstruction surface must stay removed");
        } catch (NoSuchMethodException expected) {
            // No marker-only path may synthesize immutable teacher authority.
        }
    }
}
