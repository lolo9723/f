package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeacherExecutionLeaseTest {
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
}
