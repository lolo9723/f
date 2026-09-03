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

    @Test public void promptsNeverContainTheLiveReplyMarker() {
        String requestId = "abc123";
        String marker = TeacherProtocol.markerFor(requestId);
        TaskState state = new TaskState(
                "goal","","","", "",
                TaskState.Mode.RUNNING,false,0
        );
        UiTreeSnapshot snap = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                java.util.Collections.emptyList(),
                0L
        );

        String structural = TeacherProtocol.buildRequest(state,snap,"note",requestId);
        String visual = TeacherProtocol.buildVisualRequest(state,snap,requestId,"need visual");

        assertFalse(structural.contains(marker));
        assertFalse(visual.contains(marker));
    }

    @Test public void promptsRequireContinuityRevalidationAfterHumanIntervention() {
        String requestId = "abc123";
        TaskState state = new TaskState(
                "edit existing poster","initial-fp","Existing Poster","safe-fp", "",
                TaskState.Mode.RUNNING,false,7
        );
        UiTreeSnapshot snap = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                java.util.Collections.emptyList(),
                0L
        );

        String structural = TeacherProtocol.buildRequest(
                state,snap,"Kullanıcı müdahalesi tamamlandı. Önce mevcut durumu yeniden doğrula.",requestId
        );
        String visual = TeacherProtocol.buildVisualRequest(state,snap,requestId,"resume verification");

        assertTrue(structural.contains("LastSafeSnapshotFingerprint: safe-fp"));
        assertTrue(structural.contains("current screen as untrusted until continuity is re-established"));
        assertTrue(structural.contains("otherwise request SCREENSHOT"));
        assertTrue(visual.contains("LastSafeSnapshotFingerprint: safe-fp"));
        assertTrue(visual.contains("visually verify that the screenshot belongs to that same existing design"));
    }

    @Test public void parsesEscapedMultilineSetText() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker+"SET_TEXT|Title|Hello\\|World\\nLine 2|0.99|write requested text",
                marker
        );
        assertEquals(AgentAction.Type.SET_TEXT,a.type);
        assertEquals("Hello|World\nLine 2",a.value);
        assertEquals(0.99,a.confidence,0.0001);
    }

    @Test public void preservesEscapedLiteralBackslash() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker+"SET_TEXT|Path|C:\\\\Temp|0.99|write path",
                marker
        );
        assertEquals(AgentAction.Type.SET_TEXT,a.type);
        assertEquals("C:\\Temp",a.value);
        assertEquals(0.99,a.confidence,0.0001);
    }
}
