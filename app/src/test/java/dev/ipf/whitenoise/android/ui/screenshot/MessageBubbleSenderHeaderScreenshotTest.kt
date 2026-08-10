package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFrame
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleSenderHeader
import dev.ipf.whitenoise.android.ui.conversation.messages.messageBubbleColumnMaxWidth
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
class MessageBubbleSenderHeaderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun longIncomingGroupMessageLight() {
        renderHarness(
            darkTheme = false,
            amoled = false,
            rtl = false,
            largeFont = false,
            missingAvatar = false,
            runSemantics = RunSemantics.SameSenderFollowUp,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_sender_header_long_light.png")
    }

    @Test
    fun twoSenderCompactSequenceDark() {
        renderHarness(
            darkTheme = true,
            amoled = false,
            rtl = false,
            largeFont = false,
            missingAvatar = false,
            runSemantics = RunSemantics.TwoDistinctSenders,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_sender_header_two_sender_dark.png")
    }

    @Test
    fun twoSenderCompactSequenceAmoled() {
        renderHarness(
            darkTheme = true,
            amoled = true,
            rtl = false,
            largeFont = false,
            missingAvatar = false,
            runSemantics = RunSemantics.TwoDistinctSenders,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_sender_header_two_sender_amoled.png")
    }

    @Test
    fun senderHeaderRtlLargeFontMissingAvatar() {
        renderHarness(
            darkTheme = false,
            amoled = false,
            rtl = true,
            largeFont = true,
            missingAvatar = true,
            runSemantics = RunSemantics.TwoDistinctSenders,
        )
        composeRule
            .onNodeWithTag(TAG)
            .captureRoboImage("src/test/snapshots/message_bubble_sender_header_rtl_large_missing_avatar.png")
    }

    @Composable
    private fun IncomingBubbleFixture(
        senderName: String,
        body: String,
        seed: String,
        missingAvatar: Boolean,
        showIdentityHeader: Boolean,
    ) {
        val presentation = messageBubblePresentation(deleted = false, mine = false)
        MessageBubbleFrame(
            presentation = presentation,
            highlighted = false,
            mine = false,
            mentionedSelf = false,
            mentionedYouLabel = "Mentioned you",
        ) {
            if (showIdentityHeader) {
                MessageBubbleSenderHeader(
                    name = senderName,
                    seed = seed,
                    avatarUrl = if (missingAvatar) null else "https://example.com/$seed.png",
                    profileLabel = "Open profile",
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    onProfileClick = {},
                    onLongPress = {},
                    enabled = true,
                )
            }
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    @Composable
    private fun SenderRunFixtures(
        runSemantics: RunSemantics,
        missingAvatar: Boolean,
    ) {
        when (runSemantics) {
            RunSemantics.SameSenderFollowUp -> {
                IncomingBubbleFixture(
                    senderName = "Alice Verylongdisplayname",
                    body =
                        "This is a long incoming group message that should use the wider " +
                            "bubble column without a permanent avatar lane.",
                    seed = "alice",
                    missingAvatar = missingAvatar,
                    showIdentityHeader = true,
                )
                IncomingBubbleFixture(
                    senderName = "Alice Verylongdisplayname",
                    body = "Follow-up from the same sender without a repeated header.",
                    seed = "alice",
                    missingAvatar = missingAvatar,
                    showIdentityHeader = false,
                )
            }
            RunSemantics.TwoDistinctSenders -> {
                IncomingBubbleFixture(
                    senderName = "Alice",
                    body = "First sender in a compact incoming row.",
                    seed = "alice",
                    missingAvatar = missingAvatar,
                    showIdentityHeader = true,
                )
                IncomingBubbleFixture(
                    senderName = "Bob",
                    body = "Second sender shows its own compact header.",
                    seed = "bob",
                    missingAvatar = missingAvatar,
                    showIdentityHeader = true,
                )
            }
        }
    }

    private fun renderHarness(
        darkTheme: Boolean,
        amoled: Boolean,
        rtl: Boolean,
        largeFont: Boolean,
        missingAvatar: Boolean,
        runSemantics: RunSemantics,
    ) {
        val transcriptWidth = 360.dp
        val bubbleColumnMaxWidth = messageBubbleColumnMaxWidth(transcriptWidth, 0.dp)
        composeRule.setContent {
            val layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(darkTheme = darkTheme, amoled = amoled, fontScale = if (largeFont) 2f else 1f) {
                    Surface(color = if (amoled) Color.Black else MaterialTheme.colorScheme.background) {
                        Row(
                            modifier =
                                Modifier
                                    .width(transcriptWidth)
                                    .padding(vertical = 16.dp)
                                    .testTag(TAG),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(
                                modifier =
                                    Modifier
                                        .widthIn(max = bubbleColumnMaxWidth),
                                horizontalAlignment = Alignment.Start,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                SenderRunFixtures(
                                    runSemantics = runSemantics,
                                    missingAvatar = missingAvatar,
                                )
                            }
                            Spacer(Modifier.width(48.dp).testTag(OPPOSITE_GUTTER_TAG))
                        }
                    }
                }
            }
        }
    }

    private enum class RunSemantics {
        SameSenderFollowUp,
        TwoDistinctSenders,
    }

    private companion object {
        const val TAG = "message-bubble-sender-header"
        const val OPPOSITE_GUTTER_TAG = "message-bubble-opposite-gutter"
    }
}
