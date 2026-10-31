package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.media.MediaTrustDomain

/**
 * The conversation's shared outgoing domain, which every one of the user's own picks in the conversation on screen
 * decodes in, for composer, preview and pending tiles that decode them without the controller. Unset elsewhere,
 * where those decodes stay single-use.
 */
internal val LocalPickedMediaDomain = staticCompositionLocalOf<MediaTrustDomain> { MediaTrustDomain.Unique }
