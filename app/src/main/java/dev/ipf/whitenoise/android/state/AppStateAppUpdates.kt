package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.Intent
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.openExternalStoreListing
import dev.ipf.whitenoise.android.updates.AppUpdateConstants
import dev.ipf.whitenoise.android.updates.shouldStartInAppSelfUpdate

/**
 * Opens the White Noise Zapstore listing in its own task through the user's chosen link handler,
 * without forcing the Zapstore package, returning false when no handler can open it.
 */
internal fun openAppUpdateZapstoreListing(
    context: Context,
    startActivity: (Intent) -> Unit = context::startActivity,
): Boolean =
    openExternalStoreListing(
        context = context,
        listingUrl = AppUpdateConstants.ZAPSTORE_LISTING_URL,
        preferPackage = null,
        startActivity = startActivity,
    )

/** Opens the White Noise Zapstore listing in its own task, showing an error toast if no handler exists. */
fun WhiteNoiseAppState.openZapstoreListing(context: Context = appContext) {
    if (!openAppUpdateZapstoreListing(context = context)) {
        present(R.string.toast_zapstore_unavailable)
    }
}

fun WhiteNoiseAppState.handleAppUpdateAction(context: Context = appContext) {
    // Builds that don't self-update (the Google Play distribution) leave
    // updates entirely to the distributing store — no in-app flow and no
    // off-store listing redirect, which Play policy forbids.
    val latest = appUpdateInfo.latestVersion
    if (
        latest != null &&
        shouldStartInAppSelfUpdate(
            selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
            installedVersion = appUpdateInfo.installedVersion,
            targetVersion = latest,
        )
    ) {
        startAppSelfUpdate(latest)
        return
    }
    if (BuildConfig.SELF_UPDATE_ENABLED && latest == null) openZapstoreListing(context)
}
