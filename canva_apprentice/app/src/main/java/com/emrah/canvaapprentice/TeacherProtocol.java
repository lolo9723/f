package com.emrah.canvaapprentice;

public final class TeacherProtocol {
    private TeacherProtocol() {}

    private static String markerText(String requestId) {
        return "CAA1_REPLY_" + requestId + "|";
    }

    public static String markerFor(String requestId) {
        String executionLeaseToken = TeacherExecutionLease.beginGlobal();
        String marker = markerText(requestId);
        CheckpointRequestGuard.bind(marker, executionLeaseToken);
        return marker;
    }

    public static String buildRequest(TaskState state, UiTreeSnapshot snapshot, String note, String requestId) {
        // Bind the exact teacher-visible tree to this request before it leaves the device.
        // Exact-node execution later consumes this fingerprint one time at the executor.
        CheckpointRequestGuard.bindSnapshot(markerText(requestId), snapshot.stableFingerprint());
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
                "LastSafeSnapshotFingerprint: " + state.lastSafeSnapshotHash + "\n" +
                "Note: " + (note == null ? "" : note) + "\n" +
                "UI_TREE:\n" + snapshot.compactForTeacher() + "\n" +
                "Each UI_TREE row is index|class|text|description|bounds|flags. For exact-node commands copy index, label, class, bounds, and flags from the SAME row.\n" +
                "Return ONLY one line, no markdown and no prose. Construct the prefix by concatenating CAA1_REPLY_ + RequestId + | .\n" +
                "CAA1_REPLY_<REQUEST_ID>|BIND_DESIGN|<exact unique visible design title>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|CLICK_NODE|<compact node index>|<exact row text or description>|<class>|<bounds>|<flags>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SET_NODE_TEXT|<compact node index>|<exact row text or description; empty only if unlabeled>|<class>|<bounds>|<flags>|<text>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|CLICK_TEXT|<visible text or content description>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SET_TEXT|<field label/current text>|<text to enter>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|BACK|||<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SCREENSHOT|||1.0|<why the UI tree is insufficient>\n" +
                "CAA1_REPLY_<REQUEST_ID>|HUMAN|||1.0|<why human intervention is required>\n" +
                "CAA1_REPLY_<REQUEST_ID>|DONE|||1.0|<why goal appears complete>\n" +
                "CAA1_REPLY_<REQUEST_ID>|NOOP|||1.0|<why no action is safe>\n" +
                "Reply on ONE physical line. Inside fields escape backslash as \\\\, pipe as \\|, newline as \\n, and tab as \\t. " +
                "Prefer CLICK_NODE/SET_NODE_TEXT whenever a suitable UI_TREE row exists. Plain CLICK_TEXT/SET_TEXT are fallback-only and require a unique visible label. " +
                "For CLICK_NODE/SET_NODE_TEXT, never invent or alter structural fields: copy index, class, bounds, flags and label from one current UI_TREE row. " +
                "BIND_DESIGN is memory-only; use it only when a non-generic unique design title is clearly visible and confidence >=0.98. " +
                "Coordinate gestures are FORBIDDEN in this structural turn. If the target is visual and UI tree is insufficient, request SCREENSHOT. " +
                "If the note says the user has just completed a human intervention, treat the current screen as untrusted until continuity is re-established. " +
                "When a DesignAnchor is bound, do not issue editing/navigation after human intervention unless UI tree clearly proves the same design; otherwise request SCREENSHOT. " +
                "Never create a new design unless NewDesignAllowed=true. Never guess on password/CAPTCHA/payment/destructive actions. Never navigate away merely to try something.";
    }

    public static String buildVisualRequest(TaskState state, UiTreeSnapshot snapshot,
                                            String requestId, String screenshotReason) {
        // The screenshot and compact UI tree are a single grounding unit. Preserve the
        // exact structural snapshot as one-shot authority for any returned node action.
        CheckpointRequestGuard.bindSnapshot(markerText(requestId), snapshot.stableFingerprint());
        String continuity = state.designAnchor.isEmpty()
                ? "DesignAnchor: UNBOUND\n"
                : "DesignAnchor: " + state.designAnchor + "\nDESIGN CONTINUITY RULE: preserve this exact existing design.\n";

        return "CANVA_APPRENTICE_VISUAL_TEACHER_REQUEST\n" +
                "RequestId: " + requestId + "\n" +
                "Goal: " + state.goal + "\n" +
                "Step: " + state.step + "\n" +
                "NewDesignAllowed: " + state.allowNewDesign + "\n" + continuity +
                "LastSafeSnapshotFingerprint: " + state.lastSafeSnapshotHash + "\n" +
                "ReasonScreenshotWasRequested: " + screenshotReason + "\n" +
                "A screenshot of the CURRENT Canva screen is attached. Use normalized coordinates 0..1000.\n" +
                "UI_TREE:\n" + snapshot.compactForTeacher() + "\n" +
                "Each UI_TREE row is index|class|text|description|bounds|flags. Exact-node commands must copy index, label, class, bounds and flags from the same row.\n" +
                "Return ONLY one line, no markdown and no prose. Construct the prefix by concatenating CAA1_REPLY_ + RequestId + | .\n" +
                "CAA1_REPLY_<REQUEST_ID>|BIND_DESIGN|<exact unique visible design title>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|CLICK_NODE|<compact node index>|<exact row text or description>|<class>|<bounds>|<flags>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SET_NODE_TEXT|<compact node index>|<exact row text or description; empty only if unlabeled>|<class>|<bounds>|<flags>|<text>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|CLICK_TEXT|<visible text or content description>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|SET_TEXT|<field label/current text>|<text>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|TAP_NORM|<x>,<y>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|DRAG_NORM|<x1>,<y1>,<x2>,<y2>,<durationMs>|<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|BACK|||<0..1 confidence>|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|HUMAN|||1.0|<reason>\n" +
                "CAA1_REPLY_<REQUEST_ID>|DONE|||1.0|<why final visual quality is acceptable>\n" +
                "CAA1_REPLY_<REQUEST_ID>|NOOP|||1.0|<reason>\n" +
                "Reply on ONE physical line. Inside fields escape backslash as \\\\, pipe as \\|, newline as \\n, and tab as \\t. " +
                "Prefer CLICK_NODE/SET_NODE_TEXT whenever a suitable UI_TREE row exists; copy all structural fields exactly and never invent them. " +
                "Use TAP_NORM/DRAG_NORM only for visually grounded canvas operations with confidence >=0.985. Never use coordinates for destructive/account/payment/login actions. " +
                "When a DesignAnchor is bound, visually verify that the screenshot belongs to that same existing design before any edit. If unclear, return HUMAN or NOOP. " +
                "Never create a new design unless NewDesignAllowed=true.";
    }

    public static AgentAction parse(String raw, String marker) { return parse(raw, marker, false); }

    /** Structural production path: keep the immutable request authority attached all the
     * way to the parser boundary instead of re-looking up whichever marker happens to be
     * current. The legacy marker overload remains for compatibility and focused tests. */
    public static AgentAction parse(String raw, TeacherRequestAuthority authority) {
        if (authority == null || !authority.isValid() || !authority.stillOwnsTransport()) {
            return action(AgentAction.Type.NOOP,"","",1.0,
                    "teacher request lost immutable transport authority; refresh from current state",
                    false,"");
        }
        final String expectedLease = authority.executionLeaseToken;
        AgentAction parsed = parse(raw, authority.marker, false);
        if (!expectedLease.equals(parsed.executionLeaseToken)) {
            return action(AgentAction.Type.NOOP,"","",1.0,
                    "teacher reply execution lease did not match immutable request authority",
                    false,"");
        }
        return parsed;
    }

    public static AgentAction parse(String raw, String marker, boolean visualGrounded) {
        CheckpointRequestGuard.RequestLease requestLease = CheckpointRequestGuard.consumeFullyGrounded(marker);
        final String executionLeaseToken = requestLease.executionLeaseToken;
        if (!requestLease.checkpointCurrent) {
            return action(AgentAction.Type.NOOP,"","",1.0,
                    "teacher request lost fully-grounded checkpoint authority; refresh from current state",
                    visualGrounded,executionLeaseToken);
        }
        if (raw == null) return action(AgentAction.Type.NOOP,"","",0,"empty teacher reply",visualGrounded,executionLeaseToken);
        String line = null;
        int markerMatches = 0;
        for (String s : raw.split("\\R")) {
            String t = s.trim();
            if (t.startsWith(marker)) {
                markerMatches++;
                if (markerMatches > 1) {
                    return action(AgentAction.Type.NOOP,"","",0,"ambiguous duplicate protocol marker",visualGrounded,executionLeaseToken);
                }
                line = t.substring(marker.length());
            }
        }
        if (line == null) return action(AgentAction.Type.NOOP,"","",0,"unique protocol marker missing",visualGrounded,executionLeaseToken);

        java.util.List<String> p = ProtocolCodec.splitEscaped(line);
        try {
            String cmd = at(p,0);
            switch (cmd) {
                case "BIND_DESIGN":
                    if (!arity(p,4)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.BIND_DESIGN,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded,executionLeaseToken);
                case "CLICK_NODE":
                    if (arity(p,8)) return action(AgentAction.Type.CLICK_NODE,
                            NodeTargetCodec.encode(integer(at(p,1)),nodeLabel(at(p,2)),at(p,3),at(p,4),at(p,5)),"",dbl(at(p,6)),at(p,7),visualGrounded,executionLeaseToken);
                    if (arity(p,5)) return action(AgentAction.Type.CLICK_NODE,NodeTargetCodec.encode(integer(at(p,1)),at(p,2)),"",dbl(at(p,3)),at(p,4),visualGrounded,executionLeaseToken);
                    return malformed(visualGrounded,executionLeaseToken);
                case "SET_NODE_TEXT":
                    if (arity(p,9)) return action(AgentAction.Type.SET_NODE_TEXT,
                            NodeTargetCodec.encode(integer(at(p,1)),nodeLabel(at(p,2)),at(p,3),at(p,4),at(p,5)),at(p,6),dbl(at(p,7)),at(p,8),visualGrounded,executionLeaseToken);
                    if (arity(p,6)) return action(AgentAction.Type.SET_NODE_TEXT,NodeTargetCodec.encode(integer(at(p,1)),nodeLabel(at(p,2))),at(p,3),dbl(at(p,4)),at(p,5),visualGrounded,executionLeaseToken);
                    return malformed(visualGrounded,executionLeaseToken);
                case "CLICK_TEXT":
                    if (!arity(p,4)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.CLICK_TEXT,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded,executionLeaseToken);
                case "SET_TEXT":
                    if (!arity(p,5)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.SET_TEXT,at(p,1),at(p,2),dbl(at(p,3)),at(p,4),visualGrounded,executionLeaseToken);
                case "TAP_NORM":
                    if (!arity(p,4)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.TAP_NORM,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded,executionLeaseToken);
                case "DRAG_NORM":
                    if (!arity(p,4)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.DRAG_NORM,at(p,1),"",dbl(at(p,2)),at(p,3),visualGrounded,executionLeaseToken);
                case "BACK":
                    if (arity(p,5)) return action(AgentAction.Type.BACK,"","",dbl(at(p,3)),at(p,4),visualGrounded,executionLeaseToken);
                    if (arity(p,4)) return action(AgentAction.Type.BACK,"","",dbl(at(p,2)),at(p,3),visualGrounded,executionLeaseToken);
                    return malformed(visualGrounded,executionLeaseToken);
                case "SCREENSHOT":
                    if (!arity(p,5)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.SCREENSHOT,"","",1.0,at(p,4),visualGrounded,executionLeaseToken);
                case "HUMAN":
                    if (!arity(p,5)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.HUMAN_TAKEOVER,"","",1.0,at(p,4),visualGrounded,executionLeaseToken);
                case "DONE": {
                    if (!arity(p,5)) return malformed(visualGrounded,executionLeaseToken);
                    double doneConfidence = dbl(at(p,3));
                    if (doneConfidence < 0.995) {
                        return action(AgentAction.Type.NOOP,"","",0,
                                "final done confidence below safety threshold",visualGrounded,executionLeaseToken);
                    }
                    return action(AgentAction.Type.DONE,"","",doneConfidence,at(p,4),visualGrounded,executionLeaseToken);
                }
                case "NOOP":
                    if (!arity(p,5)) return malformed(visualGrounded,executionLeaseToken);
                    return action(AgentAction.Type.NOOP,"","",1.0,at(p,4),visualGrounded,executionLeaseToken);
                default: return action(AgentAction.Type.NOOP,"","",1.0,"unknown teacher command",visualGrounded,executionLeaseToken);
            }
        } catch (Exception e) {
            return action(AgentAction.Type.NOOP,"","",0,"teacher protocol parse error",visualGrounded,executionLeaseToken);
        }
    }

    private static boolean arity(java.util.List<String> p, int expected) {
        return p != null && p.size() == expected;
    }

    private static AgentAction malformed(boolean visualGrounded, String executionLeaseToken) {
        return action(AgentAction.Type.NOOP,"","",0,
                "teacher protocol field count mismatch",visualGrounded,executionLeaseToken);
    }

    private static AgentAction action(AgentAction.Type type, String target, String value, double confidence,
                                      String reason, boolean visualGrounded, String executionLeaseToken) {
        return new AgentAction(type,target,value,confidence,reason,visualGrounded,executionLeaseToken);
    }

    private static String at(java.util.List<String> p, int i) { return i < p.size() ? p.get(i).trim() : ""; }
    private static double dbl(String s) {
        try {
            double value = Double.parseDouble(s);
            return Double.isFinite(value) && value >= 0.0 && value <= 1.0 ? value : 0;
        } catch(Exception e) {
            return 0;
        }
    }
    private static int integer(String s) {
        int v = Integer.parseInt(s.trim());
        if (v < 0 || v >= 220) throw new IllegalArgumentException("node index out of range");
        return v;
    }
    private static String nodeLabel(String s) { return s == null || s.trim().isEmpty() ? "empty" : s.trim(); }
}
