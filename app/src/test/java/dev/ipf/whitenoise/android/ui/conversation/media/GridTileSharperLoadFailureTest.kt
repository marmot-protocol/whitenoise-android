package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
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
import org.robolectric.annotation.GraphicsMode

/**
 * An album tile that already shows a cached image opens the viewer on tap even when the sharper version it then
 * asked for failed to load, as the single-image bubble does. Only a tile that shows nothing keeps Retry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GridTileSharperLoadFailureTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private var opens = 0

    /** Uses the real controller with only its native download boundary replaced, and automatic images allowed. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        opens = 0
        enableAutomaticImages()
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** A small cached thumbnail stays on screen after the larger read fails, and a tap on it opens the viewer. */
    @Test
    fun aTileShowingACachedThumbnailOpensTheViewerAfterTheSharperLoadFails() {
        controller.cacheThumbnail(
            request(0).messageIdHex,
            0,
            Bitmap.createBitmap(SMALL_EDGE, SMALL_EDGE, Bitmap.Config.ARGB_8888),
            SMALL_EDGE,
        )
        composeRule.setContent { WhiteNoiseTheme { GridTile() } }
        awaitTransfers(1)
        composeRule.runOnIdle { fixture.calls.single().fail(java.io.IOException("download failed")) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithContentDescription(text(R.string.media_tap_to_retry)).assertCountEquals(0)

        composeRule.onNodeWithTag(TILE_TAG).performClick()

        composeRule.runOnIdle { assertEquals("a tap on the visible image must open the viewer", 1, opens) }
        assertEquals("a tap on the visible image retried the transfer", 0, fixture.retryDemands.get())
        assertEquals("a tap on the visible image started a transfer", 1, fixture.calls.size)
    }

    /** A tile that failed with nothing displayed still shows Retry, and a tap retries instead of opening. */
    @Test
    fun aTileShowingNothingStillRetriesAfterAFailedLoad() {
        composeRule.setContent { WhiteNoiseTheme { GridTile() } }
        awaitTransfers(1)
        composeRule.runOnIdle { fixture.calls.single().fail(java.io.IOException("download failed")) }
        val retry = text(R.string.media_tap_to_retry)
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription(retry).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(TILE_TAG).performClick()

        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.retryDemands.get() > 0 || fixture.calls.size > 1
        }
        composeRule.runOnIdle { assertEquals("a tap on Retry opened the viewer", 0, opens) }
    }

    /** Waits until the production tile has asked the native boundary [count] times. */
    private fun awaitTransfers(count: Int) {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.calls.size == count
        }
    }

    /** The production album tile for the fixture's first attachment, larger than the cached thumbnail. */
    @Composable
    private fun GridTile() {
        MediaImageGridTile(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = { opens++ },
            overflowCount = 0,
            modifier = Modifier.size(TILE_SIZE).testTag(TILE_TAG),
        )
    }

    /** Selects the existing Wi-Fi policy without broadcasting a real connectivity change. */
    private fun enableAutomaticImages() {
        WhiteNoiseAppState::class.java
            .getDeclaredField("activeNetworkTypesSnapshot")
            .apply { isAccessible = true }
            .set(fixture.state, setOf(MediaAutoDownloadNetwork.WiFi))
        fixture.state.setMediaAutoDownload(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi, true)
        assertTrue(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    /** Localized text for a string resource. */
    private fun text(id: Int): String = ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)

    private companion object {
        const val TILE_TAG = "sharper-load-tile"
        const val WAIT_MILLIS = 20_000L
        const val SMALL_EDGE = 16
        val TILE_SIZE = 240.dp
    }
}
