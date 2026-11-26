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

    /**
     * A stock indication needs a shaped clip earlier on the modifier that actually draws it, unless it sits
     * directly in the shaped Surface, whose own clip then shapes it.
     */
    @Test
    fun rawIndicationRequiresAShapedClip() {
        val raw = "Row { Box(Modifier.indication(press, ripple())) }"
        val clipped = "Box(Modifier.then(Modifier.clip(CircleShape)).indication(press, ripple()))"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(raw)))
        assertEquals(emptyList<String>(), shapedTouchFindings(surfaceWith("Box(Modifier.indication(press, ripple()))")))
        assertEquals(emptyList<String>(), shapedTouchFindings(surfaceWith(clipped)))
        val optionalClip = "Modifier.then(if (show) Modifier.clip(CircleShape) else Modifier)"
        val optional = "Row { Box($optionalClip.indication(press, ripple())) }"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(optional)))
        val disabled = clipped.replace("ripple()", "null")
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(disabled)))
    }

    /** An unused local modifier cannot draw a replacement; applying the local modifier can. */
    @Test
    fun aReplacementModifierMustReachAComponent() {
        val local = "val feedback = Modifier.shapedPressFeedback(press, CircleShape)"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith("$local; Box(Modifier)")))
        // A named modifier value is never expanded, so applying it does not prove feedback either.
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith("$local; Box(feedback)")))
        val unused = "val value = Pair(Modifier.shapedPressFeedback(press, CircleShape), 1); Box(Modifier)"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(unused)))
        val text = """Text("label", Modifier.shapedPressFeedback(press, CircleShape))"""
        assertEquals(emptyList<String>(), shapedTouchFindings(surfaceWith(text)))
        val optional = "Box(Modifier.then(if (show) Modifier.shapedPressFeedback(press, CircleShape) else Modifier))"
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(optional)))
    }

    /** A shaped Surface must actually compose its replacement for the null-indication click. */
    @Test
    fun aSurfaceRejectsFeedbackInOptionalOrUncalledContent() {
        val feedback = "Box(Modifier.shapedPressFeedback(press, CircleShape))"
        for (content in listOf("if (visible) { $feedback }", "val render = @Composable { $feedback }")) {
            assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(content)))
        }
    }

    /** Both entry points can use branched layouts when each branch draws feedback for the same source. */
    @Test
    fun feedbackMustBeDrawnInBothBranches() {
        val pulse = "Modifier.shapedPressFeedback(press, CircleShape)"
        val rendered = "if (available) { Image(bitmap, null, $pulse) } else { Box($pulse) }"
        val target = "Box(Modifier.shapedTouchTarget(press, go))"
        assertEquals(emptyList<String>(), shapedTouchFindings(surfaceWith(rendered)))
        assertEquals(emptyList<String>(), shapedTouchFindings("$target { $rendered }"))
        val missing = rendered.replace("Box($pulse)", "Box(Modifier)")
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(missing)))
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings("$target { $missing }"))
        val uncalled = rendered.replace("Box($pulse)", "val render = @Composable { Box($pulse) }")
        assertEquals(listOf("$SHAPED_SURFACE @1"), shapedTouchFindings(surfaceWith(uncalled)))
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
        // No component can be shown to receive an unused target, so it is reported rather than trusted.
        val unusedTarget = "val unused = Modifier.shapedTouchTarget(press, go)"
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings(unusedTarget))
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

    /** Feedback must be composed whenever the target exists, including through its content layout. */
    @Test
    fun conditionalChildrenAndUncalledLambdasDoNotDrawFeedback() {
        val target = "Box(Modifier.shapedTouchTarget(press, go))"
        val feedback = "Box(Modifier.shapedPressFeedback(press, CircleShape))"
        val optionalContent =
            listOf(
                "if (visible) { $feedback }",
                "if (visible) $feedback",
                "if (visible) Box() { $feedback }",
                "if (visible) androidx.compose.foundation.layout.$feedback",
                "if (!visible) return@Box\n$feedback",
                "val render: @Composable () -> Unit = { $feedback }",
                "AnimatedVisibility(visible) { $feedback }",
            )
        for (content in optionalContent) {
            assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings("$target { $content }"))
        }
        assertEquals(emptyList<String>(), shapedTouchFindings("if (visible) { $target { Row() { $feedback } } }"))
        assertEquals(emptyList<String>(), shapedTouchFindings("$target { if (visible) Box(); Surface { $feedback } }"))
        assertEquals(emptyList<String>(), shapedTouchFindings("$target { Box { return@Box }; $feedback }"))
        val named = "Box(Modifier.shapedTouchTarget(press, go), content = { $feedback })"
        assertEquals(emptyList<String>(), shapedTouchFindings(named))
    }

    /** Forwarded feedback is also required to be drawn by its implementation. */
    @Test
    fun anUncalledForwardedLayoutDoesNotDrawFeedback() {
        val target = "TouchLayout(press = press, modifier = Modifier.shapedTouchTarget(press, go))"
        val feedback = "Box(Modifier.shapedPressFeedback(press, CircleShape))"
        val header = "fun TouchLayout(press: MutableInteractionSource, modifier: Modifier)"
        val expected = listOf("$MISSING_TOUCH_FEEDBACK @1")
        for (content in listOf("if (visible) { $feedback }", "val render = @Composable { $feedback }")) {
            assertEquals(expected, shapedTouchFindings("$target\n$header { Box(modifier) { $content } }"))
        }
    }

    /** A target passed in an argument the component never applies as a modifier installs no click, so it fails. */
    @Test
    fun aTargetInAnUnappliedArgumentIsReported() {
        val feedback = "Box(Modifier.shapedPressFeedback(press, CircleShape))"
        val header = "fun Tile(ignored: Modifier, press: MutableInteractionSource)"
        val call = "Tile(ignored = Modifier.shapedTouchTarget(press, go), press = press)\n"
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings("$call$header { $feedback }"))
        assertEquals(emptyList<String>(), shapedTouchFindings("$call$header { Box(ignored) { $feedback } }"))
        val extended = "$call$header { Box(ignored.padding(4.dp).testTag(TAG)) { $feedback } }"
        assertEquals(emptyList<String>(), shapedTouchFindings(extended))
        val positional = "Tile(Modifier.shapedTouchTarget(press, go), press = press)\n"
        assertEquals(emptyList<String>(), shapedTouchFindings("$positional$header { Box(ignored) { $feedback } }"))
        val unapplied =
            listOf(
                "val unused: @Composable () -> Unit = { Box(ignored) }",
                "if (shown) Box(ignored)",
                "val ignored = Modifier\nBox(ignored)",
                "val (ignored, other) = Modifier to Modifier\nBox(ignored)",
                "Box(ignored.let { Modifier })",
                "Box(ignored.run { Modifier })",
                "Box(ignored.takeIf { false } ?: Modifier)",
                "Box(ignored.padding(4.dp).let { Modifier })",
            )
        for (handOff in unapplied) {
            val body = "$call$header { $feedback\n$handOff }"
            assertEquals(handOff, listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings(body))
        }
    }

    /** An unbraced `else` arm after a braced `if` arm is still read, so its target can find feedback. */
    @Test
    fun anUnbracedElseArmIsRead() {
        val target = "Box(Modifier.then(if (useDefault) { Modifier } else Modifier.shapedTouchTarget(press, go)))"
        val feedback = "Box(Modifier.shapedPressFeedback(press, CircleShape))"
        assertEquals(emptyList<String>(), shapedTouchFindings("$target { $feedback }"))
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings(target))
    }

    /** A quote inside a string template opens a nested literal rather than closing the outer one. */
    @Test
    fun stringsInsideTemplatesStayBlank() {
        val example = "val example = \"\${\"IconButton(go, Modifier.width(36.dp)) {}\"}\""
        assertEquals(emptyList<String>(), shapedTouchFindings(example, "conversation/composer/Toolbar.kt"))
        val commented = "val example = \"\${/* } */ \"IconButton(go, Modifier.width(36.dp)) {}\"}\""
        assertEquals(emptyList<String>(), shapedTouchFindings(commented, "conversation/composer/Toolbar.kt"))
        val quote = "\""
        val raw = quote.repeat(3)
        val templates =
            listOf(
                quote to "\${\"x\" + \"}\"}",
                quote to "\${/* } */ \"x\"}",
                raw to "\${${raw}IconButton(go)$raw} and",
                // Kotlin closes a raw string with the last three quotes of a run, so the ones before stay content.
                raw to "q$quote$quote",
                raw to "",
                raw to "$quote",
            )
        val quotedEnd = "val label = $raw" + "quoted$quote$raw\nIconButton(go, Modifier.width(36.dp)) {}"
        val composer = "conversation/composer/Toolbar.kt"
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @2"), shapedTouchFindings(quotedEnd, composer))
        for ((open, inner) in templates) {
            val source = "val a = $open$inner$open + next"
            val blank = "val a = $open${" ".repeat(inner.length)}$open + next"
            assertEquals(source, blank, blankCommentsAndStrings(source))
        }
    }

    /**
     * A design token proves a radius only when the object its file reaches holds a positive value: by explicit
     * import, this package or a star import, never an object of the same name the file does not import.
     */
    @Test
    fun qualifiedRadiiResolveToTheirValues() {
        val corners = "package ext\nobject Corners {\n    val None = 0.dp\n    val Md = 12.dp\n}"
        val tokens = objectMemberValues(listOf(corners))
        val shape = { radius: String ->
            "Box(Modifier.shapedTouchTarget(press, go)) { " +
                "Box(Modifier.shapedPressFeedback(press, RoundedCornerShape($radius))) }"
        }
        val missing = listOf("$MISSING_TOUCH_FEEDBACK @2")
        val proven = listOf("import ext.Corners", "import ext.*", "import ext.Corners as Corners")
        for (directive in proven) {
            val source = "$directive\n${shape("Corners.Md")}"
            assertEquals(directive, emptyList<String>(), shapedTouchFindings(source, "", tokens))
        }
        val unproven =
            listOf(
                "import ext.Corners\n" + shape("Corners.None"),
                "import ext.Corners\n" + shape("Tokens.Md"),
                "import outside.Corners\n" + shape("Corners.Md"),
                "\n" + shape("Corners.Md"),
            )
        for (source in unproven) {
            assertEquals(source, missing, shapedTouchFindings(source, "", tokens))
        }
        // Any binding named like the object is what the body may read, whatever the import says, so it fails closed.
        val shadowing =
            listOf(
                "fun Tile(Corners: CornerValues) {\n%s\n}",
                "fun Tile() { all.forEach { Corners ->\n%s\n} }",
                "fun Tile() { val Corners = other\n%s\n}",
                "fun Tile() { val (Corners, rest) = pair\n%s\n}",
                "fun Tile() { for (Corners in all) {\n%s\n} }",
                "class Tile(val Corners: CornerValues) { fun Draw() {\n%s\n} }",
                "class Tile { fun Draw() {\n%s\n}\n val Corners = other }",
            )
        for (form in shadowing) {
            val source = "import ext.Corners\n" + form.replace("%s", shape("Corners.Md"))
            assertEquals(form, listOf("$MISSING_TOUCH_FEEDBACK @3"), shapedTouchFindings(source, "", tokens))
        }
        // A shared shape's token is resolved through the imports of the file that declares the shape.
        val tile = { radius: String -> "package ui\nimport ext.Corners\nval TileShape = RoundedCornerShape($radius)" }
        val user =
            "import ui.TileShape\nBox(Modifier.shapedTouchTarget(press, go)) { " +
                "Box(Modifier.shapedPressFeedback(press, TileShape)) }"
        val rounded = topLevelShapeConstants(listOf(tile("Corners.Md")), tokens) + tokens
        val flat = topLevelShapeConstants(listOf(tile("Corners.None")), tokens) + tokens
        // An unresolved token stays unproven even when a default-package object shares its spelling.
        val nearby = tokens + objectMemberValues(listOf("object Corners {\n    val Md = 8.dp\n}"))
        val outside = tile("Corners.Md").replace("ext.", "outside.")
        val unresolved = topLevelShapeConstants(listOf(outside), nearby) + nearby
        assertEquals(emptyList<String>(), shapedTouchFindings(user, "", rounded))
        assertEquals(missing, shapedTouchFindings(user, "", flat))
        assertEquals(missing, shapedTouchFindings(user, "", unresolved))
    }

    /** A content lambda passed by name or by position is the Box's content as much as a trailing one is. */
    @Test
    fun namedBoxContentIsChecked() {
        val named = "Box(modifier = Modifier.clickable(onClick = go), content = { Avatar(title) })"
        assertEquals(listOf("$ROUND_CHILD @1"), shapedTouchFindings(named))
        val positional = "Box(Modifier.clickable(onClick = go), Alignment.Center, false, { Avatar(title) })"
        assertEquals(listOf("$ROUND_CHILD @1"), shapedTouchFindings(positional))
        for (content in listOf("@Composable { Avatar(title) }", "({ Avatar(title) })")) {
            val box = "Box(modifier = Modifier.clickable(onClick = go), content = $content)"
            assertEquals(content, listOf("$ROUND_CHILD @1"), shapedTouchFindings(box))
        }
        val clickInContent =
            "Box(modifier = Modifier.size(48.dp), content = { Box(Modifier.clickable(onClick = go)); Avatar(title) })"
        assertEquals(emptyList<String>(), shapedTouchFindings(clickInContent))
    }

    /** A named clip excuses a click only once it resolves to a rounded shape. */
    @Test
    fun aNamedClipMustResolveToARoundedShape() {
        val box = "Box(Modifier.clip(TileShape).clickable(onClick = go)) { Avatar(title) }"
        assertEquals(listOf("$ROUND_CHILD @2"), shapedTouchFindings("val TileShape = RectangleShape\n$box"))
        assertEquals(emptyList<String>(), shapedTouchFindings("val TileShape = RoundedCornerShape(8.dp)\n$box"))
        assertEquals(listOf("$ROUND_CHILD @1"), shapedTouchFindings(box))
        val fill = "Box(Modifier.background(Color.Red, CircleShape).clip(TileShape).clickable(onClick = go))"
        assertEquals(listOf("$CLICK_OVER_SHAPED_FILL @2"), shapedTouchFindings("val TileShape = RectangleShape\n$fill"))
        assertEquals(emptyList<String>(), shapedTouchFindings("val TileShape = CircleShape\n$fill"))
        // A rounded top-level constant does not prove a clip whose name a local binding shadows at the use.
        val rounded = "val TileShape = RoundedCornerShape(8.dp)\n"
        val shadowedClips =
            listOf(
                "fun Tile(TileShape: Shape) { $box }",
                "fun Tile() { all.forEach { TileShape -> $box } }",
                "fun Tile() { val TileShape = RectangleShape; $box }",
            )
        for (form in shadowedClips) {
            assertEquals(form, listOf("$ROUND_CHILD @2"), shapedTouchFindings(rounded + form))
        }
        assertEquals(emptyList<String>(), shapedTouchFindings(rounded + "fun Tile() { $box }"))
    }

    /** Feedback for a source that a local binding in the content shadows observes a different source. */
    @Test
    fun aShadowedSourceDrawsNoFeedback() {
        val feedback = "Box(Modifier.shapedPressFeedback(press, CircleShape))"
        val target = { content: String -> "Box(Modifier.shapedTouchTarget(press, go)) { $content }" }
        val shadowing =
            listOf(
                "val press = remember { MutableInteractionSource() }; $feedback",
                "val (press, other) = sources; $feedback",
            )
        for (content in shadowing) {
            assertEquals(content, listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings(target(content)))
        }
        assertEquals(emptyList<String>(), shapedTouchFindings(target(feedback)))
    }

    /** Builds a shaped control whose click needs replacement feedback in the supplied content. */
    private fun surfaceWith(content: String): String =
        "Surface(shape = CircleShape, modifier = Modifier.clickable(" +
            "interactionSource = press, indication = null, onClick = go)) {$content}"
}
