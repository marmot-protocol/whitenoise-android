package dev.ipf.whitenoise.android.ui.navigation

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.common.WindowFold
import dev.ipf.whitenoise.android.ui.common.WindowFolds
import dev.ipf.whitenoise.android.ui.common.WindowLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the window is shared between the chat list and the conversation (#3685). Pixels are dp at density 1. */
class ChatPaneSplitTest {
    private val density = Density(1f)

    /** When both minimum panes fit, the list stays beside the chat; a half-open fold splits narrower windows too. */
    @Test
    fun listBesideConversationFollowsRoomAndHinges() {
        fun layout(width: Int) = WindowLayout.fromSize(DpSize(width.dp, 800.dp))
        val halfOpen = WindowFolds(upright = listOf(WindowFold(320.dp, 320.dp, separating = true)))
        val flat = WindowFolds(upright = listOf(WindowFold(320.dp, 320.dp, separating = false)))

        assertFalse(listBesideConversation(layout(412), WindowFolds.None))
        assertFalse(listBesideConversation(layout(640), flat))
        assertTrue(listBesideConversation(layout(640), halfOpen))
        assertTrue(listBesideConversation(layout(661), WindowFolds.None))
    }

    /** Regular-height windows give the list 40%, between 300dp and 360dp, and keep the chat at least 360dp. */
    @Test
    fun regularWindowsShareTheWidth() {
        assertEquals(ChatPaneSplit(300, 301), split(661))
        assertEquals(ChatPaneSplit(300, 301), split(673))
        assertEquals(ChatPaneSplit(336, 337), split(841))
        assertEquals(ChatPaneSplit(360, 361), split(1280))
    }

    /** Short windows such as a phone held horizontally split evenly, keeping the chat at least 360dp. */
    @Test
    fun shortWindowsBalanceThePanes() {
        assertEquals(ChatPaneSplit(339, 340), split(700, short = true))
        assertEquals(ChatPaneSplit(419, 420), split(840, short = true))
        assertEquals(ChatPaneSplit(480, 481), split(1280, short = true))
    }

    /** A half-open hinge sets the split even where the proportional list would be narrower. */
    @Test
    fun halfOpenHingeSetsTheSplit() {
        assertEquals(ChatPaneSplit(400, 401), split(800, fold(400, 400, separating = true)))
        assertEquals(ChatPaneSplit(390, 410), split(800, fold(390, 410, separating = true)))
    }

    /** Right to left, the list fills the right side of the fold. */
    @Test
    fun rightToLeftMeasuresFromTheRightEdge() {
        val rtl = split(800, fold(340, 340, separating = true), direction = LayoutDirection.Rtl)
        assertEquals(ChatPaneSplit(460, 461), rtl)
    }

    /** A flat crease lines up the divider only while it leaves both panes a good width. */
    @Test
    fun flatCreaseAlignsWhenItFits() {
        assertEquals(ChatPaneSplit(420, 421), split(841, fold(420, 420, separating = false)))
        // The crease would leave the chat 345dp, under its 360dp minimum, so the proportional split wins.
        assertEquals(ChatPaneSplit(300, 301), split(690, fold(345, 345, separating = false)))
        // Counting the divider, a crease at 301dp in a 661dp window would leave the chat 359dp.
        assertEquals(ChatPaneSplit(300, 301), split(661, fold(301, 301, separating = false)))
    }

    /** Of a tri-fold's two flat creases, the divider follows the one that leaves the narrowest good list. */
    @Test
    fun triFoldCreasesPreferTheNarrowerList() {
        val creases = listOf(WindowFold(325.dp, 325.dp, false), WindowFold(650.dp, 650.dp, false))

        assertEquals(ChatPaneSplit(325, 326), split(975, *creases.toTypedArray()))
        assertEquals(ChatPaneSplit(325, 326), split(975, *creases.toTypedArray(), direction = LayoutDirection.Rtl))
    }

    private fun fold(
        start: Int,
        end: Int,
        separating: Boolean,
    ) = WindowFold(start.dp, end.dp, separating)

    private fun split(
        width: Int,
        vararg folds: WindowFold,
        direction: LayoutDirection = LayoutDirection.Ltr,
        short: Boolean = false,
    ): ChatPaneSplit =
        density.chatPaneSplit(
            width = width,
            dividerWidth = 1,
            shortWindow = short,
            folds = folds.toList(),
            layoutDirection = direction,
        )
}
