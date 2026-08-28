package dev.ipf.whitenoise.android.share

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Test-APK activity that launches a share from outside the target app package. */
class ExternalShareDispatchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE) ?: return finish()
        val uri = intent.getStringExtra(EXTRA_STREAM_URI)?.let(Uri::parse) ?: return finish()
        startActivity(
            Intent(Intent.ACTION_SEND)
                .setComponent(ComponentName(targetPackage, MAIN_ACTIVITY_CLASS_NAME))
                .setType("application/octet-stream")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .apply {
                    clipData = ClipData.newRawUri("shared file", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
        )
        finish()
    }

    companion object {
        const val EXTRA_TARGET_PACKAGE = "external_share_test.target_package"
        const val EXTRA_STREAM_URI = "external_share_test.stream_uri"
        const val MAIN_ACTIVITY_CLASS_NAME = "dev.ipf.whitenoise.android.MainActivity"
    }
}
