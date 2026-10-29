package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.media.MediaTrustDomain

/**
 * The media trust domain of the conversation on screen, for images deep in its timeline, such as event-card
 * images, whose composables do not receive the conversation's controller. Unset outside a conversation, where
 * every such decode stays single-use.
 */
internal val LocalConversationMediaDomain = staticCompositionLocalOf<MediaTrustDomain> { MediaTrustDomain.Unique }

/**
 * The local domain of the user's own picks in the conversation on screen, for composer, preview and pending
 * tiles that decode them without the controller. Unset elsewhere, where those decodes stay single-use.
 */
internal val LocalPickedMediaDomain = staticCompositionLocalOf<MediaTrustDomain> { MediaTrustDomain.Unique }
