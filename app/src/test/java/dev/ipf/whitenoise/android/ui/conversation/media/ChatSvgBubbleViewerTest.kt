package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgRegion
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.reference
import dev.ipf.whitenoise.android.state.MediaDownloadIntegrationFixture.Companion.request
import dev.ipf.whitenoise.android.state.cacheKey
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.localTimelineMessage
import dev.ipf.whitenoise.android.state.timelineAppMessage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real cold encrypted-cache bubble opens its actual fullscreen owner with original SVG bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatSvgBubbleViewerTest {
    @get:Rule val rule = createComposeRule()

    /**
     * Starts with a cold message bubble, opens its actual viewer, and observes a vector decode from the original
     * source.
     */
    @Test
    fun originalSvgColdBubbleTapReachesTheRealFullscreenVectorPage() {
        val fixture = MediaDownloadIntegrationFixture()
        val controller = svgController(fixture)
        val bytes =
            (
                """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><r""" +
                    """ect width="24" height="24" fill="red"/></svg>"""
            ).toByteArray()
        val ref = reference(0).copy(mediaType = "image/svg+xml", fileName = "synthetic-vector.svg", dim = "24x24")
        val vectors =
            java.util.concurrent.atomic
                .AtomicInteger()
        val transport = vectorTransport(vectors)
        IsolatedMediaDecoder.installTestTransport(transport)
        var opened by mutableStateOf<ConversationMediaViewerOpenRequest?>(null)
        try {
            fixture.disk.put(request(0).cacheKey(), bytes)
            fixture.reopenDisk()
            rule.setContent {
                WhiteNoiseTheme {
                    MediaImageBubble(
                        item = localTimelineMessage(timelineAppMessage(request(0).messageIdHex)),
                        reference = ref,
                        attachmentIndex = 0,
                        controller = controller,
                        appState = fixture.state,
                        onOpenConversationMedia = { opened = it },
                        mine = false,
                    )
                    opened?.let { request ->
                        ConversationMediaViewer(
                            controller,
                            fixture.state,
                            emptyList(),
                            request.messageIdHex,
                            request.attachments,
                            request.tappedAttachmentIndex,
                            request.sender,
                            request.recordedAt,
                            request.mine,
                            onDismiss = { opened = null },
                            imageBytes = { bytes },
                        )
                    }
                }
            }
            openBubbleAndAwaitVector(ref.fileName, vectors)
            assertOpenedVector({ opened }, fixture)
            val context = ApplicationProvider.getApplicationContext<Context>()
            rule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
            rule.runOnIdle { assertEquals(null, opened) }
        } finally {
            closeViewerFixture(controller, fixture) { opened = null }
        }
    }

    /** Removes the viewer and joins its source work before releasing the controller and decoder fixtures. */
    private fun closeViewerFixture(
        controller: ConversationController,
        fixture: MediaDownloadIntegrationFixture,
        removeViewer: () -> Unit,
    ) {
        try {
            rule.runOnIdle { removeViewer() }
            awaitViewerImageWorkCompletion()
        } finally {
            controller.onCleared()
            fixture.close()
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        }
    }

    /** Joins the removed viewer's source work before its Compose dispatcher and decoder fixture are retired. */
    private fun awaitViewerImageWorkCompletion() {
        rule.waitForIdle()
        val completed =
            java.util.concurrent.atomic
                .AtomicBoolean()
        val owner = Job()
        val probe =
            CoroutineScope(Dispatchers.Default + owner).launch {
                withViewerImageWork { completed.set(true) }
            }
        try {
            rule.waitUntil(10_000) {
                rule.waitForIdle()
                completed.get() && probe.isCompleted
            }
        } finally {
            owner.cancel()
            rule.waitUntil(10_000) { owner.isCompleted }
        }
    }

    /** Wraps the normal test transport with a counter around successful original-vector region requests. */
    private fun vectorTransport(vectors: java.util.concurrent.atomic.AtomicInteger): MediaDecodeTransport =
        object : MediaDecodeTransport by TestMediaDecodeTransport {
            /** Counts original-vector region requests before delegating to the ordinary JVM decoder fixture. */
            override fun svgRegion(
                bytes: ByteArray,
                region: ViewerSvgRegion,
                edge: Int,
            ): android.graphics.Bitmap? {
                vectors.incrementAndGet()
                return TestMediaDecodeTransport.svgRegion(bytes, region, edge)
            }
        }

    /**
     * Checks the selected attachment MIME and confirms opening used existing bytes rather than starting another
     * download.
     */
    private fun assertOpenedVector(
        currentOpened: () -> ConversationMediaViewerOpenRequest?,
        fixture: MediaDownloadIntegrationFixture,
    ) {
        rule.runOnIdle {
            val opened = currentOpened()
            assertTrue(opened != null)
            assertEquals(
                "image/svg+xml",
                opened
                    ?.attachments
                    ?.single()
                    ?.value
                    ?.mediaType,
            )
            assertEquals(0, fixture.calls.size)
        }
    }

    /** Taps the production message bubble and waits for the selected vector source to reach the fullscreen decoder. */
    private fun openBubbleAndAwaitVector(
        fileName: String,
        vectors: java.util.concurrent.atomic.AtomicInteger,
    ) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithContentDescription(fileName).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription(fileName).assertIsDisplayed().performClick()
        rule.waitUntil(10_000) {
            rule.waitForIdle()
            vectors.get() >= 2
        }
        rule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).assertIsDisplayed()
    }

    /** Binds a production conversation controller to the scripted media fixture group. */
    private fun svgController(fixture: MediaDownloadIntegrationFixture): ConversationController =
        ConversationController(
            fixture.state,
            conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
        )
}
