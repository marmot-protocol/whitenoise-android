package dev.ipf.whitenoise.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ShapedTouchScannerRegressionTest {
    /** Replacing an audited click with a different control in that file invalidates the exception. */
    @Test
    fun auditedRoundChildTracksTheControl() {
        val source =
            """
            fun Tile() {
                Box(Modifier.testTag(VIDEO_TAG).clickable(onClick = open)) { Avatar() }
            }
            """.trimIndent()
        val replacement = source.replace("VIDEO_TAG", "AVATAR_TAG")
        assertEquals(shapedTouchFindings(source), shapedTouchFindings(replacement))
        assertEquals("Tile: testTag(VIDEO_TAG): clickable(onClick=open)", roundChildClickSite(source, 2))
        assertEquals(roundChildClickSite(source, 2), roundChildClickSite("\n$source", 3))
        assertNotEquals(roundChildClickSite(source, 2), roundChildClickSite(replacement, 2))
    }

    /** A stock indication needs a shaped clip earlier on the modifier that actually draws it. */
    @Test
    fun rawIndicationRequiresItsOwnEarlierClip() {
        val raw = "Box(Modifier.indication(press, ripple()))"
        val clipped = "Box(Modifier.then(Modifier.clip(CircleShape)).indication(press, ripple()))"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(raw)))
        assertEquals(emptyList<String>(), shapedTouchFindings(surfaceWith(clipped)))
        val optionalClip = "Modifier.then(if (show) Modifier.clip(CircleShape) else Modifier)"
        val optional = "Box($optionalClip.indication(press, ripple()))"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(optional)))
        val disabled = clipped.replace("ripple()", "null")
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(disabled)))
    }

    /** An unused local modifier cannot draw a replacement; applying the local modifier can. */
    @Test
    fun aReplacementModifierMustReachAComponent() {
        val local = "val feedback = Modifier.shapedPressFeedback(press, CircleShape)"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith("$local; Box(Modifier)")))
        assertEquals(emptyList<String>(), shapedTouchFindings(surfaceWith("$local; Box(feedback)")))
        val unused = "val value = Pair(Modifier.shapedPressFeedback(press, CircleShape), 1); Box(Modifier)"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(unused)))
        val text = """Text("label", Modifier.shapedPressFeedback(press, CircleShape))"""
        assertEquals(emptyList<String>(), shapedTouchFindings(surfaceWith(text)))
        val optional = "Box(Modifier.then(if (show) Modifier.shapedPressFeedback(press, CircleShape) else Modifier))"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(optional)))
    }

    /** A named content lambda cannot be the Surface modifier; the positional modifier is checked in place. */
    @Test
    fun shapedSurfaceScansOnlyItsModifier() {
        val contentOnly =
            "Surface(shape = CircleShape, content = { Box(Modifier.clickable(onClick = go)) {} })"
        val positional = "Card(\n    Modifier.clickable(onClick = go),\n    content = { Box() },\n)"
        assertEquals(emptyList<String>(), shapedTouchFindings(contentOnly))
        assertEquals(listOf("$SHAPED_SURFACE @2"), shapedTouchFindings(positional))
    }

    /** Private wrappers stay in their file even if an unrelated private component shares their name. */
    @Test
    fun privateWrapperNamesStayInTheirFile() {
        val privateWrapper =
            """
            private @Composable inline fun Action(onTap: () -> Unit, modifier: Modifier) {
                IconButton(onClick = onTap, modifier = modifier) {}
            }
            """.trimIndent()
        val call = "Action(onTap = go, modifier = Modifier.width(36.dp))"
        val realWrapper = "$privateWrapper\n$call"
        val unrelated = "private fun Action(onTap: () -> Unit, modifier: Modifier) {}\n$call"
        val files = mapOf("First.kt" to realWrapper, "Second.kt" to unrelated)
        val local = stretchedWrapperFindings(files)
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @4"), local.getValue("First.kt"))
        assertEquals(emptyList<String>(), local.getValue("Second.kt"))

        val internalWrapper = privateWrapper.replace("private", "internal")
        val visibleFiles = mapOf("First.kt" to internalWrapper, "Caller.kt" to call, "Second.kt" to unrelated)
        val visible = stretchedWrapperFindings(visibleFiles)
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @1"), visible.getValue("Caller.kt"))
        assertEquals(emptyList<String>(), visible.getValue("Second.kt"))
    }

    /** A width on a sibling after a semicolon is outside the wrapper's modifier initializer. */
    @Test
    fun localModifierEndsAtASemicolon() {
        val source = "val slot = Modifier.size(48.dp); Spacer(Modifier.width(36.dp)); Action(slot)"
        assertEquals(emptyList<String>(), stretchedWrapperCalls(source, mapOf("Action" to setOf(0))))
    }

    /** Traversal skips unrelated callback bodies and follows nested then chains in order. */
    @Test
    fun onlyAppliedModifierLinksCanClipTheHighlight() {
        val callback = "Modifier.onGloballyPositioned { inspect(Modifier.clip(CircleShape)) }"
        val applied = "Modifier.then(Modifier.clip(CircleShape))"
        val suffix = ".clickable(onClick = go)) { Avatar() }"
        assertEquals(listOf("$ROUND_CHILD @1"), shapedTouchFindings("Box($callback$suffix"))
        assertEquals(emptyList<String>(), shapedTouchFindings("Box($applied$suffix"))
        val optional = "Modifier.then(if (show) Modifier.clip(CircleShape) else Modifier)"
        assertEquals(listOf("$ROUND_CHILD @1"), shapedTouchFindings("Box($optional$suffix"))
    }

    /** Explicit type arguments keep their commas, while comparisons still separate positional arguments. */
    @Test
    fun genericCallsDoNotShiftPositionalModifiers() {
        val source = "IconButton(resolveAction<Pair<Int, String>>(), Modifier.width(36.dp).height(48.dp)) {}"
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @1"), shapedTouchFindings(source))
        val comparisons = topLevelArguments("left < right, top > bottom").map(String::trim)
        assertEquals(listOf("left < right", "top > bottom"), comparisons)
        val cast = topLevelArguments("action as Handler<Int, String>, modifier").map(String::trim)
        assertEquals(listOf("action as Handler<Int, String>", "modifier"), cast)
    }

    /** Nested comments are ignored through the outer close and preserve the live finding's line. */
    @Test
    fun nestedBlockCommentsDoNotExposeFakeCalls() {
        val source =
            """
            /* outer
                /* inner */
                IconButton(go, Modifier.width(36.dp)) {}
            */
            IconButton(go, Modifier.width(36.dp)) {}
            """.trimIndent()
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @5"), shapedTouchFindings(source))
    }

    /** The helper always suppresses the default ripple and needs applied feedback for the same source. */
    @Test
    fun shapedTouchTargetRequiresMatchingFeedback() {
        val target = "Box(Modifier.shapedTouchTarget(press, onClick = go))"
        val different = "Box(Modifier.shapedPressFeedback(other, CircleShape))"
        val unused = "val feedback = Modifier.shapedPressFeedback(press, CircleShape)"
        val matching = different.replace("other", "press")
        for (content in listOf("", different, unused)) {
            assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings("$target { $content }"))
        }
        assertEquals(emptyList<String>(), shapedTouchFindings("$target { $matching }"))
        assertEquals(emptyList<String>(), shapedTouchFindings("val unused = Modifier.shapedTouchTarget(press, go)"))
    }

    /** A target supplied through a conditional modifier chain still needs feedback when the target is present. */
    @Test
    fun aConditionalTouchTargetRequiresFeedback() {
        val conditional =
            "Box(Modifier.then(if (canCancel) { " +
                "Modifier.shapedTouchTarget(press, go) } else { Modifier }))"
        val feedback = "Box(Modifier.shapedPressFeedback(press, CircleShape))"
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings(conditional))
        assertEquals(emptyList<String>(), shapedTouchFindings("$conditional { $feedback }"))
    }

    /** A private layout can draw feedback for the same source passed to it with its touch modifier. */
    @Test
    fun aWrappedTouchTargetRequiresFeedback() {
        val body =
            """
            TouchLayout(press = press, modifier = Modifier.shapedTouchTarget(press, go))
            private fun TouchLayout(press: MutableInteractionSource, modifier: Modifier) {
                Box(modifier) {
                    Box(Modifier.shapedPressFeedback(press, CircleShape))
                }
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), shapedTouchFindings(body))
        val detached = body.replace(".shapedPressFeedback(press, CircleShape)", "")
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings(detached))
    }

    /** Builds a shaped control whose click needs replacement feedback in the supplied content. */
    private fun surfaceWith(content: String): String =
        "Surface(shape = CircleShape, modifier = Modifier.clickable(" +
            "interactionSource = press, indication = null, onClick = go)) {$content}"
}
