package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.os.Looper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Mounts each production tile with only its off-main local-file probe held at the real disk boundary. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
class VideoTileCacheProbeReadinessTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun gridRetainedFileNeverOffersDownloadWhileItsProbeIsHeld() = exercise(bubble = false, retained = true)

    @Test
    fun bubbleRetainedFileNeverOffersDownloadWhileItsProbeIsHeld() = exercise(bubble = true, retained = true)

    @Test
    fun gridKnownMissOffersDownloadOnlyAfterItsProbeCompletes() = exercise(bubble = false, retained = false)

    @Test
    fun bubbleKnownMissOffersDownloadOnlyAfterItsProbeCompletes() = exercise(bubble = true, retained = false)

    private fun exercise(
        bubble: Boolean,
        retained: Boolean,
    ) {
        val fixture = ProbeFixture(bubble, retained)
        var mounted by mutableStateOf(true)
        try {
            composeRule.setContent { if (mounted) WhiteNoiseTheme { MountedTile(fixture) } }
            composeRule.waitUntil(10_000) { fixture.entered.count == 0L }
            nodes(R.string.media_tap_to_download).assertCountEquals(0)
            nodes(R.string.media_opening).assertCountEquals(1)
            nodes(R.string.media_downloading).assertCountEquals(0)
            composeRule.onNodeWithTag(videoAttachmentOpenTestTag(fixture.messageId, 0)).performClick()
            composeRule.waitForIdle()
            fixture.assertNoTransfer()
            fixture.release.countDown()
            val expected = if (retained) R.string.reply_media_video else R.string.media_tap_to_download
            composeRule.waitUntil(10_000) { nodes(expected).fetchSemanticsNodes().isNotEmpty() }
            if (retained) nodes(R.string.media_tap_to_download).assertCountEquals(0)
            fixture.assertNoTransfer()
        } finally {
            fixture.release.countDown()
            composeRule.runOnIdle { mounted = false }
            composeRule.waitForIdle()
            fixture.close()
        }
    }

    private fun nodes(resource: Int) =
        composeRule.onAllNodesWithContentDescription(
            ApplicationProvider.getApplicationContext<Context>().getString(resource),
        )

    @Composable
    private fun MountedTile(fixture: ProbeFixture) {
        if (fixture.bubble) {
            MediaVideoBubble(
                item = localTimelineMessage(timelineAppMessage(fixture.messageId)),
                attachmentIndex = 0,
                reference = fixture.reference,
                controller = fixture.controller,
                appState = fixture.host.state,
                onOpenConversationMedia = {},
                mine = false,
                cacheFileProbe = fixture.probe,
            )
        } else {
            MediaVideoGridTile(
                messageIdHex = fixture.messageId,
                attachmentIndex = 0,
                reference = fixture.reference,
                controller = fixture.controller,
                appState = fixture.host.state,
                mine = false,
                onTap = {},
                overflowCount = 0,
                modifier = Modifier.size(160.dp),
                cacheFileProbe = fixture.probe,
            )
        }
    }
}

/** Owns synthetic cache bytes and a suspended disk boundary, never a network or another test's cache entry. */
private class ProbeFixture(
    val bubble: Boolean,
    retained: Boolean,
) : AutoCloseable {
    val host = MediaDownloadIntegrationFixture()
    val controller =
        ConversationController(
            appState = host.state,
            initialGroup = conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
        )
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val index = if (bubble) 22 else 23
    val messageId = request(index).messageIdHex
    val reference = reference(index).copy(fileName = "video.mp4", mediaType = "video/mp4")
    private val cacheFile =
        File(File(context.cacheDir, MediaCacheDirs.VIDEO), "$messageId-0-${reference.sourceEpoch}.mp4")
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val probe: VideoAttachmentCacheProbe = { probeContext, id, attachment, descriptor ->
        assertNotEquals("Disk probing must stay off Main", Looper.getMainLooper(), Looper.myLooper())
        entered.countDown()
        check(release.await(20, TimeUnit.SECONDS)) { "Synthetic video cache probe was not released" }
        cachedVideoAttachmentFile(probeContext, id, attachment, descriptor)
    }

    init {
        cacheFile.parentFile!!.mkdirs()
        cacheFile.delete()
        if (retained) cacheFile.writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    fun assertNoTransfer() {
        assertTrue("A cache probe alone must not request remote bytes", host.calls.isEmpty())
        assertEquals(0, host.explicitDemands.get())
        assertEquals(0, host.automaticDemands.get())
    }

    override fun close() {
        release.countDown()
        host.close()
        cacheFile.delete()
    }
}
