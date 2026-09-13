package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class StrictTeacherProtocolArityTest {
    @Test public void rejectsExtraUnescapedFieldOnClickText() {
        AgentAction action = TeacherProtocolTestFixture.parseStructural(
                "arity01", "CLICK_TEXT|Elements|0.99|open elements|unexpected extra field");

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("teacher protocol field count mismatch", action.reason);
    }

    @Test public void rejectsExtraUnescapedFieldOnExactNodeMutation() {
        AgentAction action = TeacherProtocolTestFixture.parseStructural(
                "arity02", "CLICK_NODE|17|Elements|android.view.View|0 0 100 100|C-|0.999|reason|extra");

        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertEquals("teacher protocol field count mismatch", action.reason);
    }

    @Test public void escapedPipeRemainsValidSingleField() {
        AgentAction action = TeacherProtocolTestFixture.parseStructural(
                "arity03", "CLICK_TEXT|Elements|0.99|open elements\\|from sidebar");

        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals("open elements|from sidebar", action.reason);
        assertEquals(0.99, action.confidence, 0.0001);
    }
}
