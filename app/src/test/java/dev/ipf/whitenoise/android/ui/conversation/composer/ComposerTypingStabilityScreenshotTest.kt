package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
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

/** Fixed-size draft editing keeps navigation mounted in both the inline and narrow separate-row layouts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerTypingStabilityScreenshotTest {
    @get:Rule val composeRule = createComposeRule()
    private val draft = (1..32).joinToString("\n") { "Synthetic draft line $it" } + "\na"
    private var value by mutableStateOf(TextFieldValue(draft, TextRange(draft.length)))
    private val focusRequester = FocusRequester()
    private var extraHeight = 0.dp
    private var sends = 0
    private var owner by mutableStateOf(0)
    private var transitionActive by mutableStateOf(false)

    /** The narrow light controls row must never disappear for ordinary character edits. */
    @Test
    fun typingKeepsTheNarrowLightControlsRow() = checkTyping("light", narrow = true)

    /** The wide dark toolbar retains its inline navigation width while typing and deleting. */
    @Test
    fun typingKeepsTheWideDarkToolbar() = checkTyping("dark", narrow = false)

    /** Large RTL text retains the separate navigation row and its editor bounds on every edit frame. */
    @Test
    fun typingKeepsTheNarrowLargeRtlControlsRow() = checkTyping("large_rtl", narrow = true, rtl = true)

    /** AMOLED contrast and toolbar space remain stable across per-character edits. */
    @Test
    fun typingKeepsTheWideAmoledToolbar() = checkTyping("amoled", narrow = false)

    /** Updating an IME composing range and its characters retains the already settled row. */
    @Test
    fun composingUpdatesKeepTheSettledControlsRow() {
        render("light", narrow = true, rtl = false)
        val fieldBounds = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot
        val topBounds = composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).fetchSemanticsNode().boundsInRoot
        composeRule.mainClock.autoAdvance = false
        try {
            for (suffix in listOf("ab", "abc", "ab")) {
                composeRule.runOnUiThread {
                    val text = draft.dropLast(1) + suffix
                    value =
                        TextFieldValue(
                            text,
                            TextRange(text.length),
                            TextRange(text.length - suffix.length, text.length),
                        )
                }
                assertStableDraftTopFrames(composeRule, { value }, { sends }, DraftTopBounds(fieldBounds, topBounds)) {
                    assertEquals(48.dp, extraHeight)
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** A soft wrap inside a fixed manual viewport keeps the control and row on every frame. */
    @Test
    fun wrappingChangeKeepsFixedViewportNavigation() {
        render("light", narrow = true, rtl = false)
        val previousText = prepareAtSoftWrapBoundary()
        val beforeLines = measuredLineCount()
        val bounds = currentBounds()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(hasSetTextAction()).performTextInput("W")
            assertStableRenderedHeightFrames(bounds)
            assertTrue("one character must cross a real soft-wrap boundary", measuredLineCount() > beforeLines)
            assertEquals(previousText.count { it == '\n' }, value.text.count { it == '\n' })
            composeRule.onNode(hasSetTextAction()).performTextReplacement(previousText)
            assertStableRenderedHeightFrames(bounds)
            assertEquals(beforeLines, measuredLineCount())
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** An explicit newline must not remove the toolbar when a manually sized editor stays unchanged. */
    @Test
    fun lineBreakKeepsTheManualControlsRow() = checkLineBreak(ComposerExpansionMode.Manual, "manual")

    /** Fullscreen draft reading has fixed geometry even while the natural draft gains another line. */
    @Test
    fun lineBreakKeepsTheFullscreenControlsRow() = checkLineBreak(ComposerExpansionMode.FullScreen, "fullscreen")

    /** Once automatic growth is capped, new lines keep navigation and its narrow row continuously mounted. */
    @Test
    fun lineBreakKeepsTheCappedAutomaticControlsRow() = checkLineBreak(ComposerExpansionMode.Automatic, "capped")

    /** Uncapped automatic growth still suppresses navigation on every genuinely changing height frame. */
    @Test
    fun lineBreakGrowthStillWaitsForRenderedGeometry() {
        value = TextFieldValue("a", TextRange(1))
        render("light", narrow = true, rtl = false, mode = ComposerExpansionMode.Automatic, expectTop = false)
        val before = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(hasSetTextAction()).performTextInput("\na")
            repeat(COMPOSER_EXPANSION_ANIMATION_MILLIS / FRAME_STEP_MS) { frame ->
                assertNoDraftTopOnNextFrame()
                if (frame == 0) {
                    composeRule
                        .onNodeWithTag(TAG)
                        .captureRoboImage("src/test/snapshots/composer_typing_stable_growing_line_break_light.png")
                }
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        composeRule.waitForIdle()
        assertTrue(composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot.height > before)
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
    }

    /** Checks a newline, its next character and deletion throughout the unused natural-height animation. */
    private fun checkLineBreak(
        mode: ComposerExpansionMode,
        name: String,
    ) {
        render("light", narrow = true, rtl = false, mode = mode)
        val previous = value.text
        val bounds = currentBounds()
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.onNode(hasSetTextAction()).performTextInput("\n")
            assertStableRenderedHeightFrames(bounds) {
                composeRule
                    .onNodeWithTag(TAG)
                    .captureRoboImage("src/test/snapshots/composer_typing_stable_${name}_line_break_light.png")
            }
            composeRule.onNode(hasSetTextAction()).performTextInput("a")
            assertStableRenderedHeightFrames(bounds)
            composeRule.onNode(hasSetTextAction()).performTextReplacement(previous)
            assertStableRenderedHeightFrames(bounds)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** Captures exact editor/action bounds before an edit that should leave the rendered geometry unchanged. */
    private fun currentBounds(): DraftTopBounds =
        DraftTopBounds(
            composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot,
            composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).fetchSemanticsNode().boundsInRoot,
        )

    /** Uses real font metrics to choose the last single-line suffix before the next character wraps. */
    private fun prepareAtSoftWrapBoundary(): String {
        val lines = measuredLineCount()
        var previous = value.text
        for (length in 1..32) {
            composeRule.runOnUiThread {
                val text = draft.dropLast(1) + "W".repeat(length)
                value = TextFieldValue(text, TextRange(text.length))
            }
            composeRule.waitForIdle()
            if (measuredLineCount() > lines) {
                composeRule.runOnUiThread { value = TextFieldValue(previous, TextRange(previous.length)) }
                composeRule.waitForIdle()
                return previous
            }
            previous = value.text
        }
        error("fixture failed to find a soft-wrap boundary")
    }

    /** Reads the editor's actual layout, rather than assuming a fixed glyph width or synthetic wrap count. */
    private fun measuredLineCount(): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNode(hasSetTextAction()).performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            it(layouts)
        }
        return layouts.single().lineCount
    }

    /** Natural-height changes are irrelevant when the actual viewport is fixed or already height-capped. */
    private fun assertStableRenderedHeightFrames(
        bounds: DraftTopBounds,
        firstFrame: () -> Unit = {},
    ) {
        var captured = false
        repeat(COMPOSER_EXPANSION_ANIMATION_MILLIS / (FRAME_STEP_MS * 4)) {
            assertStableDraftTopFrames(composeRule, { value }, { sends }, bounds) {
                assertEquals(48.dp, extraHeight)
                if (!captured) {
                    firstFrame()
                    captured = true
                }
            }
        }
    }

    /** Admits navigation only after the complete animation and its two stable measurement frames. */
    private fun settleNavigation() {
        composeRule.mainClock.advanceTimeBy(COMPOSER_EXPANSION_ANIMATION_MILLIS.toLong())
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertIsDisplayed()
        assertEquals(48.dp, extraHeight)
    }

    /** A previous owner's delayed settling coroutine cannot restore controls over a new unscrolled draft. */
    @Test
    fun ownerChangeRejectsPendingSettlement() {
        render("light", narrow = true, rtl = false)
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { transitionActive = true }
            assertNoDraftTopOnNextFrame()
            composeRule.runOnUiThread {
                owner++
                value = TextFieldValue("Short", TextRange(5))
                transitionActive = false
            }
            repeat(8) { assertNoDraftTopOnNextFrame() }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
        assertEquals("Short", value.text)
        assertEquals(0, sends)
    }

    /** Identical content still gets a new false admission state before the new owner can settle. */
    @Test
    fun identicalDraftOwnerCannotReusePreviousAdmission() {
        render("light", narrow = true, rtl = false)
        val previous = value
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.runOnUiThread { owner++ }
            assertNoDraftTopOnNextFrame()
            assertEquals(previous, value)
            settleNavigation()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    /** Tests absence and row-space release after each separately rendered transition frame. */
    private fun assertNoDraftTopOnNextFrame() {
        composeRule.runOnUiThread { Snapshot.sendApplyNotifications() }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG).assertDoesNotExist()
        assertEquals(0.dp, extraHeight)
    }

    /** Captures the first intermediate typing frame, and checks every subsequent insertion/deletion frame. */
    private fun checkTyping(
        theme: String,
        narrow: Boolean,
        rtl: Boolean = false,
    ) {
        render(theme, narrow, rtl)
        val expectedHeight = if (narrow) 48.dp else 0.dp
        assertEquals(expectedHeight, extraHeight)
        var captured = false
        assertDraftTopStableDuringTextEdits(composeRule, { value }, { sends }) {
            assertEquals(expectedHeight, extraHeight)
            if (!captured) {
                composeRule.onNodeWithTag(TAG).captureRoboImage("src/test/snapshots/composer_typing_stable_$theme.png")
                captured = true
            }
        }
    }

    /** Exercises the production pill with active dictation so a narrow window requires a separate navigation row. */
    private fun render(
        theme: String,
        narrow: Boolean,
        rtl: Boolean,
        mode: ComposerExpansionMode = ComposerExpansionMode.Manual,
        expectTop: Boolean = true,
    ) {
        val width = if (narrow) 240.dp else 360.dp
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, if (rtl) 2f else 1f),
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(darkTheme = theme != "light", amoled = theme == "amoled") {
                    Surface {
                        Box(Modifier.width(width).height(260.dp).testTag(TAG)) {
                            ComposerPill(
                                textFieldValue = value,
                                composerFocus = focusRequester,
                                emojiPickerOpen = false,
                                onValueChange = { value = it },
                                onEmojiPickerToggle = {},
                                onAttachmentsToggle = {},
                                attachmentSheetOpen = false,
                                onPickFromGallery = {},
                                onPickDocument = null,
                                actionColors = accountActionColors(appState = null),
                                dictationControls = { Box(Modifier.width(168.dp).height(40.dp)) },
                                onImeSend = { sends++ },
                                expansionMode = mode,
                                scrollOwnerKey = owner,
                                geometryTransitionActive = transitionActive,
                                compactMeasurementWidth = width,
                                onExtraControlsHeightChanged = { extraHeight = it },
                                modifier = Modifier.height(200.dp),
                            )
                        }
                    }
                }
            }
        }
        composeRule.runOnIdle { focusRequester.requestFocus() }
        composeRule.waitForIdle()
        val top = composeRule.onNodeWithTag(COMPOSER_DRAFT_TOP_TAG)
        if (expectTop) top.assertIsDisplayed() else top.assertDoesNotExist()
    }

    private companion object {
        const val FRAME_STEP_MS = 16
        const val TAG = "composer-typing-stability"
    }
}
