package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class ResumeFailureFailsClosedTest {
    private static String overlaySource() throws Exception {
        Path cursor=Paths.get("").toAbsolutePath().normalize();
        Path path=null;
        while(cursor!=null){
            Path direct=cursor.resolve("app/src/main/java/com/emrah/canvaapprentice/HumanTakeoverOverlay.java");
            if(Files.isRegularFile(direct)){ path=direct; break; }
            Path nested=cursor.resolve("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/HumanTakeoverOverlay.java");
            if(Files.isRegularFile(nested)){ path=nested; break; }
            cursor=cursor.getParent();
        }
        assertTrue("HumanTakeoverOverlay source must be available",path!=null && Files.isRegularFile(path));
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    @Test public void failedResumeDurablyStopsOwningService() throws Exception {
        String source=overlaySource();
        int guardedResume=source.indexOf("boolean resumed = ResumeUiTransitionGuard.runSafely(listener::onResumeRequested)");
        int failureBranch=source.indexOf("if (resumed)",guardedResume);
        int durableStop=source.indexOf("((AgentAccessibilityService) service).stopTask()",failureBranch);
        int stoppedLabel=source.indexOf("resume.setText(\"DURDU\")",durableStop);
        assertTrue(guardedResume>=0);
        assertTrue(failureBranch>guardedResume);
        assertTrue("Failed DEVAM ET must invoke durable STOP before presenting terminal UI",durableStop>failureBranch);
        assertTrue(stoppedLabel>durableStop);
    }
}
