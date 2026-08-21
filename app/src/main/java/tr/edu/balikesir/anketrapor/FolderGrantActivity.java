package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public class FolderGrantActivity extends Activity {
    private static final int REQ_TREE = 2401;
    public static final String PREF = "yerel_agent_folder";
    public static final String KEY_URI = "ajan_tree_uri";
    private static volatile Runnable completionCallback;
    private static volatile boolean active;
    private boolean completed;
    private boolean duplicateInstance;
    private boolean resumeScript;

    public static void setCompletionCallback(Runnable callback) { completionCallback = callback; }
    public static boolean isActive() { return active; }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (active) {
            duplicateInstance = true;
            finish();
            return;
        }
        active = true;

        SharedPreferences state = getSharedPreferences(AgentScriptRuntime.STATE_PREF, MODE_PRIVATE);
        resumeScript = state.getBoolean(AgentScriptRuntime.SCRIPT_RUNNING, false);
        if (resumeScript) state.edit().putBoolean(AgentScriptRuntime.SCRIPT_RUNNING, false).apply();

        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE) {
            boolean selected = resultCode == RESULT_OK && data != null && data.getData() != null;
            if (selected) {
                Uri uri = data.getData();
                int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
                getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).apply();
                Toast.makeText(this, "Ajan klasörü kaydedildi. Bundan sonra tekrar seçmen gerekmeyecek.", Toast.LENGTH_LONG).show();
            }

            if (resumeScript) {
                getSharedPreferences(AgentScriptRuntime.STATE_PREF, MODE_PRIVATE).edit()
                        .putBoolean(AgentScriptRuntime.SCRIPT_RUNNING, selected)
                        .apply();
                if (!selected) Toast.makeText(this, "Klasör seçimi iptal edildi; görev durduruldu.", Toast.LENGTH_LONG).show();
            }
            completed = true;
            finishAndNotify();
        }
    }

    @Override
    protected void onDestroy() {
        if (!duplicateInstance) {
            active = false;
            if (!completed && isFinishing()) {
                if (resumeScript) getSharedPreferences(AgentScriptRuntime.STATE_PREF, MODE_PRIVATE).edit().putBoolean(AgentScriptRuntime.SCRIPT_RUNNING, false).apply();
                notifyCompletion();
            }
        }
        super.onDestroy();
    }

    private void finishAndNotify() {
        active = false;
        notifyCompletion();
        finish();
    }

    private static void notifyCompletion() {
        Runnable r = completionCallback;
        completionCallback = null;
        if (r != null) r.run();
    }
}
