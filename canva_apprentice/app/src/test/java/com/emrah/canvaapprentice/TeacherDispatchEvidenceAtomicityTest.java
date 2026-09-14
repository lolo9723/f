package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Dispatch provenance must bind the reply baseline and the Send candidate to the same
 * accessibility traversal. A split baseline/findSend sequence can observe two different UI
 * states and click a button that was not part of the provenance baseline.
 */
public final class TeacherDispatchEvidenceAtomicityTest {
    private static String source() throws Exception {
        Path cursor = Paths.get("").toAbsolutePath().normalize();
        while (cursor != null) {
            Path direct = cursor.resolve("app/src/main/java/com/emrah/canvaapprentice/TeacherBridge.java");
            if (Files.isRegularFile(direct)) {
                return new String(Files.readAllBytes(direct), StandardCharsets.UTF_8);
            }
            Path nested = cursor.resolve("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/TeacherBridge.java");
            if (Files.isRegularFile(nested)) {
                return new String(Files.readAllBytes(nested), StandardCharsets.UTF_8);
            }
            cursor = cursor.getParent();
        }
        throw new AssertionError("TeacherBridge source must be available to the safety test");
    }

    @Test public void dispatchUsesOneEvidencePassForBaselineAndSendCandidate() throws Exception {
        String source = source();
        assertTrue(source.contains("DispatchEvidenceSnapshot dispatchEvidence = captureDispatchEvidence(beforeSendRoot, authority.marker);"));
        assertTrue(source.contains("pollReply(authority, sessionId, requestToken, callback, 0, dispatchEvidence.replyBaseline);"));
        assertFalse("split live Send traversal must stay removed", source.contains("findSend(beforeSendRoot)"));
        assertFalse("dispatch baseline must not be captured separately", source.contains("captureReplyEvidence(beforeSendRoot, authority.marker)"));
    }

    @Test public void dispatchFailsClosedOnAmbiguousSendCandidates() throws Exception {
        String source = source();
        assertTrue(source.contains("return usableSendNodeCount == 1 ? sendNode : null;"));
        assertTrue(source.contains("AccessibilityNodeInfo send = dispatchEvidence.uniqueSendNode();"));
    }

    @Test public void authorityIsRecheckedAfterEvidenceCaptureBeforeClick() throws Exception {
        String source = source();
        String token = "DispatchEvidenceSnapshot dispatchEvidence = captureDispatchEvidence(beforeSendRoot, authority.marker);";
        int first = source.indexOf(token);
        assertTrue(first >= 0);
        int recheck = source.indexOf("if (!isTransportCurrent(sessionId, requestToken, authority))", first + token.length());
        int click = source.indexOf("clickNodeOrParent(send)", first + token.length());
        assertTrue("transport authority must be rechecked after evidence capture", recheck > first);
        assertTrue("recheck must happen before the actual click", click > recheck);

        int second = source.indexOf(token, first + token.length());
        assertTrue(second > first);
        recheck = source.indexOf("if (!isTransportCurrent(sessionId, requestToken, authority))", second + token.length());
        click = source.indexOf("clickNodeOrParent(send)", second + token.length());
        assertTrue(recheck > second && click > recheck);
    }
}
