package dev.ipf.whitenoise.android.share;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

import java.io.InputStream;
import java.security.MessageDigest;

/** Cooperative external target advertised only by the instrumentation APK. */
public final class OutboundShareTestTargetActivity extends Activity {
    public static final String EXTRA_NEIGHBOR_URI = "outbound_share_target.neighbor_uri";
    public static final String EXTRA_RESULT_RECEIVER = "outbound_share_target.result_receiver";
    public static final String KEY_STREAM_SHA256 = "stream_sha256";
    public static final String KEY_NEIGHBOR_READABLE = "neighbor_readable";

    /** Reads the granted stream and returns the observations to the instrumentation process. */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri stream = parcelableExtra(getIntent(), Intent.EXTRA_STREAM, Uri.class);
        String streamSha256 = stream == null ? null : sha256(stream);
        boolean neighborReadable = canReadNeighbor(getIntent().getStringExtra(EXTRA_NEIGHBOR_URI));

        Bundle result = new Bundle();
        result.putString(KEY_STREAM_SHA256, streamSha256);
        result.putBoolean(KEY_NEIGHBOR_READABLE, neighborReadable);
        ResultReceiver receiver = parcelableExtra(getIntent(), EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        if (receiver != null) {
            receiver.send(RESULT_OK, result);
        }
        finish();
    }

    /** Reads a parcelable extra without depending on libraries from the instrumented app. */
    @SuppressWarnings("deprecation")
    private static <T> T parcelableExtra(Intent intent, String key, Class<T> type) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, type);
        }
        return type.cast(intent.getParcelableExtra(key));
    }

    /** Checks whether the receiver process can open a neighboring cache URI that was not granted. */
    private boolean canReadNeighbor(String neighborUri) {
        if (neighborUri == null) {
            return false;
        }
        try (InputStream input = getContentResolver().openInputStream(Uri.parse(neighborUri))) {
            return input != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Hashes the granted image stream from the receiving app's process. */
    private String sha256(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
                hex.append(Character.forDigit(value & 0x0f, 16));
            }
            return hex.toString();
        } catch (Exception ignored) {
            return null;
        }
    }
}
