package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.nativePageBody
import dev.ipf.whitenoise.android.ui.conversation.MeasuredPlaybackDialog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Pixel geometry through production hosts: semantics bounds alone cannot detect a page text transform. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NativePageMotionRenderingTest {
    @get:Rule val rule = createComposeRule()
    private var dialog = false
    private val origin = Rect(210f, 340f, 330f, 392f)

    @Test
    fun measuredConversationKeepsNativeHeaderAndBodyOnOpeningAndBack() {
        val route = mutableStateOf<String?>(null)
        rule.mainClock.autoAdvance = false
        setNativeContent {
            ConversationRouteAnimatedContent(
                rememberConversationRouteTransition(route.value, false),
                routeForwardDirection = 1,
                suppressMotion = false,
                contentKey = { it ?: "list" },
                activatingBounds = { if (it == null) null else origin },
            ) { NativePage(header = if (it == null) "Native list" else "Native header") }
        }
        rule.mainClock.advanceTimeBy(400)
        val reference = pixels()
        rule.runOnIdle {
            route.value = "chat"
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(112)
        val opening = pixels()
        rule.mainClock.advanceTimeBy(400)
        assertNativeInk(pixels(), opening)
        rule.runOnIdle {
            route.value = null
            Snapshot.sendApplyNotifications()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(32)
        assertNativeInk(reference, pixels())
    }

    @Test
    fun replacementPageKeepsNativeHeaderOutsideNarrowLaunchRectangle() {
        rule.mainClock.autoAdvance = false
        setNativeContent { ProfileGroupOpeningPage(origin, "replacement") { NativePage() } }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(112)
        val partial = pixels()
        rule.mainClock.advanceTimeBy(400)
        assertNativeInk(pixels(), partial)
    }

    @Test
    fun measuredDialogKeepsNativeHeaderOutsideNarrowLaunchRectangle() {
        dialog = true
        rule.mainClock.autoAdvance = false
        setNativeContent {
            MeasuredPlaybackDialog(
                origin,
                {},
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
            ) { NativePage() }
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(112)
        val partial = pixels()
        rule.mainClock.advanceTimeBy(400)
        assertNativeInk(pixels(), partial)
    }

    private fun setNativeContent(content: @Composable () -> Unit) {
        rule.setContent { WhiteNoiseTheme(darkTheme = true, amoled = true) { content() } }
    }

    @Composable
    private fun NativePage(header: String = "Native header") {
        WhiteNoiseScaffold(
            modifier = Modifier.fillMaxSize().testTag("native-page"),
            containerColor = Color.Black,
            topNoticeSlot = false,
            topBar = {
                Box(Modifier.height(80.dp)) {
                    Text(
                        header,
                        color = Color.Red,
                        fontSize = 30.sp,
                        modifier = Modifier.padding(start = 18.dp, top = 16.dp),
                    )
                }
            },
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nativePageBody()
                    .conversationRouteBodyMotion(),
            ) {
                Text(
                    "Native body",
                    color = Color.Cyan,
                    fontSize = 28.sp,
                    modifier = Modifier.padding(start = 24.dp, top = 36.dp),
                )
                Box(Modifier.padding(start = 24.dp, top = 100.dp).size(32.dp).background(Color.Green))
            }
        }
    }

    private fun pixels(): PixelMap {
        val node = if (dialog) rule.onNode(isDialog()) else rule.onRoot()
        return node.captureToImage().toPixelMap()
    }

    private fun assertNativeInk(
        reference: PixelMap,
        partial: PixelMap,
    ) {
        val header = ink(reference) { it.red > 0.95f && it.green < 0.05f && it.blue < 0.05f }
        val partialHeader = ink(partial) { it.red > 0.95f && it.green < 0.05f && it.blue < 0.05f }
        assertTrue("Fixture needs actual native header glyphs", header.size > 100)
        assertEquals("Header glyphs stay at native coordinates, dimensions and opacity", header, partialHeader)
        val body = chromaInk(reference) { minOf(it.green, it.blue) - it.red }
        val partialBody = chromaInk(partial) { minOf(it.green, it.blue) - it.red }
        assertTrue(
            "Body ink must be admitted (settled=${body.size}, partial=${partialBody.size})",
            partialBody.size > 100,
        )
        // A faded antialiased glyph edge can cross the normalized coverage threshold by one pixel.
        // Translation/scaling of the narrow launch rectangle changes these bounds by many pixels.
        bounds(body).zip(bounds(partialBody)).forEach { (settled, moving) ->
            assertTrue(
                "Body glyph stays within one raster pixel of native bounds",
                kotlin.math.abs(settled - moving) <= 1,
            )
        }
        val avatar = chromaInk(reference) { it.green - maxOf(it.red, it.blue) }
        val partialAvatar = chromaInk(partial) { it.green - maxOf(it.red, it.blue) }
        assertEquals(
            "Avatar native geometry appears with the body, without an outgoing page occluding it",
            avatar,
            partialAvatar,
        )
    }

    private fun chromaInk(
        pixels: PixelMap,
        contrast: (Color) -> Float,
    ): Set<Pair<Int, Int>> {
        var peak = 0f
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) peak = maxOf(peak, contrast(pixels[x, y]))
        }
        assertTrue("Native body ink must be visible", peak > 0.01f)
        return ink(pixels) { contrast(it) > peak * 0.5f }
    }

    private fun ink(
        pixels: PixelMap,
        matches: (Color) -> Boolean,
    ): Set<Pair<Int, Int>> =
        buildSet {
            for (y in 0 until pixels.height) {
                for (x in 0 until pixels.width) {
                    if (matches(pixels[x, y])) add(x to y)
                }
            }
        }

    private fun bounds(ink: Set<Pair<Int, Int>>): List<Int> =
        listOf(ink.minOf { it.first }, ink.minOf { it.second }, ink.maxOf { it.first }, ink.maxOf { it.second })
}
