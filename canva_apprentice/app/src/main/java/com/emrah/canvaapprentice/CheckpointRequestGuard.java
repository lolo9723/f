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
        String m = exactIdentity(marker);
        String token = exactIdentity(executionLeaseToken);
        if (!isCanonicalAuthorityIdentity(m) || !isCanonicalAuthorityIdentity(token)) return;

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
                token,
                "",
                true
        ));
        evictOldestPendingRequests();
    }

    /**
     * Creates the marker -> execution lease -> teacher-visible snapshot tuple in one
     * synchronized operation. New authority paths should use this instead of bind() followed
     * by bindSnapshot(), because a checkpoint commit or competing marker reuse between those
     * calls would otherwise expose a transient partially-grounded request.
     *
     * Authority-bearing identity is exact: never trim or normalize marker/lease/fingerprint.
     * If any layer supplies whitespace/control-contaminated identity, reject it before any
     * pending request is created so another layer cannot later interpret a different token.
     */
    public static synchronized boolean bindFullyGrounded(
            String marker,
            String executionLeaseToken,
            String snapshotFingerprint) {
        String m = exactIdentity(marker);
        String token = exactIdentity(executionLeaseToken);
        String fingerprint = exactIdentity(snapshotFingerprint);
        if (!isCanonicalAuthorityIdentity(m)
                || !isCanonicalAuthorityIdentity(token)
                || !isCanonicalAuthorityIdentity(fingerprint)) return false;

        if (REQUESTS.containsKey(m)) {
            REQUESTS.remove(m);
            REQUESTS.put(m, new RequestLease(-1L, "", "", false));
            evictOldestPendingRequests();
            return false;
        }

        REQUESTS.put(m, new RequestLease(
                checkpointGeneration,
                token,
                fingerprint,
                true
        ));
        evictOldestPendingRequests();
        return true;
    }

    /**
     * Attaches the exact teacher-visible UI snapshot to an already-bound request marker.
     * Rebinding to a different snapshot is ambiguous and poisons the marker rather than
     * allowing a reply to borrow authority from a newer screen.
     *
     * Legacy callers still reach this method through markerFor() -> buildRequest(). They may
     * only complete that split binding while the marker's original execution lease is still
     * globally current. A newer teacher request rotating the lease therefore makes the older
     * partial request permanently fail closed instead of allowing it to become fully grounded
     * after ownership has already moved on.
     */
    public static boolean bindSnapshot(String marker, String snapshotFingerprint) {
        final String m = exactIdentity(marker);
        final String fingerprint = exactIdentity(snapshotFingerprint);
        if (!isCanonicalAuthorityIdentity(m) || !isCanonicalAuthorityIdentity(fingerprint)) return false;

        final String expectedLease;
        synchronized (CheckpointRequestGuard.class) {
            RequestLease recorded = REQUESTS.get(m);
            if (recorded == null || !recorded.checkpointCurrent
                    || recorded.executionLeaseToken.isEmpty()) {
                return false;
            }
            expectedLease = recorded.executionLeaseToken;
        }

        return TeacherExecutionLease.withGlobalCurrent(expectedLease, false, () -> {
            synchronized (CheckpointRequestGuard.class) {
                RequestLease recorded = REQUESTS.get(m);
                if (recorded == null || !recorded.checkpointCurrent
                        || !expectedLease.equals(recorded.executionLeaseToken)
                        || recorded.checkpointGeneration != checkpointGeneration) {
                    if (recorded != null) {
                        REQUESTS.put(m, new RequestLease(-1L, "", "", false));
                    }
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
        });
    }

    /**
     * Atomically snapshots the complete authority owned by one exact structural marker.
     * Transport must capture this object once before any asynchronous work begins; reading
     * marker, lease and snapshot through separate calls would permit a checkpoint/race gap.
     */
    public static synchronized RequestLease currentBoundRequestLease(String marker) {
        String m = exactIdentity(marker);
        if (!isCanonicalAuthorityIdentity(m)) {
            return new RequestLease(-1L, "", "", false);
        }
        RequestLease recorded = REQUESTS.get(m);
        if (recorded == null
                || !recorded.checkpointCurrent
                || recorded.checkpointGeneration != checkpointGeneration
                || recorded.executionLeaseToken.isEmpty()
                || recorded.snapshotFingerprint.isEmpty()) {
            return new RequestLease(-1L, "", "", false);
        }
        return new RequestLease(
                recorded.checkpointGeneration,
                recorded.executionLeaseToken,
                recorded.snapshotFingerprint,
                true
        );
    }

    /**
     * Returns only the execution lease owned by this exact, fully-grounded marker.
     * This is a non-consuming transport check: parsing still consumes the request later.
     * Never substitute the globally-current lease here; doing so would let delayed
     * transport for marker A borrow marker B's newer authority.
     */
    public static synchronized String currentBoundExecutionLease(String marker) {
        return currentBoundRequestLease(marker).executionLeaseToken;
    }

    public static synchronized RequestLease consume(String marker) {
        String m = exactIdentity(marker);
        if (!isCanonicalAuthorityIdentity(m)) {
            return new RequestLease(-1L, "", "", false);
        }
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
     * Parser boundary for teacher replies. A reply is executable only if the marker still
     * owns the same checkpoint generation, execution lease AND a non-empty teacher-visible
     * snapshot, and that exact execution lease is still globally current.
     *
     * Lock order deliberately matches bindSnapshot/stillOwnsTransport: execution lease first,
     * then this guard. This closes the race where request A passes a checkpoint-only check,
     * request B rotates the global lease, and A is nevertheless parsed into an action. Stale
     * authority is consumed without publishing exact-node snapshot authority.
     */
    public static RequestLease consumeFullyGrounded(String marker) {
        final String m = exactIdentity(marker);
        if (!isCanonicalAuthorityIdentity(m)) {
            return new RequestLease(-1L, "", "", false);
        }

        final String expectedLease;
        synchronized (CheckpointRequestGuard.class) {
            RequestLease recorded = REQUESTS.get(m);
            if (recorded == null
                    || !recorded.checkpointCurrent
                    || recorded.checkpointGeneration != checkpointGeneration
                    || recorded.executionLeaseToken.isEmpty()
                    || recorded.snapshotFingerprint.isEmpty()) {
                if (recorded != null) REQUESTS.remove(m);
                return new RequestLease(-1L, "", "", false);
            }
            expectedLease = recorded.executionLeaseToken;
        }

        return TeacherExecutionLease.withGlobalCurrent(
                expectedLease,
                staleAndConsume(m),
                () -> {
                    synchronized (CheckpointRequestGuard.class) {
                        RequestLease recorded = REQUESTS.remove(m);
                        if (recorded == null
                                || !recorded.checkpointCurrent
                                || recorded.checkpointGeneration != checkpointGeneration
                                || !expectedLease.equals(recorded.executionLeaseToken)
                                || recorded.snapshotFingerprint.isEmpty()) {
                            return new RequestLease(-1L, "", "", false);
                        }
                        rememberConsumedSnapshot(
                                recorded.executionLeaseToken,
                                recorded.snapshotFingerprint
                        );
                        return new RequestLease(
                                recorded.checkpointGeneration,
                                recorded.executionLeaseToken,
                                recorded.snapshotFingerprint,
                                true
                        );
                    }
                }
        );
    }

    private static RequestLease staleAndConsume(String marker) {
        synchronized (CheckpointRequestGuard.class) {
            REQUESTS.remove(marker);
            return new RequestLease(-1L, "", "", false);
        }
    }

    /**
     * One-shot exact-node authority. A mismatch consumes the authority too, so an action
     * cannot wait for the UI to later drift back to an old fingerprint and then replay.
     */
    public static boolean consumeExecutionSnapshotIfMatches(
            String executionLeaseToken,
            String currentSnapshotFingerprint) {
        final String token = exactIdentity(executionLeaseToken);
        final String current = exactIdentity(currentSnapshotFingerprint);
        if (!isCanonicalAuthorityIdentity(token) || !isCanonicalAuthorityIdentity(current)) return false;
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
        String token = exactIdentity(executionLeaseToken);
        String fingerprint = exactIdentity(snapshotFingerprint);
        if (!isCanonicalAuthorityIdentity(token) || !isCanonicalAuthorityIdentity(fingerprint)) return;
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

    private static String exactIdentity(String value) {
        return value == null ? "" : value;
    }

    private static boolean isCanonicalAuthorityIdentity(String value) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) return false;
        }
        return true;
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
