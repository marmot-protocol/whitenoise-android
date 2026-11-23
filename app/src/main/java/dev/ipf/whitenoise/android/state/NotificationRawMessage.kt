package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppMessageRecordFfi

/** A raw event invalidated by MDK contributes neither notification content nor a navigation target. */
internal fun List<AppMessageRecordFfi>.validRawMessage(id: String): AppMessageRecordFfi? =
    firstOrNull { it.messageIdHex.equals(id, ignoreCase = true) }?.takeUnless(AppMessageRecordFfi::invalidated)
