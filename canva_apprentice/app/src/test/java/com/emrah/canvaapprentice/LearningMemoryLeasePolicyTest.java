package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Test;

public final class LearningMemoryLeasePolicyTest {
    @After public void cleanup() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void currentTeacherActionMayRecord() {
        String token = TeacherExecutionLease.beginGlobal();
        AgentAction action = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "test", false, token);

        assertTrue(LearningMemoryLeasePolicy.canRecord(action));
    }

    @Test public void staleTeacherActionCannotRecordAfterNewRequest() {
        String oldToken = TeacherExecutionLease.beginGlobal();
        AgentAction stale = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "old", false, oldToken);

        TeacherExecutionLease.beginGlobal();

        assertFalse(LearningMemoryLeasePolicy.canRecord(stale));
    }

    @Test public void invalidatedTeacherActionCannotRecord() {
        String token = TeacherExecutionLease.beginGlobal();
        AgentAction action = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "old", false, token);

        TeacherExecutionLease.invalidateGlobal();

        assertFalse(LearningMemoryLeasePolicy.canRecord(action));
    }

    @Test public void nullActionCannotRecord() {
        assertFalse(LearningMemoryLeasePolicy.canRecord(null));
    }

    @Test public void unleasedActionCannotContaminatePersistentMemory() {
        AgentAction malformed = new AgentAction(
                AgentAction.Type.BACK, "", "", 0.99, "missing provenance", false, "");

        assertFalse(LearningMemoryLeasePolicy.canRecord(malformed));
    }

    @Test public void emptyLeaseCannotPiggybackOnCurrentTeacherRequest() {
        TeacherExecutionLease.beginGlobal();
        AgentAction malformed = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "missing provenance", false, "");

        assertFalse(LearningMemoryLeasePolicy.canRecord(malformed));
    }

    @Test public void staleLeaseDoesNotRunAtomicMemoryMutation() {
        String oldToken = TeacherExecutionLease.beginGlobal();
        AgentAction stale = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "old", false, oldToken);
        TeacherExecutionLease.beginGlobal();
        AtomicBoolean ran = new AtomicBoolean(false);

        boolean committed = LearningMemoryLeasePolicy.withCurrentLease(stale, false, () -> {
            ran.set(true);
            return true;
        });

        assertFalse(committed);
        assertFalse(ran.get());
    }

    @Test public void leaseCannotRotateFromAnotherThreadDuringMemoryMutation() throws Exception {
        String token = TeacherExecutionLease.beginGlobal();
        AgentAction current = new AgentAction(
                AgentAction.Type.CLICK_TEXT, "Open", "", 0.99, "current", false, token);
        CountDownLatch insideMutation = new CountDownLatch(1);
        CountDownLatch releaseMutation = new CountDownLatch(1);
        AtomicBoolean rotationFinished = new AtomicBoolean(false);

        Thread rotator = new Thread(() -> {
            try {
                assertTrue(insideMutation.await(2, TimeUnit.SECONDS));
                TeacherExecutionLease.beginGlobal();
                rotationFinished.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        rotator.start();

        boolean committed = LearningMemoryLeasePolicy.withCurrentLease(current, false, () -> {
            insideMutation.countDown();
            try {
                assertFalse(rotationFinished.get());
                assertTrue(releaseMutation.await(100, TimeUnit.MILLISECONDS) == false);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            assertTrue(TeacherExecutionLease.isGlobalCurrent(token));
            return true;
        });
        releaseMutation.countDown();
        rotator.join(2000);

        assertTrue(committed);
        assertTrue(rotationFinished.get());
    }
}
