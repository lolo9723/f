package com.emrah.canvaapprentice;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot guard for HUMAN_TAKEOVER resume callbacks. */
final class ResumeOnceGuard {
    private final AtomicBoolean consumed = new AtomicBoolean(false);

    boolean tryConsume() {
        return consumed.compareAndSet(false, true);
    }
}
