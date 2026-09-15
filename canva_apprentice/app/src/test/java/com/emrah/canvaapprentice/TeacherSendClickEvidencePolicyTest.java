package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class TeacherSendClickEvidencePolicyTest {
    @Test public void unchangedCurrentTargetMayDispatch() {
        assertTrue(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:send-1", "uid:send-1"));
    }

    @Test public void staleOrChangedTargetFailsClosed() {
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(false, true, true, true, true, "uid:send-1", "uid:send-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, false, true, true, true, "uid:send-1", "uid:send-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, false, true, true, "uid:send-1", "uid:send-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, false, true, "uid:send-1", "uid:send-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, false, "uid:send-1", "uid:send-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:send-1", "uid:send-2"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "", ""));
    }
}
