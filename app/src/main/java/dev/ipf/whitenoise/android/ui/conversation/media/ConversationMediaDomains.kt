package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.media.MediaTrustDomain

/**
 * The outgoing domain of each of the user's own picks in the conversation on screen, by pick: a composer slot id or
 * a document URI. Composer, preview and pending tiles decode one pick in that pick's own worker without holding the
 * controller. Unset elsewhere, where every such decode stays single-use.
 */
internal val LocalPickMediaDomains =
    staticCompositionLocalOf<(String?) -> MediaTrustDomain> { { MediaTrustDomain.Unique } }
