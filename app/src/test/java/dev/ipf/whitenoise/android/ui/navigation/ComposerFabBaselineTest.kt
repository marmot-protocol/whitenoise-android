package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.chats.ChatsNewMessageFabContent
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerActionRowHeight
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Compare production endpoints under Material placement, not a duplicate baseline formula. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h640dp-mdpi")
class ComposerFabBaselineTest {
    @get:Rule val rule = createComposeRule()

    /** Compares the resting pen and composer painted edges. */
    @Test fun restingEdgesMatch() = assertBaseline(name = "default")

    /** Keeps the pen on the composer action baseline with doubled text scale. */
    @Test fun largeTextKeepsPenOnBottomActionBaseline() = assertBaseline(name = "large", fontScale = 2f)

    /** Preserves that baseline with doubled text scale in RTL. */
    @Test fun largeRtlKeepsPenOnBottomActionBaseline() = assertBaseline(name = "large_rtl", fontScale = 2f, rtl = true)

    /** Compares the pen and composer outline in the dark theme. */
    @Test fun darkOutlineMatchesComposer() = assertBaseline(name = "dark", dark = true)

    /** Compares the pen and composer outline on the AMOLED surface. */
    @Test fun amoledOutlineMatchesComposer() = assertBaseline(name = "amoled", dark = true, amoled = true)

    /** Keeps both resting endpoints above the navigation bar inset. */
    @Test fun navigationBarRemainsClear() = assertBaseline(name = "navigation", navigationBottomPx = 48)

    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h360dp-mdpi")
    fun shortRtlKeepsPenOnBottomActionBaseline() {
        assertBaseline(name = "short_rtl", fontScale = 2f, rtl = true, navigationBottomPx = 24)
    }

    @Test
    fun relayWarningKeepsMaterialGap() {
        assertBaseline(name = "relay_warning", missingRelays = true, navigationBottomPx = 48)
    }

    /** A retained portrait composer cannot move the home pen behind the current landscape header. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w640dp-h360dp-mdpi")
    fun tallRetainedComposerStaysInsideShortViewport() {
        val height = mutableStateOf(1000.dp)
        val contentTop = mutableStateOf<Float?>(null)
        var measuredContentTop: Float? = null
        rule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(LocalComposerFabHeight provides height) {
                    Scaffold(
                        topBar = { Box(Modifier.height(96.dp)) },
                        floatingActionButton = {
                            ChatsNewMessageFabContent(false, minimumTopInWindow = contentTop) {}
                        },
                    ) { padding ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .onPlaced { measuredContentTop = it.positionInWindow().y }
                                .testTag(VIEWPORT),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val body = rule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInWindow
        val initialPen =
            rule
                .onNodeWithContentDescription(context.getString(R.string.new_message))
                .fetchSemanticsNode()
                .boundsInWindow
        assertTrue("Missing current measurement keeps the pen below the header", initialPen.top >= body.top)
        assertTrue("The initial pen stays inside the body", initialPen.bottom <= body.bottom + 1.1f)
        rule.runOnIdle { contentTop.value = requireNotNull(measuredContentTop) }
        rule.waitForIdle()
        val pen =
            rule
                .onNodeWithContentDescription(context.getString(R.string.new_message))
                .fetchSemanticsNode()
                .boundsInWindow
        assertEquals("The tall retained reference clamps to the actual content boundary", body.top, pen.top, 1.1f)
        assertTrue("The complete pen stays inside the current body", pen.bottom <= body.bottom + 1.1f)
        assertEquals("Viewport clamping keeps the pen width", 48f, pen.width, 1.1f)
        assertEquals("Viewport clamping keeps the pen height", 48f, pen.height, 1.1f)
        rule.onRoot().captureRoboImage("src/test/snapshots/composer_fab_retained_tall_short_viewport.png")
    }

    /** Keeps endpoint swaps and measured assertions in one inset-aware fixture with shared state. */
    @Suppress("LongMethod", "LongParameterList")
    private fun assertBaseline(
        name: String,
        fontScale: Float = 1f,
        rtl: Boolean = false,
        navigationBottomPx: Int = 0,
        missingRelays: Boolean = false,
        dark: Boolean = false,
        amoled: Boolean = false,
    ) {
        var composer by mutableStateOf(false)
        lateinit var rootView: android.view.View
        var density = 1f
        var outline = 0
        var fill = 0
        var actualNavigationBottom = 0
        val anchor = FloatingDictationAnchor()
        val composerHeight = mutableStateOf(ComposerActionRowHeight)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val layoutDirection = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
        rule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme(fontScale = fontScale, darkTheme = dark, amoled = amoled) {
                    outline = MaterialTheme.colorScheme.outlineVariant.toArgb()
                    fill = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
                    rootView = LocalView.current.rootView
                    density = LocalDensity.current.density
                    actualNavigationBottom = WindowInsets.navigationBars.getBottom(LocalDensity.current)
                    CompositionLocalProvider(
                        LocalFloatingDictationAnchor provides anchor,
                        LocalComposerFabHeight provides composerHeight,
                    ) {
                        Box(Modifier.fillMaxSize().testTag(VIEWPORT)) {
                            if (composer) {
                                ComposerBar(
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> error("Layout fixture cannot send") },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            } else {
                                Scaffold(
                                    contentWindowInsets = WindowInsets.navigationBars,
                                    floatingActionButton = { ChatsNewMessageFabContent(missingRelays) {} },
                                ) { innerPadding ->
                                    Box(Modifier.fillMaxSize().padding(innerPadding))
                                }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBottomPx))
                    .setVisible(WindowInsetsCompat.Type.navigationBars(), navigationBottomPx > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(rootView, insets)
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/snapshots/composer_fab_baseline_${name}_fab.png")
        assertEquals("The production inset source must receive the fixture", navigationBottomPx, actualNavigationBottom)
        val description = if (missingRelays) R.string.chats_check_relays else R.string.new_message
        val fab = rule.onNodeWithContentDescription(context.getString(description)).fetchSemanticsNode().boundsInWindow
        val viewport = rule.onNodeWithTag(VIEWPORT).fetchSemanticsNode().boundsInWindow
        assertTrue("Touch target must clear navigation", fab.bottom <= viewport.bottom - actualNavigationBottom + 1)
        assertTrue(fab.height >= 48 * density)
        val obstacleTop = rule.runOnIdle { requireNotNull(anchor.topInWindow) }
        assertEquals(
            "Dictation follows the visible pen in the same window coordinates",
            fab.top,
            obstacleTop,
            1.1f,
        )
        if (missingRelays) {
            assertEquals(
                "Relay recovery keeps its Material placement",
                viewport.bottom - actualNavigationBottom - 16 * density - fab.height,
                fab.top,
                1.1f,
            )
            return
        }
        assertEquals(
            "The pen keeps the composer bottom clearance",
            6 * density,
            viewport.bottom - actualNavigationBottom - fab.bottom,
            1.1f,
        )
        assertEquals("The compact visible pen keeps a full action target", 48 * density, fab.height, 1.1f)
        assertEquals("Exact one-pixel sampling requires the mdpi fixture", 1f, density, 0f)
        val penEdge = edgePixels(fab, cornerRadiusPx = 16 * density)
        assertEquals("Pen edge must use the theme outline in every theme", outline, penEdge.first())
        assertEquals("The outline occupies one pixel at mdpi", fill, penEdge.last())
        rule.runOnIdle { composer = true }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/snapshots/composer_fab_baseline_${name}_composer.png")
        val pillNode = rule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
        val pill = pillNode.fetchSemanticsNode().boundsInWindow
        assertEquals("The editor grows above the fixed action baseline", pill.bottom, fab.bottom, 1.1f)
        val composerEdge = edgePixels(pill, cornerRadiusPx = 24 * density, gripHalfWidthPx = 16 * density)
        assertEquals("Composer and pen use the same outline ink", outline, composerEdge.first())
        assertEquals("Composer and pen use the same inset stroke", fill, composerEdge.last())
        if (fontScale == 1f) {
            assertEquals("Normal resting outlines share the exact top edge", pill.top, fab.top, 1.1f)
        }
        rule.runOnIdle { composer = false }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("src/test/snapshots/composer_fab_baseline_${name}_returning_pen.png")
        val returningPen =
            rule.onNodeWithContentDescription(context.getString(description)).fetchSemanticsNode().boundsInWindow
        assertEquals("The pen retains the last real composer upper edge", pill.top, returningPen.top, 1.1f)
        assertEquals("Retaining composer height must not resize the pen", fab.size, returningPen.size)
        assertEquals(
            "Dictation follows the raised visible pen",
            returningPen.top,
            requireNotNull(anchor.topInWindow),
            1.1f,
        )
    }

    /** Samples a straight top border and its adjacent fill, explicitly outside corners and the resize grip. */
    private fun edgePixels(
        bounds: androidx.compose.ui.geometry.Rect,
        cornerRadiusPx: Float,
        gripHalfWidthPx: Float = 0f,
    ): List<Int> {
        val root = rule.onNodeWithTag(VIEWPORT)
        val origin = root.fetchSemanticsNode().boundsInWindow.topLeft
        val pixels = root.captureToImage().toPixelMap()
        val sampleX = if (gripHalfWidthPx > 0f) bounds.left + bounds.width / 4 else bounds.center.x
        val x = (sampleX - origin.x).toInt()
        val y = (bounds.top - origin.y).toInt()
        val pixelCenterX = origin.x + x + 0.5f
        assertTrue(
            "The sample must lie on the horizontal straight segment, outside both corner arcs",
            pixelCenterX > bounds.left + cornerRadiusPx && pixelCenterX < bounds.right - cornerRadiusPx,
        )
        if (gripHalfWidthPx > 0f) {
            assertTrue(
                "The sample must avoid the resize grip",
                kotlin.math.abs(pixelCenterX - bounds.center.x) > gripHalfWidthPx + 1f,
            )
        }
        assertEquals("The sample column must use the integer pixel grid", x.toFloat(), sampleX - origin.x, 0f)
        assertEquals("This mdpi fixture must sample the integer outer border", y.toFloat(), bounds.top - origin.y, 0f)
        assertTrue(
            "Both samples must be inside the captured viewport",
            x in 0 until pixels.width && y >= 0 && y + 1 < pixels.height,
        )
        return listOf(pixels[x, y].toArgb(), pixels[x, y + 1].toArgb())
    }

    private companion object {
        const val VIEWPORT = "composer-baseline-viewport"
    }
}
