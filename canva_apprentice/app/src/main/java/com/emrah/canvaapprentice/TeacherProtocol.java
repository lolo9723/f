package com.emrah.canvaapprentice;

public final class TeacherProtocol {
    private TeacherProtocol() {}

    public static String markerFor(String requestId) {
        return "CAA1_REPLY_" + requestId + "|";
    }

    public static String buildRequest(TaskState state, UiTreeSnapshot snapshot, String note, String requestId) {
        String continuity = state.designAnchor.isEmpty()
                ? "DesignAnchor: UNBOUND. If a unique existing design title/name is clearly visible, you MAY bind it with BIND_DESIGN before risky navigation.\n"
                : "DesignAnchor: " + state.designAnchor + "\n" +
                  "DESIGN CONTINUITY RULE: stay in this existing design. If Canva home/projects is shown, recover/open this design; never create a replacement.\n";

        return "CANVA_APPRENTICE_TEACHER_REQUEST\n" +
                "You are the teacher for a safety-first Canva Android apprentice agent.\n" +
                "RequestId: " + requestId + "\n" +
                "Goal: " + state.goal + "\n" +
                "Step: " + state.step + "\n" +
                "NewDesignAllowed: " + state.allowNewDesign + "\n" +
                continuity +
                "ActivePackage: " + snapshot.packageName + "\n" +
                "InitialDesignFingerprint: " + state.designFingerprint + "\n" +
                "Note: " + (note == null ? "" : note) + "\n" +
                "UI_TREE:\n" + snapshot.compactForTeacher() + "\n" +
                "Return ONLY one line, no markdown and no prose. " +
                "Construct the prefix by concatenating CAA1_REPLY_ + RequestId + | .\n" +
                "Formats (the literal <REQUEST_ID> below is only a placeholder):\n" +
                "CAA1_REPLY_<REQUEST_ID>|BIND_DESIGN|<exact unique visible design title>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|CLICK_TEXT|<visible text or content description>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SET_TEXT|<field label/current text>|<text to enter>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|BACK|||<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SCREENSHOT|||1.0|<why the UI tree is insufficient>\n" +
                "CAA1_REPLY_<REQUEST_ID>|HUMAN|||1.0|<why human intervention is required>\n" +
                "CAA1_REPLY_<REQUEST_ID>|DONE|||1.0|<why goal appears complete>\n" +
                "CAA1_REPLY_<REQUEST_ID>|NOOP|||1.0|<why no action is safe>\n" +
                "Reply on ONE physical line. Inside fields escape backslash as \\\\, pipe as \\|, newline as \\n, and tab as \\t. " +
                "BIND_DESIGN is memory-only; use it only when a non-generic unique design title is clearly visible and confidence >=0.98. " +
                "Coordinate gestures are FORBIDDEN in this structural turn. " +
                "If the requested target is visual and the UI tree does not uniquely identify it, request SCREENSHOT instead of guessing. " +
                "Never create a new design unless NewDesignAllowed=true. Never guess on password/CAPTCHA/payment/destructive actions. " +
                "Never navigate away from the current design merely to try something.";
    }

    public static String buildVisualRequest(TaskState state, UiTreeSnapshot snapshot,
                                            String requestId, String screenshotReason) {
        String continuity = state.designAnchor.isEmpty()
                ? "DesignAnchor: UNBOUND\n"
                : "DesignAnchor: " + state.designAnchor + "\nDESIGN CONTINUITY RULE: preserve this exact existing design.\n";

        return "CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST\n" +
                "RequestId: " + requestId + "\n" +
                "Goal: " + state.goal + "\n" +
                "Step: " + state.step + "\n" +
                "NewDesignAllowed: " + state.allowNewDesign + "\n" +
                continuity +
                "ReasonScreenshotWasRequested: " + screenshotReason + "\n" +
                "A screenshot of the CURRENT Canva screen is attached. " +
                "Use normalized coordinates from 0..1000 where (0,0)=top-left and (1000,1000)=bottom-right.\n" +
                "UI_TREE:\n" + snapshot.compactForTeacher() + "\n" +
                "Return ONLY one line, no markdown and no prose. Construct the prefix by concatenating CAA1_REPLY_ + RequestId + | .\n" +
                "Formats below use the literal <REQUEST_ID> placeholder; replace it with RequestId only in your reply:\n" +
                "CAA1_REPLY_<REQUEST_ID>|BIND_DESIGN|<exact unique visible design title>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|CLICK_TEXT|<visible text or content description>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SET_TEXT|<field label/current text>|<text>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|TAP_NORM|<x>,<y>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|DRAG_NORM|<x1>,<y1>,<x2>,<y2>,<durationMs>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|BACK|||<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|HUMAN|||1.0|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|DONE|||1.0|<why final visual quality is acceptable>\n" +
                "CAA1_REPLY_<REQUEST_ID>|NOOP|||1.0|<reason>\n" +
                "Reply on ONE physical line. Inside fields escape backslash as \\\\, pipe as \\|, newline as \\n, and tab as \\t. " +
                "Use TAP_NORM/DRAG_NORM only for visually grounded canvas selection/movement/resizing when the screenshot makes the target unambiguous. " +
                "For coordinate gestures confidence must be >=0.985. Never use coordinates for delete, payment, share, account, password, login, or destructive actions. " +
                "BIND_DESIGN requires a clearly visible unique non-generic design title and confidence >=0.98. " +
                "Do not request another screenshot in this same visual turn. Do not guess. " +
                "Never create a new design unless NewDesignAllowed=true.";
    }

    public static AgentAction parse(String raw, String marker) {
        return parse(raw, marker, false);
    }

    public static AgentAction parse(String raw, String marker, boolean visualGrounded) {
        if (raw == null) {
            return new AgentAction(AgentAction.Type.NOOP,"","",0,"empty teacher reply",visualGrounded);
        }

        String line = null;
        for (String s : raw.split("\\R")) {
            String t = s.trim();
            if (t.startsWith(marker)) line = t.substring(marker.length());
        }
        if (line == null) {
            return new AgentAction(AgentAction.Type.NOOP,"","",0,"unique protocol marker missing",visualGrounded);
        }

        String[] p = line.split("\\|", 5);
        try {
            String cmd = at(p,0);
            switch (cmd) {
                case "BIND_DESIGN":
                    return new AgentAction(AgentAction.Type.BIND_DESIGN,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded);
                case "CLICK_TEXT":
                    return new AgentAction(AgentAction.Type.CLICK_TEXT,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded);
                case "SET_TEXT":
                    return new AgentAction(AgentAction.Type.SET_TEXT,at(p,1),at(p,2),dbl(at(p,3)),at(p,4),visualGrounded);
                case "TAP_NORM":
                    return new AgentAction(AgentAction.Type.TAP_NORM,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded);
                case "DRAG_NORM":
                    return new AgentAction(AgentAction.Type.DRAG_NORM,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded);
                case "BACK":
                    return new AgentAction(AgentAction.Type.BACK,"","",dbl(at(p,3).isEmpty()?at(p,2):at(p,3)),at(p,4),visualGrounded);
                case "SCREENSHOT":
                    return new AgentAction(AgentAction.Type.SCREENSHOT,"","",1.0,at(p,4),visualGrounded);
                case "HUMAN":
                    return new AgentAction(AgentAction.Type.HUMAN_TAKEOVER,"","",1.0,at(p,4),visualGrounded);
                case "DONE":
                    return new AgentAction(AgentAction.Type.DONE,"","",1.0,at(p,4),visualGrounded);
                default:
                    return new AgentAction(AgentAction.Type.NOOP,"","",1.0,at(p,4),visualGrounded);
            }
        } catch (Exception e) {
            return new AgentAction(AgentAction.Type.NOOP,"","",0,"teacher protocol parse error",visualGrounded);
        }
    }

    private static String at(java.util.List<String> p, int i) { return i < p.size() ? p.get(i).trim() : ""; }
    private static double dbl(String s) {
        try { return Double.parseDouble(s); }
        catch(Exception e) { return 0; }
    }
}
