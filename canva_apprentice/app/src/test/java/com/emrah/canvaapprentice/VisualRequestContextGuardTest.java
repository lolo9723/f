package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class VisualRequestContextGuardTest {
    @Test public void acceptsExactSameUnboundCanvaEditorState() {
        assertTrue(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "",
                false,
                false));
    }

    @Test public void rejectsUnboundCanvaHomeToPreventVisualDesignGuessing() {
        assertFalse(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-home",
                "fp-home",
                "",
                false,
                true));
    }

    @Test public void rejectsScreenshotWhenTreeChangedDuringCapture() {
        assertFalse(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-before",
                "fp-after",
                "Design A",
                true,
                false));
    }

    @Test public void rejectsBoundDesignWhenAnchorDisappeared() {
        assertFalse(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                false,
                false));
    }

    @Test public void rejectsBoundDesignOnCanvaHomeEvenIfTextIsPresent() {
        assertFalse(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                true,
                true));
    }

    @Test public void rejectsWrongActivePackage() {
        assertFalse(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                "com.other.app",
                "fp-1",
                "fp-1",
                "",
                false,
                false));
    }

    @Test public void rejectsDesignAnchorRolloverDuringScreenshotCapture() {
        assertFalse(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                "Design B",
                true,
                false));
    }

    @Test public void acceptsBoundDesignOnlyWhenAnchorIdentityAlsoRemainsExact() {
        assertTrue(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                " Design A ",
                "Design A",
                true,
                false));
    }

    @Test public void rejectsNullCurrentAnchorFailClosed() {
        assertFalse(VisualRequestContextGuard.matches(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                null,
                true,
                false));
    }

    @Test public void executionAcceptsOnlySameDesignSameTreeAndLowVisualDrift() {
        assertTrue(VisualRequestContextGuard.matchesExecution(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                "Design A",
                true,
                false,
                0.0020,
                0.0100));
    }

    @Test public void executionRejectsAnchorRolloverEvenWhenPixelsAndTreeLookStable() {
        assertFalse(VisualRequestContextGuard.matchesExecution(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                "Design B",
                true,
                false,
                0.0000,
                0.0100));
    }

    @Test public void executionRejectsTreeChangeEvenWhenPixelDriftIsTiny() {
        assertFalse(VisualRequestContextGuard.matchesExecution(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-before",
                "fp-after",
                "Design A",
                "Design A",
                true,
                false,
                0.0001,
                0.0100));
    }

    @Test public void executionRejectsVisualDriftAtOrAboveBoundary() {
        assertFalse(VisualRequestContextGuard.matchesExecution(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                "Design A",
                true,
                false,
                0.0100,
                0.0100));
    }

    @Test public void executionRejectsCallerThatAttemptsToRelaxAuditedDriftCeiling() {
        assertFalse(VisualRequestContextGuard.matchesExecution(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                "Design A",
                true,
                false,
                0.0020,
                0.0200));
    }

    @Test public void executionRejectsMalformedVisualDistanceFailClosed() {
        assertFalse(VisualRequestContextGuard.matchesExecution(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                "Design A",
                true,
                false,
                Double.NaN,
                0.0100));
        assertFalse(VisualRequestContextGuard.matchesExecution(
                AgentConstants.CANVA_PACKAGE,
                AgentConstants.CANVA_PACKAGE,
                "fp-1",
                "fp-1",
                "Design A",
                "Design A",
                true,
                false,
                -0.0010,
                0.0100));
    }
}
