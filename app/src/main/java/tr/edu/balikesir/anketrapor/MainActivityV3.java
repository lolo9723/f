package tr.edu.balikesir.anketrapor;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** v0.3 görünüm katmanı: eski v0.1 açıklamalarını gerçek yeteneklerle eşler. */
public class MainActivityV3 extends MainActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().postDelayed(this::refreshV3Texts, 120L);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) getWindow().getDecorView().postDelayed(this::refreshV3Texts, 80L);
    }

    private void refreshV3Texts() {
        replaceRecursively(getWindow().getDecorView());
    }

    private void replaceRecursively(View v) {
        if (v instanceof TextView) {
            TextView t = (TextView) v;
            String s = String.valueOf(t.getText());
            if (s.contains("İlk aşamada görev metnini güvenli yerel hafızaya alır; kod sandbox'ı sonraki sürümdedir.")) {
                t.setText("Agent Script kodunu güvenli yerel yorumlayıcıyla çalıştırır. Chrome/Instagram/Canva ve diğer uygulamalarda izinli komutları yürütür; Excel çıktısı oluşturabilir.");
            } else if (s.equals("20.  Özel Agent Görevi Çalıştır")) {
                t.setText("20.  Özel Agent Görevi Çalıştır • v0.3");
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) replaceRecursively(g.getChildAt(i));
        }
    }
}
