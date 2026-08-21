package tr.edu.balikesir.anketrapor;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** v0.4 görünüm katmanı: eski açıklamaları gerçek V4 yetenekleriyle eşler. */
public class MainActivityV3 extends MainActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().postDelayed(this::refreshV4Texts, 120L);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) getWindow().getDecorView().postDelayed(this::refreshV4Texts, 80L);
    }

    private void refreshV4Texts() { replaceRecursively(getWindow().getDecorView()); }

    private void replaceRecursively(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String s = String.valueOf(t.getText());
            if (s.contains("İlk aşamada görev metnini güvenli yerel hafızaya alır; kod sandbox'ı sonraki sürümdedir.")) {
                t.setText("Genel Agent Script motoru aktif: uygulama tıklama/yazma, pano, Ajan klasörü, görünür web araştırması, doğrulama ve XLSX çıktısı. AGENT/2 görevleri yeni APK gerektirmeden çalıştırılabilir.");
            } else if (s.startsWith("20.  Özel Agent Görevi Çalıştır")) {
                t.setText("20.  Özel Agent Görevi Çalıştır • v0.4");
            } else if (s.contains("Çekirdek APK'da INTERNET izni yok")) {
                t.setText("✓ Ana ajan sürecinde INTERNET yasak • yalnız ayrı web araştırma sürecinde ağ açık • SMS / OTP / rehber / mikrofon / kamera izni yok");
            } else if ("Güvenlik".equals(s)) {
                t.setOnClickListener(x -> showV4Security());
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) replaceRecursively(g.getChildAt(i));
        }
    }

    private void showV4Security() {
        new AlertDialog.Builder(this).setTitle("Güvenlik tasarımı • v0.4")
                .setMessage("• Ana ajan ve erişilebilirlik sürecinin INTERNET izni Android süreç politikasında reddedilir.\n" +
                        "• Yalnız görünür :web araştırma süreci internete çıkabilir; clipboard, Accessibility ve özel dosya içeriği bu sürece aktarılmaz.\n" +
                        "• SMS/OTP, rehber, kamera, mikrofon ve bildirim okuma izni yoktur.\n" +
                        "• Web motoru yalnız HTTPS public adresleri kabul eder; localhost/özel IP ve finans/şifre alanları engellenir.\n" +
                        "• Banka, SMS/OTP veya şifre yöneticisi uygulaması öne gelirse ajan tamamen durdurulur.\n" +
                        "• Yayınla/Paylaş/Gönder, ödeme ve silme gibi kritik son eylemler otomatik yapılmaz.\n\n" +
                        "Bu mimari riski ciddi biçimde azaltır; hiçbir yazılım mutlak sıfır-risk garantisi veremez.")
                .setPositiveButton("Tamam", null).show();
    }
}
