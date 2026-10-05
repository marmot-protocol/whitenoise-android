package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.share.ShareRequest

/** Resolves the request whose import or picker is being dismissed. */
internal fun shareRequestToCancel(
    inbound: ShareRequest?,
    visible: ShareRequest?,
): ShareRequest? = inbound?.takeUnless { it.payload.importReady } ?: visible ?: inbound
