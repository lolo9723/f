package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class SafetyGateMalformedInputTest {
    private final SafetyGate gate = new SafetyGate();

    private TaskState running() {
        return new TaskState(
                "test goal","","","", "",
                TaskState.Mode.RUNNING,false,0
        );
    }

    @Test public void nullActionTypeFailsClosedBeforeExecutorBoundary() {
        AgentAction malformed = new AgentAction(
                null,"Resize","",0.999,"malformed teacher action",false,""
        );
        SafetyGate.Decision d = gate.evaluate(
                malformed,running(),AgentConstants.CANVA_PACKAGE
        );
        assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
        assertTrue(d.reason.contains("türü"));
    }

    @Test public void nullTaskStateFailsClosedInsteadOfCrashing() {
        AgentAction action = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Resize","",0.999,"safe edit",false,""
        );
        SafetyGate.Decision d = gate.evaluate(
                action,null,AgentConstants.CANVA_PACKAGE
        );
        assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
        assertTrue(d.reason.contains("Görev durumu"));
    }
}
