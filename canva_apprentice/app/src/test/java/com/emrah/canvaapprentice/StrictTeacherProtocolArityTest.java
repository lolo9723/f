package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class StrictTeacherProtocolArityTest {
    @Test public void rejectsExtraUnescapedFieldOnClickText() {
        String marker = TeacherProtocolTestFixture.groundedMarker("arity01");
        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Elements|0.99|open elements|unexpected extra field",
                marker
        );

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("teacher protocol field count mismatch", action.reason);
    }

    @Test public void rejectsExtraUnescapedFieldOnExactNodeMutation() {
        String marker = TeacherProtocolTestFixture.groundedMarker("arity02");
        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_NODE|17|Elements|android.view.View|0 0 100 100|C-|0.999|reason|extra",
                marker
        );

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("teacher protocol field count mismatch", action.reason);
    }

    @Test public void escapedPipeRemainsValidSingleField() {
        String marker = TeacherProtocolTestFixture.groundedMarker("arity03");
        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Elements|0.99|open elements\\|from sidebar",
                marker
        );

        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals("open elements|from sidebar", action.reason);
        assertEquals(0.99, action.confidence, 0.0001);
    }
}
