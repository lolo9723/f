package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TeacherConfidenceRangeTest {
    @Test public void aboveOneClickConfidenceFailsClosedToZero() {
        String marker = TeacherProtocolTestFixture.groundedMarker("confidence-click-high");
        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Share|1.01|bad confidence",
                marker
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(0.0, action.confidence, 0.0);
    }

    @Test public void negativeConfidenceFailsClosedToZero() {
        String marker = TeacherProtocolTestFixture.groundedMarker("confidence-set-negative");
        AgentAction action = TeacherProtocol.parse(
                marker + "SET_TEXT|Title|Hello|-0.01|bad confidence",
                marker
        );
        assertEquals(AgentAction.Type.SET_TEXT, action.type);
        assertEquals(0.0, action.confidence, 0.0);
    }

    @Test public void aboveOneDoneCannotBypassFinalQaThreshold() {
        String marker = TeacherProtocolTestFixture.groundedMarker("confidence-done-high");
        AgentAction action = TeacherProtocol.parse(
                marker + "DONE|||1.01|looks done",
                marker,
                true
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0);
        assertTrue(action.reason.contains("final done confidence below safety threshold"));
    }

    @Test public void validUnitIntervalConfidenceIsPreserved() {
        String marker = TeacherProtocolTestFixture.groundedMarker("confidence-click-valid");
        AgentAction action = TeacherProtocol.parse(
                marker + "CLICK_TEXT|Share|0.99|valid",
                marker
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(0.99, action.confidence, 0.0);
    }
}
