package dev.ipf.whitenoise.android.updates

/**
 * Returns whether this build may install the offered version through the in-app updater.
 *
 * Google Play builds delegate updates to Play, while Zapstore builds must still reject
 * equal or older versions even if stale release metadata reaches an update entry point.
 */
fun shouldStartInAppSelfUpdate(
    selfUpdateEnabled: Boolean,
    installedVersion: String,
    targetVersion: String,
): Boolean = selfUpdateEnabled && CalVer.compare(targetVersion, installedVersion) > 0
