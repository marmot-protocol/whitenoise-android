package dev.ipf.whitenoise.android.share;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

/** Minimal provider owned by the test APK's external-source package. */
public final class ExternalShareTestFileProvider extends ContentProvider {
    private static final String AUTHORITY_SUFFIX = ".external-share-test-files";
    private static final String SHARED_FILES_PATH = "shared-files";
    private static final String OCTET_STREAM_MIME_TYPE = "application/octet-stream";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        resolve(uri);
        return OCTET_STREAM_MIME_TYPE;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new IllegalArgumentException("External share fixtures are read-only");
        }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        File file = resolve(uri);
        String[] columns =
                projection != null
                        ? projection
                        : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] values = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                values[index] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[index])) {
                values[index] = file.length();
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("External share fixtures are read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("External share fixtures are read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("External share fixtures are read-only");
    }

    private File resolve(Uri uri) {
        Context providerContext = getContext();
        if (providerContext == null) {
            throw new IllegalStateException("Provider context is unavailable");
        }
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())
                || !authority(providerContext).equals(uri.getAuthority())) {
            throw new IllegalArgumentException("Unexpected external share URI");
        }
        List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !SHARED_FILES_PATH.equals(segments.get(0))) {
            throw new IllegalArgumentException("Unexpected external share URI path");
        }
        String name = segments.get(1);
        if (name.isEmpty() || !new File(name).getName().equals(name)) {
            throw new IllegalArgumentException("Unexpected external share file name");
        }
        File file = new File(providerContext.getCacheDir(), name);
        if (!file.isFile()) {
            throw new IllegalArgumentException("External share fixture does not exist");
        }
        return file;
    }

    static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority(context))
                .appendPath(SHARED_FILES_PATH)
                .appendPath(file.getName())
                .build();
    }

    private static String authority(Context context) {
        return context.getPackageName() + AUTHORITY_SUFFIX;
    }
}
