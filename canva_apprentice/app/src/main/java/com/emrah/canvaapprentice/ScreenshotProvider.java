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

    /**
     * Exposes only already-created, immutable capture-specific screenshot evidence. Screenshot
     * creation itself must never pass through a shared mutable staging filename.
     */
    public static Uri uriFor(File file) {
        if (file == null || !ScreenshotFilePolicy.isCaptureFileName(file.getName())) {
            throw new IllegalArgumentException("Invalid screenshot evidence file");
        }
        return Uri.parse("content://" + AUTHORITY + "/" + file.getName());
    }

    public static void cleanupExpiredEvidence(File cacheDir, long nowMs) {
        File[] files = cacheDir == null ? null : cacheDir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file == null || !file.isFile()) continue;
            if (ScreenshotFilePolicy.shouldDeleteExpiredCapture(file.getName(), file.lastModified(), nowMs)) {
                file.delete();
            }
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return isAllowed(uri) ? "image/png" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!isAllowed(uri) || !"r".equals(mode)) throw new FileNotFoundException("Not allowed");
        String fileName = uri.getLastPathSegment();
        File file = new File(getContext().getCacheDir(), fileName);
        if (!file.exists() || !file.isFile()) throw new FileNotFoundException("Screenshot missing");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        if (!isAllowed(uri)) return null;
        String fileName = uri.getLastPathSegment();
        String[] cols = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor c = new MatrixCursor(cols);
        MatrixCursor.RowBuilder row = c.newRow();
        File file = new File(getContext().getCacheDir(), fileName);
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) row.add(fileName);
            else if (OpenableColumns.SIZE.equals(col)) row.add(file.exists() ? file.length() : 0L);
            else row.add(null);
        }
        return c;
    }

    private boolean isAllowed(Uri uri) {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) return false;
        if (uri.getPathSegments().size() != 1) return false;
        return ScreenshotFilePolicy.isCaptureFileName(uri.getLastPathSegment());
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
