package com.emrah.canvaapprentice;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.Arrays;

public final class SafetyGate {
    private static final Set<String> NEW_DESIGN_PATTERNS = new HashSet<>(Arrays.asList(
            "yeni tasarim", "tasarim olustur", "create a design", "new design", "create design"
    ));
    private static final Set<String> DESTRUCTIVE_PATTERNS = new HashSet<>(Arrays.asList(
            "sil", "delete", "trash", "remove page", "sayfayi sil", "tasarimi sil"
    ));

    public Decision evaluate(AgentAction action, TaskState state, String activePackage) {
        if (state.mode != TaskState.Mode.RUNNING) return Decision.block("Ajan çalışma modunda değil.");
        if (!AgentConstants.ALLOWED_PACKAGES.contains(activePackage)) return Decision.block("İzin verilmeyen uygulama.");
        if (action.confidence < AgentConstants.SAFE_CLICK_CONFIDENCE && action.type != AgentAction.Type.ASK_TEACHER) {
            return Decision.ask("Eylem güveni yetersiz: " + action.confidence);
        }
        String t = normalize(action.target + " " + action.value);
        if (!state.allowNewDesign && containsAny(t, NEW_DESIGN_PATTERNS)) {
            return Decision.block("Yeni tasarım oluşturma bu görev için kilitli.");
        }
        if (containsAny(t, DESTRUCTIVE_PATTERNS)) {
            return Decision.ask("Yıkıcı işlem öğretmen doğrulaması gerektiriyor.");
        }
        if (action.type == AgentAction.Type.COORDINATE_TAP && action.confidence < AgentConstants.SAFE_COORDINATE_CONFIDENCE) {
            return Decision.ask("Koordinat tıklaması için güven yetersiz.");
        }
        return Decision.allow();
    }

    private static boolean containsAny(String text, Set<String> patterns) {
        for (String p : patterns) if (text.contains(p)) return true;
        return false;
    }

    private static String normalize(String s) {
        String x = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        return x.replace('ı', 'i');
    }

    public static final class Decision {
        public enum Kind { ALLOW, BLOCK, ASK_TEACHER }
        public final Kind kind;
        public final String reason;
        private Decision(Kind kind, String reason) { this.kind = kind; this.reason = reason; }
        public static Decision allow() { return new Decision(Kind.ALLOW, ""); }
        public static Decision block(String r) { return new Decision(Kind.BLOCK, r); }
        public static Decision ask(String r) { return new Decision(Kind.ASK_TEACHER, r); }
    }
}
