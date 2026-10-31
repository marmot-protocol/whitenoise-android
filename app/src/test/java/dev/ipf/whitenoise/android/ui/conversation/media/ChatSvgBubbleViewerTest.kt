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

    @Test
    fun originalSvgColdBubbleTapReachesTheRealFullscreenVectorPage() {
        val fixture = MediaDownloadIntegrationFixture()
        val controller =
            ConversationController(
                fixture.state,
                conversationTimelineTestGroup().copy(groupIdHex = MediaDownloadIntegrationFixture.GROUP),
            )
        val bytes = """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><rect width="24" height="24" fill="red"/></svg>""".toByteArray()
        val ref = reference(0).copy(mediaType = "image/svg+xml", fileName = "synthetic-vector.svg", dim = "24x24")
        val vectors =
            java.util.concurrent.atomic
                .AtomicInteger()
        val transport =
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun svgRegion(
                    bytes: ByteArray,
                    region: ViewerSvgRegion,
                    edge: Int,
                ): android.graphics.Bitmap? {
                    vectors.incrementAndGet()
                    return TestMediaDecodeTransport.svgRegion(bytes, region, edge)
                }
            }
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
            rule.waitUntil(10_000) {
                rule.onAllNodesWithContentDescription(ref.fileName).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithContentDescription(ref.fileName).assertIsDisplayed().performClick()
            rule.waitUntil(10_000) {
                rule.waitForIdle()
                vectors.get() >= 2
            }
            rule.onNodeWithTag(MEDIA_VIEWER_PAGE_GESTURE_TAG).assertIsDisplayed()
            rule.runOnIdle {
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
            val context = ApplicationProvider.getApplicationContext<Context>()
            rule.onNodeWithContentDescription(context.getString(R.string.close)).performClick()
            rule.runOnIdle { assertEquals(null, opened) }
        } finally {
            rule.runOnIdle { opened = null }
            controller.onCleared()
            fixture.close()
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        }
    }
}
