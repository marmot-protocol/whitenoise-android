package dev.ipf.whitenoise.android.ui.screenshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.ui.conversation.DaySeparator
import dev.ipf.whitenoise.android.ui.conversation.PinnedDayRibbonLabel
import dev.ipf.whitenoise.android.ui.conversation.UNREAD_MESSAGES_DIVIDER_CONTENT_TAG
import dev.ipf.whitenoise.android.ui.conversation.UnreadMessagesDivider
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Actual inline/pinned native date chrome and unread count across the supported presentation themes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ConversationDateChromeScreenshotTest {
    @get:Rule val composeRule = createComposeRule()

    /** Inline remains transparent while the pinned date is a translucent circle-shaped surface. */
    @Test fun datesLight() = capture("conversation_dates_light")

    /** Dark pinned background and text preserve the prototype's surfaceDim/onSurface roles. */
    @Test fun datesDark() = capture("conversation_dates_dark", dark = true)

    /** The pinned date remains outlined on the fixed black AMOLED canvas. */
    @Test fun datesAmoled() = capture("conversation_dates_amoled", dark = true, amoled = true)

    /** Long localized-style labels and count remain readable at twice-sized RTL text. */
    @Test fun datesLargeRtl() = capture("conversation_dates_large_rtl", largeRtl = true)

    /** Native top spacer plus slot and date insets equal the prototype 8dp content + 16dp date inset. */
    @Test fun firstDayHasPrototypeLeadingAndTrailingGaps() {
        mountSpacing(first = true)
        val leading = composeRule.onNodeWithTag("dates.leading").fetchSemanticsNode().boundsInRoot
        val date = composeRule.onNodeWithTag("conversation.date.inline").fetchSemanticsNode().boundsInRoot
        val message = composeRule.onNodeWithTag("dates.message").fetchSemanticsNode().boundsInRoot
        assertEquals(24f, date.top - leading.top, 1f)
        assertEquals(18f, message.top - date.bottom, 1f)
    }

    /** Later days match the prototype's 16dp date inset plus its separate 2dp timeline slot on both sides. */
    @Test fun laterDayHasPrototypeGapsAroundItsPill() {
        mountSpacing(first = false)
        val leading = composeRule.onNodeWithTag("dates.leading").fetchSemanticsNode().boundsInRoot
        val date = composeRule.onNodeWithTag("conversation.date.inline").fetchSemanticsNode().boundsInRoot
        val message = composeRule.onNodeWithTag("dates.message").fetchSemanticsNode().boundsInRoot
        assertEquals(18f, date.top - leading.bottom, 1f)
        assertEquals(18f, message.top - date.bottom, 1f)
    }

    /** A same-item unread marker retains the complete prototype date-bottom/slot/unread-top interval. */
    @Test fun unreadImmediatelyAfterDateHasPrototypeGaps() {
        mountSpacing(first = false, unread = true)
        val date = composeRule.onNodeWithTag("conversation.date.inline").fetchSemanticsNode().boundsInRoot
        val divider = composeRule.onNodeWithTag(UNREAD_MESSAGES_DIVIDER_CONTENT_TAG).fetchSemanticsNode().boundsInRoot
        val message = composeRule.onNodeWithTag("dates.message").fetchSemanticsNode().boundsInRoot
        assertEquals(42f, divider.top - date.bottom, 1f)
        assertEquals(18f, message.top - divider.bottom, 1f)
    }

    /** Reproduces native 8dp slots and the embedded date/unread/message structure with measured content bounds. */
    private fun mountSpacing(
        first: Boolean,
        unread: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(360.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().height(if (first) 4.dp else 40.dp).testTag("dates.leading"),
                            )
                        }
                        item {
                            Column {
                                DaySeparator("September 13, 2026", atTranscriptStart = first)
                                if (unread) UnreadMessagesDivider(count = 5, followsDayHeader = true)
                                Box(Modifier.fillMaxWidth().height(40.dp).testTag("dates.message"))
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /** Mounts only production date/count presenters; labels are fixed to avoid wall-clock-dependent PNGs. */
    private fun capture(
        name: String,
        dark: Boolean = false,
        amoled: Boolean = false,
        largeRtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (largeRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark, amoled = amoled, fontScale = if (largeRtl) 2f else 1f) {
                    Surface(Modifier.width(360.dp).testTag("dates.root")) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            DaySeparator("September 13, 2026")
                            PinnedDayRibbonLabel("September 12, 2026", Modifier.testTag("dates.pinned"))
                            UnreadMessagesDivider(count = 5)
                        }
                    }
                }
            }
        }
        composeRule
            .onNodeWithText("September 13, 2026")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule
            .onNodeWithTag("dates.pinned")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        composeRule.onNodeWithText("5 unread messages").assertExists()
        composeRule.onNodeWithTag("dates.root").captureRoboImage("src/test/snapshots/$name.png")
    }
}
