package com.emrah.canvaapprentice;

public final class AgentAction {
    public enum Type {
        CLICK_TEXT, SET_TEXT,
        CLICK_NODE, SET_NODE_TEXT,
        BACK, TAP_NORM, DRAG_NORM,
        BIND_DESIGN,
        ASK_TEACHER, SCREENSHOT, HUMAN_TAKEOVER, DONE, NOOP
    }

    public final Type type;
    public final String target;
    public final String value;
    public final double confidence;
    public final String reason;
    public final boolean visualGrounded;
    public final String executionLeaseToken;

    public AgentAction(Type type, String target, String value, double confidence, String reason) {
        this(type,target,value,confidence,reason,false);
    }

    public AgentAction(Type type, String target, String value, double confidence,
                       String reason, boolean visualGrounded) {
        this.type = type;
        this.target = target == null ? "" : target;
        this.value = value == null ? "" : value;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.reason = reason == null ? "" : reason;
        this.visualGrounded = visualGrounded;
        // Every newly accepted/constructed action becomes the sole owner of the post-teacher
        // execution chain. If a newer reply is parsed before this one executes, its construction
        // rotates the global lease and this action will fail closed in the runtime gate.
        this.executionLeaseToken = TeacherExecutionLease.beginGlobal();
    }

    public boolean isCoordinateGesture() {
        return type == Type.TAP_NORM || type == Type.DRAG_NORM;
    }

    public boolean isNodeAction() {
        return type == Type.CLICK_NODE || type == Type.SET_NODE_TEXT;
    }
}
