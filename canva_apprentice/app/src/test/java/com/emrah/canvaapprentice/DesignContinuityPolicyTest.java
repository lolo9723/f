package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesignContinuityPolicyTest {

    @Test public void unboundTaskAllowsNormalExecution() {
        AgentAction a = AgentAction.clickText("Share", 0.99);
        assertTrue(DesignContinuityPolicy.allows(a, "", false, false));
    }

    @Test public void visibleBoundAnchorAllowsExecution() {
        AgentAction a = AgentAction.setText("Title", "Hello", 0.99);
        assertTrue(DesignContinuityPolicy.allows(a, "Campaign A", true, false));
    }

    @Test public void unknownEditorBlocksMutationWhenAnchorMissing() {
        AgentAction a = AgentAction.tapNorm("500,500", 0.99, true);
        assertFalse(DesignContinuityPolicy.allows(a, "Campaign A", false, false));
    }

    @Test public void backIsAllowedAsNonMutatingRecovery() {
        AgentAction a = AgentAction.back(0.99);
        assertTrue(DesignContinuityPolicy.allows(a, "Campaign A", false, false));
    }

    @Test public void homeAllowsOnlyExactExistingDesignRecovery() {
        AgentAction exact = AgentAction.clickText("Campaign A", 0.99);
        AgentAction other = AgentAction.clickText("Campaign B", 0.99);
        AgentAction create = AgentAction.clickText("Create a design", 0.99);

        assertTrue(DesignContinuityPolicy.allows(exact, "Campaign A", false, true));
        assertFalse(DesignContinuityPolicy.allows(other, "Campaign A", false, true));
        assertFalse(DesignContinuityPolicy.allows(create, "Campaign A", false, true));
    }

    @Test public void matchingIsCaseAccentAndWhitespaceStable() {
        AgentAction exact = AgentAction.clickText("  Çalışma   Planı  ", 0.99);
        assertTrue(DesignContinuityPolicy.allows(exact, "calisma plani", false, true));
    }
}
