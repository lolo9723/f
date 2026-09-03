package com.emrah.canvaapprentice;

public final class TaskState {
    public enum Mode { IDLE, RUNNING, HUMAN_TAKEOVER, STOPPED }

    public final String goal;
    public final String designFingerprint;
    public final String lastSafeSnapshotHash;
    public final Mode mode;
    public final boolean allowNewDesign;
    public final int step;

    public TaskState(String goal, String designFingerprint, String lastSafeSnapshotHash,
                     Mode mode, boolean allowNewDesign, int step) {
        this.goal = goal == null ? "" : goal;
        this.designFingerprint = designFingerprint == null ? "" : designFingerprint;
        this.lastSafeSnapshotHash = lastSafeSnapshotHash == null ? "" : lastSafeSnapshotHash;
        this.mode = mode == null ? Mode.IDLE : mode;
        this.allowNewDesign = allowNewDesign;
        this.step = Math.max(0, step);
    }
}
