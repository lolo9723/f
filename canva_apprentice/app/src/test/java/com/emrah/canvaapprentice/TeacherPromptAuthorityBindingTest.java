package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class TeacherPromptAuthorityBindingTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @Test public void structuralPromptMustCarryExactlyItsAuthorityRequestId() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("req123", "snapshot-A");

        assertTrue(TeacherBridge.promptMatchesAuthority(
                "CANVA_APPRENTICE_TEACHER_REQUEST\nRequestId: req123\nGoal: edit",
                authority,
                false));
        assertFalse(TeacherBridge.promptMatchesAuthority(
                "CANVA_APPRENTICE_TEACHER_REQUEST\nRequestId: other\nGoal: edit",
                authority,
                false));
        assertFalse(TeacherBridge.promptMatchesAuthority(
                "CANVA_APPRENTICE_TEACHER_REQUEST\nRequestId: req123\nRequestId: req123\nGoal: edit",
                authority,
                false));
    }

    @Test public void structuralAndVisualTransportsCannotCrossPromptTypes() {
        TeacherRequestAuthority structural = TeacherRequestAuthority.begin("struct1", "snapshot-S");
        String structuralPrompt = "CANVA_APPRENTICE_TEACHER_REQUEST\nRequestId: struct1\nGoal: edit";
        assertTrue(TeacherBridge.promptMatchesAuthority(structuralPrompt, structural, false));
        assertFalse(TeacherBridge.promptMatchesAuthority(structuralPrompt, structural, true));

        TeacherRequestAuthority visual = TeacherRequestAuthority.beginVisual("visual1", "snapshot-V");
        String visualPrompt = "CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST\nRequestId: visual1\nGoal: edit";
        assertTrue(TeacherBridge.promptMatchesAuthority(visualPrompt, visual, true));
        assertFalse(TeacherBridge.promptMatchesAuthority(visualPrompt, visual, false));
    }

    @Test public void requestIdLookalikesDoNotSatisfyAuthorityBinding() {
        TeacherRequestAuthority authority = TeacherRequestAuthority.begin("safe1", "snapshot-S");

        assertFalse(TeacherBridge.promptMatchesAuthority(
                "CANVA_APPRENTICE_TEACHER_REQUEST\nXRequestId: safe1\nGoal: edit",
                authority,
                false));
        assertFalse(TeacherBridge.promptMatchesAuthority(
                "CANVA_APPRENTICE_TEACHER_REQUEST\nRequestId: safe1-extra\nGoal: edit",
                authority,
                false));
        assertFalse(TeacherBridge.promptMatchesAuthority(null, authority, false));
    }
}
