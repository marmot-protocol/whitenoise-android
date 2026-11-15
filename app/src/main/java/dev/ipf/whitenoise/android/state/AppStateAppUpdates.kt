package dev.ipf.whitenoise.android.state

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.updates.AppUpdateConstants
import dev.ipf.whitenoise.android.updates.shouldStartInAppSelfUpdate

fun WhiteNoiseAppState.openZapstoreListing(context: Context = appContext) {
    val intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateConstants.ZAPSTORE_LISTING_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
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
