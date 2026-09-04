package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.*;

public class TeacherRequestLeasePolicyTest {
    @After public void resetLease() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void visualTeacherRequestPreservesScreenshotOwningLease() {
        String screenshotOwner = TeacherExecutionLease.beginGlobal();
        assertFalse(screenshotOwner.isEmpty());

        String preserved = TeacherRequestLeasePolicy.currentVisualRequestLease();

        assertEquals(screenshotOwner, preserved);
        assertTrue(TeacherExecutionLease.isGlobalCurrent(screenshotOwner));
    }

    @Test public void structuralTeacherRequestSupersedesPreviousActionLease() {
        String oldAction = TeacherExecutionLease.beginGlobal();

        String structural = TeacherRequestLeasePolicy.beginStructuralRequest();

        assertNotEquals(oldAction, structural);
        assertFalse(TeacherExecutionLease.isGlobalCurrent(oldAction));
        assertTrue(TeacherExecutionLease.isGlobalCurrent(structural));
    }

    @Test public void visualTeacherRequestWithoutOwnerFailsClosedAsEmpty() {
        TeacherExecutionLease.invalidateGlobal();
        assertEquals("", TeacherRequestLeasePolicy.currentVisualRequestLease());
    }
}
