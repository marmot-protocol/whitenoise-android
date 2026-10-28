package dev.ipf.whitenoise.android.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

/** Test-APK activity that launches a share from outside the target app package. */
public final class ExternalShareDispatchActivity extends Activity {
    public static final String EXTRA_TARGET_PACKAGE = "external_share_test.target_package";
    public static final String EXTRA_STREAM_NAME = "external_share_test.stream_name";
    public static final String EXTRA_STREAM_NAMES = "external_share_test.stream_names";
    public static final String EXTRA_SHARE_TEXT = "external_share_test.share_text";
    public static final String EXTRA_GRANT_READ = "external_share_test.grant_read";
    public static final String EXTRA_EMPTY_STREAM = "external_share_test.empty_stream";
    public static final String EXTRA_STREAM_BYTES = "external_share_test.stream_bytes";
    private static final String MAIN_ACTIVITY_CLASS_NAME = "dev.ipf.whitenoise.android.MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String targetPackage = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        String streamName = getIntent().getStringExtra(EXTRA_STREAM_NAME);
        String[] streamNames = getIntent().getStringArrayExtra(EXTRA_STREAM_NAMES);
        String shareText = getIntent().getStringExtra(EXTRA_SHARE_TEXT);
        boolean grantRead = getIntent().getBooleanExtra(EXTRA_GRANT_READ, true);
        boolean emptyStream = getIntent().getBooleanExtra(EXTRA_EMPTY_STREAM, false);
        long streamBytes = getIntent().getLongExtra(EXTRA_STREAM_BYTES, 3L);
        if (streamNames == null && streamName != null) {
            streamNames = new String[] {streamName};
        }
        if (targetPackage == null || (shareText == null && (streamNames == null || streamNames.length == 0))) {
            finish();
            return;
        }

        File directory = getCacheDir();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create the external source cache");
        }
        ArrayList<Uri> streamUris = new ArrayList<>();
        if (streamNames != null) {
            for (String name : streamNames) {
                streamUris.add(createExternalStream(directory, name, emptyStream, streamBytes));
            }
        }

        boolean multipleStreams = streamUris.size() > 1;
        String mimeType = streamUris.isEmpty() ? "text/plain" : "application/octet-stream";
        Intent shareIntent =
                new Intent(multipleStreams ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND)
                        .setComponent(new ComponentName(targetPackage, MAIN_ACTIVITY_CLASS_NAME))
                        .setType(mimeType);
        if (shareText != null) {
            shareIntent.putExtra(Intent.EXTRA_TEXT, shareText);
        }
        if (multipleStreams) {
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, streamUris);
        } else if (!streamUris.isEmpty()) {
            shareIntent.putExtra(Intent.EXTRA_STREAM, streamUris.get(0));
        }
        ClipData clipData = null;
        for (Uri uri : streamUris) {
            if (clipData == null) {
                clipData = ClipData.newRawUri("shared file", uri);
            } else {
                clipData.addItem(new ClipData.Item(uri));
            }
        }
        if (clipData != null) {
            shareIntent.setClipData(clipData);
            if (grantRead) {
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                for (Uri uri : streamUris) {
                    revokeUriPermission(targetPackage, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                }
            }
        }
        startActivity(shareIntent);
        finish();
    }

    /** Creates one provider-backed stream while rejecting path traversal from the test input. */
    private Uri createExternalStream(File directory, String streamName, boolean emptyStream, long streamBytes) {
        if (streamName == null || streamName.isEmpty() || !new File(streamName).getName().equals(streamName)) {
            throw new IllegalArgumentException("Stream name must be a simple file name");
        }
        if (streamBytes < 0L || streamBytes > 64L * 1024L * 1024L) {
            throw new IllegalArgumentException("External fixture size is outside its bounded test range");
        }
        File source = new File(directory, streamName);
        try {
            writeExternalFixture(source, emptyStream, streamBytes);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the external share fixture", exception);
        }
        return ExternalShareTestFileProvider.uriFor(this, source);
    }

    /** Replaces fixture contents without retaining bytes from an earlier cache file. */
    static void writeExternalFixture(File source, boolean emptyStream, long streamBytes) throws IOException {
        try (java.io.RandomAccessFile output = new java.io.RandomAccessFile(source, "rw")) {
            output.setLength(0L);
            output.setLength(emptyStream ? 0L : streamBytes);
            if (!emptyStream && streamBytes == 3L) {
                output.write(new byte[] {1, 2, 3});
            } else if (!emptyStream && streamBytes >= 4L) {
                output.write(new byte[] {80, 75, 3, 4});
            }
        }
    }
}
