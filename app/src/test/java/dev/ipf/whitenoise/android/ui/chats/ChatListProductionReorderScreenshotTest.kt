package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual rows mid-promotion retain theme, readable geometry and accessibility layout. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h950dp-mdpi")
class ChatListProductionReorderScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** Light-theme placement uses real unread/avatar/preview content. */
    @Test
    fun middlePromotionLight() = capture(dark = false, rtl = false, fontScale = 1f)

    /** Dark-theme placement uses the same current candidate and frame. */
    @Test
    fun middlePromotionDark() = capture(dark = true, rtl = false, fontScale = 1f)

    /** Large-text RTL motion must retain readable row geometry in light theme. */
    @Test
    fun middlePromotionLargeRtlLight() = capture(dark = false, rtl = true, fontScale = 2f)

    /** Large-text RTL motion must retain contrast and layout in dark theme. */
    @Test
    fun middlePromotionLargeRtlDark() = capture(dark = true, rtl = true, fontScale = 2f)

    /** Captures a deterministic animation frame, never a live account or network image. */
    private fun capture(
        dark: Boolean,
        rtl: Boolean,
        fontScale: Float,
    ) {
        val original = listOf("A", "B", "C", "D", "E", "F")
        var ids by mutableStateOf(original)
        val rows = original.associateWith(::realChatListMotionRow)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appState = ChatRowPortFixtures.state(context)
        composeRule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalDensity provides Density(density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.fillMaxSize()) {
                        ChatListHeadReorderMotionHarness(
                            itemIds = ids,
                            listState = rememberLazyListState(),
                            rowHeight = 48.dp,
                            rowContent = { id, modifier, enabled ->
                                ChatRow(
                                    item = rows.getValue(id),
                                    appState = appState,
                                    onClick = {},
                                    onOpenProfile = {},
                                    interactionsEnabled = enabled,
                                    modifier = modifier,
                                )
                            },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { ids = listOf("E", "A", "B", "C", "D", "F") }
        repeat(6) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
        }
        val theme = if (dark) "dark" else "light"
        val accessibility = if (rtl) "_large_rtl" else ""
        composeRule.onRoot().captureRoboImage("src/test/snapshots/chat_list_middle_motion${accessibility}_$theme.png")
    }
}
