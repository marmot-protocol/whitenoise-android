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
 * A click passes when an earlier clip in its own modifier chain is not a rectangle, or when it turns its own
 * highlight off (`indication = null`) and that control draws the press from the same source binding.
 * Audited exceptions in [allowed] name each control and its click, so a fixed site has to leave the list.
 */
class ShapedTouchFeedbackCoverageTest {
    /** Every UI source passes the shape rules apart from the audited exceptions. */
    @Test
    fun pressFeedbackFollowsTheVisibleShape() {
        val root = appModuleFile(UI_ROOT)
        val sources =
            root
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }
        val wrappers = stretchedWrapperFindings(sources)
        val members = objectMemberValues(sources.values)
        val shapes = topLevelShapeConstants(sources.values, members) + members
        val found =
            sources.flatMap { (path, source) ->
                (shapedTouchFindings(source, path, shapes) + wrappers.getValue(path)).map { finding ->
                    val kind = kind(finding)
                    val line = finding.substringAfterLast(" @").toInt()
                    val site = if (kind == ROUND_CHILD) roundChildClickSite(source, line) else null
                    if (site == null) "$path: $finding" else "$path: $kind: $site"
                }
            }
        assertEquals(
            "Press feedback must follow the visible shape. Use Surface(onClick = …), clip before the click, " +
                "or shapedTouchTarget with shapedPressFeedback (ui/common/ShapedTouch.kt). Findings:\n" +
                found.joinToString("\n"),
            allowed,
            found.sorted(),
        )
    }

    /**
     * The shared press-feedback API is used only where this gate scans, and declared only in ShapedTouch.kt. A
     * caller in another production source set or package, or a same-named function elsewhere, would escape or
     * impersonate every rule above.
     */
    @Test
    fun theSharedFeedbackApiStaysInsideTheScannedTree() {
        val sourceSets = appModuleFile(SOURCE_ROOT)
        val ui = appModuleFile(UI_ROOT)
        val production =
            sourceSets
                .walkTopDown()
                .onEnter { it == sourceSets || it.parentFile != sourceSets || it.name !in testSourceSets }
                .filter { it.isFile && it.extension == "kt" }
                .associateWith { blankCommentsAndStrings(it.readText()) }
        val outside =
            production
                .filter { (file, code) -> !file.startsWith(ui) && sharedFeedbackApi.containsMatchIn(code) }
                .keys
                .map { it.relativeTo(sourceSets).invariantSeparatorsPath }
        assertEquals("Move these callers under main ui/ so the touch gate checks them", emptyList<String>(), outside)
        val declarations =
            production
                .filter { (_, code) -> sharedFeedbackDeclaration.containsMatchIn(code) }
                .keys
                .map { it.relativeTo(sourceSets).invariantSeparatorsPath }
        assertEquals(listOf(SHARED_FEEDBACK_SOURCE), declarations)
        // The gate trusts these layout components and standard modifiers, so production must not redefine them.
        val trusted =
            Regex(
                """\bfun\s+(?:${drawingComponents.joinToString("|")})\s*\(|""" +
                    """\bfun\s+Modifier\.(?:${(standardModifiers - sharedFeedbackNames).joinToString("|")})\s*\(""",
            )
        val redefined = production.filter { (_, code) -> trusted.containsMatchIn(code) }.keys
        assertEquals(emptyList<String>(), redefined.map { it.relativeTo(sourceSets).invariantSeparatorsPath })
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

    /**
     * The patterns found in review: card modifiers, a clip after the click (in `then`, or after a click's
     * trailing lambda), fills, bare glyphs, and sized icon buttons whose modifier is named or positional.
     */
    @Test
    fun scannerFindsTheReviewedHoles() {
        val cases =
            mapOf(
                "Card(modifier = Modifier.clickable { go() }) {}" to SHAPED_SURFACE,
                "Box(Modifier.size(40.dp).clickable { go() }.then(Modifier.clip(CircleShape)))" to CLICK_BEFORE_CLIP,
                "Box(Modifier.clickable(enabled = true) { go() }.clip(CircleShape))" to CLICK_BEFORE_CLIP,
                "Box(Modifier.clickable(onClick = go).clip(CircleShape))" to CLICK_BEFORE_CLIP,
                "Box(Modifier.size(40.dp).background(c, CircleShape).clickable { go() })" to CLICK_OVER_SHAPED_FILL,
                "IconButton(onClick = go, modifier = Modifier.requiredSize(36.dp, 48.dp)) {}" to STRETCHED_ICON_BUTTON,
                "IconToggleButton(on, { go() }, Modifier.width(36.dp).height(48.dp)) {}" to STRETCHED_ICON_BUTTON,
                "IconButton(onClick = go, Modifier.width(36.dp).height(48.dp)) {}" to STRETCHED_ICON_BUTTON,
                "IconToggleButton(checked = on, onCheckedChange = { go() }, Modifier.width(36.dp).height(48.dp)) {}" to
                    STRETCHED_ICON_BUTTON,
                "IconToggleButton(on, onCheckedChange = { go() }, Modifier.width(36.dp).height(48.dp)) {}" to
                    STRETCHED_ICON_BUTTON,
                "Icon(p, null, Modifier.size(28.dp).clickable { retry() })" to BARE_GLYPH,
            )
        cases.forEach { (source, kind) -> assertEquals(source, listOf(kind), shapedTouchFindings(source).map(::kind)) }
        val slotSized = "IconButton(onClick = go, modifier = modifier) {}"
        assertEquals(
            listOf(STRETCHED_ICON_BUTTON),
            shapedTouchFindings(slotSized, "conversation/composer/Toolbar.kt").map(::kind),
        )
        assertEquals(emptyList<String>(), shapedTouchFindings(slotSized, "settings/Row.kt"))
        val positional = "IconButton(go, modifier) {}"
        assertEquals(
            listOf(STRETCHED_ICON_BUTTON),
            shapedTouchFindings(positional, "conversation/composer/Toolbar.kt").map(::kind),
        )
    }

    /**
     * Only a clip in the click's own modifier chain shapes its highlight, never one in a content lambda or
     * another argument, and `indication = null` passes only when a modifier watching the same interaction
     * source draws the press instead.
     */
    @Test
    fun onlyTheClicksOwnChainAndAMatchingIndicationExcuseIt() {
        val clipInContent =
            "Surface(shape = CircleShape, content = { Box(Modifier.clip(CircleShape)) }, " +
                "modifier = Modifier.clickable { go() })"
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(clipInContent).map(::kind))
        val clipInThen = "Box(Modifier.clip(CircleShape).then(Modifier.clickable { go() })) { Avatar() }"
        assertEquals(emptyList<String>(), shapedTouchFindings(clipInThen))
        val click = "Modifier.clickable(interactionSource = press, indication = null) { go() }"
        val noReplacement = "Surface(shape = CircleShape, modifier = $click)"
        val otherSource = "$noReplacement { Box(Modifier.pressFeedbackInsideClip(other)) }"
        val sameSource = "$noReplacement { Box(Modifier.pressFeedbackInsideClip(press)) }"
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(noReplacement).map(::kind))
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(otherSource).map(::kind))
        assertEquals(emptyList<String>(), shapedTouchFindings(sameSource))
        // A source handed to a round component's parameter counts when that parameter is the one drawn.
        val handedIn =
            """
            ColorSwatch(pressSource = press, modifier = $click)
            fun ColorSwatch(pressSource: MutableInteractionSource, modifier: Modifier) {
                Box(modifier.clip(CircleShape).pressFeedbackInsideClip(pressSource))
            }
            """.trimIndent()
        assertEquals(emptyList<String>(), shapedTouchFindings(handedIn))
        val handedElsewhere = handedIn.replace("pressFeedbackInsideClip(pressSource)", "pressFeedbackInsideClip(other)")
        assertEquals(listOf(ROUND_COMPONENT), shapedTouchFindings(handedElsewhere).map(::kind))
    }

    /** Reusing a variable name in another composable does not supply this control's missing feedback. */
    @Test
    fun aReplacementInAnotherComposableDoesNotExcuseTheClick() {
        val source =
            """
            fun Broken() {
                val press = remember { MutableInteractionSource() }
                Surface(shape = CircleShape, modifier = Modifier.clickable(
                    interactionSource = press, indication = null, onClick = go)) {}
            }
            fun Working() {
                val press = remember { MutableInteractionSource() }
                Surface(shape = CircleShape, modifier = Modifier.clickable(
                    interactionSource = press, indication = null, onClick = go)) {
                    Box(Modifier.pressFeedbackInsideClip(press))
                }
            }
            """.trimIndent()
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(source).map(::kind))
    }

    /** Even a shared source only excuses the control whose own content draws its feedback. */
    @Test
    fun aReplacementInASiblingControlDoesNotExcuseTheClick() {
        val source =
            """
            val press = remember { MutableInteractionSource() }
            Surface(shape = CircleShape, modifier = Modifier.clickable(
                interactionSource = press, indication = null, onClick = go)) {
                Box(Modifier.pressFeedbackInsideClip(press))
            }
            Surface(shape = CircleShape, modifier = Modifier.clickable(
                interactionSource = press, indication = null, onClick = go)) {}
            """.trimIndent()
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(source).map(::kind))
    }

    /** A child that shadows the parent's source cannot draw the parent's press, despite using the same name. */
    @Test
    fun aShadowedChildSourceDoesNotReplaceTheParentSource() {
        val source =
            """
            val press = remember { MutableInteractionSource() }
            Surface(shape = CircleShape, modifier = Modifier.clickable(
                interactionSource = press, indication = null, onClick = go)) {
                val press = remember { MutableInteractionSource() }
                Box(Modifier.pressFeedbackInsideClip(press))
            }
            """.trimIndent()
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(source).map(::kind))
    }

    /** A named hand-off must be drawn by that component, not an unrelated function with the same parameter. */
    @Test
    fun aReplacementInAnUnrelatedFunctionDoesNotValidateTheHandOff() {
        val source =
            """
            val press = remember { MutableInteractionSource() }
            ColorSwatch(pressSource = press, modifier = Modifier.clickable(
                interactionSource = press, indication = null, onClick = go))
            fun Other(pressSource: MutableInteractionSource) {
                Box(Modifier.pressFeedbackInsideClip(pressSource))
            }
            fun ColorSwatch(pressSource: MutableInteractionSource, modifier: Modifier) {
                Box(modifier.clip(CircleShape))
            }
            """.trimIndent()
        assertEquals(listOf(ROUND_COMPONENT), shapedTouchFindings(source).map(::kind))
    }

    /** An overload or a member with the same name cannot prove which implementation draws this hand-off. */
    @Test
    fun aNamedHandOffRequiresOneUnambiguousComponent() {
        val call =
            "ColorSwatch(pressSource = press, modifier = Modifier.clickable(" +
                "interactionSource = press, indication = null, onClick = go))"
        val overloads =
            """
            fun ColorSwatch(pressSource: MutableInteractionSource, modifier: Modifier) {
                Box(modifier.clip(CircleShape))
            }
            fun ColorSwatch(pressSource: MutableInteractionSource, modifier: Modifier, enabled: Boolean) {
                Box(Modifier.pressFeedbackInsideClip(pressSource))
            }
            """.trimIndent()
        val member =
            """
            class OtherPalette {
                fun ColorSwatch(pressSource: MutableInteractionSource, modifier: Modifier) {
                    Box(Modifier.pressFeedbackInsideClip(pressSource))
                }
            }
            """.trimIndent()
        for (implementation in listOf(overloads, member)) {
            assertEquals(listOf(ROUND_COMPONENT), shapedTouchFindings("$call\n$implementation").map(::kind))
        }
    }

    /** A clip only excuses a click on a shaped Surface or card when it clips to that container's own shape. */
    @Test
    fun aClipOfAnotherShapeDoesNotExcuseTheClick() {
        val mismatched =
            "Surface(shape = CircleShape, modifier = Modifier.clip(RoundedCornerShape(2.dp)).clickable { go() }) {}"
        val matched = "Surface(shape = CircleShape, modifier = Modifier.clip(CircleShape).clickable { go() }) {}"
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(mismatched).map(::kind))
        assertEquals(emptyList<String>(), shapedTouchFindings(matched))
        // A card with no shape named has its default one, which a circle does not match.
        val defaultCard = "Card(modifier = Modifier.clip(CircleShape).clickable { go() }) {}"
        val defaultCardClipped = "Card(modifier = Modifier.clip(CardDefaults.shape).clickable { go() }) {}"
        assertEquals(listOf(SHAPED_SURFACE), shapedTouchFindings(defaultCard).map(::kind))
        assertEquals(emptyList<String>(), shapedTouchFindings(defaultCardClipped))
        val outlinedClipped =
            "OutlinedCard(modifier = Modifier.clip(CardDefaults.outlinedShape).clickable { go() }) {}"
        assertEquals(emptyList<String>(), shapedTouchFindings(outlinedClipped))
    }

    /**
     * A wrapper that hands its caller's modifier to an icon button is found in one file, and a call that sizes it
     * as a slot that may not be square is found in another — the shape of the emoji and paste buttons on master.
     */
    @Test
    fun aSlotSizedCallOfAPassThroughWrapperIsFound() {
        val wrapper =
            """
            @Composable
            internal fun PasteButton(onPaste: () -> Unit, modifier: Modifier = Modifier) {
                IconButton(onClick = onPaste, modifier = modifier.onGloballyPositioned { }) { Icon() }
            }
            """.trimIndent()
        val caller = "PasteButton(onPaste = go, modifier = Modifier.width(40.dp).height(48.dp))"
        val square = "PasteButton(onPaste = go, modifier = Modifier.size(48.dp))"
        val wrappers = passThroughIconButtonWrappers(wrapper)
        assertEquals(mapOf("PasteButton" to setOf(1)), wrappers)
        assertEquals(listOf(STRETCHED_ICON_BUTTON), stretchedWrapperCalls(caller, wrappers).map(::kind))
        val mixed = "PasteButton(onPaste = go, Modifier.width(40.dp).height(48.dp))"
        assertEquals(listOf(STRETCHED_ICON_BUTTON), stretchedWrapperCalls(mixed, wrappers).map(::kind))
        assertEquals(emptyList<String>(), stretchedWrapperCalls(square, wrappers))
        assertEquals(emptyList<String>(), stretchedWrapperCalls(wrapper, wrappers))
        val positional = "fun A(onClick: () -> Unit, modifier: Modifier) { IconButton(onClick = onClick, modifier) {} }"
        assertEquals(mapOf("A" to setOf(1)), passThroughIconButtonWrappers(positional))
    }

    /**
     * The conversation header's shape on master: a slot size held in a local `val`, passed positionally to a
     * private wrapper. It is found, while a size in another argument is not mistaken for the modifier's.
     */
    @Test
    fun aSlotSizeHeldInALocalValIsFound() {
        val header =
            """
            @Composable
            internal fun Header(actions: List<Action>) {
                val cell = Modifier.width(36.dp).height(48.dp)
                for (action in actions) {
                    ActionButton(action, cell.testTag("x"))
                }
                ActionButton(Action(size = Modifier.width(36.dp)), Modifier.size(48.dp))
            }

            @Composable
            private fun ActionButton(
                action: Action,
                modifier: Modifier,
            ) {
                val content = @Composable { Icon() }
                IconButton(onClick = action.onClick, modifier = modifier, content = content)
            }
            """.trimIndent()
        val wrappers = passThroughIconButtonWrappers(header)
        assertEquals(mapOf("ActionButton" to setOf(1)), wrappers)
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @5"), stretchedWrapperCalls(header, wrappers))
    }

    /** A wrapper is named after the function whose body holds the icon button, not an earlier nested `fun`. */
    @Test
    fun aWrapperIsNamedAfterTheFunctionThatHoldsTheButton() {
        val source =
            """
            @Composable
            internal fun PlayPause(onToggle: () -> Unit, modifier: Modifier = Modifier) {
                val listener = object : Listener { override fun onEvents(player: Player) { update() } }
                IconButton(onClick = onToggle, modifier = modifier) { Icon() }
            }
            """.trimIndent()
        assertEquals(mapOf("PlayPause" to setOf(1)), passThroughIconButtonWrappers(source))
    }

    /**
     * A slot size is read from the binding in scope at the call — the nearest one before it, not a same-named
     * one elsewhere — and over a chain ktlint wraps onto several lines.
     */
    @Test
    fun theNearestWrappedBindingIsRead() {
        val wrappers = mapOf("ActionButton" to setOf(1))
        val source =
            """
            @Composable
            internal fun Elsewhere() {
                val cell = Modifier.size(48.dp)
                ActionButton(action, cell)
            }

            @Composable
            internal fun Header() {
                val cell =
                    Modifier
                        .width(36.dp)
                        .height(48.dp)
                ActionButton(action, cell.testTag("x"))
            }
            """.trimIndent()
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @13"), stretchedWrapperCalls(source, wrappers))
    }

    /** A binding in a block that has already closed is not the one a later call uses, in either direction. */
    @Test
    fun aShadowingBindingInAClosedBlockIsIgnored() {
        val wrappers = mapOf("ActionButton" to setOf(1))
        val stretchedOuter =
            """
            @Composable
            internal fun Header(expanded: Boolean) {
                val cell = Modifier.width(36.dp).height(48.dp)
                if (expanded) {
                    val cell = Modifier.size(48.dp)
                    Spacer(cell)
                }
                ActionButton(action, cell)
            }
            """.trimIndent()
        val squareOuter =
            """
            @Composable
            internal fun Header(expanded: Boolean) {
                val cell = Modifier.size(48.dp)
                if (expanded) {
                    val cell = Modifier.width(36.dp).height(48.dp)
                    Spacer(cell)
                }
                ActionButton(action, cell)
            }
            """.trimIndent()
        assertEquals(listOf("$STRETCHED_ICON_BUTTON @8"), stretchedWrapperCalls(stretchedOuter, wrappers))
        assertEquals(emptyList<String>(), stretchedWrapperCalls(squareOuter, wrappers))
    }

    /** A `modifier` parameter after one with a generic type keeps its position. */
    @Test
    fun aModifierAfterAGenericParameterKeepsItsPosition() {
        val source =
            """
            @Composable
            internal fun Picker(options: Map<String, Int>, onPick: (String) -> Unit, modifier: Modifier) {
                IconButton(onClick = { onPick("a") }, modifier = modifier) { Icon() }
            }
            """.trimIndent()
        assertEquals(mapOf("Picker" to setOf(2)), passThroughIconButtonWrappers(source))
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
                Box(Modifier.shapedPressFeedback(press, CircleShape))
            }
            Box(Modifier.clip(CircleShape).clickable { go() }) { Avatar(title) }
            SlotIconButton(onClick = go, modifier = Modifier.width(40.dp).height(48.dp)) {}
            IconButton(onClick = go, modifier = Modifier.size(48.dp), content = { Box(Modifier.width(24.dp)) })
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
        const val SOURCE_ROOT = "src"
        const val SHARED_FEEDBACK_SOURCE = "main/java/dev/ipf/whitenoise/android/ui/common/ShapedTouch.kt"
        val testSourceSets = setOf("androidTest", "test", "testSupport", "testZapstore")
        val sharedFeedbackNames = setOf("pressFeedbackInsideClip", "shapedPressFeedback", "shapedTouchTarget")
        val sharedFeedbackApi = Regex("""\b(?:shapedTouchTarget|shapedPressFeedback|pressFeedbackInsideClip)\b""")
        val sharedFeedbackDeclaration =
            Regex("""\bfun\s+(?:[\w.<>]+\.)?(?:shapedTouchTarget|shapedPressFeedback|pressFeedbackInsideClip)\s*\(""")

        /**
         * The rectangular video tile and profile banner each contain a round status or camera glyph.
         * Their highlights match the entire control. Each exception identifies the control and its click signature.
         */
        val allowed =
            listOf(
                "conversation/media/MediaVideoGridTile.kt: $ROUND_CHILD: " +
                    "MediaVideoGridTile: testTag(videoAttachmentOpenTestTag(messageIdHex,attachmentIndex)): " +
                    "combinedClickable(onLongClick=onLongPress,onClick={onTileTap()})",
                "profile/ProfileEditScreen.kt: $ROUND_CHILD: ProfileBannerControl: " +
                    "testTag(PROFILE_BANNER_CONTROL_TAG): " +
                    "clickable(enabled=!isUploading&&isProfileLoaded," +
                    "onClickLabel=stringResource(R.string.profile_banner_edit),role=Role.Button,onClick=onClick)",
            )
    }
}
