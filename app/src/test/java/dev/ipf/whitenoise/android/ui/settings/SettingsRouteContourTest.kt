package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The first travelling frame must reproduce native sides and its physical-pixel shared seam. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class SettingsRouteContourTest {
    @get:Rule val rule = createComposeRule()

    @Test fun firstSegmentMatchesNative() = compareNative(first = true, last = false)

    @Test fun middleSegmentMatchesNative() = compareNative(first = false, last = false)

    @Test fun lastSegmentMatchesNative() = compareNative(first = false, last = true)

    // One fixture renders the native and animated paths with the same density and paint inputs.
    @Suppress("LongMethod")
    private fun compareNative(
        first: Boolean,
        last: Boolean,
    ) {
        val travelling = mutableStateOf(false)
        val shape =
            ConnectedRowShape(
                RoundedCornerShape(
                    topStart = if (first) 20.dp else 0.dp,
                    topEnd = if (first) 20.dp else 0.dp,
                    bottomEnd = if (last) 20.dp else 0.dp,
                    bottomStart = if (last) 20.dp else 0.dp,
                ),
                first,
                last,
            )
        val outline = Color.White
        val seam = amoledRowSeamColor(outline)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1.5f)) {
                Box(
                    Modifier.size(200.dp, 52.dp).background(Color.Black).then(
                        if (!travelling.value) {
                            Modifier.connectedRowBorder(shape, outline, seam)
                        } else {
                            Modifier.drawWithContent {
                                drawContent()
                                val bounds = Rect(Offset.Zero, size)
                                val corners =
                                    when (val resolved = shape.createOutline(size, layoutDirection, this)) {
                                        is Outline.Rounded -> resolved.roundRect
                                        else -> RoundRect(bounds)
                                    }
                                val source =
                                    SettingsRouteEndpoint(
                                        "source",
                                        "destination",
                                        bounds,
                                        corners,
                                        Color.Black,
                                        outline,
                                        first,
                                        last,
                                        seam,
                                    )
                                drawRouteContour(
                                    bounds,
                                    corners,
                                    Path().apply { addRoundRect(corners) },
                                    source,
                                    0f,
                                    outline,
                                )
                            }
                        },
                    ),
                )
            }
        }
        val native = rule.onRoot().captureToImage().toPixelMap()
        rule.runOnIdle { travelling.value = true }
        val morph = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(native.width, morph.width)
        assertEquals(native.height, morph.height)
        for (y in 0 until native.height) {
            for (x in 0 until native.width) {
                assertEquals("native contour at ($x,$y)", native[x, y], morph[x, y])
            }
        }
    }
}
