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
        this(type,target,value,confidence,reason,false,TeacherExecutionLease.currentGlobalToken());
    }

    public AgentAction(Type type, String target, String value, double confidence,
                       String reason, boolean visualGrounded) {
        this(type,target,value,confidence,reason,visualGrounded,TeacherExecutionLease.currentGlobalToken());
    }

    AgentAction(Type type, String target, String value, double confidence,
                String reason, boolean visualGrounded, String executionLeaseToken) {
        this.type = type;
        this.target = target == null ? "" : target;
        this.value = value == null ? "" : value;
        // Fail closed on malformed teacher confidence. Double.parseDouble accepts
        // NaN/Infinity and ordinary comparisons against NaN are false, which can
        // otherwise bypass minimum-confidence safety thresholds.
        this.confidence = Double.isFinite(confidence)
                ? Math.max(0.0, Math.min(1.0, confidence))
                : 0.0;
        this.reason = reason == null ? "" : reason;
        this.visualGrounded = visualGrounded;
        this.executionLeaseToken = executionLeaseToken == null ? "" : executionLeaseToken;
    }

    public boolean isCoordinateGesture() {
        return type == Type.TAP_NORM || type == Type.DRAG_NORM;
    }

    public boolean isNodeAction() {
        return type == Type.CLICK_NODE || type == Type.SET_NODE_TEXT;
    }
}
