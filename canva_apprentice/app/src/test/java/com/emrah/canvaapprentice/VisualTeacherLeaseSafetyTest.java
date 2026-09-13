package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisualTeacherLeaseSafetyTest {
    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void productionVisualMutationRequiresExecutionLease() {
        assertFalse(SafetyGate.visualTeacherLeaseMayExecute(true,true,""));
        assertFalse(SafetyGate.visualTeacherLeaseMayExecute(true,true,null));
    }

    @Test public void productionVisualMutationAcceptsOnlyCurrentVisualRequestLease() {
        String visualLease = TeacherRequestLeasePolicy.beginVisualRequest();
        assertTrue(SafetyGate.visualTeacherLeaseMayExecute(true,true,visualLease));
    }

    @Test public void structuralLeaseCannotBeUpgradedIntoVisualAuthority() {
        String structuralLease = TeacherRequestLeasePolicy.beginStructuralRequest();
        assertTrue(TeacherExecutionLease.isGlobalCurrent(structuralLease));
        assertFalse(SafetyGate.visualTeacherLeaseMayExecute(true,true,structuralLease));
    }

    @Test public void newerStructuralRequestRevokesPreviousVisualAuthority() {
        String visualLease = TeacherRequestLeasePolicy.beginVisualRequest();
        assertTrue(SafetyGate.visualTeacherLeaseMayExecute(true,true,visualLease));

        TeacherRequestLeasePolicy.beginStructuralRequest();
        assertFalse(SafetyGate.visualTeacherLeaseMayExecute(true,true,visualLease));
    }

    @Test public void nonVisualActionDoesNotRequireVisualTeacherLease() {
        assertTrue(SafetyGate.visualTeacherLeaseMayExecute(true,false,""));
    }

    @Test public void jvmPolicyTestsRemainIndependentFromAndroidServiceLifecycle() {
        assertTrue(SafetyGate.visualTeacherLeaseMayExecute(false,true,""));
    }
}
