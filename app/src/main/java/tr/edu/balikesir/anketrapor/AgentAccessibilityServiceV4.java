package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;

/** Hazır modülleri korur; özel görevleri genel VM runtime ile çalıştırır. */
public class AgentAccessibilityServiceV4 extends TouchAgentServiceV2 {
    private AgentScriptRuntimeV5 runtime;
    private boolean selfTestsOk;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        selfTestsOk = AgentVm.selfTest()
                && AgentTemplateResolver.selfTest()
                && WebResearchActivity.selfTestNumbers()
                && AgentComplexSelfTest.run()
                && LocalModelRegistry.selfTest()
                && AgentScriptEngineV3.selfTest()
                && AgentScriptEngineV2.selfTest()
                && AgentScriptEngine.selfTest();
        runtime = new AgentScriptRuntimeV5(this);
        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = null;
        setServiceInfo(info);
        if (!selfTestsOk) {
            android.widget.Toast.makeText(this, "Agent çekirdek öz testi başarısız. Özel görevler güvenli biçimde devre dışı.", android.widget.Toast.LENGTH_LONG).show();
        }
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        if (SafetyPolicy.isBlockedPackage(this, pkg)) {
            SharedPreferences s = getSharedPreferences("yerel_agent_state", MODE_PRIVATE);
            s.edit().putBoolean("running", false).putBoolean("learning", false).putBoolean(AgentScriptRuntimeV5.SCRIPT_RUNNING, false).apply();
            if (runtime != null) runtime.interrupt();
            super.onInterrupt();
            android.widget.Toast.makeText(this, "Hassas uygulama algılandı. Yerel Ajan tamamen durduruldu.", android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        if (runtime == null) runtime = new AgentScriptRuntimeV5(this);
        if (runtime.isRunning()) { runtime.onEvent(event); return; }
        if (selfTestsOk) runtime.maybeStartFromOwnApp(event);
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
