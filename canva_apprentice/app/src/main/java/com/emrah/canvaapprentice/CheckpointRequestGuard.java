package com.emrah.canvaapprentice;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Binds every teacher request to the safe-checkpoint generation and execution lease
 * that existed when the request was issued. If an asynchronous screenshot-backed
 * checkpoint commits while the teacher is thinking, the old reply must not be
 * treated as if it belonged to the newer task step/continuity authority.
 */
public final class CheckpointRequestGuard {
    private static final int MAX_PENDING_REQUESTS = 128;
    private static final Map<String, RequestLease> REQUESTS = new LinkedHashMap<>();
    private static long checkpointGeneration = 0L;

    private CheckpointRequestGuard() {}

    public static synchronized void bind(String marker, String executionLeaseToken) {
        String m = normalize(marker);
        if (m.isEmpty()) return;

        // A marker should be unique, but if a caller accidentally reuses one, refresh its
        // insertion position rather than leaving it eligible for eviction as an old request.
        REQUESTS.remove(m);
        REQUESTS.put(m, new RequestLease(
                checkpointGeneration,
                normalize(executionLeaseToken),
                true
        ));
        evictOldestPendingRequests();
    }

    public static synchronized RequestLease consume(String marker) {
        String m = normalize(marker);
        RequestLease recorded = REQUESTS.remove(m);
        if (recorded == null) {
            return new RequestLease(-1L, "", false);
        }
        return new RequestLease(
                recorded.checkpointGeneration,
                recorded.executionLeaseToken,
                recorded.checkpointGeneration == checkpointGeneration
        );
    }

    public static synchronized void onCheckpointCommitted() {
        checkpointGeneration++;
        // Keep recorded requests until their replies arrive so stale replies retain their
        // ORIGINAL execution lease and cannot borrow a fresh post-checkpoint lease. Capacity
        // control happens on bind and evicts only the oldest abandoned requests; never clear
        // the entire table because that can invalidate a fresh in-flight request merely due
        // to unrelated historical churn.
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

    static synchronized void resetForTest() {
        REQUESTS.clear();
        checkpointGeneration = 0L;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public static final class RequestLease {
        public final long checkpointGeneration;
        public final String executionLeaseToken;
        public final boolean checkpointCurrent;

        RequestLease(long checkpointGeneration, String executionLeaseToken, boolean checkpointCurrent) {
            this.checkpointGeneration = checkpointGeneration;
            this.executionLeaseToken = executionLeaseToken == null ? "" : executionLeaseToken;
            this.checkpointCurrent = checkpointCurrent;
        }
    }
}
