package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    private TextView status;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        int pad = dp(22);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Kapat → Sahibinden");
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView info = new TextView(this);
        info.setText("Erişilebilirlik servisini bir kez aç. Sonra her uygulamanın üzerinde küçük ‘Kapat’ düğmesi görünür. Düğmeye dokununca mevcut ekrandan çıkılır ve Sahibinden açılır.");
        info.setTextSize(16f);
        info.setPadding(0, dp(18), 0, dp(18));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(14));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button accessibility = new Button(this);
        accessibility.setText("Erişilebilirliği Aç");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, new LinearLayout.LayoutParams(-1, -2));

        Button test = new Button(this);
        test.setText("Sahibinden’i Şimdi Aç");
        test.setOnClickListener(v -> QuickAccessibilityService.openSahibinden(this));
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(-1, -2);
        testLp.topMargin = dp(10);
        root.addView(test, testLp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isAccessibilityEnabled()) {
            status.setText("✓ Hazır — küçük Kapat düğmesi aktif.");
        } else {
            status.setText("Servis kapalı — aşağıdaki düğmeden etkinleştir.");
        }
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName component = new ComponentName(this, QuickAccessibilityService.class);
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabled);
        while (splitter.hasNext()) {
            ComponentName current = ComponentName.unflattenFromString(splitter.next());
            if (component.equals(current)) return true;
        }
        return false;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
