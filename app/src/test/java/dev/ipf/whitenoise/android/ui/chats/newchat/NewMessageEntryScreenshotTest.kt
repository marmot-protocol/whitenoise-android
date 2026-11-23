package dev.ipf.whitenoise.android.ui.chats.newchat

import android.content.Context
import android.provider.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.chats.ChatRowPortFixtures
import dev.ipf.whitenoise.android.ui.chats.ChatsScreen
import dev.ipf.whitenoise.android.ui.chats.leftScopeController
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.cancel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import com.github.takahirom.roborazzi.captureScreenRoboImage as captureRoboImage

/** Captures both windows of the production Chats → New Message transition on the same clock. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class NewMessageEntryScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun penOpensAndReturnsLight() = captureRoundTrip("light", dark = false)

    @Test fun penOpensAndReturnsAmoled() = captureRoundTrip("amoled")

    @Test
    @Config(qualifiers = "ar-rEG-ldrtl-w360dp-h780dp-mdpi")
    fun penOpensAndReturnsLargeRtl() = captureRoundTrip("large_rtl", largeRtl = true)

    private fun captureRoundTrip(
        variant: String,
        dark: Boolean = true,
        largeRtl: Boolean = false,
    ) {
        val app = ChatRowPortFixtures.state(context)
        val controller = leftScopeController(app, emptyList())
        val resolver = context.contentResolver
        val animatorScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalSystemAnimationsEnabled provides true,
                ) {
                    WhiteNoiseTheme(darkTheme = dark, amoled = dark, fontScale = if (largeRtl) 1.6f else 1f) {
                        ChatsScreen(app, controller, {}, { _, _, _, _ -> })
                    }
                }
            }
            assertRoundTrip(variant)
        } finally {
            composeRule.mainClock.autoAdvance = true
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, animatorScale)
            controller.onCleared()
            app.mutationsScope.cancel()
        }
    }

    private fun assertRoundTrip(variant: String) {
        val pen = composeRule.onNodeWithContentDescription(context.getString(R.string.new_message))
        val panel = composeRule.onNodeWithTag("new_message.screen", useUnmergedTree = true)
        val newGroup = composeRule.onNodeWithText(context.getString(R.string.new_group))
        pen.assertIsDisplayed()
        composeRule.mainClock.autoAdvance = false

        pen.performClick()
        advanceFrames(6)
        newGroup.assertIsNotDisplayed()
        capture("open", variant)
        advanceFrames(32)
        panel.assertIsDisplayed()
        newGroup.assertIsDisplayed()

        composeRule.onNodeWithContentDescription(context.getString(R.string.back)).performClick()
        advanceFrames(6)
        panel.assertExists()
        newGroup.assertIsNotDisplayed()
        capture("close", variant)
        advanceFrames(32)
        panel.assertDoesNotExist()
        pen.assertIsDisplayed()
    }

    /** Step both test clocks so the native dialog is traversed before each capture. */
    private fun advanceFrames(count: Int) {
        repeat(count) {
            composeRule.mainClock.advanceTimeByFrame()
            ShadowLooper.idleMainLooper(16L, TimeUnit.MILLISECONDS)
        }
        composeRule.waitForIdle()
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun capture(
        direction: String,
        variant: String,
    ) {
        captureRoboImage("src/test/snapshots/new_message_entry_${direction}_${variant}.png")
    }
}
