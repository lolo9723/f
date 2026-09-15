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
        String restoredAnchor = designAnchor == null ? "" : designAnchor;
        Mode restoredMode = mode == null ? Mode.IDLE : mode;
        String restoredReason = humanReason == null ? "" : humanReason;
        boolean invalidPersistedAnchor = !restoredAnchor.isEmpty()
                && !DesignAnchorPersistencePolicy.isPersistableAnchor(restoredAnchor);
        if (invalidPersistedAnchor) {
            // Never return an identity that current code would refuse to create. Process-level
            // restore safety is enforced durably by TaskStateRepository before TaskState is built:
            // a restored RUNNING task is first moved to HUMAN_TAKEOVER and its session/checkpoint
            // authority is rotated. After the user explicitly presses DEVAM ET, the repository
            // returns to RUNNING; keeping the stale invalid raw anchor quarantined here (as empty)
            // lets that explicit resume proceed UNBOUND so BIND_DESIGN must establish fresh live
            // Canva evidence. Forcing HUMAN_TAKEOVER again here would create an endless resume loop.
            restoredAnchor = "";
        }
        this.designAnchor = restoredAnchor;
        this.lastSafeSnapshotHash = invalidPersistedAnchor ? "" : (lastSafeSnapshotHash == null ? "" : lastSafeSnapshotHash);
        this.humanReason = restoredReason;
        this.mode = restoredMode;
        this.allowNewDesign = allowNewDesign;
        this.step = Math.max(0, step);
    }
}
