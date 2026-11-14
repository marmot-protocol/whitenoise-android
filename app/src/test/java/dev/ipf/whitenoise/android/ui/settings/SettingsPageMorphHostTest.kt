package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Draw the real morph host before testing its reverse-frame retention and protection. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
// Shared Compose rule and native-frame fixture bind this contour scenario catalog.
@Suppress("LargeClass")
class SettingsPageMorphHostTest {
    @Test fun backDuringExitPreservesNativePixelsAtReplacementClockZero() =
        assertRetargetContinuous(
            0.2f,
            reverse = true,
        )

    @Test fun backDuringEntryPreservesNativePixelsAtReplacementClockZero() =
        assertRetargetContinuous(
            0.65f,
            reverse = true,
        )

    @Test fun forwardDuringExitPreservesNativePixelsAtReplacementClockZero() =
        assertRetargetContinuous(
            0.2f,
            reverse = false,
        )

    @Test fun forwardDuringEntryPreservesNativePixelsAtReplacementClockZero() {
        assertRetargetContinuous(0.65f, reverse = false)
    }

    @Test fun backAtDockPreservesMeasuredCellsAtReplacementZero() = assertRetargetContinuous(0.4f, reverse = true)

    @Test fun forwardAtDockPreservesMeasuredCellsAtReplacementZero() = assertRetargetContinuous(0.4f, reverse = false)

    @Test fun backBeforeDockPreservesMeasuredCellsAtReplacementZero() = assertRetargetContinuous(0.39f, reverse = true)

    @Test fun forwardAfterDockPreservesMeasuredCellsAtReplacementZero() =
        assertRetargetContinuous(
            0.41f,
            reverse = false,
        )

    @Test fun backLatePreservesMeasuredCellsAtReplacementZero() = assertRetargetContinuous(0.9f, reverse = true)

    @Test fun forwardLatePreservesMeasuredCellsAtReplacementZero() = assertRetargetContinuous(0.9f, reverse = false)

    private fun assertRetargetPixelCoverage(
        before: androidx.compose.ui.graphics.PixelMap,
        after: androidx.compose.ui.graphics.PixelMap,
    ) {
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                val old = before[x, y]
                val new = after[x, y]
                assertEquals(
                    "opaque contour coverage at replacement ($x,$y)",
                    minOf(old.red, old.green, old.blue),
                    minOf(new.red, new.green, new.blue),
                    0.01f,
                )
            }
        }
    }

    // The real native draw fixture and retarget action share local measured state; pixel comparison is separate.
    @Suppress("LongMethod")
    private fun assertRetargetContinuous(
        interruptedTime: Float,
        reverse: Boolean,
    ) {
        val page = SettingsPageMorph()
        val time = mutableStateOf(1f)
        val route = mutableStateOf("parent")
        lateinit var host: Rect
        val blocks = listOf(Rect(20f, 100f, 340f, 260f), Rect(20f, 300f, 340f, 500f))
        val groups = listOf(Any(), Any())
        val rows = List(2) { List(2) { Any() } }

        fun bounds(): List<Rect> =
            when (route.value) {
                "parent" -> blocks
                "child" -> listOf(Rect(20f, 140f, 340f, 440f))
                else -> listOf(Rect(20f, 80f, 340f, 200f), Rect(20f, 240f, 340f, 560f))
            }

        fun endpoint(
            index: Int,
            rect: Rect,
        ) = SettingsRouteEndpoint(
            groups[index],
            route.value,
            rect,
            androidx.compose.ui.geometry
                .RoundRect(rect, 20f, 20f),
            Color.Black,
            Color.White,
            true,
            true,
        )

        fun report() {
            groups.forEach(page::removeDestination)
            bounds().forEachIndexed { index, rect ->
                page.reportDestination(groups[index], endpoint(index, rect))
                page.reportRow(
                    groups[index],
                    rows[index][0],
                    endpoint(index, Rect(rect.left, rect.top, rect.right, rect.center.y)).copy(last = false),
                )
                page.reportRow(
                    groups[index],
                    rows[index][1],
                    endpoint(index, Rect(rect.left, rect.center.y, rect.right, rect.bottom)).copy(first = false),
                )
            }
        }
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        host = Rect(0f, 0f, size.width, size.height)
                        report()
                        with(page) { draw(host, time.value, recording = true, backplate = Color.Black) }
                    }.drawWithContent {
                        drawRect(Color.Black)
                        bounds().forEachIndexed { index, rect ->
                            drawPath(
                                dev.ipf.whitenoise.android.ui.theme
                                    .connectedRowBorderPath(rect, endpoint(index, rect).corners, true, true, 1f),
                                Color.White,
                                style =
                                    androidx.compose.ui.graphics.drawscope
                                        .Stroke(1f),
                            )
                            drawLine(
                                Color.White,
                                androidx.compose.ui.geometry
                                    .Offset(rect.left + 0.5f, rect.center.y - 0.5f),
                                androidx.compose.ui.geometry
                                    .Offset(rect.right - 0.5f, rect.center.y - 0.5f),
                                strokeWidth = 1f,
                            )
                            val ink =
                                when (route.value) {
                                    "parent" -> Color.Red
                                    "child" -> Color.Green
                                    else -> Color.Blue
                                }
                            // Distinct opaque marks in both cells expose crop/translation discontinuities.
                            for (y in listOf(rect.top + 28f, rect.center.y + 28f)) {
                                drawRect(
                                    ink,
                                    androidx.compose.ui.geometry
                                        .Offset(70f, y),
                                    androidx.compose.ui.geometry
                                        .Size(37f, 9f),
                                )
                                drawRect(
                                    ink,
                                    androidx.compose.ui.geometry
                                        .Offset(70f, y + 14f),
                                    androidx.compose.ui.geometry
                                        .Size(61f, 7f),
                                )
                            }
                        }
                    },
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            page.begin("parent", true, false, host)
            time.value = 0.99f
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            page.pendingOrigin = blocks.first()
            assertTrue(page.begin("child", true, true, host))
            route.value = "child"
            report()
            assertTrue(page.admitDestination())
            time.value = interruptedTime
        }
        val before = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(
            "fixture must exercise measured connected rows",
            page.renderedContours(interruptedTime).any {
                it.seams.isNotEmpty()
            },
        )
        val beforeGeometry = page.renderedContours(interruptedTime)
        rule.runOnIdle {
            page.pendingOrigin = if (reverse) null else blocks.last()
            val next = if (reverse) "parent" else "other"
            assertTrue(page.begin(next, !reverse, true, host, interruptedTime))
            route.value = next
            report()
            assertTrue(page.admitDestination())
            time.value = 0f
        }
        val after = rule.onRoot().captureToImage().toPixelMap()
        assertEquals("replacement retains the exact drawn geometry", beforeGeometry, page.renderedContours(0f))
        assertRetargetPixelCoverage(before, after)
        rule.runOnIdle { time.value = 1f }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            page.finish()
            assertFalse(page.active)
            assertEquals(0, page.keptPictures)
        }
    }

    @Test fun protectedOutlinesStayOpaqueWithoutTheirPrivatePixelsOrIncomingInk() {
        val page = SettingsPageMorph()
        val time = mutableStateOf(1f)
        lateinit var host: Rect
        val block = Rect(20f, 100f, 340f, 220f)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        host = Rect(0f, 0f, size.width, size.height)
                        with(page) { draw(host, time.value, recording = false, backplate = Color.Black) }
                    }.background(Color.Green),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            page.begin("parent", true, false, host)
            page.reportDestination(
                Any(),
                SettingsRouteEndpoint(
                    "native",
                    "parent",
                    block,
                    androidx.compose.ui.geometry
                        .RoundRect(Rect(0f, 0f, block.width, block.height), 20f, 20f),
                    Color.Black,
                    Color.White,
                    true,
                    true,
                ),
            )
            page.pendingOrigin = block
            assertTrue(page.begin("child", true, true, host))
            time.value = 0.2f
        }
        val frame = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(Color.Green, frame[180, 160])
        assertEquals(Color.White, frame[20, 160])
        assertEquals(Color.Green, frame[25, 160])
        assertEquals(0, page.keptPictures)
        rule.runOnIdle { time.value = 0.55f }
        val entering = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(entering[180, 160].green > 0f)
        assertEquals(0, page.keptPictures)
    }

    @Test fun nestedPageRedrawsReachTheSettledAncestor() {
        val parent = SettingsCardMorph(fullScreen = true)
        val child = SettingsCardMorph(fullScreen = true, parent = parent)
        val color = mutableStateOf(Color.Red)
        val routeFrame = mutableStateOf(0)
        val colors = SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        routeFrame.value
                        drawContent()
                    }.settingsCardMorphHost(parent, colors),
            ) {
                Box(Modifier.fillMaxSize().settingsCardMorphHost(child, colors)) {
                    Box(Modifier.fillMaxSize().graphicsLayer().drawWithContent { drawRect(color.value) })
                }
            }
        }
        val source = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(source[8, 80].red > 0.95f)
        // The ancestor's route does not change when the descendant publishes new content.
        rule.runOnIdle { color.value = Color.Blue }
        val updated = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(updated[8, 80].blue > 0.95f)
        rule.runOnIdle { color.value = Color.Green }
        val prepared = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(prepared[8, 80].green > 0.95f)
        rule.runOnIdle {
            parent.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            assertTrue(parent.beginPage("documents", true, true))
            parent.finish()
            routeFrame.value++
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle { assertTrue(parent.page!!.hasSettledPicture) }
        // A layer-only repaint must refresh the inert outgoing frame as well as live content.
        rule.runOnIdle { color.value = Color.Blue }
        val latest = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(latest[8, 80].blue > 0.95f)
        rule.runOnIdle {
            assertTrue(parent.beginPage("details", false, true))
            parent.time = { 0f }
        }
        val outgoing = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(latest[8, 80], outgoing[8, 80])
    }

    @Test fun descendantViewportChangesRefreshTheAncestorExitPicture() {
        val parent = SettingsCardMorph(fullScreen = true)
        val child = SettingsCardMorph(fullScreen = true, parent = parent)
        val viewport = mutableStateOf(0)
        val release = child.observeCaptureInputs { viewport.value }
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    parent,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer().drawWithContent {
                        drawRect(if (viewport.value == 0) Color.Red else Color.Blue)
                    },
                )
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            parent.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            parent.beginPage("documents", true, true)
            parent.finish()
            viewport.value = 1
        }
        val source = rule.onRoot().captureToImage().toPixelMap()
        assertTrue(source[8, 80].blue > 0.95f)
        rule.runOnIdle {
            assertTrue(parent.beginPage("details", false, true))
            parent.time = { 0f }
        }
        val outgoing = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(source[8, 80], outgoing[8, 80])
        rule.runOnIdle { release() }
    }

    @Test fun viewportChangedBeforeItsDrawCannotReplayAnOlderExitPicture() {
        val morph = SettingsCardMorph(fullScreen = true)
        val viewport = mutableStateOf(0)
        val release = morph.observeCaptureInputs { viewport.value }
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .settingsCardMorphHost(
                        morph,
                        SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                    ).background(Color.Red),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            morph.beginPage("documents", true, true)
            morph.finish()
            viewport.value = 1
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            // No intervening draw: a stale retained image must not cover the restored parent.
            viewport.value = 2
            assertTrue(morph.beginPage("details", false, true))
            assertEquals(0, morph.keptPictures)
            release()
        }
    }

    @Test fun liveContentOutsideTheGrowingContourStaysAtNativeOpacity() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.99f }.background(color.value))
            }
        }
        val before = rule.onRoot().captureToImage().toPixelMap()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            assertTrue(morph.beginPage("documents", true, true))
            morph.time = { 0.1f }
            color.value = Color.Blue
        }
        val after = rule.onRoot().captureToImage().toPixelMap()
        // Outside the contour, the live destination remains at its native layer opacity.
        assertEquals(before[8, 80].red, after[8, 80].blue, 0.02f)
        assertTrue(after[8, 80].red < 0.05f)
    }

    @Test fun unmeasuredReentryCannotReuseAnEarlierLaunchRectangle() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(Modifier.fillMaxSize().background(color.value))
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            assertTrue(morph.beginPage("documents", true, true))
            morph.finish()
            color.value = Color.Blue
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            // A different route/programmatic entry replaces this presentation without a measured tap.
            assertFalse(morph.beginPage("documents", true, true))
            color.value = Color.Green
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            assertFalse(morph.beginPage("details", false, true))
        }
    }

    @Test fun protectedFullscreenReverseKeepsGeometryWithoutAnOpaqueSheet() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        val row = Rect(16f, 340f, 344f, 392f)
        val release = morph.holdSecure()
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .settingsCardMorphHost(
                        morph,
                        SettingsMorphColors(Color.Black, Color.Black, Color.White),
                    ).background(color.value),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = row
            assertTrue(morph.beginPage("members", true, true))
            morph.finish()
            color.value = Color.Blue
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.beginPage("details", false, true))
            morph.time = { 0.4f }
            color.value = Color.Red
        }
        val restored = rule.onRoot().captureToImage().toPixelMap()
        // The centre lies inside the shrinking contour. Missing secure pixels must reveal the live parent.
        assertTrue(restored[180, 366].red > 0.35f)
        assertTrue(restored[180, 366].blue < 0.05f)
        rule.runOnIdle {
            assertTrue(morph.page!!.active)
            assertEquals(0, morph.keptPictures)
            release()
        }
    }

    @Test fun fullscreenChildCardRegistrationsCannotSelectTheOrdinalAlgorithm() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .settingsCardMorphHost(
                        morph,
                        SettingsMorphColors(Color.Black, Color.Black, Color.White),
                    ).background(color.value),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            assertTrue(morph.beginPage("notifications", true, true))
            morph.report("first-child", Rect(16f, 120f, 344f, 200f))
            morph.report("second-child", Rect(16f, 220f, 344f, 300f))
            morph.time = { 0.1f }
            color.value = Color.Blue
        }
        val frame = rule.onRoot().captureToImage().toPixelMap()
        assertEquals("live content remains fully opaque outside the contour", 1f, frame[8, 80].blue, 0.02f)
        assertEquals(0f, frame[8, 80].red, 0.02f)
        assertEquals(null, morph.travel("first-child"))
    }

    // Keep clipping, target movement and frozen-frame assertions in causal order.

    @Test
    @Suppress("LongMethod")
    fun clippedNativeChromePreservesOutsideInkAndMovingTargetRebasesTheDrawnFrame() {
        val page = SettingsPageMorph()
        val time = mutableStateOf(1f)
        val child = mutableStateOf(false)
        val source = Rect(20f, 40f, 340f, 220f)
        val target = mutableStateOf(Rect(50f, 60f, 310f, 420f))
        val viewport = Rect(0f, 100f, 360f, 700f)
        val group = Any()
        val firstRow = Any()
        val lastRow = Any()
        val viewportToken = Any()
        lateinit var host: Rect

        fun endpoint(bounds: Rect) =
            SettingsRouteEndpoint(
                group,
                "child",
                bounds,
                androidx.compose.ui.geometry
                    .RoundRect(bounds, 20f, 20f),
                Color.Black,
                Color.White,
                true,
                true,
                viewportToken = viewportToken,
            )

        fun report(bounds: Rect) {
            page.reportViewport(viewportToken, viewport)
            page.reportDestination(group, endpoint(bounds))
            page.reportRow(
                group,
                firstRow,
                endpoint(Rect(bounds.left, bounds.top, bounds.right, 80f)).copy(last = false),
            )
            page.reportRow(
                group,
                lastRow,
                endpoint(Rect(bounds.left, 80f, bounds.right, bounds.bottom)).copy(first = false),
            )
        }
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        host = Rect(0f, 0f, size.width, size.height)
                        report(if (child.value) target.value else source)
                        with(page) { draw(host, time.value, true, Color.Black) }
                    }.drawWithContent {
                        drawRect(Color.Black)
                        // Toolbar ink crosses both the clipped full-row top and the offscreen seam.
                        drawRect(
                            if (child.value) Color.Blue else Color.Green,
                            androidx.compose.ui.geometry
                                .Offset(100f, 20f),
                            androidx.compose.ui.geometry
                                .Size(160f, 70f),
                        )
                        val bounds = if (child.value) target.value else source
                        clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom) {
                            drawPath(
                                dev.ipf.whitenoise.android.ui.theme
                                    .connectedRowBorderPath(bounds, endpoint(bounds).corners, true, true, 1f),
                                Color.White,
                                style =
                                    androidx.compose.ui.graphics.drawscope
                                        .Stroke(1f),
                            )
                            drawLine(
                                Color.White,
                                androidx.compose.ui.geometry
                                    .Offset(bounds.left + 0.5f, 79.5f),
                                androidx.compose.ui.geometry
                                    .Offset(bounds.right - 0.5f, 79.5f),
                                strokeWidth = 1f,
                            )
                        }
                    },
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            page.pendingOrigin = source
            assertTrue(page.begin("child", true, true, host))
            child.value = true
            report(target.value)
            assertTrue(page.admitDestination())
        }
        for (fraction in listOf(0.2f, 0.7f)) {
            rule.runOnIdle { time.value = fraction }
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            for (y in listOf(40, 60, 79)) {
                val ink = pixels[180, y]
                assertEquals("live outside ink stays opaque at $fraction, y=$y", 1f, ink.blue, 0.01f)
                assertEquals(0f, ink.red, 0.01f)
            }
            assertEquals("visible old native side is removed", Color.Black, pixels[20, 160])
            assertEquals("clipping invents no viewport-edge border", Color.Black, pixels[180, 100])
            val x =
                page
                    .renderedContours(fraction)
                    .single()
                    .bounds.left
                    .toInt()
            val coverage = (x - 2..x + 3).sumOf { pixels[it, 160].red.toDouble() }.toFloat()
            assertEquals("replacement contour retains native coverage", 1f, coverage, 0.04f)
        }
        val before = rule.onRoot().captureToImage().toPixelMap()
        val geometry = page.renderedContours(time.value)
        rule.runOnIdle { target.value = Rect(70f, 70f, 290f, 460f) }
        val after = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(
            "layout movement freezes the displayed geometry at the same clock",
            geometry,
            page.renderedContours(time.value),
        )
        for (y in 0 until before.height) {
            for (x in 0 until before.width) assertEquals("moving-target rebase at ($x,$y)", before[x, y], after[x, y])
        }
        rule.runOnIdle { time.value = 1f }
        val endpoint = rule.onRoot().captureToImage().toPixelMap()
        assertEquals("the live moved target owns the endpoint", Color.White, endpoint[70, 160])
        assertEquals("the old destination side is absent", Color.Black, endpoint[50, 160])
    }

    // One pixel scenario compares native coverage and excludes old and future chrome at the same samples.

    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun nativeBorderCoverageStaysOpaqueAndOldAndFutureChromeAreAbsent() {
        val page = SettingsPageMorph()
        val time = mutableStateOf(1f)
        val child = mutableStateOf(false)
        val source = Rect(20f, 100f, 340f, 220f)
        val target = Rect(50f, 300f, 310f, 500f)
        val token = Any()
        val rowToken = Any()
        val lastRowToken = Any()
        lateinit var host: Rect

        fun endpoint(
            bounds: Rect,
            last: Boolean = true,
        ) = SettingsRouteEndpoint(
            token,
            token,
            bounds,
            androidx.compose.ui.geometry
                .RoundRect(bounds, 20f, 20f),
            Color.Black,
            Color.White,
            true,
            last,
        )

        fun report(bounds: Rect) {
            page.reportDestination(token, endpoint(bounds))
            val row = Rect(bounds.left, bounds.top, bounds.right, bounds.center.y)
            page.reportRow(token, rowToken, endpoint(row, last = false))
            val lastRow = Rect(bounds.left, bounds.center.y, bounds.right, bounds.bottom)
            page.reportRow(token, lastRowToken, endpoint(lastRow).copy(first = false))
        }
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        host = Rect(0f, 0f, size.width, size.height)
                        report(if (child.value) target else source)
                        with(page) { draw(host, time.value, true, Color.Black) }
                    }.drawWithContent {
                        drawRect(Color.Black)
                        val bounds = if (child.value) target else source
                        drawPath(
                            dev.ipf.whitenoise.android.ui.theme.connectedRowBorderPath(
                                bounds,
                                androidx.compose.ui.geometry
                                    .RoundRect(bounds, 20f, 20f),
                                true,
                                true,
                                1f,
                            ),
                            Color.White,
                            style =
                                androidx.compose.ui.graphics.drawscope
                                    .Stroke(1f),
                        )
                        drawLine(
                            Color.White,
                            androidx.compose.ui.geometry
                                .Offset(bounds.left + 0.5f, bounds.center.y - 0.5f),
                            androidx.compose.ui.geometry
                                .Offset(bounds.right - 0.5f, bounds.center.y - 0.5f),
                            strokeWidth = 1f,
                        )
                        drawRect(
                            if (child.value) Color.Blue else Color.Green,
                            androidx.compose.ui.geometry
                                .Offset(bounds.center.x - 4f, bounds.top + 24f),
                            androidx.compose.ui.geometry
                                .Size(8f, 8f),
                        )
                    },
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            page.pendingOrigin = source
            assertTrue(page.begin("child", true, true, host))
            child.value = true
            report(target)
            assertTrue(page.admitDestination())
        }
        for (fraction in listOf(0.001f, 0.2f, 0.39f, 0.4f, 0.41f, 0.65f, 0.9f, 0.999f)) {
            rule.runOnIdle { time.value = fraction }
            val pixels = rule.onRoot().captureToImage().toPixelMap()
            val contour = page.renderedContours(fraction).single()
            val y = (contour.bounds.top + 32f).toInt()
            val x = contour.bounds.left.toInt()
            val coverage = (x - 2..x + 3).sumOf { pixels[it, y].red.toDouble() }.toFloat()
            assertEquals("native one-pixel border coverage at $fraction", 1f, coverage, 0.04f)
            assertEquals(1f, contour.modulationAlpha, 0f)
            for (native in listOf(source, target)) {
                val px = native.left.toInt()
                val py = native.center.y.toInt() - 8
                if (kotlin.math.abs(native.left - contour.bounds.left) > 4f ||
                    py < contour.bounds.top - 4f ||
                    py > contour.bounds.bottom + 4f
                ) {
                    assertEquals("no ghost native side at $fraction", 0f, pixels[px, py].red, 0.01f)
                }
                val seamY = native.center.y.toInt() - 1
                if (kotlin.math.abs(
                        seamY -
                            contour.seams
                                .single()
                                .start.y,
                    ) > 4f &&
                    kotlin.math.abs(seamY - contour.bounds.top) > 4f &&
                    kotlin.math.abs(seamY - contour.bounds.bottom) > 4f
                ) {
                    assertEquals("no ghost native seam at $fraction", 0f, pixels[180, seamY].red, 0.01f)
                }
            }
        }
    }

    @Test fun unoutlinedOutgoingBodyKeepsItsMeasuredViewportAfterSourceReportsClear() {
        val page = SettingsPageMorph()
        val time = mutableStateOf(1f)
        val child = mutableStateOf(false)
        val body = Rect(16f, 100f, 344f, 300f)
        val viewport = Any()
        val group = Any()
        lateinit var host: Rect
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        host = Rect(0f, 0f, size.width, size.height)
                        if (!child.value) {
                            page.reportViewport(viewport, Rect(0f, 100f, size.width, 220f))
                            reportUnoutlinedBody(page, viewport, group, body)
                        }
                        with(page) { draw(host, time.value, true, Color.Black) }
                    }.drawWithContent { drawRect(if (child.value) Color.Blue else Color.Red) },
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            page.pendingOrigin = body
            assertTrue(page.begin(true, true, true, host))
            child.value = true
            time.value = 0.1f
            assertTrue(page.admitDestination())
        }
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals("measured unoutlined ink fades", 0.75f, pixels[100, 150].red, 0.02f)
        assertEquals("header stays live", 1f, pixels[100, 50].blue, 0.01f)
        assertEquals("outside group stays live", 1f, pixels[8, 150].blue, 0.01f)
        assertEquals("source viewport stays frozen", 1f, pixels[100, 240].blue, 0.01f)
        rule.runOnIdle { time.value = 0.4f }
        assertEquals(
            "unoutlined outgoing ink retires fast",
            0f,
            rule
                .onRoot()
                .captureToImage()
                .toPixelMap()[100, 150]
                .red,
            0.01f,
        )
    }

    private fun reportUnoutlinedBody(
        page: SettingsPageMorph,
        viewport: Any,
        group: Any,
        body: Rect,
    ) {
        page.reportDestination(
            group,
            SettingsRouteEndpoint(
                group,
                false,
                body,
                androidx.compose.ui.geometry
                    .RoundRect(body, 0f, 0f),
                Color.Black,
                Color.Unspecified,
                true,
                true,
                viewportToken = viewport,
            ),
        )
    }

    private fun assertStationaryGlyphs(
        fraction: Float,
        child: Boolean,
        nativeHeader: List<List<Pair<Int, Int>>>,
        nativeBody: List<List<Pair<Int, Int>>>,
        nativeOutgoing: List<List<Pair<Int, Int>>>,
        ink: (Float, (Color) -> Float) -> List<List<Pair<Int, Int>>>,
    ) {
        val actual = ink(0.8f) { if (child) it.blue else it.red }
        val outgoing = ink(0.8f) { if (child) it.red else it.blue }.drop(2)
        assertEquals("fixed opaque header", nativeHeader, ink(0.8f) { it.green }.take(2))
        if (fraction == 0f) assertEquals("outgoing starts at native coordinates", nativeOutgoing, outgoing)
        if (fraction >= 0.4f) {
            val remaining = ink(0.01f) { if (child) it.red else it.blue }.drop(2)
            assertTrue("outgoing retires in the fast phase", remaining.all { it.isEmpty() })
        }
        if (fraction <= 0.8f) {
            assertTrue("body waits for the outlines", actual.drop(2).all { it.isEmpty() })
        } else if (fraction == 1f) {
            assertEquals("body arrives at native coordinates", nativeBody, actual.drop(2))
        } else {
            actual.drop(2).zip(nativeBody).forEach { (visible, native) ->
                if (fraction >= 0.98f) assertTrue("late body is visibly present", visible.isNotEmpty())
                assertTrue("body never travels outside native glyphs", native.containsAll(visible))
            }
        }
    }

    private inner class NativeGlyphScene {
        val page = SettingsPageMorph()
        val time = mutableStateOf(1f)
        val child = mutableStateOf(false)
        val source = Rect(16f, 100f, 344f, 400f)
        val target = Rect(16f, 120f, 344f, 360f)
        val group = Any()
        lateinit var host: Rect

        fun report(rect: Rect) {
            page.reportDestination(
                group,
                SettingsRouteEndpoint(
                    group,
                    child.value,
                    rect,
                    androidx.compose.ui.geometry
                        .RoundRect(rect, 20f, 20f),
                    Color.Black,
                    Color.White,
                    true,
                    true,
                ),
            )
        }

        fun install() {
            rule.setContent {
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            host = Rect(0f, 0f, size.width, size.height)
                            report(if (child.value) target else source)
                            with(page) { draw(host, time.value, true, Color.Black) }
                        }.drawWithContent {
                            drawRect(Color.Black)
                            drawIntoCanvas { canvas ->
                                val paint =
                                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        color = android.graphics.Color.GREEN
                                        textSize = 16f
                                    }
                                canvas.nativeCanvas.drawText("Header", 60f, 50f, paint)
                                canvas.nativeCanvas.drawText("Helper", 60f, 80f, paint)
                                paint.color =
                                    if (child.value) android.graphics.Color.BLUE else android.graphics.Color.RED
                                canvas.nativeCanvas.drawText("Voice & language", 60f, 160f, paint)
                                canvas.nativeCanvas.drawText("Privacy & Security", 60f, 250f, paint)
                            }
                        },
                )
            }
        }

        fun ink(
            threshold: Float = 0.8f,
            channel: (Color) -> Float,
        ) = rule.onRoot().captureToImage().toPixelMap().let { pixels ->
            listOf(40..52, 70..82, 150..162, 240..252).map { band ->
                (band.first..band.last)
                    .flatMap { y -> (50..250).map { x -> x to y } }
                    .filter { (x, y) -> channel(pixels[x, y]) > threshold }
            }
        }
    }

    @Test fun liveHeaderStaysOpaqueAndBodyGlyphsRevealAtNativePositionsDuringBothDirections() {
        with(NativeGlyphScene()) {
            install()
            val nativeHeader = ink { it.green }.take(2)
            val nativeParent = ink { it.red }.drop(2)
            // Calibrate each native color independently: channel thresholds can select different
            // antialiased edge pixels even when the glyph geometry is unchanged.
            rule.runOnIdle { child.value = true }
            val nativeChild = ink { it.blue }.drop(2)
            rule.runOnIdle { child.value = false }
            rule.onRoot().captureToImage()
            assertTrue((nativeHeader + nativeParent + nativeChild).all { it.isNotEmpty() })

            rule.runOnIdle {
                page.pendingOrigin = source
                assertTrue(page.begin(true, true, true, host))
                child.value = true
                report(target)
                assertTrue(page.admitDestination())
            }
            for (fraction in listOf(0f, 0.2f, 0.39f, 0.4f, 0.41f, 0.65f, 0.9f, 0.98f, 1f)) {
                rule.runOnIdle { time.value = fraction }
                assertStationaryGlyphs(
                    fraction,
                    child.value,
                    nativeHeader,
                    if (child.value) nativeChild else nativeParent,
                    if (child.value) nativeParent else nativeChild,
                    ::ink,
                )
            }
            rule.runOnIdle {
                page.pendingOrigin = target
                assertTrue(page.begin(false, false, true, host))
                child.value = false
                report(source)
                assertTrue(page.admitDestination())
            }
            for (fraction in listOf(0f, 0.2f, 0.39f, 0.4f, 0.41f, 0.65f, 0.9f, 0.98f, 1f)) {
                rule.runOnIdle { time.value = fraction }
                assertStationaryGlyphs(
                    fraction,
                    child.value,
                    nativeHeader,
                    if (child.value) nativeChild else nativeParent,
                    if (child.value) nativeParent else nativeChild,
                    ::ink,
                )
            }
        }
    }

    @get:Rule val rule = createComposeRule()

    @Test fun settledPageReturnsToTheExactClickedRowWithoutKeepingItsControls() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        val host = Rect(0f, 0f, 360f, 780f)
        val row = Rect(16f, 340f, 344f, 392f)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(Modifier.fillMaxSize().background(color.value))
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = row
            assertTrue(morph.beginPage("documents", true, true))
            color.value = Color.Blue
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.beginPage("details", false, true))
            assertEquals(1, morph.keptPictures)
            assertEquals(host, morph.page!!.bounds(host, 1f))
        }
    }

    @Test fun secureHoldClearsBothSettledAndAlreadyKeptPagePictures() {
        val morph = SettingsCardMorph(fullScreen = true)
        val color = mutableStateOf(Color.Red)
        rule.setContent {
            Box(
                Modifier
                    .fillMaxSize()
                    .settingsCardMorphHost(
                        morph,
                        SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                    ).background(color.value),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            morph.page!!.pendingOrigin = Rect(16f, 340f, 344f, 392f)
            morph.beginPage("documents", true, true)
            color.value = Color.Blue
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            assertTrue(morph.beginPage("details", false, true))
            assertEquals(1, morph.keptPictures)
            val release = morph.holdSecure()
            assertEquals(0, morph.keptPictures)
            assertFalse(morph.page!!.hasSettledPicture)
            assertTrue(morph.page!!.active)
            release()
            assertFalse(morph.beginPage("other", false, true))
        }
    }

    @Test fun chatCaptureIsExcludedWhileTheSafeSettingsExitFrameSurvives() {
        val morph = SettingsCardMorph(fullScreen = true)
        val capture = mutableStateOf(false)
        rule.setContent {
            morph.captureContent = capture.value
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    morph,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            )
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertFalse(morph.page!!.hasSettledPicture)
            morph.page!!.pendingOrigin = Rect(16f, 24f, 64f, 72f)
            assertTrue(morph.beginPage("settings", true, true))
            assertEquals(0, morph.keptPictures)
            capture.value = true
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(morph.page!!.hasSettledPicture)
            assertTrue(morph.beginPage("chats", false, true))
            capture.value = false
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertFalse(morph.page!!.hasSettledPicture)
            assertEquals(0, morph.keptPictures)
            morph.finish()
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle { assertFalse(morph.page!!.hasSettledPicture) }
    }

    @Test fun aSecureDescendantClearsEveryEnclosingPageRecorder() {
        val parent = SettingsCardMorph(fullScreen = true)
        val child = SettingsCardMorph(fullScreen = true, parent = parent)
        rule.setContent {
            Box(
                Modifier.fillMaxSize().settingsCardMorphHost(
                    parent,
                    SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                ),
            ) {
                Box(
                    Modifier.fillMaxSize().settingsCardMorphHost(
                        child,
                        SettingsMorphColors(Color.Black, Color.Black, Color.Unspecified),
                    ),
                )
            }
        }
        rule.onRoot().captureToImage()
        rule.runOnIdle {
            assertTrue(parent.page!!.hasSettledPicture)
            assertTrue(child.page!!.hasSettledPicture)
            val release = child.holdSecure()
            assertFalse(parent.recording)
            assertFalse(child.recording)
            assertFalse(parent.page!!.hasSettledPicture)
            assertFalse(child.page!!.hasSettledPicture)
            release()
            assertTrue(parent.recording)
            assertTrue(child.recording)
        }
    }
}
