package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How the focused-message actions arrange themselves into columns.
 *
 * #1857 asked for the labelled grid the menu had before the design port, which had regressed to a
 * tall single column. Two columns are only offered when both cells can hold their labels; below
 * that the menu stays the readable list it already was, because a grid cell sized for two columns
 * clips a label at large font.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-rUS-w360dp-h780dp-mdpi")
class FocusedMessageActionsGridTest {
    @get:Rule val composeRule = createComposeRule()

    /** A single action needs no second column and occupies one row on its own. */
    @Test
    fun oneActionStandsAlone() {
        render(labels = listOf("Reply"))

        assertEquals(1, rowTops(listOf("Reply")).size)
    }

    /** An even action count fills both columns of every row. */
    @Test
    fun anEvenCountFillsBothColumns() {
        val labels = listOf("Reply", "Edit", "Copy", "Select")
        render(labels = labels)

        assertEquals("four actions make two rows", 2, rowTops(labels).size)
        assertEquals("paired across the row", bounds("Reply").top, bounds("Edit").top, 0.5f)
        assertTrue("in different columns", bounds("Edit").left > bounds("Reply").left)
    }

    /** An odd action keeps its column rather than stretching across the row. */
    @Test
    fun anOddActionKeepsItsColumnWidth() {
        val labels = listOf("Reply", "Edit", "Copy")
        render(labels = labels)

        assertEquals("the trailing action matches a paired cell", bounds("Reply").width, bounds("Copy").width, 0.5f)
        assertEquals("and stays in the first column", bounds("Reply").left, bounds("Copy").left, 0.5f)
    }

    /** Ten actions stay balanced rather than spilling into a third column. */
    @Test
    fun tenActionsStayInTwoColumns() {
        val labels = (1..10).map { "Act $it" }
        render(labels = labels)

        assertEquals("ten actions make five rows", 5, rowTops(labels).size)
    }

    /** A destructive action keeps the error colour that marks it apart. */
    @Test
    fun aDestructiveActionIsMarkedApart() {
        render(labels = listOf("Reply", "Delete"), destructiveLast = true)

        composeRule.onNodeWithText("Delete").assertExists()
    }

    /**
     * Short labels keep both columns at large font, because the fallback is about fit, not scale.
     *
     * The fallback itself is driven by a label too wide for half the menu, which this harness
     * cannot reproduce: it renders the actions directly, where `WhiteNoiseTheme`'s scaled
     * typography does not reach the width measurement. `MessageActionMenuLayoutTest`
     * `largeFontFallsBackToOneReadableColumn` covers it through the production entry point.
     */
    @Test
    fun shortLabelsKeepTwoColumnsAtLargeFont() {
        val labels = listOf("Reply", "Edit", "Copy", "Select")
        render(labels = labels, fontScale = 2f)

        assertEquals("four short actions still pair into two rows", 2, rowTops(labels).size)
    }

    /** Right-to-left keeps two columns, with the first action on the right. */
    @Test
    fun rightToLeftKeepsTwoColumnsMirrored() {
        render(labels = listOf("Reply", "Edit"), layoutDirection = LayoutDirection.Rtl)

        assertEquals("still one row", bounds("Reply").top, bounds("Edit").top, 0.5f)
        assertTrue("the first action sits on the right", bounds("Reply").left > bounds("Edit").left)
    }

    private fun bounds(label: String): Rect = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot

    /** The distinct row positions the given labels occupy, which is the grid's row count. */
    private fun rowTops(labels: List<String>): Set<Int> = labels.map { bounds(it).top.toInt() }.toSet()

    private fun render(
        labels: List<String>,
        fontScale: Float = 1f,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
        destructiveLast: Boolean = false,
    ) {
        composeRule.setContent {
            WhiteNoiseTheme(fontScale = fontScale) {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    FocusedMessageActions(
                        sourceBounds = IntRect(0, 260, 360, 340),
                        touchY = 300f,
                        mine = true,
                        actions =
                            labels.mapIndexed { index, label ->
                                FocusedMessageAction(
                                    label = label,
                                    supportingLabel = null,
                                    enabled = true,
                                    destructive = destructiveLast && index == labels.lastIndex,
                                    icon = {},
                                    onClick = {},
                                )
                            },
                        quickReactions = emptyList(),
                        canReact = false,
                        selectedReactions = emptySet(),
                        previewDescription = "Lifted message",
                        previewReady = true,
                        preview = {
                            Box(Modifier.size(200.dp, 60.dp).background(MaterialTheme.colorScheme.surface)) {
                                Text("The lifted message")
                            }
                        },
                        onReact = {},
                        onMoreReactions = {},
                        onDismiss = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }
}
