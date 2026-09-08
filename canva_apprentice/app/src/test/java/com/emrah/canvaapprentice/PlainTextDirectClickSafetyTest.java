package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PlainTextDirectClickSafetyTest {
    @Test public void uniqueVisibleEnabledClickableNodeMayBeClickedDirectly() {
        assertTrue(ActionExecutor.plainTextDirectClickAllowed(true, true, true, true));
    }

    @Test public void nonClickableLabelCannotDelegateClickToAncestor() {
        assertFalse(ActionExecutor.plainTextDirectClickAllowed(true, true, true, false));
    }

    @Test public void hiddenOrDisabledLabelFailsClosed() {
        assertFalse(ActionExecutor.plainTextDirectClickAllowed(true, false, true, true));
        assertFalse(ActionExecutor.plainTextDirectClickAllowed(true, true, false, true));
    }

    @Test public void ambiguousTextProofFailsClosed() {
        assertFalse(ActionExecutor.plainTextDirectClickAllowed(false, true, true, true));
    }
}
