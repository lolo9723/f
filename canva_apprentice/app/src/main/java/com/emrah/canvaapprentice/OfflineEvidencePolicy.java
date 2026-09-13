package com.emrah.canvaapprentice;

/**
 * Cross-checks Canva accessibility offline text against Android's validated network state.
 * Accessibility text alone is deliberately never authoritative because WebView/Compose trees
 * can retain stale banners after connectivity has recovered.
 */
public final class OfflineEvidencePolicy {
    public enum Verdict { ONLINE, OFFLINE_CONFIRMED, CONFLICT, UNKNOWN }

    private OfflineEvidencePolicy() {}

    public static Verdict classify(boolean offlineTextVisible, Boolean androidNetworkValidated) {
        if (Boolean.TRUE.equals(androidNetworkValidated)) {
            return offlineTextVisible ? Verdict.CONFLICT : Verdict.ONLINE;
        }
        if (Boolean.FALSE.equals(androidNetworkValidated)) {
            return offlineTextVisible ? Verdict.OFFLINE_CONFIRMED : Verdict.UNKNOWN;
        }
        return Verdict.UNKNOWN;
    }

    public static String teacherEvidence(boolean offlineTextVisible, Boolean androidNetworkValidated) {
        Verdict verdict = classify(offlineTextVisible, androidNetworkValidated);
        switch (verdict) {
            case ONLINE:
                return "CONNECTIVITY_EVIDENCE: Android reports a VALIDATED network and no visible Canva offline banner.";
            case OFFLINE_CONFIRMED:
                return "CONNECTIVITY_EVIDENCE: OFFLINE_CONFIRMED by both a visible Canva offline banner and Android reporting no validated network. Do not start network-dependent navigation.";
            case CONFLICT:
                return "CONNECTIVITY_EVIDENCE: CONFLICT. A visible Canva 'offline' accessibility node exists, but Android reports a VALIDATED network. Treat the Canva node as potentially stale; it MUST NOT by itself cause NOOP/HUMAN. If the next action depends on connectivity and current UI evidence is insufficient, request SCREENSHOT/revalidation.";
            default:
                return "CONNECTIVITY_EVIDENCE: UNKNOWN. Accessibility offline text alone is not proof of current connectivity. Do not declare offline solely from that node; request SCREENSHOT/revalidation when connectivity is material.";
        }
    }
}
