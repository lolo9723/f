package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeacherProtocolColumnAuthorityTest {
    @Test public void parserRejectsSpaceIndentedProtocolMarker() {
        TeacherRequestAuthority authority = TeacherProtocolTestFixture.groundedAuthority("columnSpace1");
        AgentAction action = TeacherProtocol.parse(
                "status\n   " + authority.marker + "CLICK_TEXT|Elements|0.99|rendered quote\nfooter",
                authority
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("unique protocol marker missing", action.reason);
    }

    @Test public void parserRejectsTabIndentedProtocolMarker() {
        TeacherRequestAuthority authority = TeacherProtocolTestFixture.groundedAuthority("columnTab1");
        AgentAction action = TeacherProtocol.parse(
                "\t" + authority.marker + "CLICK_TEXT|Elements|0.99|rendered quote",
                authority
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("unique protocol marker missing", action.reason);
    }

    @Test public void parserStillAcceptsExactColumnZeroMarker() {
        TeacherRequestAuthority authority = TeacherProtocolTestFixture.groundedAuthority("columnExact1");
        AgentAction action = TeacherProtocol.parse(
                "status\n" + authority.marker + "CLICK_TEXT|Elements|0.99|exact reply\nfooter",
                authority
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals("Elements", action.target);
        assertEquals(0.99, action.confidence, 0.0001);
    }
}
