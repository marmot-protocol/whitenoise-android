package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.ConnectedRowShape
import dev.ipf.whitenoise.android.ui.theme.amoledRowSeamColor
import dev.ipf.whitenoise.android.ui.theme.connectedRowBorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Native modifier is the independent raster oracle; never calls the legacy contour renderer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SettingsContinuousContourPixelsTest {
    @get:Rule val rule = createComposeRule()

    // Compare one fractional-density native fixture and its travelling contour at identical pixels.
    @Test
    @Suppress("LongMethod")
    fun continuousPageMatchesNativeFractionalDensityCornersAndTJunctions() {
        val page = SettingsPageMorph()
        val travelling = mutableStateOf(false)
        val time = mutableStateOf(1f)
        val group = Any()
        val rows = List(3) { Any() }
        lateinit var host: Rect

        fun report() {
            val endpoint =
                SettingsRouteEndpoint(
                    group,
                    "same-native-endpoint",
                    host,
                    RoundRect(host, 30f, 30f),
                    Color.Black,
                    Color.White,
                    true,
                    true,
                    amoledRowSeamColor(Color.White),
                    strokeWidth = 2f,
                )
            page.reportDestination(group, endpoint)
            rows.forEachIndexed { index, token ->
                page.reportRow(
                    group,
                    token,
                    endpoint.copy(
                        bounds = Rect(0f, index * 78f, 300f, (index + 1) * 78f),
                        first = index == 0,
                        last = index == 2,
                    ),
                )
            }
        }
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                Box(
                    Modifier.size(200.dp, 156.dp).background(Color.Black).drawWithContent {
                        host = Rect(0f, 0f, size.width, size.height)
                        report()
                        if (travelling.value) {
                            with(
                                page,
                            ) { draw(host, time.value, true, Color.Black) }
                        } else {
                            drawContent()
                        }
                    },
                ) {
                    Column {
                        repeat(3) { index ->
                            val shape =
                                ConnectedRowShape(
                                    RoundedCornerShape(
                                        topStart = if (index == 0) 20.dp else 0.dp,
                                        topEnd = if (index == 0) 20.dp else 0.dp,
                                        bottomStart = if (index == 2) 20.dp else 0.dp,
                                        bottomEnd = if (index == 2) 20.dp else 0.dp,
                                    ),
                                    index == 0,
                                    index == 2,
                                )
                            Box(
                                Modifier
                                    .size(
                                        200.dp,
                                        52.dp,
                                    ).connectedRowBorder(shape, Color.White, amoledRowSeamColor(Color.White)),
                            )
                        }
                    }
                }
            }
        }
        val native = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(300, native.width)
        assertEquals(234, native.height)
        rule.runOnIdle {
            page.pendingOrigin = host
            assertTrue(page.begin("same-native-endpoint", true, true, host))
            report()
            assertTrue(page.admitDestination())
            travelling.value = true
        }
        // Equal endpoint geometry isolates coverage ownership from easing/position rounding.
        // Full-frame equality includes all four AA corners and both side/seam intersections.
        for (fraction in listOf(0f, 0.0001f, 0.9999f, 1f)) {
            rule.runOnIdle { time.value = fraction }
            val frame = rule.onRoot().captureToImage().toPixelMap()
            for (y in 0 until native.height) {
                for (x in 0 until native.width) {
                    assertEquals("native coverage at $fraction ($x,$y)", native[x, y].red, frame[x, y].red, 0.015f)
                }
            }
        }
        rule.runOnIdle { page.finish() }
    }
}
