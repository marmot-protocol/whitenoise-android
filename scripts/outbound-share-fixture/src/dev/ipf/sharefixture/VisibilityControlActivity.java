package dev.ipf.sharefixture;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

/** A fresh caller UID without queries proves the independent receiver is actually filtered. */
public final class VisibilityControlActivity extends Activity {
    /** Queries implicitly before any receiver interaction and reports whether the fixture was visible. */
    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        boolean visible = false;
        Intent probe = new Intent(Intent.ACTION_SEND).setType("text/plain");
        for (ResolveInfo match : getPackageManager().queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY)) {
            if ("dev.ipf.sharefixture".equals(match.activityInfo.packageName)) visible = true;
        }
        Bundle result = new Bundle();
        result.putBoolean("visible", visible);
        ResultReceiver receiver = Build.VERSION.SDK_INT >= 33
                ? getIntent().getParcelableExtra("outbound_share_fixture.result", ResultReceiver.class)
                : getIntent().getParcelableExtra("outbound_share_fixture.result");
        if (receiver != null) receiver.send(RESULT_OK, result);
        finish();
    }
}
