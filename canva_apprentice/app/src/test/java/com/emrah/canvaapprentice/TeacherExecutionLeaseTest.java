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
        assertFalse(lease.isCurrent(""));
        assertFalse(lease.isCurrent(null));
    }

    @Test public void newerActionMakesOlderActionFailClosedAtRuntimeGate() {
        AgentAction older = new AgentAction(AgentAction.Type.CLICK_TEXT,"Old target","",0.99,"older reply");
        assertTrue(DesignContinuityPolicy.allows(older,"",true,false,false));

        AgentAction newer = new AgentAction(AgentAction.Type.CLICK_TEXT,"New target","",0.99,"newer reply");
        assertFalse(DesignContinuityPolicy.allows(older,"",true,false,false));
        assertTrue(DesignContinuityPolicy.allows(newer,"",true,false,false));
    }
}
