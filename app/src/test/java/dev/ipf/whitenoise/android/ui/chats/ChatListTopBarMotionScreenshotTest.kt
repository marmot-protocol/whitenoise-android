package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ErrorCollector
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Temporal counterparts to ChatListTopBarScreenshotTest, using the real toolbar's measured decoration. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h500dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatListTopBarMotionScreenshotTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule val rule = createComposeRule(effectContext = StandardTestDispatcher(scheduler))
    @get:Rule val visualErrors = ErrorCollector()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var previousScale: String? = null
    private var open by mutableStateOf(false)
    private var committedOpen = false
    private var query = "Retained query"

    /** Exercises enabled motion without depending on the runner's global animator default. */
    @Before
    fun enableMotion() {
        previousScale = Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    /** Restores the animation preference and releases the paused test clock even after a failed baseline. */
    @After
    fun restoreMotion() {
        rule.mainClock.autoAdvance = true
        Settings.Global.putString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousScale)
    }

    /** Captures opening and closing in light mode without replacing the caller-owned query. */
    @Test
    fun searchRoundTripLight() = captureRoundTrip("light", dark = false)

    /** A narrow, short dark window retains both measured directions. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h360dp-mdpi")
    fun searchRoundTripNarrowDark() = captureRoundTrip("narrow_dark", dark = true)

    /** Large RTL text and AMOLED contrast retain a visible middle frame and one real editor. */
    @Test
    @Config(sdk = [36], qualifiers = "en-w280dp-h360dp-mdpi")
    fun searchRoundTripLargeRtlAmoled() = captureRoundTrip("large_rtl_amoled", dark = true, largeRtl = true)

    /** No click ripple or cursor blink can masquerade as the production outline's changing pixels. */
    private fun captureRoundTrip(
        variant: String,
        dark: Boolean,
        largeRtl: Boolean = false,
    ) {
        val appState = ChatRowPortFixtures.state(context)
        val focusRequester = FocusRequester()
        rule.setContent {
            SideEffect { committedOpen = open }
            CompositionLocalProvider(
                LocalCursorBlinkEnabled provides false,
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = largeRtl, fontScale = if (largeRtl) 2f else 1f) {
                    Surface(Modifier.fillMaxWidth().testTag(TAG)) {
                        ChatListTopBar(
                            appState = appState,
                            searchOpen = open,
                            searchQuery = query,
                            searchFocusRequester = focusRequester,
                            onSearchQueryChange = { query = it },
                            onSearchOpen = { open = true },
                            onSearchClose = { open = false },
                            onMic = {},
                            onOpenSettings = {},
                            onSwitchAccount = {},
                            connectivityState = ConnectivityBannerState.Hidden,
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        captureTransition(variant, opening = true)
        captureTransition(variant, opening = false)
    }

    /** A committed first frame, timed midpoint and retired decoration are separate rendered assertions. */
    private fun captureTransition(
        variant: String,
        opening: Boolean,
    ) {
        val start = rule.mainClock.currentTime
        rule.runOnIdle {
            open = opening
            Snapshot.sendApplyNotifications()
        }
        scheduler.runCurrent()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertEquals(opening, committedOpen)
        assertEquals(start + 16L, rule.mainClock.currentTime)
        val direction = if (opening) "open" else "close"
        assertEditor(opening)
        capture("${direction}_first_$variant")
        rule.mainClock.advanceTimeBy(80)
        rule.waitForIdle()
        assertEquals(start + 96L, rule.mainClock.currentTime)
        assertEditor(opening)
        val middle = pixels()
        capture("${direction}_mid_$variant")
        rule.mainClock.advanceTimeBy(144)
        rule.waitForIdle()
        assertEquals(start + 240L, rule.mainClock.currentTime)
        assertEditor(opening)
        val settled = pixels()
        capture("${direction}_settled_$variant")
        assertFalse("The measured outline must change actual pixels before retiring", middle.contentEquals(settled))
        assertEquals("Retained query", query)
    }

    /** Decorative geometry never adds a stale or duplicate text input. */
    private fun assertEditor(opening: Boolean) {
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(if (opening) 1 else 0)
    }

    /** Retains all requested actuals on the first CI run while leaving every missing baseline a failure. */
    private fun capture(name: String) {
        visualErrors.checkSucceeds {
            rule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/chat_search_motion_$name.png")
        }
    }

    /** Reads rendered pixels only; no fixture-generated rectangle substitutes for the production decoration. */
    private fun pixels(): IntArray {
        val image = rule.onNodeWithTag(TAG).captureToImage()
        return IntArray(image.width * image.height).also { image.readPixels(it) }
    }

    private companion object {
        const val TAG = "search-motion-viewport"
    }
}
