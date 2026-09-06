package com.emrah.canvaapprentice;

import android.graphics.Rect;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExactNodeStructuralEvidenceTest {
    @Test public void structuralTeacherClickCarriesFullRowEvidence() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker + "CLICK_NODE|17|Elements|android.widget.TextView|24 180 260 236|C-|0.997|same current row",
                marker
        );

        assertEquals(AgentAction.Type.CLICK_NODE, a.type);
        assertEquals(17, NodeTargetCodec.index(a.target));
        assertEquals("Elements", NodeTargetCodec.label(a.target));
        assertEquals("android.widget.TextView", NodeTargetCodec.className(a.target));
        assertEquals("24 180 260 236", NodeTargetCodec.bounds(a.target));
        assertEquals("C-", NodeTargetCodec.flags(a.target));
        assertTrue(NodeTargetCodec.hasStructuralEvidence(a.target));
        assertEquals(0.997, a.confidence, 0.0001);
    }

    @Test public void legacyIndexAndLabelHasNoStructuralEvidence() {
        String encoded = NodeTargetCodec.encode(17, "Elements");
        assertFalse(NodeTargetCodec.hasStructuralEvidence(encoded));
    }

    @Test public void structuralSetTextCarriesEditableFlagAndValue() {
        String marker = TeacherProtocol.markerFor("abc123");
        AgentAction a = TeacherProtocol.parse(
                marker + "SET_NODE_TEXT|31|Title|android.widget.EditText|40 300 700 380|-E|New heading|0.999|same editable row",
                marker
        );

        assertEquals(AgentAction.Type.SET_NODE_TEXT, a.type);
        assertEquals("-E", NodeTargetCodec.flags(a.target));
        assertEquals("New heading", a.value);
        assertTrue(NodeTargetCodec.hasStructuralEvidence(a.target));
    }

    @Test public void promptRequiresSameRowStructuralFields() {
        TaskState state = new TaskState("goal", "", "", "", "", TaskState.Mode.RUNNING, false, 0);
        UiTreeSnapshot snap = new UiTreeSnapshot(AgentConstants.CANVA_PACKAGE,
                java.util.Collections.emptyList(), 0L);
        String prompt = TeacherProtocol.buildRequest(state, snap, "note", "abc123");

        assertTrue(prompt.contains("index|class|text|description|bounds|flags"));
        assertTrue(prompt.contains("copy index, label, class, bounds, and flags from the SAME row"));
    }

    @Test public void exactNodeBoundsMustHavePositiveArea() {
        // android.jar Rect constructors are mocked/no-op in local JVM tests. Populate the
        // public edge fields directly so this test exercises the production admission guard
        // rather than accidentally testing the Android stub implementation.
        Rect valid = rect(24, 180, 260, 236);
        Rect zeroWidth = rect(24, 180, 24, 236);
        Rect zeroHeight = rect(24, 180, 260, 180);
        Rect inverted = rect(260, 236, 24, 180);

        assertTrue(ActionExecutor.exactNodeBoundsUsable(valid));
        assertFalse(ActionExecutor.exactNodeBoundsUsable(zeroWidth));
        assertFalse(ActionExecutor.exactNodeBoundsUsable(zeroHeight));
        assertFalse(ActionExecutor.exactNodeBoundsUsable(inverted));
        assertFalse(ActionExecutor.exactNodeBoundsUsable(null));
    }

    private static Rect rect(int left, int top, int right, int bottom) {
        Rect r = new Rect();
        r.left = left;
        r.top = top;
        r.right = right;
        r.bottom = bottom;
        return r;
    }
}
