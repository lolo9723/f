package com.emrah.canvaapprentice;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds every teacher request to the safe-checkpoint generation and execution lease
 * that existed when the request was issued. If an asynchronous screenshot-backed
 * checkpoint commits while the teacher is thinking, the old reply must not be
 * treated as if it belonged to the newer task step/continuity authority.
 *
 * Exact-node actions additionally receive one-shot authority for the exact UI snapshot
 * that was shown to the teacher. The executor must consume that authority against the
 * current snapshot before resolving the compact node index.
 */
public final class CheckpointRequestGuard {
    private static final int MAX_PENDING_REQUESTS = 128;
    private static final int MAX_CONSUMED_SNAPSHOTS = 128;
    private static final Map<String, RequestLease> REQUESTS = new LinkedHashMap<>();
    private static final Map<String, String> CONSUMED_SNAPSHOTS = new LinkedHashMap<>();
    private static long checkpointGeneration = 0L;

    private CheckpointRequestGuard() {}

    public static synchronized void bind(String marker, String executionLeaseToken) {
        String m = normalize(marker);
        if (m.isEmpty()) return;

        // Marker reuse is ambiguous: an older in-flight teacher reply could arrive after
        // a newer request reused the same marker and otherwise borrow that newer request's
        // execution lease. Poison the marker instead of refreshing it. Whichever reply
        // arrives first will consume an invalid lease; any later duplicate also fails closed.
        if (REQUESTS.containsKey(m)) {
            REQUESTS.remove(m);
            REQUESTS.put(m, new RequestLease(-1L, "", "", false));
            evictOldestPendingRequests();
            return;
        }

        REQUESTS.put(m, new RequestLease(
                checkpointGeneration,
                normalize(executionLeaseToken),
                "",
                true
        ));
        evictOldestPendingRequests();
    }

    /**
     * Attaches the exact teacher-visible UI snapshot to an already-bound request marker.
     * Rebinding to a different snapshot is ambiguous and poisons the marker rather than
     * allowing a reply to borrow authority from a newer screen.
     */
    public static synchronized boolean bindSnapshot(String marker, String snapshotFingerprint) {
        String m = normalize(marker);
        String fingerprint = normalize(snapshotFingerprint);
        if (m.isEmpty() || fingerprint.isEmpty()) return false;

        RequestLease recorded = REQUESTS.get(m);
        if (recorded == null || !recorded.checkpointCurrent
                || recorded.executionLeaseToken.isEmpty()) {
            return false;
        }
        if (!recorded.snapshotFingerprint.isEmpty()
                && !recorded.snapshotFingerprint.equals(fingerprint)) {
            REQUESTS.put(m, new RequestLease(-1L, "", "", false));
            return false;
        }
        REQUESTS.put(m, new RequestLease(
                recorded.checkpointGeneration,
                recorded.executionLeaseToken,
                fingerprint,
                true
        ));
        return true;
    }

    /**
     * Returns only the execution lease owned by this exact, fully-grounded marker.
     * This is a non-consuming transport check: parsing still consumes the request later.
     * Never substitute the globally-current lease here; doing so would let delayed
     * transport for marker A borrow marker B's newer authority.
     */
    public static synchronized String currentBoundExecutionLease(String marker) {
        String m = normalize(marker);
        RequestLease recorded = REQUESTS.get(m);
        if (recorded == null
                || !recorded.checkpointCurrent
                || recorded.checkpointGeneration != checkpointGeneration
                || recorded.executionLeaseToken.isEmpty()
                || recorded.snapshotFingerprint.isEmpty()) {
            return "";
        }
        return recorded.executionLeaseToken;
    }

    public static synchronized RequestLease consume(String marker) {
        String m = normalize(marker);
        RequestLease recorded = REQUESTS.remove(m);
        if (recorded == null) {
            return new RequestLease(-1L, "", "", false);
        }
        boolean current = recorded.checkpointGeneration == checkpointGeneration
                && recorded.checkpointGeneration >= 0L
                && !recorded.executionLeaseToken.isEmpty();
        if (current && !recorded.snapshotFingerprint.isEmpty()) {
            rememberConsumedSnapshot(
                    recorded.executionLeaseToken,
                    recorded.snapshotFingerprint
            );
        }
        return new RequestLease(
                recorded.checkpointGeneration,
                recorded.executionLeaseToken,
                recorded.snapshotFingerprint,
                current
        );
    }

    /**
     * One-shot exact-node authority. A mismatch consumes the authority too, so an action
     * cannot wait for the UI to later drift back to an old fingerprint and then replay.
     */
    public static boolean consumeExecutionSnapshotIfMatches(
            String executionLeaseToken,
            String currentSnapshotFingerprint) {
        final String token = normalize(executionLeaseToken);
        final String current = normalize(currentSnapshotFingerprint);
        if (token.isEmpty() || current.isEmpty()) return false;
        return TeacherExecutionLease.withGlobalCurrent(token, false, () -> {
            synchronized (CheckpointRequestGuard.class) {
                String expected = CONSUMED_SNAPSHOTS.remove(token);
                return expected != null && !expected.isEmpty() && expected.equals(current);
            }
        });
    }

    public static synchronized void onCheckpointCommitted() {
        checkpointGeneration++;
        // Keep recorded requests until their replies arrive so stale replies retain their
        // ORIGINAL execution lease and cannot borrow a fresh post-checkpoint lease. Capacity
        // control happens on bind and evicts only the oldest abandoned requests; never clear
        // the entire table because that can invalidate a fresh in-flight request merely due
        // to unrelated historical churn.
    }

    private static void rememberConsumedSnapshot(String executionLeaseToken, String snapshotFingerprint) {
        String token = normalize(executionLeaseToken);
        String fingerprint = normalize(snapshotFingerprint);
        if (token.isEmpty() || fingerprint.isEmpty()) return;
        CONSUMED_SNAPSHOTS.remove(token);
        CONSUMED_SNAPSHOTS.put(token, fingerprint);
        while (CONSUMED_SNAPSHOTS.size() > MAX_CONSUMED_SNAPSHOTS) {
            Iterator<String> oldest = CONSUMED_SNAPSHOTS.keySet().iterator();
            if (!oldest.hasNext()) return;
            oldest.next();
            oldest.remove();
        }
    }

    private static void evictOldestPendingRequests() {
        while (REQUESTS.size() > MAX_PENDING_REQUESTS) {
            Iterator<String> oldest = REQUESTS.keySet().iterator();
            if (!oldest.hasNext()) return;
            oldest.next();
            oldest.remove();
        }
    }

    static synchronized long currentGenerationForTest() {
        return checkpointGeneration;
    }

    static synchronized int pendingRequestCountForTest() {
        return REQUESTS.size();
    }

    static synchronized int consumedSnapshotCountForTest() {
        return CONSUMED_SNAPSHOTS.size();
    }

    static synchronized void resetForTest() {
        REQUESTS.clear();
        CONSUMED_SNAPSHOTS.clear();
        checkpointGeneration = 0L;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class RequestLease {
        public final long checkpointGeneration;
        public final String executionLeaseToken;
        public final String snapshotFingerprint;
        public final boolean checkpointCurrent;

        RequestLease(long checkpointGeneration, String executionLeaseToken,
                     String snapshotFingerprint, boolean checkpointCurrent) {
            this.checkpointGeneration = checkpointGeneration;
            this.executionLeaseToken = executionLeaseToken == null ? "" : executionLeaseToken;
            this.snapshotFingerprint = snapshotFingerprint == null ? "" : snapshotFingerprint;
            this.checkpointCurrent = checkpointCurrent;
        }
    }
}
