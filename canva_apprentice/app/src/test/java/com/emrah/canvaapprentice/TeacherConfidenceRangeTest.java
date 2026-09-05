package com.emrah.canvaapprentice;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TeacherConfidenceRangeTest {
    private static final String MARKER = "CAA1_REPLY_test|";

    @Test public void aboveOneClickConfidenceFailsClosedToZero() {
        AgentAction action = TeacherProtocol.parse(
                MARKER + "CLICK_TEXT|Share|1.01|bad confidence",
                MARKER
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(0.0, action.confidence, 0.0);
    }

    @Test public void negativeConfidenceFailsClosedToZero() {
        AgentAction action = TeacherProtocol.parse(
                MARKER + "SET_TEXT|Title|Hello|-0.01|bad confidence",
                MARKER
        );
        assertEquals(AgentAction.Type.SET_TEXT, action.type);
        assertEquals(0.0, action.confidence, 0.0);
    }

    @Test public void aboveOneDoneCannotBypassFinalQaThreshold() {
        AgentAction action = TeacherProtocol.parse(
                MARKER + "DONE|||1.01|looks done",
                MARKER,
                true
        );
        assertEquals(AgentAction.Type.NOOP, action.type);
        assertEquals(0.0, action.confidence, 0.0);
        assertTrue(action.reason.contains("final done confidence below safety threshold"));
    }

    @Test public void validUnitIntervalConfidenceIsPreserved() {
        AgentAction action = TeacherProtocol.parse(
                MARKER + "CLICK_TEXT|Share|0.99|valid",
                MARKER
        );
        assertEquals(AgentAction.Type.CLICK_TEXT, action.type);
        assertEquals(0.99, action.confidence, 0.0);
    }
}
