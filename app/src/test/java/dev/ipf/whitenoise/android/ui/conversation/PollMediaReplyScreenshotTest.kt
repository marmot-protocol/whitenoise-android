package dev.ipf.whitenoise.android.ui.conversation

import android.os.Looper
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
import dev.ipf.marmotkit.DeletionSourceFfi
import dev.ipf.marmotkit.TimelineReplyPreviewFfi
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.TimeZone

/** Real attachment bubbles keep the safe poll quote in pending and delivered states. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class PollMediaReplyScreenshotTest : PollMessageTestFixtures() {
    @get:Rule val composeRule = createComposeRule(effectContext = UnconfinedTestDispatcher())

    private val originalTimeZone = TimeZone.getDefault()

    /** Pins timestamp rendering and gives the real timeline dispatcher its authoritative membership. */
    @Before
    fun bindRoster() =
        runTest {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            pollController.retryMembers()
        }

    /** No native stream or time-zone override survives a screenshot fixture. */
    @After
    fun clearController() {
        pollController.onCleared()
        TimeZone.setDefault(originalTimeZone)
    }

    /** Default presentation includes both pending and delivered media discussion. */
    @Test fun pollMediaReplyLight() = render(false)

    /** Large RTL text and AMOLED preserve the same poll identity without raw JSON. */
    @Test fun pollMediaReplyAmoledRtlLarge() = render(true)

    /** A poll sent as a reply draws the quoted message above its card. */
    @Test fun pollAsReplyLight() = renderPollReply(false)

    /** Large RTL text and AMOLED keep the quote above the poll card without crowding its options. */
    @Test fun pollAsReplyAmoledRtlLarge() = renderPollReply(true)

    /** Renders the shipping timeline rows with native reply metadata for the original poll. */
    private fun render(largeRtl: Boolean) {
        val poll = pollMessage(closed = true)
        retain(poll)
        val items =
            listOf(MessageStatus.Pending, MessageStatus.Sent).mapIndexed { index, status ->
                val source = fileTimelineMessage(80 + index, "Lunch-menu.pdf", mine = true, status = status)
                source.copy(
                    record =
                        source.record.copy(
                            tags = source.record.tags + MessageProjector.eventTag(poll.record.messageIdHex),
                        ),
                    projected =
                        checkNotNull(source.projected).copy(
                            replyToMessageIdHex = poll.record.messageIdHex,
                            replyPreview = replyPreviewOf(poll),
                        ),
                )
            }
        items.forEach(::retain)
        showRows("poll-media-replies", items, largeRtl)
        awaitFailedTransfers(items.map { it.record.messageIdHex })
        val name = if (largeRtl) "amoled_rtl_large" else "light"
        composeRule
            .onNodeWithTag("poll-media-replies")
            .captureRoboImage("src/test/snapshots/poll_media_replies_$name.png")
    }

    /** Renders a sent poll that replies to a received message, with the reply metadata MDK projects. */
    private fun renderPollReply(largeRtl: Boolean) {
        val parent = fileTimelineMessage(60, "", caption = "Where should we eat?", attachments = emptyList())
        val source = pollMessage(mine = true)
        val poll =
            source.copy(
                record =
                    source.record.copy(
                        tags =
                            source.record.tags +
                                MessageProjector.eventTag(parent.record.messageIdHex) +
                                MessageProjector.quoteTag(parent.record.messageIdHex),
                    ),
                projected =
                    checkNotNull(source.projected).copy(
                        replyToMessageIdHex = parent.record.messageIdHex,
                        replyPreview = replyPreviewOf(parent),
                    ),
            )
        retain(parent)
        retain(poll)
        showRows("poll-as-reply", listOf(poll), largeRtl)
        composeRule.waitForIdle()
        val name = if (largeRtl) "amoled_rtl_large" else "light"
        composeRule.onNodeWithTag("poll-as-reply").captureRoboImage("src/test/snapshots/poll_as_reply_$name.png")
    }

    /** Shows [items] as real timeline rows in a tagged surface, AMOLED and mirrored at 200% text when [largeRtl]. */
    private fun showRows(
        tag: String,
        items: List<TimelineMessage>,
        largeRtl: Boolean,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = largeRtl, amoled = largeRtl) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(1f, if (largeRtl) 2f else 1f),
                ) {
                    Surface(Modifier.width(360.dp).testTag(tag)) {
                        Column(Modifier.padding(vertical = 16.dp)) {
                            items.forEach { RealPollMessage(it, menuOpen = false) {} }
                        }
                    }
                }
            }
        }
    }

    /** The reply preview MDK projects for a reply to [target]. */
    private fun replyPreviewOf(target: TimelineMessage) =
        TimelineReplyPreviewFfi(
            retentionSeconds = null,
            retentionExpiresAt = null,
            messageIdHex = target.record.messageIdHex,
            replyToMessageIdHex = null,
            sender = target.record.sender,
            plaintext = target.record.plaintext,
            contentTokens = target.record.contentTokens,
            kind = target.record.kind,
            sticker = null,
            mediaJson = null,
            media = emptyList(),
            deleted = false,
            deletionSource = DeletionSourceFfi.UNKNOWN,
            invalidationStatus = null,
            thread = null,
        )

    /** Waits for the scripted unavailable files to settle before capturing their reply presentation. */
    private fun awaitFailedTransfers(messageIds: List<String>) {
        val transfers = messageIds.map { pollController.attachmentTransferState(it, 0, false) }
        try {
            composeRule.waitUntil(5_000) {
                shadowOf(Looper.getMainLooper()).idle()
                transfers.all { it.value == AttachmentTransferState.Failed }
            }
            composeRule.waitForIdle()
        } finally {
            messageIds.forEach { pollController.releaseAttachmentTransferState(it, 0) }
        }
    }
}
