package dev.ipf.whitenoise.android.ui.conversation.messages

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The kept-messages card floats inside the transcript, which its scaffold pads for a side cutout or a side
 * navigation bar and which then consumes those insets (#3312). The card's own safe-drawing padding therefore adds
 * nothing there, so in landscape the card keeps only its side margin from the transcript's edge and stays clear
 * of the navigation bar, instead of being pushed inward by the inset a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w780dp-h360dp-mdpi")
class KeptMessagesOverlayInsetTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var view: View

    /** The card rests its side margin against the transcript's padded end edge, clear of the navigation bar. */
    @Test
    fun theCardAlignsWithTheTranscriptEdgeUnderSideInsets() {
        render()
        dispatchSideInsets()

        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val transcript = composeRule.onNodeWithTag(TRANSCRIPT_TAG).getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag(KEPT_MESSAGE_CARD_TAG).assertIsDisplayed().getUnclippedBoundsInRoot()
        assertEquals((root.right - NAVIGATION_PX.dp).value, transcript.right.value, TOLERANCE)
        assertEquals(
            "the card keeps only its own margin from the transcript edge",
            (transcript.right - KeptMessageMetrics.SideMargin).value,
            card.right.value,
            TOLERANCE,
        )
        assertTrue("the card stays clear of the navigation bar", card.right <= root.right - NAVIGATION_PX.dp)
    }

    /** Applies a left display cutout and a right navigation bar, the landscape side insets. */
    private fun dispatchSideInsets() {
        composeRule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(CUTOUT_PX, 0, 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, NAVIGATION_PX, 0))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), true)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(view.rootView, insets)
        }
        composeRule.waitForIdle()
    }

    /** One kept message inside a transcript stand-in padded for the side insets and consuming them, as the conversation does. */
    private fun render() {
        val controller = KeptMessagesController().apply { keep(KeptMessageKey(ACCOUNT, GROUP, MESSAGE_ID)) }
        composeRule.setContent {
            view = LocalView.current
            WhiteNoiseTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                        .testTag(TRANSCRIPT_TAG),
                ) {
                    val entries = rememberKeptMessageEntries(controller, ACCOUNT) { key -> timelineMessage(key.messageIdHex) }
                    KeptMessagesOverlay(
                        state = KeptMessagesOverlayState(entries, controller, ACCOUNT),
                        composerHeight = 96.dp,
                        presentation = { entry ->
                            KeptMessagePresentation(
                                authorName = "Alice",
                                body = "kept body ${entry.key.messageIdHex}",
                                chatTitle = "Design",
                                timeLabel = "12:34",
                            )
                        },
                        onOpenMessage = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** A received message record with the given id, enough for the card to present. */
    private fun timelineMessage(id: String): TimelineMessage =
        TimelineMessage(
            id = "msg:$id",
            record =
                AppMessageRecordFfi(
                    messageIdHex = id,
                    direction = "received",
                    groupIdHex = GROUP,
                    sender = "alice",
                    plaintext = "kept body $id",
                    contentTokens =
                        MarkdownDocumentFfi(
                            truncated = false,
                            blocks = emptyList(),
                            blankLinesBefore = ByteArray(0),
                        ),
                    kind = 9uL,
                    tags = emptyList(),
                    sourceEpoch = null,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                    recordedAt = 1_700_000_000uL,
                    receivedAt = 1_700_000_000uL,
                ),
            status = MessageStatus.Received,
        )

    private companion object {
        const val TRANSCRIPT_TAG = "kept-inset-transcript"
        const val ACCOUNT = "account-a"
        const val GROUP = "group-a"
        const val MESSAGE_ID = "aa"
        const val CUTOUT_PX = 40
        const val NAVIGATION_PX = 48
        const val TOLERANCE = 1f
    }
}
