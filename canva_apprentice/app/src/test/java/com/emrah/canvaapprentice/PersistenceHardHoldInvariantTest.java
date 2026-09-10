package com.emrah.canvaapprentice;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public final class PersistenceHardHoldInvariantTest {
    private static String serviceSource() throws Exception {
        Path path=Paths.get("app/src/main/java/com/emrah/canvaapprentice/AgentAccessibilityService.java");
        if(!Files.isRegularFile(path)){
            path=Paths.get("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/AgentAccessibilityService.java");
        }
        assertTrue("AgentAccessibilityService source must be available to the safety test",Files.isRegularFile(path));
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    @Test public void hardHoldCanNeverBeClearedInsideSameServiceLifetime() throws Exception {
        String source=serviceSource();
        assertFalse("Persistence hard hold must be monotonic until service destruction",
                source.contains("persistenceHardHold.set(false)"));
    }

    @Test public void startingNewTaskCannotBypassExistingHardHold() throws Exception {
        String source=serviceSource();
        int start=source.indexOf("public void startTask(String goal, boolean allowNewDesign)");
        int stop=source.indexOf("public void stopTask()",start);
        assertTrue(start>=0 && stop>start);
        String startBody=source.substring(start,stop);
        assertTrue(startBody.contains("if(persistenceHardHold.get())"));
        assertTrue(startBody.contains("enterPersistenceHardHold"));
        assertTrue(startBody.indexOf("if(persistenceHardHold.get())") < startBody.indexOf("repo.start"));
    }

    @Test public void durableStopDoesNotRearmAgentAfterPriorHardHold() throws Exception {
        String source=serviceSource();
        int stop=source.indexOf("public void stopTask()");
        int pause=source.indexOf("private void pauseForHuman",stop);
        assertTrue(stop>=0 && pause>stop);
        String stopBody=source.substring(stop,pause);
        assertTrue(stopBody.contains("final boolean wasHardHold=persistenceHardHold.get()"));
        assertTrue(stopBody.contains("if(wasHardHold)"));
        assertTrue(stopBody.contains("enterPersistenceHardHold"));
    }
}
