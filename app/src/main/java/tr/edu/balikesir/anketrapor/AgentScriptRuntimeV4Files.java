package tr.edu.balikesir.anketrapor;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.util.ArrayList;
import java.util.Locale;

final class AgentScriptRuntimeV4Files {
    private AgentScriptRuntimeV4Files() {}

    static ArrayList<Uri> listImages(Context c, Uri tree, int max) throws Exception {
        ArrayList<Uri> out = new ArrayList<>();
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
        String[] projection = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cur = c.getContentResolver().query(children, projection, null, null, DocumentsContract.Document.COLUMN_DISPLAY_NAME + " ASC")) {
            if (cur == null) return out;
            int idI = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int mimeI = cur.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int nameI = cur.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            while (cur.moveToNext() && out.size() < Math.max(1, Math.min(max, 50))) {
                String id = idI >= 0 ? cur.getString(idI) : "";
                String mime = mimeI >= 0 ? cur.getString(mimeI) : "";
                String name = nameI >= 0 ? cur.getString(nameI) : "";
                boolean image = mime != null && mime.startsWith("image/");
                if (!image && name != null) {
                    String x = name.toLowerCase(Locale.ROOT);
                    image = x.endsWith(".jpg") || x.endsWith(".jpeg") || x.endsWith(".png") || x.endsWith(".webp") || x.endsWith(".heic");
                }
                if (image && id != null && !id.isEmpty()) out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        }
        return out;
    }
}
