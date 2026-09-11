package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class TeacherAuthorityTransportRotationTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void newerTeacherRequestRevokesOlderImmutableTransportAuthority() {
        TeacherRequestAuthority older = TeacherRequestAuthority.begin("older", "snapshot-old");
        assertTrue(older.isValid());
        assertTrue(older.stillOwnsTransport());

        TeacherRequestAuthority newer = TeacherRequestAuthority.begin("newer", "snapshot-new");
        assertTrue(newer.isValid());
        assertTrue(newer.stillOwnsTransport());

        assertFalse("older transport must not survive execution-lease rotation",
                older.stillOwnsTransport());
    }

    @Test public void invalidatedGlobalLeaseRevokesOtherwiseIntactCheckpointTuple() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("invalidate", "snapshot-A");
        assertTrue(authority.isValid());
        assertTrue(authority.stillOwnsTransport());

        CheckpointRequestGuard.RequestLease before =
                CheckpointRequestGuard.currentBoundRequestLease(authority.marker);
        assertTrue(before.checkpointCurrent);

        TeacherExecutionLease.invalidateGlobal();

        CheckpointRequestGuard.RequestLease after =
                CheckpointRequestGuard.currentBoundRequestLease(authority.marker);
        assertTrue("checkpoint binding intentionally remains recorded", after.checkpointCurrent);
        assertFalse("recorded tuple alone must never authorize transport",
                authority.stillOwnsTransport());
    }
}
