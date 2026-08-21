package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

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

    private static final String[] DEFAULT_PACKAGES = new String[]{
            "com.android.chrome",
            "com.instagram.android",
            "com.canva.editor",
            "com.google.android.documentsui",
            "com.android.documentsui"
    };

    private SharedPreferences state;
    private SecureStore secure;
    private String lastLearningSignature = "";
    private long lastLearningAt = 0L;
    private long lastRunAt = 0L;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        state = getSharedPreferences(STATE_PREF, MODE_PRIVATE);
        secure = new SecureStore(this);
        AccessibilityServiceInfo info = getServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                | AccessibilityEvent.TYPE_VIEW_CLICKED
                | AccessibilityEvent.TYPE_VIEW_FOCUSED
                | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        info.notificationTimeout = 80;
        info.packageNames = DEFAULT_PACKAGES;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        ensureStores();
        CharSequence p = event.getPackageName();
        String pkg = p == null ? "" : p.toString();
        if (pkg.isEmpty()) return;

        if (state.getBoolean(KEY_LEARNING, false)) {
            handleLearning(event, pkg);
            return;
        }

        if (state.getBoolean(KEY_RUNNING, false)) {
            if (isDocumentsPackage(pkg)) {
                tryPendingFileSelection();
            } else {
                handleRun(pkg);
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Fail closed: Android interrupting the service stops automation.
    }

    private void ensureStores() {
        if (state == null) state = getSharedPreferences(STATE_PREF, MODE_PRIVATE);
        if (secure == null) secure = new SecureStore(this);
    }

    private void handleLearning(AccessibilityEvent event, String pkg) {
        if (isDocumentsPackage(pkg)) return;
        String expected = state.getString(KEY_TARGET_PACKAGE, "");
        if (!expected.isEmpty() && !expected.equals(pkg)) return;

        AccessibilityNodeInfo node = event.getSource();
        if (node == null) return;

        int type = event.getEventType();
        boolean editable = node.isEditable();
        String kind = null;
        if (type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED && editable) {
            kind = "text";
        } else if (type == AccessibilityEvent.TYPE_VIEW_CLICKED && !editable) {
            kind = "click";
        }
        if (kind == null) {
            node.recycle();
            return;
        }

        String label = safe(node.getText());
        String desc = safe(node.getContentDescription());
        if (isProtectedFinal(label) || isProtectedFinal(desc)) {
            node.recycle();
            return;
        }

        String viewId = safe(node.getViewIdResourceName());
        String clazz = safe(node.getClassName());
        String path = buildPath(node);
        String signature = kind + "|" + pkg + "|" + viewId + "|" + path + "|" + clazz;
        long now = SystemClock.uptimeMillis();
        if (signature.equals(lastLearningSignature) && now - lastLearningAt < 1300) {
            node.recycle();
            return;
        }

        try {
            String module = state.getString(KEY_LEARNING_MODULE, "");
            JSONArray steps = calibration(module);
            if ("text".equals(kind) && containsSelector(steps, signature)) {
                node.recycle();
                return;
            }
            JSONObject step = new JSONObject();
            step.put("kind", kind);
            step.put("signature", signature);
            step.put("package", pkg);
            step.put("id", viewId);
            step.put("class", clazz);
            step.put("path", path);
            if (!editable) {
                step.put("label", trimForSelector(label));
                step.put("desc", trimForSelector(desc));
            } else {
                step.put("label", "");
                step.put("desc", "");
                step.put("slot", nextTextSlot(steps));
            }
            steps.put(step);
            secure.put(CAL_PREFIX + module, steps.toString());
            lastLearningSignature = signature;
            lastLearningAt = now;
        } catch (Exception ignored) {
        } finally {
            node.recycle();
        }
    }

    private boolean containsSelector(JSONArray steps, String signature) {
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s != null && signature.equals(s.optString("signature", ""))) return true;
        }
        return false;
    }

    private int nextTextSlot(JSONArray steps) {
        int count = 0;
        for (int i = 0; i < steps.length(); i++) {
            JSONObject s = steps.optJSONObject(i);
            if (s != null && "text".equals(s.optString("kind"))) count++;
        }
        return count;
    }

    private void handleRun(String pkg) {
        long now = SystemClock.uptimeMillis();
        if (now - lastRunAt < 650) return;

        String module = state.getString(KEY_RUNNING_MODULE, "");
        JSONArray steps = calibration(module);
        int index = state.getInt(KEY_STEP_INDEX, 0);
        if (index >= steps.length()) {
            stopRun("prepared");
            return;
        }

        JSONObject step = steps.optJSONObject(index);
        if (step == null) {
            state.edit().putInt(KEY_STEP_INDEX, index + 1).apply();
            return;
        }

        String expectedPkg = step.optString("package", "");
        if (!expectedPkg.isEmpty() && !expectedPkg.equals(pkg)) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo target = null;
        try {
            target = findNode(root, step);
            if (target == null) return;

            String label = safe(target.getText());
            String desc = safe(target.getContentDescription());
            if (isProtectedFinal(label) || isProtectedFinal(desc)) {
                stopRun("protected-final");
                return;
            }

            boolean success;
            String kind = step.optString("kind", "click");
            if ("text".equals(kind)) {
                String taskText = secure.get(KEY_RUNNING_TEXT, "");
                int slot = step.optInt("slot", 0);
                String value = valueForSlot(module, slot, taskText);
                Bundle args = new Bundle();
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
                success = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            } else {
                AccessibilityNodeInfo clickable = clickableAncestor(target);
                success = clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (clickable != null && clickable != target) clickable.recycle();
            }

            if (success) {
                state.edit().putInt(KEY_STEP_INDEX, index + 1).apply();
                lastRunAt = now;
            }
        } finally {
            if (target != null) target.recycle();
            root.recycle();
        }
    }

    private String valueForSlot(String module, int slot, String taskText) {
        if (taskText == null) taskText = "";
        if (module.startsWith("instagram_")) return taskText;
        if (module.startsWith("baun_") || module.startsWith("canva_")) {
            if (slot == 0) return firstNonEmptyLine(taskText);
            if (slot == 1) return bodyWithoutFirstLine(taskText);
            return taskText;
        }
        return taskText;
    }

    private String firstNonEmptyLine(String text) {
        for (String line : text.split("\\R")) {
            String s = line.trim();
            if (!s.isEmpty()) return s.length() > 180 ? s.substring(0, 180) : s;
        }
        return text.length() > 180 ? text.substring(0, 180) : text;
    }

    private String bodyWithoutFirstLine(String text) {
        String[] lines = text.split("\\R", -1);
        boolean skipped = false;
        StringBuilder b = new StringBuilder();
        for (String line : lines) {
            if (!skipped && !line.trim().isEmpty()) {
                skipped = true;
                continue;
            }
            if (skipped) {
                if (b.length() > 0) b.append('\n');
                b.append(line);
            }
        }
        String out = b.toString().trim();
        return out.isEmpty() ? text : out;
    }

    private AccessibilityNodeInfo findNode(AccessibilityNodeInfo root, JSONObject step) {
        String id = step.optString("id", "");
        if (!id.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(id);
                if (found != null && !found.isEmpty()) {
                    AccessibilityNodeInfo first = AccessibilityNodeInfo.obtain(found.get(0));
                    recycleList(found);
                    return first;
                }
                recycleList(found);
            } catch (Exception ignored) {
            }
        }

        String label = step.optString("label", "");
        if (!label.isEmpty()) {
            try {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(label);
                if (found != null) {
                    for (AccessibilityNodeInfo n : found) {
                        if (label.equals(safe(n.getText()))) {
                            AccessibilityNodeInfo copy = AccessibilityNodeInfo.obtain(n);
                            recycleList(found);
                            return copy;
                        }
                    }
                }
                recycleList(found);
            } catch (Exception ignored) {
            }
        }

        String desc = step.optString("desc", "");
        if (!desc.isEmpty()) {
            AccessibilityNodeInfo n = bfsByDescription(root, desc);
            if (n != null) return n;
        }

        String path = step.optString("path", "");
        AccessibilityNodeInfo pathNode = traversePath(root, path);
        if (pathNode != null) {
            String clazz = step.optString("class", "");
            if (clazz.isEmpty() || clazz.equals(safe(pathNode.getClassName()))) return pathNode;
            pathNode.recycle();
        }
        return null;
    }

    private AccessibilityNodeInfo bfsByDescription(AccessibilityNodeInfo root, String desc) {
        ArrayList<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        int index = 0;
        while (index < queue.size() && index < 700) {
            AccessibilityNodeInfo n = queue.get(index++);
            if (desc.equals(safe(n.getContentDescription()))) {
                AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(n);
                recycleList(queue);
                return result;
            }
            int count = n.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        recycleList(queue);
        return null;
    }

    private AccessibilityNodeInfo traversePath(AccessibilityNodeInfo root, String path) {
        if (path == null || path.isEmpty()) return null;
        String[] parts = path.split("/");
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(root);
        try {
            for (String part : parts) {
                if (part.isEmpty()) continue;
                int idx = Integer.parseInt(part);
                AccessibilityNodeInfo child = current.getChild(idx);
                current.recycle();
                current = child;
                if (current == null) return null;
            }
            AccessibilityNodeInfo result = AccessibilityNodeInfo.obtain(current);
            current.recycle();
            return result;
        } catch (Exception e) {
            if (current != null) current.recycle();
            return null;
        }
    }

    private String buildPath(AccessibilityNodeInfo node) {
        ArrayList<Integer> rev = new ArrayList<>();
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        int guard = 0;
        try {
            while (current != null && guard++ < 40) {
                AccessibilityNodeInfo parent = current.getParent();
                if (parent == null) break;
                int index = childIndex(parent, current);
                if (index < 0) {
                    parent.recycle();
                    break;
                }
                rev.add(index);
                current.recycle();
                current = parent;
            }
        } finally {
            if (current != null) current.recycle();
        }
        Collections.reverse(rev);
        StringBuilder b = new StringBuilder();
        for (Integer i : rev) {
            if (b.length() > 0) b.append('/');
            b.append(i);
        }
        return b.toString();
    }

    private int childIndex(AccessibilityNodeInfo parent, AccessibilityNodeInfo child) {
        int count = parent.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo c = parent.getChild(i);
            if (c != null) {
                boolean same = c.equals(child);
                c.recycle();
                if (same) return i;
            }
        }
        return -1;
    }

    private AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(n);
        for (int i = 0; i < 8 && current != null; i++) {
            if (current.isClickable()) return current;
            AccessibilityNodeInfo parent = current.getParent();
            current.recycle();
            current = parent;
        }
        if (current != null) current.recycle();
        return null;
    }

    private void tryPendingFileSelection() {
        long now = SystemClock.uptimeMillis();
        if (now - lastRunAt < 650) return;
        String json = secure.get(KEY_RUNNING_FILES, "[]");
        JSONArray files;
        try {
            files = new JSONArray(json);
        } catch (Exception e) {
            return;
        }
        if (files.length() == 0) return;
        String name = files.optString(0, "");
        if (name.isEmpty()) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(name);
            if (nodes == null) return;
            for (AccessibilityNodeInfo node : nodes) {
                if (name.equals(safe(node.getText()))) {
                    AccessibilityNodeInfo clickable = clickableAncestor(node);
                    if (clickable != null && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        JSONArray next = new JSONArray();
                        for (int i = 1; i < files.length(); i++) next.put(files.optString(i));
                        secure.put(KEY_RUNNING_FILES, next.toString());
                        lastRunAt = now;
                        clickable.recycle();
                        break;
                    }
                    if (clickable != null) clickable.recycle();
                }
            }
            recycleList(nodes);
        } finally {
            root.recycle();
        }
    }

    private JSONArray calibration(String module) {
        ensureStores();
        String raw = secure.get(CAL_PREFIX + module, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void stopRun(String reason) {
        state.edit()
                .putBoolean(KEY_RUNNING, false)
                .putString("last_run_result", reason)
                .apply();
        secure.remove(KEY_RUNNING_TEXT);
        secure.remove(KEY_RUNNING_FILES);
    }

    private boolean isDocumentsPackage(String pkg) {
        return "com.google.android.documentsui".equals(pkg) || "com.android.documentsui".equals(pkg);
    }

    private boolean isProtectedFinal(String value) {
        String n = normalize(value);
        if (n.isEmpty()) return false;
        String[] blocked = {
                "yayinla", "paylas", "gonder", "guncelle",
                "publish", "share", "send", "post",
                "simdi paylas", "hikayende paylas", "share now", "publish now"
        };
        for (String b : blocked) {
            if (n.equals(b)) return true;
        }
        return false;
    }

    private String normalize(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(new Locale("tr", "TR"));
        x = x.replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c');
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String trimForSelector(String s) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= 120 ? s : s.substring(0, 120);
    }

    private static String safe(CharSequence c) {
        return c == null ? "" : c.toString();
    }

    private static void recycleList(List<AccessibilityNodeInfo> list) {
        if (list == null) return;
        for (AccessibilityNodeInfo n : list) if (n != null) n.recycle();
    }

    public static void beginLearning(Context c, String module, String targetPackage) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        s.edit()
                .putBoolean(KEY_RUNNING, false)
                .putBoolean(KEY_LEARNING, true)
                .putString(KEY_LEARNING_MODULE, module)
                .putString(KEY_TARGET_PACKAGE, targetPackage == null ? "" : targetPackage)
                .apply();
        new SecureStore(c).put(CAL_PREFIX + module, "[]");
    }

    public static void finishLearning(Context c) {
        c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_LEARNING, false)
                .remove(KEY_LEARNING_MODULE)
                .remove(KEY_TARGET_PACKAGE)
                .apply();
    }

    public static String learningModule(Context c) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        if (!s.getBoolean(KEY_LEARNING, false)) return "";
        return s.getString(KEY_LEARNING_MODULE, "");
    }

    public static boolean hasCalibration(Context c, String module) {
        String raw = new SecureStore(c).get(CAL_PREFIX + module, "[]");
        try {
            return new JSONArray(raw).length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public static void beginRun(Context c, String module, String targetPackage, String text, String filesJson) {
        SharedPreferences s = c.getSharedPreferences(STATE_PREF, Context.MODE_PRIVATE);
        s.edit()
                .putBoolean(KEY_LEARNING, false)
                .putBoolean(KEY_RUNNING, true)
                .putString(KEY_RUNNING_MODULE, module)
                .putString(KEY_TARGET_PACKAGE, targetPackage == null ? "" : targetPackage)
                .putInt(KEY_STEP_INDEX, 0)
                .apply();
        SecureStore secure = new SecureStore(c);
        secure.put(KEY_RUNNING_TEXT, text == null ? "" : text);
        secure.put(KEY_RUNNING_FILES, filesJson == null ? "[]" : filesJson);
    }
}
