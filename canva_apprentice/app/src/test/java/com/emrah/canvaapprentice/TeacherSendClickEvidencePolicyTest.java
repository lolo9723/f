package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public final class TeacherSendClickEvidencePolicyTest {
    @Test public void unchangedCurrentTargetMayDispatch() {
        assertTrue(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:send-1", "uid:send-1"));
        assertTrue(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true,
                "anc:w7|1:A|1:B|1:C|1:D", "anc:w7|1:A|1:B|1:C|1:D"));
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

    @Test public void malformedOrAmbiguousIdentityFailsClosed() {
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, " uid:send-1", " uid:send-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:send-1 ", "uid:send-1 "));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:send\u200B-1", "uid:send\u200B-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:send\n-1", "uid:send\n-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:", "uid:"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "send-1", "send-1"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true, "uid:\uD800", "uid:\uD800"));
    }

    @Test public void malformedAncestryIdentityFailsClosed() {
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true,
                "anc:w|1:A|1:B|1:C|1:D", "anc:w|1:A|1:B|1:C|1:D"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true,
                "anc:w7|1:A|1:B", "anc:w7|1:A|1:B"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true,
                "anc:w7|2:A|1:B|1:C|1:D", "anc:w7|2:A|1:B|1:C|1:D"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true,
                "anc:w7|1:A|1:B|1:C|1:Djunk", "anc:w7|1:A|1:B|1:C|1:Djunk"));
        assertFalse(TeacherSendClickEvidencePolicy.mayDispatch(true, true, true, true, true,
                "anc:w7|999999999999999999999:A|1:B|1:C|1:D",
                "anc:w7|999999999999999999999:A|1:B|1:C|1:D"));
    }
}
