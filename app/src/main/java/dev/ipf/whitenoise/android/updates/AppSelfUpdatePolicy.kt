package dev.ipf.whitenoise.android.updates

/** Whether the active build should run the in-app self-update flow instead of an external listing. */
fun shouldStartInAppSelfUpdate(selfUpdateEnabled: Boolean): Boolean = selfUpdateEnabled

/** Whether the active build should open the external Zapstore listing for updates. */
fun shouldOpenExternalZapstoreListing(selfUpdateEnabled: Boolean): Boolean = !selfUpdateEnabled
