package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.LayoutDirection
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.chats.ConversationSearchNavBar
import dev.ipf.whitenoise.android.ui.conversation.ConversationSearchScanStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** In-chat search arrows stay honest while the full-history scan loads or fails (#2873). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationSearchNavBarScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Loading shows no partial count, disables both arrows and is announced politely. */
    @Test
    fun loadingLight() {
        val steps = render(ConversationSearchScanStatus.LOADING, matchCount = 2, dark = false, largeRtl = false)
        composeRule.onNodeWithTag(STATUS_TAG).assertTextEquals("Searching all messages…")
        composeRule
            .onNodeWithTag(STATUS_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        composeRule.onNodeWithContentDescription("Previous match").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Next match").assertIsNotEnabled()
        capture("conversation_search_nav_loading_light")
        assertEquals(emptyList<String>(), steps)
    }

    /** A completed scan shows the final count and enables stepping. */
    @Test
    fun completeDark() {
        val steps = render(ConversationSearchScanStatus.COMPLETE, matchCount = 12, dark = true, largeRtl = false)
        composeRule.onNodeWithTag(STATUS_TAG).assertTextEquals("1/12")
        composeRule.onNodeWithContentDescription("Next match").assertIsEnabled().performClick()
        capture("conversation_search_nav_complete_dark")
        assertEquals(listOf("next"), steps)
    }

    /** A failed scan labels the count as loaded-only and offers a retry, in large RTL. */
    @Test
    fun failedDarkLargeRtl() {
        val steps = render(ConversationSearchScanStatus.FAILED, matchCount = 2, dark = true, largeRtl = true)
        composeRule.onNodeWithTag(STATUS_TAG).assertTextEquals("1/2 in loaded messages")
        capture("conversation_search_nav_failed_dark_large_rtl")
        composeRule.onNodeWithContentDescription("Retry search").performClick()
        composeRule.onNodeWithContentDescription("Previous match").assertIsEnabled().performClick()
        assertEquals(listOf("retry", "prev"), steps)
    }

    /** A failed scan with no loaded matches never claims there are none. */
    @Test
    fun failedWithoutLoadedMatchesLight() {
        render(ConversationSearchScanStatus.FAILED, matchCount = 0, dark = false, largeRtl = false)
        composeRule.onNodeWithTag(STATUS_TAG).assertTextEquals("Couldn't search older messages")
        composeRule.onNodeWithContentDescription("Next match").assertIsNotEnabled()
        capture("conversation_search_nav_failed_empty_light")
    }

    /** Renders the bar and records which callbacks fire. */
    private fun render(
        status: ConversationSearchScanStatus,
        matchCount: Int,
        dark: Boolean,
        largeRtl: Boolean,
    ): MutableList<String> {
        val steps = mutableListOf<String>()
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, fontScale = if (largeRtl) 2f else 1f) {
                    Box(Modifier.fillMaxWidth().testTag(ROOT_TAG)) {
                        ConversationSearchNavBar(
                            matchCount = matchCount,
                            activeIndex = 0,
                            status = status,
                            onPrev = { steps += "prev" },
                            onNext = { steps += "next" },
                            onRetryScan = { steps += "retry" },
                        )
                    }
                }
            }
        }
        return steps
    }

    /** Captures the committed baseline for one state. */
    private fun capture(name: String) {
        composeRule.onNodeWithTag(ROOT_TAG).captureRoboImage("src/test/snapshots/$name.png")
    }

    private companion object {
        const val ROOT_TAG = "conversation.search.nav"
        const val STATUS_TAG = "conversation.search.status"
    }
}
