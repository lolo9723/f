package com.emrah.canvaapprentice;

/**
 * Fail-closed identity check for async work owned by an AccessibilityService runtime.
 *
 * Android may destroy and recreate the service while a screenshot callback is still queued. A
 * callback from the old service must never be allowed to authorize state in the replacement
 * runtime, even when persisted task/session values happen to still match.
 */
public final class RuntimeOwnerPolicy {
    private RuntimeOwnerPolicy() {}

    public static boolean isCurrent(Object capturedOwner, Object currentOwner) {
        return capturedOwner != null && capturedOwner == currentOwner;
    }
}
