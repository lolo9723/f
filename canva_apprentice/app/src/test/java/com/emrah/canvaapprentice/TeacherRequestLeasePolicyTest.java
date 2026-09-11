package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class TeacherRequestLeasePolicyTest {
    @After public void resetLease() {
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

    @Test public void structuralTransportPreservesMarkerOwningLease() {
        String markerOwner = TeacherRequestLeasePolicy.beginStructuralRequest();

        String preserved = TeacherRequestLeasePolicy.currentStructuralRequestLease();

        assertEquals(markerOwner, preserved);
        assertTrue(TeacherRequestLeasePolicy.transportStillOwns(preserved));
    }

    @Test public void structuralTeacherRequestWithoutMarkerOwnerFailsClosedAsEmpty() {
        TeacherExecutionLease.invalidateGlobal();
        assertEquals("", TeacherRequestLeasePolicy.currentStructuralRequestLease());
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
