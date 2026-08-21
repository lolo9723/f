package tr.edu.balikesir.anketrapor;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.text.Normalizer;
import java.util.Locale;

final class SafetyPolicy {
    private SafetyPolicy() {}

    private static final String[] BLOCKED_PACKAGES = {
            "com.google.android.apps.authenticator2",
            "com.authy.authy",
            "com.bitwarden.android",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "com.onepassword.android",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.pozitron.iscep",
            "com.garanti.cepsubesi",
            "com.ykb.android",
            "com.akbank.android.apps.akbank_direkt",
            "com.finansbank.mobile.cepsube",
            "tr.com.ziraatbank.mobil",
            "com.tmobtech.halkbank"
    };

    private static final String[] SENSITIVE_LABEL_TERMS = {
            "bank", "banka", "bankacilik", "bankacılık",
            "password", "sifre", "şifre", "authenticator",
            "mesajlar", "messages", "sms", "otp"
    };

    private static final String[] SENSITIVE_URL_TERMS = {
            "isbank.com.tr", "garanti.com.tr", "garantibbva.com.tr",
            "akbank.com", "ziraatbank.com.tr", "halkbank.com.tr",
            "yapikredi.com.tr", "qnb.com.tr", "enpara.com",
            "vakifbank.com.tr", "teb.com.tr", "denizbank.com",
            "ing.com.tr", "bitwarden.com", "1password.com", "lastpass.com",
            "authy.com"
    };

    private static final String[] PROTECTED_FINALS = {
            "yayinla", "paylas", "gonder", "guncelle",
            "publish", "share", "send",
            "simdi paylas", "hikayende paylas", "share now", "publish now",
            "sil", "delete", "kaldir", "remove", "uninstall", "hesabi sil", "delete account",
            "sifirla", "reset", "format",
            "satın al", "satin al", "öde", "ode", "pay now",
            "havale", "eft", "transfer", "onayla odeme", "ödemeyi onayla"
    };

    static boolean isBlockedPackage(Context context, String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) return false;
        for (String blocked : BLOCKED_PACKAGES) if (blocked.equals(packageName)) return true;
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(packageName, 0);
            String label = String.valueOf(pm.getApplicationLabel(ai));
            String n = normalize(label);
            for (String term : SENSITIVE_LABEL_TERMS) {
                if (n.contains(normalize(term))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    static boolean isProtectedFinal(String value) {
        String n = normalize(value);
        if (n.isEmpty()) return false;
        for (String s : PROTECTED_FINALS) {
            String b = normalize(s);
            if (n.equals(b) || n.startsWith(b + " ")) return true;
        }
        return false;
    }

    static boolean isSafeUrl(String url) {
        if (url == null) return false;
        String s = url.trim().toLowerCase(Locale.ROOT);
        if (!(s.startsWith("https://") || s.startsWith("http://"))) return false;
        for (String blocked : SENSITIVE_URL_TERMS) if (s.contains(blocked)) return false;
        return true;
    }

    static String normalize(String s) {
        if (s == null) return "";
        String x = s.trim().toLowerCase(new Locale("tr", "TR"));
        x = x.replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('ü', 'u').replace('ö', 'o').replace('ç', 'c');
        x = Normalizer.normalize(x, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return x.replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
