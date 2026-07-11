package dev.ipf.darkmatter.updates

/** Whether the active build should run the in-app self-update flow instead of an external listing. */
fun shouldStartInAppSelfUpdate(selfUpdateEnabled: Boolean): Boolean = selfUpdateEnabled

/** Whether the active build should open the external Zapstore listing for updates. */
fun shouldOpenExternalZapstoreListing(selfUpdateEnabled: Boolean): Boolean = !selfUpdateEnabled

sealed interface AppUpdateAction {
    data class StartInAppSelfUpdate(
        val version: String,
    ) : AppUpdateAction

    data object OpenExternalListing : AppUpdateAction

    data object NoOp : AppUpdateAction
}

/** Pure policy for what to do after a refreshed [AppUpdateInfo] is available. */
fun decideAppUpdateAction(
    selfUpdateEnabled: Boolean,
    info: AppUpdateInfo,
    appInForeground: Boolean,
): AppUpdateAction {
    if (!appInForeground || !info.isUpdateAvailable) return AppUpdateAction.NoOp
    val latest = info.latestVersion ?: return AppUpdateAction.NoOp
    return if (shouldStartInAppSelfUpdate(selfUpdateEnabled)) {
        AppUpdateAction.StartInAppSelfUpdate(latest)
    } else {
        AppUpdateAction.OpenExternalListing
    }
}
