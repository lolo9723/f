package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public final class TeacherPromptKindAuthorityTest {
    private TaskState state;
    private UiTreeSnapshot snapshot;

    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
        state = new TaskState(
                "edit current design", "", "", "", "",
                TaskState.Mode.RUNNING, false, 0);
        snapshot = new UiTreeSnapshot(
                AgentConstants.CANVA_PACKAGE,
                Collections.emptyList(),
                0L);
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void structuralPromptRejectsVisualAuthorityBeforeTransport() {
        TeacherRequestAuthority visual = TeacherRequestAuthority.beginVisual(
                "visualCross1", snapshot.stableFingerprint());
        assertTrue(visual.isVisualGrounded());

        try {
            TeacherProtocol.buildRequest(state, snapshot, "structural turn", visual);
            fail("visual authority must not build a structural prompt");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("grounding type mismatch"));
        }
    }

    @Test public void visualPromptRejectsStructuralAuthorityBeforeScreenshotTransport() {
        TeacherRequestAuthority structural = TeacherRequestAuthority.begin(
                "structCross1", snapshot.stableFingerprint());
        assertTrue(structural.isValid());
        assertFalse(structural.isVisualGrounded());

        try {
            TeacherProtocol.buildVisualRequest(state, snapshot, structural, "need screenshot");
            fail("structural authority must not build a visual prompt");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("grounding type mismatch"));
        }
    }

    @Test public void matchingAuthorityKindsStillBuildNormally() {
        TeacherRequestAuthority structural = TeacherRequestAuthority.begin(
                "structOk1", snapshot.stableFingerprint());
        String structuralPrompt = TeacherProtocol.buildRequest(
                state, snapshot, "safe structural turn", structural);
        assertTrue(structuralPrompt.startsWith("CANVA_APPRENTICE_TEACHER_REQUEST"));

        TeacherRequestAuthority visual = TeacherRequestAuthority.beginVisual(
                "visualOk1", snapshot.stableFingerprint());
        String visualPrompt = TeacherProtocol.buildVisualRequest(
                state, snapshot, visual, "safe visual turn");
        assertTrue(visualPrompt.startsWith("CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST"));
    }
}
