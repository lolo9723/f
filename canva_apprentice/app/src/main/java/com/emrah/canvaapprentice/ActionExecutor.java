package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public final class ActionExecutor {
    private final AccessibilityService service;

    public ActionExecutor(AccessibilityService service) {
        this.service = service;
    }

    public boolean execute(AgentAction action) {
        switch (action.type) {
            case TAP_NORM:
                return tapNorm(action.target);
            case DRAG_NORM:
                return dragNorm(action.target);
            case BACK:
                return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case CLICK_TEXT:
            case SET_TEXT:
                AccessibilityNodeInfo root = service.getRootInActiveWindow();
                if (root == null) return false;
                if (action.type == AgentAction.Type.CLICK_TEXT) return clickByTextOrDescription(root, action.target);
                return setText(root, action.target, action.value);
            default:
                return false;
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

    private boolean tapNorm(String spec) {
        double[] v = parseCsv(spec, 2);
        if (v == null) return false;
        Rect b = displayBounds();
        float x = normToX(v[0], b);
        float y = normToY(v[1], b);
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, 80);
        return service.dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(),
                null, null
        );
    }

    private boolean dragNorm(String spec) {
        double[] v = parseCsv(spec, 5);
        if (v == null) return false;
        Rect b = displayBounds();
        float x1 = normToX(v[0], b);
        float y1 = normToY(v[1], b);
        float x2 = normToX(v[2], b);
        float y2 = normToY(v[3], b);
        long duration = Math.max(150L, Math.min(2000L, Math.round(v[4])));

        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(p, 0, duration);
        return service.dispatchGesture(
                new GestureDescription.Builder().addStroke(stroke).build(),
                null, null
        );
    }

    private Rect displayBounds() {
        WindowManager wm = service.getSystemService(WindowManager.class);
        WindowMetrics metrics = wm.getCurrentWindowMetrics();
        return metrics.getBounds();
    }

    private static float normToX(double n, Rect b) {
        float x = (float)(b.left + (n / 1000.0) * b.width());
        return Math.max(b.left + 1f, Math.min(b.right - 1f, x));
    }

    private static float normToY(double n, Rect b) {
        float y = (float)(b.top + (n / 1000.0) * b.height());
        return Math.max(b.top + 1f, Math.min(b.bottom - 1f, y));
    }

    private AccessibilityNodeInfo bestMatch(AccessibilityNodeInfo root, String target, boolean editableOnly) {
        String wanted = norm(target);
        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo partial = null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if ((!editableOnly || n.isEditable()) && n.isEnabled()) {
                String text = norm(n.getText());
                String desc = norm(n.getContentDescription());
                if (text.equals(wanted) || desc.equals(wanted)) return n;
                if (partial == null && !wanted.isEmpty() &&
                        (text.contains(wanted) || desc.contains(wanted))) partial = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return partial;
    }

    private static double[] parseCsv(String s, int n) {
        try {
            String[] p = s.split(",");
            if (p.length != n) return null;
            double[] out = new double[n];
            for (int i = 0; i < n; i++) out[i] = Double.parseDouble(p[i].trim());
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static String norm(CharSequence s) {
        return s == null ? "" : s.toString().trim().toLowerCase(Locale.ROOT);
    }
}
