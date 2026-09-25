package dev.ipf.fixture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

/** Read-only viewer receipt: the external process records the bytes it actually opened. */
public final class FixtureStatusProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String documentId, Bundle extras) {
        if (!"grant".equals(method) && !"revoke".equals(method)) {
            throw new IllegalArgumentException("Unexpected fixture command");
        }
        String target = extras == null ? null : extras.getString("targetPackage");
        if (target == null || !target.matches("dev\\.ipf\\.whitenoise\\.android\\.preview\\.pr[0-9]+")) {
            throw new IllegalArgumentException("Unexpected preview package");
        }
        if (documentId == null || !documentId.matches("[a-z]+")) {
            throw new IllegalArgumentException("Unexpected document ID");
        }
        Uri uri = DocumentsContract.buildDocumentUri("dev.ipf.fixture.documents", documentId);
        if ("grant".equals(method)) {
            getContext().grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // Revoke every matching grant, including any held by the instrumentation package.
            getContext().revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        Bundle result = new Bundle();
        result.putBoolean("ok", true);
        return result;
    }

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
