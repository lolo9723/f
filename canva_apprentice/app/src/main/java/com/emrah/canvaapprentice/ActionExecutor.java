package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public final class ActionExecutor {
    private final AccessibilityService service;
    public ActionExecutor(AccessibilityService service) { this.service = service; }

    public boolean execute(AgentAction action) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null) return false;
        switch (action.type) {
            case CLICK_TEXT: return clickByTextOrDescription(root, action.target);
            case SET_TEXT: return setText(root, action.target, action.value);
            case BACK: return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            default: return false;
        }
    }

    private boolean clickByTextOrDescription(AccessibilityNodeInfo root, String target) {
        AccessibilityNodeInfo match = bestMatch(root, target, false);
        if (match == null) return false;
        AccessibilityNodeInfo clickable = match;
        while (clickable != null && !clickable.isClickable()) clickable = clickable.getParent();
        return clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean setText(AccessibilityNodeInfo root, String target, String value) {
        AccessibilityNodeInfo match = bestMatch(root, target, true);
        if (match == null || !match.isEditable() || !match.isEnabled()) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return match.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo bestMatch(AccessibilityNodeInfo root, String target, boolean editableOnly) {
        String wanted = norm(target);
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>(); q.add(root);
        AccessibilityNodeInfo partial = null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if ((!editableOnly || n.isEditable()) && n.isEnabled()) {
                String text = norm(n.getText()); String desc = norm(n.getContentDescription());
                if (text.equals(wanted) || desc.equals(wanted)) return n;
                if (partial == null && !wanted.isEmpty() && (text.contains(wanted) || desc.contains(wanted))) partial = n;
            }
            for (int i=0;i<n.getChildCount();i++) { AccessibilityNodeInfo c=n.getChild(i); if(c!=null) q.add(c); }
        }
        return partial;
    }

    private static String norm(CharSequence s) { return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT); }
}
