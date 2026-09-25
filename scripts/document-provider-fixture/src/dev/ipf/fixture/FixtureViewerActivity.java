package dev.ipf.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.InputStream;
import java.security.MessageDigest;

/** External handler whose visible result proves the URI grant reached another app. */
public final class FixtureViewerActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setTextSize(18);
        label.setPadding(32, 32, 32, 32);
        try (InputStream input = getContentResolver().openInputStream(getIntent().getData())) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            long length = 0;
            int n;
            while ((n = input.read(buffer)) != -1) { sha.update(buffer, 0, n); length += n; }
            StringBuilder hex = new StringBuilder();
            for (byte b : sha.digest()) hex.append(String.format("%02x", b & 0xff));
            label.setText("WN fixture viewer\nBytes: " + length + "\nSHA-256: " + hex);
            int views = getSharedPreferences("viewer", MODE_PRIVATE).getInt("views", 0) + 1;
            getSharedPreferences("viewer", MODE_PRIVATE).edit().putString("sha256", hex.toString()).putLong("bytes", length).putInt("views", views).apply();
        } catch (Exception error) {
            label.setText("WN fixture viewer\nCould not read granted URI: " + error.getClass().getSimpleName());
            getSharedPreferences("viewer", MODE_PRIVATE).edit().putString("sha256", "ERROR").apply();
        }
        setContentView(label);
    }
}
