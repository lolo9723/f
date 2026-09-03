package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesignContinuityPolicyTest {

    private static AgentAction action(AgentAction.Type type, String target, String value) {
        return new AgentAction(type, target, value, 0.99, "test");
    }

    @Test public void unboundTaskAllowsNormalExecution() {
        AgentAction a = action(AgentAction.Type.CLICK_TEXT, "Share", "");
        assertTrue(DesignContinuityPolicy.allows(a, "", false, false));
    }

    @Test public void visibleBoundAnchorAllowsExecution() {
        AgentAction a = action(AgentAction.Type.SET_TEXT, "Title", "Hello");
        assertTrue(DesignContinuityPolicy.allows(a, "Campaign A", true, false));
    }

    @Test public void unknownEditorBlocksMutationWhenAnchorMissing() {
        AgentAction a = new AgentAction(AgentAction.Type.TAP_NORM, "500,500", "", 0.99, "test", true);
        assertFalse(DesignContinuityPolicy.allows(a, "Campaign A", false, false));
    }

    @Test public void backIsAllowedAsNonMutatingRecovery() {
        AgentAction a = action(AgentAction.Type.BACK, "", "");
        assertTrue(DesignContinuityPolicy.allows(a, "Campaign A", false, false));
    }

    @Test public void homeAllowsOnlyExactExistingDesignRecovery() {
        AgentAction exact = action(AgentAction.Type.CLICK_TEXT, "Campaign A", "");
        AgentAction other = action(AgentAction.Type.CLICK_TEXT, "Campaign B", "");
        AgentAction create = action(AgentAction.Type.CLICK_TEXT, "Create a design", "");

        assertTrue(DesignContinuityPolicy.allows(exact, "Campaign A", false, true));
        assertFalse(DesignContinuityPolicy.allows(other, "Campaign A", false, true));
        assertFalse(DesignContinuityPolicy.allows(create, "Campaign A", false, true));
    }

    @Test public void matchingIsCaseAccentAndWhitespaceStable() {
        AgentAction exact = action(AgentAction.Type.CLICK_TEXT, "  Çalışma   Planı  ", "");
        assertTrue(DesignContinuityPolicy.allows(exact, "calisma plani", false, true));
    }
}
