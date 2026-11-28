package dev.ipf.whitenoise.android.ui.medialibrary

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.conversation.media.rememberDocumentSaveFallback
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Files tab owns its shares, so the production file row can leave composition, as it does when scrolled out of
 * the list, without cancelling a share it started, and shows the share as running while it stages.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class LibraryFileShareRowTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private lateinit var shares: LibraryFileShares
    private var rowShown by mutableStateOf(true)
    private val release = CompletableDeferred<Unit>()
    private var started = 0
    private var finished = 0
    private var cancelled = 0
    private val row =
        SharedMediaRow(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0).copy(mediaType = "application/pdf", fileName = "report.pdf"),
            mine = false,
            recordedAt = 1uL,
            sender = "sender",
        )

    /** Mounts the production row over a real controller with a share that waits until the test releases it. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                val scope = rememberCoroutineScope()
                shares = remember { LibraryFileShares(scope) { awaitRelease() } }
                if (rowShown) {
                    FileLibraryRow(
                        row = row,
                        controller = controller,
                        appState = fixture.state,
                        documentSaveFallback =
                            rememberDocumentSaveFallback(fixture.state, MediaDownloadIntegrationFixture.GROUP),
                        shares = shares,
                    )
                }
            }
        }
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** A row that leaves composition mid-share keeps the share running and shows it again when it returns. */
    @Test
    fun aRowLeavingCompositionKeepsItsShareRunning() {
        composeRule.onNodeWithContentDescription(text(R.string.shared_media_file_actions)).performClick()
        composeRule.onNodeWithText(text(R.string.shared_media_share)).performClick()
        composeRule.waitUntil(TIMEOUT_MS) { started == 1 }
        // While it stages, the row shows progress in place of its actions, so a second Share cannot start.
        assertActionsHidden()

        composeRule.runOnIdle { rowShown = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { rowShown = true }
        assertActionsHidden()
        composeRule.runOnIdle { rowShown = false }
        composeRule.waitForIdle()
        release.complete(Unit)

        composeRule.waitUntil(TIMEOUT_MS) { finished == 1 }
        composeRule.runOnIdle {
            assertEquals(0, cancelled)
            assertEquals(1, started)
            assertFalse(shares.isSharing(row))
        }
    }

    /** Stands in for staging: counts the share and waits for the test, recording a cancellation. */
    private suspend fun awaitRelease() {
        started += 1
        try {
            release.await()
            finished += 1
        } catch (cancel: CancellationException) {
            cancelled += 1
            throw cancel
        }
    }

    /** Asserts that the row offers no file actions, as it does while a share of its file is staging. */
    private fun assertActionsHidden() {
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription(text(R.string.shared_media_file_actions)).assertCountEquals(0)
    }

    /** The English text of [id]. */
    private fun text(id: Int): String = context.getString(id)

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
