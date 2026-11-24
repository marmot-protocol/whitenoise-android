package dev.ipf.whitenoise.android.ui.common

import dev.ipf.whitenoise.android.appModuleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Press feedback follows the visible shape (#4102, #4065). A round or rounded control whose highlight is
 * drawn on the rectangle around it looks broken on every tap, and resting screenshots never show it, so
 * this gate scans the UI sources for the four patterns that produce it:
 *
 * 1. a click, toggle or select modifier passed to a shaped `Surface` through `modifier`, where the Surface
 *    clips only after it — use `Surface(onClick = …)` or clip first;
 * 2. a click modifier followed by a round clip in the same chain, so the highlight is drawn before the clip;
 * 3. a clickable `Box` whose content draws the round or rounded shape the user sees, without a shaped
 *    highlight on that shape — use [shapedTouchTarget] and [shapedPressFeedback];
 * 4. a clickable passed to a round component (an avatar or swatch) that clips itself inside;
 * 5. an `IconButton` forced into a box that may not be square, whose circle clip stretches into a capsule
 *    — give it [CenteredCircleShape].
 *
 * A click that draws no highlight of its own (`indication = null`) or comes after a `.clip(` in its chain
 * passes. Audited exceptions live in [allowed] with the reason; the counts are exact, so a fixed site has to
 * leave the list.
 */
class ShapedTouchFeedbackCoverageTest {
    /** Every UI source passes the five shape rules apart from the audited exceptions. */
    @Test
    fun pressFeedbackFollowsTheVisibleShape() {
        val root = appModuleFile(UI_ROOT)
        val found =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> shapedTouchFindings(file.readText()).map { "${file.relativeTo(root).path}: $it" } }
                .toList()
        val counted = found.groupingBy { it.substringBefore(" @") }.eachCount()
        assertEquals(
            "Press feedback must follow the visible shape. Use Surface(onClick = …), clip before the click, " +
                "or shapedTouchTarget with shapedPressFeedback (ui/common/ShapedTouch.kt). Findings:\n" +
                found.joinToString("\n"),
            allowed,
            counted,
        )
    }

    /** The scanner finds each pattern the gate exists for, so a parsing regression cannot pass silently. */
    @Test
    fun scannerFindsEachPattern() {
        val shapedSurface =
            """
            Surface(
                shape = CircleShape,
                modifier = Modifier.size(48.dp).clickable { go() },
            ) { Icon() }
            """.trimIndent()
        val clickBeforeClip = "Box(Modifier.clickable { go() }.clip(CircleShape))"
        val roundChild =
            """
            Box(Modifier.size(48.dp).clickable(onClick = go)) {
                Surface(shape = CircleShape, modifier = Modifier.size(34.dp)) {}
            }
            """.trimIndent()
        val roundComponent = "ColorSwatch(argb, modifier = Modifier.clickable { pick() })"
        val stretchedIconButton = "IconButton(onClick = go, modifier = Modifier.width(40.dp).height(48.dp)) {}"
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(shapedSurface).map(::kind))
        assertEquals(listOf(CLICK_BEFORE_CLIP), shapedTouchFindings(clickBeforeClip).map(::kind))
        assertEquals(listOf(ROUND_CHILD), shapedTouchFindings(roundChild).map(::kind))
        assertEquals(listOf(ROUND_COMPONENT), shapedTouchFindings(roundComponent).map(::kind))
        assertEquals(listOf(STRETCHED_ICON_BUTTON), shapedTouchFindings(stretchedIconButton).map(::kind))
    }

    /** The shared helpers and the fixes they stand for pass, so the gate does not push callers off them. */
    @Test
    fun scannerAcceptsTheShapedPatterns() {
        val fixed =
            """
            Surface(onClick = go, shape = CircleShape) {}
            Surface(shape = CircleShape, modifier = Modifier.clip(CircleShape).clickable { go() }) {}
            Box(Modifier.shapedTouchTarget(press, onClick = go)) {
                Surface(shape = CircleShape) { Box(Modifier.shapedPressFeedback(press, CircleShape)) }
            }
            Box(Modifier.clickable(interactionSource = press, indication = null, onClick = go)) {
                Box(Modifier.clip(CircleShape))
            }
            Box(Modifier.clip(CircleShape).clickable { go() }) { Avatar(title) }
            IconButton(onClick = go, shape = CenteredCircleShape, modifier = Modifier.width(40.dp).height(48.dp)) {}
            // Box(Modifier.clickable { go() }.clip(CircleShape))
            Text("Box(Modifier.clickable { go() }.clip(CircleShape))")
            """.trimIndent()
        assertTrue(shapedTouchFindings(fixed).joinToString(), shapedTouchFindings(fixed).isEmpty())
    }

    /** The finding kind, without its line suffix. */
    private fun kind(finding: String): String = finding.substringBefore(" @")

    private companion object {
        const val UI_ROOT = "src/main/java/dev/ipf/whitenoise/android/ui"

        /**
         * Audited exceptions, keyed by `path: kind`, with the number of sites allowed. A video grid tile is
         * the rectangle the user sees, so its rectangular highlight matches it; the play disc inside is a
         * glyph, not a control.
         */
        val allowed: Map<String, Int> =
            mapOf(
                "conversation/media/MediaVideoGridTile.kt: $ROUND_CHILD" to 1,
            )
    }
}
