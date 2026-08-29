package dev.ipf.whitenoise.android.share;

import android.app.Activity;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test-APK activity that launches a share from outside the target app package. */
public final class ExternalShareDispatchActivity extends Activity {
    public static final String EXTRA_TARGET_PACKAGE = "external_share_test.target_package";
    public static final String EXTRA_STREAM_NAME = "external_share_test.stream_name";
    private static final String MAIN_ACTIVITY_CLASS_NAME = "dev.ipf.whitenoise.android.MainActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String targetPackage = getIntent().getStringExtra(EXTRA_TARGET_PACKAGE);
        String streamName = getIntent().getStringExtra(EXTRA_STREAM_NAME);
        if (targetPackage == null || streamName == null) {
            finish();
            return;
        }
        if (streamName.isEmpty() || !new File(streamName).getName().equals(streamName)) {
            throw new IllegalArgumentException("Stream name must be a simple file name");
        }

        File directory = getCacheDir();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create the external source cache");
        }
        File source = new File(directory, streamName);
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(new byte[] {1, 2, 3});
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to create the external share fixture", exception);
        }

        Uri uri = ExternalShareTestFileProvider.uriFor(this, source);
        Intent shareIntent =
                new Intent(Intent.ACTION_SEND)
                        .setComponent(new ComponentName(targetPackage, MAIN_ACTIVITY_CLASS_NAME))
                        .setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, uri);
        shareIntent.setClipData(ClipData.newRawUri("shared file", uri));
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(shareIntent);
        finish();
    }
}
