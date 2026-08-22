package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** 1.0.4: model ağdan indirilmez; APK içindeki SHA doğrulanmış model yerel dosyaya hazırlanır. */
public class ModelSetupActivityV2 extends Activity {
    private static final long HARD_TIMEOUT_MS=120_000L;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private TextView status;
    private ProgressBar progress;
    private Button verify, close;
    private volatile boolean working=false, finished=false;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        if(LocalModelRegistry.looksInstalled(this,LocalModelRegistry.BUNDLED))showReady();
        else prepareBundled();
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(24),dp(20),dp(20));
        root.setBackgroundColor(Color.WHITE);
        root.addView(tv("Yerel Ajan • Gömülü Yerel Model",22,true));
        TextView info=tv("Model internetten indirilmiyor. Qwen3 0.6B No-Think INT4 modeli APK'nın içindedir; ilk kullanımda yalnızca uygulamanın özel model klasörüne kopyalanır ve SHA-256 ile doğrulanır.",14,false);
        info.setPadding(0,dp(12),0,dp(12));root.addView(info);
        status=tv("Hazırlanıyor…",15,true);root.addView(status);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(100);
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(8));pp.topMargin=dp(16);root.addView(progress,pp);
        verify=button("Modeli tam SHA-256 ile yeniden doğrula");verify.setOnClickListener(v->fullVerify());root.addView(verify,buttonLp(18));
        close=button("Kapat");close.setOnClickListener(v->finish());root.addView(close,buttonLp(8));
        setContentView(root);
    }

    private void prepareBundled(){
        if(working)return;
        working=true;finished=false;verify.setEnabled(false);close.setEnabled(false);progress.setProgress(0);
        if(!BundledModelInstaller.assetMetadataValid(this)){
            failUi("APK içindeki model paketi bulunamadı veya boyutu hatalı. Bu APK kullanılmamalı.");return;
        }
        ui.postDelayed(hardTimeout,HARD_TIMEOUT_MS);
        new Thread(()->{
            try{
                final long total=LocalModelRegistry.BUNDLED_BYTES;
                BundledModelInstaller.ensureInstalled(this,(done,t)->runOnUiThread(()->{
                    int pct=(int)Math.min(100,done*100L/Math.max(1,total));
                    progress.setProgress(pct);
                    status.setText("Yerel model hazırlanıyor • "+format(done)+" / "+format(total)+" • %"+pct);
                }));
                runOnUiThread(this::showReady);
            }catch(Throwable e){runOnUiThread(()->failUi("Model hazırlanamadı: "+message(e)));}
        },"bundled-model-prepare").start();
    }

    private void fullVerify(){
        if(working)return;
        working=true;finished=false;verify.setEnabled(false);close.setEnabled(false);progress.setIndeterminate(true);
        status.setText("Modelin tamamı SHA-256 ile doğrulanıyor…");
        ui.postDelayed(hardTimeout,HARD_TIMEOUT_MS);
        new Thread(()->{
            boolean ok=BundledModelInstaller.fullVerifyInstalled(this);
            runOnUiThread(()->{
                progress.setIndeterminate(false);
                if(ok)showReady(); else failUi("Model SHA-256 doğrulaması başarısız. Uygulamayı temiz kurman gerekir.");
            });
        },"bundled-model-verify").start();
    }

    private void showReady(){
        finished=true;working=false;ui.removeCallbacks(hardTimeout);progress.setIndeterminate(false);progress.setProgress(100);
        status.setText("✓ "+LocalModelRegistry.status(this)+"\nAğ bağlantısı gerekmez. Planlayıcı çevrimdışı çalışır.");
        verify.setEnabled(true);close.setEnabled(true);
    }

    private void failUi(String m){
        finished=true;working=false;ui.removeCallbacks(hardTimeout);progress.setIndeterminate(false);progress.setProgress(0);
        status.setText("✕ "+m);verify.setEnabled(LocalModelRegistry.file(this,LocalModelRegistry.BUNDLED).isFile());close.setEnabled(true);
    }

    private final Runnable hardTimeout=()->{
        if(!working||finished)return;
        status.setText("✕ Model hazırlama 120 saniyeyi aştı. İşlem güvenli biçimde sonlandırılıyor.");
        // Bu Activity ayrı :planner prosesindedir; ana ajanı öldürmeden takılan yerel kopyayı kesin sonlandırır.
        ui.postDelayed(()->android.os.Process.killProcess(android.os.Process.myPid()),700L);
    };

    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}
    private LinearLayout.LayoutParams buttonLp(int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(52));p.topMargin=dp(top);return p;}
    private TextView tv(String s,float size,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(28,31,36));if(bold)t.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);return t;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private String format(long n){return String.format(java.util.Locale.US,"%.2f MB",n/(1024d*1024d));}
    private String message(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    @Override protected void onDestroy(){ui.removeCallbacksAndMessages(null);super.onDestroy();}
}
