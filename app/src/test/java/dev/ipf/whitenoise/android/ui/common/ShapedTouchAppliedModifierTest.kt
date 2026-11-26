package dev.ipf.whitenoise.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Probes every syntactic form an applied modifier can take against the scanner's one shared rule: a touch target is
 * checked wherever it is written, while only a plain unconditional chain proves feedback or a clip.
 */
class ShapedTouchAppliedModifierTest {
    /** A target written in any form is checked: without feedback, each one is reported. */
    @Test
    fun everyTargetFormIsChecked() {
        val forms =
            listOf(
                "Box(if (c) $TARGET else Modifier)",
                "Box(if (c) { $TARGET } else { Modifier })",
                "Box(when { c -> $TARGET; else -> Modifier })",
                "Box(source?.let { Modifier.shapedTouchTarget(it, go) } ?: Modifier)",
                "Box(Modifier.let { it.shapedTouchTarget(press, go) })",
                "Box(Modifier.run { shapedTouchTarget(press, go) })",
                "Box(Modifier.also { it.shapedTouchTarget(press, go) })",
                "Box(Modifier.then($TARGET))",
                "Box(Modifier.then(if (a && b) $TARGET else Modifier))",
                "val tap = $TARGET; Box(tap)",
                "val tap: Modifier = $TARGET; Box(tap)",
                "var tap = $TARGET; Box(tap)",
                "var tap: Modifier = Modifier; tap = tap.shapedTouchTarget(press, go); Box(tap)",
                "val tap = $TARGET; Box(Modifier.size(4.dp).then(tap))",
                "val tap = $TARGET",
                "fun Tap(modifier: Modifier = $TARGET) { Box(modifier) }",
                "fun Modifier.tap(press: MutableInteractionSource) = shapedTouchTarget(press, go)\n" +
                    "Box(Modifier.tap(press))",
            )
        for (form in forms) {
            val kinds = kinds(form)
            assertTrue("$form must be reported, was $kinds", kinds.isNotEmpty())
            assertEquals(form, setOf(MISSING_TOUCH_FEEDBACK), kinds.toSet())
        }
    }

    /** Feedback that might not be applied never proves the target: one arm, a lambda, a `var` or an extension. */
    @Test
    fun onlyAnUnconditionalChainProvesFeedback() {
        val forms =
            listOf(
                "Box(if (c) $FEEDBACK else Modifier.size(1.dp))",
                "Box(if (c) { $FEEDBACK } else { Modifier })",
                "Box(when { c -> $FEEDBACK; else -> Modifier })",
                "Box(press.takeIf { c }?.let { Modifier.shapedPressFeedback(it, CircleShape) } ?: Modifier)",
                "Box(Modifier.let { it.shapedPressFeedback(press, CircleShape) })",
                "Box(Modifier.run { shapedPressFeedback(press, CircleShape) })",
                "Box(Modifier.also { it.shapedPressFeedback(press, CircleShape) })",
                "Box(Modifier.then(if (c) $FEEDBACK else Modifier))",
                "Box(Modifier.then(if (a || b) $FEEDBACK else Modifier))",
                "var pulse = $FEEDBACK; Box(pulse)",
                "var pulse: Modifier = Modifier; pulse = $FEEDBACK; Box(pulse)",
                "val pulse = if (c) $FEEDBACK else Modifier; Box(pulse)",
                "Pulse()",
                "Box(Modifier.pulse(press))",
            )
        val declarations =
            "\nfun Pulse(modifier: Modifier = $FEEDBACK) { Box(modifier) }" +
                "\nfun Modifier.pulse(press: MutableInteractionSource) = shapedPressFeedback(press, CircleShape)"
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $form }$declarations"))
        }
    }

    /** Plain inline chains still prove feedback, and a conditional target is satisfied by unconditional feedback. */
    @Test
    fun plainInlineChainsStillProveFeedback() {
        val forms =
            listOf(
                "Box(Modifier.size(4.dp).then($FEEDBACK))",
                "Box(Modifier.clip(CircleShape).pressFeedbackInsideClip(press))",
                "Box(Modifier.clip(CircleShape).indication(press, ripple()))",
            )
        for (form in forms) {
            assertEquals(form, emptyList<String>(), kinds("Box($TARGET) { $form }"))
        }
        assertEquals(emptyList<String>(), kinds("Box(if (c) $TARGET else Modifier) { Box($FEEDBACK) }"))
    }

    /**
     * A named modifier value is never expanded, so it can neither prove feedback or a clip nor deliver a target:
     * the gate asks for both to be written inline, because what a name holds at each use needs a parser to prove.
     */
    @Test
    fun namedModifierValuesProveNothing() {
        val feedbackForms =
            listOf(
                "val pulse = $FEEDBACK; Box(pulse)",
                "val pulse: Modifier = $FEEDBACK; Box(pulse)",
                "val pulse = $FEEDBACK; Box(Modifier.size(4.dp).then(pulse))",
                "val base = Modifier.clip(CircleShape); Box(base.pressFeedbackInsideClip(press))",
                "fun Child(base: Modifier) { " +
                    "Box(base.clip(CircleShape).padding(2.dp).pressFeedbackInsideClip(press)) }",
            )
        for (form in feedbackForms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $form }"))
        }
        val targetForms =
            listOf(
                "val tap = $TARGET; Box(tap) { Box($FEEDBACK) }",
                "val a = $TARGET; val b = a; Box(b) { Box($FEEDBACK) }",
                "val tap = $TARGET; Row(if (c) tap else Modifier)",
                "val tap = $TARGET; Custom(tap)",
                "val m = m.shapedTouchTarget(press, go); Box(m)",
            )
        for (form in targetForms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        val renamed = "import dev.ipf.whitenoise.android.ui.common.shapedTouchTarget as tapTarget"
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds("$renamed\nBox(Modifier.tapTarget(press, go))"))
    }

    /**
     * A ripple that does not clip itself needs an earlier unconditional clip, or must be the first link on a direct
     * child of a Surface with a rounded shape, which passes its full size to that child. Cards wrap their content.
     */
    @Test
    fun unclippedRipplesNeedAClipOrAShapedContainer() {
        val unclipped =
            listOf(
                "Box(Modifier.pressFeedbackInsideClip(press))",
                "Box(Modifier.indication(press, ripple()))",
                "Box(Modifier.pressFeedbackInsideClip(press).clip(CircleShape))",
                "Box(Modifier.then(if (c) Modifier.clip(CircleShape) else Modifier).pressFeedbackInsideClip(press))",
                "Surface { Box(Modifier.pressFeedbackInsideClip(press)) }",
                "Surface(shape = RectangleShape) { Box(Modifier.pressFeedbackInsideClip(press)) }",
                "Card(shape = RectangleShape) { Box(Modifier.pressFeedbackInsideClip(press)) }",
                "Card { Box(Modifier.pressFeedbackInsideClip(press)) }",
                "Card { Column(Modifier.indication(press, ripple())) }",
                "Surface(shape = CircleShape) { Box(Modifier.padding(4.dp).pressFeedbackInsideClip(press)) }",
                "Box(Modifier.clip(CircleShape).padding(12.dp).pressFeedbackInsideClip(press))",
                "Box(Modifier.clip(CircleShape).requiredSize(8.dp).indication(press, ripple()))",
                "Box(Modifier.clip(CircleShape).indication(press, if (c) ripple() else null))",
                "Box(Modifier.shapedPressFeedback(press, RectangleShape))",
            )
        for (content in unclipped) {
            assertEquals(content, listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $content }"))
        }
        val parameterRoot =
            "fun Tile(modifier: Modifier) { Box($TARGET) { " +
                "Surface(shape = CircleShape) { Box(modifier.pressFeedbackInsideClip(press)) } } }"
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds(parameterRoot))
        val nested = "Row { Box(Modifier.pressFeedbackInsideClip(press)) }"
        assertEquals(listOf(SHAPED_SURFACE), kinds(surfaceWith(nested)))
        val contained =
            listOf(
                "Surface(shape = CircleShape) { Box(Modifier.pressFeedbackInsideClip(press)) }",
                "Surface(shape = CircleShape) { Column(Modifier.pressFeedbackInsideClip(press).padding(4.dp)) }",
            )
        for (content in contained) {
            assertEquals(content, emptyList<String>(), kinds("Box($TARGET) { $content }"))
        }
        assertEquals(emptyList<String>(), kinds(surfaceWith("Box(Modifier.pressFeedbackInsideClip(press))")))
    }

    /** A nullable-source guard covers only the guarded arm's own chain, never a lambda or argument inside it. */
    @Test
    fun aSourceGuardDoesNotReachIntoLambdas() {
        val layout =
            "fun TouchLayout(press: MutableInteractionSource?, modifier: Modifier) { Box(modifier) { Box(FEEDBACK) } }"
        val target = "TouchLayout(press = press, modifier = Modifier.shapedTouchTarget(press, go))\n"
        val guarded = "Modifier.then(if (press != null) Modifier.shapedPressFeedback(press, CircleShape) else Modifier)"
        val ignored =
            "Modifier.then(if (press != null) Modifier.also { " +
                "it.shapedPressFeedback(press, CircleShape) } else Modifier)"
        assertEquals(emptyList<String>(), kinds(target + layout.replace("FEEDBACK", guarded)))
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds(target + layout.replace("FEEDBACK", ignored)))
    }

    /** A container clips only to a shape established as rounded; an unresolved or rectangular name is unproven. */
    @Test
    fun aContainerShapeMustBeEstablished() {
        val child = "Box(Modifier.pressFeedbackInsideClip(press))"
        val rectangle = "val shape: Shape = RectangleShape\nBox($TARGET) { Surface(shape = shape) { $child } }"
        val parameter = "fun Tile(shape: Shape) { Box($TARGET) { Surface(shape = shape) { $child } } }"
        val renamed = "val TileShape = RectangleShape\nBox($TARGET) { Surface(shape = TileShape) { $child } }"
        for (source in listOf(rectangle, parameter, renamed)) {
            assertEquals(source, listOf(MISSING_TOUCH_FEEDBACK), kinds(source))
        }
        val rounded = "val TileShape = RoundedCornerShape(8.dp)\nBox($TARGET) { Surface(shape = TileShape) { $child } }"
        assertEquals(emptyList<String>(), kinds(rounded))
    }

    /** Grouping parentheses keep an applied chain applied, and feedback after `?:` is not always composed. */
    @Test
    fun groupingStaysAppliedAndElvisChildrenAreOptional() {
        assertEquals(emptyList<String>(), kinds("Box($TARGET) { Box(($FEEDBACK)) }"))
        assertEquals(emptyList<String>(), kinds("Box($TARGET) { Box(Modifier.then(($FEEDBACK))) }"))
        val elvis = "custom?.invoke() ?: Box($FEEDBACK)"
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $elvis }"))
        assertEquals(listOf(SHAPED_SURFACE), kinds(surfaceWith(elvis)))
    }

    /**
     * The rest of the playbook's forms: `apply`, `takeIf`, `remember { }` blocks, comments and strings, feedback on
     * a sibling, and the same uncertain forms reached through the second entry point, a shaped Surface's click.
     */
    @Test
    fun playbookFormsFailAtBothEntryPoints() {
        val targets =
            listOf(
                "Box(Modifier.apply { shapedTouchTarget(press, go) })",
                "Box(Modifier.takeIf { c }?.shapedTouchTarget(press, go) ?: Modifier)",
                "val tap = remember { $TARGET }; Box(tap)",
            )
        for (form in targets) assertEquals(form, setOf(MISSING_TOUCH_FEEDBACK), kinds(form).toSet())
        val uncertain =
            listOf(
                "Box(Modifier.apply { shapedPressFeedback(press, CircleShape) })",
                "Box(Modifier.takeIf { c }?.shapedPressFeedback(press, CircleShape) ?: Modifier)",
                "val pulse = remember { $FEEDBACK }; Box(pulse)",
                "Box(Modifier /* .shapedPressFeedback(press, CircleShape) */)",
                "Text(\"Modifier.shapedPressFeedback(press, CircleShape)\", Modifier)",
            )
        for (form in uncertain) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $form }"))
            assertEquals(form, listOf(SHAPED_SURFACE), kinds(surfaceWith(form)))
        }
        val branches =
            listOf(
                "Box(if (c) $FEEDBACK else Modifier)",
                "Box(Modifier.let { it.shapedPressFeedback(press, CircleShape) })",
            )
        for (form in branches) assertEquals(form, listOf(SHAPED_SURFACE), kinds(surfaceWith(form)))
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET); Box($FEEDBACK)"))
        assertEquals(emptyList<String>(), kinds("// Box($TARGET)\nval note = \"Box($TARGET)\""))
    }

    /** A scope call that can replace the chain, after or around the feedback, leaves nothing in it certain. */
    @Test
    fun replacingScopeCallsMakeTheWholeChainUncertain() {
        val forms =
            listOf(
                "Box(Modifier.shapedPressFeedback(press, CircleShape).let { if (c) it else Modifier })",
                "Box(Modifier.shapedPressFeedback(press, CircleShape).run { if (c) this else Modifier })",
                "Box(Modifier.clip(CircleShape).let { it }.pressFeedbackInsideClip(press))",
                "val pulse = Modifier.shapedPressFeedback(press, CircleShape)\n    ?: Modifier\nBox(pulse)",
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $form }"))
        }
    }

    /** The guard applies only to a plain guarded arm, and a clip earlier in that same arm shapes its ripple. */
    @Test
    fun aGuardedArmMustBePlainAndCanCarryItsOwnClip() {
        val layout =
            "fun TouchLayout(press: MutableInteractionSource?, modifier: Modifier) { Box(modifier) { Box(FEEDBACK) } }"
        val target = "TouchLayout(press = press, modifier = Modifier.shapedTouchTarget(press, go))\n"
        val nested =
            listOf(
                "Modifier.then(if (press != null) if (c) $GUARDED else Modifier else Modifier)",
                "Modifier.then(if (press != null) { if (c) $GUARDED else Modifier } else { Modifier })",
                "Modifier.then(if (press != null) $GUARDED.takeIf { c } ?: Modifier else Modifier)",
            )
        for (modifier in nested) {
            assertEquals(modifier, listOf(MISSING_TOUCH_FEEDBACK), kinds(target + layout.replace("FEEDBACK", modifier)))
        }
        val clippedArm =
            "Modifier.then(if (press != null) Modifier.clip(CircleShape).pressFeedbackInsideClip(press) else Modifier)"
        assertEquals(emptyList<String>(), kinds(target + layout.replace("FEEDBACK", clippedArm)))
    }

    /** Every clip route uses one shape rule: a variable, a branch or a shadowed constant proves nothing. */
    @Test
    fun everyClipRouteNeedsAnEstablishedShape() {
        val rectangle = "val shape: Shape = RectangleShape\n"
        val child = "Box(Modifier.pressFeedbackInsideClip(press))"
        val unproven =
            listOf(
                "${rectangle}Box($TARGET) { Box(Modifier.clip(shape).pressFeedbackInsideClip(press)) }",
                "${rectangle}Box($TARGET) { Box(Modifier.shapedPressFeedback(press, shape)) }",
                "val TileShape = CircleShape.takeIf { c } ?: fallback\n" +
                    "Box($TARGET) { Surface(shape = TileShape) { $child } }",
                "val TileShape = RoundedCornerShape(8.dp)\n" +
                    "fun Tile(TileShape: Shape) { Box($TARGET) { Surface(shape = TileShape) { $child } } }",
            )
        for (source in unproven) {
            assertEquals(source, listOf(MISSING_TOUCH_FEEDBACK), kinds(source))
        }
        val constant = "val TileShape = RoundedCornerShape(8.dp)\n"
        val named = "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, TileShape)) }"
        assertEquals(emptyList<String>(), kinds(constant + named))
    }

    /** A target inside another call's arguments may be discarded, so it never counts as reaching the component. */
    @Test
    fun aTargetNestedInAnotherCallIsNotApplied() {
        val discard = "fun discard(value: Modifier): Modifier = Modifier\n"
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds("${discard}Box(discard($TARGET)) { Box($FEEDBACK) }"))
        val lambda = "Box(Modifier.pointerInput(Unit) { $TARGET }) { Box($FEEDBACK) }"
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds(lambda))
    }

    /** A stored or helper modifier can shrink a ripple before it or discard feedback after it, so neither proves. */
    @Test
    fun opaqueModifiersAroundFeedbackProveNothing() {
        val inset = "val inset = Modifier.padding(12.dp)\n"
        val forms =
            listOf(
                "fun Modifier.dropFeedback(): Modifier = Modifier\n" +
                    "Box($TARGET) { Box($FEEDBACK.dropFeedback()) }",
                "${inset}Box($TARGET) { Box(Modifier.clip(CircleShape).then(inset).pressFeedbackInsideClip(press)) }",
                "${inset}Box($TARGET) { " +
                    "Box(Modifier.clip(CircleShape).then(if (c) inset else Modifier).pressFeedbackInsideClip(press)) }",
                "${inset}Box($TARGET) { Surface(shape = CircleShape) { " +
                    "Box(Modifier.then(inset).pressFeedbackInsideClip(press)) } }",
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        val safeCall = "host?.Pulse(modifier = $FEEDBACK)"
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $safeCall }"))
        assertEquals(listOf(SHAPED_SURFACE), kinds(surfaceWith(safeCall)))
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds("val make = Modifier::shapedTouchTarget"))
    }

    /** A shape constant must resolve, in scope, to an established shape; a prefix or a bare name is not enough. */
    @Test
    fun shapeConstantsResolveInScope() {
        val named = "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, TileShape)) }"
        val forms =
            listOf(
                "val CircleShapeFallback: Shape = RectangleShape\n" +
                    "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, CircleShapeFallback)) }",
                "val TileShape: Shape = RectangleShape\n" +
                    "fun Tile() { if (c) { val TileShape: Shape = CircleShape }\n$named }",
                named,
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        val shared = mapOf("TileShape" to "RoundedCornerShape(8.dp)")
        assertEquals(emptyList<String>(), shapedTouchFindings(named, sharedShapes = shared))
        val rectangle = mapOf("TileShape" to "RectangleShape")
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @1"), shapedTouchFindings(named, sharedShapes = rectangle))
    }

    /** A branch nested anywhere inside a guarded arm drops the guard, so it never excuses uncertain feedback. */
    @Test
    fun aGuardStopsAtANestedBranch() {
        val layout =
            "fun TouchLayout(press: MutableInteractionSource?, modifier: Modifier) { Box(modifier) { Box(FEEDBACK) } }"
        val target = "TouchLayout(press = press, modifier = Modifier.shapedTouchTarget(press, go))\n"
        val nested =
            listOf(
                "Modifier.then(if (press != null) Modifier.then((if (c) $GUARDED else Modifier)) else Modifier)",
                "Modifier.then(if (press != null) Modifier.then($GUARDED.takeIf { c } ?: Modifier) else Modifier)",
                "Modifier.then(if (press != null) (if (c) $GUARDED else Modifier) else Modifier)",
            )
        for (modifier in nested) {
            assertEquals(modifier, listOf(MISSING_TOUCH_FEEDBACK), kinds(target + layout.replace("FEEDBACK", modifier)))
        }
    }

    /** A child behind a safe call, or after a return that leaves an enclosing scope, is not always composed. */
    @Test
    fun safeCallsAndEnclosingReturnsMakeAChildOptional() {
        val optional =
            listOf(
                "scope?.Item(modifier = $FEEDBACK)",
                "host?.slots.Disc(modifier = $FEEDBACK)",
                "items.forEach { if (c) return@forEach\nBox($FEEDBACK) }",
            )
        for (content in optional) {
            assertEquals(content, listOf(MISSING_TOUCH_FEEDBACK), kinds("Box($TARGET) { $content }"))
        }
        val function = "fun Pill() { Box($TARGET) { if (c) return@Pill\nBox($FEEDBACK) } }"
        assertEquals(listOf(MISSING_TOUCH_FEEDBACK), kinds(function))
        val handler =
            "Box($TARGET) { Box(Modifier.size(1.dp).pointerInput(Unit) { return@pointerInput }); Box($FEEDBACK) }"
        assertEquals(emptyList<String>(), kinds(handler))
    }

    /** Shapes resolve only to whole stock names or to the nearest definition in scope, across comment lines. */
    @Test
    fun shapeNamesNeedAWholeInScopeDefinition() {
        val named = "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, TileShape)) }"
        val forms =
            listOf(
                "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, CircleShapeOrSquare(2))) }",
                "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, MaterialTheme.shapes.small.copy(z))) }",
                "fun Tile() { val TileShape = RectangleShape\n$named }\nval TileShape = CircleShape",
                "val TileShape = CircleShape\nRow(content = { TileShape -> $named })",
                "val TileShape = CircleShape\n    // a note\n    .takeIf { c } ?: RectangleShape\n$named",
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
    }

    /** A grouped stored modifier, a reassignable shape and a value that is never drawn prove nothing either. */
    @Test
    fun groupedOpaquesMutableShapesAndStoredValuesProveNothing() {
        val inset = "val inset = Modifier.padding(12.dp)\n"
        val forms =
            listOf(
                "${inset}Box($TARGET) { Box(Modifier.clip(CircleShape).then((inset)).pressFeedbackInsideClip(press)) }",
                "${inset}Box($TARGET) { Surface(shape = CircleShape) { " +
                    "Box(Modifier.then((inset)).pressFeedbackInsideClip(press)) } }",
                "Box($TARGET) { Box($FEEDBACK.then((dropper))) }",
                "var TileShape: Shape = CircleShape\nTileShape = RectangleShape\n" +
                    "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, TileShape)) }",
                "data class ChipSpec(val modifier: Modifier)\nBox($TARGET) { ChipSpec(modifier = $FEEDBACK) }",
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
    }

    /** A labeled return skips the feedback whenever the scope its label names encloses it, braces or not. */
    @Test
    fun labeledReturnsResolveToTheScopeTheyName() {
        val skipped =
            listOf(
                "Box($TARGET) { if (c) { return@Box }\nBox($FEEDBACK) }",
                "fun Pill() { Box($TARGET) { if (c) { return@Pill }\nBox($FEEDBACK) } }",
                "fun Pill() { Box($TARGET) { when { c -> { return@Pill } }\nBox($FEEDBACK) } }",
                "fun Pill() { Box($TARGET) { value ?: run { return@Pill }\nBox($FEEDBACK) } }",
                "Box($TARGET) { items.forEach { if (c) { return@forEach }\nBox($FEEDBACK) } }",
            )
        for (source in skipped) {
            assertEquals(source, listOf(MISSING_TOUCH_FEEDBACK), kinds(source))
        }
        assertEquals(emptyList<String>(), kinds("Box($TARGET) { Box { return@Box }; Box($FEEDBACK) }"))
    }

    /** An infix `then`, an extension property or a zero or conditional radius proves nothing about the ripple. */
    @Test
    fun infixThenPropertiesAndFlatRadiiProveNothing() {
        val forms =
            listOf(
                "Box($TARGET) { " +
                    "Box(Modifier.clip(CircleShape) then inset then Modifier.pressFeedbackInsideClip(press)) }",
                "Box($TARGET) { Box($FEEDBACK.dropped) }",
                "Box($TARGET) { Box(Modifier.clip(CircleShape).inset.pressFeedbackInsideClip(press)) }",
                "Box($TARGET) { Surface(shape = CircleShape) { Box(Modifier.inset.pressFeedbackInsideClip(press)) } }",
                "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, RoundedCornerShape(0.dp))) }",
                "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, RoundedCornerShape(if (round) 50 else 0))) }",
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        val computed =
            listOf(
                "val radius = 0.dp\n" +
                    "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, RoundedCornerShape(radius))) }",
                "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, RoundedCornerShape(0.dp + 0.dp))) }",
            )
        for (form in computed) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        val proven = "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, RoundedCornerShape(Radii.md))) }"
        assertEquals(emptyList<String>(), kinds(proven))
        val hidden = listOf("private val TileShape = CircleShape", "private  val OtherShape = CircleShape")
        assertEquals(emptyMap<String, String>(), topLevelShapeConstants(hidden))
        val imported = "import external.TileShape\nBox($TARGET) { Box(Modifier.shapedPressFeedback(press, TileShape)) }"
        val shared = mapOf("TileShape" to "CircleShape")
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @2"), shapedTouchFindings(imported, sharedShapes = shared))
    }

    /** Any bare name after the root is opaque, and a shape member anywhere in an enclosing block shadows. */
    @Test
    fun bareNamesAreOpaqueAndMembersShadowShapes() {
        val forms =
            listOf(
                "Box($TARGET) { Box(Modifier.clip(CircleShape) then(inset).pressFeedbackInsideClip(press)) }",
                "Box($TARGET) { " +
                    "Box(Modifier.clip(CircleShape) and inset then Modifier.pressFeedbackInsideClip(press)) }",
                "val TileShape = CircleShape\nclass Tiles { fun Tile() { Box($TARGET) { " +
                    "Box(Modifier.shapedPressFeedback(press, TileShape)) } }\n" +
                    "private val TileShape: Shape = RectangleShape }",
                "val TileShape = CircleShape\nfun Tile() { Box($TARGET) { if (c) { " +
                    "Box(Modifier.shapedPressFeedback(press, TileShape)) } }\nval TileShape = RectangleShape }",
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
    }

    /** A shape constant needs one plain top-level `val`; any other binding of its name leaves it unproven. */
    @Test
    fun aShapeConstantNeedsItsOnlyBindingToBeAPlainVal() {
        val use = "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, TileShape)) }"
        val top = "val TileShape = CircleShape\n"
        val forms =
            listOf(
                "${top}class Tiles { fun Tile() { $use }\nval TileShape: Shape by lazy { RectangleShape } }",
                "${top}class Tiles { fun Tile() { $use }\nval TileShape: Shape get() = RectangleShape }",
                "${top}class Tiles { fun Tile() { $use }\nlateinit var TileShape: Shape }",
                "${top}fun Tile() { for (TileShape in shapes) { $use } }",
                "${top}fun Tile() { val (TileShape, other) = pair\n$use }",
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        assertEquals(emptyList<String>(), kinds(top + use))
        val shadowing =
            listOf(
                "import ext.TileShape\n$top$use",
                "import ext.RectShape as TileShape\n$top$use",
                "${top}class Tiles { fun Tile() { $use }\nobject TileShape : Shape by RectangleShape }",
                "${top}enum class Tiles { TileShape, Other; fun Tile() { $use } }",
                "${top}enum class Kind { A { override fun x() = 1 }, TileShape; fun Tile() { $use } }",
                "${top}val BoxScope.TileShape: Shape get() = RectangleShape\n$use",
                "${top}val <T : Any> T.TileShape: Shape get() = RectangleShape\n$use",
                "${top}val <T : Comparable<T>> T.TileShape: Shape get() = RectangleShape\n$use",
                "${top}val <K, V> Map<K, V>.TileShape: Shape get() = RectangleShape\n$use",
            )
        for (form in shadowing) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        val annotated = topLevelShapeConstants(listOf("package ui.a\nprivate @Stable val TileShape = CircleShape"))
        assertEquals(emptyMap<String, String>(), annotated)
        val suppressed = "package ui.a\nprivate @Suppress(\"unused\") val TileShape = CircleShape"
        assertEquals(emptyMap<String, String>(), topLevelShapeConstants(listOf(suppressed)))
        val extended =
            topLevelShapeConstants(
                listOf(
                    "package ui.a\nval TileShape = CircleShape",
                    "package ui.a\nval <T> T.TileShape: Shape get() = x",
                ),
            )
        assertEquals(emptyMap<String, String>(), extended)
        val alias = mapOf("ui.a.TileShape" to "BaseShape")
        val importing = "package ui.b\nimport ui.a.TileShape\nval BaseShape = CircleShape\n$use"
        assertEquals(listOf("$MISSING_TOUCH_FEEDBACK @4"), shapedTouchFindings(importing, sharedShapes = alias))
        val split = topLevelShapeConstants(listOf("package ui.a\nprivate\nval TileShape = CircleShape"))
        assertEquals(emptyMap<String, String>(), split)
    }

    /** A radius name must resolve to a positive literal here, or to a positive design token another file defines. */
    @Test
    fun radiusNamesMustResolveToPositiveValues() {
        val shape = { radius: String -> "Box($TARGET) { Box(Modifier.shapedPressFeedback(press, $radius)) }" }
        val forms =
            listOf(
                "val NoCorner = 0.dp\n" + shape("RoundedCornerShape(NoCorner)"),
                shape("RoundedCornerShape(Dp.Hairline)"),
                "object Corners { val None = 0.dp }\n" + shape("RoundedCornerShape(Corners.None)"),
                shape("RoundedCornerShape(percent = Zero)"),
                "interface Corners { companion object { val none = 0.dp } }\n" +
                    shape("RoundedCornerShape(Corners.none)"),
                "import ext.PillCorner\nval PillCorner = 10.dp\n" + shape("RoundedCornerShape(PillCorner)"),
                "val PillCorner = 10.dp\nval BoxScope.PillCorner: Dp get() = 0.dp\n" +
                    shape("RoundedCornerShape(PillCorner)"),
                "val PillCorner = 10.dp\nval <T> T.PillCorner: Dp get() = 0.dp\n" +
                    shape("RoundedCornerShape(PillCorner)"),
            )
        for (form in forms) {
            assertEquals(form, listOf(MISSING_TOUCH_FEEDBACK), kinds(form))
        }
        assertEquals(emptyList<String>(), kinds("val Corner = 8.dp\n" + shape("RoundedCornerShape(Corner)")))
        assertEquals(emptyList<String>(), kinds(shape("RoundedCornerShape(Radii.sm)")))
    }

    /** The finding kinds for [source], with the `Radii` design tokens in scope, without their line numbers. */
    private fun kinds(source: String): List<String> {
        val findings = shapedTouchFindings(source, sharedShapes = radii)
        return findings.map { it.substringBefore(" @") }
    }

    /** Builds a shaped control whose click needs replacement feedback in the supplied content. */
    private fun surfaceWith(content: String): String =
        "Surface(shape = CircleShape, modifier = Modifier.clickable(" +
            "interactionSource = press, indication = null, onClick = go)) {$content}"

    private companion object {
        val radii =
            objectMemberValues(listOf("object Radii {\n    val sm = 8.dp\n    val md = 12.dp\n}"))
        const val TARGET = "Modifier.shapedTouchTarget(press, go)"
        const val FEEDBACK = "Modifier.shapedPressFeedback(press, CircleShape)"
        const val GUARDED = "Modifier.shapedPressFeedback(press, CircleShape)"
    }
}
