package tr.edu.balikesir.anketrapor;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.view.accessibility.AccessibilityEvent;

/** Hazır modülleri korur; özel görevleri genel VM runtime ile çalıştırır. */
public class AgentAccessibilityServiceV4 extends TouchAgentServiceV2 {
    private static volatile AgentAccessibilityServiceV4 active;
    private static volatile String lastStartMessage="";
    private AgentScriptRuntimeV5 runtime;
    private boolean selfTestsOk;

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        active=this;
        selfTestsOk = AgentVm.selfTest()
                && AgentTemplateResolver.selfTest()
                && WebResearchActivity.selfTestNumbers()
                && AgentComplexSelfTest.run()
                && LocalModelRegistry.selfTest()
                && BundledModelInstaller.selfTest()
                && AgentScriptStarter.selfTest()
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

    /** 20. modülün doğrudan başlangıç yolu; dialog/tıklama event'i taklit etmez. */
    static boolean requestAgentStartFromUi() {
        AgentAccessibilityServiceV4 s=active;
        if(s==null){lastStartMessage="Erişilebilirlik servisi bağlı değil.";return false;}
        if(!s.selfTestsOk){lastStartMessage="Agent çekirdek öz testi başarısız.";return false;}
        if(s.runtime!=null&&s.runtime.isRunning()){lastStartMessage="Başka bir Agent görevi zaten çalışıyor.";return false;}

        if(s.runtime!=null)s.runtime.destroy();
        AgentScriptStarter.Result r=AgentScriptStarter.start(s);
        lastStartMessage=r.message;
        s.runtime=new AgentScriptRuntimeV5(s);
        if(!r.ok)return false;
        // State artık SCRIPT_RUNNING=true. onEvent(null) doğrudan pump'ı planlar.
        s.runtime.onEvent(null);
        android.widget.Toast.makeText(s,r.message,android.widget.Toast.LENGTH_LONG).show();
        return true;
    }

    static String lastStartMessage(){return lastStartMessage;}
    static boolean isConnectedAndHealthy(){AgentAccessibilityServiceV4 s=active;return s!=null&&s.selfTestsOk;}

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
        // Eski accessibility-click başlangıcı yalnız geriye uyumlu yedek yol olarak kalır.
        if (selfTestsOk) runtime.maybeStartFromOwnApp(event);
        super.onAccessibilityEvent(event);
    }

    @Override public void onInterrupt() {
        if (runtime != null) runtime.interrupt();
        super.onInterrupt();
    }

    @Override public void onDestroy() {
        if(active==this)active=null;
        if (runtime != null) runtime.destroy();
        super.onDestroy();
    }
}
