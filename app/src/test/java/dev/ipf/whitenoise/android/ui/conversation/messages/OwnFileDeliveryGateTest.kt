package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The file card's delivery gate as a conversation wires it. An own row whose send is committed but not yet delivered
 * starts no fetch, while delivery, seen on the row or only in the controller's record map, releases exactly one. The
 * bubble and controller are the production ones, only the native transfer is replaced, and it holds each fetch open.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class OwnFileDeliveryGateTest : MessageBubbleFileAttachmentFixtures() {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val composerTextState = ComposerTextState(TextFieldValue(""))
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController

    /** Uses the real controller with only its native download boundary replaced. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** An undelivered own file row shows Preparing and starts no fetch, so its card stays downloadable underneath. */
    @Test
    fun anUndeliveredOwnFileRowStartsNoFetch() {
        val row = undelivered(ownFileRow())
        val host = controller.attachmentTransferState(row.record.messageIdHex, 0, initiallyAvailable = false)
        composeRule.setContent { WhiteNoiseTheme { FileMessage(row) } }

        composeRule.waitUntil(WAIT_MILLIS) { host.value != AttachmentTransferState.Resolving }
        composeRule.waitForIdle()
        assertTrue("an undelivered own file must not fetch", fixture.calls.isEmpty())
        assertEquals(AttachmentTransferState.Remote, host.value)
        composeRule.onNodeWithText(context.getString(R.string.media_preparing_attachment)).assertExists()
    }

    /** Delivery of a held own row, as MDK's upsert with the published source event, releases one fetch. */
    @Test
    fun deliveryReleasesTheHeldOwnRow() {
        val delivered = ownFileRow()
        var row by mutableStateOf(undelivered(delivered))
        val host = controller.attachmentTransferState(delivered.record.messageIdHex, 0, initiallyAvailable = false)
        composeRule.setContent { WhiteNoiseTheme { FileMessage(row) } }
        composeRule.waitUntil(WAIT_MILLIS) { host.value != AttachmentTransferState.Resolving }
        composeRule.waitForIdle()
        assertTrue(fixture.calls.isEmpty())

        composeRule.runOnIdle { row = delivered }

        awaitOneFetch()
    }

    /** A row whose delivery only the controller's record map has seen is delivered, not held. */
    @Test
    fun deliveryKnownOnlyToTheRecordMapIsNotHeld() {
        val delivered = ownFileRow()
        controller.timelineRecords[delivered.record.messageIdHex] = requireNotNull(delivered.projected)
        composeRule.setContent { WhiteNoiseTheme { FileMessage(undelivered(delivered)) } }

        awaitOneFetch()
    }

    /** Waits until the card has asked the native boundary for its body, then checks that it asked only once. */
    private fun awaitOneFetch() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.calls.isNotEmpty()
        }
        composeRule.waitForIdle()
        assertEquals("delivery must release exactly one fetch", 1, fixture.calls.size)
    }

    /** A delivered own PDF row keyed to the fixture's first native attachment. */
    private fun ownFileRow(): TimelineMessage {
        val id = request(0).messageIdHex
        val group = MediaDownloadIntegrationFixture.GROUP
        val row = fileTimelineMessage(index = 1, fileName = FILE, mine = true, status = MessageStatus.Sent)
        return row.copy(
            id = "msg:$id",
            record = row.record.copy(messageIdHex = id, groupIdHex = group),
            projected = row.projected?.copy(messageIdHex = id, sourceMessageIdHex = id, groupIdHex = group),
        )
    }

    /** [row] as MDK projects an own send that is committed but not yet published: no source event, still pending. */
    private fun undelivered(row: TimelineMessage): TimelineMessage {
        val projected = row.projected?.copy(sourceMessageIdHex = null)
        return row.copy(status = MessageStatus.Pending, projected = projected)
    }

    /** The production message bubble for [item] in a plain conversation. */
    @Composable
    private fun FileMessage(item: TimelineMessage) {
        MessageBubble(
            item = item,
            controller = controller,
            appState = fixture.state,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            batchSelectable = true,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = emptyList(),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = false,
            onActionMenuOpenChange = {},
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = ComposerGate.COMPOSER,
            inviteMutationInFlight = false,
            onJoinInvite = {},
            onDeclineInvite = {},
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
            showSenderAvatar = false,
            parseMarkdown = ::markdown,
        )
    }

    private companion object {
        const val FILE = "large-archive.pdf"
        const val WAIT_MILLIS = 10_000L
    }
}
