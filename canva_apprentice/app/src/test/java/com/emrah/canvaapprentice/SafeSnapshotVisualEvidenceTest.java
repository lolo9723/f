package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SafeSnapshotVisualEvidenceTest {
    private static String repeat(char c) {
        StringBuilder out = new StringBuilder(256);
        for (int i = 0; i < 256; i++) out.append(c);
        return out.toString();
    }

    private static boolean mayCommit(String visual) {
        return SafeSnapshotPolicy.mayCommitObservedCheckpoint(
                TaskState.Mode.RUNNING,
                "Annual Report",
                "Annual Report",
                "session-1",
                "session-1",
                "tree-fp",
                "tree-fp",
                true,
                false,
                visual
        );
    }

    @Test public void uniformBlackCaptureCannotCreateContinuityAuthority() {
        assertFalse(mayCommit(repeat('0')));
    }

    @Test public void uniformWhiteCaptureCannotCreateContinuityAuthority() {
        assertFalse(mayCommit(repeat('f')));
    }

    @Test public void syntacticallyValidButUniformMidtoneCaptureAlsoFailsClosed() {
        assertFalse(mayCommit(repeat('8')));
    }

    @Test public void nonUniformWellFormedCaptureRemainsAdmissible() {
        String visual = repeat('8').substring(0, 255) + "9";
        assertTrue(mayCommit(visual));
    }
}
