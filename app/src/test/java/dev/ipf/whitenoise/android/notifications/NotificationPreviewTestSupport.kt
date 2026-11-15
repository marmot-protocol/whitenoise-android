package dev.ipf.whitenoise.android.notifications

import android.content.Context

/** Test shorthand for the two-state choice most privacy tests need: full previews or hidden. */
internal suspend fun NotificationPreviewPreferences.setEnabled(
    context: Context,
    value: Boolean,
    scrub: (suspend () -> Boolean)? = null,
): Boolean {
    val mode = if (value) NotificationPreviewMode.FULL else NotificationPreviewMode.NONE
    return if (scrub == null) setMode(context, mode) else setMode(context, mode, scrub)
}
