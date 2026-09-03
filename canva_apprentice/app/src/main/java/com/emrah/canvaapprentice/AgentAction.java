package com.emrah.canvaapprentice;

public final class AgentAction {
    public enum Type {
        CLICK_TEXT, SET_TEXT, BACK,
        TAP_NORM, DRAG_NORM,
        BIND_DESIGN,
        ASK_TEACHER, SCREENSHOT, HUMAN_TAKEOVER, DONE, NOOP
    }

    public final Type type;
    public final String target;
    public final String value;
    public final double confidence;
    public final String reason;
    public final boolean visualGrounded;

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
    }

    public boolean isCoordinateGesture() {
        return type == Type.TAP_NORM || type == Type.DRAG_NORM;
    }
}
