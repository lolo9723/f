package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class TeacherSnapshotAuthorityCanonicalizationTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void surroundingSnapshotWhitespaceFailsBeforeLeaseRotation() {
        TeacherRequestAuthority current = TeacherRequestAuthority.begin("current", "snapshot-current");
        assertTrue(current.isValid());
        String currentLease = current.executionLeaseToken;

        assertFalse(TeacherRequestAuthority.begin("bad-leading", " snapshot-next").isValid());
        assertFalse(TeacherRequestAuthority.beginVisual("bad-trailing", "snapshot-next ").isValid());
        assertFalse(TeacherRequestAuthority.begin("bad-tab", "\tsnapshot-next").isValid());

        assertTrue(current.stillOwnsTransport());
        assertTrue(TeacherExecutionLease.isGlobalCurrent(currentLease));
    }

    @Test public void canonicalSnapshotRemainsExactAcrossAuthorityAndGuard() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("exact", "snapshot-exact");
        assertTrue(authority.isValid());
        assertEquals("snapshot-exact", authority.snapshotFingerprint);

        CheckpointRequestGuard.RequestLease bound =
                CheckpointRequestGuard.currentBoundRequestLease(authority.marker);
        assertTrue(bound.checkpointCurrent);
        assertEquals("snapshot-exact", bound.snapshotFingerprint);
        assertTrue(authority.stillOwnsTransport());
    }
}
