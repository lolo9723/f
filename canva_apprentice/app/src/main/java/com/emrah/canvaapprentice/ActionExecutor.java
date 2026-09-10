package com.emrah.canvaapprentice;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityNodeInfo;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

public final class ActionExecutor {
    private final AccessibilityService service;
    private final TaskStateRepository stateRepo;

    public ActionExecutor(AccessibilityService service) {
        this.service = service;
        this.stateRepo = new TaskStateRepository(service);
    }

    public boolean execute(AgentAction action) {
        if (action == null) return false;
        // Keep ownership of the exact teacher execution lease from the final
        // revalidation through the actual Android mutation. A newer teacher request,
        // STOP, human takeover or DEVAM ET rotates/invalidates this same monitor;
        // therefore a stale chain can no longer pass a check and then dispatch after
        // losing authority in the check-to-act window.
        return TeacherExecutionLease.withGlobalCurrent(
                action.executionLeaseToken,
                false,
                () -> executeWithCurrentLease(action));
    }

    private boolean executeWithCurrentLease(AgentAction action) {
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return false;
        if (!AgentConstants.CANVA_PACKAGE.equals(root.getPackageName().toString())) return false;

        TaskState state = stateRepo.load();
        if (state.mode != TaskState.Mode.RUNNING) return false;
        UiTreeSnapshot snap = UiTreeSnapshot.capture(root);
        boolean anchorVisible = !state.designAnchor.isEmpty() && snap.containsText(state.designAnchor);
        String currentSnapshotHash = snap.stableFingerprint();
        boolean matchesLastSafeEditorSnapshot = !state.lastSafeSnapshotHash.isEmpty()
                && state.lastSafeSnapshotHash.equals(currentSnapshotHash);
        if (!DesignContinuityPolicy.allows(
                action, state.designAnchor, anchorVisible, snap.looksLikeCanvaHome(),
                matchesLastSafeEditorSnapshot)) {
            return false;
        }

        // Commit-boundary revalidation: the service may have proven the visual/tree context
        // immediately before calling us, but Accessibility UI can still change between that
        // proof and the actual mutation. Re-read the active window and persisted design state
        // here, immediately before dispatch, and fail closed on any drift.
        AccessibilityNodeInfo commitRoot = service.getRootInActiveWindow();
        if (commitRoot == null || commitRoot.getPackageName() == null) return false;
        TaskState commitState = stateRepo.load();
        UiTreeSnapshot commitSnap = UiTreeSnapshot.capture(commitRoot);
        String commitPackage = commitRoot.getPackageName().toString();
        if (!executionCommitContextMatches(
                commitPackage,
                currentSnapshotHash,
                commitSnap.stableFingerprint(),
                state.designAnchor,
                commitState.designAnchor,
                commitState.mode == TaskState.Mode.RUNNING)) {
            return false;
        }
        boolean commitAnchorVisible = !commitState.designAnchor.isEmpty()
                && commitSnap.containsText(commitState.designAnchor);
        boolean commitMatchesLastSafe = !commitState.lastSafeSnapshotHash.isEmpty()
                && commitState.lastSafeSnapshotHash.equals(commitSnap.stableFingerprint());
        if (!DesignContinuityPolicy.allows(
                action, commitState.designAnchor, commitAnchorVisible, commitSnap.looksLikeCanvaHome(),
                commitMatchesLastSafe)) {
            return false;
        }

        switch (action.type) {
            case TAP_NORM:
                return tapNorm(action.target);
            case DRAG_NORM:
                return dragNorm(action.target);
            case BACK:
                return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case CLICK_TEXT:
                return clickByTextOrDescription(commitRoot, action.target);
            case SET_TEXT:
                return setText(commitRoot, action.target, action.value);
            case CLICK_NODE:
                return clickExactNode(commitRoot, action.target);
            case SET_NODE_TEXT:
                return setExactNodeText(commitRoot, action.target, action.value);
            default:
                return false;
        }
    }

    static boolean executionCommitContextMatches(String currentPackage,
                                                 String expectedFingerprint,
                                                 String currentFingerprint,
                                                 String expectedDesignAnchor,
                                                 String currentDesignAnchor,
                                                 boolean running) {
        return running
                && AgentConstants.CANVA_PACKAGE.equals(currentPackage)
                && expectedFingerprint != null
                && !expectedFingerprint.isEmpty()
                && expectedFingerprint.equals(currentFingerprint)
                && expectedDesignAnchor != null
                && expectedDesignAnchor.equals(currentDesignAnchor);
    }

    private boolean clickExactNode(AccessibilityNodeInfo root, String encodedTarget) {
        AccessibilityNodeInfo node = verifiedCompactNode(root, encodedTarget);
        if (node == null || !node.isVisibleToUser() || !node.isEnabled() || !node.isClickable()) return false;
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean setExactNodeText(AccessibilityNodeInfo root, String encodedTarget, String value) {
        AccessibilityNodeInfo node = verifiedCompactNode(root, encodedTarget);
        if (node == null || !node.isVisibleToUser() || !node.isEditable() || !node.isEnabled()) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    private AccessibilityNodeInfo verifiedCompactNode(AccessibilityNodeInfo root, String encodedTarget) {
        int wantedIndex = NodeTargetCodec.index(encodedTarget);
        String expectedLabel = NodeTargetCodec.label(encodedTarget);
        if (wantedIndex < 0 || expectedLabel.trim().isEmpty()) return null;
        if (!NodeTargetCodec.hasStructuralEvidence(encodedTarget)) return null;

        int[] current = new int[]{0};
        AccessibilityNodeInfo found = findCompactNodeDepthFirst(root, wantedIndex, current, 0);
        if (found == null || !matchesStructuralEvidence(found, encodedTarget)) return null;

        String expected = norm(expectedLabel);
        String text = norm(found.getText());
        String desc = norm(found.getContentDescription());
        if ("empty".equals(expected)) {
            return text.isEmpty() && desc.isEmpty() ? found : null;
        }
        return expected.equals(text) || expected.equals(desc) ? found : null;
    }

    private boolean matchesStructuralEvidence(AccessibilityNodeInfo node, String encodedTarget) {
        if (!node.isVisibleToUser()) return false;
        if (!raw(node.getClassName()).trim().equals(NodeTargetCodec.className(encodedTarget))) return false;

        String expectedFlags = NodeTargetCodec.flags(encodedTarget);
        String actualFlags = (node.isClickable() ? "C" : "-") + (node.isEditable() ? "E" : "-");
        if (!actualFlags.equals(expectedFlags)) return false;

        Rect actual = new Rect();
        node.getBoundsInScreen(actual);
        Rect expected = parseBounds(NodeTargetCodec.bounds(encodedTarget));
        return exactNodeBoundsUsable(expected)
                && exactNodeBoundsUsable(actual)
                && boundsNear(expected, actual, 8);
    }

    static Rect parseBounds(String raw) {
        if (raw == null) return null;
        String[] p = raw.trim().split("\\s+");
        if (p.length != 4) return null;
        try {
            return new Rect(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                    Integer.parseInt(p[2]), Integer.parseInt(p[3]));
        } catch (Exception e) {
            return null;
        }
    }

    static boolean exactNodeBoundsUsable(Rect rect) {
        return rect != null && rect.right > rect.left && rect.bottom > rect.top;
    }

    static boolean boundsNear(Rect a, Rect b, int tolerancePx) {
        return a != null && b != null
                && Math.abs(a.left - b.left) <= tolerancePx
                && Math.abs(a.top - b.top) <= tolerancePx
                && Math.abs(a.right - b.right) <= tolerancePx
                && Math.abs(a.bottom - b.bottom) <= tolerancePx;
    }

    private AccessibilityNodeInfo findCompactNodeDepthFirst(
            AccessibilityNodeInfo node, int wantedIndex, int[] current, int depth) {
        if (node == null || depth > 60 || current[0] > 220) return null;

        if (isMeaningful(node)) {
            if (current[0] == wantedIndex) return node;
            current[0]++;
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findCompactNodeDepthFirst(child, wantedIndex, current, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean isMeaningful(AccessibilityNodeInfo node) {
        return !raw(node.getText()).trim().isEmpty()
                || !raw(node.getContentDescription()).trim().isEmpty()
                || node.isClickable()
                || node.isEditable();
    }

    private boolean clickByTextOrDescription(AccessibilityNodeInfo root, String target) {
        AccessibilityNodeInfo match = bestMatch(root, target, false);
        if (match == null) return false;
        if (!plainTextDirectClickAllowed(true, match.isVisibleToUser(), match.isEnabled(), match.isClickable())) {
            return false;
        }
        return match.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    static boolean plainTextDirectClickAllowed(boolean uniqueExactMatch,
                                               boolean visible,
                                               boolean enabled,
                                               boolean clickable) {
        return uniqueExactMatch && visible && enabled && clickable;
    }

    private boolean setText(AccessibilityNodeInfo root, String target, String value) {
        AccessibilityNodeInfo match = bestMatch(root, target, true);
        if (match == null) return false;
        if (!plainTextDirectSetAllowed(true, match.isVisibleToUser(), match.isEnabled(), match.isEditable())) {
            return false;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        return match.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    static boolean plainTextDirectSetAllowed(boolean uniqueExactMatch,
                                             boolean visible,
                                             boolean enabled,
                                             boolean editable) {
        return uniqueExactMatch && visible && enabled && editable;
    }

    private boolean tapNorm(String spec) {
        double[] v = parseCsv(spec, 2);
        if (v == null || !normalizedCoordinate(v[0]) || !normalizedCoordinate(v[1])) return false;
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
        if (v == null
                || !normalizedCoordinate(v[0]) || !normalizedCoordinate(v[1])
                || !normalizedCoordinate(v[2]) || !normalizedCoordinate(v[3])
                || !normalizedDuration(v[4])) return false;
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

    static boolean normalizedCoordinate(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1000.0;
    }

    static boolean normalizedDuration(double value) {
        return Double.isFinite(value) && value >= 150.0 && value <= 2000.0;
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
        if (wanted.isEmpty()) return null;

        Deque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        AccessibilityNodeInfo exact = null;
        int exactCount = 0;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.removeFirst();
            if ((!editableOnly || n.isEditable()) && n.isEnabled()) {
                String text = norm(n.getText());
                String desc = norm(n.getContentDescription());
                if (text.equals(wanted) || desc.equals(wanted)) {
                    exact = n;
                    exactCount++;
                    if (exactCount > 1) return null;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return exactCount == 1 ? exact : null;
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
        String x = Normalizer.normalize(raw(s), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace('ı','i');
        return x.replaceAll("\\s+"," ").trim();
    }

    private static String raw(CharSequence s) {
        return s == null ? "" : s.toString();
    }
}
