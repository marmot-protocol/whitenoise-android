package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.conversation.messages.colorFromArgb
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubblePresentation
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCard
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardKind
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardLayout
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardModel
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Uses the actual outgoing bubble palette, which differs from the app's button/accent palette. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h800dp-mdpi")
class NostrEventCardActionContrastScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private var foreground = Color.Unspecified
    private var plays = 0

    @Test
    fun darkOutgoingMediaControlsAreVisibleAndUseBubbleForeground() {
        media(dark = true, amoled = false, suffix = "dark")
    }

    @Test
    fun lightOutgoingMediaControlsAreVisibleAndUseBubbleForeground() {
        media(dark = false, amoled = false, suffix = "light")
    }

    @Test
    fun amoledOutgoingMediaControlsAreVisibleAndUseBubbleForeground() {
        media(dark = true, amoled = true, suffix = "amoled")
    }

    @Test
    fun expansionOnAWhiteOutgoingBubbleUsesItsForeground() {
        render(dark = true, amoled = false) {
            NostrEventCardLayout(140.dp, onExpand = {}) { Box(Modifier.height(220.dp)) }
        }
        assertForeground(ApplicationProvider.getApplicationContext<Context>().getString(R.string.nostr_event_expand))
    }

    private fun media(
        dark: Boolean,
        amoled: Boolean,
        suffix: String,
    ) {
        render(dark, amoled) {
            NostrEventCard(
                state =
                    NostrEventCardState.Loaded(
                        NostrEventCardModel(
                            kind = NostrEventCardKind.Note,
                            eventIdHex = "ab8b7c29853d30301910ae2ee26d8ea13be686c24c44f148664cdf8fa28290d7",
                            authorPubkeyHex = "b".repeat(64),
                            createdAt = 1,
                            eventKind = 1,
                            title = null,
                            summary = "A public music note with a direct MP4 and an explicit photo action.",
                            mediaUrl = "https://v.nostr.build/JjkLXIiAUWwAHJ2VA4i8yU.mp4",
                            imageUrls = listOf("https://images.example/photo.jpg"),
                        ),
                    ),
                authorDisplayName = { "Classical Music" },
                contentColor = foreground,
                onRetry = {},
                onCopy = {},
                onOpen = {},
                onPlayVideo = { plays++ },
            )
        }
        assertForeground("View image")
        assertForeground("Play video")
        rule.onNodeWithText("Play video").performClick()
        rule.runOnIdle { assertEquals(1, plays) }
        rule.onNodeWithTag("outgoing").captureRoboImage("src/test/snapshots/nostr_outgoing_media_controls_$suffix.png")
    }

    private fun render(
        dark: Boolean,
        amoled: Boolean,
        content: @Composable () -> Unit,
    ) {
        rule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                val presentation = messageBubblePresentation(deleted = false, mine = true)
                foreground = colorFromArgb(presentation.contentArgb)
                Surface(
                    color = colorFromArgb(presentation.backgroundArgb),
                    contentColor = foreground,
                    modifier = Modifier.width(310.dp).testTag("outgoing"),
                ) { Column { content() } }
            }
        }
    }

    private fun assertForeground(label: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        rule
            .onNodeWithText(label)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(
            "$label must inherit the actual bubble foreground",
            foreground,
            layouts
                .single()
                .layoutInput.style.color,
        )
    }
}
