package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BubbleCollapsibleFooterLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun tallBodyUsesOneStableCollapsedHeight() {
        val measuredHeights = mutableListOf<Int>()

        composeRule.setContent {
            WhiteNoiseTheme {
                BubbleCollapsibleFooterLayout(
                    maxBodyHeight = 96.dp,
                    readMore = { Text("Read More", Modifier.testTag("read-more")) },
                    footer = { Text("7:19 PM") },
                    modifier = Modifier.onSizeChanged { measuredHeights += it.height },
                ) {
                    Column {
                        repeat(20) { Text("Long body line $it") }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("read-more").assertIsDisplayed()
        assertTrue("the collapsed bubble must be measured", measuredHeights.isNotEmpty())
        assertEquals(
            "a visible long-message row must not publish a full height before its collapsed height",
            1,
            measuredHeights.distinct().size,
        )
    }

    @Test
    fun shortBodyDoesNotDisplayReadMore() {
        composeRule.setContent {
            WhiteNoiseTheme {
                BubbleCollapsibleFooterLayout(
                    maxBodyHeight = 96.dp,
                    readMore = { Text("Read More", Modifier.testTag("read-more")) },
                    footer = { Text("7:19 PM") },
                ) {
                    Text("Short body")
                }
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("read-more").assertIsNotDisplayed()
    }

    @Test
    fun collapsedHeightEqualsBodyCapPlusOneFooterRow() {
        var layoutHeight = 0
        var readMoreHeight = 0
        var footerHeight = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                BubbleCollapsibleFooterLayout(
                    maxBodyHeight = 96.dp,
                    readMore = { Text("Read More", Modifier.onSizeChanged { readMoreHeight = it.height }) },
                    footer = { Text("7:19 PM", Modifier.onSizeChanged { footerHeight = it.height }) },
                    modifier = Modifier.onSizeChanged { layoutHeight = it.height },
                ) {
                    Column {
                        repeat(20) { Text("Long body line $it") }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bodyCap = with(composeRule.density) { 96.dp.roundToPx() }
        assertEquals(bodyCap + maxOf(readMoreHeight, footerHeight), layoutHeight)
    }

    @Test
    fun narrowCollapsedFooterStacksReadMoreAboveFooter() {
        var layoutHeight = 0
        var readMoreHeight = 0
        var footerHeight = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                BubbleCollapsibleFooterLayout(
                    maxBodyHeight = 96.dp,
                    readMore = { Text("Read More", Modifier.onSizeChanged { readMoreHeight = it.height }) },
                    footer = { Text("7:19 PM", Modifier.onSizeChanged { footerHeight = it.height }) },
                    modifier =
                        Modifier
                            .width(24.dp)
                            .onSizeChanged { layoutHeight = it.height },
                ) {
                    Column {
                        repeat(20) { Text("Long body line $it") }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        val bodyCap = with(composeRule.density) { 96.dp.roundToPx() }
        assertEquals(bodyCap + readMoreHeight + footerHeight, layoutHeight)
    }

    @Test
    fun suppliedLastLineWidthKeepsShortBodyFooterInline() {
        var layoutHeight = 0
        var contentHeight = 0

        composeRule.setContent {
            WhiteNoiseTheme {
                BubbleCollapsibleFooterLayout(
                    maxBodyHeight = 96.dp,
                    readMore = { Text("Read More") },
                    footer = { Text("7:19 PM") },
                    modifier =
                        Modifier
                            .width(140.dp)
                            .onSizeChanged { layoutHeight = it.height },
                    lastLineWidth = with(composeRule.density) { 16.dp.roundToPx() },
                ) {
                    Spacer(
                        Modifier
                            .width(120.dp)
                            .height(32.dp)
                            .onSizeChanged { contentHeight = it.height },
                    )
                }
            }
        }
        composeRule.waitForIdle()

        assertEquals(contentHeight, layoutHeight)
    }
}
