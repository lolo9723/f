package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class TeacherRequestAuthorityCanonicalFingerprintTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void nbspFingerprintFailsClosedWithoutRotatingCurrentLease() {
        String current = TeacherExecutionLease.beginGlobal();

        TeacherRequestAuthority authority = TeacherRequestAuthority.begin(
                "nbspFingerprint",
                "snapshot\u00a0fingerprint");

        assertFalse(authority.isValid());
        assertEquals(current, TeacherExecutionLease.currentGlobalToken());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(current));
    }

    @Test public void zeroWidthFormatFingerprintFailsClosedWithoutRotatingCurrentLease() {
        String current = TeacherExecutionLease.beginGlobal();

        TeacherRequestAuthority authority = TeacherRequestAuthority.beginVisual(
                "zeroWidthFingerprint",
                "snapshot\u200bfingerprint");

        assertFalse(authority.isValid());
        assertEquals(current, TeacherExecutionLease.currentGlobalToken());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(current));
    }

    @Test public void ordinaryOpaqueFingerprintRemainsAccepted() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin(
                "ordinaryFingerprint",
                "snapshot-A");

        assertTrue(authority.isValid());
        assertTrue(authority.stillOwnsTransport());
        assertEquals("snapshot-A", authority.snapshotFingerprint);
    }
}
