package dev.ipf.whitenoise.android.ui.common

import dev.ipf.whitenoise.android.appModuleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Press feedback follows the visible shape (#4102, #4065). A round or rounded control whose highlight is
 * drawn on the rectangle around it looks broken on every tap, and resting screenshots never show it, so
 * this gate scans the UI sources for the patterns that produce it:
 *
 * 1. a click, toggle or select modifier passed to a shaped `Surface` or a card through `modifier`, which
 *    clips only after it — use `Surface(onClick = …)` or clip first,
 * 2. a click modifier followed by a round clip in the same chain, directly or inside `.then(…)`, so the
 *    highlight is drawn before the clip,
 * 3. a clickable `Box` whose content draws the round or rounded shape the user sees, without a shaped
 *    highlight on that shape — use [shapedTouchTarget] and [shapedPressFeedback],
 * 4. a clickable passed to a round component (an avatar or swatch) that clips itself inside,
 * 5. a transparent `IconButton` or `IconToggleButton` sized to a box that may not be square, or taking a
 *    composer slot's size from its caller, whose one shape clips both the highlight and the touch area —
 *    use [SlotIconButton]. Filled icon buttons are left out: their visible fill is the shape the highlight
 *    follows,
 * 6. a click modifier after a round `background` or `border` in the same chain, with no clip,
 * 7. a click modifier on a bare `Icon`, whose highlight is a square around the glyph.
 *
 * A click that draws no highlight of its own (`indication = null`) or comes after a clip that is not a
 * rectangle passes. Audited exceptions live in [allowed] with the reason, and the counts are exact, so a
 * fixed site has to leave the list.
 */
class ShapedTouchFeedbackCoverageTest {
    /** Every UI source passes the shape rules apart from the audited exceptions. */
    @Test
    fun pressFeedbackFollowsTheVisibleShape() {
        val root = appModuleFile(UI_ROOT)
        val found =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    val path = file.relativeTo(root).invariantSeparatorsPath
                    shapedTouchFindings(file.readText(), path).map { "$path: $it" }
                }.toList()
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

    /** The patterns found in review: card modifiers, a clip after the click in `then`, fills and bare glyphs. */
    @Test
    fun scannerFindsTheReviewedHoles() {
        val cases =
            mapOf(
                "Card(modifier = Modifier.clickable { go() }) {}" to SHAPED_SURFACE,
                "Box(Modifier.size(40.dp).clickable { go() }.then(Modifier.clip(CircleShape)))" to CLICK_BEFORE_CLIP,
                "Box(Modifier.size(40.dp).background(c, CircleShape).clickable { go() })" to CLICK_OVER_SHAPED_FILL,
                "IconButton(onClick = go, modifier = Modifier.requiredSize(36.dp, 48.dp)) {}" to STRETCHED_ICON_BUTTON,
                "Icon(p, null, Modifier.size(28.dp).clickable { retry() })" to BARE_GLYPH,
            )
        cases.forEach { (source, kind) -> assertEquals(source, listOf(kind), shapedTouchFindings(source).map(::kind)) }
        val slotSized = "IconButton(onClick = go, modifier = modifier) {}"
        assertEquals(
            listOf(STRETCHED_ICON_BUTTON),
            shapedTouchFindings(slotSized, "conversation/composer/Toolbar.kt").map(::kind),
        )
        assertEquals(emptyList<String>(), shapedTouchFindings(slotSized, "settings/Row.kt"))
    }

    /** A rectangular clip before the click does not count as shaping it, so the square highlight is still found. */
    @Test
    fun rectangularClipDoesNotExcuseAShapedControl() {
        val shapedSurface =
            """
            Surface(
                shape = CircleShape,
                modifier = Modifier.clip(RectangleShape).clickable { go() },
            ) {}
            """.trimIndent()
        val roundChild =
            """
            Box(Modifier.clip(RectangleShape).clickable(onClick = go)) {
                Avatar(title)
            }
            """.trimIndent()
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(shapedSurface).map(::kind))
        assertEquals(listOf(ROUND_CHILD), shapedTouchFindings(roundChild).map(::kind))
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
            SlotIconButton(onClick = go, modifier = Modifier.width(40.dp).height(48.dp)) {}
            Icon(p, null, Modifier.size(48.dp).clip(CircleShape).clickable { retry() })
            Box(Modifier.background(c, CircleShape).clip(CircleShape).clickable { go() })
            Card(onClick = go) {}
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
         * Audited exceptions, keyed by `path: kind`, with the number of sites allowed. Each control is the
         * rectangle the user sees, so a rectangular highlight matches it, and the round shape inside it is a
         * glyph, not a control: the video grid tile's play disc and the profile banner's camera badge.
         */
        val allowed: Map<String, Int> =
            mapOf(
                "conversation/media/MediaVideoGridTile.kt: $ROUND_CHILD" to 1,
                "profile/ProfileEditScreen.kt: $ROUND_CHILD" to 1,
            )
    }
}
