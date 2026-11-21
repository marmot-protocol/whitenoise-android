package dev.ipf.whitenoise.android.ui.common

import android.view.View
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Space between a text field and the top edge of the emoji sheet it opens. */
internal val EmojiSheetFieldGap = 8.dp

/** Picker height the opener scrolls room for below the field; a short window may still give less. */
internal val EmojiSheetMinimumPickerHeight = 320.dp

/** Material's sheet drag handle: a 4 dp pill with 22 dp above and below it, drawn above the picker. */
internal val EmojiSheetDragHandleHeight = 48.dp

/**
 * Where a text field sits, so the emoji sheet it opens stays underneath it instead of covering it.
 * Layout-only state for one field; never saved.
 */
@Stable
internal class EmojiSheetFieldAnchor {
    internal val bringIntoViewRequester = BringIntoViewRequester()

    private var fieldCoordinates: LayoutCoordinates? = null

    /** Resolves screen position only when the picker actually needs it. */
    internal val fieldBottomOnScreen: Float?
        get() =
            fieldCoordinates?.takeIf { it.isAttached }?.let { coordinates ->
                (coordinates.positionOnScreen().y + coordinates.size.height).takeIf(Float::isFinite)
            }

    internal fun place(coordinates: LayoutCoordinates) {
        fieldCoordinates = coordinates
    }

    /** Scrolls the field's scroll containers until [roomBelow] pixels below the field are on screen too. */
    internal suspend fun makeRoomBelow(roomBelow: Float) {
        val size = fieldCoordinates?.takeIf { it.isAttached }?.size ?: return
        bringIntoViewRequester.bringIntoView(Rect(0f, 0f, size.width.toFloat(), size.height + roomBelow))
    }
}

@Composable
internal fun rememberEmojiSheetFieldAnchor(): EmojiSheetFieldAnchor = remember { EmojiSheetFieldAnchor() }

/** Records this field's position for [anchor] and lets the opener scroll it up. */
internal fun Modifier.emojiSheetFieldAnchor(anchor: EmojiSheetFieldAnchor): Modifier =
    bringIntoViewRequester(anchor.bringIntoViewRequester).onGloballyPositioned(anchor::place)

/**
 * Opens an emoji sheet below [anchor]'s field. The keyboard hides and the field scrolls up until a
 * [EmojiSheetMinimumPickerHeight] picker fits below it, then `open` runs. Taps while one opening is still
 * scrolling are ignored, and nothing opens after the field has left composition. Without an anchor `open`
 * runs straight away.
 */
@Composable
internal fun rememberEmojiSheetOpener(anchor: EmojiSheetFieldAnchor?): (open: () -> Unit) -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val navigationBars = WindowInsets.navigationBars
    var opening by remember { mutableStateOf<Job?>(null) }
    return { open ->
        if (anchor == null) {
            open()
        } else if (opening?.isActive != true) {
            keyboard?.hide()
            opening =
                scope.launch {
                    anchor.makeRoomBelow(emojiSheetRoomBelowField(navigationBars.getBottom(density), density))
                    open()
                }
        }
    }
}

/** Pixels the field needs below it for the gap, drag handle, minimum picker and navigation bar. */
internal fun emojiSheetRoomBelowField(
    navigationBarBottom: Int,
    density: Density,
): Float =
    with(density) {
        (EmojiSheetFieldGap + EmojiSheetDragHandleHeight + EmojiSheetMinimumPickerHeight).toPx() + navigationBarBottom
    }

/**
 * Picker height that puts the sheet's top edge [gap] pixels below the field, so the sheet never covers it.
 *
 * [pickerMaxHeight] is what the sheet offers the picker: the window minus the drag handle and the system bar and
 * keyboard padding below it. The sheet is that chrome plus the picker, anchored to the window's bottom, so taking
 * the field's bottom and the gap off [pickerMaxHeight] leaves the sheet's top exactly at the gap below the field.
 * The result is never negative: a field with no room left below it gets an empty picker, not an overlap.
 */
internal fun emojiSheetPickerHeightBelowField(
    pickerMaxHeight: Int,
    fieldBottomInWindow: Float,
    gap: Int,
): Int = (pickerMaxHeight - ceil(fieldBottomInWindow).toInt() - gap).coerceIn(0, pickerMaxHeight)

/**
 * Sizes the sheet's picker with [emojiSheetPickerHeightBelowField]. [sheetView] is the sheet window's view, so a
 * field in another window is placed by its screen position. Before the field is placed, [fallbackFraction] of the
 * offered height is used.
 */
internal fun Modifier.emojiSheetHeightBelowField(
    anchor: EmojiSheetFieldAnchor,
    sheetView: View,
    gap: Int,
    fallbackFraction: Float,
): Modifier =
    layout { measurable, constraints ->
        val fieldBottom = anchor.fieldBottomOnScreen
        val height =
            if (fieldBottom == null) {
                (constraints.maxHeight * fallbackFraction).roundToInt()
            } else {
                val windowTop = IntArray(2).also(sheetView::getLocationOnScreen)[1]
                emojiSheetPickerHeightBelowField(constraints.maxHeight, fieldBottom - windowTop, gap)
            }
        val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
