package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.descendants
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.whitenoise.android.state.awaitConversationCondition
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_CLOSE_MILLIS
import dev.ipf.whitenoise.android.ui.common.APP_MOTION_OPEN_MILLIS
import dev.ipf.whitenoise.android.ui.common.PopupSourceActivation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.design.PopupSurfaceMotion
import dev.ipf.whitenoise.android.ui.group.TTS_AUTO_READ_GROUP_ROW_TAG
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadGroupActionRow
import dev.ipf.whitenoise.android.ui.group.TtsAutoReadPickerContent
import dev.ipf.whitenoise.android.ui.settings.SettingsGroup
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Material native window and production read-aloud body; no replacement dialog renderer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
@OptIn(ExperimentalMaterial3Api::class)
class PopupSourceMotionScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val captureFrames = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"
    private var denseDirectory: File? = null
    private var denseFrame = 0

    private fun startDenseCapture(name: String) {
        if (!captureFrames) return
        denseDirectory = File("build/popup-source-motion/$name").apply { mkdirs() }
        denseFrame = 0
        captureScreenRoboImage(File(denseDirectory, "source.png").path)
    }

    /** Sample actual clock steps; screen capture keeps activity and native dialog in one viewport. */
    private fun advanceCapturedTime(duration: Long) {
        if (!captureFrames) {
            composeRule.mainClock.advanceTimeBy(duration)
            return
        }
        var remaining = duration
        while (remaining > 0) {
            val step = minOf(16L, remaining)
            composeRule.mainClock.advanceTimeBy(step)
            composeRule.waitForIdle()
            captureScreenRoboImage(File(checkNotNull(denseDirectory), "frame-%03d.png".format(denseFrame++)).path)
            remaining -= step
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    // Keep three native-dialog cycles, input gates and measurements sequential with their captured fixture.
    @Suppress("LongMethod")
    fun actualConnectedRowNativeDialogOpensAndReturnsThreeTimes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        var shown by mutableStateOf(false)
        var activation by mutableStateOf<PopupSourceActivation?>(null)
        var sourceBottom by mutableStateOf(24)
        var surface: PopupSurfaceMotion? = null
        var bodySize = IntSize.Zero
        var bodyBounds = Rect.Zero
        var dismissals = 0
        var selections = 0

        // Declarative content shares the scenario's sole native state owner.
        @androidx.compose.runtime.Composable
        @Suppress("FunctionNaming")
        fun NativeDialogFixture() {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
                    SettingsGroup(Modifier.padding(bottom = sourceBottom.dp)) {
                        row("read-aloud") { rowContext ->
                            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                TtsAutoReadGroupActionRow(
                                    context = rowContext,
                                    title = "Read Aloud",
                                    provenanceLabel = "Use global default",
                                    onClick = { shown = true },
                                    sourceOwner = "read-aloud",
                                    sourceActivation = activation,
                                    onSourceActivated = { activation = it },
                                )
                            }
                        }
                    }
                }
                if (shown) {
                    WhiteNoiseAlertDialog(
                        source = activation,
                        onDismissRequest = {
                            dismissals++
                            shown = false
                        },
                        modifier =
                            Modifier.onGloballyPositioned {
                                bodySize = it.size
                                val position = it.localToRoot(androidx.compose.ui.geometry.Offset.Zero)
                                bodyBounds =
                                    Rect(
                                        position,
                                        androidx.compose.ui.geometry
                                            .Size(it.size.width.toFloat(), it.size.height.toFloat()),
                                    )
                            },
                        title = { Text("Read Aloud") },
                        text = {
                            TtsAutoReadPickerContent(
                                globalDefaultEnabled = false,
                                selectedOverride = null,
                                enabled = acceptsInput,
                                onSelect = {
                                    close {
                                        selections++
                                        dismissals++
                                        shown = false
                                    }
                                },
                            )
                        },
                        confirmButton = {
                            val current = this
                            SideEffect { surface = current }
                            CompositionLocalProvider(LocalRippleConfiguration provides null) {
                                TextButton(onClick = dismiss, enabled = acceptsInput) { Text("Cancel") }
                            }
                        },
                    )
                }
            }
        }
        composeRule.setContent { NativeDialogFixture() }
        composeRule.mainClock.autoAdvance = false
        var settledSize = IntSize.Zero
        val nativeSourceSide = sourceSidePixel()
        repeat(3) { cycle ->
            startDenseCapture("cycle_${cycle}_opening")
            surface = null
            composeRule.onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG).performClick()
            advanceCapturedTime(16L)
            composeRule.waitForIdle()
            assertNotNull("The real row must capture its own source", activation)
            assertTrue("Frozen source must still belong to the mounted row", activation!!.isCurrent())
            // The native dialog is a separate composition. Drain its setup work at this
            // clock time before reading the surface; do not advance away its input gate.
            awaitConversationCondition(timeoutMs = 5_000) {
                composeRule.runOnUiThread { composeRule.mainClock.scheduler.runCurrent() }
                composeRule.waitForIdle()
                surface != null
            }
            assertFalse("Input remains gated during the transition", surface!!.acceptsInput)
            assertFalse("Preparation keeps chrome with the real row", activation!!.ownsContour)
            assertEquals(nativeSourceSide, sourceSidePixel())
            // Preserve the initial native measurement before supplying the fullscreen traversal.
            val nativeSize = bodySize
            assertTrue(nativeSize.width > 0 && nativeSize.height > 0)
            traverseNativeWindow()
            repeat(4) {
                advanceCapturedTime(16L)
                composeRule.waitForIdle()
            }
            capture(cycle, "opening_initial")
            val initialWindow = windowPixels()
            advanceCapturedTime((APP_MOTION_OPEN_MILLIS / 3).toLong())
            assertTrue("Opening must have started after native readiness", surface!!.presence.value > 0f)
            assertTrue("Opening must remain animated", surface!!.presence.value < 1f)
            assertTrue("Only the ready travelling contour owns source chrome", activation!!.ownsContour)
            assertFalse("Input remains gated during the transition", surface!!.acceptsInput)
            assertEquals("Morph must preserve native body measurement", nativeSize, bodySize)
            capture(cycle, "opening_mid")
            advanceCapturedTime(APP_MOTION_OPEN_MILLIS.toLong() + 64)
            composeRule.onNodeWithText("Cancel").assertIsEnabled()
            assertEquals(1f, surface!!.presence.value, 0f)
            assertFalse("Settled native dialog releases source chrome", activation!!.ownsContour)
            assertEquals(nativeSourceSide, sourceSidePixel())
            assertEquals(nativeSize, bodySize)
            if (cycle == 0) settledSize = bodySize else assertEquals(settledSize, bodySize)
            assertTrue(
                "Source must require drawing outside the final native body",
                activation!!.endpoint.bounds.top > bodyBounds.bottom,
            )
            capture(cycle, "opening_settled")
            val settledWindow = windowPixels()
            val outsideBodyTop = (bodyBounds.bottom + 16).toInt().coerceIn(0, 779)
            var changedOutsideBody = 0
            for (y in outsideBodyTop until 780) {
                for (x in 0 until 360) {
                    if (initialWindow.getPixel(x, y) != settledWindow.getPixel(x, y)) changedOutsideBody++
                }
            }
            assertTrue(
                "The actual native window must render the source contour outside its final body",
                changedOutsideBody > 16,
            )
            initialWindow.recycle()
            settledWindow.recycle()
            startDenseCapture("cycle_${cycle}_return")
            composeRule.onNodeWithText("Cancel").performClick()
            advanceCapturedTime(16L)
            assertEquals(cycle, dismissals)
            assertFalse("Input remains gated during the transition", surface!!.acceptsInput)
            capture(cycle, "return_initial")
            advanceCapturedTime((APP_MOTION_CLOSE_MILLIS / 2).toLong())
            assertTrue(surface!!.presence.value > 0f && surface!!.presence.value < 1f)
            assertEquals(nativeSize, bodySize)
            capture(cycle, "return_mid")
            // A repeated terminal request must retain the first accepted dismissal.
            composeRule.runOnIdle { surface!!.dismiss() }
            advanceCapturedTime(APP_MOTION_CLOSE_MILLIS.toLong() + 64)
            composeRule.onNode(isDialog()).assertDoesNotExist()
            assertEquals(cycle + 1, dismissals)
            assertEquals(0, selections)
            composeRule.onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG).assertExists()
            composeRule
                .onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG)
                .captureRoboImage("src/test/snapshots/popup_source_cycle_${cycle}_return_settled.png")
        }
        composeRule.onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG).performClick()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        traverseNativeWindow()
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        composeRule.mainClock.advanceTimeBy((APP_MOTION_OPEN_MILLIS / 3).toLong())
        assertTrue(activation!!.ownsContour)
        composeRule.runOnIdle { sourceBottom = 32 }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        assertFalse("Invalid geometry restores native source chrome", activation!!.ownsContour)
        assertEquals(nativeSourceSide, sourceSidePixel())
        composeRule.mainClock.advanceTimeBy(APP_MOTION_OPEN_MILLIS.toLong() + 64)
        composeRule.onNodeWithText("Cancel").assertIsEnabled().performClick()
        composeRule.mainClock.advanceTimeBy(APP_MOTION_CLOSE_MILLIS.toLong() + 64)
        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertFalse("Disposal releases the activated source", activation!!.ownsContour)
    }

    @Test
    fun reducedMotionKeepsNativeSourceChrome() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        var shown by mutableStateOf(false)
        var activation by mutableStateOf<PopupSourceActivation?>(null)
        composeRule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.BottomCenter) {
                    SettingsGroup(Modifier.padding(bottom = 24.dp)) {
                        row("read-aloud") { rowContext ->
                            TtsAutoReadGroupActionRow(
                                context = rowContext,
                                title = "Read Aloud",
                                provenanceLabel = "Use global default",
                                onClick = { shown = true },
                                sourceOwner = "read-aloud",
                                sourceActivation = activation,
                                onSourceActivated = { activation = it },
                            )
                        }
                    }
                }
                if (shown) {
                    WhiteNoiseAlertDialog(
                        source = activation,
                        onDismissRequest = { shown = false },
                        title = { Text("Read Aloud") },
                        confirmButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
                    )
                }
            }
        }
        val nativeSourceSide = sourceSidePixel()
        composeRule.onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG).performClick()
        composeRule.onNodeWithText("Cancel").assertIsEnabled()
        assertNotNull(activation)
        assertFalse("Reduced motion never transfers chrome to a travelling contour", activation!!.ownsContour)
        assertEquals(nativeSourceSide, sourceSidePixel())
        composeRule.onNodeWithText("Cancel").performClick()
        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertFalse(activation!!.ownsContour)
    }

    /** Sample the straight connected-row side, away from rounded corners and text. */
    private fun sourceSidePixel(): Color {
        val pixels = composeRule.onNodeWithTag(TTS_AUTO_READ_GROUP_ROW_TAG).captureToImage().toPixelMap()
        return pixels[0, pixels.height / 2]
    }

    private fun windowPixels(): Bitmap {
        val bitmap = Bitmap.createBitmap(360, 780, Bitmap.Config.ARGB_8888)
        composeRule.runOnIdle { nativeDecor().draw(Canvas(bitmap)) }
        return bitmap
    }

    private fun nativeDecor(): View =
        WindowInspector.getGlobalWindowViews().single {
            (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_APPLICATION
        }

    private fun capture(
        cycle: Int,
        phase: String,
    ) {
        composeRule.onNode(isDialog()).captureRoboImage("src/test/snapshots/popup_source_cycle_${cycle}_$phase.png")
    }

    /** Robolectric needs the real WindowManager resize traversal after MATCH_PARENT admission. */
    private fun traverseNativeWindow() {
        composeRule.runOnIdle {
            val decor = nativeDecor()
            val provider = (decor as ViewGroup).descendants.filterIsInstance<DialogWindowProvider>().single()
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, provider.window.attributes.width)
            assertEquals(WindowManager.LayoutParams.MATCH_PARENT, provider.window.attributes.height)
            (provider as View).requestLayout()
            decor.requestLayout()
            decor.measure(
                View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(780, View.MeasureSpec.EXACTLY),
            )
            decor.layout(0, 0, decor.measuredWidth, decor.measuredHeight)
        }
    }
}
