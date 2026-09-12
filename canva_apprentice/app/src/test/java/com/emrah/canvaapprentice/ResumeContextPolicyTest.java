package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class ResumeContextPolicyTest {
    @Test public void sameRunningSessionAndAnchorPass(){
        assertTrue(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","session-2","Design A","session-2"));
    }
    @Test public void rotatedSessionFails(){
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","session-3","Design A","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","","Design A","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","session-2","Design A",""));
    }
    @Test public void rotatedAnchorFails(){
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design B","session-2","Design A","session-2"));
    }
    @Test public void missingAnchorFailsClosed(){
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"","session-2","","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","session-2","","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"","session-2","Design A","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"   ","session-2","   ","session-2"));
    }
    @Test public void paddedSessionIdentityFailsClosed(){
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A"," session-2","Design A","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","session-2","Design A","session-2 "));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","\tsession-2","Design A","session-2"));
    }
    @Test public void paddedAnchorIdentityFailsClosed(){
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING," Design A","session-2","Design A","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","session-2","Design A ","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design\tA","session-2","Design\tA","session-2"));
    }
    @Test public void controlCharactersFailClosedEvenWhenBothSidesMatch(){
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design A","session\n2","Design A","session\n2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.RUNNING,"Design\u0007A","session-2","Design\u0007A","session-2"));
    }
    @Test public void nonRunningFails(){
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.HUMAN_TAKEOVER,"Design A","session-2","Design A","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.STOPPED,"Design A","session-2","Design A","session-2"));
        assertFalse(ResumeContextPolicy.isCurrent(TaskState.Mode.IDLE,"Design A","session-2","Design A","session-2"));
    }
}
