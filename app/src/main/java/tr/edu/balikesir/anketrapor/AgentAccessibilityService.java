package tr.edu.balikesir.anketrapor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;

/**
 * Servis bileşen adı v0.1 ile aynı tutulur; gerçek dokunma motoru TouchAgentServiceV2'dedir.
 * Böylece güncellemede Android'in erişilebilirlik izin bileşeni mümkün olduğunca korunur.
 */
public class AgentAccessibilityService extends TouchAgentServiceV2 {
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
        try {
            return new JSONArray(new SecureStore(c).get(CAL_PREFIX + module, "[]")).length() > 0;
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
