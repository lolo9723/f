package com.emrah.canvaapprentice;

/** Pure fail-closed policy for revalidating a captured teacher Send click target. */
final class TeacherSendClickEvidencePolicy {
    private TeacherSendClickEvidencePolicy() {}

    static boolean mayDispatch(boolean transportCurrent,
                               boolean nodePresent,
                               boolean visibleToUser,
                               boolean enabled,
                               boolean clickable,
                               String capturedIdentity,
                               String currentIdentity) {
        if (!transportCurrent || !nodePresent || !visibleToUser || !enabled || !clickable) return false;
        if (capturedIdentity == null || capturedIdentity.isEmpty()
                || currentIdentity == null || currentIdentity.isEmpty()) return false;
        return capturedIdentity.equals(currentIdentity);
    }
}
