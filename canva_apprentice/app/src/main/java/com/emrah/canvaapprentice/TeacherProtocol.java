package com.emrah.canvaapprentice;

public final class TeacherProtocol {
    private TeacherProtocol() {}

    public static String buildRequest(TaskState state, UiTreeSnapshot snapshot, String note) {
        return "CAA1_TEACHER_REQUEST\n" +
                "You are the teacher for a safety-first Canva Android apprentice agent.\n" +
                "Goal: " + state.goal + "\n" +
                "Step: " + state.step + "\n" +
                "NewDesignAllowed: " + state.allowNewDesign + "\n" +
                "ActivePackage: " + snapshot.packageName + "\n" +
                "DesignFingerprint: " + state.designFingerprint + "\n" +
                "Note: " + (note == null ? "" : note) + "\n" +
                "UI_TREE:\n" + snapshot.compactForTeacher() + "\n" +
                "Return ONLY one command, no prose:\n" +
                "CAA1|CLICK_TEXT|<visible text or content description>|<0..1 confidence>|<short reason>\n" +
                "CAA1|SET_TEXT|<field label/current text>|<text to enter>|<0..1 confidence>|<short reason>\n" +
                "CAA1|BACK|||<0..1 confidence>|<short reason>\n" +
                "CAA1|HUMAN|||1.0|<why human intervention is required>\n" +
                "CAA1|DONE|||1.0|<why goal is complete>\n" +
                "CAA1|NOOP|||1.0|<why no action is safe>\n" +
                "Never create a new design unless NewDesignAllowed=true. Never guess on password/CAPTCHA/payment/destructive actions.";
    }

    public static AgentAction parse(String raw) {
        if (raw == null) return new AgentAction(AgentAction.Type.NOOP,"","",0,"empty teacher reply");
        String line = null;
        for (String s : raw.split("\\R")) if (s.trim().startsWith("CAA1|")) line = s.trim();
        if (line == null) return new AgentAction(AgentAction.Type.NOOP,"","",0,"protocol marker missing");
        String[] p = line.split("\\|", 6);
        try {
            String cmd = p.length > 1 ? p[1] : "NOOP";
            switch (cmd) {
                case "CLICK_TEXT": return new AgentAction(AgentAction.Type.CLICK_TEXT, at(p,2), "", dbl(at(p,3)), at(p,4));
                case "SET_TEXT": return new AgentAction(AgentAction.Type.SET_TEXT, at(p,2), at(p,3), dbl(at(p,4)), at(p,5));
                case "BACK": return new AgentAction(AgentAction.Type.BACK,"","",dbl(at(p,4).trim().isEmpty()?at(p,3):at(p,4)), at(p,5));
                case "HUMAN": return new AgentAction(AgentAction.Type.HUMAN_TAKEOVER,"","",1.0, at(p,5));
                case "DONE": return new AgentAction(AgentAction.Type.DONE,"","",1.0, at(p,5));
                default: return new AgentAction(AgentAction.Type.NOOP,"","",1.0, at(p,5));
            }
        } catch (Exception e) { return new AgentAction(AgentAction.Type.NOOP,"","",0,"teacher protocol parse error"); }
    }

    private static String at(String[] p, int i) { return i < p.length ? p[i].trim() : ""; }
    private static double dbl(String s) { try { return Double.parseDouble(s); } catch(Exception e) { return 0; } }
}
