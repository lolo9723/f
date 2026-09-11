package com.emrah.canvaapprentice;

import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

/**
 * Cross-layer invariant for the final teacher-session/action-dispatch race.
 *
 * ActionExecutor holds TeacherExecutionLease.GLOBAL from its last lease check through
 * the actual Android mutation. Every service path that rotates the durable teacher
 * session must therefore invalidate that same lease before touching TaskStateRepository.
 * Together these two ordering constraints make session rotation and stale action
 * dispatch mutually exclusive instead of relying on a check-then-act window.
 */
public final class TeacherSessionDispatchAuthorityInvariantTest {
    private static String source(String fileName) throws Exception {
        Path cursor=Paths.get("").toAbsolutePath().normalize();
        Path path=null;
        while(cursor!=null){
            Path direct=cursor.resolve("app/src/main/java/com/emrah/canvaapprentice/"+fileName);
            if(Files.isRegularFile(direct)){
                path=direct;
                break;
            }
            Path nested=cursor.resolve("canva_apprentice/app/src/main/java/com/emrah/canvaapprentice/"+fileName);
            if(Files.isRegularFile(nested)){
                path=nested;
                break;
            }
            cursor=cursor.getParent();
        }
        assertTrue(fileName+" source must be available to the safety test",path!=null && Files.isRegularFile(path));
        return new String(Files.readAllBytes(path),StandardCharsets.UTF_8);
    }

    private static String section(String source,String startMarker,String endMarker){
        int start=source.indexOf(startMarker);
        int end=start<0 ? -1 : source.indexOf(endMarker,start);
        assertTrue("Missing section start: "+startMarker,start>=0);
        assertTrue("Missing section end after: "+startMarker,end>start);
        return source.substring(start,end);
    }

    private static void assertOrdered(String body,String first,String second,String message){
        int a=body.indexOf(first);
        int b=body.indexOf(second);
        assertTrue(message+" (missing first token)",a>=0);
        assertTrue(message+" (missing second token)",b>=0);
        assertTrue(message+" (wrong order)",b>a);
    }

    @Test public void executorKeepsGlobalLeaseAcrossActualMutation() throws Exception {
        String executor=source("ActionExecutor.java");
        String execute=section(executor,"public boolean execute(AgentAction action)","private boolean executeWithCurrentLease");
        assertTrue("dispatch must be owned by the global teacher execution lease",
                execute.contains("TeacherExecutionLease.withGlobalCurrent("));
        assertTrue("the Android mutation path must execute inside the owned lease callback",
                execute.contains("() -> executeWithCurrentLease(action)"));
    }

    @Test public void allServiceSessionRotationsInvalidateDispatchLeaseFirst() throws Exception {
        String service=source("AgentAccessibilityService.java");

        String start=section(service,"public void startTask(","public void stopTask(");
        assertOrdered(start,"TeacherExecutionLease.invalidateGlobal();","repo.start(",
                "task start must invalidate stale dispatch authority before rotating session");

        String stop=section(service,"public void stopTask(","private void pauseForHuman(");
        assertOrdered(stop,"TeacherExecutionLease.invalidateGlobal();","repo::stop",
                "STOP must invalidate stale dispatch authority before rotating session");

        String pause=section(service,"private void pauseForHuman(","private void enterPersistenceHardHold(");
        assertOrdered(pause,"TeacherExecutionLease.invalidateGlobal();","repo.pauseForHuman(",
                "human takeover must invalidate stale dispatch authority before rotating session");

        String resume=section(service,"private void showHumanOverlay(","public void onStaleTeacherRequestDiscarded(");
        assertOrdered(resume,"TeacherExecutionLease.invalidateGlobal();","repo.resume();",
                "DEVAM ET must invalidate stale dispatch authority before rotating session");
    }

    @Test public void dispatchRemainsSessionCheckedOnBothSides() throws Exception {
        String service=source("AgentAccessibilityService.java");
        String allow=section(service,
                "if(d.kind==SafetyGate.Decision.Kind.ALLOW)",
                "} else if(d.kind==SafetyGate.Decision.Kind.ASK_TEACHER)");
        int before=allow.indexOf("if(!isActionChainCurrent(action,teacherSessionId))");
        int dispatch=allow.indexOf("executor.execute(action)");
        int after=allow.indexOf("if(!isActionChainCurrent(action,teacherSessionId))",before+1);
        assertTrue("session+lease authority must be checked immediately before dispatch",before>=0 && dispatch>before);
        assertTrue("session+lease authority must be checked again immediately after dispatch",after>dispatch);
    }
}
