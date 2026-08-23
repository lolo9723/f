package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.VpnService;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    public static final String PREFS = "quick_close_prefs";
    public static final String KEY_VPN_PACKAGE = "vpn_package";
    private TextView status;
    private TextView vpnStatus;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

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
        info.setText("Küçük ‘Kapat’ düğmesine dokununca Chrome zorla durdurulur, seçtiğin VPN uygulaması zorla durdurulur ve ardından Sahibinden açılır.");
        info.setTextSize(16f);
        info.setPadding(0, dp(18), 0, dp(18));
        root.addView(info, new LinearLayout.LayoutParams(-1, -2));

        status = new TextView(this);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 0, 0, dp(10));
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        vpnStatus = new TextView(this);
        vpnStatus.setTextSize(15f);
        vpnStatus.setGravity(Gravity.CENTER);
        vpnStatus.setPadding(0, 0, 0, dp(12));
        root.addView(vpnStatus, new LinearLayout.LayoutParams(-1, -2));

        Button accessibility = new Button(this);
        accessibility.setText("Erişilebilirliği Aç");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, new LinearLayout.LayoutParams(-1, -2));

        Button chooseVpn = new Button(this);
        chooseVpn.setText("VPN Uygulamasını Seç");
        chooseVpn.setOnClickListener(v -> chooseVpnApp());
        LinearLayout.LayoutParams vpnLp = new LinearLayout.LayoutParams(-1, -2);
        vpnLp.topMargin = dp(10);
        root.addView(chooseVpn, vpnLp);

        Button test = new Button(this);
        test.setText("Sahibinden’i Şimdi Aç");
        test.setOnClickListener(v -> QuickAccessibilityService.openSahibinden(this));
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(-1, -2);
        testLp.topMargin = dp(10);
        root.addView(test, testLp);

        setContentView(root);
        updateVpnStatus();
    }

    private void chooseVpnApp() {
        PackageManager pm = getPackageManager();
        Intent query = new Intent(VpnService.SERVICE_INTERFACE);
        List<ResolveInfo> services = pm.queryIntentServices(query, PackageManager.MATCH_ALL);
        Map<String, String> unique = new LinkedHashMap<>();

        for (ResolveInfo info : services) {
            if (info.serviceInfo == null || info.serviceInfo.packageName == null) continue;
            String pkg = info.serviceInfo.packageName;
            if (pkg.equals(getPackageName())) continue;
            CharSequence label = info.loadLabel(pm);
            if (label == null || label.length() == 0) {
                try { label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)); }
                catch (Exception ignored) { label = pkg; }
            }
            unique.put(pkg, label.toString());
        }

        if (unique.isEmpty()) {
            Toast.makeText(this, "Telefonda seçilebilir VPN uygulaması bulunamadı.", Toast.LENGTH_LONG).show();
            return;
        }

        List<String> packages = new ArrayList<>(unique.keySet());
        String[] labels = new String[packages.size()];
        for (int i = 0; i < packages.size(); i++) {
            labels[i] = unique.get(packages.get(i)) + "\n" + packages.get(i);
        }

        new AlertDialog.Builder(this)
                .setTitle("VPN uygulamasını seç")
                .setItems(labels, (dialog, which) -> {
                    prefs.edit().putString(KEY_VPN_PACKAGE, packages.get(which)).apply();
                    updateVpnStatus();
                    Toast.makeText(this, "VPN kaydedildi: " + unique.get(packages.get(which)), Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void updateVpnStatus() {
        String pkg = prefs.getString(KEY_VPN_PACKAGE, "");
        if (TextUtils.isEmpty(pkg)) {
            vpnStatus.setText("VPN seçilmedi.");
            return;
        }
        try {
            CharSequence label = getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0));
            vpnStatus.setText("VPN: " + label);
        } catch (Exception e) {
            vpnStatus.setText("VPN: " + pkg);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (status != null) {
            if (isAccessibilityEnabled()) status.setText("✓ Hazır — küçük Kapat düğmesi aktif.");
            else status.setText("Servis kapalı — aşağıdaki düğmeden etkinleştir.");
        }
        if (vpnStatus != null) updateVpnStatus();
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
