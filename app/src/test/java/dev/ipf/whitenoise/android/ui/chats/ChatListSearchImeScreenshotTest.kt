package dev.ipf.whitenoise.android.ui.chats

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Raising the keyboard over chat-list search keeps every visible row, avatar included, painted whole. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListSearchImeScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val scope = TestScope()
    private val mounted = mutableStateOf(true)
    private var fixture: OffWindowActionFixture? = null
    private var originalTimeZone: TimeZone? = null

    /** Pins relative timestamps so the captured rows are reproducible across hosts. */
    @Before fun stableTimeZone() {
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    /** Unmounts the screen and settles the fixture's mutations before the next test. */
    @After fun close() {
        rule.runOnIdle { mounted.value = false }
        rule.waitForIdle()
        val model = fixture
        rule.runOnIdle { model?.state?.mutationsScope?.cancel() }
        try {
            awaitProgress {
                model
                    ?.state
                    ?.mutationsScope
                    ?.coroutineContext
                    ?.get(Job)
                    ?.isCompleted != false
            }
        } finally {
            rule.runOnIdle { model?.controller?.onCleared() }
            scope.cancel()
            originalTimeZone?.let(TimeZone::setDefault)
        }
    }

    /** The left-hand avatar keeps its start edge once the keyboard finishes opening over search. */
    @Test fun lightKeyboardKeepsRowStartEdge() = verify("light")

    /** RTL rows keep their left-hand timestamp, with the dark palette and enlarged text. */
    @Test fun darkRtlLargeFontKeyboardKeepsRowStartEdge() = verify("dark_rtl_large", dark = true, rtl = true)

    /** Opens search from the top bar, raises the IME and requires the row to match its keyboard-less frame. */
    private fun verify(
        name: String,
        dark: Boolean = false,
        rtl: Boolean = false,
    ) {
        val model = OffWindowActionFixture(scope).also { fixture = it }
        var search by mutableStateOf(GlobalSearchState())
        rule.setContent {
            if (mounted.value) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    WhiteNoiseTheme(darkTheme = dark, fontScale = if (rtl) 1.6f else 1f) {
                        ChatsScreen(
                            model.state,
                            model.controller,
                            {},
                            { _, _, _, _ -> },
                            globalSearchState = search,
                            onGlobalSearchStateChange = { transition -> search = transition(search) },
                        )
                    }
                }
            }
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.chat_list_search_open)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("chats.searchField").assertIsDisplayed()
        val row = rule.onNodeWithTag("chat.row.$ACTION_SURVIVOR")
        val withoutKeyboard = row.assertIsDisplayed().captureToImage().toPixelMap()

        dispatchIme(bottom = 300)
        val withKeyboard = row.assertIsDisplayed().captureToImage().toPixelMap()
        rule.onRoot().captureRoboImage("src/test/snapshots/chat_list_search_ime_$name.png")

        assertEquals(
            "row size",
            withoutKeyboard.width to withoutKeyboard.height,
            withKeyboard.width to withKeyboard.height,
        )
        val clipped = firstChangedColumn(withoutKeyboard, withKeyboard)
        assertTrue("keyboard changed the row's painting from column $clipped", clipped == null)
        dispatchIme(bottom = 0)
    }

    /** Returns the first x at which the two captures differ, or null when they are identical. */
    private fun firstChangedColumn(
        before: PixelMap,
        after: PixelMap,
    ): Int? =
        (0 until before.width).firstOrNull { x ->
            (0 until before.height).any { y -> before[x, y] != after[x, y] }
        }

    /** Dispatches real window insets so Compose's IME visibility flips as it does once the keyboard settles. */
    private fun dispatchIme(bottom: Int) {
        rule.runOnUiThread {
            val insets =
                WindowInsetsCompat
                    .Builder()
                    .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, bottom))
                    .setVisible(WindowInsetsCompat.Type.ime(), bottom > 0)
                    .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView.rootView, insets)
        }
        rule.waitForIdle()
    }

    /** Pumps the fixture's test scheduler and main looper until [ready] holds. */
    private fun awaitProgress(ready: () -> Boolean) {
        rule.waitUntil {
            scope.testScheduler.runCurrent()
            ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS)
            scope.testScheduler.runCurrent()
            ready()
        }
    }
}
