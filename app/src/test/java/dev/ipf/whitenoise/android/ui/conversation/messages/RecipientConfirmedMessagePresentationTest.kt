package dev.ipf.whitenoise.android.ui.conversation.messages

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.TimelineProjector
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verifies the actual bubble footers and reader consume MDK's receipt-backed delivery projection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class RecipientConfirmedMessagePresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var surface: SwipeTestSurface? = null

    /** Retires the production controller even when a semantics assertion fails. */
    @After
    fun tearDown() {
        surface?.controller?.onCleared()
    }

    /** A live receipt removes the stale text warning and exposes exactly one accessible sent indicator. */
    @Test
    fun textFooterFollowsNativeConfirmationAndWithdrawal() = assertBubbleTransition(Shape.Text)

    /** File-card metadata has the same delivery truth as a text bubble, even without a relay source event. */
    @Test
    fun fileFooterFollowsNativeConfirmationAndWithdrawal() = assertBubbleTransition(Shape.File)

    /** The visual-media footer must not leave the old warning or a second inaccessible status behind. */
    @Test
    fun imageFooterFollowsNativeConfirmationAndWithdrawal() = assertBubbleTransition(Shape.Image)

    /** Deleting a previously confirmed send removes its delivery glyph and retains the deleted-message body. */
    @Test
    fun deletionStillTakesPrecedenceAfterPeerConfirmation() {
        val fixture = fixture(Shape.Text)
        val deleted = confirmed(requireNotNull(fixture.item.projected)).copy(deleted = true)
        fixture.controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(deleted)))
        composeRule.setContent { SwipeTestBubbleHost(fixture.copy(item = fixture.controller.timeline.single())) }

        composeRule
            .onAllNodesWithText(context.getString(R.string.message_deleted_by_you), useUnmergedTree = true)
            .assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sent", useUnmergedTree = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Delivery not confirmed", useUnmergedTree = true).assertCountEquals(0)
    }

    /** A peer reaction does not make a non-canonical message canonical or hide its convergence warning. */
    @Test
    fun convergenceInvalidationIsNotSuppressedByAReaction() {
        val fixture = fixture(Shape.Text)
        val invalidated =
            confirmed(requireNotNull(fixture.item.projected)).copy(
                recipientConfirmed = false,
                invalidationStatus = "LosingBranch",
            )
        fixture.controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(invalidated)))
        composeRule.setContent { SwipeTestBubbleHost(fixture.copy(item = fixture.controller.timeline.single())) }

        composeRule
            .onAllNodesWithText(
                requireNotNull(TimelineProjector.invalidationWarning(invalidated)),
                useUnmergedTree = true,
            )
            .assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sent", useUnmergedTree = true).assertCountEquals(0)
    }

    /** Receipt-backed status reaches the full-screen reader through the production controller projection. */
    @Test
    fun fullScreenReaderShowsConfirmedDeliveryWithoutASourceEvent() {
        val fixture = fixture(Shape.Text)
        val confirmed = confirmed(requireNotNull(fixture.item.projected))
        fixture.controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(confirmed)))
        composeRule.setContent {
            val item = fixture.controller.timeline.single()
            WhiteNoiseTheme {
                MessageFullScreenView(
                    senderDisplayName = "Sender",
                    senderSeed = "sender",
                    senderAvatarUrl = null,
                    body = item.record.plaintext,
                    bodyMarkdownDocument = null,
                    mentionDisplayName = null,
                    onNostrProfileTap = null,
                    onCopyMarkdownLink = {},
                    timeText = "10:30",
                    showStatus = true,
                    status = item.status,
                    canReply = false,
                    canReact = false,
                    canDelete = false,
                    onReply = {},
                    onReact = {},
                    onCopy = {},
                    onDelete = {},
                    onDismiss = {},
                    bottomBar = {},
                )
            }
        }

        composeRule.onAllNodesWithContentDescription("Sent", useUnmergedTree = true).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Sending", useUnmergedTree = true).assertCountEquals(0)
    }

    /** Real bubble semantics follow live native upserts in both directions without reopening the conversation. */
    private fun assertBubbleTransition(shape: Shape) {
        val fixture = fixture(shape)
        val failed = requireNotNull(fixture.item.projected)
        composeRule.setContent {
            SwipeTestBubbleHost(fixture.copy(item = fixture.controller.timeline.single()))
        }
        assertWarningState(confirmed = false)

        composeRule.runOnIdle {
            fixture.controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(confirmed(failed))))
        }
        assertWarningState(confirmed = true)
        composeRule.runOnIdle { assertEquals(MessageStatus.Sent, fixture.controller.timeline.single().status) }

        composeRule.runOnIdle {
            fixture.controller.testApplyLiveTimelineChangesAndRegisterStreams(listOf(upsert(failed)))
        }
        assertWarningState(confirmed = false)
    }

    /** Checks visible warning copy and accessible status instead of inferring delivery from icon pixels. */
    private fun assertWarningState(confirmed: Boolean) {
        composeRule
            .onAllNodesWithText("Delivery not confirmed", useUnmergedTree = true)
            .assertCountEquals(if (confirmed) 0 else 1)
        composeRule
            .onAllNodesWithContentDescription("Sent", useUnmergedTree = true)
            .assertCountEquals(if (confirmed) 1 else 0)
    }

    /** Uses the existing real-bubble fixture with a historical native failure instead of a live optimistic send. */
    private fun fixture(shape: Shape): SwipeTestSurface {
        val base = swipeTestSurface(context, reacted = false, mine = true, media = shape != Shape.Text)
        surface = base
        val initial = requireNotNull(base.item.projected)
        val media =
            if (shape == Shape.Image) {
                initial.media.map { outcome ->
                    val accepted = outcome as MediaAttachmentOutcomeFfi.Accepted
                    accepted.copy(reference = accepted.reference.copy(fileName = "photo.jpg", mediaType = "image/jpeg"))
                }
            } else {
                initial.media
            }
        val failed =
            initial.copy(
                sourceMessageIdHex = null,
                invalidationStatus = "local_publish_failed",
                media = media,
            )
        runBlocking {
            base.controller.testRefreshCurrentTimeline(SWIPE_TEST_ACCOUNT_REF) {
                TimelinePageFfi(listOf(failed), false, false)
            }
        }
        return base.copy(item = base.controller.timeline.single())
    }

    /** MDK clears the local publication failure when it certifies recipient evidence; source identity stays absent. */
    private fun confirmed(record: TimelineMessageRecordFfi) =
        record.copy(
            invalidationStatus = null,
            recipientConfirmed = true,
            reactions = swipeTestProjectedRecord(reacted = true, mine = true, media = false).reactions,
        )

    /** Reactions and status may arrive together under the delivery-state trigger. */
    private fun upsert(record: TimelineMessageRecordFfi) =
        TimelineMessageChangeFfi.Upsert(TimelineUpdateTriggerFfi.DELIVERY_OR_SEND_STATE_CHANGED, record)

    private enum class Shape { Text, File, Image }
}
