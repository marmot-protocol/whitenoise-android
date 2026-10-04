package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ATTACHMENT_PRESENTATION_MAX_BYTES
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
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
 * A verified image one byte above the presentation budget is never copied onto the heap for a preview. The image
 * bubble, the album tile and the viewer page read it as too large to preview, offer no Retry that could never
 * succeed, and the tiles open the viewer instead. The composables are the production ones over a real controller
 * whose only replaced boundary is the native transfer, so any new request would be counted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaImageTooLargeToPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private var opens = 0

    /** Uses the real controller with only its native download boundary replaced, and no automatic downloads. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        opens = 0
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** The bubble shows Too large to preview with no spinner and no Retry, and its control opens the viewer. */
    @Test
    fun anOversizedImageBubbleOpensTheViewerInsteadOfRetrying() {
        enableAutomaticImages()
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(oversizedImage()) }

        awaitTooLarge()
        composeRule.onNodeWithContentDescription(text(R.string.media_too_large_to_preview)).performClick()

        composeRule.runOnIdle { assertEquals("the control must open the viewer", 1, opens) }
        assertEquals("a tap on the oversized image started a transfer", 1, fixture.calls.size)
    }

    /** An album tile shows the same control, and a tap anywhere on the tile opens the viewer rather than retrying. */
    @Test
    fun anOversizedAlbumTileOpensOnTapInsteadOfRetrying() {
        enableAutomaticImages()
        composeRule.setContent { WhiteNoiseTheme { GridTile() } }
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(oversizedImage()) }

        awaitTooLarge()
        composeRule.onNodeWithTag(TILE_TAG).performClick()

        composeRule.runOnIdle { assertEquals("a tap on the tile must open the viewer", 1, opens) }
        assertEquals("a tap on the oversized tile started a transfer", 1, fixture.calls.size)
    }

    /**
     * With automatic downloads off, an image MDK already retains is read cache-only through the retained-asset
     * path, still bounded, and starts nothing. The retention is seeded by the production download first, then the
     * reader leaves the chat, turns automatic downloads off and returns.
     */
    @Test
    fun anOversizedRetainedImageReadsAsTooLargeWithoutATransfer() {
        enableAutomaticImages()
        var shown by mutableStateOf(true)
        composeRule.setContent { WhiteNoiseTheme { if (shown) Bubble() } }
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(oversizedImage()) }
        awaitTooLarge()

        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { disableAutomaticImages() }
        composeRule.runOnIdle { shown = true }

        awaitTooLarge()
        assertEquals("the cache-only render started a transfer", 1, fixture.calls.size)
    }

    /** The viewer page reads as too large to preview with the Save or share hint and without a Retry action. */
    @Test
    fun anOversizedViewerPageOffersNoRetry() {
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.fillMaxSize()) {
                    ViewerPage(
                        controller = controller,
                        messageIdHex = request(0).messageIdHex,
                        attachmentIndex = 0,
                        reference = reference(0),
                        scale = 1f,
                        offset = Offset.Zero,
                        onScaleChange = {},
                        onOffsetChange = {},
                        mine = false,
                        isCurrent = true,
                        onChromeToggle = {},
                    )
                }
            }
        }
        awaitSingleTransfer()
        composeRule.runOnIdle { fixture.calls.single().succeed(oversizedImage()) }

        val tooLarge = text(R.string.media_too_large_to_preview)
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            composeRule.onAllNodesWithText(tooLarge).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(text(R.string.media_too_large_to_preview_hint)).assertIsDisplayed()
        composeRule.onAllNodesWithText(text(R.string.media_tap_to_retry)).assertCountEquals(0)
        assertNoProgressIndicator()
        assertEquals(1, fixture.calls.size)
    }

    /** Waits until the production tile has asked the native boundary once. */
    private fun awaitSingleTransfer() {
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            fixture.calls.size == 1
        }
    }

    /** Waits for the too-large control and then checks neither Retry nor a spinner is offered beside it. */
    private fun awaitTooLarge() {
        val description = text(R.string.media_too_large_to_preview)
        composeRule.waitUntil(WAIT_MILLIS) {
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(text(R.string.media_tap_to_retry)).assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(text(R.string.media_tap_to_download)).assertCountEquals(0)
        assertNoProgressIndicator()
    }

    /** No node may still describe itself as progress once the image is known to be too large. */
    private fun assertNoProgressIndicator() {
        composeRule
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertCountEquals(0)
    }

    /** The production single-image bubble for the fixture's first attachment. */
    @Composable
    private fun Bubble() {
        MediaImageBubble(
            item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
            reference = reference(0),
            attachmentIndex = 0,
            controller = controller,
            appState = fixture.state,
            onOpenConversationMedia = { opens++ },
            mine = false,
        )
    }

    /** The production album tile for the fixture's first attachment. */
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
            modifier = Modifier.width(TILE_WIDTH).testTag(TILE_TAG),
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

    /** Turns image automatic downloads off on the active network, so a tile may only read retained bytes. */
    private fun disableAutomaticImages() {
        fixture.state.setMediaAutoDownload(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi, false)
        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
    }

    /** Localized text for a string resource. */
    private fun text(id: Int): String {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return context.getString(id)
    }

    private companion object {
        const val TILE_TAG = "oversized-tile"
        const val WAIT_MILLIS = 20_000L
        val TILE_WIDTH = 240.dp

        /** One byte past the presentation budget, shared across tests so the heap holds it once. */
        private val oversized: ByteArray by lazy { ByteArray(ATTACHMENT_PRESENTATION_MAX_BYTES.toInt() + 1) }

        /** The oversized payload, no valid image is needed because the size check runs before any decode. */
        fun oversizedImage(): ByteArray = oversized
    }
}
