package com.emrah.canvaapprentice;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.*;

public final class TeacherPromptGroundingPurityTest {
    @Before public void setUp() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    @After public void tearDown() {
        TeacherExecutionLease.invalidateGlobal();
        CheckpointRequestGuard.resetForTest();
    }

    private static TaskState state() {
        return new TaskState("edit", "", "", "", "",
                TaskState.Mode.RUNNING, false, 1);
    }

    private static UiTreeSnapshot snapshot(String pkg) {
        return new UiTreeSnapshot(pkg, Collections.emptyList(), 1L);
    }

    @Test public void structuralPromptOnlyVerifiesExistingAuthority() {
        UiTreeSnapshot snap = snapshot(AgentConstants.CANVA_PACKAGE);
        TeacherRequestAuthority authority =
                TeacherRequestAuthority.begin("purestruct", snap.stableFingerprint());
        assertTrue(authority.isValid());

        CheckpointRequestGuard.RequestLease before =
                CheckpointRequestGuard.currentBoundRequestLease(authority.marker);
        String prompt = TeacherProtocol.buildRequest(state(), snap, "note", authority.requestId);
        CheckpointRequestGuard.RequestLease after =
                CheckpointRequestGuard.currentBoundRequestLease(authority.marker);

        assertTrue(prompt.contains("RequestId: " + authority.requestId));
        assertTrue(authority.stillOwnsTransport());
        assertEquals(before.executionLeaseToken, after.executionLeaseToken);
        assertEquals(before.snapshotFingerprint, after.snapshotFingerprint);
    }

    @Test(expected = IllegalStateException.class)
    public void structuralPromptCannotRepairMissingAuthority() {
        TeacherProtocol.buildRequest(state(), snapshot(AgentConstants.CANVA_PACKAGE),
                "note", "unbound1");
    }

    @Test(expected = IllegalStateException.class)
    public void structuralPromptCannotSubstituteDifferentSnapshot() {
        UiTreeSnapshot original = snapshot(AgentConstants.CANVA_PACKAGE);
        TeacherRequestAuthority authority =
                TeacherRequestAuthority.begin("mismatch1", original.stableFingerprint());
        assertTrue(authority.isValid());

        TeacherProtocol.buildRequest(state(), snapshot("different.package"),
                "note", authority.requestId);
    }

    @Test public void visualPromptOnlyVerifiesExistingAuthority() {
        UiTreeSnapshot snap = snapshot(AgentConstants.CANVA_PACKAGE);
        TeacherRequestAuthority authority =
                TeacherRequestAuthority.beginVisual("purevisual", snap.stableFingerprint());
        assertTrue(authority.isValid());

        String prompt = TeacherProtocol.buildVisualRequest(
                state(), snap, authority.requestId, "need visual evidence");

        assertTrue(prompt.contains("RequestId: " + authority.requestId));
        assertTrue(authority.stillOwnsTransport());
        CheckpointRequestGuard.RequestLease current =
                CheckpointRequestGuard.currentBoundRequestLease(authority.marker);
        assertEquals(authority.executionLeaseToken, current.executionLeaseToken);
        assertEquals(authority.snapshotFingerprint, current.snapshotFingerprint);
    }
}
