package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class TeacherProtocolTest {
    @Test public void ignoresReplyWithWrongRequestMarker() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                "CAA1_REPLY_other|CLICK_TEXT|Share|0.99|wrong request",
                marker
        );
        assertEquals(AgentAction.Type.NOOP,a.type);
        assertEquals(0.0,a.confidence,0.0001);
    }

    @Test public void parsesStructuralClickAsNonVisual() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker+"CLICK_TEXT|Elements|0.99|open elements",
                marker
        );
        assertEquals(AgentAction.Type.CLICK_TEXT,a.type);
        assertFalse(a.visualGrounded);
        assertEquals("Elements",a.target);
    }

    @Test public void parsesVisualTapAsVisualGrounded() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker+"TAP_NORM|520,410|0.995|select photo",
                marker,
                true
        );
        assertEquals(AgentAction.Type.TAP_NORM,a.type);
        assertTrue(a.visualGrounded);
        assertEquals(0.995,a.confidence,0.0001);
    }

    @Test public void parsesVisualDrag() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker+"DRAG_NORM|500,500,700,500,450|0.998|move logo right",
                marker,
                true
        );
        assertEquals(AgentAction.Type.DRAG_NORM,a.type);
        assertEquals("500,500,700,500,450",a.target);
    }

    @Test public void parsesDesignBinding() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker+"BIND_DESIGN|30 Ağustos Fakülte Afişi|0.99|unique top title",
                marker
        );
        assertEquals(AgentAction.Type.BIND_DESIGN,a.type);
        assertEquals("30 Ağustos Fakülte Afişi",a.target);
    }

    @Test public void parsesScreenshotFallback() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker+"SCREENSHOT|||1.0|canvas objects have no labels",
                marker
        );
        assertEquals(AgentAction.Type.SCREENSHOT,a.type);
    }
}
