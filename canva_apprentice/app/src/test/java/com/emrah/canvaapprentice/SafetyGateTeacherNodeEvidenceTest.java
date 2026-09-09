package com.emrah.canvaapprentice;

import org.junit.Test;
import static org.junit.Assert.*;

public class SafetyGateTeacherNodeEvidenceTest {
    private final SafetyGate gate = new SafetyGate();

    private TaskState running() {
        return new TaskState(
                "test goal","","","", "",
                TaskState.Mode.RUNNING,false,0
        );
    }

    @Test public void leasedTeacherClickNodeWithoutStructuralEvidenceFailsClosed() {
        TeacherExecutionLease.beginGlobal();
        try {
            AgentAction action = new AgentAction(
                    AgentAction.Type.CLICK_NODE,
                    NodeTargetCodec.encode(7,"Template"),"",0.999,"teacher exact-node click"
            );
            SafetyGate.Decision d = gate.evaluate(action,running(),AgentConstants.CANVA_PACKAGE);
            assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
            assertTrue(d.reason.contains("yapısal düğüm kanıtı"));
        } finally {
            TeacherExecutionLease.invalidateGlobal();
        }
    }

    @Test public void leasedTeacherSetNodeTextWithoutStructuralEvidenceFailsClosed() {
        TeacherExecutionLease.beginGlobal();
        try {
            AgentAction action = new AgentAction(
                    AgentAction.Type.SET_NODE_TEXT,
                    NodeTargetCodec.encode(8,"Title"),"New title",0.999,"teacher exact-node edit"
            );
            SafetyGate.Decision d = gate.evaluate(action,running(),AgentConstants.CANVA_PACKAGE);
            assertEquals(SafetyGate.Decision.Kind.BLOCK,d.kind);
            assertTrue(d.reason.contains("yapısal düğüm kanıtı"));
        } finally {
            TeacherExecutionLease.invalidateGlobal();
        }
    }

    @Test public void leasedTeacherClickNodeWithFullStructuralEvidenceStillPassesExactNodeGate() {
        TeacherExecutionLease.beginGlobal();
        try {
            AgentAction action = new AgentAction(
                    AgentAction.Type.CLICK_NODE,
                    NodeTargetCodec.encode(7,"Template","android.widget.Button","20 100 220 160","C-"),
                    "",0.999,"teacher exact-node click"
            );
            assertEquals(
                    SafetyGate.Decision.Kind.ALLOW,
                    gate.evaluate(action,running(),AgentConstants.CANVA_PACKAGE).kind
            );
        } finally {
            TeacherExecutionLease.invalidateGlobal();
        }
    }
}
