package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ATTACHMENT_PRESENTATION_MAX_BYTES
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A file larger than a byte-returning read may hold still lands on the device: its card settles on the file's type
 * rather than Tap to retry, both after a reader's Download and on the sender's own card, which fetches its own body
 * only once MDK has projected its reference. The composable and controller are the production ones, only the native
 * transfer is replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class LargeFileCardStateTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController

    /** Uses the real controller with only its native download boundary replaced, on a visible conversation. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        fixture.state.attachmentOpens.setDestination(
            AttachmentOpenDestination(
                accountRef = MediaDownloadIntegrationFixture.ACCOUNT,
                groupIdHex = MediaDownloadIntegrationFixture.GROUP,
                navigationGeneration = 1L,
            ),
        )
        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Document))
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** A reader's Download of a file over the read budget ends on its type glyph, not Tap to retry. */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aReceivedFileOverTheReadBudgetSettlesDownloaded() {
        composeRule.setContent { WhiteNoiseTheme { LargeFileCard(mine = false) } }
        awaitDescribed(text(R.string.media_tap_to_download))

        composeRule.onNodeWithTag(fileAttachmentCardTestTag(request(0).messageIdHex, 0)).performClick()
        deliverBodyOverTheReadBudget()

        awaitDescribed(text(R.string.attachment_type_pdf_description))
        assertNoRetry()
        composeRule.onRoot().captureRoboImage("src/test/snapshots/large-received-file-downloaded.png")
    }

    /** The sender's own card fetches its own body, and one over the read budget also ends on its type glyph. */
    @Test
    fun anOwnFileOverTheReadBudgetSettlesDownloaded() {
        composeRule.setContent { WhiteNoiseTheme { LargeFileCard(mine = true) } }

        deliverBodyOverTheReadBudget()

        awaitDescribed(text(R.string.attachment_type_pdf_description))
        assertNoRetry()
    }

    /**
     * The sender's own card before MDK projects its reference shows Preparing, starts no fetch that could only fail,
     * and fetches exactly once when the projected reference arrives.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun anOwnFileWaitsForItsProjectionInsteadOfFailing() {
        var reference by mutableStateOf(pdfReference().copy(sourceEpoch = 0uL))
        val host = controller.attachmentTransferState(request(0).messageIdHex, 0, initiallyAvailable = false)
        composeRule.setContent { WhiteNoiseTheme { LargeFileCard(mine = true, reference = reference) } }

        composeRule.waitUntil(WAIT_MILLIS) { host.value != AttachmentTransferState.Resolving }
        composeRule.waitForIdle()
        assertEquals("an unprojected own file must not start a fetch", AttachmentTransferState.Remote, host.value)
        assertTrue(fixture.calls.isEmpty())
        composeRule.onNodeWithText(text(R.string.media_preparing_attachment)).assertExists()
        assertNoRetry()
        composeRule.onAllNodesWithContentDescription(text(R.string.media_tap_to_download)).assertCountEquals(0)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/own-large-file-awaiting-projection.png")

        composeRule.runOnIdle { reference = pdfReference() }
        deliverBodyOverTheReadBudget()

        awaitDescribed(text(R.string.attachment_type_pdf_description))
        assertNoRetry()
        assertEquals("the projected reference is fetched once", 1, fixture.calls.size)
        controller.releaseAttachmentTransferState(request(0).messageIdHex, 0)
    }

    /** Waits for the card's one native transfer, then lets MDK retain a body one byte over the 32 MiB read budget. */
    private fun deliverBodyOverTheReadBudget() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.calls.size == 1
        }
        composeRule.runOnIdle {
            fixture.calls.single().succeed(ByteArray(ATTACHMENT_PRESENTATION_MAX_BYTES.toInt() + 1))
        }
    }

    /** No control offers Retry for a body that is on the device. */
    private fun assertNoRetry() {
        composeRule.onAllNodesWithContentDescription(text(R.string.media_tap_to_retry)).assertCountEquals(0)
    }

    /** Waits until some node is described exactly as [description]. */
    private fun awaitDescribed(description: String) {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The fixture's first attachment as a projected PDF document. */
    private fun pdfReference() = reference(0).copy(mediaType = "application/pdf", fileName = "archive.pdf")

    /** The production file card for the fixture's first attachment, as a document from the reader or the sender. */
    @Composable
    private fun LargeFileCard(
        mine: Boolean,
        reference: MediaAttachmentReferenceFfi = pdfReference(),
    ) {
        MediaFileBubble(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference,
            controller = controller,
            appState = fixture.state,
            senderKey = "sender",
            senderDisplayName = "Sender",
            mine = mine,
        )
    }

    /** Localized text for a string resource. */
    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<Context>().getString(id)

    private companion object {
        const val WAIT_MILLIS = 10_000L
    }
}
