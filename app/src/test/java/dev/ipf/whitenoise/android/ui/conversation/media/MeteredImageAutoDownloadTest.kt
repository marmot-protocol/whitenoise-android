package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaPipeline
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaAutoDownloadNetwork
import dev.ipf.whitenoise.android.state.MediaAutoDownloadType
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/**
 * Counts the network-capable transfers the production image bubble actually starts on a metered
 * connection under the shipped default (#2699).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeteredImageAutoDownloadTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController

    /** Uses the real controller and the shipped matrix, with only the native transfer replaced. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        activeNetworks(setOf(MediaAutoDownloadNetwork.Mobile, MediaAutoDownloadNetwork.Metered))
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** Composition, recomposition and a later metered capability update all transfer nothing. */
    @Test
    fun defaultMeteredPolicyStartsNoTransfer() {
        var revision by mutableIntStateOf(0)
        composeRule.setContent { WhiteNoiseTheme { Bubble(revision) } }

        composeRule.waitForIdle()
        assertEquals(0, fixture.calls.size)
        composeRule.runOnIdle { revision++ }
        composeRule.waitForIdle()
        assertEquals(0, fixture.calls.size)

        // A capability update that adds Wi-Fi while the connection stays metered changes nothing:
        // the most-restrictive rule still denies it.
        composeRule.runOnIdle {
            activeNetworks(setOf(MediaAutoDownloadNetwork.WiFi, MediaAutoDownloadNetwork.Metered))
            revision++
        }
        composeRule.waitForIdle()
        assertEquals(0, fixture.calls.size)
        composeRule.onNodeWithContentDescription(downloadLabel()).assertIsDisplayed()
    }

    /** Cached bytes still render on a metered connection without any remote request. */
    @Test
    fun cachedImageRendersOnMeteredWithoutATransfer() {
        fixture.disk.put(request(0).cacheKey(), imageBytes())
        fixture.reopenDisk()
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }

        composeRule.waitUntil(WAIT_MS) {
            composeRule.waitForIdle()
            composeRule.onAllNodesWithContentDescription(reference(0).fileName).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, fixture.calls.size)
    }

    /** The restrictive metered policy leaves an interactive request free to reach the network. */
    @Test
    fun manualRequestStillReachesTheNetworkOnMetered() {
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }
        composeRule.waitForIdle()
        assertEquals(0, fixture.calls.size)
        composeRule.onNodeWithContentDescription(downloadLabel()).assertIsDisplayed()

        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Image))
        assertTrue(
            attachmentTransferAllowsNetwork(
                intent = AttachmentMaterializationIntent.Interactive,
                automaticNetworkAllowed = false,
            ),
        )
        assertFalse(
            attachmentTransferAllowsNetwork(
                intent = AttachmentMaterializationIntent.Automatic,
                automaticNetworkAllowed = false,
            ),
        )
    }

    /** An explicit metered opt-in restores the automatic transfer for that media type. */
    @Test
    fun explicitMeteredOptInTransfersAutomatically() {
        fixture.state.setMediaAutoDownload(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.Metered, true)
        fixture.state.setMediaAutoDownload(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.Mobile, true)
        composeRule.setContent { WhiteNoiseTheme { Bubble() } }

        composeRule.waitUntil(WAIT_MS) {
            composeRule.waitForIdle()
            fixture.calls.size == 1
        }
        assertEquals(1, fixture.calls.size)
    }

    /** The production bubble for a received image, refreshed on each [revision]. */
    @Composable
    private fun Bubble(revision: Int = 0) {
        MediaImageBubble(
            item =
                localTimelineMessage(
                    timelineAppMessage(request(0).messageIdHex).copy(plaintext = "Synthetic revision $revision"),
                ),
            reference = reference(0).copy(),
            attachmentIndex = 0,
            controller = controller,
            appState = fixture.state,
            onOpenConversationMedia = {},
            mine = false,
        )
    }

    /** Selects the live network classification without broadcasting a real connectivity change. */
    private fun activeNetworks(networks: Set<MediaAutoDownloadNetwork>) {
        WhiteNoiseAppState::class.java
            .getDeclaredField("activeNetworkTypesSnapshot")
            .apply { isAccessible = true }
            .set(fixture.state, networks)
    }

    /** The localized Download affordance shown while a remote image is un-materialized. */
    private fun downloadLabel(): String =
        ApplicationProvider
            .getApplicationContext<Context>()
            .getString(R.string.media_tap_to_download)

    /** Generates valid pixels so the cached case exercises the real off-main decoder. */
    private fun imageBytes(): ByteArray {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return try {
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray().also { bytes ->
                    assertNotNull(
                        "Synthetic PNG must decode before exercising the UI",
                        runBlocking {
                            decodeMessageAttachmentImage(bytes, "image/png", MediaPipeline.THUMBNAIL_MAX_EDGE_PX)
                        },
                    )
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val WAIT_MS = 10_000L
    }
}
