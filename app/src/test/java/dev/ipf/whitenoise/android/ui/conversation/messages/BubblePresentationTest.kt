package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.graphics.toArgb
import dev.ipf.whitenoise.android.core.TimelineInvalidationPresentation
import dev.ipf.whitenoise.android.core.timelineInvalidationPresentation
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import dev.ipf.whitenoise.android.ui.conversation.share.parseSharedUserFromText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePresentationTest {
    private val sampleNpub =
        "npub180cvv07t2zynsj7gmj4hu77davwc9x7kx00c7m92fw4wwjfn3z2qly42e0"

    private val tokens =
        BubblePresentationTokens(
            errorBackgroundArgb = 0xFFFFDAD6,
            errorContentArgb = 0xFF410002,
            surfaceBackgroundArgb = 0xFFE1E3E4,
            surfaceContentArgb = 0xFF444748,
            mineBackgroundArgb = 0xFFB5EFFF,
            mineContentArgb = 0xFF001F28,
            mentionAccentArgb = 0xFF006780,
        )

    @Test
    fun deletedBubbleIgnoresCustomColors() {
        assertEquals(
            BubblePresentation(OPAQUE_BLACK_ARGB, 0xFF444748, 0xFF006780),
            resolveBubblePresentationArgb(
                deleted = true,
                amoled = true,
                mine = false,
                customArgb = 0xFFFF0000,
                tokens = tokens,
            ),
        )
    }

    @Test
    fun localPublishFailureKeepsOrdinaryChromeButNoDeliveryGlyph() {
        // #1747: the body and normal chrome are retained, and the delivery
        // glyph is suppressed so a "Sending" clock cannot contradict the
        // "Delivery not confirmed" warning rendered beside it.
        assertEquals(
            TimelineInvalidationPresentation.UnconfirmedDelivery,
            timelineInvalidationPresentation("local_publish_failed"),
        )
        assertTrue(
            !shouldShowMessageStatus(
                mine = true,
                deleted = false,
                presentation = TimelineInvalidationPresentation.UnconfirmedDelivery,
            ),
        )
        // A delivered row still shows its glyph.
        assertTrue(
            shouldShowMessageStatus(
                mine = true,
                deleted = false,
                presentation = TimelineInvalidationPresentation.None,
            ),
        )
    }

    @Test
    fun unknownInvalidationKeepsPersistedFailureBubbleChrome() {
        val persistedFailure =
            timelineInvalidationPresentation("FutureReason") ==
                TimelineInvalidationPresentation.PersistedFailure

        assertEquals(
            BubblePresentation(
                backgroundArgb = tokens.errorBackgroundArgb,
                contentArgb = tokens.errorContentArgb,
                mentionAccentArgb = tokens.mentionAccentArgb,
                suppressBorder = true,
            ),
            resolveBubblePresentationArgb(
                deleted = false,
                amoled = false,
                mine = true,
                customArgb = 0xFFFF0000,
                tokens = tokens,
                persistedFailure = persistedFailure,
            ),
        )
        assertTrue(persistedFailure)
        assertTrue(
            !shouldShowMessageStatus(
                mine = true,
                deleted = false,
                presentation = TimelineInvalidationPresentation.PersistedFailure,
            ),
        )
    }

    @Test
    fun amoledCustomColorKeepsBlackBackgroundAndRemovesBlueFromBorder() {
        val defaultPresentation = resolveBubblePresentationArgb(false, true, true, null, tokens)
        val customPresentation = resolveBubblePresentationArgb(false, true, true, 0xFF336699, tokens)

        assertEquals(OPAQUE_BLACK_ARGB, defaultPresentation.backgroundArgb)
        assertNull(defaultPresentation.borderOverrideArgb)
        assertEquals(OPAQUE_BLACK_ARGB, customPresentation.backgroundArgb)
        assertEquals(tokens.surfaceContentArgb, customPresentation.contentArgb)
        assertEquals(0xFF336600, customPresentation.borderOverrideArgb)
        assertNull(resolveBubblePresentationArgb(false, true, true, 0xFF0000FF, tokens).borderOverrideArgb)
    }

    @Test
    fun customColorGetsWcagReadableContentColor() {
        val presentation = resolveBubblePresentationArgb(false, false, false, 0xFF777777, tokens)

        assertTrue(contrastRatio(presentation.contentArgb, presentation.backgroundArgb) >= WCAG_AA_NORMAL_TEXT_CONTRAST)
        assertNull(presentation.borderOverrideArgb)
    }

    @Test
    fun customColorKeepsSemanticMentionAccent() {
        val presentation = resolveBubblePresentationArgb(false, false, false, 0xFF336699, tokens)

        assertEquals(0xFF006780, presentation.mentionAccentArgb)
    }

    @Test
    fun composeColorConversionPreservesArgbBits() {
        assertEquals(0xFF336699.toInt(), colorFromArgb(0xFF336699).toArgb())
    }

    @Test
    fun standardColorsKeepMaterialPairedContentTokens() {
        assertEquals(
            BubblePresentation(0xFFB5EFFF, 0xFF001F28, 0xFF006780),
            resolveBubblePresentationArgb(false, false, true, null, tokens),
        )
        assertEquals(
            BubblePresentation(0xFFE1E3E4, 0xFF444748, 0xFF006780),
            resolveBubblePresentationArgb(false, false, false, null, tokens),
        )
    }

    @Test
    fun npubWithFreeTextKeepsTheMessageBodyVisible() {
        val body = "Please follow this profile\nnostr:$sampleNpub"
        val sharedUser = parseSharedUserFromText(body)

        assertNull(sharedUser)
        assertEquals(
            body,
            messageBodyTextToRender(
                displayedBody = body,
                deleted = false,
                persistedFailure = false,
                structuredShareOwnsBody = sharedUser != null,
                hasPendingMediaName = false,
                hasConfirmedMedia = false,
                mediaCaption = null,
            ),
        )
    }
}
