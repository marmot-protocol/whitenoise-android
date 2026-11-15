@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.translation.ConversationTranslationSession
import dev.ipf.whitenoise.android.translation.LocalConversationTranslation
import dev.ipf.whitenoise.android.translation.MESSAGE_TRANSLATION_PENDING_TEST_TAG
import dev.ipf.whitenoise.android.translation.MESSAGE_TRANSLATION_TOGGLE_TEST_TAG
import dev.ipf.whitenoise.android.translation.MessageTranslationToggle
import dev.ipf.whitenoise.android.translation.MessageTranslationToggleSlotWidth
import dev.ipf.whitenoise.android.translation.TranslationRendition
import dev.ipf.whitenoise.android.translation.TranslationSource
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleBottomAlignmentLine
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageReactionSummary
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageSenderAvatarSlot
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class MessageReactionAlignmentScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun reactionAlignmentLight() =
        captureGallery(
            name = "message_reaction_alignment_light",
            dark = false,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
        )

    @Test
    fun reactionAlignmentDarkLargeRtl() =
        captureGallery(
            name = "message_reaction_alignment_dark_large_rtl",
            dark = true,
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
        )

    @Test
    fun reactionAlignmentWithTranslationLight() =
        captureGallery(
            name = "message_reaction_alignment_translation_light",
            dark = false,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            translated = true,
        )

    @Test
    fun reactionAlignmentWithTranslationDarkLargeRtl() =
        captureGallery(
            name = "message_reaction_alignment_translation_dark_large_rtl",
            dark = true,
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
            translated = true,
        )

    @Test
    fun reactionAlignmentWithPendingTranslationLight() =
        captureGallery(
            name = "message_reaction_alignment_pending_light",
            dark = false,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            pending = true,
        )

    @Test
    fun reactionAlignmentWithPendingTranslationDarkLargeRtl() =
        captureGallery(
            name = "message_reaction_alignment_pending_dark_large_rtl",
            dark = true,
            fontScale = 1.6f,
            layoutDirection = LayoutDirection.Rtl,
            pending = true,
        )

    @Test
    fun reactionAlignmentWithTranslationAmoled() =
        captureGallery(
            name = "message_reaction_alignment_translation_amoled",
            dark = true,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Ltr,
            translated = true,
            amoled = true,
        )

    @Test
    fun reactionAlignmentWithOriginalShownAmoledRtl() =
        captureGallery(
            name = "message_reaction_alignment_original_amoled_rtl",
            dark = true,
            fontScale = 1f,
            layoutDirection = LayoutDirection.Rtl,
            translated = true,
            amoled = true,
            showTranslation = false,
        )

    /** Seed real pending/completed sessions and assert the expected controls before capturing alignment. */
    private fun captureGallery(
        name: String,
        dark: Boolean,
        fontScale: Float,
        layoutDirection: LayoutDirection,
        translated: Boolean = false,
        pending: Boolean = false,
        amoled: Boolean = false,
        showTranslation: Boolean = true,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                val density = LocalDensity.current
                val scope = rememberCoroutineScope()
                val session =
                    remember {
                        ConversationTranslationSession(
                            scope,
                            { if (pending) awaitCancellation() else error("No translation requested") },
                            { true },
                        ).apply {
                            if (translated) {
                                renditions[OUTGOING_SOURCE] = TranslationRendition("Sieht gut aus", showTranslation)
                                renditions[INCOMING_SOURCE] =
                                    TranslationRendition("Kannst du die Datei prüfen?", showTranslation)
                            }
                            if (pending) {
                                request(OUTGOING_SOURCE)
                                request(INCOMING_SOURCE)
                            }
                        }
                    }
                CompositionLocalProvider(
                    LocalConversationTranslation provides session,
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    ReactionAlignmentGallery()
                }
            }
        }

        val toggles = composeRule.onAllNodesWithTag(MESSAGE_TRANSLATION_TOGGLE_TEST_TAG)
        val rings = composeRule.onAllNodesWithTag(MESSAGE_TRANSLATION_PENDING_TEST_TAG)
        toggles.assertCountEquals(if (translated) 2 else 0)
        rings.assertCountEquals(if (pending) 2 else 0)
        composeRule
            .onNodeWithTag(GALLERY_TAG)
            .captureRoboImage("src/test/snapshots/$name.png")
    }

    /** Compare both message directions within one fixed viewport under the requested theme and density. */
    @Composable
    private fun ReactionAlignmentGallery() {
        Surface(modifier = Modifier.width(360.dp).testTag(GALLERY_TAG)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IncomingReactionExample()
                OutgoingReactionExample()
            }
        }
    }

    /** Keep the incoming avatar, bubble-bottom alignment and side translation toggle in the production order. */
    @Composable
    private fun IncomingReactionExample() {
        Text("Incoming", style = MaterialTheme.typography.labelMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MessageSenderAvatarSlot(
                showSenderAvatar = true,
                title = "Alex",
                seed = "alex",
                pictureUrl = null,
                enabled = true,
                alignToBubbleBottom = true,
                onClick = {},
            )
            Column(
                modifier = Modifier.widthIn(max = 220.dp).alignBy(MessageBubbleBottomAlignmentLine),
                horizontalAlignment = Alignment.Start,
            ) {
                ReactionBubble(text = "Can you review the file?", mine = false)
                MessageReactionSummary(
                    tallies = listOf(ReactionTally(emoji = "👍", count = 2, mine = true)),
                    mine = false,
                    onClick = {},
                )
            }
            MessageTranslationToggle(
                INCOMING_SOURCE,
                Modifier.width(MessageTranslationToggleSlotWidth).alignBy { it.measuredHeight },
            )
        }
    }

    /** Place the outgoing chips against the bubble, with the seeded translation toggle beside the bubble. */
    @Composable
    private fun ColumnScope.OutgoingReactionExample() {
        Text(
            text = "Outgoing",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.End),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MessageTranslationToggle(
                OUTGOING_SOURCE,
                Modifier.width(MessageTranslationToggleSlotWidth).alignBy { it.measuredHeight },
            )
            Column(
                modifier = Modifier.widthIn(max = 220.dp).alignBy(MessageBubbleBottomAlignmentLine),
                horizontalAlignment = Alignment.End,
            ) {
                ReactionBubble(text = "Looks good to me", mine = true)
                MessageReactionSummary(
                    tallies = listOf(ReactionTally(emoji = "❤️", count = 3, mine = false)),
                    mine = true,
                    onClick = {},
                )
            }
        }
    }

    /** Use the shared bubble frame so background, padding and alignment reflect actual message rendering. */
    @Composable
    private fun ReactionBubble(
        text: String,
        mine: Boolean,
    ) {
        MessageBubbleFrame(
            presentation = messageBubblePresentation(deleted = false, mine = mine),
            highlighted = false,
            mine = mine,
            mentionedSelf = false,
            mentionedYouLabel = "Mentioned you",
        ) {
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    private companion object {
        const val GALLERY_TAG = "message-reaction-alignment-gallery"
        val INCOMING_SOURCE = TranslationSource("incoming", "Can you review the file?", "de")
        val OUTGOING_SOURCE = TranslationSource("outgoing", "Looks good to me", "de")
    }
}
