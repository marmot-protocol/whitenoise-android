package dev.ipf.whitenoise.android.ui.conversation.media

import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReusableContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.LocalConversationMotionEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real clickable/button interactions exercise the draw-only feedback without a framework ripple drawable. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-mdpi")
class MediaPressIndicationTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var inputMode: InputModeManager

    @Test
    fun heldReadyImagePressIsVisibleAndCancelRemovesItWithoutOpening() {
        var opens = 0
        setImage { opens++ }
        composeRule.onNodeWithTag("image").performTouchInput { down(center) }
        assertBlueLevel(0.88f)
        composeRule.onRoot().captureRoboImage("src/test/snapshots/media_viewer_pressed_feedback.png")
        composeRule.runOnUiThread {
            assertFalse(WindowInspector.getGlobalWindowViews().any(::containsPlatformRipple))
        }
        composeRule.onNodeWithTag("image").performTouchInput { cancel() }
        assertBlueLevel(1f)
        assertEquals(0, opens)
    }

    @Test
    fun readyImageKeepsClickLongPressAndKeyboardActions() {
        var opens = 0
        var longPresses = 0
        setImage(onLongPress = { longPresses++ }) { opens++ }
        composeRule.onNodeWithTag("image").performTouchInput { longClick() }
        composeRule.runOnIdle { assertEquals(1, longPresses) }
        assertEquals(0, opens)
        composeRule.onNodeWithTag("image").performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(1, opens) }
        composeRule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        composeRule.onNodeWithTag("image").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.onNodeWithTag("image").performKeyInput { pressKey(Key.Enter) }
        composeRule.runOnIdle { assertEquals(2, opens) }
    }

    @Test
    fun releaseDoesNotClearFocusAndCancelDoesNotClearHover() {
        val source = MutableInteractionSource()
        setControlledFeedback(source)
        val focus = FocusInteraction.Focus()
        val press = PressInteraction.Press(Offset.Zero)
        emit(source, focus)
        emit(source, press)
        emit(source, PressInteraction.Release(press))
        assertBlueLevel(0.88f)
        emit(source, FocusInteraction.Unfocus(focus))
        assertBlueLevel(1f)
        val hover = HoverInteraction.Enter()
        val nextPress = PressInteraction.Press(Offset.Zero)
        emit(source, hover)
        emit(source, nextPress)
        emit(source, PressInteraction.Cancel(nextPress))
        assertBlueLevel(0.92f)
        emit(source, HoverInteraction.Exit(hover))
        assertBlueLevel(1f)
    }

    @Test
    fun reusedContentDoesNotKeepThePreviousItemsInteraction() {
        val source = MutableInteractionSource()
        val item = mutableStateOf(0)
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.Black) {
                    val feedback = rememberMediaPressIndication()
                    ReusableContent(item.value) {
                        Box(
                            Modifier
                                .size(96.dp, 64.dp)
                                .testTag("image")
                                .indication(source, feedback)
                                .background(Color.Blue),
                        )
                    }
                }
            }
        }
        emit(source, FocusInteraction.Focus())
        assertBlueLevel(0.88f)
        composeRule.runOnIdle { item.value++ }
        assertBlueLevel(1f)
    }

    @Test
    fun disabledCloseKeepsItsTargetAndDoesNotDispatchThenEnabledCloseDoes() {
        var closes = 0
        val enabled = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalMediaViewerPressFeedback provides true,
                    androidx.compose.material3.LocalContentColor provides Color.Black,
                ) {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        MediaViewerCloseButton(onDismiss = { closes++ }, enabled = enabled.value)
                    }
                }
            }
        }
        val close = composeRule.onNodeWithContentDescription("Close")
        close.assertIsNotEnabled().assertWidthIsEqualTo(48.dp)
        close.performTouchInput { click() }
        composeRule.runOnIdle { assertEquals(0, closes) }
        composeRule.runOnIdle { enabled.value = true }
        close.performTouchInput { down(center) }
        val pixels = close.captureToImage().toPixelMap()
        assertEquals("circle feedback stays inside the icon target", 1f, pixels[1, 1].red, 0.02f)
        assertTrue("held close still shows feedback", pixels[pixels.width / 2, 4].red < 0.97f)
        close.performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(1, closes) }
    }

    @Test
    fun themeColorChangeKeepsTheExistingFocusState() {
        val source = MutableInteractionSource()
        val tint = mutableStateOf(Color.Black)
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint.value) {
                    Box(
                        Modifier
                            .size(96.dp, 64.dp)
                            .testTag("image")
                            .indication(source, rememberMediaPressIndication())
                            .background(Color.Blue),
                    )
                }
            }
        }
        emit(source, FocusInteraction.Focus())
        assertBlueLevel(0.88f)
        composeRule.runOnIdle { tint.value = Color.Red }
        val pixels = composeRule.onNodeWithTag("image").captureToImage().toPixelMap()
        val center = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals(0.12f, center.red, 0.02f)
        assertEquals(0.88f, center.blue, 0.02f)
    }

    @Test
    fun pendingAndReducedMotionImagesKeepTheDefaultIndication() {
        composeRule.setContent {
            WhiteNoiseTheme {
                val default = androidx.compose.foundation.LocalIndication.current
                CompositionLocalProvider(LocalConversationMotionEnabled provides true) {
                    MediaViewerThumbnailHost(MediaViewerThumbnailRegistry()) {
                        assertSame(default, rememberMediaViewerPressIndication(ready = false))
                    }
                }
                CompositionLocalProvider(LocalConversationMotionEnabled provides false) {
                    MediaViewerThumbnailHost(MediaViewerThumbnailRegistry()) {
                        assertSame(default, rememberMediaViewerPressIndication())
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun setImage(
        onLongPress: () -> Unit = {},
        onOpen: () -> Unit,
    ) {
        composeRule.setContent {
            inputMode = LocalInputModeManager.current
            WhiteNoiseTheme {
                CompositionLocalProvider(
                    LocalConversationMotionEnabled provides true,
                    androidx.compose.material3.LocalContentColor provides Color.Black,
                ) {
                    MediaViewerThumbnailHost(MediaViewerThumbnailRegistry()) {
                        Box(Modifier.fillMaxSize().background(Color.White)) {
                            Box(
                                Modifier
                                    .size(96.dp, 64.dp)
                                    .testTag("image")
                                    .combinedClickable(
                                        interactionSource = null,
                                        indication = rememberMediaViewerPressIndication(),
                                        onLongClick = onLongPress,
                                        onClick = onOpen,
                                    ).background(Color.Blue),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun setControlledFeedback(source: MutableInteractionSource) {
        composeRule.setContent {
            WhiteNoiseTheme {
                CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Color.Black) {
                    Box(
                        Modifier
                            .size(96.dp, 64.dp)
                            .testTag("image")
                            .indication(source, rememberMediaPressIndication())
                            .background(Color.Blue),
                    )
                }
            }
        }
    }

    private fun emit(
        source: MutableInteractionSource,
        interaction: androidx.compose.foundation.interaction.Interaction,
    ) {
        composeRule.runOnIdle { assertTrue(source.tryEmit(interaction)) }
        composeRule.waitForIdle()
    }

    private fun assertBlueLevel(expected: Float) {
        val pixels = composeRule.onNodeWithTag("image").captureToImage().toPixelMap()
        assertEquals(expected, pixels[pixels.width / 2, pixels.height / 2].blue, 0.02f)
    }

    private fun containsPlatformRipple(view: View): Boolean =
        view.javaClass.name.endsWith(".RippleHostView") ||
            (view is ViewGroup && (0 until view.childCount).any { containsPlatformRipple(view.getChildAt(it)) })
}
