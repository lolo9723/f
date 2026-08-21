package tr.edu.balikesir.anketrapor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * Export edilmeyen uygulama-içi IPC sağlayıcısı. :web süreci yalnız VM değişkenlerinin JSON snapshot'unu alır.
 * Accessibility, clipboard veya başka özel veri arayüzü sunmaz.
 */
public final class VmStateProvider extends ContentProvider {
    static final String AUTHORITY = "tr.edu.balikesir.yerelajan.vmstate";
    static final Uri URI = Uri.parse("content://" + AUTHORITY);
    private static final String VM_STATE_KEY = "agent_vm_state_v5";

    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if ("snapshot".equals(method) && getContext() != null) {
            String raw = new SecureStore(getContext()).get(VM_STATE_KEY, "{}");
            if (raw == null || raw.length() > 2_000_000) raw = "{}";
            out.putString("json", raw);
        }
        return out;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
