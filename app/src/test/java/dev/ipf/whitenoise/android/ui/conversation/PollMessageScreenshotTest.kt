package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
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
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_ACTION_MENU_TEST_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MESSAGE_DETAILS_TAG
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageDetailsScreen
import dev.ipf.whitenoise.android.ui.conversation.reactions.REACTION_PILL_TEST_TAG
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Baselines of actual timeline poll chrome, its passive action preview and reply composer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class PollMessageScreenshotTest : PollMessageTestFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    @Before fun bindRoster() = runTest { pollController.retryMembers() }

    @After fun clearController() {
        pollController.onCleared()
    }

    @Test fun pollReactionsLight() = render("light", PollScreenshotConfiguration(sent = true))

    @Test fun pollReactionsDark() = render("dark", PollScreenshotConfiguration(dark = true, sent = true))

    @Test fun pendingPollKeepsVotingLight() = render("pending_light", PollScreenshotConfiguration(pending = true))

    @Test fun closedPollReactionsAmoledRtlLarge() =
        render(
            "amoled_rtl_large",
            PollScreenshotConfiguration(dark = true, amoled = true, rtl = true, fontScale = 2f, closed = true),
        )

    @Test fun pollActionMenuLight() = render("menu_light", PollScreenshotConfiguration(menu = true))

    @Test fun pollReplyComposerAmoled() =
        render(
            "reply_amoled",
            PollScreenshotConfiguration(dark = true, amoled = true, reply = true, closed = true),
        )

    @Test fun pollInfoLight() {
        val item = pollMessage(closed = true)
        composeRule.setContent {
            WhiteNoiseTheme {
                MessageDetailsScreen(
                    record = item.record,
                    status = item.status,
                    mine = false,
                    senderDisplayName = "Alice",
                    senderNpub = "npub-${item.record.sender}",
                    senderAvatarUrl = null,
                    reactions = emptyList(),
                    recipients = emptyList(),
                    attachmentLabels = emptyList(),
                    onDismissRequest = {},
                    onCopy = {},
                )
            }
        }
        composeRule
            .onNodeWithTag(MESSAGE_DETAILS_TAG)
            .captureRoboImage("src/test/snapshots/poll_message_info_light.png")
    }

    private fun render(
        name: String,
        configuration: PollScreenshotConfiguration = PollScreenshotConfiguration(),
    ) {
        val item =
            pollMessage(
                closed = configuration.closed,
                mine = configuration.pending || configuration.sent,
                status =
                    when {
                        configuration.pending -> MessageStatus.Pending
                        configuration.sent -> MessageStatus.Sent
                        else -> MessageStatus.Received
                    },
            )
        retain(item)
        runTest { pollController.toggleReaction("👍", item.record) }
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = configuration.dark, amoled = configuration.amoled) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (configuration.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(1f, configuration.fontScale),
                ) {
                    Surface(Modifier.width(360.dp).testTag("poll-discussion")) {
                        // Reaction pills overlap the footer; leave capture room for their full outline.
                        Column(Modifier.padding(bottom = 24.dp)) {
                            RealPollMessage(item, configuration.menu) { }
                            if (configuration.reply) {
                                ComposerBar(
                                    replyingTo = item.record,
                                    replyingToDisplay =
                                        TimelineProjector.replyTargetPreview(checkNotNull(item.projected)),
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                )
                            }
                        }
                    }
                }
            }
        }
        assertPollLayout(configuration)
        composeRule
            .onNodeWithTag(if (configuration.menu) MESSAGE_ACTION_MENU_TEST_TAG else "poll-discussion")
            .captureRoboImage("src/test/snapshots/poll_message_$name.png")
    }

    private fun assertPollLayout(configuration: PollScreenshotConfiguration) {
        if (configuration.menu) {
            val card =
                composeRule
                    .onNodeWithTag("poll-preview-card", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
            val footer =
                composeRule
                    .onNodeWithTag("poll-preview-footer", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
            assertTrue("Preview footer must remain inside the card", footer.bottom <= card.bottom - 8f)
            assertEquals("Preview footer must follow trailing alignment", 12f, card.right - footer.right, 1f)
        } else {
            val card = composeRule.onNodeWithTag("poll-message-card").fetchSemanticsNode().boundsInRoot
            val footer =
                composeRule
                    .onNodeWithTag("poll-message-footer", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
            assertTrue("Footer must remain inside the rounded card", footer.bottom <= card.bottom - 8f)
            val trailingInset = if (configuration.rtl) footer.left - card.left else card.right - footer.right
            assertEquals("Footer must follow the ordinary trailing alignment", 12f, trailingInset, 1f)
            val reaction = composeRule.onNodeWithTag("$REACTION_PILL_TEST_TAG:0").fetchSemanticsNode().boundsInRoot
            assertEquals("Reactions must overlap the card's bottom edge", card.bottom - 21f, reaction.top, 1f)
        }
    }
}

private data class PollScreenshotConfiguration(
    val dark: Boolean = false,
    val amoled: Boolean = false,
    val rtl: Boolean = false,
    val fontScale: Float = 1f,
    val menu: Boolean = false,
    val reply: Boolean = false,
    val closed: Boolean = false,
    val pending: Boolean = false,
    val sent: Boolean = false,
)
