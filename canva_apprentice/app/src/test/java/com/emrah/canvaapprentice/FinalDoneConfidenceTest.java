package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class FinalDoneConfidenceTest {
    @Test public void lowConfidenceVisualDoneFailsClosed() {
        AgentAction action = TeacherProtocolTestFixture.parseVisual(
                "final001", "DONE|||0.80|looks mostly complete but uncertain");
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertTrue(action.visualGrounded);
        assertEquals("final done confidence below safety threshold", action.reason);
    }

    @Test public void malformedVisualDoneFailsClosed() {
        AgentAction action = TeacherProtocolTestFixture.parseVisual(
                "final002", "DONE|||NaN|cannot certify final quality");
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertTrue(action.visualGrounded);
    }

    @Test public void highConfidenceVisualDonePreservesReportedConfidence() {
        AgentAction action = TeacherProtocolTestFixture.parseVisual(
                "final003", "DONE|||0.999|goal and final visual quality verified");
        assertEquals(AgentAction.Type.DONE, action.type);
        assertEquals(0.999, action.confidence, 0.0001);
        assertTrue(action.visualGrounded);
    }
}
