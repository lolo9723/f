package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class AgentAccessibilityService extends AccessibilityService {
    private static final String STATE_PREF = "yerel_agent_state";
    private static final String KEY_LEARNING = "learning";
    private static final String KEY_LEARNING_MODULE = "learning_module";
    private static final String KEY_TARGET_PACKAGE = "target_package";
    private static final String KEY_RUNNING = "running";
    private static final String KEY_RUNNING_MODULE = "running_module";
    private static final String KEY_RUNNING_TEXT = "running_text";
    private static final String KEY_RUNNING_FILES = "running_files";
    private static final String KEY_STEP_INDEX = "step_index";
    private static final String CAL_PREFIX = "calibration_";
    private static final String OWN_PACKAGE = "tr.edu.balikesir.anketrapor";

    private static final String[] DEFAULT_PACKAGES = new String[]{
            "com.android.chrome",
            "com.instagram.android",
            "com.canva.editor",
            "com.google.android.documentsui",
            "com.android.documentsui",
            OWN_PACKAGE
    };

    private SharedPreferences state;
    private SecureStore secure;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastLearningAt = 0L;
    private long lastRunAt = 0L;
    private boolean executing = false;

    private WindowManager windowManager;
    private LearningOverlay overlay;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayAdded = false;
    private boolean overlayTouchable = true;
    private Runnable resumeOverlayRunnable;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        state = getSharedPreferences(STATE_PREF, MODE_PRIVATE);
        secure = new SecureStore(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 60;
        info.packageNames = DEFAULT_PACKAGES;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        ensureStores();
        String pkg = safe(event.getPackageName());
        if (pkg.isEmpty()) return;

        if (state.getBoolean(KEY_LEARNING, false)) {
            String expected = state.getString(KEY_TARGET_PACKAGE, "");
            if (expected.equals(pkg)) {
                if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                    handleLearningTextEvent(event, pkg);
                }
                showLearningOverlay();
            } else if (OWN_PACKAGE.equals(pkg) || isDocumentsPackage(pkg)) {
                hideLearningOverlay();
            }
            return;
        }

        hideLearningOverlay();

        if (state.getBoolean(KEY_RUNNING, false)) {
            if (isDocumentsPackage(pkg)) {
                tryPendingFileSelection();
            } else if (!OWN_PACKAGE.equals(pkg)) {
                handleRun(pkg);
            }
        }
    }

    @Override
    public void onInterrupt() {
        hideLearningOverlay();
        ensureStores();
        state.edit().putBoolean(KEY_RUNNING, false).apply();
    }

    @Override
    public void onDestroy() {
        hideLearningOverlay();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void ensureStores() {
        if (state == null) state = getSharedPreferences(STATE_PREF, MODE_PRIVATE);
        if (secure == null) secure = new SecureStore(this);
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    // ---------- ÖĞRETME: gerçek dokunmayı yakala, kaydet, alttaki uygulamaya ilet ----------

    private void showLearningOverlay() {
        ensureStores();
        if (!state.getBoolean(KEY_LEARNING, false) || overlayAdded || windowManager == null) return;
        try {
            overlay = new LearningOverlay(this);
            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
            );
            overlayParams.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(overlay, overlayParams);
            overlayAdded = true;
            overlayTouchable = true;
        } catch (Exception ignored) {
            overlayAdded = false;
        }
    }

    private void hideLearningOverlay() {
        if (resumeOverlayRunnable != null) handler.removeCallbacks(resumeOverlayRunnable);
        resumeOverlayRunnable = null;
        if (!overlayAdded || windowManager == null || overlay == null) return;
        try {
            windowManager.removeViewImmediate(overlay);
        } catch (Exception ignored) {
        }
        overlayAdded = false;
        overlay = null;
        overlayParams = null;
    }

    private void setOverlayTouchable(boolean touchable) {
        if (!overlayAdded || overlayParams == null || overlay == null || windowManager == null) return;
        if (overlayTouchable == touchable) return;
        try {
            if (touchable) overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(overlay, overlayParams);
            overlayTouchable = touchable;
        } catch (Exception ignored) {
        }
    }

    private void pauseOverlayForTyping() {
        setOverlayTouchable(false);
        if (resumeOverlayRunnable != null) handler.removeCallbacks(resumeOverlayRunnable);
        resumeOverlayRunnable = () -> {
            if (state != null && state.getBoolean(KEY_LEARNING, false)
                    && state.getString(KEY_TARGET_PACKAGE, "").equals(currentActivePackage())) {
                setOverlayTouchable(true);
            }
        };
        handler.postDelayed(resumeOverlayRunnable, 1700L);
    }

    private final class LearningOverlay extends View {
        private float downX, downY;
        private long downAt;
        private final Paint pill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);

        LearningOverlay(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            pill.setColor(0xD91B1E23);
            label.setColor(Color.WHITE);
            label.setTextSize(dp(12));
            label.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float l = dp(12), t = dp(12), r = Math.min(getWidth() - dp(12), l + dp(270)), b = t + dp(38);
            canvas.drawRoundRect(l, t, r, b, dp(18), dp(18), pill);
            canvas.drawText("AJAN ÖĞRETİYOR • dokunarak ilerle", l + dp(14), t + dp(24), label);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e == null) return true;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = e.getRawX();
                downY = e.getRawY();
                downAt = SystemClock.uptimeMillis();
                return true;
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                learnGesture(downX, downY, e.getRawX(), e.getRawY(), Math.max(60L, SystemClock.uptimeMillis() - downAt));
                return true;
            }
            return true;
        }
    }

    private void learnGesture(float sx, float sy, float ex, float ey, long duration) {
        ensureStores();
        String expected = state.getString(KEY_TARGET_PACKAGE, "");
        if (!state.getBoolean(KEY_LEARNING, false) || (!expected.isEmpty() && !expected.equals(currentActivePackage()))) return;

        float dx = ex - sx, dy = ey - sy;
        float distance = (float) Math.sqrt(dx * dx + dy * dy);
        if (distance > dp(22)) {
            recordSwipe(sx, sy, ex, ey, duration, expected);
            forwardGesture(sx, sy, ex, ey, duration, false);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo node = null;
        boolean editable = false;
        try {
            if (root != null) node = findBestNodeAt(root, Math.round(ex), Math.round(ey));
            if (node != null) {
                String text = safe(node.getText());
                String desc = safe(node.getContentDescription());
                if (isProtectedFinal(text) || isProtectedFinal(desc)) {
                    toast("Son Yayınla / Paylaş / Gönder adımı kaydedilmedi ve çalıştırılmadı.");
                    return;
                }
                editable = node.isEditable() || safe(node.getClassName()).toLowerCase(Locale.ROOT).contains("edittext");
                if (editable) {
                    recordNodeStep("text", node, ex, ey, expected);
                } else {
                    recordNodeStep("click", node, ex, ey, expected);
                }
            } else {
                recordTap(ex, ey, expected);
            }
        } finally {
            if (node != null) node.recycle();
            if (root != null) root.recycle();
        }
        forwardGesture(ex, ey, ex, ey, 70L, editable);
    }

    private void handleLearningTextEvent(AccessibilityEvent event, String pkg) {
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) {
            pauseOverlayForTyping();
            return;
        }
        try {
            if (node.isEditable()) {
                JSONArray steps = calibration(state.getString(KEY_LEARNING_MODULE, ""));
                String sig = selectorSignature(node);
                if (!hasRecentTextSelector(steps, sig)) {
                    JSONObject step = nodeStep("text", node, centerX(node), centerY(node), pkg, steps);
                    step.put("signature", sig);
                    addLearningStep(steps, step);
                }
            }
        } catch (Exception ignored) {
        } finally {
            node.recycle();
            pauseOverlayForTyping();
        }
    }

    private boolean hasRecentTextSelector(JSONArray steps, String signature) {
        for (int i = Math.max(0, steps.length() - 4); i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s != null && "text".equals(s.optString("kind", ""))
                    && signature.equals(s.optString("signature", selectorSignature(s)))) return true;
        }
        return false;
    }

    private void recordNodeStep(String kind, AccessibilityNodeInfo node, float x, float y, String pkg) {
        try {
            JSONArray steps = calibration(state.getString(KEY_LEARNING_MODULE, ""));
            JSONObject step = nodeStep(kind, node, x, y, pkg, steps);
            step.put("signature", selectorSignature(node));
            if ("text".equals(kind) && hasRecentTextSelector(steps, selectorSignature(node))) return;
            addLearningStep(steps, step);
        } catch (Exception ignored) {
        }
    }

    private JSONObject nodeStep(String kind, AccessibilityNodeInfo node, float x, float y, String pkg, JSONArray steps) throws Exception {
        JSONObject step = new JSONObject();
        step.put("kind", kind);
        step.put("package", pkg);
        step.put("id", safe(node.getViewIdResourceName()));
        step.put("class", safe(node.getClassName()));
        step.put("path", buildPath(node));
        step.put("x", xRatio(x));
        step.put("y", yRatio(y));
        if ("text".equals(kind)) {
            step.put("label", "");
            step.put("desc", "");
            step.put("slot", nextTextSlot(steps));
        } else {
            step.put("label", trimForSelector(safe(node.getText())));
            step.put("desc", trimForSelector(safe(node.getContentDescription())));
        }
        return step;
    }

    private void recordTap(float x, float y, String pkg) {
        try {
            JSONArray steps = calibration(state.getString(KEY_LEARNING_MODULE, ""));
            JSONObject step = new JSONObject();
            step.put("kind", "tap"); step.put("package", pkg); step.put("x", xRatio(x)); step.put("y", yRatio(y));
            addLearningStep(steps, step);
        } catch (Exception ignored) {}
    }

    private void recordSwipe(float sx, float sy, float ex, float ey, long duration, String pkg) {
        try {
            JSONArray steps = calibration(state.getString(KEY_LEARNING_MODULE, ""));
            JSONObject step = new JSONObject();
            step.put("kind", "swipe"); step.put("package", pkg);
            step.put("sx", xRatio(sx)); step.put("sy", yRatio(sy)); step.put("ex", xRatio(ex)); step.put("ey", yRatio(ey));
            step.put("duration", Math.max(120L, Math.min(duration, 1600L)));
            addLearningStep(steps, step);
        } catch (Exception ignored) {}
    }

    private void addLearningStep(JSONArray steps, JSONObject step) {
        long now = SystemClock.uptimeMillis();
        if (now - lastLearningAt < 100L) return;
        steps.put(step);
        secure.put(CAL_PREFIX + state.getString(KEY_LEARNING_MODULE, ""), steps.toString());
        lastLearningAt = now;
        if (overlay != null) overlay.invalidate();
    }

    private int nextTextSlot(JSONArray steps) {
        int count = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s != null && "text".equals(s.optString("kind", ""))) count++;
        }
        return count;
    }

    private AccessibilityNodeInfo findBestNodeAt(AccessibilityNodeInfo root, int x, int y) {
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo bestAction = null, bestAny = null;
        long bestActionArea = Long.MAX_VALUE, bestAnyArea = Long.MAX_VALUE;
        for (int i = 0; i < q.size() && i < 900; i++) {
            AccessibilityNodeInfo n = q.get(i);
            Rect r = new Rect(); n.getBoundsInScreen(r);
            if (r.contains(x, y) && n.isVisibleToUser()) {
                long area = Math.max(1L, (long) Math.max(1, r.width()) * Math.max(1, r.height()));
                if (area <= bestAnyArea) {
                    if (bestAny != null) bestAny.recycle();
                    bestAny = AccessibilityNodeInfo.obtain(n); bestAnyArea = area;
                }
                boolean actionable = n.isEditable() || n.isClickable() || n.isFocusable()
                        || !safe(n.getText()).isEmpty() || !safe(n.getContentDescription()).isEmpty();
                if (actionable && area <= bestActionArea) {
                    if (bestAction != null) bestAction.recycle();
                    bestAction = AccessibilityNodeInfo.obtain(n); bestActionArea = area;
                }
            }
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo child = n.getChild(c); if (child != null) q.add(child);
            }
        }
        recycleList(q);
        if (bestAction != null) { if (bestAny != null) bestAny.recycle(); return bestAction; }
        return bestAny;
    }

    // ---------- ÇALIŞTIRMA ----------

    private void handleRun(String pkg) {
        if (executing) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRunAt < 220L) return;
        ensureStores();

        String module = state.getString(KEY_RUNNING_MODULE, "");
        JSONArray steps = calibration(module);
        int index = state.getInt(KEY_STEP_INDEX, 0);
        if (index >= steps.length()) {
            stopRun("prepared");
            toast("Görev hazır. Son Yayınla / Paylaş / Gönder dokunuşu sende.");
            return;
        }
        JSONObject step = steps.optJSONObject(index);
        if (step == null) { advance(index); return; }
        String expectedPkg = step.optString("package", "");
        if (!expectedPkg.isEmpty() && !expectedPkg.equals(pkg)) return;

        String kind = step.optString("kind", "click");
        if ("tap".equals(kind)) {
            runGesture(step.optDouble("x", .5), step.optDouble("y", .5), step.optDouble("x", .5), step.optDouble("y", .5), 70L, index);
            return;
        }
        if ("swipe".equals(kind)) {
            runGesture(step.optDouble("sx", .5), step.optDouble("sy", .7), step.optDouble("ex", .5), step.optDouble("ey", .3), step.optLong("duration", 350L), index);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo target = null;
        try {
            target = findNode(root, step);
            if (target == null) {
                int fx = screenX(step.optDouble("x", -.1)), fy = screenY(step.optDouble("y", -.1));
                if (fx >= 0 && fy >= 0) target = findBestNodeAt(root, fx, fy);
            }

            if ("text".equals(kind)) {
                if (target == null) return;
                AccessibilityNodeInfo editable = editableNode(target);
                if (editable == null) return;
                String taskText = secure.get(KEY_RUNNING_TEXT, "");
                String value = valueForSlot(module, step.optInt("slot", 0), taskText);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                boolean ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                if (editable != target) editable.recycle();
                if (ok) advance(index);
                return;
            }

            if (target != null) {
                if (isProtectedFinal(safe(target.getText())) || isProtectedFinal(safe(target.getContentDescription()))) {
                    stopRun("protected-final");
                    toast("Son kritik düğmeye dokunmadım. Devam sende.");
                    return;
                }
                AccessibilityNodeInfo clickable = clickableAncestor(target);
                boolean ok = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (clickable != null && clickable != target) clickable.recycle();
                if (ok) { advance(index); return; }
            }

            double rx = step.optDouble("x", -.1), ry = step.optDouble("y", -.1);
            if (rx >= 0 && ry >= 0) runGesture(rx, ry, rx, ry, 70L, index);
        } finally {
            if (target != null) target.recycle();
            root.recycle();
        }
    }

    private void advance(int index) {
        state.edit().putInt(KEY_STEP_INDEX, index + 1).apply();
        lastRunAt = SystemClock.uptimeMillis();
        executing = false;
        handler.postDelayed(this::continueRun, 620L);
    }

    private void continueRun() {
        ensureStores();
        if (!state.getBoolean(KEY_RUNNING, false)) return;
        String pkg = currentActivePackage();
        if (pkg.isEmpty() || OWN_PACKAGE.equals(pkg)) return;
        if (isDocumentsPackage(pkg)) tryPendingFileSelection(); else handleRun(pkg);
    }

    private void runGesture(double sx, double sy, double ex, double ey, long duration, int index) {
        int x1 = screenX(sx), y1 = screenY(sy), x2 = screenX(ex), y2 = screenY(ey);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return;
        executing = true;
        dispatchGesturePixels(x1, y1, x2, y2, duration, new GestureDescription.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { advance(index); }
            @Override public void onCancelled(GestureDescription gestureDescription) { executing = false; handler.postDelayed(AgentAccessibilityService.this::continueRun, 500L); }
        });
    }

    private AccessibilityNodeInfo editableNode(AccessibilityNodeInfo n) {
        if (n.isEditable()) return AccessibilityNodeInfo.obtain(n);
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(n));
        for (int i = 0; i < q.size() && i < 120; i++) {
            AccessibilityNodeInfo cur = q.get(i);
            if (cur.isEditable()) { AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(cur); recycleList(q); return result; }
            for (int c = 0; c < cur.getChildCount(); c++) { AccessibilityNodeInfo ch = cur.getChild(c); if (ch != null) q.add(ch); }
        }
        recycleList(q); return null;
    }

    // ---------- gesture iletimi ----------

    private void forwardGesture(float sx, float sy, float ex, float ey, long duration, boolean typing) {
        setOverlayTouchable(false);
        dispatchGesturePixels(sx, sy, ex, ey, duration, new GestureDescription.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) {
                if (typing) pauseOverlayForTyping();
                else handler.postDelayed(() -> {
                    if (state != null && state.getBoolean(KEY_LEARNING, false)
                            && state.getString(KEY_TARGET_PACKAGE, "").equals(currentActivePackage())) setOverlayTouchable(true);
                }, 130L);
            }
            @Override public void onCancelled(GestureDescription gestureDescription) { handler.postDelayed(() -> setOverlayTouchable(true), 130L); }
        });
    }

    private void dispatchGesturePixels(float sx, float sy, float ex, float ey, long duration, GestureDescription.GestureResultCallback callback) {
        Path p = new Path(); p.moveTo(sx, sy);
        if (Math.abs(ex - sx) > 2 || Math.abs(ey - sy) > 2) p.lineTo(ex, ey);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, Math.max(50L, duration));
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), callback, null);
    }

    // ---------- seçiciler ----------

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, JSONObject step) {
        String id = step.optString("id", "");
        if (!id.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
                if (found != null && !found.isEmpty()) { AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(found.get(0)); recycleList(found); return result; }
                recycleList(found);
            } catch (Exception ignored) {}
        }
        String label = step.optString("label", "");
        if (!label.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
                if (found != null) {
                    for (AccessibilityNodeInfo n : found) if (label.equals(safe(n.getText()))) { AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(n); recycleList(found); return result; }
                }
                recycleList(found);
            } catch (Exception ignored) {}
        }
        String desc = step.optString("desc", "");
        if (!desc.isEmpty()) {
            AccessibilityNodeInfo byDesc = bfsByDescription(root, desc); if (byDesc != null) return byDesc;
        }
        AccessibilityNodeInfo byPath = traversePath(root, step.optString("path", ""));
        if (byPath != null) {
            String clazz = step.optString("class", "");
            if (clazz.isEmpty() || clazz.equals(safe(byPath.getClassName()))) return byPath;
            byPath.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo bfsByDescription(AccessibilityNodeInfo root, String desc) {
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 700; i++) {
            AccessibilityNodeInfo n = q.get(i);
            if (desc.equals(safe(n.getContentDescription()))) { AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(n); recycleList(q); return result; }
            for (int c = 0; c < n.getChildCount(); c++) { AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch); }
        }
        recycleList(q); return null;
    }

    private AccessibilityNodeInfo traversePath(AccessibilityNodeInfo root, String path) {
        if (path == null || path.isEmpty()) return null;
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(root);
        try {
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                AccessibilityNodeInfo child = current.getChild(Integer.parseInt(part)); current.recycle(); current = child;
                if (current == null) return null;
            }
            AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(current); current.recycle(); return result;
        } catch (Exception e) { if (current != null) current.recycle(); return null; }
    }

    private String buildPath(AccessibilityNodeInfo node) {
        ArrayList<Integer> rev = new ArrayList<>(); AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int guard = 0; current != null && guard < 40; guard++) {
                AccessibilityNodeInfo parent = current.getParent(); if (parent == null) break;
                int idx = childIndex(parent, current); if (idx < 0) { parent.recycle(); break; }
                rev.add(idx); current.recycle(); current = parent;
            }
        } finally { if (current != null) current.recycle(); }
        Collections.reverse(rev); StringBuilder b = new StringBuilder();
        for (Integer i : rev) { if (b.length() > 0) b.append('/'); b.append(i); }
        return b.toString();
    }

    private int childIndex(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo c = parent.getChild(i);
            if (c != null) { boolean same = c.equals(child); c.recycle(); if (same) return i; }
        }
        return -1;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.isClickable()) return current;
            AccessibilityNodeInfo parent = current.getParent(); current.recycle(); current = parent;
        }
        if (current != null) current.recycle(); return null;
    }

    // ---------- dosya seçici ----------

    private void tryPendingFileSelection() {
        if (executing) return;
        long now = SystemClock.uptimeMillis(); if (now - lastRunAt < 300L) return;
        ensureStores(); JSONArray files;
        try { files = new JSONArray(secure.get(KEY_RUNNING_FILES, "[]")); } catch (Exception e) { return; }
        if (files.length() == 0) { handler.postDelayed(this::continueRun, 400L); return; }
        String name = files.optString(0, ""); if (name.isEmpty()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow(); if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(name);
            if (nodes == null) return;
            for (AccessibilityNodeInfo node : nodes) {
                if (name.equals(safe(node.getText()))) {
                    AccessibilityNodeInfo clickable = clickableAncestor(node);
                    if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        JSONArray next = new JSONArray(); for (int i = 1; i < files.length(); i++) next.put(files.optString(i));
                        secure.put(KEY_RUNNING_FILES, next.toString()); lastRunAt = now;
                        clickable.recycle(); handler.postDelayed(this::continueRun, 650L); break;
                    }
                    if (clickable != null) clickable.recycle();
                }
            }
            recycleList(nodes);
        } finally { root.recycle(); }
    }

    // ---------- güvenlik / veri ----------

    private JSONArray calibration(String module) {
        ensureStores();
        try { return new JSONArray(secure.get(CAL_PREFIX + module, "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private void stopRun(String reason) {
        executing = false;
        state.edit().putBoolean(KEY_RUNNING, false).putString("last_run_result", reason).apply();
        secure.remove(KEY_RUNNING_TEXT); secure.remove(KEY_RUNNING_FILES);
    }

    private boolean isDocumentsPackage(String pkg) {
        return "com.google.android.documentsui".equals(pkg) || "com.android.documentsui".equals(pkg);
    }

    private boolean isProtectedFinal(String value) {
        String n = normalize(value); if (n.isEmpty()) return false;
        String[] blocked = {"yayinla","paylas","gonder","guncelle","publish","share","send","simdi paylas","hikayende paylas","share now","publish now"};
        for (String b : blocked) if (n.equals(b)) return true;
        return false;
    }

    private String valueForSlot(String module, int slot, String taskText) {
        if (taskText == null) taskText = "";
        if (module.startsWith("instagram_")) return taskText;
        if (module.startsWith("baun_") || module.startsWith("canva_")) {
            if (slot == 0) return firstNonEmptyLine(taskText);
            if (slot == 1) return bodyWithoutFirstLine(taskText);
        }
        return taskText;
    }

    private String firstNonEmptyLine(String text) {
        for (String line : text.split("\\R")) { String s = line.trim(); if (!s.isEmpty()) return s.length() > 180 ? s.substring(0, 180) : s; }
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    private String bodyWithoutFirstLine(String text) {
        String[] lines = text.split("\\R", -1); boolean skipped = false; StringBuilder b = new StringBuilder();
        for (String line : lines) {
            if (!skipped && !line.trim().isEmpty()) { skipped = true; continue; }
            if (skipped) { if (b.length() > 0) b.append('\n'); b.append(line); }
        }
        String out = b.toString().trim(); return out.isEmpty() ? text : out;
    }

    private String normalize(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(new Locale("tr","TR"));
        x = x.replace('ı','i').replace('ş','s').replace('ğ','g').replace('ü','u').replace('ö','o').replace('ç','c');
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String trimForSelector(String s) { if (s == null) return ""; s = s.trim(); return s.length() <= 120 ? s : s.substring(0,120); }
    private static String safe(CharSequence c) { return c == null ? "" : c.toString(); }
    private static void recycleList(List<AccessibilityNodeInfo> list) { if (list != null) for (AccessibilityNodeInfo n : list) if (n != null) n.recycle(); }

    private String selectorSignature(AccessibilityNodeInfo n) {
        return safe(n.getViewIdResourceName()) + "|" + safe(n.getClassName()) + "|" + buildPath(n);
    }

    private String selectorSignature(JSONObject s) {
        return s.optString("id","") + "|" + s.optString("class","") + "|" + s.optString("path","");
    }

    private float centerX(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r.exactCenterX(); }
    private float centerY(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r.exactCenterY(); }
    private double xRatio(float x) { return Math.max(0d, Math.min(1d, x / Math.max(1f, getResources().getDisplayMetrics().widthPixels))); }
    private double yRatio(float y) { return Math.max(0d, Math.min(1d, y / Math.max(1f, getResources().getDisplayMetrics().heightPixels))); }
    private int screenX(double ratio) { if (ratio < 0) return -1; return (int)Math.round(Math.max(0d, Math.min(1d, ratio)) * getResources().getDisplayMetrics().widthPixels); }
    private int screenY(double ratio) { if (ratio < 0) return -1; return (int)Math.round(Math.max(0d, Math.min(1d, ratio)) * getResources().getDisplayMetrics().heightPixels); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String currentActivePackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        try { return safe(root.getPackageName()); } finally { root.recycle(); }
    }

    private void toast(String s) { handler.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // ---------- MainActivity tarafından çağrılan statik API ----------

    public static void beginLearning(Context c, String module, String targetPackage) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        s.edit().putBoolean(KEY_RUNNING,false).putBoolean(KEY_LEARNING,true)
                .putString(KEY_LEARNING_MODULE,module).putString(KEY_TARGET_PACKAGE,targetPackage == null ? "" : targetPackage).apply();
        new SecureStore(c).put(CAL_PREFIX + module, "[]");
    }

    public static void finishLearning(Context c) {
        c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_LEARNING,false)
                .remove(KEY_LEARNING_MODULE).remove(KEY_TARGET_PACKAGE).apply();
    }

    public static String learningModule(Context c) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        return s.getBoolean(KEY_LEARNING,false) ? s.getString(KEY_LEARNING_MODULE,"") : "";
    }

    public static boolean hasCalibration(Context c, String module) {
        try { return new JSONArray(new SecureStore(c).get(CAL_PREFIX + module,"[]")).length() > 0; } catch (Exception e) { return false; }
    }

    public static void beginRun(Context c, String module, String targetPackage, String text, String filesJson) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        s.edit().putBoolean(KEY_LEARNING,false).putBoolean(KEY_RUNNING,true).putString(KEY_RUNNING_MODULE,module)
                .putString(KEY_TARGET_PACKAGE,targetPackage == null ? "" : targetPackage).putInt(KEY_STEP_INDEX,0).apply();
        SecureStore secure = new SecureStore(c);
        secure.put(KEY_RUNNING_TEXT,text == null ? "" : text);
        secure.put(KEY_RUNNING_FILES,filesJson == null ? "[]" : filesJson);
    }
}
