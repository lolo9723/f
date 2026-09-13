package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TeacherConfidenceRangeTest {
    @Test public void aboveOneClickConfidenceFailsClosedToZero() {
        AgentAction action = TeacherProtocolTestFixture.parseStructural(
                "confidence-click-high", "CLICK_TEXT|Share|1.01|bad confidence");
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(0.0, action.confidence, 0.0);
    }

    @Test public void negativeConfidenceFailsClosedToZero() {
        AgentAction action = TeacherProtocolTestFixture.parseStructural(
                "confidence-set-negative", "SET_TEXT|Title|Hello|-0.01|bad confidence");
        assertEquals(AgentAction.Type.SET_TEXT, action.type);
        assertEquals(0.0, action.confidence, 0.0);
    }

    @Test public void aboveOneDoneCannotBypassFinalQaThreshold() {
        AgentAction action = TeacherProtocolTestFixture.parseVisual(
                "confidence-done-high", "DONE|||1.01|looks done");
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0);
        assertTrue(action.reason.contains("final done confidence below safety threshold"));
    }

    @Test public void validUnitIntervalConfidenceIsPreserved() {
        AgentAction action = TeacherProtocolTestFixture.parseStructural(
                "confidence-click-valid", "CLICK_TEXT|Share|0.99|valid");
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(0.99, action.confidence, 0.0);
    }
}
