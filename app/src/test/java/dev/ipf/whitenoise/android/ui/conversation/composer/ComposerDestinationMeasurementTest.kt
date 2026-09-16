package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val EDITING_WIDTH_PX = 600
private const val COMPACT_WIDTH_PX = 300

/**
 * A bulk insert must be measured at the width the composer settles at. Measuring at the width it is
 * leaving produced a taller first target, which the wider editing inset then contradicted mid-animation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerDestinationMeasurementTest {
    @OptIn(ExperimentalTextApi::class)
    private val measurer =
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext()),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr,
        )
    private val style = TextStyle(fontSize = 16.sp)

    /** A draft that will cross into the editing layout is measured at the editing width straight away. */
    @Test
    fun draftCrossingIntoEditingIsMeasuredAtTheEditingWidth() {
        val text = AnnotatedString((1..8).joinToString(" ") { "word$it wraps here" })
        val destination = layoutFor(text, startsEditing = false)
        val atEditingWidth = measure(text, EDITING_WIDTH_PX)

        assertTrue("the draft must reach the editing layout for this case", atEditingWidth.lineCount >= 1)
        assertEquals(atEditingWidth.lineCount, destination.lineCount)
        assertEquals(atEditingWidth.size.height, destination.size.height)
    }

    /** A draft already in the editing layout skips the compact pass entirely. */
    @Test
    fun draftAlreadyEditingIsMeasuredAtTheEditingWidth() {
        val text = AnnotatedString("short draft")
        assertEquals(
            measure(text, EDITING_WIDTH_PX).size.height,
            layoutFor(text, startsEditing = true).size.height,
        )
    }

    /** A one-line draft that stays compact keeps the compact measurement. */
    @Test
    fun shortCompactDraftKeepsTheCompactWidth() {
        val text = AnnotatedString("hi")
        val destination = layoutFor(text, startsEditing = false)
        assertEquals(measure(text, COMPACT_WIDTH_PX).size.height, destination.size.height)
        assertEquals(1, destination.lineCount)
    }

    /** With the multiline controls suppressed the composer never crosses over, so it stays compact. */
    @Test
    fun suppressedMultilineControlsKeepTheCompactWidth() {
        val text = AnnotatedString((1..8).joinToString(" ") { "word$it wraps here" })
        val destination = layoutFor(text, startsEditing = false, multilineControlsSuppressed = true)
        assertEquals(measure(text, COMPACT_WIDTH_PX).size.height, destination.size.height)
    }

    private fun layoutFor(
        text: AnnotatedString,
        startsEditing: Boolean,
        multilineControlsSuppressed: Boolean = false,
    ) = composerDestinationTextLayout(
        measurer = measurer,
        text = text,
        style = style,
        editingWidthPx = EDITING_WIDTH_PX,
        compactWidthPx = COMPACT_WIDTH_PX,
        startsEditing = startsEditing,
        multilineControlsSuppressed = multilineControlsSuppressed,
    )

    private fun measure(
        text: AnnotatedString,
        widthPx: Int,
    ) = measurer.measure(
        text = text,
        style = style,
        constraints =
            androidx.compose.ui.unit
                .Constraints(maxWidth = widthPx),
    )
}
