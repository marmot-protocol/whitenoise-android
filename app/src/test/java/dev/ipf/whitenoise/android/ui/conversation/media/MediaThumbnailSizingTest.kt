package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** Observes actual production bubble/tile requests at the codec boundary, using retained synthetic image bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaThumbnailSizingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private val transport = RecordingThumbnailTransport()

    /** Keeps the real presentation and encrypted-cache path; substitutes only native state and codec IPC. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        fixture.disk.put(request(0).cacheKey(), syntheticPng())
        fixture.reopenDisk()
        IsolatedMediaDecoder.installTestTransport(transport)
    }

    /** Restores the shared JVM codec fixture and closes only this test's synthetic resources. */
    @After
    fun tearDown() {
        IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        fixture.close()
    }

    /** A real single-image bubble requests only its measured physical bound, including a density change. */
    @Test
    fun bubbleUsesPhysicalPixelsAndUpgradesAfterDensityChanges() {
        var density by mutableStateOf(1f)
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalDensity provides Density(density)) {
                    Box(Modifier.size(96.dp)) {
                        MediaImageBubble(
                            item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
                            reference = reference(0),
                            attachmentIndex = 0,
                            controller = controller,
                            appState = fixture.state,
                            onOpenConversationMedia = {},
                            mine = false,
                        )
                    }
                }
            }
        }
        awaitCached(128)
        composeRule.runOnIdle { density = 3f }
        awaitCached(384)
        assertEquals(listOf(128, 384), transport.edges.toList())
        assertEquals(0, fixture.calls.size)
    }

    /** Enlarging a mounted album tile replaces its small cache entry; shrink/reentry never creates more variants. */
    @Test
    fun albumResizeCannotReuseUndersizedPixelsOrThrashOnShrinkAndReentry() {
        var edge by mutableIntStateOf(96)
        var shown by mutableStateOf(true)
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    if (shown) {
                        MediaImageGridTile(
                            messageIdHex = request(0).messageIdHex,
                            attachmentIndex = 0,
                            reference = reference(0),
                            controller = controller,
                            appState = fixture.state,
                            mine = false,
                            onTap = {},
                            overflowCount = 0,
                            modifier = Modifier.size(edge.dp),
                        )
                    }
                }
            }
        }
        awaitCached(128)
        composeRule.runOnIdle { edge = 400 }
        awaitCached(512)
        repeat(3) {
            composeRule.runOnIdle { edge = 96 }
            composeRule.runOnIdle { edge = 400 }
        }
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle {
            edge = 96
            shown = true
        }
        composeRule.waitForIdle()
        assertEquals(listOf(128, 512), transport.edges.toList())
        composeRule.runOnIdle {
            assertEquals(1, fixture.state.mediaMemoryCacheKeysSnapshot().size)
            assertTrue(requireNotNull(controller.thumbnailFor(request(0).messageIdHex, 0)).width >= 400)
        }
        assertEquals(0, fixture.calls.size)
    }

    /** Verified cached geometry must override inaccurate metadata before accepting pixels for a cropped tile. */
    @Test
    fun cachedLandscapeGeometryCannotUnderfillALargerSquareCrop() {
        fixture.disk.put(request(0).cacheKey(), syntheticPng(1600, 400))
        fixture.reopenDisk()
        controller.cacheThumbnail(
            request(0).messageIdHex,
            0,
            Bitmap.createBitmap(512, 128, Bitmap.Config.ARGB_8888),
            512,
        )
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MediaImageGridTile(
                        messageIdHex = request(0).messageIdHex,
                        attachmentIndex = 0,
                        reference = reference(0),
                        controller = controller,
                        appState = fixture.state,
                        mine = false,
                        onTap = {},
                        overflowCount = 0,
                        modifier = Modifier.size(256.dp),
                    )
                }
            }
        }
        awaitCached(1024)
        assertEquals(listOf(1024), transport.edges.toList())
        assertEquals(0, fixture.calls.size)
    }

    /** Waits for the real guarded cache publication, not merely a launched coroutine or a loading placeholder. */
    private fun awaitCached(edge: Int) {
        composeRule.waitUntil(10_000) {
            composeRule.runOnIdle {
                controller.thumbnailEntryFor(request(0).messageIdHex, 0, edge) != null
            }
        }
    }

    /** Generates controlled source geometry while still exercising a real sampled codec. */
    private fun syntheticPng(
        width: Int = 1200,
        height: Int = 1200,
    ): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return try {
            bitmap.eraseColor(android.graphics.Color.BLUE)
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/** Records requested decode ceilings without replacing the actual JVM image decoder. */
private class RecordingThumbnailTransport : MediaDecodeTransport by TestMediaDecodeTransport {
    val edges = CopyOnWriteArrayList<Int>()

    /** Captures the production request before forwarding the same bytes and options to the test codec. */
    override fun decode(
        bytes: ByteArray,
        edge: Int,
        oriented: Boolean,
        operation: Int,
    ): Bitmap? {
        edges.add(edge)
        return TestMediaDecodeTransport.decode(bytes, edge, oriented, operation)
    }
}
