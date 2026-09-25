package dev.ipf.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Read-only viewer receipt: the external process records the bytes it actually opened. */
public final class FixtureStatusProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!"dev.ipf.fixture.status".equals(uri.getAuthority()) || !"/viewer".equals(uri.getPath())) {
            throw new IllegalArgumentException("Unexpected fixture status URI");
        }
        MatrixCursor cursor = new MatrixCursor(new String[] {"sha256", "bytes", "views"});
        cursor.addRow(new Object[] {
            getContext().getSharedPreferences("viewer", 0).getString("sha256", ""),
            getContext().getSharedPreferences("viewer", 0).getLong("bytes", -1),
            getContext().getSharedPreferences("viewer", 0).getInt("views", 0)
        });
        return cursor;
    }

    @Override public String getType(Uri uri) { return "vnd.android.cursor.item/vnd.wnmatrix.viewer"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { throw new UnsupportedOperationException(); }
}
