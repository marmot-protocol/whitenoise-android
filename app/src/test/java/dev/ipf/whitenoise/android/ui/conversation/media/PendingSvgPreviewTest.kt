package dev.ipf.whitenoise.android.ui.conversation.media

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
import dev.ipf.whitenoise.android.media.sandbox.MediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.TestMediaDecodeTransport
import dev.ipf.whitenoise.android.media.sandbox.ViewerSvgRegion
import dev.ipf.whitenoise.android.state.PendingAttachment
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PendingSvgPreviewTest {
    @get:Rule val rule = createComposeRule()
    private val source =
        (
            """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24"><r""" +
                """ect width="24" height="24" fill="red"/></svg>"""
        ).toByteArray()

    @Test
    fun optimisticSvgActuallyDrawsItsOriginalVectorAndReleasesOwnedPixels() {
        val decoded = CopyOnWriteArrayList<Bitmap>()
        IsolatedMediaDecoder.installTestTransport(
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun svgRegion(
                    bytes: ByteArray,
                    region: ViewerSvgRegion,
                    edge: Int,
                ): Bitmap? = TestMediaDecodeTransport.svgRegion(bytes, region, edge)?.also(decoded::add)
            },
        )
        var shown by mutableStateOf(true)
        try {
            rule.setContent {
                WhiteNoiseTheme {
                    if (shown) {
                        Box(Modifier.width(192.dp).testTag("pending-svg")) {
                            MediaPendingPlaceholder(
                                listOf(
                                    PendingAttachment(
                                        source,
                                        "image/svg+xml",
                                        "synthetic.svg",
                                        "24x24",
                                        null,
                                    ),
                                ),
                                failed = false,
                            )
                        }
                    }
                }
            }
            rule.waitUntil(10_000) { decoded.isNotEmpty() }
            rule.waitForIdle()
            val pixels = rule.onNodeWithTag("pending-svg").captureToImage().toPixelMap()
            val sample = pixels[pixels.width / 3, pixels.height / 4]
            assertTrue("Original red vector must be drawn, not merely decoded", sample.red > 0.5f && sample.blue < 0.2f)
            rule.runOnIdle { shown = false }
            rule.waitForIdle()
            assertTrue(decoded.all { it.isRecycled })
        } finally {
            rule.runOnIdle { shown = false }
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        }
    }

    @Test
    fun cancellationRecyclesPixelsProducedAfterThePendingOwnerDisappears() {
        val started = CountDownLatch(1)
        val produced = CopyOnWriteArrayList<Bitmap>()
        val release = CountDownLatch(1)
        val transport =
            object : MediaDecodeTransport by TestMediaDecodeTransport {
                override fun svgRegion(
                    bytes: ByteArray,
                    region: ViewerSvgRegion,
                    edge: Int,
                ): Bitmap {
                    started.countDown()
                    try {
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    } catch (
                        _: InterruptedException,
                    ) {
                        // Native work can finish after cancellation.
                    }
                    return Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also(produced::add)
                }
            }
        IsolatedMediaDecoder.installTestTransport(transport)
        var shown by mutableStateOf(true)
        try {
            rule.setContent {
                WhiteNoiseTheme {
                    if (shown) {
                        MediaPendingPlaceholder(
                            listOf(
                                PendingAttachment(
                                    source,
                                    "image/svg+xml",
                                    "cancelled.svg",
                                    "24x24",
                                    null,
                                ),
                            ),
                            failed = false,
                        )
                    }
                }
            }
            rule.waitUntil(10_000) { started.count == 0L }
            rule.runOnIdle { shown = false }
            release.countDown()
            rule.waitUntil(10_000) { produced.isNotEmpty() && produced.all { it.isRecycled } }
        } finally {
            release.countDown()
            rule.runOnIdle { shown = false }
            IsolatedMediaDecoder.installTestTransport(TestMediaDecodeTransport)
        }
    }
}
