package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class NewDesignNavigationSafetyTest {
    private final SafetyGate gate = new SafetyGate();

    private TaskState runningLocked() {
        return new TaskState(
                "edit existing design","","","", "",
                TaskState.Mode.RUNNING,false,0
        );
    }

    private void assertBlocked(String label) {
        AgentAction action = new AgentAction(
                AgentAction.Type.CLICK_TEXT,label,"",0.99,"navigate"
        );
        assertEquals(
                label,
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(action,runningLocked(),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksCommonEnglishCreateVariants() {
        assertBlocked("Create new");
        assertBlocked("Blank design");
        assertBlocked("New presentation");
        assertBlocked("New whiteboard");
        assertBlocked("New document");
    }

    @Test public void blocksCommonTurkishCreateVariants() {
        assertBlocked("Yeni oluştur");
        assertBlocked("Yeni sunum");
        assertBlocked("Boş tasarım");
        assertBlocked("Tasarım yarat");
        assertBlocked("Sıfırdan tasarla");
    }

    @Test public void blocksExactNodeCreateNewVariant() {
        AgentAction action = new AgentAction(
                AgentAction.Type.CLICK_NODE,
                NodeTargetCodec.encode(31,"Create new"),"",0.99,"navigate"
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(action,runningLocked(),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksVisualCoordinateCreateReason() {
        AgentAction action = new AgentAction(
                AgentAction.Type.TAP_NORM,"820,120","",0.995,"tap the visible Blank design card",true
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(action,runningLocked(),AgentConstants.CANVA_PACKAGE).kind
        );
    }
}
