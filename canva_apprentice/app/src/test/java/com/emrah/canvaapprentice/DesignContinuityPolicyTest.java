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

    @Test public void exactLastSafeEditorSnapshotAllowsTransientMissingAnchor() {
        AgentAction edit = action(AgentAction.Type.SET_TEXT, "Title", "Hello");
        assertTrue(DesignContinuityPolicy.allows(
                edit, "Campaign A", false, false, true));
    }

    @Test public void changedUnknownEditorStillBlocksWhenAnchorMissing() {
        AgentAction edit = action(AgentAction.Type.SET_TEXT, "Title", "Wrong");
        assertFalse(DesignContinuityPolicy.allows(
                edit, "Campaign A", false, false, false));
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

    @Test public void homeAnchorVisibilityDoesNotAuthorizeUnrelatedActions() {
        AgentAction edit = action(AgentAction.Type.SET_TEXT, "Title", "Wrong");
        AgentAction share = action(AgentAction.Type.CLICK_TEXT, "Share", "");
        AgentAction coordinateTap = new AgentAction(
                AgentAction.Type.TAP_NORM, "500,500", "", 0.99, "test", true);
        AgentAction exact = action(AgentAction.Type.CLICK_TEXT, "Campaign A", "");

        // A project card exposes the bound design name on home; that must not be confused with
        // proof that the editor for that design is open.
        assertFalse(DesignContinuityPolicy.allows(edit, "Campaign A", true, true));
        assertFalse(DesignContinuityPolicy.allows(share, "Campaign A", true, true));
        assertFalse(DesignContinuityPolicy.allows(coordinateTap, "Campaign A", true, true));
        assertTrue(DesignContinuityPolicy.allows(exact, "Campaign A", true, true));
    }

    @Test public void homeNeverTrustsLastSafeEditorSnapshotFallback() {
        AgentAction edit = action(AgentAction.Type.SET_TEXT, "Title", "Wrong");
        AgentAction exact = action(AgentAction.Type.CLICK_TEXT, "Campaign A", "");

        assertFalse(DesignContinuityPolicy.allows(
                edit, "Campaign A", false, true, true));
        assertTrue(DesignContinuityPolicy.allows(
                exact, "Campaign A", false, true, true));
    }

    @Test public void matchingIsCaseAccentAndWhitespaceStable() {
        AgentAction exact = action(AgentAction.Type.CLICK_TEXT, "  Çalışma   Planı  ", "");
        assertTrue(DesignContinuityPolicy.allows(exact, "calisma plani", false, true));
    }
}
