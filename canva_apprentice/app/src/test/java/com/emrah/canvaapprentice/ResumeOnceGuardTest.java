package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResumeOnceGuardTest {
    @Test public void onlyFirstResumeCallbackOwnsTransition() {
        ResumeOnceGuard guard = new ResumeOnceGuard();
        assertTrue(guard.tryConsume());
        assertFalse(guard.tryConsume());
        assertFalse(guard.tryConsume());
    }

    @Test public void eachOverlayShowGetsIndependentGuard() {
        ResumeOnceGuard first = new ResumeOnceGuard();
        ResumeOnceGuard second = new ResumeOnceGuard();
        assertTrue(first.tryConsume());
        assertFalse(first.tryConsume());
        assertTrue(second.tryConsume());
    }
}
