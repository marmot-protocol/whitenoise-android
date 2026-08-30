package dev.ipf.whitenoise.android.ui

import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalLayoutDirection
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
@Suppress("LargeClass")
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
        val automaticBounds = composerBounds()
        val automaticHeight = automaticBounds.height
        val fixedBottom = automaticBounds.bottom

        val expansionFrames =
            withManualClock {
                resizeHandle().performClick()
                composeRule.runOnIdle { }
                sampleComposerHeights(expectedBottom = fixedBottom)
            }
        composeRule.waitForIdle()
        val fullHeight = composerHeight()
        assertComposerBottom(fixedBottom)

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
                sampleComposerHeights(expectedBottom = fixedBottom)
            }
        composeRule.waitForIdle()
        val collapsedHeight = composerHeight()
        assertComposerBottom(fixedBottom)

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
    fun resizeHandleIsCenteredOnThePillBorderAndTheTargetStopsAtTheFirstLine() {
        val draft = longDraft()
        render(draft)

        val target = composerControlBounds(R.string.composer_resize)
        val surface =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val editor = composeRule.onNodeWithText(draft).fetchSemanticsNode().boundsInRoot
        val indicator =
            composeRule
                .onNodeWithTag(COMPOSER_RESIZE_INDICATOR_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot

        assertTrue("resize target must extend above the pill", target.top < surface.top)
        assertTrue("resize target must extend into the pill", target.bottom > surface.top)
        assertTrue(
            "the visible handle must be centered on the pill border",
            abs(indicator.center.y - surface.top) <= 1f,
        )
        assertTrue("expanded editor top inset should match half the target", abs(editor.top - surface.top - 24f) <= 1f)
        assertTrue("first-line gestures must start below the resize target", editor.top >= target.bottom - 1f)
    }

    @Test
    fun expandedSendKeepsCornerSafeInsetInLtr() {
        render(longDraft())

        assertExpandedSendInset(LayoutDirection.Ltr)
    }

    @Test
    fun expandedSendKeepsCornerSafeInsetInRtl() {
        render(longDraft(), layoutDirection = LayoutDirection.Rtl)

        assertExpandedSendInset(LayoutDirection.Rtl)
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
    fun oneToTwoLineGrowthKeepsTextInsideTheComposerOnEveryFrame() {
        val oneLine = "First line"
        val twoLines = "$oneLine\nSecond line"
        render(oneLine)
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        val oneLineGeometry = composerGeometry()
        val fixedBottom = oneLineGeometry.composer.bottom

        val frames =
            withManualClock {
                editor.performTextReplacement(twoLines)
                buildList {
                    repeat(4) {
                        composeRule.mainClock.advanceTimeByFrame()
                        composeRule.runOnIdle { }
                        assertEquals(
                            twoLines,
                            editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
                        )
                        add(composerGeometry())
                    }
                }
            }

        frames.forEach { geometry ->
            assertTextFitsInsidePill(geometry)
            assertTrue(
                "natural text growth must preserve the composer bottom edge",
                abs(geometry.composer.bottom - fixedBottom) <= 1f,
            )
        }
        val allocatedHeight = frames.first().composer.height
        assertTrue(
            "two text lines must receive more composer height immediately",
            allocatedHeight > oneLineGeometry.composer.height + 10f,
        )
        assertTrue(
            "the editor viewport must expose the newly added line immediately",
            frames.first().editor.height > oneLineGeometry.editor.height + 10f,
        )
    }

    @Test
    fun bulkReplacementKeepsEveryFrameVisibleAndHeightMonotonic() {
        val initialDraft = "Short draft"
        val replacement = (1..24).joinToString("\n") { "Bulk line $it remains visible" }
        render(initialDraft)
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        val initialGeometry = composerGeometry()

        val frames =
            withManualClock {
                editor.performTextReplacement(replacement)
                buildList {
                    repeat(20) {
                        composeRule.mainClock.advanceTimeByFrame()
                        composeRule.runOnIdle { }
                        assertEquals(
                            replacement,
                            editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
                        )
                        add(composerGeometry())
                    }
                }
            }
        composeRule.waitForIdle()
        val allFrames = listOf(initialGeometry) + frames + composerGeometry()

        allFrames.drop(1).forEach { geometry ->
            assertTrue("bulk replacement must retain non-zero composer bounds", geometry.composer.height > 0f)
            assertTrue("bulk replacement must retain non-zero editor bounds", geometry.editor.height > 0f)
            assertTextFitsInsidePill(geometry)
        }
        assertMonotonic(allFrames.map { it.composer.height }, increasing = true)
        assertEditorState(editor, replacement, TextRange(replacement.length))
    }

    @Test
    fun supersedingBulkReplacementCannotRestoreStaleExpandedMode() {
        val initialDraft = "Short draft"
        val staleReplacement = (1..24).joinToString("\n") { "Stale bulk line $it" }
        val committedReplacement = "Newest short draft"
        render(initialDraft)
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()

        withManualClock {
            editor.performTextReplacement(staleReplacement)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.runOnIdle { }
            editor.performTextReplacement(committedReplacement)
            repeat(20) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle { }
                assertEquals(
                    committedReplacement,
                    editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
                )
                assertTrue("superseding edit must keep the editor visible", composerGeometry().editor.height > 0f)
            }
        }
        composeRule.waitForIdle()

        resizeHandle().assertDoesNotExist()
        assertEditorState(editor, committedReplacement, TextRange(committedReplacement.length))
    }

    @Test
    fun wideThresholdKeepsEditorAndPillAnchoredOnEveryFrame() {
        val twoLines = "First line\nSecond line"
        render(twoLines)
        val editor = composeRule.onNode(hasSetTextAction())
        editor.performClick()
        val compact = composerGeometry()

        val frames =
            withManualClock {
                editor.performTextInputSelection(TextRange(twoLines.length))
                editor.performTextInput("\nThird line")
                buildList {
                    repeat(20) {
                        composeRule.mainClock.advanceTimeByFrame()
                        composeRule.runOnIdle { }
                        assertEquals(
                            "$twoLines\nThird line",
                            editor.fetchSemanticsNode().config[SemanticsProperties.EditableText].text,
                        )
                        add(composerGeometry())
                    }
                }
            }
        composeRule.waitForIdle()
        val allFrames = listOf(compact) + frames + composerGeometry()

        allFrames.forEach { geometry ->
            assertTextFitsInsidePill(geometry)
            assertTrue(
                "wide transition must keep the pill's leading edge fixed",
                abs(geometry.pill.left - compact.pill.left) <= 1f,
            )
            assertTrue(
                "wide transition must not slide the editor laterally",
                abs(geometry.editor.left - compact.editor.left) <= 1f,
            )
            assertTrue(
                "wide transition must preserve the composer bottom edge",
                abs(geometry.composer.bottom - compact.composer.bottom) <= 1f,
            )
        }
        assertMonotonic(allFrames.map { it.composer.height }, increasing = true)
        assertMonotonic(allFrames.map { it.pill.width }, increasing = true)
        val expandedWidth = allFrames.last().pill.width
        assertTrue(
            "wide transition must expose intermediate pill widths",
            allFrames.any { it.pill.width > compact.pill.width + 1f && it.pill.width < expandedWidth - 1f },
        )
        assertEditorState(editor, "$twoLines\nThird line", TextRange("$twoLines\nThird line".length))
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
        val initialBounds = composerBounds()
        val initialHeight = initialBounds.height

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
        assertComposerBottom(initialBounds.bottom)
        composeRule.onNodeWithText(longDraft()).assertExists()
    }

    @Test
    fun longAutomaticDraftCanBeDraggedDownToAShortManualViewport() {
        val draft = (1..40).joinToString("\n") { "Draft line $it" }
        render(draft)
        val automaticBounds = composerBounds()

        resizeHandle().performTouchInput {
            val start = center
            swipe(start, Offset(start.x, start.y + 220f), durationMillis = 320)
        }
        composeRule.waitForIdle()

        val manualBounds = composerBounds()
        assertTrue(
            "manual resize must shrink independently of natural draft height",
            manualBounds.height < automaticBounds.height - 140f,
        )
        assertTrue(
            "manual viewport must retain a usable one-line minimum",
            manualBounds.height >= 140f,
        )
        assertTrue(
            "manual resize must preserve the anchored bottom edge",
            abs(manualBounds.bottom - automaticBounds.bottom) <= 1f,
        )
        composeRule.onNodeWithText(draft).assertExists()
    }

    @Test
    fun fullScreenHandleStaysBelowAndDoesNotActivateTheTopBar() {
        var topBarClicks = 0
        render(
            draft = longDraft(),
            topInteractionClearance = 64.dp,
            onTopBarClick = { topBarClicks += 1 },
        )

        resizeHandle().performClick()
        composeRule.waitForIdle()
        val fullScreenHandle = composerControlBounds(R.string.composer_resize)
        assertTrue("full-screen resize target must stay below top-bar interactions", fullScreenHandle.top >= 64f)

        resizeHandle().performTouchInput { click(center) }
        composeRule.waitForIdle()

        assertEquals("resize handle tap must not reach the top bar", 0, topBarClicks)
        assertResizeHandleToggleLabel(R.string.composer_expand_full_screen)
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
        topInteractionClearance: Dp = 0.dp,
        onTopBarClick: (() -> Unit)? = null,
        layoutDirection: LayoutDirection = LayoutDirection.Ltr,
    ) {
        var value by mutableStateOf(TextFieldValue(draft))
        composeRule.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                WhiteNoiseTheme {
                    Surface(modifier = Modifier.width(width.dp).height(720.dp)) {
                        Box(contentAlignment = Alignment.BottomCenter) {
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
                                topInteractionClearance = topInteractionClearance,
                                modifier = Modifier.testTag(TAG),
                            )
                            if (onTopBarClick != null) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopCenter)
                                        .fillMaxWidth()
                                        .height(topInteractionClearance)
                                        .clickable(onClick = onTopBarClick),
                                )
                            }
                        }
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

    private fun composerHeight() = composerBounds().height

    private fun composerBounds(): Rect =
        composeRule
            .onNodeWithTag(TAG)
            .fetchSemanticsNode()
            .boundsInRoot

    private fun composerGeometry() =
        ComposerGeometry(
            composer = composerBounds(),
            pill =
                composeRule
                    .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                    .fetchSemanticsNode()
                    .boundsInRoot,
            editor = composeRule.onNode(hasSetTextAction()).fetchSemanticsNode().boundsInRoot,
        )

    private fun assertTextFitsInsidePill(geometry: ComposerGeometry) {
        assertTrue(
            "editor must stay inside the pill's leading edge",
            geometry.editor.left >= geometry.pill.left - 1f,
        )
        assertTrue(
            "editor must stay inside the pill's trailing edge",
            geometry.editor.right <= geometry.pill.right + 1f,
        )
        assertTrue(
            "editor must stay inside the pill's top edge",
            geometry.editor.top >= geometry.pill.top - 1f,
        )
        assertTrue(
            "editor must stay inside the pill's bottom edge",
            geometry.editor.bottom <= geometry.pill.bottom + 1f,
        )
    }

    private fun assertExpandedSendInset(layoutDirection: LayoutDirection) {
        val surface =
            composeRule
                .onNodeWithTag(COMPOSER_PILL_SURFACE_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        val send = composerControlBounds(R.string.send)

        assertTrue("Send must keep at least a 48dp semantics target", send.width >= 48f && send.height >= 48f)
        assertTrue("Send must keep bottom breathing room", surface.bottom - send.bottom >= 4f)
        if (layoutDirection == LayoutDirection.Ltr) {
            assertTrue("LTR Send must keep end breathing room", surface.right - send.right >= 4f)
        } else {
            assertTrue("RTL Send must keep end breathing room", send.left - surface.left >= 4f)
        }
    }

    private fun sampleComposerHeights(
        frameCount: Int = 20,
        expectedBottom: Float? = null,
    ): List<Float> =
        buildList {
            repeat(frameCount) {
                composeRule.mainClock.advanceTimeByFrame()
                composeRule.runOnIdle { }
                val bounds = composerBounds()
                expectedBottom?.let { bottom ->
                    assertTrue(
                        "composer bottom must remain anchored during resize: $bottom -> ${bounds.bottom}",
                        abs(bounds.bottom - bottom) <= 1f,
                    )
                }
                add(bounds.height)
            }
        }

    private fun assertComposerBottom(expectedBottom: Float) {
        val actualBottom = composerBounds().bottom
        assertTrue(
            "composer bottom must remain anchored: $expectedBottom -> $actualBottom",
            abs(actualBottom - expectedBottom) <= 1f,
        )
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

    private data class ComposerGeometry(
        val composer: Rect,
        val pill: Rect,
        val editor: Rect,
    )

    private companion object {
        const val TAG = "expandable-composer"
        const val ACCOUNT = "account"
        const val GROUP = "group"
    }
}
