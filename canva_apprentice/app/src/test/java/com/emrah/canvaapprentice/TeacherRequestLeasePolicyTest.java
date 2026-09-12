package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class TeacherRequestLeasePolicyTest {
    @Before public void resetCheckpoint() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @After public void resetLease() {
        CheckpointRequestGuard.resetForTest();
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void visualTeacherRequestGetsFreshLeaseAndRevokesStructuralOwner() {
        String structuralOwner = TeacherExecutionLease.beginGlobal();

        String visualOwner = TeacherRequestLeasePolicy.beginVisualRequest();

        assertFalse(visualOwner.isEmpty());
        assertNotEquals(structuralOwner, visualOwner);
        assertFalse(TeacherExecutionLease.isGlobalCurrent(structuralOwner));
        assertTrue(TeacherExecutionLease.isGlobalCurrent(visualOwner));
    }

    @Test public void structuralTeacherRequestSupersedesPreviousActionLease() {
        String oldAction = TeacherExecutionLease.beginGlobal();

        String structural = TeacherRequestLeasePolicy.beginStructuralRequest();

        assertNotEquals(oldAction, structural);
        assertFalse(TeacherExecutionLease.isGlobalCurrent(oldAction));
        assertTrue(TeacherExecutionLease.isGlobalCurrent(structural));
    }

    @Test public void structuralTransportReadsOnlyExactMarkerOwningLease() {
        String marker = "CAA1_REPLY_markerOwner|";
        String markerOwner = TeacherRequestLeasePolicy.beginStructuralRequest();
        assertTrue(CheckpointRequestGuard.bindFullyGrounded(marker, markerOwner, "snapshot-owner"));

        String preserved = TeacherRequestLeasePolicy.currentStructuralRequestLease(marker);

        assertEquals(markerOwner, preserved);
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(preserved));
    }

    @Test public void unboundMarkerCannotBorrowCurrentGlobalLease() {
        String globalOwner = TeacherRequestLeasePolicy.beginStructuralRequest();

        assertEquals("", TeacherRequestLeasePolicy.currentStructuralRequestLease("CAA1_REPLY_unbound|"));
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(globalOwner));
    }

    @Test public void staleMarkerCannotBorrowNewerRequestLease() {
        String staleMarker = "CAA1_REPLY_stale|";
        String staleOwner = TeacherRequestLeasePolicy.beginStructuralRequest();
        assertTrue(CheckpointRequestGuard.bindFullyGrounded(staleMarker, staleOwner, "snapshot-stale"));

        String newerMarker = "CAA1_REPLY_newer|";
        String newerOwner = TeacherRequestLeasePolicy.beginStructuralRequest();
        assertTrue(CheckpointRequestGuard.bindFullyGrounded(newerMarker, newerOwner, "snapshot-newer"));

        assertEquals(staleOwner, TeacherRequestLeasePolicy.currentStructuralRequestLease(staleMarker));
        assertFalse(TeacherRequestLeasePolicy.transportStillOwns(
                TeacherRequestLeasePolicy.currentStructuralRequestLease(staleMarker)));
        assertEquals(newerOwner, TeacherRequestLeasePolicy.currentStructuralRequestLease(newerMarker));
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(
                TeacherRequestLeasePolicy.currentStructuralRequestLease(newerMarker)));
    }

    @Test public void structuralTeacherRequestWithoutMarkerOwnerFailsClosedAsEmpty() {
        TeacherExecutionLease.invalidateGlobal();
        assertEquals("", TeacherRequestLeasePolicy.currentStructuralRequestLease("CAA1_REPLY_none|"));
        assertEquals("", TeacherRequestLeasePolicy.currentStructuralRequestLease(""));
        assertEquals("", TeacherRequestLeasePolicy.currentStructuralRequestLease(null));
    }

    @Test public void delayedTransportCannotBorrowNewerLease() {
        String oldRequest = TeacherExecutionLease.beginGlobal();
        String newerRequest = TeacherExecutionLease.beginGlobal();

        assertFalse(TeacherRequestLeasePolicy.transportStillOwns(oldRequest));
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(newerRequest));
    }

    @Test public void invalidatedTransportFailsClosed() {
        String request = TeacherExecutionLease.beginGlobal();
        TeacherExecutionLease.invalidateGlobal();

        assertFalse(TeacherRequestLeasePolicy.transportStillOwns(request));
        assertFalse(TeacherRequestLeasePolicy.transportStillOwns(""));
        assertFalse(TeacherRequestLeasePolicy.transportStillOwns(null));
    }
}
