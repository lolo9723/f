package com.emrah.canvaapprentice;

public final class TaskState {
    public enum Mode { IDLE, RUNNING, HUMAN_TAKEOVER, STOPPED }

    public final String goal;
    public final String designFingerprint;
    public final String designAnchor;
    public final String lastSafeSnapshotHash;
    public final String humanReason;
    public final Mode mode;
    public final boolean allowNewDesign;
    public final int step;

    public TaskState(String goal, String designFingerprint, String designAnchor,
                     String lastSafeSnapshotHash, String humanReason,
                     Mode mode, boolean allowNewDesign, int step) {
        this.goal = goal == null ? "" : goal;
        this.designFingerprint = designFingerprint == null ? "" : designFingerprint;
        this.designAnchor = designAnchor == null ? "" : designAnchor;
        this.lastSafeSnapshotHash = lastSafeSnapshotHash == null ? "" : lastSafeSnapshotHash;
        this.humanReason = humanReason == null ? "" : humanReason;
        this.mode = mode == null ? Mode.IDLE : mode;
        this.allowNewDesign = allowNewDesign;
        this.step = Math.max(0, step);
    }
}
