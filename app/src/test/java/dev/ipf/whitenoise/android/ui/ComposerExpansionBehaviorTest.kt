package dev.ipf.whitenoise.android.ui

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationPlatform
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionListener
import dev.ipf.whitenoise.android.audio.ConversationDictationRecognitionSession
import dev.ipf.whitenoise.android.audio.ConversationDictationTimeoutHandle
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_PILL_SURFACE_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.COMPOSER_RESIZE_INDICATOR_TAG
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerBar
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerOverlayBackRegistrar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h780dp-mdpi")
class ComposerExpansionBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun expandAndCollapseKeepTheSameEditorDraft() {
        val draft = longDraft()
        render(draft)
        val automaticHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height

        resizeHandle().performClick()
        composeRule.waitForIdle()

        val fullHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue("full screen should be materially taller than auto-grow", fullHeight > automaticHeight * 1.5f)
        composeRule.onNodeWithText(draft).assertExists()

        resizeHandle().performClick()
        composeRule.waitForIdle()

        val collapsedHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue(
            "collapse should return to the measured auto-grow height",
            abs(collapsedHeight - automaticHeight) <= 1f,
        )
        composeRule.onNodeWithText(draft).assertExists()
    }

    @Test
    fun tapFullScreenAndCollapseAnimateHeightMonotonically() {
        val draft = longDraft()
        val selection = TextRange(draft.indexOf("controls"))
        render(draft)
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        editor.performTextInputSelection(selection)
        editor.assertIsFocused()
        val automaticHeight = composerHeight()

        val expansionFrames =
            withManualClock {
                resizeHandle().performClick()
                composeRule.runOnIdle { }
                sampleComposerHeights()
            }
        composeRule.waitForIdle()
        val fullHeight = composerHeight()

        assertMonotonic(expansionFrames + fullHeight, increasing = true)
        assertTrue(
            "tap-to-full-screen must expose an intermediate animated height",
            expansionFrames.any { it > automaticHeight + 1f && it < fullHeight - 1f },
        )
        assertEditorState(editor, draft, selection)

        val collapseFrames =
            withManualClock {
                resizeHandle().performClick()
                composeRule.runOnIdle { }
                sampleComposerHeights()
            }
        composeRule.waitForIdle()
        val collapsedHeight = composerHeight()

        assertMonotonic(collapseFrames + collapsedHeight, increasing = false)
        assertTrue(
            "full-screen collapse must expose an intermediate animated height",
            collapseFrames.any { it < fullHeight - 1f && it > collapsedHeight + 1f },
        )
        assertTrue("collapse should return to automatic height", abs(collapsedHeight - automaticHeight) <= 1f)
        assertEditorState(editor, draft, selection)
    }

    @Test
    fun multilineControlsShareTheBottomEdgeInReadingOrder() {
        render(longDraft())

        val emoji = composerControlBounds(R.string.open_emoji_picker)
        val attach = composerControlBounds(R.string.attach_options)
        val send = composerControlBounds(R.string.send)
        val resize = composerControlBounds(R.string.composer_resize)

        assertTrue(emoji.center.x < attach.center.x)
        assertTrue(attach.center.x < send.center.x)
        assertTrue(abs(emoji.bottom - send.bottom) <= 4f)
        assertTrue(abs(attach.bottom - send.bottom) <= 4f)
        // The semantics and gesture layer meets the accessibility target while
        // the visual handle remains in the compact 36dp top inset.
        assertTrue("resize handle should meet the 48dp touch minimum", resize.height >= 48f)
        assertTrue("resize handle should stay wide", resize.width >= 96f)
        assertResizeHandleToggleLabel(R.string.composer_expand_full_screen)
    }

    @Test
    fun visibleResizeIndicatorIsCenteredInsideItsUnchangedTouchTarget() {
        render(longDraft())

        val target = composerControlBounds(R.string.composer_resize)
        val indicator =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_INDICATOR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val topGap = indicator.top - target.top
        val bottomGap = target.bottom - indicator.bottom

        assertTrue("visible handle breathing room should be balanced", abs(topGap - bottomGap) <= 1f)
        assertTrue("resize target must retain its 48dp minimum", target.height >= 48f)
        assertTrue("resize target must retain its 96dp width", target.width >= 96f)
    }

    @Test
    fun resizeTargetStraddlesThePillBorderWithoutOverlappingTheFirstLine() {
        val draft = longDraft()
        render(draft)

        val target = composerControlBounds(R.string.composer_resize)
        val surface =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val editor = composeRule.onNodeWithText(draft).fetchSemanticsNode().boundsInRoot

        assertTrue("resize target must extend above the pill", target.top < surface.top)
        assertTrue("resize target must extend into the pill", target.bottom > surface.top)
        assertTrue("the visible handle center must sit on the pill border", abs(target.center.y - surface.top) <= 1f)
        assertTrue("expanded editor inset must be materially smaller than 48dp", editor.top - surface.top < 32f)
        assertTrue("first-line gestures must start below the resize target", editor.top >= target.bottom - 1f)
    }

    @Test
    fun twoLinesKeepTheExistingCompactComposer() {
        render("First line\nSecond line")

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.composer_resize))
            .assertDoesNotExist()
    }

    @Test
    fun lineThresholdsAnimateMonotonicallyAndPreserveFocusDraftAndSelection() {
        val twoLines = "First line\nSecond line"
        val threeLines = "$twoLines\nThird line"
        render(twoLines)
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        editor.assertIsFocused()
        val compactHeight = composerHeight()

        val growthFrames =
            withManualClock {
                editor.performTextInputSelection(TextRange(twoLines.length))
                editor.performTextInput("\nThird line")
                composeRule.runOnIdle { }
                resizeHandle().assertDoesNotExist()
                sampleComposerHeights()
            }
        composeRule.waitForIdle()
        val expandedHeight = composerHeight()

        assertMonotonic(growthFrames + expandedHeight, increasing = true)
        assertTrue("three-line activation must grow over multiple frames", expandedHeight > compactHeight + 16f)
        assertTrue(
            "three-line activation must expose an intermediate animated height",
            growthFrames.any { it > compactHeight + 1f && it < expandedHeight - 1f },
        )
        resizeHandle().assertExists()
        editor.assertIsFocused()
        assertEquals(threeLines, editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(
            TextRange(threeLines.length),
            editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )

        editor.performTextReplacement(twoLines)
        composeRule.waitForIdle()
        resizeHandle().assertExists()
        val expandedTwoLineHeight = composerHeight()

        val shrinkFrames =
            withManualClock {
                editor.performTextReplacement("First line")
                composeRule.runOnIdle { }
                sampleComposerHeights()
            }
        composeRule.waitForIdle()
        val collapsedHeight = composerHeight()

        assertMonotonic(shrinkFrames + collapsedHeight, increasing = false)
        assertTrue(
            "one-line deactivation must shrink over multiple frames",
            collapsedHeight < expandedTwoLineHeight - 16f,
        )
        assertTrue(
            "one-line deactivation must expose an intermediate animated height",
            shrinkFrames.any { it < expandedTwoLineHeight - 1f && it > collapsedHeight + 1f },
        )
        resizeHandle().assertDoesNotExist()
        editor.assertIsFocused()
        assertEquals("First line", editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(
            TextRange("First line".length),
            editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange],
        )
    }

    @Test
    fun thresholdDraftWithDictationAndAttachmentsSettlesWithoutLayoutOscillation() {
        val draft = "#938 close as done, win obtained"

        render(
            draft = draft,
            dictationController = idleDictationController(TextFieldValue(draft)),
            width = 300,
        )

        resizeHandle().assertExists()
        val settledHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height

        repeat(6) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()

        resizeHandle().assertExists()
        val finalHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue("the unchanged draft must keep one composer height", abs(finalHeight - settledHeight) <= 1f)
    }

    @Test
    fun deletingAnAutomaticLongDraftBackToOneLineRestoresCompactControls() {
        val draft = longDraft()
        render(draft)
        resizeHandle().assertExists()

        composeRule.onNodeWithText(draft).performTextReplacement("Short draft")
        composeRule.waitForIdle()

        resizeHandle().assertDoesNotExist()
    }

    @Test
    fun automaticGrowthStopsNearHalfOfTheAvailableViewport() {
        render((1..40).joinToString("\n") { "Draft line $it" })

        val height =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        // This mdpi test renders inside a 720dp-tall Surface, so the automatic
        // half-viewport ceiling is 360px; 300px proves the long draft grew.
        assertTrue("a long draft should grow well beyond the compact composer", height >= 300f)
        assertTrue("automatic growth should preserve roughly half the viewport", height <= 360f)
    }

    @Test
    fun dragHandleContinuouslyAddsTheDraggedDistance() {
        render(longDraft())
        val initialHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height

        composeRule
            .onNodeWithContentDescription(app.getString(R.string.composer_resize))
            .performTouchInput {
                val start = center
                swipe(start, Offset(start.x, start.y - 96f), durationMillis = 320)
            }
        composeRule.waitForIdle()

        val draggedHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue("upward drag should grow the composer", draggedHeight > initialHeight + 64f)
        assertTrue("a short drag must not jump directly to full screen", draggedHeight < initialHeight + 140f)
        composeRule.onNodeWithText(longDraft()).assertExists()
    }

    @Test
    fun firstLineCenterTapTargetsTheEditorWithoutChangingExpansion() {
        val draft = longDraft()
        render(draft)
        val editor = composeRule.onNodeWithText(draft)
        val resizeBounds = composerControlBounds(R.string.composer_resize)
        val editorBounds = editor.fetchSemanticsNode().boundsInRoot
        val initialHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height

        assertTrue("editor must start below the resize target", editorBounds.top >= resizeBounds.bottom)
        editor.performClick()
        editor.assertIsFocused()
        editor.performTouchInput { click(Offset(width / 2f, 8f)) }
        composeRule.waitForIdle()

        editor.assertIsFocused()
        assertResizeHandleToggleLabel(R.string.composer_expand_full_screen)
        val finalHeight =
            composeRule
                .onNodeWithTag(TAG)
                .fetchSemanticsNode()
                .boundsInRoot.height
        assertTrue("editor tap must not resize the composer", abs(finalHeight - initialHeight) <= 1f)
    }

    @Test
    fun backFromFocusedFullScreenCollapsesAndClearsFocus() {
        var overlayCallback: OnBackInvokedCallback? = null
        var overlayPriority: Int? = null
        val registrar =
            ComposerOverlayBackRegistrar { priority, callback ->
                overlayPriority = priority
                overlayCallback = callback
                { if (overlayCallback === callback) overlayCallback = null }
            }
        val draft = longDraft()
        render(draft, registrar)
        val editor = composeRule.onNodeWithText(draft)

        editor.performClick()
        editor.assertIsFocused()
        resizeHandle().performClick()
        composeRule.waitForIdle()
        assertResizeHandleToggleLabel(R.string.composer_collapse)
        assertEquals(OnBackInvokedDispatcher.PRIORITY_OVERLAY, overlayPriority)

        composeRule.runOnIdle { checkNotNull(overlayCallback).onBackInvoked() }
        composeRule.waitForIdle()

        assertResizeHandleToggleLabel(R.string.composer_expand_full_screen)
        assertNull("losing focus must release the overlay callback", overlayCallback)
        editor.assertIsNotFocused()
    }

    private fun render(
        draft: String,
        overlayBackRegistrar: ComposerOverlayBackRegistrar? = null,
        dictationController: ConversationDictationController? = null,
        width: Int = 360,
    ) {
        var value by mutableStateOf(TextFieldValue(draft))
        composeRule.setContent {
            WhiteNoiseTheme {
                Surface(modifier = Modifier.width(width.dp).height(720.dp)) {
                    Box {
                        ComposerBar(
                            replyingTo = null,
                            messageTextCopy = MessageTextCopy.Default,
                            onCancelReply = {},
                            onSend = { _, _ -> },
                            onPickFromGallery = {},
                            onPickDocument = {},
                            dictationController = dictationController,
                            dictationAccountRef = dictationController?.let { ACCOUNT },
                            dictationGroupIdHex = dictationController?.let { GROUP },
                            initialDraft = value,
                            onDraftChange = { value = it },
                            overlayBackRegistrar = overlayBackRegistrar,
                            modifier = Modifier.testTag(TAG),
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Suppress("MaxLineLength")
    private fun idleDictationController(draft: TextFieldValue): ConversationDictationController =
        ConversationDictationController(
            platform =
                object : ConversationDictationPlatform {
                    override fun hasRecordAudioPermission() = true

                    override fun recognitionAvailable() = true

                    override fun createSession(listener: ConversationDictationRecognitionListener): ConversationDictationRecognitionSession =
                        object : ConversationDictationRecognitionSession {
                            override fun start() = Unit

                            override fun stop() = Unit

                            override fun cancel() = Unit

                            override fun destroy() = Unit
                        }
                },
            readDraft = { _, _ -> ConversationDictationDraftSnapshot(draft, 0L) },
            writeDraft = { _, _, _, _ -> true },
            disclosureAccepted = { true },
            markDisclosureAccepted = {},
            scheduleTimeout = { _, _ -> ConversationDictationTimeoutHandle {} },
            elapsedRealtime = { 0L },
        )

    private fun resizeHandle() = composeRule.onNodeWithContentDescription(app.getString(R.string.composer_resize))

    private fun composerHeight() =
        composeRule
            .onNodeWithTag(TAG)
            .fetchSemanticsNode()
            .boundsInRoot.height

    private fun sampleComposerHeights(frameCount: Int = 20): List<Float> =
        buildList {
            repeat(frameCount) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle { }
                add(composerHeight())
            }
        }

    private fun <T> withManualClock(block: () -> T): T {
        composeRule.mainClock.autoAdvance = false
        return try {
            block()
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    private fun assertMonotonic(
        heights: List<Float>,
        increasing: Boolean,
    ) {
        heights.zipWithNext().forEach { (before, after) ->
            if (increasing) {
                assertTrue("composer height must not reverse while expanding: $before -> $after", after >= before - 1f)
            } else {
                assertTrue("composer height must not reverse while collapsing: $before -> $after", after <= before + 1f)
            }
        }
    }

    private fun assertEditorState(
        editor: androidx.compose.ui.test.SemanticsNodeInteraction,
        draft: String,
        selection: TextRange,
    ) {
        editor.assertIsFocused()
        assertEquals(draft, editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(selection, editor.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    // The handle is one control for both resize paths; its current tap
    // outcome (expand vs collapse) is exposed as the click action's label.
    private fun assertResizeHandleToggleLabel(labelRes: Int) {
        val label =
            resizeHandle()
                .fetchSemanticsNode()
                .config[SemanticsActions.OnClick]
                .label
        assertEquals(app.getString(labelRes), label)
    }

    private fun composerControlBounds(contentDescriptionRes: Int) =
        composeRule
            .onNodeWithContentDescription(app.getString(contentDescriptionRes))
            .fetchSemanticsNode()
            .boundsInRoot

    private fun longDraft(): String =
        "A thoughtful long message starts here.\n" +
            "It keeps growing naturally line by line.\n" +
            "The controls remain easy to reach.\n" +
            "Nothing in the draft is replaced.\n" +
            "The final paragraph stays visible while editing."

    private companion object {
        const val TAG = "expandable-composer"
        const val ACCOUNT = "account"
        const val GROUP = "group"
    }
}
