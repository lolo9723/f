package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class SafetyGateTest {
    private final SafetyGate gate = new SafetyGate();

    private TaskState running(boolean allowNewDesign) {
        return new TaskState(
                "test goal","","","", "",
                TaskState.Mode.RUNNING,allowNewDesign,0
        );
    }

    @Test public void blocksNewDesignWhenLocked() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Create a design","",0.99,"open create"
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksNewDesignThroughExactNodeWhenLocked() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_NODE,
                NodeTargetCodec.encode(12,"Create a design"),"",0.99,"open create"
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void exactNodeNewDesignIsAllowedOnlyWhenExplicitlyAllowed() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_NODE,
                NodeTargetCodec.encode(12,"Create a design"),"",0.99,"user explicitly asked"
        );
        assertEquals(
                SafetyGate.Decision.Kind.ALLOW,
                gate.evaluate(a,running(true),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void allowsNewDesignOnlyWhenExplicitlyAllowed() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Create a design","",0.99,"user explicitly asked"
        );
        assertEquals(
                SafetyGate.Decision.Kind.ALLOW,
                gate.evaluate(a,running(true),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void rejectsCoordinateGestureWithoutVisualGrounding() {
        AgentAction a = new AgentAction(
                AgentAction.Type.TAP_NORM,"500,500","",0.999,"select image",false
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void asksWhenVisualGestureConfidenceTooLow() {
        AgentAction a = new AgentAction(
                AgentAction.Type.TAP_NORM,"500,500","",0.984,"select image",true
        );
        assertEquals(
                SafetyGate.Decision.Kind.ASK_TEACHER,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void allowsHighConfidenceVisualTap() {
        AgentAction a = new AgentAction(
                AgentAction.Type.TAP_NORM,"500,500","",0.995,"select clearly visible image",true
        );
        assertEquals(
                SafetyGate.Decision.Kind.ALLOW,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksMalformedDragCoordinates() {
        AgentAction a = new AgentAction(
                AgentAction.Type.DRAG_NORM,"50,50,200","",0.999,"move object",true
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksDestructiveVisualReason() {
        AgentAction a = new AgentAction(
                AgentAction.Type.TAP_NORM,"900,100","",0.999,"delete selected object",true
        );
        assertEquals(
                SafetyGate.Decision.Kind.ASK_TEACHER,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void asksForDiscardAndPermanentDeleteVariants() {
        AgentAction discard = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Discard","",0.999,"discard unsaved changes"
        );
        AgentAction permanent = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Delete permanently","",0.999,"remove project"
        );
        assertEquals(
                SafetyGate.Decision.Kind.ASK_TEACHER,
                gate.evaluate(discard,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
        assertEquals(
                SafetyGate.Decision.Kind.ASK_TEACHER,
                gate.evaluate(permanent,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void asksForTurkishIrreversibleDeleteVariants() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Çöp kutusuna taşı","",0.999,
                "Bu işlem geri dönülemez olabilir"
        );
        assertEquals(
                SafetyGate.Decision.Kind.ASK_TEACHER,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void doesNotBlockRecoveryBecauseReasonMentionsNoNewDesign() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Projects","",0.99,
                "open existing project; do not create a new design"
        );
        assertEquals(
                SafetyGate.Decision.Kind.ALLOW,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void doesNotTreatUserTextAsNewDesignNavigation() {
        AgentAction a = new AgentAction(
                AgentAction.Type.SET_TEXT,"Title","New Design Trends 2026",0.99,
                "enter requested title"
        );
        assertEquals(
                SafetyGate.Decision.Kind.ALLOW,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksTeacherActionAfterExecutionLeaseRotates() {
        TeacherExecutionLease.beginGlobal();
        AgentAction stale = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Resize","",0.99,"safe edit"
        );
        TeacherExecutionLease.beginGlobal();
        try {
            SafetyGate.Decision d = gate.evaluate(stale,running(false),AgentConstants.CANVA_PACKAGE);
            assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
            assertTrue(d.reason.toLowerCase().contains("execution lease"));
        } finally {
            TeacherExecutionLease.invalidateGlobal();
        }
    }

    @Test public void blocksExactNodeClickWhenEvidencedRowIsNotClickable() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_NODE,
                NodeTargetCodec.encode(7,"Template","android.widget.TextView","20 100 220 160","--"),
                "",0.999,"text child inside clickable card"
        );
        SafetyGate.Decision d = gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE);
        assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
        assertTrue(d.reason.contains("üst öğe"));
    }

    @Test public void allowsExactNodeClickOnlyWhenEvidencedRowIsClickable() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_NODE,
                NodeTargetCodec.encode(7,"Template","android.widget.Button","20 100 220 160","C-"),
                "",0.999,"exact clickable row"
        );
        assertEquals(
                SafetyGate.Decision.Kind.ALLOW,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksExactNodeSetTextWhenEvidencedRowIsNotEditable() {
        AgentAction a = new AgentAction(
                AgentAction.Type.SET_NODE_TEXT,
                NodeTargetCodec.encode(8,"Title","android.widget.TextView","20 180 500 240","C-"),
                "New title",0.999,"wrong capability"
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(a,running(false),AgentConstants.CANVA_PACKAGE).kind
        );
    }

    @Test public void blocksUiActionOnChatGptCompanionSurface() {
        AgentAction a = new AgentAction(
                AgentAction.Type.CLICK_TEXT,"Send","",0.999,"teacher reply visible"
        );
        SafetyGate.Decision d = gate.evaluate(a,running(false),AgentConstants.CHATGPT_PACKAGE);
        assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
        assertTrue(d.reason.contains("Canva"));
    }

    @Test public void blocksUiActionOnUnknownSurface() {
        AgentAction a = new AgentAction(
                AgentAction.Type.SET_TEXT,"Title","hello",0.999,"wrong app"
        );
        assertEquals(
                SafetyGate.Decision.Kind.BLOCK,
                gate.evaluate(a,running(false),"com.example.other").kind
        );
    }
}
