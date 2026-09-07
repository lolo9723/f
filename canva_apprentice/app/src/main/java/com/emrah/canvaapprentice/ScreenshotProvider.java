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
     * Exposes only already-created, immutable capture-specific screenshot evidence owned by the
     * currently active execution lease. A stale capture from an older teacher/action chain must
     * never be attachable to a newer visual request even if its filename is otherwise well formed.
     */
    public static Uri uriFor(File file) {
        if (file == null || !ScreenshotFilePolicy.isCaptureFileForCurrentLease(file.getName())) {
            throw new IllegalArgumentException("Invalid or stale screenshot evidence file");
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
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())
                || uri.getPathSegments().size() != 1 || !"r".equals(mode)) {
            throw new FileNotFoundException("Not allowed");
        }

        final String fileName = uri.getLastPathSegment();
        final String leaseToken = TeacherExecutionLease.currentGlobalToken();
        if (!ScreenshotFilePolicy.isCaptureFileForLease(fileName, leaseToken)) {
            throw new FileNotFoundException("Not allowed");
        }

        ParcelFileDescriptor descriptor = TeacherExecutionLease.withGlobalCurrentChecked(
                leaseToken,
                null,
                () -> {
                    // Re-validate the filename while the lease monitor is held. beginGlobal() and
                    // invalidateGlobal() use the same monitor, so ownership cannot rotate between
                    // this check and ParcelFileDescriptor.open().
                    if (!ScreenshotFilePolicy.isCaptureFileForLease(fileName, leaseToken)) {
                        return null;
                    }
                    File file = new File(getContext().getCacheDir(), fileName);
                    if (!file.exists() || !file.isFile()) {
                        throw new FileNotFoundException("Screenshot missing");
                    }
                    return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                });
        if (descriptor == null) throw new FileNotFoundException("Stale execution lease");
        return descriptor;
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

    /**
     * Re-check ownership every time the provider is accessed, not only when the URI is created.
     * If a new teacher request rotates or invalidates the execution lease after sharing, the old
     * URI immediately becomes unreadable. openFile() additionally performs its validation and
     * descriptor open atomically under the execution-lease monitor.
     */
    private boolean isAllowed(Uri uri) {
        if (uri == null || !AUTHORITY.equals(uri.getAuthority())) return false;
        if (uri.getPathSegments().size() != 1) return false;
        return ScreenshotFilePolicy.isCaptureFileForCurrentLease(uri.getLastPathSegment());
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
