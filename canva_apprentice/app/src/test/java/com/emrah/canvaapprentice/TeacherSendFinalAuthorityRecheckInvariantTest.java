package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/** Locks the last authority check into the live-node reacquire -> click boundary. */
public class TeacherSendFinalAuthorityRecheckInvariantTest {
    @Test
    public void liveTargetIsRevalidatedAgainstTransportAfterReacquireAndBeforeClick() throws Exception {
        String source = readTeacherBridgeSource();
        int method = source.indexOf("private boolean revalidateAndClickCapturedSend(");
        int reacquire = source.indexOf("findUniqueNodeByIdentity(currentRoot, evidence.sendClickTargetIdentity)", method);
        int postReacquireAuthority = source.indexOf("isTransportCurrent(sessionId, requestToken, authority)", reacquire);
        int policy = source.indexOf("TeacherSendClickEvidencePolicy.mayDispatch(", reacquire);
        int click = source.indexOf("currentTarget.performAction(AccessibilityNodeInfo.ACTION_CLICK)", reacquire);

        assertTrue("send revalidation method must exist", method >= 0);
        assertTrue("live target must be reacquired", reacquire > method);
        assertTrue("transport authority must be checked again after live-node reacquire",
                postReacquireAuthority > reacquire);
        assertTrue("post-reacquire authority must feed the dispatch policy",
                policy > postReacquireAuthority);
        assertTrue("click must remain behind both live-node and authority revalidation",
                click > policy);
    }

    private static String readTeacherBridgeSource() throws Exception {
        Path[] candidates = new Path[] {
                Paths.get("app/src/main/java/com/emrah/canvaapprentice/TeacherBridge.java"),
                Paths.get("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/TeacherBridge.java")
        };
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("TeacherBridge.java source not found");
    }
}
