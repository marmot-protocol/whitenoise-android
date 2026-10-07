package dev.ipf.whitenoise.android.maestro

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry

/** Verify Android's permission authority, not the disappearance of its dialog or a Settings label. */
internal fun verifyMaestroNotificationPermission(postcondition: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    check(context.packageName == MaestroFixtureRunner.FIXTURE_PACKAGE)
    val granted =
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    check(granted == (postcondition == "notification-granted")) { "Unexpected Android notification permission" }
}
