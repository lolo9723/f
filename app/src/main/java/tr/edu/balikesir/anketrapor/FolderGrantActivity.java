package tr.edu.balikesir.anketrapor;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public class FolderGrantActivity extends Activity {
    private static final int REQ_TREE = 2401;
    public static final String PREF = "yerel_agent_folder";
    public static final String KEY_URI = "ajan_tree_uri";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, REQ_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_TREE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try { getContentResolver().takePersistableUriPermission(uri, flags); } catch (Exception ignored) {}
            getSharedPreferences(PREF, MODE_PRIVATE).edit().putString(KEY_URI, uri.toString()).apply();
            Toast.makeText(this, "Ajan klasörü kaydedildi. Bundan sonra tekrar seçmen gerekmeyecek.", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
