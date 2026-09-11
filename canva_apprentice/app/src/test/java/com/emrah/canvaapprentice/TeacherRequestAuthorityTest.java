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
    }

    @Test public void invalidationRevokesTransportAuthority() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("abc123", "snapshot-A");
        TeacherExecutionLease.invalidateGlobal();

        assertFalse(authority.stillOwnsTransport());
    }
}
