package com.emrah.canvaapprentice;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class TeacherExecutionLeaseTest {
    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
    }

    @Test public void newerLeaseInvalidatesOlderAcceptedReply() {
        TeacherExecutionLease lease = new TeacherExecutionLease();
        String first = lease.begin();
        assertTrue(lease.isCurrent(first));

        String second = lease.begin();
        assertFalse(lease.isCurrent(first));
        assertTrue(lease.isCurrent(second));
    }

    @Test public void staleCompletionCannotClearNewerLease() {
        TeacherExecutionLease lease = new TeacherExecutionLease();
        String first = lease.begin();
        String second = lease.begin();

        assertFalse(lease.completeIfCurrent(first));
        assertTrue(lease.isCurrent(second));
        assertTrue(lease.completeIfCurrent(second));
        assertFalse(lease.isCurrent(second));
    }

    @Test public void explicitInvalidationFailsClosed() {
        TeacherExecutionLease lease = new TeacherExecutionLease();
        String token = lease.begin();
        lease.invalidate();

        assertFalse(lease.isCurrent(token));
        assertFalse(lease.isCurrent("") );
        assertFalse(lease.isCurrent(null));
    }

    @Test public void newerTeacherRequestMakesOlderActionFailClosedAtRuntimeGate() {
        TeacherExecutionLease.beginGlobal();
        AgentAction older = new AgentAction(AgentAction.Type.CLICK_TEXT,"Old target","",0.99,"older reply");
        assertTrue(DesignContinuityPolicy.allows(older,"",true,false,false));

        TeacherExecutionLease.beginGlobal();
        AgentAction newer = new AgentAction(AgentAction.Type.CLICK_TEXT,"New target","",0.99,"newer reply");
        assertFalse(DesignContinuityPolicy.allows(older,"",true,false,false));
        assertTrue(DesignContinuityPolicy.allows(newer,"",true,false,false));
    }

    @Test public void constructingCandidateActionsDoesNotRotateCurrentLease() {
        String token = TeacherExecutionLease.beginGlobal();
        AgentAction first = new AgentAction(AgentAction.Type.CLICK_TEXT,"A","",0.99,"candidate A");
        AgentAction second = new AgentAction(AgentAction.Type.CLICK_TEXT,"B","",0.99,"candidate B");

        assertEquals(token, first.executionLeaseToken);
        assertEquals(token, second.executionLeaseToken);
        assertTrue(TeacherExecutionLease.isGlobalCurrent(first.executionLeaseToken));
        assertTrue(TeacherExecutionLease.isGlobalCurrent(second.executionLeaseToken));
    }

    @Test public void globalGuardRunsMutationOnlyForCurrentToken() {
        String oldToken = TeacherExecutionLease.beginGlobal();
        String currentToken = TeacherExecutionLease.beginGlobal();

        assertEquals("stale", TeacherExecutionLease.withGlobalCurrent(oldToken, "stale", () -> "mutated"));
        assertEquals("mutated", TeacherExecutionLease.withGlobalCurrent(currentToken, "stale", () -> "mutated"));
    }

    @Test public void checkedGlobalGuardSerializesLeaseRotationAcrossProtectedIo() throws Exception {
        String token = TeacherExecutionLease.beginGlobal();
        CountDownLatch rotationAttempted = new CountDownLatch(1);
        AtomicBoolean rotationFinished = new AtomicBoolean(false);
        AtomicReference<String> nextToken = new AtomicReference<>("");
        AtomicReference<Thread> workerRef = new AtomicReference<>();

        String result = TeacherExecutionLease.withGlobalCurrentChecked(token, "stale", () -> {
            Thread worker = new Thread(() -> {
                rotationAttempted.countDown();
                nextToken.set(TeacherExecutionLease.beginGlobal());
                rotationFinished.set(true);
            });
            workerRef.set(worker);
            worker.start();
            assertTrue(rotationAttempted.await(1, TimeUnit.SECONDS));
            assertFalse("lease rotation must block while protected I/O owns the monitor", rotationFinished.get());
            assertTrue(TeacherExecutionLease.isGlobalCurrent(token));
            return "opened";
        });

        assertEquals("opened", result);
        Thread worker = workerRef.get();
        assertNotNull(worker);
        worker.join(1000L);
        assertFalse("rotation thread should finish after protected I/O releases the monitor", worker.isAlive());
        assertTrue(rotationFinished.get());
        assertFalse(nextToken.get().isEmpty());
        assertFalse(TeacherExecutionLease.isGlobalCurrent(token));
        assertTrue(TeacherExecutionLease.isGlobalCurrent(nextToken.get()));
    }

    @Test public void checkedGlobalGuardFailsClosedForStaleToken() throws Exception {
        String stale = TeacherExecutionLease.beginGlobal();
        TeacherExecutionLease.beginGlobal();
        AtomicBoolean ran = new AtomicBoolean(false);

        String result = TeacherExecutionLease.withGlobalCurrentChecked(stale, "stale", () -> {
            ran.set(true);
            return "opened";
        });

        assertEquals("stale", result);
        assertFalse(ran.get());
    }
}
