package com.emrah.canvaapprentice;

import java.util.HashMap;
import java.util.Map;

/**
 * Binds every teacher request to the safe-checkpoint generation and execution lease
 * that existed when the request was issued. If an asynchronous screenshot-backed
 * checkpoint commits while the teacher is thinking, the old reply must not be
 * treated as if it belonged to the newer task step/continuity authority.
 */
public final class CheckpointRequestGuard {
    private static final Map<String, RequestLease> REQUESTS = new HashMap<>();
    private static long checkpointGeneration = 0L;

    private CheckpointRequestGuard() {}

    public static synchronized void bind(String marker, String executionLeaseToken) {
        String m = normalize(marker);
        if (m.isEmpty()) return;
        REQUESTS.put(m, new RequestLease(
                checkpointGeneration,
                normalize(executionLeaseToken),
                true
        ));
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
        // Requests are intentionally retained until their reply is parsed so the parser
        // can recover using the ORIGINAL execution lease rather than accidentally adopting
        // a newer request's lease. Bound memory is naturally drained on parse; additionally
        // cap pathological abandoned-request accumulation fail-closed.
        if (REQUESTS.size() > 128) REQUESTS.clear();
    }

    static synchronized long currentGenerationForTest() {
        return checkpointGeneration;
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