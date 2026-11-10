package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatDetailsMorphGeometryTest {
    private val composer = Rect(16f, 700f, 344f, 748f)
    private val page = Rect(0f, 0f, 360f, 780f)

    @Test
    fun cardStartsAndEndsOnItsEndpoints() {
        assertEquals(composer, chatDetailsCardRect(composer, page, 0f))
        assertEquals(page, chatDetailsCardRect(composer, page, 1f))
        assertEquals(page, chatDetailsCardRect(composer, page, 2f))
        val middle = chatDetailsCardRect(composer, page, 0.5f)
        assertTrue(middle.top in page.top..composer.top && middle.width in composer.width..page.width)
    }

    @Test
    fun openingContentFadesInAfterThePillMoves() {
        assertEquals(0f, chatDetailsContentAlpha(opening = true, 0.1f))
        assertEquals(1f, chatDetailsContentAlpha(opening = true, 0.6f))
        assertEquals(1f, chatDetailsCardAlpha(opening = true, 0f))
        assertEquals(1f, chatDetailsCardAlpha(opening = true, 1f))
    }

    @Test
    fun closingContentLeavesBeforeTheCardHandsOverToTheComposer() {
        assertEquals(1f, chatDetailsContentAlpha(opening = false, 0.5f))
        assertEquals(0f, chatDetailsContentAlpha(opening = false, 0.95f))
        assertEquals(1f, chatDetailsCardAlpha(opening = false, 0.85f))
        assertEquals(0f, chatDetailsCardAlpha(opening = false, 1f))
    }
}
