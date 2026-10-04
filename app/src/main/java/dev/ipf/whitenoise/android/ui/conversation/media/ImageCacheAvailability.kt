package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State

/**
 * What is known about retained bytes for one tile: [cached] is the availability hint, which a rejected payload may
 * revoke, and [resolved] turns true once the host and MDK probes behind it have finished, so an absent file is not
 * offered for download before the answer is known.
 */
internal class ImageCacheAvailability(
    val cached: MutableState<Boolean>,
    val resolved: State<Boolean>,
)
