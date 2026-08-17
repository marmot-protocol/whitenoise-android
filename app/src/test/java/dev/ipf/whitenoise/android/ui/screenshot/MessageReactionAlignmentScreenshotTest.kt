@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.ReactionTally
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageReactionSummary
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageSenderAvatarSlot
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
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

    private fun captureGallery(
        name: String,
        dark: Boolean,
        fontScale: Float,
        layoutDirection: LayoutDirection,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark) {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides layoutDirection,
                ) {
                    ReactionAlignmentGallery()
                }
            }
        }

        composeRule
            .onNodeWithTag(GALLERY_TAG)
            .captureRoboImage("src/test/snapshots/$name.png")
    }

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
                onClick = {},
            )
            Column(modifier = Modifier.width(220.dp), horizontalAlignment = Alignment.Start) {
                ReactionBubble(text = "Can you review the file?", mine = false)
                MessageReactionSummary(
                    tallies = listOf(ReactionTally(emoji = "👍", count = 2, mine = true)),
                    mine = false,
                    onClick = {},
                )
            }
        }
    }

    @Composable
    private fun ColumnScope.OutgoingReactionExample() {
        Text(
            text = "Outgoing",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.align(Alignment.End),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(modifier = Modifier.width(220.dp), horizontalAlignment = Alignment.End) {
                ReactionBubble(text = "Looks good to me", mine = true)
                MessageReactionSummary(
                    tallies = listOf(ReactionTally(emoji = "❤️", count = 3, mine = false)),
                    mine = true,
                    onClick = {},
                )
            }
        }
    }

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
    }
}
