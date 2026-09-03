package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class AgentConstantsTest {
    @Test public void allowlistContainsExactlyCanvaAndChatGPT() {
        assertEquals(2, AgentConstants.ALLOWED_PACKAGES.size());
        assertTrue(AgentConstants.ALLOWED_PACKAGES.contains("com.canva.editor"));
        assertTrue(AgentConstants.ALLOWED_PACKAGES.contains("com.openai.chatgpt"));
    }

    @Test public void safetyGateRejectsOtherActivePackage() {
        TaskState state = new TaskState(
                "goal","","","", "",
                TaskState.Mode.RUNNING,false,0
        );
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Projects","",0.99,"recover existing design"
        );
        SafetyGate.Decision d = new SafetyGate().evaluate(a,state,"com.example.other");
        assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
    }
}
