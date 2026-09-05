package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class UnboundDesignMutationSafetyTest {
    private static AgentAction action(AgentAction.Type type, String target, String value, boolean visual) {
        return new AgentAction(type, target, value, 0.999, "test", visual);
    }

    @Test public void unboundDesignBlocksTextMutation() {
        assertFalse(DesignContinuityPolicy.allows(
                action(AgentAction.Type.SET_TEXT, "Title", "Wrong", false), "", false, false));
        assertFalse(DesignContinuityPolicy.allows(
                action(AgentAction.Type.SET_NODE_TEXT,
                        NodeTargetCodec.encode(8,"Title","android.widget.EditText","20 180 500 240","-E"),
                        "Wrong", false), "", false, false));
    }

    @Test public void unboundDesignBlocksCoordinateMutationEvenWithVisualGrounding() {
        assertFalse(DesignContinuityPolicy.allows(
                action(AgentAction.Type.TAP_NORM, "500,500", "", true), "", false, false));
        assertFalse(DesignContinuityPolicy.allows(
                action(AgentAction.Type.DRAG_NORM, "100,100,800,800,500", "", true), "", false, false));
    }

    @Test public void unboundDesignStillAllowsNonContentRecoveryNavigation() {
        assertTrue(DesignContinuityPolicy.allows(
                action(AgentAction.Type.CLICK_TEXT, "Projects", "", false), "", false, true));
        assertTrue(DesignContinuityPolicy.allows(
                action(AgentAction.Type.CLICK_NODE,
                        NodeTargetCodec.encode(4,"Existing Campaign","android.widget.Button","10 20 300 100","C-"),
                        "", false), "", false, true));
        assertTrue(DesignContinuityPolicy.allows(
                action(AgentAction.Type.BACK, "", "", false), "", false, false));
    }
}
