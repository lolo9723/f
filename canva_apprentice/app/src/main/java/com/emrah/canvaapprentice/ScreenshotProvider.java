package com.emrah.canvaapprentice;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class ScreenshotProvider extends ContentProvider {
    public static final String AUTHORITY = "com.emrah.canvaapprentice.screenshot";
    private static final String FILE_NAME = "canva_agent_last.png";

    public static Uri uri() {
        return Uri.parse("content://" + AUTHORITY + "/" + FILE_NAME);
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return isAllowed(uri) ? "image/png" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!isAllowed(uri) || !"r".equals(mode)) throw new FileNotFoundException("Not allowed");
        File file = new File(getContext().getCacheDir(), FILE_NAME);
        if (!file.exists()) throw new FileNotFoundException("Screenshot missing");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!isAllowed(uri)) return null;
        String[] cols = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor c = new MatrixCursor(cols);
        MatrixCursor.RowBuilder row = c.newRow();
        File file = new File(getContext().getCacheDir(), FILE_NAME);
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) row.add(FILE_NAME);
            else if (OpenableColumns.SIZE.equals(col)) row.add(file.exists() ? file.length() : 0L);
            else row.add(null);
        }
        return c;
    }

    private boolean isAllowed(Uri uri) {
        return uri != null && AUTHORITY.equals(uri.getAuthority()) &&
                ("/" + FILE_NAME).equals(uri.getPath());
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
