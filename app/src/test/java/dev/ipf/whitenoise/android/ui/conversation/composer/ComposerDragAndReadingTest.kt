package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import dev.ipf.whitenoise.android.core.MentionComposer
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.common.accountActionColors
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real composer gestures keep one draft owner while resizing and reading. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerDragAndReadingTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var observed = TextFieldValue()
    private var sends = 0
    private var cancels = 0

    /** Actual edits retain navigation and reserved editor space on every fixed-geometry frame. */
    @Test
    fun typingKeepsTheSettledDraftTopAndEditorBounds() {
        render(longDraft + "\na")
        assertDraftTopStableDuringTextEdits(composeRule, { observed }, { sends })
    }

    @Test
    fun emptyDraftCanGrowFromItsVisibleGrip() {
        render("")
        composeRule.onNodeWithTag(COMPOSER_RESIZE_HANDLE_TAG).assertIsDisplayed()
        val before = height()
        drag(320f)
        assertEquals("an empty row is already at its smallest automatic height", before, height(), 1f)
        drag(-320f)
        assertTrue(height() > before + 200f)
        assertEquals("", observed.text)
    }

    @Test
    fun downwardShortDraftDragKeepsAutomaticGrowthAvailable() {
        render("Short")
        val before = height()
        drag(400f)
        assertEquals(before, height(), 1f)
        composeRule.onNode(hasSetTextAction()).performTextReplacement(longDraft)
        composeRule.waitForIdle()
        assertTrue("the unchanged automatic mode must still grow with the draft", height() > before + 100f)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h780dp-420dpi")
    fun downwardOneLineDragAtFractionalDensityKeepsAutomaticGrowth() {
        render("Short", fontScale = 1.15f)
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
        val before = height()
        drag(1_600f)
        assertEquals(before, height(), 2f)
        composeRule.onNode(hasSetTextAction()).performTextReplacement(longDraft)
        composeRule.waitForIdle()
        assertTrue("fractional pixel rounding must not disable automatic growth", height() > before + 100f)
    }

    @Test
    @Config(qualifiers = "en-w360dp-h780dp-440dpi")
    fun expandedOneLineAtFractionalDensityCollapsesBackToSuggestions() {
        val candidate =
            MentionComposer.Candidate(
                accountIdHex = "aa".repeat(32),
                npub = "npub1" + "q".repeat(58),
                displayName = "Ada",
                nip05 = null,
            )
        render("Short", fontScale = 1.15f, mentionCandidates = listOf(candidate))
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
        val before = height()
        drag(-500f)
        assertTrue(height() > before + 100f)
        drag(2_000f)
        assertEquals(before, height(), 2f)
        composeRule.onNode(hasSetTextAction()).performTextReplacement("@Ad")
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        assertEquals("@Ad", observed.text)
    }

    @Test
    fun firstUpwardDragFrameDoesNotJumpToTheManualMinimum() {
        render("")
        val before = height()
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            val start = center
            down(start)
            moveTo(start - Offset(0f, 30f), delayMillis = 16)
        }
        composeRule.waitForIdle()
        assertTrue("the first drag must actually move", height() > before)
        assertTrue("the small movement must stay below the extra manual chrome", height() < before + 24f)
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput { up() }
    }

    @Test
    fun twoLineDraftReturnsToAutomaticEvenWhenLandingZonesOverlap() {
        render("First line\nSecond line")
        composeRule.onNode(hasSetTextAction()).performClick()
        composeRule.waitForIdle()
        val automaticHeight = height()
        val original = observed
        drag(-250f)
        drag(height() - automaticHeight)
        assertEquals(automaticHeight, height(), 1f)
        assertEquals(original, observed)
        composeRule.onNode(hasSetTextAction()).performTextReplacement(longDraft)
        composeRule.waitForIdle()
        assertTrue("returning to Automatic must restore content growth", height() > automaticHeight + 100f)
    }

    /** Compact resizing preserves one measured editor line and focus without reserving an expanded toolbar. */
    @Test
    fun compactHeightCanReachOneEditorLineWithoutReservingAnExpandedToolbar() {
        render(longDraft, surfaceHeight = 150)
        val before = height()
        drag(600f)
        assertTrue("compact chrome must leave unused space to the transcript", height() < before - 40f)
        assertOneComposerEditorLine(composeRule.onNode(hasSetTextAction()))
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
    }

    @Test
    fun accessibleResizeAndEditorFocusRemainIndependentlyAvailable() {
        render("")
        val resize = composeRule.onNodeWithTag(COMPOSER_RESIZE_ACCESSIBILITY_TAG)
        val action = resize.fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
        composeRule.runOnIdle { assertTrue(action.action()) }
        composeRule.waitForIdle()
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals("", observed.text)
        assertEquals(0, sends)
    }

    @Test
    fun returningToTheContentHeightRestoresMentionSuggestions() {
        val candidate =
            MentionComposer.Candidate(
                accountIdHex = "aa".repeat(32),
                npub = "npub1" + "q".repeat(58),
                displayName = "Ada",
                nip05 = null,
            )
        render("@Ad", mentionCandidates = listOf(candidate))
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        val automaticHeight = height()
        val original = observed
        drag(-250f)
        composeRule.onNodeWithText("Ada").assertDoesNotExist()
        drag(height() - automaticHeight)
        composeRule.onNodeWithText("Ada").assertIsDisplayed()
        assertEquals(original, observed)
    }

    @Test
    fun longDraftCanShrinkToOneEditorLineAndGrowAgain() {
        render(longDraft)
        val initial = height()
        val original = observed
        drag(600f)
        assertTrue("manual minimum must be below automatic height", height() < initial)
        assertTrue("only one text line plus grip and controls", pillHeight() <= 103f)
        assertEquals(original, observed)
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_manual_minimum_light.png")
        drag(-600f)
        assertTrue("the same grip reaches full height", height() > 450f)
        assertEquals(original, observed)
    }

    /** Adding a long draft after empty expansion retains the one-line minimum without changing its content. */
    @Test
    fun aLongDraftEnteredAfterExpandingAnEmptyComposerCanStillStayAtOneLine() {
        render("")
        drag(-250f)
        composeRule.onNode(hasSetTextAction()).performTextReplacement(longDraft)
        composeRule.waitForIdle()
        val original = observed
        drag(600f)
        assertOneComposerEditorLine(composeRule.onNode(hasSetTextAction()))
        val minimum = height()
        composeRule.mainClock.advanceTimeBy(500)
        composeRule.waitForIdle()
        assertEquals("the stale empty height must not restore automatic growth", minimum, height(), 1f)
        assertEquals(original, observed)
    }

    /** The icon-only toolbar button stays accessible and changes scroll without editing or sending. */
    @Test
    fun jumpToTopPreservesTextAndSelectionAndHidesAtTop() {
        render(longDraft, dark = true)
        val original = observed
        assertDraftTopIconButton(composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG))
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_draft_top_dark.png")
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(0f, scroll(), 1f)
        assertEquals(original, observed)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        assertEquals("draft navigation must never submit", 0, sends)
    }

    @Test
    fun jumpToTopPreservesAnExistingSelectedRange() {
        val selection = TextRange(longDraft.length - 12, longDraft.length - 6)
        render(longDraft, selection = selection)
        val original = observed
        assertEquals(selection, original.selection)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(0f, scroll(), 1f)
        assertEquals(original, observed)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        assertEquals("draft navigation must never submit", 0, sends)
    }

    @Test
    fun readingFlickContinuesAfterReleaseWithoutMovingTheCaret() {
        render(longDraft)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        val original = observed
        composeRule.mainClock.autoAdvance = false
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            down(Offset(center.x, height - 8f))
            moveTo(Offset(center.x, height / 2f), delayMillis = 16)
            moveTo(Offset(center.x, 4f), delayMillis = 16)
            up()
        }
        val releaseScroll = scroll()
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertTrue("release velocity should keep reading moving", scroll() > releaseScroll + 10f)
        assertEquals(original, observed)
    }

    @Test
    fun reversingAnOutwardBoundaryDragReadsWithoutUndoingRejectedMovement() {
        render(longDraft)
        val original = observed
        for (outward in listOf(-1f, 1f)) {
            if (outward > 0f) {
                composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
                composeRule.waitForIdle()
            }
            val before = scroll()
            composeRule.mainClock.autoAdvance = false
            composeRule.onNode(hasSetTextAction()).performTouchInput {
                val start = center
                down(start)
                moveTo(start + Offset(0f, outward * 40f), delayMillis = 16)
                moveTo(start + Offset(0f, outward * 80f), delayMillis = 16)
                moveTo(start + Offset(0f, outward * 40f), delayMillis = 16)
                cancel()
            }
            val moved = (scroll() - before) * outward
            composeRule.mainClock.autoAdvance = true
            composeRule.waitForIdle()
            assertTrue("reversing at either edge must read only the new movement", moved in 20f..60f)
            assertEquals(original, observed)
            assertEquals("reading must never submit", 0, sends)
        }
    }

    @Test
    fun cancelledReadingTouchDoesNotStartMomentum() {
        render(longDraft)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        val original = observed
        composeRule.mainClock.autoAdvance = false
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            down(Offset(center.x, height - 8f))
            moveTo(Offset(center.x, height / 2f), delayMillis = 16)
            moveTo(Offset(center.x, 4f), delayMillis = 16)
            cancel()
        }
        val cancelledScroll = scroll()
        assertTrue("the touch must have scrolled before cancellation", cancelledScroll > 0f)
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("cancellation must not launch a fling", cancelledScroll, scroll(), 1f)
        assertEquals(original, observed)
    }

    @Test
    fun downwardReadingFlickReachesTheDraftStartWithoutMovingTheCaret() {
        render((1..32).joinToString("\n") { "Synthetic line $it" })
        val original = observed
        composeRule.mainClock.autoAdvance = false
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            swipe(Offset(center.x, 8f), Offset(center.x, height * 0.6f), durationMillis = 80)
        }
        assertTrue("the release must leave content for momentum to traverse", scroll() > 0f)
        composeRule.mainClock.advanceTimeBy(2000)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertEquals("momentum must reach the beginning", 0f, scroll(), 1f)
        assertEquals(original, observed)
    }

    /** A fresh draft must not inherit reading controls from the previous scrolled owner. */
    @Test
    fun changingOwnerToAnUnscrolledDraftHidesThePreviousTopAction() {
        lateinit var changeOwner: () -> Unit
        composeRule.setContent {
            var owner by remember { mutableStateOf(0) }
            changeOwner = { owner++ }
            WhiteNoiseTheme {
                Surface(Modifier.width(300.dp).height(192.dp)) {
                    ComposerPill(
                        actionColors = accountActionColors(appState = null),
                        textFieldValue =
                            if (owner == 0) {
                                TextFieldValue(longDraft, TextRange(longDraft.length))
                            } else {
                                TextFieldValue("Short")
                            },
                        composerFocus = remember { FocusRequester() },
                        emojiPickerOpen = false,
                        onValueChange = {},
                        onEmojiPickerToggle = {},
                        onAttachmentsToggle = {},
                        attachmentSheetOpen = false,
                        onPickFromGallery = null,
                        onPickDocument = null,
                        expansionMode = ComposerExpansionMode.Manual,
                        compactMeasurementWidth = 300.dp,
                        scrollOwnerKey = owner,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        composeRule.runOnIdle { changeOwner() }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        assertEquals(0f, scroll(), 1f)
    }

    /** Large RTL text retains one measured line and top navigation at the narrow manual minimum. */
    @Test
    fun narrowLargeRtlDraftRetainsUsableMinimumAndTopAction() {
        render(longDraft, width = 280, rtl = true, fontScale = 2f)
        val original = observed
        drag(600f)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_manual_minimum_large_rtl.png")
        assertOneComposerEditorLine(composeRule.onNode(hasSetTextAction()))
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals(0f, scroll(), 1f)
        assertEquals(original, observed)
    }

    @Test
    fun freshTouchStopsReadingMomentum() {
        render(longDraft)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performTouchInput {
            swipe(Offset(center.x, height - 8f), Offset(center.x, 4f), durationMillis = 80)
        }
        composeRule.mainClock.advanceTimeBy(64)
        editor.performTouchInput { down(center) }
        val stopped = scroll()
        composeRule.mainClock.advanceTimeBy(100)
        assertEquals("new touch must interrupt the old fling", stopped, scroll(), 1f)
        editor.performTouchInput { up() }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    /** Owner changes cancel old reading momentum even when draft text and selection are identical. */
    @Test
    fun changingDraftOwnerStopsMomentumEvenForIdenticalTextAndSelection() {
        lateinit var changeOwner: () -> Unit
        val value = TextFieldValue(longDraft, TextRange(longDraft.length))
        composeRule.setContent {
            var owner by remember { mutableStateOf("first synthetic account") }
            changeOwner = { owner = "second synthetic account" }
            WhiteNoiseTheme {
                Surface(Modifier.width(300.dp).height(192.dp)) {
                    ComposerPill(
                        actionColors = accountActionColors(appState = null),
                        textFieldValue = value,
                        composerFocus = remember { FocusRequester() },
                        emojiPickerOpen = false,
                        onValueChange = {},
                        onEmojiPickerToggle = {},
                        onAttachmentsToggle = {},
                        attachmentSheetOpen = false,
                        onPickFromGallery = null,
                        onPickDocument = null,
                        expansionMode = ComposerExpansionMode.Manual,
                        compactMeasurementWidth = 300.dp,
                        scrollOwnerKey = owner,
                    )
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).performClick()
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNode(hasSetTextAction()).performTouchInput {
            swipe(Offset(center.x, height - 8f), Offset(center.x, 4f), durationMillis = 80)
        }
        composeRule.mainClock.advanceTimeBy(64)
        composeRule.runOnIdle { changeOwner() }
        composeRule.mainClock.advanceTimeByFrame()
        val range =
            composeRule
                .onNode(hasSetTextAction())
                .fetchSemanticsNode()
                .config[SemanticsProperties.VerticalScrollAxisRange]
        assertEquals("new owner gets its own caret-visible viewport", range.maxValue(), scroll(), 1f)
        val newOwnerScroll = scroll()
        composeRule.mainClock.advanceTimeBy(200)
        assertEquals("old owner's fling must not move the new viewport", newOwnerScroll, scroll(), 1f)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    /** Narrow RTL editing preserves both top navigation and Cancel when the editor reaches its minimum. */
    @Test
    fun narrowEditedDraftKeepsNavigationAndCancelUsable() {
        render(longDraft, width = 280, rtl = true, fontScale = 2f, editing = true)
        drag(600f)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_manual_minimum_edit_large_rtl.png")
        composeRule.onNodeWithTag(COMPOSER_EDIT_CANCEL_TAG).assertIsDisplayed()
        assertOneComposerEditorLine(composeRule.onNode(hasSetTextAction()))
        composeRule.onNodeWithTag(COMPOSER_EDIT_CANCEL_TAG).performClick()
        composeRule.waitForIdle()
        assertEquals("navigation must not crowd out Cancel", 1, cancels)
        assertEquals(0, sends)
    }

    /** Wrapped reading controls reserve a complete editor line while all accessory actions remain clickable. */
    @Test
    fun navigationWrapsWithoutHidingActiveControlsOrTheEditor() {
        var extra = 0.dp
        var pressed = 0
        val value = TextFieldValue(longDraft, TextRange(longDraft.length))
        composeRule.setContent {
            var extraHeight by remember { mutableStateOf(0.dp) }
            WhiteNoiseTheme {
                Surface(Modifier.width(240.dp).height(240.dp)) {
                    Box(contentAlignment = Alignment.BottomCenter) {
                        ComposerPill(
                            actionColors = accountActionColors(appState = null),
                            textFieldValue = value,
                            composerFocus = remember { FocusRequester() },
                            emojiPickerOpen = false,
                            onValueChange = {},
                            onEmojiPickerToggle = {},
                            onAttachmentsToggle = {},
                            attachmentSheetOpen = false,
                            onPickFromGallery = {},
                            onPickDocument = {},
                            expansionMode = ComposerExpansionMode.Manual,
                            compactMeasurementWidth = 240.dp,
                            dictationControls = {
                                repeat(3) { index ->
                                    IconButton(
                                        onClick = { pressed++ },
                                        modifier = Modifier.size(48.dp).testTag("synthetic-control-$index"),
                                    ) { Text("${index + 1}") }
                                }
                            },
                            onExtraControlsHeightChanged = {
                                extraHeight = it
                                extra = it
                            },
                            modifier = Modifier.height(84.dp + extraHeight),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(48.dp, extra)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        assertEquals(
            "wrapping must leave one complete editor line",
            24f,
            composeRule
                .onNode(hasSetTextAction())
                .fetchSemanticsNode()
                .boundsInRoot.height,
            1f,
        )
        repeat(3) { index ->
            composeRule.onNodeWithTag("synthetic-control-$index").assertIsDisplayed().performClick()
        }
        composeRule.waitForIdle()
        assertEquals("all active controls remain actionable", 3, pressed)
    }

    /** The resize grip stays outside accessory bounds so attachment and reply actions remain reachable. */
    @Test
    fun resizeStripLeavesAccessoryActionsIndependent() {
        var dismissed = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(Modifier.width(300.dp).height(240.dp)) {
                    ComposerPill(
                        actionColors = accountActionColors(appState = null),
                        textFieldValue = TextFieldValue("Synthetic caption"),
                        composerFocus = remember { FocusRequester() },
                        emojiPickerOpen = false,
                        onValueChange = {},
                        onEmojiPickerToggle = {},
                        onAttachmentsToggle = {},
                        attachmentSheetOpen = false,
                        onPickFromGallery = null,
                        onPickDocument = null,
                        expansionMode = ComposerExpansionMode.Manual,
                        accessoryContent = {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .clickable { dismissed++ }
                                    .testTag("synthetic-accessory"),
                            )
                        },
                    )
                }
            }
        }
        composeRule.waitForIdle()
        val strip = composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).fetchSemanticsNode().boundsInRoot
        val accessory = composeRule.onNodeWithTag("synthetic-accessory")
        assertTrue(
            "the grip must not cover reply/attachment actions",
            strip.bottom <= accessory.fetchSemanticsNode().boundsInRoot.top,
        )
        accessory.performClick()
        composeRule.waitForIdle()
        assertEquals(1, dismissed)
    }

    /** Swipes the actual resize strip and waits for layout before assertions inspect its resulting geometry. */
    private fun drag(delta: Float) {
        composeRule.onNodeWithTag(COMPOSER_RESIZE_GESTURE_TAG).performTouchInput {
            swipe(center, center + Offset(0f, delta), durationMillis = 320)
        }
        composeRule.waitForIdle()
    }

    /** Measures the full composer, including any separate navigation row, for resize and reservation assertions. */
    private fun height() = composerNodeHeight(composeRule.onNodeWithTag(TAG))

    /** Measures the editor pill separately from surrounding composer controls during compact resizing. */
    private fun pillHeight() = composerNodeHeight(composeRule.onNodeWithTag(COMPOSER_PILL_SURFACE_TAG))

    /** Reads the editor's semantic scroll offset without moving its caret or selection. */
    private fun scroll() = composerEditorScroll(composeRule.onNode(hasSetTextAction()))

    /** Hosts the real composer with an unsent synthetic draft and observes edits, Send and Cancel callbacks. */
    private fun render(
        draft: String,
        dark: Boolean = false,
        width: Int = 360,
        rtl: Boolean = false,
        fontScale: Float = 1f,
        editing: Boolean = false,
        mentionCandidates: List<MentionComposer.Candidate> = emptyList(),
        surfaceHeight: Int = 600,
        selection: TextRange = TextRange(draft.length),
    ) {
        observed = TextFieldValue(draft, selection)
        sends = 0
        cancels = 0
        composeRule.setContent {
            var value by remember { mutableStateOf(observed) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = dark) {
                    Surface(Modifier.width(width.dp).height(surfaceHeight.dp)) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            ComposerBar(
                                replyingTo = null,
                                messageTextCopy = MessageTextCopy.Default,
                                onCancelReply = {},
                                onSend = { _, _ -> sends++ },
                                onPickFromGallery = {},
                                onPickDocument = {},
                                mentionCandidates = mentionCandidates,
                                mentionPickerEnabled = mentionCandidates.isNotEmpty(),
                                editingMessageId = if (editing) "synthetic-message" else null,
                                editingInitialText = draft.takeIf { editing },
                                onCancelEdit = { cancels++ },
                                initialDraft = value,
                                onDraftChange = {
                                    value = it
                                    observed = it
                                },
                                modifier = Modifier.testTag(TAG),
                            )
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private companion object {
        const val TAG = "composer-reading-test"
        val longDraft = (1..80).joinToString("\n") { "Synthetic line $it in this long draft" }
    }
}
