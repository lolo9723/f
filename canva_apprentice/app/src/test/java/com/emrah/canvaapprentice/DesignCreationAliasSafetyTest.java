package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** Regression coverage for short/decorated Canva creation controls before design identity is bound. */
public final class DesignCreationAliasSafetyTest {
    private static AgentAction clickText(String target) {
        return new AgentAction(AgentAction.Type.CLICK_TEXT, target, "", 0.99, "test");
    }

    @Test public void unboundTaskRejectsGenericEnglishCreateControl() {
        assertFalse(DesignContinuityPolicy.allows(clickText("Create"), "", false, true));
        assertFalse(DesignContinuityPolicy.allows(clickText("＋ Create …"), "", false, true));
    }

    @Test public void unboundTaskRejectsGenericTurkishCreateControl() {
        assertFalse(DesignContinuityPolicy.allows(clickText("Oluştur"), "", false, true));
        assertFalse(DesignContinuityPolicy.allows(clickText("＋ OLUŞTUR ›"), "", false, true));
    }

    @Test public void exactNodeCannotBypassGenericCreationAliasGuard() {
        String english = NodeTargetCodec.encode(0, "Create", "button", "", "");
        String turkish = NodeTargetCodec.encode(1, "Oluştur", "button", "", "");
        assertFalse(DesignContinuityPolicy.allows(
                new AgentAction(AgentAction.Type.CLICK_NODE, english, "", 0.99, "test"), "", false, true));
        assertFalse(DesignContinuityPolicy.allows(
                new AgentAction(AgentAction.Type.CLICK_NODE, turkish, "", 0.99, "test"), "", false, true));
    }
}
