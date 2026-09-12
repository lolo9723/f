package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeacherProtocolColumnAuthorityTest {
    @Test public void parserRejectsSpaceIndentedProtocolMarker() {
        String marker = TeacherProtocolTestFixture.groundedMarker("columnSpace1");
        AgentAction action = TeacherProtocol.parse(
                "status\n   " + marker + "CLICK_TEXT|Elements|0.99|rendered quote\nfooter",
                marker
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("unique protocol marker missing", action.reason);
    }

    @Test public void parserRejectsTabIndentedProtocolMarker() {
        String marker = TeacherProtocolTestFixture.groundedMarker("columnTab1");
        AgentAction action = TeacherProtocol.parse(
                "\t" + marker + "CLICK_TEXT|Elements|0.99|rendered quote",
                marker
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("unique protocol marker missing", action.reason);
    }

    @Test public void parserStillAcceptsExactColumnZeroMarker() {
        String marker = TeacherProtocolTestFixture.groundedMarker("columnExact1");
        AgentAction action = TeacherProtocol.parse(
                "status\n" + marker + "CLICK_TEXT|Elements|0.99|exact reply\nfooter",
                marker
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals("Elements", action.target);
        assertEquals(0.99, action.confidence, 0.0001);
    }
}
