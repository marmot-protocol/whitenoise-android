package dev.ipf.fixture;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

/** Issues and revokes ordinary cross-package read grants for synthetic SAF documents. */
public final class FixtureGrantReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String target = intent.getStringExtra("targetPackage");
        String documentId = intent.getStringExtra("documentId");
        if (target == null || !target.matches("dev\\.ipf\\.whitenoise\\.android\\.preview\\.pr[0-9]+")) return;
        if (documentId == null || !documentId.matches("[a-z]+")) return;
        Uri uri = DocumentsContract.buildDocumentUri("dev.ipf.fixture.documents", documentId);
        if ("dev.ipf.fixture.REVOKE".equals(intent.getAction())) {
            context.revokeUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else if ("dev.ipf.fixture.GRANT".equals(intent.getAction())) {
            context.grantUriPermission(target, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
    }
}
