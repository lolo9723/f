package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Dispatch provenance must bind the reply baseline, Send candidate, and concrete clickable
 * target to the same accessibility evidence pass. The captured target must then be reacquired
 * by stable identity and fail closed unless the live node is still unique and safe to click.
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
        assertTrue(source.contains("return usableSendNodeCount == 1 ? sendClickTarget : null;"));
        assertTrue(source.contains("evidence.uniqueSendClickTarget() == null"));
        assertTrue("live identity lookup must reject duplicate matches",
                source.contains("if (match != null) return null;"));
    }

    @Test public void clickableParentIsBoundDuringEvidenceCapture() throws Exception {
        String source = source();
        assertTrue(source.contains("sendClickTarget = firstClickableNodeOrParent(n);"));
        assertTrue(source.contains("sendClickTargetIdentity = stableNodeIdentity(sendClickTarget);"));
        assertFalse("dispatch must not traverse parents after evidence capture",
                source.contains("clickNodeOrParent("));

        int capture = source.indexOf("private static DispatchEvidenceSnapshot captureDispatchEvidence");
        int bind = source.indexOf("sendClickTarget = firstClickableNodeOrParent(n);", capture);
        int captureEnd = source.indexOf("private static ReplyEvidenceSnapshot captureReplyEvidence", capture);
        assertTrue("click target must be resolved inside the dispatch evidence pass",
                capture >= 0 && bind > capture && captureEnd > bind);
    }

    @Test public void capturedSendIsReacquiredAndRevalidatedBeforeClick() throws Exception {
        String source = source();
        assertTrue(source.contains("AccessibilityNodeInfo currentTarget = findUniqueNodeByIdentity(currentRoot, evidence.sendClickTargetIdentity);"));
        assertTrue(source.contains("TeacherSendClickEvidencePolicy.mayDispatch("));
        assertTrue(source.contains("return currentTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);"));

        String token = "DispatchEvidenceSnapshot dispatchEvidence = captureDispatchEvidence(beforeSendRoot, authority.marker);";
        int first = source.indexOf(token);
        assertTrue(first >= 0);
        int revalidate = source.indexOf("revalidateAndClickCapturedSend(dispatchEvidence, sessionId, requestToken, authority)", first + token.length());
        int poll = source.indexOf("pollReply(authority, sessionId, requestToken, callback, 0, dispatchEvidence.replyBaseline);", first + token.length());
        assertTrue("captured Send must be revalidated after evidence capture", revalidate > first);
        assertTrue("reply polling must start only after revalidated dispatch", poll > revalidate);

        int second = source.indexOf(token, first + token.length());
        assertTrue(second > first);
        revalidate = source.indexOf("revalidateAndClickCapturedSend(dispatchEvidence, sessionId, requestToken, authority)", second + token.length());
        poll = source.indexOf("pollReply(authority, sessionId, requestToken, callback, 0, dispatchEvidence.replyBaseline);", second + token.length());
        assertTrue(revalidate > second && poll > revalidate);
    }

    @Test public void authorityIsRecheckedInsideRevalidationBeforeLiveLookupAndClick() throws Exception {
        String source = source();
        int method = source.indexOf("private boolean revalidateAndClickCapturedSend");
        assertTrue(method >= 0);
        int recheck = source.indexOf("if (!isTransportCurrent(sessionId, requestToken, authority)) return false;", method);
        int liveRoot = source.indexOf("AccessibilityNodeInfo currentRoot = service.getRootInActiveWindow();", method);
        int secondRecheck = source.indexOf("boolean transportCurrent = isTransportCurrent(sessionId, requestToken, authority);", liveRoot);
        int click = source.indexOf("return currentTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK);", method);
        assertTrue("authority must be current before reacquiring live UI", recheck > method && liveRoot > recheck);
        assertTrue("authority must be rechecked after reacquire and before click", secondRecheck > liveRoot && click > secondRecheck);
    }
}
