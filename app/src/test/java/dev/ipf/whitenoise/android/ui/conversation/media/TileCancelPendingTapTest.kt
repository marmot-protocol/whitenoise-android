package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.AttachmentCancellationState
import dev.ipf.whitenoise.android.state.AttachmentOpenDestination
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.attachmentTransferKey
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A reader's Cancel stays pending until the engine acknowledges it, and a tap during that time must not reach the
 * tile's own open handler, because opening promotes the very transfer that is being stopped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TileCancelPendingTapTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private var tileTaps = 0

    /** Uses the real controller with only its native download boundary replaced. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        // A tap persists an open intent only for the conversation that is on screen.
        fixture.state.attachmentOpens.setDestination(
            AttachmentOpenDestination(
                accountRef = MediaDownloadIntegrationFixture.ACCOUNT,
                groupIdHex = MediaDownloadIntegrationFixture.GROUP,
                navigationGeneration = 1L,
            ),
        )
        tileTaps = 0
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** The control claims a tap on its slot while Cancel awaits acknowledgement, so a parent never sees it. */
    @Test
    fun theControlSlotConsumesATapInsideAClickableParentWhileCancelIsPending() {
        var parentTaps = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.size(PARENT_SIZE).testTag(TILE_TAG).clickable { parentTaps++ }) {
                    TileTransferControl(
                        transfer = transfer(AttachmentCancellationState.Pending),
                        onRetry = {},
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription(cancellingText()).performTouchInput { click() }
        composeRule.waitForIdle()
        assertEquals("a tap on the pending control reached its parent", 0, parentTaps)

        composeRule.onNodeWithTag(TILE_TAG).performTouchInput { click(Offset(CORNER, CORNER)) }
        composeRule.waitForIdle()
        assertEquals("the parent must still receive taps outside the control", 1, parentTaps)
    }

    /** An image album tile persists no open intent from a tap while Cancel is pending, then offers Download again. */
    @Test
    fun anImageAlbumTileIgnoresEveryTapWhileCancelIsPending() {
        composeRule.setContent { WhiteNoiseTheme { ImageTile() } }
        val acknowledge = cancelWhileDownloading()

        tapRingAndCorner()

        assertFalse("a tap during a pending cancel left an open intent", hasOpenIntent())
        assertEquals("a tap during a pending cancel started a download", 0, fixture.calls.size)

        composeRule.runOnIdle { acknowledge(true) }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription(cancelledText()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(cancelledText()).assertHasClickAction()
    }

    /** A video album tile does not open the viewer from any tap while its Cancel is pending. */
    @Test
    fun aVideoAlbumTileIgnoresEveryTapWhileCancelIsPending() {
        composeRule.setContent { WhiteNoiseTheme { VideoTile() } }
        cancelWhileDownloading()

        tapRingAndCorner()

        assertEquals("a tap during a pending cancel opened the viewer", 0, tileTaps)
        assertEquals("a tap during a pending cancel started a download", 0, fixture.calls.size)
    }

    /** Puts the first attachment into a Cancel the engine has not acknowledged, and waits for the tile to show it. */
    private fun cancelWhileDownloading(): (Boolean) -> Unit {
        var acknowledge: (Boolean) -> Unit = {}
        composeRule.runOnIdle {
            acknowledge =
                controller.attachmentTransfers.cancel(
                    controller.attachmentTransferKey(request(0).messageIdHex, 0),
                    nativeActive = true,
                )
        }
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            showsCancelling()
        }
        return acknowledge
    }

    /** Taps the centre of the tile, where the ring sits, and then a corner well outside it. */
    private fun tapRingAndCorner() {
        composeRule.onNodeWithContentDescription(cancellingText()).performTouchInput { click() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TILE_TAG).performTouchInput { click(Offset(CORNER, CORNER)) }
        composeRule.waitForIdle()
    }

    /** True once a tap has persisted the reader's intent to open the first attachment. */
    private fun hasOpenIntent(): Boolean = controller.hasAttachmentOpenIntent(request(0).messageIdHex, 0)

    /** Production image tile for the first fixture attachment. */
    @Composable
    private fun ImageTile() {
        MediaImageGridTile(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = { tileTaps++ },
            overflowCount = 0,
            modifier = Modifier.size(PARENT_SIZE).testTag(TILE_TAG),
        )
    }

    /** Production video tile for the first fixture attachment. */
    @Composable
    private fun VideoTile() {
        MediaVideoGridTile(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = { tileTaps++ },
            overflowCount = 0,
            modifier = Modifier.size(PARENT_SIZE).testTag(TILE_TAG),
        )
    }

    /** A transfer in the given cancellation state, as the tile model would derive it. */
    private fun transfer(cancellation: AttachmentCancellationState) =
        TileTransfer(
            state = AttachmentTransferState.Downloading,
            progress = null,
            cancellation = cancellation,
            suppressed = false,
            onCancel = {},
        )

    /** Localized text the control exposes while Cancel awaits acknowledgement. */
    private fun cancellingText(): String = context().getString(R.string.media_cancelling_download)

    /** Localized text the control exposes once the engine acknowledged Cancel. */
    private fun cancelledText(): String = context().getString(R.string.media_download_cancelled)

    /** The application context that owns the string resources. */
    private fun context() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** True while a node describes a Cancel that the engine has not acknowledged. */
    private fun showsCancelling(): Boolean {
        val nodes = composeRule.onAllNodesWithContentDescription(cancellingText()).fetchSemanticsNodes()
        return nodes.isNotEmpty()
    }

    private companion object {
        const val TILE_TAG = "tile"
        const val CORNER = 6f
        const val WAIT_MILLIS = 10_000L
        val PARENT_SIZE = 160.dp
    }
}
