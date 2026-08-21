package tr.edu.balikesir.anketrapor;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Genel motor görünüm katmanı + yerel model planlayıcı girişi. */
public class MainActivityV3 extends MainActivity {
    private Button brainButton;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().postDelayed(this::refreshTexts, 120L);
        installBrainButton();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) getWindow().getDecorView().postDelayed(this::refreshTexts, 80L);
    }

    private void installBrainButton() {
        if (brainButton != null) return;
        brainButton = new Button(this);
        brainButton.setText("🧠");
        brainButton.setTextSize(20);
        brainButton.setAllCaps(false);
        brainButton.setContentDescription("Yerel model ve doğal dil planlayıcı");
        brainButton.setOnClickListener(v -> showBrainMenu());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(58), dp(58), Gravity.END | Gravity.BOTTOM);
        lp.setMargins(dp(12), dp(12), dp(18), dp(24));
        addContentView(brainButton, lp);
    }

    private void showBrainMenu() {
        String modelStatus = LocalModelRegistry.status(this);
        new AlertDialog.Builder(this)
                .setTitle("🧠 Yerel Akıllı Planlayıcı")
                .setMessage(modelStatus + "\n\nModel yalnız görev planı üretir. Ekrana doğrudan dokunamaz; plan AGENT güvenlik motorundan geçtikten sonra çalışır.")
                .setNegativeButton("Kapat", null)
                .setNeutralButton("Modeli Kur / Kontrol Et", (d,w) -> startActivity(new Intent(this, ModelSetupActivityV2.class)))
                .setPositiveButton("Görev Planla", (d,w) -> showPlannerPrompt())
                .show();
    }

    private void showPlannerPrompt() {
        if (LocalModelRegistry.strongestInstalled(this) == null) {
            new AlertDialog.Builder(this).setTitle("Yerel model gerekli")
                    .setMessage("Doğal dilden planlama için önce cihazına uygun modeli bir kez kur. ChatGPT'den aldığın AGENT kodlarını model olmadan da 20. modülde çalıştırabilirsin.")
                    .setNegativeButton("Kapat", null)
                    .setPositiveButton("Modeli Kur", (d,w) -> startActivity(new Intent(this, ModelSetupActivityV2.class))).show();
            return;
        }
        EditText input = new EditText(this);
        input.setMinLines(6); input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        String clip = clipboardText(); if (!clip.trim().startsWith("AGENT/")) input.setText(clip);
        new AlertDialog.Builder(this).setTitle("Ne yapmamı istiyorsun?").setView(input)
                .setNegativeButton("Vazgeç", null)
                .setPositiveButton("Planla", (d,w) -> {
                    String prompt=input.getText().toString().trim();
                    if(prompt.isEmpty()){toast("Görev metni boş.");return;}
                    launchPlanner(prompt);
                }).show();
    }

    private void launchPlanner(String prompt) {
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == LocalPlannerActivity.RESULT_OK_PLAN) {
                    String script = resultData == null ? "" : resultData.getString("script", "");
                    if (!script.isEmpty()) {
                        ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("Yerel Ajan Görevi",script));
                    }
                    new AlertDialog.Builder(MainActivityV3.this).setTitle("✓ Görev planı hazır")
                            .setMessage("Plan güvenlik şemasından geçti, şifreli yerel hafızaya kaydedildi ve panoya kopyalandı. 20. Özel Agent Görevi'ni açıp Pano'yu seçerek çalıştırabilirsin.")
                            .setPositiveButton("20. Modülü Aç", (d,w) -> openModule20()).show();
                } else {
                    String m=resultData==null?"Planlayıcı başarısız oldu.":resultData.getString("message","Planlayıcı başarısız oldu.");toast(m);
                }
            }
        };
        Intent i=new Intent(this,LocalPlannerActivity.class);i.putExtra(LocalPlannerActivity.EXTRA_PROMPT,prompt);i.putExtra(LocalPlannerActivity.EXTRA_RECEIVER,receiver);startActivity(i);
    }

    private void openModule20() {
        View v=findTextView(getWindow().getDecorView(),"20.  Özel Agent Görevi");
        View cur=v;
        for(int i=0;cur!=null&&i<6;i++){
            if(cur.hasOnClickListeners()){cur.performClick();return;}
            if(!(cur.getParent() instanceof View))break;cur=(View)cur.getParent();
        }
        toast("20. Özel Agent Görevi'ni listeden aç.");
    }

    private View findTextView(View v,String prefix) {
        if(v instanceof TextView && String.valueOf(((TextView)v).getText()).startsWith(prefix))return v;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=0;i<g.getChildCount();i++){View x=findTextView(g.getChildAt(i),prefix);if(x!=null)return x;}}
        return null;
    }

    private void refreshTexts() { replaceRecursively(getWindow().getDecorView()); }

    private void replaceRecursively(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String s = String.valueOf(t.getText());
            if (s.contains("İlk aşamada görev metnini güvenli yerel hafızaya alır; kod sandbox'ı sonraki sürümdedir.")) {
                t.setText("Genel Agent motoru: değişkenler, IF/ELSE, döngüler, hesaplama, uygulama otomasyonu, çok kaynaklı görünür web araştırması, dataset birleştirme ve XLSX çıktısı. AGENT/1–3 desteklenir.");
            } else if (s.startsWith("20.  Özel Agent Görevi Çalıştır")) {
                t.setText("20.  Özel Agent Görevi Çalıştır • GENEL MOTOR");
            } else if (s.contains("Çekirdek APK'da INTERNET izni yok")) {
                t.setText("✓ Ana ajan + yerel model süreçlerinde INTERNET yasak • yalnız ayrı web araştırma/model indirme sürecinde ağ açık • SMS / OTP / rehber / mikrofon / kamera izni yok");
            } else if ("Güvenlik".equals(s)) {
                t.setOnClickListener(x -> showSecurity());
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) replaceRecursively(g.getChildAt(i));
        }
    }

    private void showSecurity() {
        new AlertDialog.Builder(this).setTitle("Güvenlik tasarımı • Genel Motor")
                .setMessage("• Ana ajan/Accessibility ve yerel model planlayıcı süreçlerinde INTERNET Android süreç politikasında reddedilir.\n" +
                        "• Yalnız görünür :web araştırma ve model indirme süreci internete çıkar; Accessibility bu süreçte yoktur.\n" +
                        "• Model doğrudan tıklamaz; yalnız AGENT planı üretir ve parser doğrulaması zorunludur.\n" +
                        "• SMS/OTP, rehber, kamera, mikrofon ve bildirim okuma izni yoktur.\n" +
                        "• Web motoru yalnız HTTPS public adresleri kabul eder; localhost/özel IP ve finans/şifre alanları engellenir.\n" +
                        "• Banka, SMS/OTP veya şifre yöneticisi uygulaması öne gelirse ajan tamamen durdurulur.\n" +
                        "• Yayınla/Paylaş/Gönder, ödeme ve silme gibi kritik son eylemler otomatik yapılmaz.\n\n" +
                        "Hiçbir genel amaçlı otomasyon CAPTCHA, site engeli veya erişilemeyen UI karşısında mutlak başarı garantisi veremez; motor takılmak yerine açık hata üretmek üzere tasarlanmıştır.")
                .setPositiveButton("Tamam", null).show();
    }

    private String clipboardText(){try{ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);ClipData d=cm==null?null:cm.getPrimaryClip();if(d==null||d.getItemCount()==0)return "";CharSequence x=d.getItemAt(0).coerceToText(this);return x==null?"":x.toString();}catch(Exception e){return "";}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
