package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.audio.VoicePlaybackController
import dev.ipf.whitenoise.android.audio.VoicePlaybackSource
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * Background automatic downloads leave video and audio bytes retained by MDK, not in Android's own caches (#3308).
 * Offline, with the automatic network policy off, the production video tile and voice bubble must still treat those
 * bytes as local and materialize them, and must neither materialize nor request a transfer when MDK holds nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w320dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeRetainedMediaOfflineTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var fixture: MediaDownloadIntegrationFixture
    private lateinit var controller: ConversationController
    private val materializations = AtomicInteger()

    /** Uses the real controller with only its native boundary replaced, and no network at all. */
    @Before
    fun setUp() {
        fixture = MediaDownloadIntegrationFixture()
        controller =
            ConversationController(
                appState = fixture.state,
                initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        WhiteNoiseAppState::class.java
            .getDeclaredField("activeNetworkTypesSnapshot")
            .apply { isAccessible = true }
            .set(fixture.state, emptySet<MediaAutoDownloadNetwork>())
    }

    /** Cancels fixture transfers and removes its private temporary cache. */
    @After
    fun tearDown() {
        fixture.close()
    }

    /** A video MDK retains plays offline: the tile materializes it without any transfer request. */
    @Test
    fun aNativelyRetainedVideoMaterializesOffline() {
        fixture.retainNatively(0, VIDEO_BYTES)
        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Video))

        composeRule.setContent { WhiteNoiseTheme { VideoTile() } }

        composeRule.waitUntil(WAIT_MS) {
            composeRule.waitForIdle()
            materializations.get() >= 1
        }
        assertNoTransfer()
    }

    /** A video MDK does not hold stays a Download offline, with no materialization and no transfer request. */
    @Test
    fun aVideoNotRetainedStaysRemoteOffline() {
        composeRule.setContent { WhiteNoiseTheme { VideoTile() } }

        settle()
        assertEquals(0, materializations.get())
        assertNoTransfer()
    }

    /** A video MDK retains plays offline in the chat: the bubble materializes it without any transfer request. */
    @Test
    fun aNativelyRetainedVideoBubbleMaterializesOffline() {
        fixture.retainNatively(0, VIDEO_BYTES)
        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Video))

        composeRule.setContent { WhiteNoiseTheme { VideoBubble() } }

        composeRule.waitUntil(WAIT_MS) {
            composeRule.waitForIdle()
            materializations.get() >= 1
        }
        assertNoTransfer()
    }

    /** A video bubble MDK does not hold stays a Download offline, with no materialization and no transfer request. */
    @Test
    fun aVideoBubbleNotRetainedStaysRemoteOffline() {
        composeRule.setContent { WhiteNoiseTheme { VideoBubble() } }

        settle()
        assertEquals(0, materializations.get())
        assertNoTransfer()
    }

    /** A voice note or audio file MDK retains plays offline: the bubble materializes it without a transfer. */
    @Test
    fun aNativelyRetainedVoiceClipMaterializesOffline() {
        fixture.retainNatively(0, VIDEO_BYTES)
        assertFalse(fixture.state.shouldAutoDownloadMedia(MediaAutoDownloadType.Audio))

        composeRule.setContent { WhiteNoiseTheme { VoiceBubble() } }

        composeRule.waitUntil(WAIT_MS) {
            composeRule.waitForIdle()
            materializations.get() >= 1
        }
        assertNoTransfer()
    }

    /** An audio clip MDK does not hold stays a Download offline, with no materialization and no transfer request. */
    @Test
    fun aVoiceClipNotRetainedStaysRemoteOffline() {
        composeRule.setContent { WhiteNoiseTheme { VoiceBubble() } }

        settle()
        assertEquals(0, materializations.get())
        assertNoTransfer()
    }

    /** The production video tile, with its file materialization counted instead of decoded. */
    @androidx.compose.runtime.Composable
    private fun VideoTile() {
        MediaVideoGridTile(
            messageIdHex = request(0).messageIdHex,
            attachmentIndex = 0,
            reference = reference(0).copy(fileName = "clip.mp4", mediaType = "video/mp4"),
            controller = controller,
            appState = fixture.state,
            mine = false,
            onTap = {},
            overflowCount = 0,
            modifier = Modifier.size(160.dp),
            videoFileResolver = { _, _, _, _, _, _, _ -> materialized() },
        )
    }

    /** The production in-chat video bubble, with its file materialization counted instead of decoded. */
    @androidx.compose.runtime.Composable
    private fun VideoBubble() {
        MediaVideoBubble(
            item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
            attachmentIndex = 0,
            reference = reference(0).copy(fileName = "clip.mp4", mediaType = "video/mp4"),
            controller = controller,
            appState = fixture.state,
            onOpenConversationMedia = {},
            mine = false,
            modifier = Modifier.size(160.dp),
            videoFileResolver = { _, _, _, _, _, _, _ -> materialized() },
        )
    }

    /** The production voice bubble, with its file materialization counted instead of decoded. */
    @androidx.compose.runtime.Composable
    private fun VoiceBubble() {
        CompositionLocalProvider(LocalVoiceAttachmentPresentationRuntime provides CountingVoiceRuntime()) {
            MediaVoiceBubble(
                messageIdHex = request(0).messageIdHex,
                attachmentIndex = 0,
                reference = reference(0).copy(fileName = "clip.m4a", mediaType = "audio/mp4"),
                controller = controller,
                appState = fixture.state,
                presentationOwner = Any(),
                mine = false,
            )
        }
    }

    /** Counts one materialization and hands back a throwaway local file. */
    private fun materialized(): java.io.File {
        materializations.incrementAndGet()
        return java.io.File
            .createTempFile("native-retained", ".bin")
            .apply { deleteOnExit() }
    }

    /** Lets the off-main availability probe answer, so a late materialization would have started by now. */
    private fun settle() {
        repeat(SETTLE_ROUNDS) {
            composeRule.waitForIdle()
            Thread.sleep(SETTLE_STEP_MS)
        }
        composeRule.waitForIdle()
    }

    /** Neither an automatic nor an explicit native transfer was requested. */
    private fun assertNoTransfer() {
        assertEquals(0, fixture.calls.size)
        assertEquals(0, fixture.automaticDemands.get())
        assertEquals(0, fixture.explicitDemands.get())
    }

    /** Voice presentation whose only active part counts materializations; playback is never started here. */
    private inner class CountingVoiceRuntime : VoiceAttachmentPresentationRuntime {
        override val playbackState = VoicePlaybackController.state
        override val playbackFailures = VoicePlaybackController.failures

        /** Counts the materialization instead of publishing a cache file. */
        override suspend fun materialize(request: VoiceAttachmentMaterializationRequest): java.io.File = materialized()

        /** Reports a fixed duration for the throwaway file. */
        override suspend fun durationMs(file: java.io.File): Int = 1_000

        /** Never reached: these tests do not tap Play. */
        override suspend fun play(
            key: String,
            file: java.io.File,
            ownerKey: String,
            source: VoicePlaybackSource,
        ): VoicePlaybackController.PlaybackStartResult = VoicePlaybackController.PlaybackStartResult.StartFailed

        /** No playback to pause. */
        override fun pause() = Unit

        /** No playback to seek. */
        override fun seekTo(
            key: String,
            positionMs: Int,
        ) = Unit

        /** No speed preference to change. */
        override fun cycleSpeed() = Unit
    }

    private companion object {
        const val WAIT_MS = 10_000L
        const val SETTLE_ROUNDS = 10
        const val SETTLE_STEP_MS = 50L
        val VIDEO_BYTES = ByteArray(64) { it.toByte() }
    }
}
