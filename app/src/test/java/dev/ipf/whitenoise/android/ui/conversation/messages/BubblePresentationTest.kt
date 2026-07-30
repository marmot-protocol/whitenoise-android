package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.graphics.toArgb
import dev.ipf.whitenoise.android.core.TimelineInvalidationPresentation
import dev.ipf.whitenoise.android.core.timelineInvalidationPresentation
import dev.ipf.whitenoise.android.state.OPAQUE_BLACK_ARGB
import dev.ipf.whitenoise.android.state.WCAG_AA_NORMAL_TEXT_CONTRAST
import dev.ipf.whitenoise.android.state.contrastRatio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePresentationTest {
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
    fun localPublishFailureKeepsPersistedFailureBubbleChrome() {
        val persistedFailure =
            timelineInvalidationPresentation("local_publish_failed") ==
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
                amoled = true,
                mine = true,
                customArgb = 0xFFFF0000,
                tokens = tokens,
                persistedFailure = persistedFailure,
            ),
        )
        assertTrue(persistedFailure)
        assertTrue(!shouldShowMessageStatus(mine = true, deleted = false, persistedFailure = persistedFailure))
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
        assertTrue(!shouldShowMessageStatus(mine = true, deleted = false, persistedFailure = persistedFailure))
    }

    @Test
    fun amoledCustomColorKeepsBlackBackgroundAndThemeContent() {
        val defaultPresentation = resolveBubblePresentationArgb(false, true, true, null, tokens)
        val customPresentation = resolveBubblePresentationArgb(false, true, true, 0xFF336699, tokens)

        assertEquals(OPAQUE_BLACK_ARGB, defaultPresentation.backgroundArgb)
        assertNull(defaultPresentation.borderOverrideArgb)
        assertEquals(OPAQUE_BLACK_ARGB, customPresentation.backgroundArgb)
        assertEquals(tokens.surfaceContentArgb, customPresentation.contentArgb)
        assertEquals(0xFF336699, customPresentation.borderOverrideArgb)
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
}
