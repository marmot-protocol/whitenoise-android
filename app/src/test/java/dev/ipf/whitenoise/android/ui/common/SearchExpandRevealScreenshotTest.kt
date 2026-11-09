package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.Search
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/**
 * A search field opened from its icon grows from the end edge toward the start edge, mirrored in
 * RTL, and a closed field shrinks back into the end edge as an inert picture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SearchExpandRevealScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun bubbleStartsIconSizedAtTheEndEdge() {
        assertEquals(Rect(250f, 0f, 300f, 50f), searchExpandBubble(300f, 50f, 0f, LayoutDirection.Ltr))
        assertEquals(Rect(0f, 0f, 50f, 50f), searchExpandBubble(300f, 50f, 0f, LayoutDirection.Rtl))
        assertEquals(Rect(125f, 0f, 300f, 50f), searchExpandBubble(300f, 50f, 0.5f, LayoutDirection.Ltr))
        assertEquals(Rect(0f, 0f, 300f, 50f), searchExpandBubble(300f, 50f, 1f, LayoutDirection.Rtl))
    }

    @Test fun midRevealLight() = capture("light", dark = false, largeRtl = false, progress = 0.45f)

    @Test fun midRevealLargeRtlDark() = capture("large_rtl_dark", dark = true, largeRtl = true, progress = 0.45f)

    @Test fun reducedMotionShowsTheWholeField() =
        capture(
            "reduced_motion_light",
            dark = false,
            largeRtl = false,
            progress = null,
        )

    @Test fun midCollapseLight() = captureCollapse("light", dark = false, largeRtl = false, animate = true)

    @Test fun midCollapseLargeRtlDark() {
        captureCollapse("large_rtl_dark", dark = true, largeRtl = true, animate = true)
    }

    @Test fun reducedMotionClosesAtOnce() =
        captureCollapse(
            "reduced_motion_light",
            dark = false,
            largeRtl = false,
            animate = false,
        )

    /** Opens a settled field, closes it and captures the frame halfway through the close. */
    private fun captureCollapse(
        variant: String,
        dark: Boolean,
        largeRtl: Boolean,
        animate: Boolean,
    ) {
        val open = mutableStateOf(true)
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, if (largeRtl) 1.6f else 1f),
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    val collapse = rememberSearchCollapse(open.value, animationsEnabled = animate)
                    Surface(Modifier.fillMaxWidth().searchCollapseHost(collapse)) {
                        CompositionLocalProvider(LocalSearchCollapse provides collapse) {
                            Box(Modifier.padding(16.dp)) {
                                if (open.value) {
                                    OutlinedTextField(
                                        value = "",
                                        onValueChange = {},
                                        placeholder = { Text("Search") },
                                        leadingIcon = { Icon(Icons.Default.Search, null) },
                                        modifier =
                                            Modifier.fillMaxWidth().searchExpandReveal(
                                                animationsEnabled = animate,
                                                fixedProgress = 1f,
                                            ),
                                    )
                                } else {
                                    Spacer(Modifier.fillMaxWidth().height(56.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        // Robolectric draws only on demand; draw once so the open field records its picture.
        composeRule.onRoot().captureToImage()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnIdle { open.value = false }
        composeRule.waitForIdle()
        repeat(SEARCH_COLLAPSE_MILLIS / 2 / FRAME_MILLIS) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(FRAME_MILLIS.toLong(), TimeUnit.MILLISECONDS)
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/search_collapse_$variant.png")
    }

    private fun capture(
        variant: String,
        dark: Boolean,
        largeRtl: Boolean,
        progress: Float?,
    ) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDensity provides Density(density.density, if (largeRtl) 1.6f else 1f),
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface {
                        Box(Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = "",
                                onValueChange = {},
                                placeholder = { Text("Search") },
                                leadingIcon = { Icon(Icons.Default.Search, null) },
                                modifier =
                                    Modifier.fillMaxWidth().searchExpandReveal(
                                        animationsEnabled = progress != null,
                                        fixedProgress = progress,
                                    ),
                            )
                        }
                    }
                }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/snapshots/search_expand_reveal_$variant.png")
    }
}

private const val FRAME_MILLIS = 16
