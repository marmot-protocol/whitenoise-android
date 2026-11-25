package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_DETAILS_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageDetailsScreen
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Message details on the shared top bar: start-aligned title, Back arrow and the page's bar colour. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class MessageDetailsChromeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private lateinit var previousZone: TimeZone

    /** Pins the clock zone so the sent time renders the same on every machine. */
    @Before fun setClockZone() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Restores the clock zone for later tests. */
    @After fun restoreClockZone() {
        TimeZone.setDefault(previousZone)
    }

    /** Light theme: the bar matches the page surface. */
    @Test fun detailsLight() = capture("message_details_light", dark = false)

    /** AMOLED keeps the bar and its Back arrow on the black canvas. */
    @Test fun detailsAmoled() = capture("message_details_amoled", dark = true, amoled = true)

    /** Renders a received message's details, records the page and checks that Back dismisses it once. */
    private fun capture(
        name: String,
        dark: Boolean,
        amoled: Boolean = false,
    ) {
        var dismissed = 0
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = dark, amoled = amoled) {
                MessageDetailsScreen(
                    record = record(),
                    status = MessageStatus.Received,
                    mine = false,
                    senderDisplayName = "Alice",
                    senderNpub = "npub1alice",
                    senderAvatarUrl = null,
                    reactions = emptyList(),
                    recipients = emptyList(),
                    attachmentLabels = emptyList(),
                    onDismissRequest = { dismissed++ },
                    onCopy = {},
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(MESSAGE_DETAILS_TAG).captureRoboImage("src/test/snapshots/$name.png")
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
    }

    /** Builds a received text message. */
    private fun record() =
        AppMessageRecordFfi(
            invalidated = false,
            messageIdHex = "aa".repeat(32),
            direction = "received",
            groupIdHex = "bb".repeat(32),
            sender = "cc".repeat(32),
            plaintext = "See you at the garden at six.",
            contentTokens = EMPTY_DOCUMENT,
            kind = 9uL,
            tags = emptyList(),
            sourceEpoch = null,
            retentionSeconds = null,
            retentionExpiresAt = null,
            recordedAt = 1_800_000_000uL,
            receivedAt = 1_800_000_000uL,
            sticker = null,
        )

    private companion object {
        val EMPTY_DOCUMENT =
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = byteArrayOf())
    }
}
