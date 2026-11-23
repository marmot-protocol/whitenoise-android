package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** The emoji sheet opened from a text field takes the height below the field and never covers it (#3601). */
class EmojiSheetFieldAnchorTest {
    private val gap = 8
    private val handle = 48

    /** The sheet is the drag handle, the picker and the bottom padding, anchored to the window's bottom. */
    private fun sheetTop(
        windowHeight: Int,
        fieldBottom: Float,
        bottomPadding: Int,
    ): Int {
        val pickerMaxHeight = windowHeight - handle - bottomPadding
        val picker = emojiSheetPickerHeightBelowField(pickerMaxHeight, fieldBottom, gap)
        return windowHeight - bottomPadding - picker - handle
    }

    @Test
    fun sheetTopSitsJustBelowTheFieldAcrossWindowsAndInsets() {
        val cases =
            listOf(
                Triple(780, 180f, 0),
                Triple(780, 180f, 48),
                Triple(2400, 640f, 126),
                Triple(1200, 300.4f, 0),
                Triple(640, 220f, 24),
            )
        cases.forEach { (window, field, padding) ->
            assertEquals("window $window field $field", ceil(field).toInt() + gap, sheetTop(window, field, padding))
        }
    }

    @Test
    fun keyboardInTheSheetShrinksThePickerButKeepsItsTopBelowTheField() {
        val withoutKeyboard = emojiSheetPickerHeightBelowField(780 - handle, 200f, gap)
        val withKeyboard = emojiSheetPickerHeightBelowField(780 - handle - 300, 200f, gap)
        assertEquals(withoutKeyboard - 300, withKeyboard)
        assertEquals(208, sheetTop(780, 200f, 300))
    }

    @Test
    fun aFieldNearTheTopGetsMoreThanTheUnanchoredShare() {
        val pickerMaxHeight = 780 - handle
        val picker = emojiSheetPickerHeightBelowField(pickerMaxHeight, 60f, gap)
        assertEquals(pickerMaxHeight - 68, picker)
        assertTrue(picker > pickerMaxHeight * 0.88f)
    }

    @Test
    fun noRoomBelowTheFieldGivesAnEmptyPickerRatherThanAnOverlap() {
        assertEquals(0, emojiSheetPickerHeightBelowField(732, 760f, gap))
        assertEquals(0, emojiSheetPickerHeightBelowField(732, 732f, gap))
        assertEquals(1, emojiSheetPickerHeightBelowField(732, 723f, gap))
    }

    @Test
    fun aFieldAboveTheSheetWindowIsClampedToTheOfferedHeight() {
        assertEquals(732, emojiSheetPickerHeightBelowField(732, -100f, gap))
    }

    @Test
    fun roomBelowCoversGapHandleMinimumPickerAndNavigationBar() {
        assertEquals(376f, emojiSheetRoomBelowField(navigationBarBottom = 0, density = Density(1f)), 0.01f)
        assertEquals(1166f, emojiSheetRoomBelowField(navigationBarBottom = 132, density = Density(2.75f)), 0.01f)
    }
}
