package com.emrah.canvaapprentice;

public final class TeacherProtocol {
    private TeacherProtocol() {}

    public static String markerFor(String requestId) {
        return "CAA1_REPLY_" + requestId + "|";
    }

    public static String buildRequest(TaskState state, UiTreeSnapshot snapshot, String note, String requestId) {
        return "CANVA_APPRENTICE_TEACHER_REQUEST\n" +
                "You are the teacher for a safety-first Canva Android apprentice agent.\n" +
                "RequestId: " + requestId + "\n" +
                "Goal: " + state.goal + "\n" +
                "Step: " + state.step + "\n" +
                "NewDesignAllowed: " + state.allowNewDesign + "\n" +
                "ActivePackage: " + snapshot.packageName + "\n" +
                "DesignFingerprint: " + state.designFingerprint + "\n" +
                "Note: " + (note == null ? "" : note) + "\n" +
                "UI_TREE:\n" + snapshot.compactForTeacher() + "\n" +
                "Return ONLY one line, no markdown and no prose. " +
                "Construct the prefix by concatenating CAA1_REPLY_ + RequestId + | .\n" +
                "Formats (the literal <REQUEST_ID> below is only a placeholder):\n" +
                "CAA1_REPLY_<REQUEST_ID>|CLICK_TEXT|<visible text or content description>|<0..1 confidence>|<short reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SET_TEXT|<field label/current text>|<text to enter>|<0..1 confidence>|<short reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|BACK|||<0..1 confidence>|<short reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SCREENSHOT|||1.0|<why the UI tree is insufficient>\n" +
                "CAA1_REPLY_<REQUEST_ID>|HUMAN|||1.0|<why human intervention is required>\n" +
                "CAA1_REPLY_<REQUEST_ID>|DONE|||1.0|<why goal is complete>\n" +
                "CAA1_REPLY_<REQUEST_ID>|NOOP|||1.0|<why no action is safe>\n" +
                "If the requested target is visual and the UI tree does not uniquely identify it, request SCREENSHOT instead of guessing. " +
                "Never create a new design unless NewDesignAllowed=true. Never guess on password/CAPTCHA/payment/destructive actions. " +
                "Never navigate away from the current Canva design merely to try something.";
    }

    public static String buildVisualRequest(TaskState state, UiTreeSnapshot snapshot, String requestId, String screenshotReason) {
        return "CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST\n" +
                "RequestId: " + requestId + "\n" +
                "Goal: " + state.goal + "\n" +
                "Step: " + state.step + "\n" +
                "NewDesignAllowed: " + state.allowNewDesign + "\n" +
                "ReasonScreenshotWasRequested: " + screenshotReason + "\n" +
                "A screenshot of the CURRENT Canva screen is attached. Use it together with this UI tree:\n" +
                snapshot.compactForTeacher() + "\n" +
                "Return ONLY one line beginning with CAA1_REPLY_" + requestId + "| and one of: " +
                "CLICK_TEXT, SET_TEXT, BACK, HUMAN, DONE, NOOP. " +
                "Do not request another screenshot in the same visual turn. " +
                "Do not guess. Never create a new design unless NewDesignAllowed=true.";
    }

    public static AgentAction parse(String raw, String marker) {
        if (raw == null) return new AgentAction(AgentAction.Type.NOOP,"","",0,"empty teacher reply");
        String line = null;
        for (String s : raw.split("\\R")) {
            String t = s.trim();
            if (t.startsWith(marker)) line = t.substring(marker.length());
        }
        if (line == null) return new AgentAction(AgentAction.Type.NOOP,"","",0,"unique protocol marker missing");
        String[] p = line.split("\\|", 5);
        try {
            String cmd = at(p,0);
            switch (cmd) {
                case "CLICK_TEXT": return new AgentAction(AgentAction.Type.CLICK_TEXT, at(p,1), "", dbl(at(p,2)), at(p,3));
                case "SET_TEXT": return new AgentAction(AgentAction.Type.SET_TEXT, at(p,1), at(p,2), dbl(at(p,3)), at(p,4));
                case "BACK": return new AgentAction(AgentAction.Type.BACK,"","",dbl(at(p,3).isEmpty()?at(p,2):at(p,3)), at(p,4));
                case "SCREENSHOT": return new AgentAction(AgentAction.Type.SCREENSHOT,"","",1.0, at(p,4));
                case "HUMAN": return new AgentAction(AgentAction.Type.HUMAN_TAKEOVER,"","",1.0, at(p,4));
                case "DONE": return new AgentAction(AgentAction.Type.DONE,"","",1.0, at(p,4));
                default: return new AgentAction(AgentAction.Type.NOOP,"","",1.0, at(p,4));
            }
        } catch (Exception e) {
            return new AgentAction(AgentAction.Type.NOOP,"","",0,"teacher protocol parse error");
        }
    }

    private static String at(String[] p, int i) { return i < p.length ? p[i].trim() : ""; }
    private static double dbl(String s) { try { return Double.parseDouble(s); } catch(Exception e) { return 0; } }
}
