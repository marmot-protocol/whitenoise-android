package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.whitenoise.android.ui.conversation.media.shouldMaterializeAttachmentAutomatically
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactionArtworkPolicyTest {
    /** Auto-download follows the viewer's media policy and pause, never the message's authorship. */
    @Test
    fun reactionArtworkFollowsThePolicyAndNotTheMessageAuthor() {
        assertTrue(allowed(mediaAllowed = true, paused = false))
        assertFalse(allowed(mediaAllowed = false, paused = false))
        assertFalse(allowed(mediaAllowed = true, paused = true))
    }

    /** An own message would bypass the policy if its authorship were used, which reaction artwork must not do. */
    @Test
    fun anOwnMessageWouldBypassThePolicyIfAuthorshipWereUsed() {
        val ownMessage =
            shouldMaterializeAttachmentAutomatically(
                mine = true,
                mediaAutoDownloadAllowed = false,
                automaticDownloadsPaused = false,
            )
        assertTrue(ownMessage)
        assertFalse(allowed(mediaAllowed = false, paused = false))
    }

    /** The reaction artwork policy for the given media setting and pause state. */
    private fun allowed(
        mediaAllowed: Boolean,
        paused: Boolean,
    ) = reactionArtworkAutoDownloadAllowed(
        mediaAutoDownloadAllowed = mediaAllowed,
        automaticDownloadsPaused = paused,
    )
}
