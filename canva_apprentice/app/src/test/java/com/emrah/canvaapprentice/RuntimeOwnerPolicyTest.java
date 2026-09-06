package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeOwnerPolicyTest {
    @Test public void capturedOwnerMustBeExactCurrentRuntimeInstance() {
        Object owner = new Object();
        assertTrue(RuntimeOwnerPolicy.isCurrent(owner, owner));
    }

    @Test public void destroyedRuntimeWithNoCurrentOwnerFailsClosed() {
        assertFalse(RuntimeOwnerPolicy.isCurrent(new Object(), null));
    }

    @Test public void recreatedRuntimeRejectsOldOwnerEvenWhenBothExist() {
        Object oldOwner = new Object();
        Object replacementOwner = new Object();
        assertFalse(RuntimeOwnerPolicy.isCurrent(oldOwner, replacementOwner));
    }

    @Test public void nullCapturedOwnerNeverAuthorizesCallback() {
        Object currentOwner = new Object();
        assertFalse(RuntimeOwnerPolicy.isCurrent(null, currentOwner));
    }
}
