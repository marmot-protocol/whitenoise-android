package dev.ipf.sharefixture;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.OpenableColumns;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;

/** Independent APK that observes only streams granted by a real Sharesheet selection. */
public final class ShareReceiverActivity extends Activity {
    /** Reports generated-file observations over a binder, without another provider or query grant. */
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent intent = getIntent();
        ArrayList<Uri> streams = streams(intent);
        ArrayList<String> hashes = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (Uri stream : streams) {
            hashes.add(hash(stream));
            names.add(displayName(stream));
        }
        Bundle result = new Bundle();
        result.putStringArrayList("hashes", hashes);
        result.putStringArrayList("names", names);
        result.putString("mime", intent.getType());
        result.putString("caption", intent.getStringExtra(Intent.EXTRA_TEXT));
        result.putInt("clipCount", intent.getClipData() == null ? 0 : intent.getClipData().getItemCount());
        result.putBoolean("readGrant", (intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        result.putBoolean("neighborReadable", readable(intent.getStringExtra("outbound_share_fixture.neighbor")));
        ResultReceiver receiver = extra(intent, "outbound_share_fixture.result", ResultReceiver.class);
        if (receiver != null) receiver.send(RESULT_OK, result);
        finish();
    }

    /** Reads the single- and multiple-stream contracts without linking the instrumented APK. */
    @SuppressWarnings("deprecation")
    private static ArrayList<Uri> streams(Intent intent) {
        if (Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())) {
            ArrayList<Uri> result = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri.class)
                    : intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            return result == null ? new ArrayList<>() : result;
        }
        ArrayList<Uri> result = new ArrayList<>();
        Uri stream = extra(intent, Intent.EXTRA_STREAM, Uri.class);
        if (stream != null) result.add(stream);
        return result;
    }

    /** Supports both Android 11 and typed modern parcelable access. */
    @SuppressWarnings("deprecation")
    private static <T> T extra(Intent intent, String key, Class<T> type) {
        return Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(key, type)
                : type.cast(intent.getParcelableExtra(key));
    }

    /** Reads only metadata exposed by the granted FileProvider URI. */
    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            return cursor != null && cursor.moveToFirst() ? cursor.getString(0) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Computes a bounded-buffer digest of the bytes the receiving UID can actually read. */
    private String hash(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return null;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    /** A neighboring URI deliberately lacks a grant even though the receiver learns its name. */
    private boolean readable(String value) {
        if (value == null) return false;
        try (InputStream input = getContentResolver().openInputStream(Uri.parse(value))) {
            return input != null;
        } catch (Exception ignored) {
            return false;
        }
    }
}
