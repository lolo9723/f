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

    @Test public void adoptsExistingStructuralMarkerWithoutRotatingLease() {
        String marker = TeacherProtocol.markerFor("legacy123");
        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "snapshot-L"));
        String originalLease = CheckpointRequestGuard.currentBoundExecutionLease(marker);

        TeacherRequestAuthority adopted = TeacherRequestAuthority.fromBoundStructural(marker);

        assertTrue(adopted.isValid());
        assertEquals("legacy123", adopted.requestId);
        assertEquals(marker, adopted.marker);
        assertEquals(originalLease, adopted.executionLeaseToken);
        assertEquals("snapshot-L", adopted.snapshotFingerprint);
        assertTrue(adopted.stillOwnsTransport());
        assertEquals(originalLease, CheckpointRequestGuard.currentBoundExecutionLease(marker));
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

        // A different snapshot for the same marker is deliberately rejected and poisons
        // the binding. The assertion must preserve that fail-closed contract instead of
        // expecting an unsafe rebinding to succeed.
        assertFalse(CheckpointRequestGuard.bindSnapshot(authority.marker, "snapshot-B"));
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(authority.executionLeaseToken));

        assertFalse(authority.stillOwnsTransport());
    }

    @Test public void adoptionFailsClosedForUngroundedOrStaleStructuralMarker() {
        String marker = TeacherProtocol.markerFor("ungrounded");
        assertFalse(TeacherRequestAuthority.fromBoundStructural(marker).isValid());

        assertTrue(CheckpointRequestGuard.bindSnapshot(marker, "snapshot-U"));
        CheckpointRequestGuard.onCheckpointCommitted();

        assertFalse(TeacherRequestAuthority.fromBoundStructural(marker).isValid());
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
        assertFalse(TeacherRequestAuthority.fromBoundStructural("bad-marker").isValid());
    }

    @Test public void invalidationRevokesTransportAuthority() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("abc123", "snapshot-A");
        TeacherExecutionLease.invalidateGlobal();

        assertFalse(authority.stillOwnsTransport());
    }
}
