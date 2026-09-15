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
            // A durable identity that current code would refuse to create must never regain authority
            // after process restore. Drop the untrusted identity and force explicit human re-entry
            // instead of silently continuing RUNNING against an ambiguous/spoofed Canva title.
            restoredAnchor = "";
            if (restoredMode == Mode.RUNNING) {
                restoredMode = Mode.HUMAN_TAKEOVER;
                restoredReason = "Kalıcı tasarım kimliği güvenli biçimde doğrulanamadı. Canva'daki mevcut tasarımı kontrol edip DEVAM ET'e bas.";
            }
        }
        this.designAnchor = restoredAnchor;
        this.lastSafeSnapshotHash = invalidPersistedAnchor ? "" : (lastSafeSnapshotHash == null ? "" : lastSafeSnapshotHash);
        this.humanReason = restoredReason;
        this.mode = restoredMode;
        this.allowNewDesign = allowNewDesign;
        this.step = Math.max(0, step);
    }
}
