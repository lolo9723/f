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
}
