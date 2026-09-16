package dev.ipf.whitenoise.android.ui.conversation.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val EDITOR_WIDTH_PX = 330f
private const val THUMB_WIDTH_PX = 3f
private const val EDGE_INSET_PX = 2f
private const val OUTER_GUTTER_PX = 14f

/**
 * The expanded editor fills its row, so its overflow thumb is painted in the inset beside the editor.
 * Painting it inside the editor put it over a long line's last glyphs, caret and selection handles.
 */
class ComposerScrollbarLayoutTest {
    /** With an inset to paint in, the thumb clears the editor's trailing edge entirely. */
    @Test
    fun thumbIsPaintedBeyondTheEditorsTrailingEdge() {
        val x = thumbX(outerGutterPx = OUTER_GUTTER_PX, rightToLeft = false)
        assertTrue("the thumb must start at or past the editor's edge", x >= EDITOR_WIDTH_PX)
        assertTrue("the thumb must stay inside the inset", x + THUMB_WIDTH_PX <= EDITOR_WIDTH_PX + OUTER_GUTTER_PX)
    }

    /** Mirrored, the inset is on the leading side, so the thumb sits entirely left of the editor. */
    @Test
    fun rightToLeftThumbIsPaintedBeforeTheEditorsLeadingEdge() {
        val x = thumbX(outerGutterPx = OUTER_GUTTER_PX, rightToLeft = true)
        assertTrue("the thumb must end at or before the editor's edge", x + THUMB_WIDTH_PX <= 0f)
        assertTrue("the thumb must stay inside the inset", x >= -OUTER_GUTTER_PX)
    }

    /** The thumb is centred in the inset rather than crowding either side of it. */
    @Test
    fun thumbIsCentredInTheInset() {
        val expected = EDITOR_WIDTH_PX + (OUTER_GUTTER_PX - THUMB_WIDTH_PX) / 2f
        assertEquals(expected, thumbX(outerGutterPx = OUTER_GUTTER_PX, rightToLeft = false), 0.01f)
    }

    /** Without an inset — the compact composer — the thumb keeps its trailing position inside the editor. */
    @Test
    fun withoutAnInsetTheThumbKeepsItsTrailingPosition() {
        assertEquals(
            EDITOR_WIDTH_PX - THUMB_WIDTH_PX - EDGE_INSET_PX,
            thumbX(outerGutterPx = 0f, rightToLeft = false),
            0.01f,
        )
        assertEquals(EDGE_INSET_PX, thumbX(outerGutterPx = 0f, rightToLeft = true), 0.01f)
    }

    private fun thumbX(
        outerGutterPx: Float,
        rightToLeft: Boolean,
    ) = composerOverflowThumbXPx(
        editorWidthPx = EDITOR_WIDTH_PX,
        thumbWidthPx = THUMB_WIDTH_PX,
        edgeInsetPx = EDGE_INSET_PX,
        outerGutterPx = outerGutterPx,
        rightToLeft = rightToLeft,
    )
}
