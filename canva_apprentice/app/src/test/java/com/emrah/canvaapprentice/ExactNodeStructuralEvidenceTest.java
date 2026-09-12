package com.emrah.canvaapprentice;

import android.graphics.Rect;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExactNodeStructuralEvidenceTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void structuralTeacherClickCarriesFullRowEvidence() {
        String marker = TeacherProtocolTestFixture.groundedMarker("abc123");
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
        String marker = TeacherProtocolTestFixture.groundedMarker("abc123");
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
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin(
                "abc123", snap.stableFingerprint());
        assertTrue(authority.isValid());
        String prompt = TeacherProtocol.buildRequest(state, snap, "note", authority.requestId);

        assertTrue(prompt.contains("index|class|text|description|bounds|flags"));
        assertTrue(prompt.contains("copy index, label, class, bounds, and flags from the SAME row"));
    }

    @Test public void exactNodeBoundsMustHavePositiveArea() {
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

    @Test public void exactNodeIndexIgnoresHiddenTeacherInvisibleNodes() {
        assertFalse(ActionExecutor.compactIndexEligible(
                false, "Hidden stale target", "", true, false));
        assertTrue(ActionExecutor.compactIndexEligible(
                true, "Visible target", "", true, false));
        assertTrue(ActionExecutor.compactIndexEligible(
                true, "", "", false, true));
    }

    @Test public void exactNodeIndexIgnoresVisibleButMeaninglessContainers() {
        assertFalse(ActionExecutor.compactIndexEligible(
                true, "", "", false, false));
        assertFalse(ActionExecutor.compactIndexEligible(
                true, "   ", "   ", false, false));
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
