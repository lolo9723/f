package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;

/** Hazır 1-9 modüllerini korur; 20. modülü V4 runtime'a bağlar. */
public class AgentAccessibilityServiceV4 extends TouchAgentServiceV2 {
    private AgentScriptRuntimeV4 runtime;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        runtime = new AgentScriptRuntimeV4(this);
        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = null; // Hassas uygulamalar aşağıda anında kesilir; yeni uygulamalar için APK gerekmez.
        setServiceInfo(info);
        if (!AgentScriptEngineV2.selfTest() || !AgentScriptEngine.selfTest()) {
            android.widget.Toast.makeText(this, "Agent görev öz testi başarısız. 20. modül çalıştırılmayacak.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (SafetyPolicy.isBlockedPackage(this, pkg)) {
            SharedPreferences s = getSharedPreferences("yerel_agent_state", MODE_PRIVATE);
            s.edit().putBoolean("running", false).putBoolean("learning", false).putBoolean(AgentScriptRuntimeV4.SCRIPT_RUNNING, false).apply();
            if (runtime != null) runtime.interrupt();
            super.onInterrupt();
            android.widget.Toast.makeText(this, "Hassas uygulama algılandı. Yerel Ajan tamamen durduruldu.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (runtime == null) runtime = new AgentScriptRuntimeV4(this);
        if (runtime.isRunning()) {
            runtime.onEvent(event);
            return;
        }
        runtime.maybeStartFromOwnApp(event);
        super.onAccessibilityEvent(event);
    }

    @Override public void onInterrupt() {
        if (runtime != null) runtime.interrupt();
        super.onInterrupt();
    }

    @Override public void onDestroy() {
        if (runtime != null) runtime.destroy();
        super.onDestroy();
    }
}
