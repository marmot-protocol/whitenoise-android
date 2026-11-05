package dev.ipf.whitenoise.android.ui.screenshot

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.settings.SETTINGS_HOME_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.settings.SettingsHomeContent
import dev.ipf.whitenoise.android.ui.settings.SettingsSearchEligibility
import dev.ipf.whitenoise.android.ui.settings.prepareSettingsSearch
import dev.ipf.whitenoise.android.ui.settings.searchSettings
import dev.ipf.whitenoise.android.ui.settings.settingsHomeState
import dev.ipf.whitenoise.android.ui.settings.settingsSearchCatalog
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Frozen-clock evidence for the production keyed list, rather than a static animation claim. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class SettingsSearchMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()
    private val query = mutableStateOf("appearance")
    private val motion = mutableStateOf(true)
    private val target = "settings.search.result.Appearance.font_size"
    private val removed = "settings.search.result.home.Appearance"
    private val strongest = "settings.search.result.Appearance.font_family"

    @Test fun firstResultStaysAnchoredWhileLowerResultsMove() {
        mount()
        val expectedTop =
            rule
                .onNodeWithTag(removed)
                .fetchSemanticsNode()
                .boundsInRoot.top
        rule.runOnIdle { query.value = "appearance font" }
        advanceCommittedLayout()
        rule.onNodeWithTag(removed).assertDoesNotExist()
        assertFirstAt(expectedTop)
        val first = top()
        capture("settings_search_filter_first")
        rule.mainClock.advanceTimeBy(80)
        val middle = top()
        assertFirstAt(expectedTop)
        capture("settings_search_filter_middle")
        rule.mainClock.advanceTimeBy(320)
        val settled = top()
        assertFirstAt(expectedTop)
        capture("settings_search_filter_settled")
        assertTrue(
            "Expected lower-row placement motion: $first > $middle > $settled",
            first > middle && middle > settled,
        )
    }

    @Test fun liveReducedMotionImmediatelySettlesTheCurrentResults() {
        mount()
        val expected =
            rule
                .onNodeWithTag(removed)
                .fetchSemanticsNode()
                .boundsInRoot.top
        rule.runOnIdle { query.value = "appearance font" }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.runOnIdle { motion.value = false }
        advanceCommittedLayout()
        rule.onNodeWithTag(removed).assertDoesNotExist()
        assertFirstAt(expected)
        val firstBounds = rule.onNodeWithTag(strongest).fetchSemanticsNode().boundsInRoot
        assertEquals(firstBounds.bottom + WhiteNoiseSpacing.Related.value, top(), 1f)
        capture("settings_search_filter_reduced_motion")
    }

    @Test fun replacementStrongestMatchNeverTravelsThroughAnEmptyTopSlot() {
        query.value = "app font"
        mount()
        val expected =
            rule
                .onNodeWithTag(strongest)
                .fetchSemanticsNode()
                .boundsInRoot.top
        rule.runOnIdle { query.value = "font size" }
        advanceCommittedLayout()
        rule.onNodeWithTag(strongest).assertDoesNotExist()
        assertEquals(expected, top(), 1f)
        rule.mainClock.advanceTimeBy(80)
        assertEquals(expected, top(), 1f)
        rule.mainClock.advanceTimeBy(320)
        assertEquals(expected, top(), 1f)
    }

    @Test fun emptyResultsAndDeletionPublishCurrentContentWithoutWaitingForMotion() {
        mount()
        rule.runOnIdle { query.value = "no matching setting" }
        advanceCommittedLayout()
        rule.onNodeWithTag("settings.search.field").assertTextContains("no matching setting")
        rule
            .onNodeWithText(
                ApplicationProvider.getApplicationContext<Context>().getString(R.string.settings_search_no_results),
            ).assertExists()
        rule.onNodeWithTag(target).assertDoesNotExist()
        rule.onNodeWithTag(removed).assertDoesNotExist()
        rule.runOnIdle { query.value = "font" }
        advanceCommittedLayout()
        rule.onNodeWithTag("settings.search.field").assertTextContains("font")
        rule.onNodeWithTag(target).assertExists()
        rule.runOnIdle { query.value = "" }
        advanceCommittedLayout()
        rule.onNodeWithTag("settings.search.field").assertTextContains("")
        rule.onNodeWithTag("settings.search.results").assertDoesNotExist()
    }

    private fun mount() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val documents =
            prepareSettingsSearch(
                settingsSearchCatalog(),
                SettingsSearchEligibility(true, true, true, true),
                context::getString,
            )
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true) {
                SettingsHomeContent(
                    state = settingsHomeState(false, false),
                    account = null,
                    profileCount = 0,
                    appUpdateInfo = AppUpdateInfo("1.0.0", null, null, null, null),
                    versionName = "1.0.0",
                    onBack = {},
                    onOpenShareConnect = {},
                    onAddProfile = {},
                    onSwitchProfile = {},
                    onOpenDetail = {},
                    onAppUpdateAction = {},
                    searchOpen = true,
                    searchQuery = query.value,
                    searchResults = searchSettings(documents, query.value),
                    searchAnimationsEnabled = motion.value,
                )
            }
        }
        rule.mainClock.advanceTimeBy(500)
    }

    /** One frame publishes query state; the next commits the lazy list's measure/semantics tree.
     * This is 32 ms of the frozen clock, not a wait for the 200 ms placement animation to finish.
     */
    private fun advanceCommittedLayout() {
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun assertFirstAt(expected: Float) {
        assertEquals(
            expected,
            rule
                .onNodeWithTag(strongest)
                .fetchSemanticsNode()
                .boundsInRoot.top,
            1f,
        )
    }

    private fun top() =
        rule
            .onNodeWithTag(target)
            .fetchSemanticsNode()
            .boundsInRoot.top

    private fun capture(name: String) = rule.onNodeWithTag(SETTINGS_HOME_CONTENT_TAG).captureRoboImage("src/test/snapshots/$name.png")
}
