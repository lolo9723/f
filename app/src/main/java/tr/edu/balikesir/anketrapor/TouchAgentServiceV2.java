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

public class TouchAgentServiceV2 extends AccessibilityService {
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

    private static final String[] ALLOWED_PACKAGES = {
            "com.android.chrome",
            "com.instagram.android",
            "com.canva.editor",
            "com.google.android.documentsui",
            "com.android.documentsui",
            OWN_PACKAGE
    };

    private SharedPreferences state;
    private SecureStore secure;
    private WindowManager windowManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CaptureOverlay overlay;
    private WindowManager.LayoutParams overlayParams;
    private boolean overlayAdded;
    private boolean overlayTouchable = true;
    private boolean executing;
    private long lastRecordedAt;
    private long lastRunAt;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        ensureStores();
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
        info.packageNames = ALLOWED_PACKAGES;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        ensureStores();
        String pkg = safe(event.getPackageName());
        if (pkg.isEmpty()) return;

        if (state.getBoolean(KEY_LEARNING, false)) {
            String target = state.getString(KEY_TARGET_PACKAGE, "");
            if (target.equals(pkg)) {
                showOverlay();
                if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) {
                    recordTextEventFallback(event, pkg);
                }
            } else if (OWN_PACKAGE.equals(pkg) || isDocumentsPackage(pkg)) {
                hideOverlay();
            }
            return;
        }

        hideOverlay();
        if (!state.getBoolean(KEY_RUNNING, false)) return;

        if (isDocumentsPackage(pkg)) {
            tryPendingFileSelection();
        } else if (!OWN_PACKAGE.equals(pkg)) {
            handleRun(pkg);
        }
    }

    @Override
    public void onInterrupt() {
        hideOverlay();
        ensureStores();
        state.edit().putBoolean(KEY_RUNNING, false).apply();
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    private void ensureStores() {
        if (state == null) state = getSharedPreferences(STATE_PREF, MODE_PRIVATE);
        if (secure == null) secure = new SecureStore(this);
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    // ---------- ÖĞRETME ----------

    private void showOverlay() {
        if (overlayAdded || windowManager == null || !state.getBoolean(KEY_LEARNING, false)) return;
        try {
            overlay = new CaptureOverlay(this);
            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(overlay, overlayParams);
            overlayAdded = true;
            overlayTouchable = true;
        } catch (Exception e) {
            overlayAdded = false;
            toast("Öğretme katmanı açılamadı.");
        }
    }

    private void hideOverlay() {
        if (!overlayAdded || overlay == null || windowManager == null) return;
        try { windowManager.removeViewImmediate(overlay); } catch (Exception ignored) {}
        overlayAdded = false;
        overlay = null;
        overlayParams = null;
        overlayTouchable = true;
    }

    private void setOverlayTouchable(boolean touchable) {
        if (!overlayAdded || overlay == null || overlayParams == null || windowManager == null) return;
        if (overlayTouchable == touchable) return;
        try {
            if (touchable) overlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else overlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            windowManager.updateViewLayout(overlay, overlayParams);
            overlayTouchable = touchable;
        } catch (Exception ignored) {}
    }

    private final class CaptureOverlay extends View {
        private float downX, downY;
        private long downAt;
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fg = new Paint(Paint.ANTI_ALIAS_FLAG);

        CaptureOverlay(Context context) {
            super(context);
            setBackgroundColor(Color.TRANSPARENT);
            bg.setColor(0xD91B1E23);
            fg.setColor(Color.WHITE);
            fg.setTextSize(dp(12));
            fg.setFakeBoldText(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float l = dp(12), t = dp(12), r = Math.min(getWidth() - dp(12), l + dp(278)), b = t + dp(38);
            canvas.drawRoundRect(l, t, r, b, dp(18), dp(18), bg);
            canvas.drawText("AJAN ÖĞRETİYOR • dokunarak ilerle", l + dp(14), t + dp(24), fg);
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
                captureGesture(downX, downY, e.getRawX(), e.getRawY(), Math.max(60L, SystemClock.uptimeMillis() - downAt));
                return true;
            }
            return true;
        }
    }

    private void captureGesture(float sx, float sy, float ex, float ey, long duration) {
        ensureStores();
        String targetPkg = state.getString(KEY_TARGET_PACKAGE, "");
        if (!state.getBoolean(KEY_LEARNING, false)) return;
        if (!targetPkg.isEmpty() && !targetPkg.equals(activePackage())) return;

        float dx = ex - sx, dy = ey - sy;
        if (Math.sqrt(dx * dx + dy * dy) > dp(22)) {
            addSwipe(sx, sy, ex, ey, duration, targetPkg);
            forwardGesture(sx, sy, ex, ey, duration, false);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo node = null;
        boolean editable = false;
        try {
            if (root != null) node = bestNodeAt(root, Math.round(ex), Math.round(ey));
            if (node != null) {
                if (isProtectedFinal(safe(node.getText())) || isProtectedFinal(safe(node.getContentDescription()))) {
                    toast("Son Yayınla / Paylaş / Gönder adımı engellendi.");
                    return;
                }
                editable = node.isEditable() || safe(node.getClassName()).toLowerCase(Locale.ROOT).contains("edittext");
                if (editable) {
                    int slot = addNodeStep("text", node, ex, ey, targetPkg);
                    String sample = secure.get("last_text", "");
                    if (!sample.isEmpty()) {
                        String module = state.getString(KEY_LEARNING_MODULE, "");
                        String value = valueForSlot(module, slot, sample);
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                        if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                            toast("Metin alanı kaydedildi.");
                            return;
                        }
                    }
                } else {
                    addNodeStep("click", node, ex, ey, targetPkg);
                }
            } else {
                addTap(ex, ey, targetPkg);
            }
        } finally {
            if (node != null) node.recycle();
            if (root != null) root.recycle();
        }
        forwardGesture(ex, ey, ex, ey, 70L, editable);
    }

    private int addNodeStep(String kind, AccessibilityNodeInfo node, float x, float y, String pkg) {
        try {
            JSONArray steps = calibration();
            String signature = selectorSignature(node);
            if ("text".equals(kind)) {
                for (int i = Math.max(0, steps.length() - 3); i < steps.length(); i++) {
                    JSONObject s = steps.optJSONObject(i);
                    if (s != null && "text".equals(s.optString("kind")) && signature.equals(s.optString("signature"))) {
                        return s.optInt("slot", 0);
                    }
                }
            }
            JSONObject step = new JSONObject();
            step.put("kind", kind);
            step.put("package", pkg);
            step.put("id", safe(node.getViewIdResourceName()));
            step.put("class", safe(node.getClassName()));
            step.put("path", buildPath(node));
            step.put("x", xRatio(x));
            step.put("y", yRatio(y));
            step.put("signature", signature);
            int slot = -1;
            if ("text".equals(kind)) {
                slot = nextTextSlot(steps);
                step.put("slot", slot);
                step.put("label", "");
                step.put("desc", "");
            } else {
                step.put("label", trim(safe(node.getText())));
                step.put("desc", trim(safe(node.getContentDescription())));
            }
            saveStep(steps, step);
            return slot;
        } catch (Exception ignored) { return -1; }
    }

    private void addTap(float x, float y, String pkg) {
        try {
            JSONArray steps = calibration();
            JSONObject s = new JSONObject();
            s.put("kind", "tap"); s.put("package", pkg); s.put("x", xRatio(x)); s.put("y", yRatio(y));
            saveStep(steps, s);
        } catch (Exception ignored) {}
    }

    private void addSwipe(float sx, float sy, float ex, float ey, long duration, String pkg) {
        try {
            JSONArray steps = calibration();
            JSONObject s = new JSONObject();
            s.put("kind", "swipe"); s.put("package", pkg);
            s.put("sx", xRatio(sx)); s.put("sy", yRatio(sy)); s.put("ex", xRatio(ex)); s.put("ey", yRatio(ey));
            s.put("duration", Math.max(120L, Math.min(1600L, duration)));
            saveStep(steps, s);
        } catch (Exception ignored) {}
    }

    private void saveStep(JSONArray steps, JSONObject step) {
        long now = SystemClock.uptimeMillis();
        if (now - lastRecordedAt < 90L) return;
        steps.put(step);
        secure.put(CAL_PREFIX + state.getString(KEY_LEARNING_MODULE, ""), steps.toString());
        lastRecordedAt = now;
    }

    private void recordTextEventFallback(AccessibilityEvent event, String pkg) {
        AccessibilityNodeInfo node = event.getSource();
        if (node == null) return;
        try {
            if (node.isEditable()) addNodeStep("text", node, centerX(node), centerY(node), pkg);
        } finally { node.recycle(); }
    }

    private void forwardGesture(float sx, float sy, float ex, float ey, long duration, boolean typingFallback) {
        setOverlayTouchable(false);
        dispatchPixels(sx, sy, ex, ey, duration, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                long delay = typingFallback ? 2200L : 130L;
                handler.postDelayed(() -> {
                    if (state != null && state.getBoolean(KEY_LEARNING, false)
                            && state.getString(KEY_TARGET_PACKAGE, "").equals(activePackage())) {
                        setOverlayTouchable(true);
                    }
                }, delay);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                handler.postDelayed(() -> setOverlayTouchable(true), 180L);
            }
        });
    }

    // ---------- YÜRÜTME ----------

    private void handleRun(String pkg) {
        if (executing) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastRunAt < 220L) return;

        JSONArray steps = runningCalibration();
        int index = state.getInt(KEY_STEP_INDEX, 0);
        if (index >= steps.length()) {
            stopRun("prepared");
            toast("Hazır. Son Yayınla / Paylaş / Gönder dokunuşu sende.");
            return;
        }

        JSONObject step = steps.optJSONObject(index);
        if (step == null) { advance(index); return; }
        String expected = step.optString("package", "");
        if (!expected.isEmpty() && !expected.equals(pkg)) return;

        String kind = step.optString("kind", "click");
        if ("tap".equals(kind)) {
            int x = screenX(step.optDouble("x", -.1)), y = screenY(step.optDouble("y", -.1));
            if (x < 0 || y < 0 || protectedAt(x, y)) { if (protectedAt(x, y)) stopRun("protected-final"); return; }
            runGesture(x, y, x, y, 70L, index);
            return;
        }
        if ("swipe".equals(kind)) {
            int sx = screenX(step.optDouble("sx", -.1)), sy = screenY(step.optDouble("sy", -.1));
            int ex = screenX(step.optDouble("ex", -.1)), ey = screenY(step.optDouble("ey", -.1));
            if (sx < 0 || sy < 0 || ex < 0 || ey < 0) return;
            runGesture(sx, sy, ex, ey, step.optLong("duration", 350L), index);
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo target = null;
        try {
            target = findNode(root, step);
            if (target == null) {
                int x = screenX(step.optDouble("x", -.1)), y = screenY(step.optDouble("y", -.1));
                if (x >= 0 && y >= 0) target = bestNodeAt(root, x, y);
            }

            if ("text".equals(kind)) {
                if (target == null) return;
                AccessibilityNodeInfo editable = editableNode(target);
                if (editable == null) return;
                String module = state.getString(KEY_RUNNING_MODULE, "");
                String text = secure.get(KEY_RUNNING_TEXT, "");
                String value = valueForSlot(module, step.optInt("slot", 0), text);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                boolean ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                editable.recycle();
                if (ok) advance(index);
                return;
            }

            if (target != null) {
                if (isProtectedFinal(safe(target.getText())) || isProtectedFinal(safe(target.getContentDescription()))) {
                    stopRun("protected-final");
                    toast("Son kritik düğmeye dokunmadım.");
                    return;
                }
                AccessibilityNodeInfo clickable = clickableAncestor(target);
                boolean ok = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (clickable != null) clickable.recycle();
                if (ok) { advance(index); return; }
            }

            int x = screenX(step.optDouble("x", -.1)), y = screenY(step.optDouble("y", -.1));
            if (x >= 0 && y >= 0 && !protectedAt(x, y)) runGesture(x, y, x, y, 70L, index);
        } finally {
            if (target != null) target.recycle();
            root.recycle();
        }
    }

    private void runGesture(float sx, float sy, float ex, float ey, long duration, int index) {
        executing = true;
        dispatchPixels(sx, sy, ex, ey, duration, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription gestureDescription) { advance(index); }
            @Override public void onCancelled(GestureDescription gestureDescription) {
                executing = false;
                handler.postDelayed(TouchAgentServiceV2.this::continueRun, 500L);
            }
        });
    }

    private void advance(int index) {
        state.edit().putInt(KEY_STEP_INDEX, index + 1).apply();
        executing = false;
        lastRunAt = SystemClock.uptimeMillis();
        handler.postDelayed(this::continueRun, 620L);
    }

    private void continueRun() {
        if (!state.getBoolean(KEY_RUNNING, false)) return;
        String pkg = activePackage();
        if (pkg.isEmpty() || OWN_PACKAGE.equals(pkg)) return;
        if (isDocumentsPackage(pkg)) tryPendingFileSelection(); else handleRun(pkg);
    }

    private void dispatchPixels(float sx, float sy, float ex, float ey, long duration, AccessibilityService.GestureResultCallback callback) {
        Path p = new Path();
        p.moveTo(sx, sy);
        if (Math.abs(ex - sx) > 2 || Math.abs(ey - sy) > 2) p.lineTo(ex, ey);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(p, 0, Math.max(50L, duration));
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), callback, null);
    }

    // ---------- DOSYA SEÇİCİ ----------

    private void tryPendingFileSelection() {
        if (executing) return;
        JSONArray files;
        try { files = new JSONArray(secure.get(KEY_RUNNING_FILES, "[]")); }
        catch (Exception e) { return; }
        if (files.length() == 0) { handler.postDelayed(this::continueRun, 350L); return; }

        String name = files.optString(0, "");
        if (name.isEmpty()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(name);
            if (found == null) return;
            for (AccessibilityNodeInfo n : found) {
                if (!name.equals(safe(n.getText()))) continue;
                AccessibilityNodeInfo clickable = clickableAncestor(n);
                if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    JSONArray next = new JSONArray();
                    for (int i = 1; i < files.length(); i++) next.put(files.optString(i));
                    secure.put(KEY_RUNNING_FILES, next.toString());
                    lastRunAt = SystemClock.uptimeMillis();
                    clickable.recycle();
                    handler.postDelayed(this::continueRun, 650L);
                    break;
                }
                if (clickable != null) clickable.recycle();
            }
            recycle(found);
        } finally { root.recycle(); }
    }

    // ---------- UI SEÇİCİLER ----------

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, JSONObject step) {
        String id = step.optString("id", "");
        if (!id.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
                if (found != null && !found.isEmpty()) {
                    AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(found.get(0));
                    recycle(found); return r;
                }
                recycle(found);
            } catch (Exception ignored) {}
        }

        String label = step.optString("label", "");
        if (!label.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
                if (found != null) {
                    for (AccessibilityNodeInfo n : found) {
                        if (label.equals(safe(n.getText()))) {
                            AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(n);
                            recycle(found); return r;
                        }
                    }
                }
                recycle(found);
            } catch (Exception ignored) {}
        }

        String desc = step.optString("desc", "");
        if (!desc.isEmpty()) {
            AccessibilityNodeInfo n = byDescription(root, desc);
            if (n != null) return n;
        }

        AccessibilityNodeInfo n = byPath(root, step.optString("path", ""));
        if (n != null) {
            String clazz = step.optString("class", "");
            if (clazz.isEmpty() || clazz.equals(safe(n.getClassName()))) return n;
            n.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo bestNodeAt(AccessibilityNodeInfo root, int x, int y) {
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>();
        q.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo best = null;
        long bestArea = Long.MAX_VALUE;
        for (int i = 0; i < q.size() && i < 900; i++) {
            AccessibilityNodeInfo n = q.get(i);
            Rect r = new Rect(); n.getBoundsInScreen(r);
            boolean actionable = n.isEditable() || n.isClickable() || n.isFocusable()
                    || !safe(n.getText()).isEmpty() || !safe(n.getContentDescription()).isEmpty();
            if (r.contains(x, y) && n.isVisibleToUser() && actionable) {
                long area = Math.max(1L, (long)Math.max(1, r.width()) * Math.max(1, r.height()));
                if (area <= bestArea) {
                    if (best != null) best.recycle();
                    best = AccessibilityNodeInfo.obtain(n);
                    bestArea = area;
                }
            }
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo ch = n.getChild(c);
                if (ch != null) q.add(ch);
            }
        }
        recycle(q);
        return best;
    }

    private AccessibilityNodeInfo byDescription(AccessibilityNodeInfo root, String desc) {
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(root));
        for (int i = 0; i < q.size() && i < 700; i++) {
            AccessibilityNodeInfo n = q.get(i);
            if (desc.equals(safe(n.getContentDescription()))) {
                AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(n); recycle(q); return r;
            }
            for (int c = 0; c < n.getChildCount(); c++) {
                AccessibilityNodeInfo ch = n.getChild(c); if (ch != null) q.add(ch);
            }
        }
        recycle(q); return null;
    }

    private AccessibilityNodeInfo byPath(AccessibilityNodeInfo root, String path) {
        if (path == null || path.isEmpty()) return null;
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(root);
        try {
            for (String part : path.split("/")) {
                if (part.isEmpty()) continue;
                AccessibilityNodeInfo child = cur.getChild(Integer.parseInt(part));
                cur.recycle(); cur = child;
                if (cur == null) return null;
            }
            AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(cur); cur.recycle(); return r;
        } catch (Exception e) {
            if (cur != null) cur.recycle(); return null;
        }
    }

    private String buildPath(AccessibilityNodeInfo node) {
        ArrayList<Integer> rev = new ArrayList<>();
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(node);
        try {
            for (int guard = 0; cur != null && guard < 40; guard++) {
                AccessibilityNodeInfo parent = cur.getParent();
                if (parent == null) break;
                int idx = childIndex(parent, cur);
                if (idx < 0) { parent.recycle(); break; }
                rev.add(idx);
                cur.recycle(); cur = parent;
            }
        } finally { if (cur != null) cur.recycle(); }
        Collections.reverse(rev);
        StringBuilder b = new StringBuilder();
        for (Integer i : rev) { if (b.length() > 0) b.append('/'); b.append(i); }
        return b.toString();
    }

    private int childIndex(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            AccessibilityNodeInfo c = parent.getChild(i);
            if (c != null) {
                boolean same = c.equals(child); c.recycle();
                if (same) return i;
            }
        }
        return -1;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo p = cur.getParent(); cur.recycle(); cur = p;
        }
        if (cur != null) cur.recycle();
        return null;
    }

    private AccessibilityNodeInfo editableNode(AccessibilityNodeInfo n) {
        if (n.isEditable()) return AccessibilityNodeInfo.obtain(n);
        ArrayList<AccessibilityNodeInfo> q = new ArrayList<>(); q.add(AccessibilityNodeInfo.obtain(n));
        for (int i = 0; i < q.size() && i < 120; i++) {
            AccessibilityNodeInfo cur = q.get(i);
            if (cur.isEditable()) {
                AccessibilityNodeInfo r = AccessibilityNodeInfo.obtain(cur); recycle(q); return r;
            }
            for (int c = 0; c < cur.getChildCount(); c++) {
                AccessibilityNodeInfo ch = cur.getChild(c); if (ch != null) q.add(ch);
            }
        }
        recycle(q); return null;
    }

    // ---------- GÜVENLİK / YARDIMCI ----------

    private boolean protectedAt(int x, int y) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo n = null;
        try {
            n = bestNodeAt(root, x, y);
            return n != null && (isProtectedFinal(safe(n.getText())) || isProtectedFinal(safe(n.getContentDescription())));
        } finally {
            if (n != null) n.recycle(); root.recycle();
        }
    }

    private boolean isProtectedFinal(String value) {
        String n = normalize(value);
        if (n.isEmpty()) return false;
        String[] blocked = {"yayinla","paylas","gonder","guncelle","publish","share","send","share now","publish now","simdi paylas","hikayende paylas"};
        for (String b : blocked) if (n.equals(b)) return true;
        return false;
    }

    private void stopRun(String reason) {
        executing = false;
        state.edit().putBoolean(KEY_RUNNING, false).putString("last_run_result", reason).apply();
        secure.remove(KEY_RUNNING_TEXT);
        secure.remove(KEY_RUNNING_FILES);
    }

    private JSONArray calibration() {
        try { return new JSONArray(secure.get(CAL_PREFIX + state.getString(KEY_LEARNING_MODULE, ""), "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private JSONArray runningCalibration() {
        try { return new JSONArray(secure.get(CAL_PREFIX + state.getString(KEY_RUNNING_MODULE, ""), "[]")); }
        catch (Exception e) { return new JSONArray(); }
    }

    private int nextTextSlot(JSONArray steps) {
        int n = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s != null && "text".equals(s.optString("kind"))) n++;
        }
        return n;
    }

    private String valueForSlot(String module, int slot, String text) {
        if (text == null) text = "";
        if (module.startsWith("instagram_")) return text;
        if (module.startsWith("baun_") || module.startsWith("canva_")) {
            if (slot == 0) return firstLine(text);
            if (slot == 1) return body(text);
        }
        return text;
    }

    private String firstLine(String text) {
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (!s.isEmpty()) return s.length() > 180 ? s.substring(0, 180) : s;
        }
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    private String body(String text) {
        String[] lines = text.split("\\R", -1);
        boolean skipped = false;
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            if (!skipped && !line.trim().isEmpty()) { skipped = true; continue; }
            if (skipped) { if (b.length() > 0) b.append('\n'); b.append(line); }
        }
        String out = b.toString().trim();
        return out.isEmpty() ? text : out;
    }

    private String selectorSignature(AccessibilityNodeInfo n) {
        return safe(n.getViewIdResourceName()) + "|" + safe(n.getClassName()) + "|" + buildPath(n);
    }

    private float centerX(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r.exactCenterX(); }
    private float centerY(AccessibilityNodeInfo n) { Rect r = new Rect(); n.getBoundsInScreen(r); return r.exactCenterY(); }
    private double xRatio(float x) { return Math.max(0d, Math.min(1d, x / Math.max(1f, getResources().getDisplayMetrics().widthPixels - 1f))); }
    private double yRatio(float y) { return Math.max(0d, Math.min(1d, y / Math.max(1f, getResources().getDisplayMetrics().heightPixels - 1f))); }
    private int screenX(double r) { if (r < 0) return -1; int max = Math.max(0, getResources().getDisplayMetrics().widthPixels - 1); return (int)Math.round(Math.max(0d, Math.min(1d, r)) * max); }
    private int screenY(double r) { if (r < 0) return -1; int max = Math.max(0, getResources().getDisplayMetrics().heightPixels - 1); return (int)Math.round(Math.max(0d, Math.min(1d, r)) * max); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String activePackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "";
        try { return safe(root.getPackageName()); }
        finally { root.recycle(); }
    }

    private boolean isDocumentsPackage(String pkg) {
        return "com.google.android.documentsui".equals(pkg) || "com.android.documentsui".equals(pkg);
    }

    private String trim(String s) { if (s == null) return ""; s = s.trim(); return s.length() <= 120 ? s : s.substring(0, 120); }
    private static String safe(CharSequence c) { return c == null ? "" : c.toString(); }
    private static void recycle(List<AccessibilityNodeInfo> list) { if (list != null) for (AccessibilityNodeInfo n : list) if (n != null) n.recycle(); }

    private String normalize(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(new Locale("tr", "TR"));
        x = x.replace('ı','i').replace('ş','s').replace('ğ','g').replace('ü','u').replace('ö','o').replace('ç','c');
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private void toast(String text) {
        handler.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }
}
