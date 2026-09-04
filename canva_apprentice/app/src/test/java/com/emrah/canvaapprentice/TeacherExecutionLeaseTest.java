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
}
