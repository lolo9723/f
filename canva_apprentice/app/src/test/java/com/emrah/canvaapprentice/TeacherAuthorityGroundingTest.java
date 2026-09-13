package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class TeacherAuthorityGroundingTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void structuralAuthorityCannotAcquireVisualGroundingAtParseBoundary() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("structGround1", "snapshot-S");
        assertTrue(authority.isValid());
        assertFalse(authority.isVisualGrounded());

        AgentAction parsed = TeacherProtocol.parse(
                authority.marker + "TAP_NORM|500,500|0.999|must remain structural",
                authority
        );

        assertEquals(AgentAction.Type.TAP_NORM, parsed.type);
        assertFalse(parsed.visualGrounded);
        assertEquals(authority.executionLeaseToken, parsed.executionLeaseToken);
    }

    @Test public void visualAuthorityCarriesVisualGroundingThroughParserWithoutCallerBoolean() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.beginVisual("visualGround1", "snapshot-V");
        assertTrue(authority.isValid());
        assertTrue(authority.isVisualGrounded());

        AgentAction parsed = TeacherProtocol.parse(
                authority.marker + "TAP_NORM|520,410|0.995|screenshot-grounded target",
                authority
        );

        assertEquals(AgentAction.Type.TAP_NORM, parsed.type);
        assertTrue(parsed.visualGrounded);
        assertEquals(0.995, parsed.confidence, 0.0001);
        assertEquals(authority.executionLeaseToken, parsed.executionLeaseToken);
    }

    @Test public void staleVisualAuthorityFailsClosedWithoutLosingGroundingProvenance() {
        TeacherRequestAuthority stale = TeacherRequestAuthority.beginVisual("visualOld1", "snapshot-old");
        TeacherRequestAuthority current = TeacherRequestAuthority.begin("structNew1", "snapshot-new");
        assertTrue(current.stillOwnsTransport());
        assertFalse(stale.stillOwnsTransport());

        AgentAction parsed = TeacherProtocol.parse(
                stale.marker + "TAP_NORM|520,410|0.999|stale visual reply",
                stale
        );

        assertEquals(AgentAction.Type.NOOP, parsed.type);
        assertTrue(parsed.visualGrounded);
        assertTrue(parsed.executionLeaseToken.isEmpty());
        assertTrue(current.stillOwnsTransport());
    }
}