package com.emrah.canvaapprentice;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TeacherProtocolPreflightAuthorityTest {
    @Test public void unrelatedReplyDoesNotConsumeCurrentGroundedAuthority() {
        String marker = TeacherProtocolTestFixture.groundedMarker("preflight123");

        AgentAction unrelated = TeacherProtocol.parse(
                "CAA1_REPLY_other|CLICK_TEXT|Share|0.99|wrong request",
                marker
        );
        assertEquals(AgentAction.Type.NOOP, unrelated.type);
        assertEquals("unique protocol marker missing", unrelated.reason);
        assertEquals("", unrelated.executionLeaseToken);

        AgentAction valid = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Elements|0.99|current request",
                marker
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, valid.type);
        assertEquals("Elements", valid.target);
    }

    @Test public void duplicateReplyDoesNotConsumeCurrentGroundedAuthority() {
        String marker = TeacherProtocolTestFixture.groundedMarker("duplicate123");

        AgentAction duplicate = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Elements|0.99|first\n" +
                marker + "CLICK_TEXT|Share|0.99|second",
                marker
        );
        assertEquals(AgentAction.Type.NOOP, duplicate.type);
        assertEquals("ambiguous duplicate protocol marker", duplicate.reason);
        assertEquals("", duplicate.executionLeaseToken);

        AgentAction valid = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Elements|0.99|current request",
                marker
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, valid.type);
        assertEquals("Elements", valid.target);
    }
}
