package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.media.MediaTrustDomain

/**
 * The outgoing domain of each of the user's own picks in the conversation on screen, by pick: a composer slot id or
 * a document URI, which names the shared worker of the draft that pick belongs to. Composer and preview tiles decode a
 * pick there without holding the controller. Unset elsewhere, where every such decode stays single-use; a pending
 * bubble names its sent draft's domain on its attachment instead.
 */
internal val LocalPickMediaDomains =
    staticCompositionLocalOf<(String?) -> MediaTrustDomain> { { MediaTrustDomain.Unique } }
