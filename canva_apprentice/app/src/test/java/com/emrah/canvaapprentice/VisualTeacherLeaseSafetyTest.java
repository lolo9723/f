package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class VisualTeacherLeaseSafetyTest {
    @Test public void productionVisualMutationRequiresExecutionLease() {
        assertFalse(SafetyGate.visualTeacherLeaseMayExecute(true,true,""));
        assertFalse(SafetyGate.visualTeacherLeaseMayExecute(true,true,null));
    }

    @Test public void productionVisualMutationAcceptsNonEmptyLeaseAtThisBoundary() {
        assertTrue(SafetyGate.visualTeacherLeaseMayExecute(true,true,"lease-123"));
    }

    @Test public void nonVisualActionDoesNotRequireVisualTeacherLease() {
        assertTrue(SafetyGate.visualTeacherLeaseMayExecute(true,false,""));
    }

    @Test public void jvmPolicyTestsRemainIndependentFromAndroidServiceLifecycle() {
        assertTrue(SafetyGate.visualTeacherLeaseMayExecute(false,true,""));
    }
}
