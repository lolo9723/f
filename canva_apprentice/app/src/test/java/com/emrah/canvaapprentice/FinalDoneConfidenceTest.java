package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class FinalDoneConfidenceTest {
    @Test public void lowConfidenceVisualDoneFailsClosed() {
        String marker = TeacherProtocol.markerFor("final001");
        AgentAction action = TeacherProtocol.parse(
                marker + "DONE|||0.80|looks mostly complete but uncertain",
                marker,
                true
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
        assertTrue(action.visualGrounded);
        assertEquals("final done confidence below safety threshold", action.reason);
    }

    @Test public void malformedVisualDoneFailsClosed() {
        String marker = TeacherProtocol.markerFor("final002");
        AgentAction action = TeacherProtocol.parse(
                marker + "DONE|||NaN|cannot certify final quality",
                marker,
                true
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0001);
    }

    @Test public void highConfidenceVisualDonePreservesReportedConfidence() {
        String marker = TeacherProtocol.markerFor("final003");
        AgentAction action = TeacherProtocol.parse(
                marker + "DONE|||0.999|goal and final visual quality verified",
                marker,
                true
        );
        assertEquals(AgentAction.Type.DONE, action.type);
        assertEquals(0.999, action.confidence, 0.0001);
        assertTrue(action.visualGrounded);
    }
}
