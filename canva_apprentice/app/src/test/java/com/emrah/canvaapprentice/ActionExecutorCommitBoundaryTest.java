package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ActionExecutorCommitBoundaryTest {
    @Test public void allowsOnlySameRunningCanvaContext() {
        assertTrue(ActionExecutor.executionCommitContextMatches(
                AgentConstants.CANVA_PACKAGE,
                "tree-123",
                "tree-123",
                "Existing Design",
                "Existing Design",
                true));
    }

    @Test public void blocksTreeDriftAtMutationBoundary() {
        assertFalse(ActionExecutor.executionCommitContextMatches(
                AgentConstants.CANVA_PACKAGE,
                "tree-before",
                "tree-after",
                "Existing Design",
                "Existing Design",
                true));
    }

    @Test public void blocksDesignAnchorDriftAtMutationBoundary() {
        assertFalse(ActionExecutor.executionCommitContextMatches(
                AgentConstants.CANVA_PACKAGE,
                "tree-123",
                "tree-123",
                "Existing Design",
                "Other Design",
                true));
    }

    @Test public void blocksNonCanvaOrPausedStateAtMutationBoundary() {
        assertFalse(ActionExecutor.executionCommitContextMatches(
                "com.openai.chatgpt",
                "tree-123",
                "tree-123",
                "Existing Design",
                "Existing Design",
                true));
        assertFalse(ActionExecutor.executionCommitContextMatches(
                AgentConstants.CANVA_PACKAGE,
                "tree-123",
                "tree-123",
                "Existing Design",
                "Existing Design",
                false));
    }

    @Test public void blocksMissingExpectedFingerprint() {
        assertFalse(ActionExecutor.executionCommitContextMatches(
                AgentConstants.CANVA_PACKAGE,
                "",
                "",
                "Existing Design",
                "Existing Design",
                true));
    }
}