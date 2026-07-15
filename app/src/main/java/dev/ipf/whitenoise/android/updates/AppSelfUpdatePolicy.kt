package dev.ipf.whitenoise.android.updates

/**
 * Whether the active build runs the in-app self-update flow. Builds that don't
 * (the Google Play distribution) surface no in-app update UI at all — the
 * distributing store owns updates, and off-store update redirects violate policy.
 */
fun shouldStartInAppSelfUpdate(selfUpdateEnabled: Boolean): Boolean = selfUpdateEnabled
