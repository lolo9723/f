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
            "sil", "delete", "trash", "remove page", "sayfayi sil", "tasarimi sil",
            "payment", "odeme", "purchase", "buy", "share externally", "hesabi sil"
    ));

    public Decision evaluate(AgentAction action, TaskState state, String activePackage) {
        if (state.mode != TaskState.Mode.RUNNING) return Decision.block("Ajan çalışma modunda değil.");
        if (!AgentConstants.ALLOWED_PACKAGES.contains(activePackage)) return Decision.block("İzin verilmeyen uygulama.");

        if (action.isCoordinateGesture()) {
            if (!action.visualGrounded) return Decision.block("Koordinat eylemi görsel öğretmen turundan gelmedi.");
            if (action.confidence < AgentConstants.SAFE_COORDINATE_CONFIDENCE) {
                return Decision.ask("Koordinat eylemi için güven yetersiz: " + action.confidence);
            }
        } else if (action.confidence < AgentConstants.SAFE_CLICK_CONFIDENCE &&
                action.type != AgentAction.Type.ASK_TEACHER) {
            return Decision.ask("Eylem güveni yetersiz: " + action.confidence);
        }

        String directTarget = normalize(action.target);
        String contextual = normalize(action.target + " " + action.value + " " + action.reason);

        if (!state.allowNewDesign) {
            boolean attemptsCreate =
                    (action.type == AgentAction.Type.CLICK_TEXT && containsAny(directTarget, NEW_DESIGN_PATTERNS)) ||
                    (action.isCoordinateGesture() && containsAny(normalize(action.reason), NEW_DESIGN_PATTERNS));
            if (attemptsCreate) {
                return Decision.block("Yeni tasarım oluşturma bu görev için kilitli.");
            }
        }

        if (containsAny(contextual, DESTRUCTIVE_PATTERNS)) {
            return Decision.ask("Yıkıcı/hassas işlem öğretmen veya kullanıcı doğrulaması gerektiriyor.");
        }

        if (action.type == AgentAction.Type.TAP_NORM && !validTap(action.target)) {
            return Decision.block("Normalize tap koordinatı geçersiz.");
        }
        if (action.type == AgentAction.Type.DRAG_NORM && !validDrag(action.target)) {
            return Decision.block("Normalize drag koordinatı geçersiz.");
        }
        return Decision.allow();
    }

    private static boolean validTap(String spec) {
        double[] v=parseCsv(spec,2);
        return v!=null && inNorm(v[0]) && inNorm(v[1]);
    }

    private static boolean validDrag(String spec) {
        double[] v=parseCsv(spec,5);
        if(v==null) return false;
        return inNorm(v[0])&&inNorm(v[1])&&inNorm(v[2])&&inNorm(v[3])&&v[4]>=150&&v[4]<=2000;
    }

    private static double[] parseCsv(String s,int n) {
        try {
            String[] p=s.split(",");
            if(p.length!=n) return null;
            double[] out=new double[n];
            for(int i=0;i<n;i++) out[i]=Double.parseDouble(p[i].trim());
            return out;
        } catch(Exception e) { return null; }
    }

    private static boolean inNorm(double x){ return x>=0 && x<=1000; }

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
