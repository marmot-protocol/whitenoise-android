package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.window.OnBackInvokedCallback
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController
import dev.ipf.whitenoise.android.core.MessageTextCopy
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerToolbarLayout
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ExplanationHost
import dev.ipf.whitenoise.android.ui.settings.rememberComposerSettingsEditState
import dev.ipf.whitenoise.android.ui.share.emptyAppState
import dev.ipf.whitenoise.android.ui.share.testAccount
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/** Exercises the actual app-state-backed Bar, not the legacy isolated Pill fixture path. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class ComposerToolbarProductionTest {
    @get:Rule val composeRule = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private var cameraCalls = 0
    private var galleryCalls = 0
    private var fileCalls = 0
    private var identity = false
    private var attachments = true
    private var extraInventory = false
    private var pollOnly = false
    private val extraCalls = mutableMapOf<String, Int>()
    private lateinit var appState: WhiteNoiseAppState
    private lateinit var text: ComposerTextState
    private var focused = false
    private var keyboardHides = 0
    private var back: OnBackInvokedCallback? = null

    // Stands in for the conversation's full-screen route, which replaces the composer while open.
    private var customizing by mutableStateOf(false)
    private val keyboard =
        object : SoftwareKeyboardController {
            override fun show() = Unit

            override fun hide() {
                keyboardHides++
            }
        }

    @Test
    fun identityBackKeepsKeyboardAndItsRetiredCallbackCannotCloseCustomization() {
        identity = true
        render(
            ComposerToolbarSettings(
                listOf(ComposerShortcut.SendingIdentity, ComposerShortcut.More, ComposerShortcut.Camera),
            ),
            TextFieldValue("keep my draft"),
        )
        composeRule.onNode(hasSetTextAction()).performClick().assertIsFocused()
        val hidesBefore = keyboardHides
        val customize =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.attachment_camera))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.composer_customize) }
        composeRule.onNodeWithTag("composer-sending-identity").performClick()
        composeRule.onNodeWithTag("composer-sending-identity-popup").assertIsDisplayed()
        val retiredBack = checkNotNull(back)
        composeRule.runOnIdle { retiredBack.onBackInvoked() }
        composeRule.onNodeWithTag("composer-sending-identity-popup").assertDoesNotExist()
        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        assertEquals(hidesBefore, keyboardHides)
        assertEquals("keep my draft", text.valueState.value.text)
        composeRule.onNodeWithTag("composer-sending-identity").performClick()
        composeRule.onNodeWithTag("composer-sending-identity-popup").assertIsDisplayed()
        val previousPopupBack = checkNotNull(back)
        composeRule.runOnIdle { assertTrue(customize.action()) }
        composeRule.onNodeWithTag("composer-sending-identity-popup").assertDoesNotExist()
        composeRule.onNodeWithTag("composer.customize").assertIsDisplayed()
        composeRule.runOnIdle { previousPopupBack.onBackInvoked() }
        composeRule.onNodeWithTag("composer.customize").assertIsDisplayed()
        assertEquals("keep my draft", text.valueState.value.text)
    }

    @Test
    fun identityConfirmationAnchorsToThePhysicalSlotInBothDirections() {
        val provider = SendingIdentityPopupPositionProvider(48)
        val anchor = IntRect(100, 300, 136, 348)
        val window = IntSize(360, 780)
        val popup = IntSize(120, 50)
        assertEquals(IntOffset(100, 250), provider.calculatePosition(anchor, window, LayoutDirection.Ltr, popup))
        assertEquals(IntOffset(16, 250), provider.calculatePosition(anchor, window, LayoutDirection.Rtl, popup))
    }

    @Test
    fun directMediaCommandsReuseCallbacksAndLongPressExplainsInsteadOfTapping() {
        render(
            ComposerToolbarSettings(
                listOf(
                    ComposerShortcut.Camera,
                    ComposerShortcut.Gallery,
                    ComposerShortcut.Files,
                    ComposerShortcut.More,
                ),
            ),
        )
        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { click() }
        composeRule.onNodeWithTag("composer.shortcut.gallery").performTouchInput { click() }
        composeRule.onNodeWithTag("composer.shortcut.files").performTouchInput { click() }
        assertEquals(listOf(1, 1, 1), listOf(cameraCalls, galleryCalls, fileCalls))
        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        composeRule.onNodeWithTag("explanation.sheet").assertIsDisplayed()
        composeRule.onNodeWithTag("explanation.action").performClick()
        composeRule.onNodeWithTag("composer.customize").assertIsDisplayed()
        assertEquals(1, cameraCalls)
    }

    @Test
    fun clipboardPasteCanEditTheNextDraftWhileAnAttachmentSendIsPending() {
        identity = true
        var completion: ((Boolean) -> Unit)? = null
        render(
            ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Paste, ComposerShortcut.Camera)),
            attachmentSend = { _, complete -> completion = complete },
        )
        val cameraClick =
            checkNotNull(
                composeRule
                    .onNodeWithContentDescription(context.getString(R.string.attachment_camera))
                    .fetchSemanticsNode()
                    .config[SemanticsActions.OnClick]
                    .action,
            )
        context
            .getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("next draft", "next draft"))
        composeRule.onNodeWithContentDescription(context.getString(R.string.send)).performClick()
        try {
            composeRule.runOnIdle { assertTrue(completion != null) }
            composeRule.onNodeWithTag("composer.paste").performTouchInput { click() }
            composeRule.waitUntil(timeoutMillis = 5_000) { text.valueState.value.text == "next draft" }
            assertEquals("next draft", text.valueState.value.text)
            composeRule.runOnIdle { cameraClick() }
            assertEquals(0, cameraCalls)
        } finally {
            composeRule.runOnIdle { completion?.invoke(false) }
        }
    }

    @Test
    fun pasteLongPressDoesNotReadOrChangeTheDraftAndBackKeepsSavedPreferences() {
        val initial = TextFieldValue("keep this draft", TextRange(5, 9))
        val settings = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Paste))
        render(settings, initial)
        context
            .getSystemService(
                ClipboardManager::class.java,
            ).setPrimaryClip(ClipData.newPlainText("sample", "replacement"))
        composeRule.onNodeWithTag("composer.shortcut.paste").performTouchInput { longClick() }
        assertEquals(initial, text.valueState.value)
        composeRule.onNodeWithTag("explanation.action").performClick()
        composeRule.onNodeWithTag("composer.customize.label.camera").performScrollTo()
        composeRule.onNodeWithTag("composer.customize.label.camera").assertExists()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithTag("composer.customize.done").assertDoesNotExist()
        composeRule.runOnIdle { checkNotNull(back).onBackInvoked() }
        composeRule.onNodeWithTag("composer.customize").assertDoesNotExist()
        assertEquals(initial, text.valueState.value)
        assertEquals(settings, appState.composerToolbarPreference.value)
    }

    @Test
    fun unavailableCommandsRemainSavedAndAlwaysExpandedUsesTheFullWidthFromTheFirstFrame() {
        val settings =
            ComposerToolbarSettings(
                listOf(ComposerShortcut.More, ComposerShortcut.Camera, ComposerShortcut.Poll),
                ComposerToolbarLayout.AlwaysExpanded,
            )
        composeRule.mainClock.autoAdvance = false
        try {
            render(settings)
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.onNodeWithTag("composer.shortcut.poll").assertDoesNotExist()
            assertEquals(settings, appState.composerToolbarPreference.value)
            val field = composeRule.onNodeWithTag(COMPOSER_EDITOR_VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
            val toolbar = composeRule.onNodeWithTag("composer.shortcuts").fetchSemanticsNode().boundsInRoot
            assertTrue("first laid-out frame already places the editor above actions", field.bottom <= toolbar.top + 1f)
            assertTrue("editor keeps usable width", field.width > 250f)
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun alwaysExpandedEndShortcutsHugSendInLtr() = assertEndShortcutsHugSend(LayoutDirection.Ltr)

    @Test
    fun alwaysExpandedEndShortcutsHugSendInRtl() = assertEndShortcutsHugSend(LayoutDirection.Rtl)

    /** The group beside Send ends at Send's own inset; spare row width belongs to the start group. */
    private fun assertEndShortcutsHugSend(direction: LayoutDirection) {
        render(
            ComposerToolbarSettings(
                listOf(
                    ComposerShortcut.More,
                    ComposerShortcut.Gallery,
                    ComposerShortcut.Emoji,
                    ComposerShortcut.Camera,
                ),
                ComposerToolbarLayout.AlwaysExpanded,
                rightShortcuts = setOf(ComposerShortcut.Emoji, ComposerShortcut.Camera),
            ),
            value = TextFieldValue("hello"),
            direction = direction,
        )
        val send =
            composeRule.onNodeWithContentDescription(context.getString(R.string.send)).fetchSemanticsNode().boundsInRoot
        val endGroup =
            if (direction == LayoutDirection.Ltr) listOf("emoji", "camera") else listOf("more", "gallery")
        val bounds =
            endGroup.map { composeRule.onNodeWithTag("composer.shortcut.$it").fetchSemanticsNode().boundsInRoot }
        val gap =
            if (direction == LayoutDirection.Ltr) {
                send.left - bounds.maxOf { it.right }
            } else {
                bounds.minOf { it.left } - send.right
            }
        val limit = with(composeRule.density) { 12.dp.toPx() }
        assertTrue("end shortcuts sit beside Send, gap was ${gap}px", gap in 0f..limit)
    }

    @Test
    fun manyShortcutsWrapWithoutOverlappingTheEditorInRtl() {
        extraInventory = true
        render(
            ComposerToolbarSettings(
                ComposerShortcut.entries,
                ComposerToolbarLayout.AlwaysExpanded,
                rightShortcuts = WRAPPING_RIGHT_SHORTCUTS,
            ),
            direction = LayoutDirection.Rtl,
        )
        val field = composeRule.onNodeWithTag(COMPOSER_EDITOR_VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
        val toolbars = composeRule.onAllNodesWithTag("composer.shortcuts").fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("physical shortcut groups are present", toolbars.isNotEmpty())
        toolbars.forEach { toolbar -> assertTrue(field.bottom <= toolbar.top + 1f) }
        val twoRows = with(composeRule.density) { composerToolbarStackHeight(2).toPx() }
        assertTrue("the populated side wraps its actions", toolbars.any { it.height >= twoRows })
        val camera = composeRule.onNodeWithTag("composer.shortcut.camera").fetchSemanticsNode().boundsInRoot
        assertTrue(camera.width >= 36f && camera.height >= 48f)
    }

    @Test
    fun inlineCustomizationHidesKeyboardAndPreservesTheDraftSelection() {
        render(
            ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera, ComposerShortcut.Paste)),
            TextFieldValue("keep this draft", TextRange(5, 9)),
        )
        composeRule.onNode(hasSetTextAction()).performClick().assertIsFocused()
        val before = text.valueState.value
        val hidesBefore = keyboardHides
        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        composeRule.onNodeWithTag("explanation.action").performClick()
        composeRule.onNodeWithTag("composer.customize").assertIsDisplayed()
        assertTrue("customization clears the composing focus", !focused)
        assertTrue("customization hides the keyboard", keyboardHides > hidesBefore)
        composeRule.runOnIdle { checkNotNull(back).onBackInvoked() }
        composeRule.onNodeWithTag("composer.customize").assertDoesNotExist()
        assertTrue("Back does not reopen the keyboard", !focused)
        assertEquals(before, text.valueState.value)
        assertTrue("customization hides the keyboard", keyboardHides > hidesBefore)
    }

    @Test
    fun compressedLargeTextKeepsItsMinimumWidthAfterTheDraftScrolls() {
        val draft = (1..40).joinToString("\n") { "Line $it remains editable" }
        render(
            ComposerToolbarSettings(ComposerShortcut.entries),
            TextFieldValue(draft, TextRange(draft.length)),
            height = 180,
            fontScale = 1.5f,
        )
        composeRule.onNode(hasSetTextAction()).performClick().performTextInputSelection(TextRange(draft.length))
        composeRule.mainClock.advanceTimeBy(1_000)
        composeRule.waitForIdle()
        val editor = composeRule.onNodeWithTag(COMPOSER_EDITOR_VIEWPORT_TAG).fetchSemanticsNode()
        val toolbars = composeRule.onAllNodesWithTag("composer.shortcuts").fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("physical shortcut groups are present", toolbars.isNotEmpty())
        toolbars.forEach { toolbar ->
            assertEquals(with(composeRule.density) { ComposerShortcutTarget.toPx() }, toolbar.height, 1f)
        }
        val scroll = editor.config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue("the draft overflows this real compressed viewport", scroll.maxValue() > 0f)
        assertTrue("the actual draft is scrolled", scroll.value() > 0f)
        val minimum = with(composeRule.density) { (ComposerMinimumTextWidth * 1.5f).toPx() }
        assertTrue("navigation must not take the large-text reservation", editor.boundsInRoot.width >= minimum)
    }

    @Test
    fun attachedAttachmentMenuRejectsAcquisitionAfterTheSignOutGateCloses() {
        render(ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)))
        composeRule.onNodeWithTag("composer.shortcut.more").performTouchInput { click() }
        composeRule.onNodeWithText(context.getString(R.string.attachment_photos_videos)).assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(R.string.attachment_camera)).assertDoesNotExist()
        composeRule.runOnIdle { appState.signOutInProgress = true }
        try {
            composeRule.onNodeWithText(context.getString(R.string.attachment_photos_videos)).performClick()
            assertEquals(0, galleryCalls)
        } finally {
            composeRule.runOnIdle { appState.signOutInProgress = false }
        }
    }

    /** A short, large-text editor still scrolls to every command, and accessibility add and reorder actions save. */
    @Test
    fun shortHeightEditorRetainsScrollableCommandsAndAccessibilityReorder() {
        val settings = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera))
        render(settings, height = 180, fontScale = 1.5f)
        composeRule.onNodeWithTag("composer.shortcut.camera").performTouchInput { longClick() }
        composeRule.onNodeWithTag("explanation.action").performClick()
        val tray = composeRule.onNodeWithTag("composer.customize").fetchSemanticsNode().boundsInRoot
        assertTrue("tray respects the available viewport", tray.height in 48f..180f)
        composeRule.onNodeWithTag("composer.customize.label.gallery").performScrollTo()
        val add =
            composeRule
                .onNodeWithTag("composer.customize.label.gallery")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single {
                    it.label ==
                        context.getString(dev.ipf.whitenoise.android.R.string.composer_add)
                }
        composeRule.runOnIdle { assertTrue(add.action()) }
        composeRule.onNodeWithTag("composer.customize.label.gallery").performScrollTo()
        val move =
            composeRule
                .onNodeWithTag("composer.customize.label.gallery")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single {
                    it.label ==
                        "Move earlier"
                }
        composeRule.runOnIdle { assertTrue(move.action()) }
        composeRule.onNodeWithTag("composer.customize.done").assertDoesNotExist()
        awaitToolbarSaveSettled()
        assertEquals(
            listOf(ComposerShortcut.More, ComposerShortcut.Gallery, ComposerShortcut.Camera),
            appState.composerToolbarPreference.value.shortcuts,
        )
    }

    @Test
    fun describedActionsExposeExplainAndCustomizeOnTheirActualMergedSemanticsNodes() {
        identity = true
        render(
            ComposerToolbarSettings(
                listOf(
                    ComposerShortcut.More,
                    ComposerShortcut.Camera,
                    ComposerShortcut.Paste,
                    ComposerShortcut.SendingIdentity,
                ),
            ),
        )
        listOf(R.string.attachment_camera, R.string.paste_from_clipboard).forEach { label ->
            val node = composeRule.onNodeWithContentDescription(context.getString(label)).fetchSemanticsNode()
            val actions = node.config[SemanticsActions.CustomActions]
            assertTrue(actions.any { it.label == context.getString(R.string.explanation_action_explain) })
            assertTrue(actions.any { it.label == context.getString(R.string.composer_customize) })
        }
        val sender = composeRule.onNodeWithTag("composer-sending-identity").fetchSemanticsNode()
        assertTrue(
            sender.config[SemanticsActions.CustomActions].any {
                it.label == context.getString(R.string.composer_customize)
            },
        )
    }

    @Test
    fun readerWithoutAttachmentCapabilitiesOmitsMoreWithoutChangingTheSavedConfiguration() {
        attachments = false
        val settings =
            ComposerToolbarSettings(
                listOf(
                    ComposerShortcut.More,
                    ComposerShortcut.Emoji,
                    ComposerShortcut.Paste,
                    ComposerShortcut.Dictation,
                ),
            )
        render(settings)
        composeRule.onNodeWithTag("composer.shortcut.more").assertDoesNotExist()
        assertEquals(settings, appState.composerToolbarPreference.value)
        composeRule.onNode(hasSetTextAction()).performClick().assertIsFocused()
    }

    /** Unpinned text actions keep Plus available and appear in its menu even without attachment sources. */
    @Test
    fun unpinnedTextActionsKeepPlusAndAppearInItsMenu() {
        attachments = false
        render(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        composeRule.onNodeWithTag("composer.shortcut.more").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("conversation.attachment.menu.emoji").assertIsDisplayed()
        composeRule.onNodeWithTag("conversation.attachment.menu.paste").assertIsDisplayed()
    }

    @Test
    fun locationPeopleContactPollAndStickerShortcutsInvokeTheirProductionCallbacksOnce() {
        extraInventory = true
        render(
            ComposerToolbarSettings(
                listOf(
                    ComposerShortcut.More,
                    ComposerShortcut.Location,
                    ComposerShortcut.Person,
                    ComposerShortcut.Contact,
                    ComposerShortcut.Poll,
                    ComposerShortcut.Sticker,
                ),
            ),
        )
        listOf("location", "person", "contact", "poll", "sticker").forEach { id ->
            composeRule.onNodeWithTag("composer.shortcut.$id").performTouchInput { click() }
        }
        assertEquals(mapOf("location" to 1, "person" to 1, "contact" to 1, "poll" to 1, "sticker" to 1), extraCalls)
    }

    @Test
    fun pollOnlyCapabilityStillExposesTheOrdinaryMoreInventory() {
        attachments = false
        pollOnly = true
        render(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        composeRule.onNodeWithTag("composer.shortcut.more").performTouchInput { click() }
        composeRule.onNodeWithText(context.getString(R.string.poll_create)).performClick()
        assertEquals(mapOf("poll" to 1), extraCalls)
    }

    @Test
    fun dismissedAttachmentMenuAndRetainedToolbarCallbackCannotAcquireDuringCustomization() {
        render(ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)))
        val customize =
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.attachment_camera))
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == context.getString(R.string.composer_customize) }
        composeRule.onNodeWithTag("composer.shortcut.more").performTouchInput { click() }
        val visibleCameraInMenu =
            hasText(context.getString(R.string.attachment_camera)) and hasAnyAncestor(isPopup())
        composeRule.onNode(visibleCameraInMenu).assertDoesNotExist()
        val hiddenGallery =
            hasText(context.getString(R.string.attachment_photos_videos)) and hasAnyAncestor(isPopup())
        composeRule.onNode(hiddenGallery).assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(customize.action()) }
        composeRule.onNodeWithTag("composer.customize").assertIsDisplayed()
        composeRule.onNodeWithTag("composer.customize.label.camera").performScrollTo().assertIsDisplayed()
        composeRule.onNode(hiddenGallery).assertDoesNotExist()
        // The full-screen route replaces the composer; its preview glyphs never run acquisition.
        assertEquals(0, cameraCalls)
    }

    @Test
    fun selectingEveryEligibleAttachmentHidesMore() {
        extraInventory = true
        render(ComposerToolbarSettings(ComposerShortcut.entries))
        composeRule.onNodeWithTag("composer.shortcut.more").assertDoesNotExist()
        assertEquals(ComposerShortcut.entries, appState.composerToolbarPreference.value.shortcuts)
    }

    /** Typing disables the selected live voice slot without changing its place. */
    @Test
    fun typedVoiceShortcutKeepsItsBoundsAndCannotAcquireTheMicrophone() {
        var permissionRequests = 0
        val voice =
            VoiceRecordingController(
                context = context,
                outputDirectory = context.cacheDir,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                onPermissionRequest = {
                    permissionRequests++
                    false
                },
                onRecordingComplete = { _, _ -> },
                onError = {},
            )
        try {
            render(
                ComposerToolbarSettings(
                    listOf(
                        ComposerShortcut.Camera,
                        ComposerShortcut.VoiceNote,
                        ComposerShortcut.Gallery,
                    ),
                    rightShortcuts = emptySet(),
                ),
                voiceController = voice,
            )
            val tag = "composer.shortcut.voice_note"
            val before = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            composeRule.runOnIdle { text.updateValue(TextFieldValue("Typed draft")) }
            composeRule.waitForIdle()
            val after = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertEquals(before.center.x, after.center.x, 1f)
            assertEquals(before.center.y, after.center.y, 1f)
            composeRule
                .onNodeWithContentDescription(context.getString(R.string.voice_message_record))
                .assertIsNotEnabled()
                .performTouchInput { click() }
            composeRule.runOnIdle {
                assertEquals(0, permissionRequests)
                assertTrue(!voice.isRecording)
            }
            composeRule.runOnIdle { text.updateValue(TextFieldValue()) }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag("composer.voice.launcher").assertIsDisplayed()
            val restored = composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertEquals(before.center.x, restored.center.x, 1f)
        } finally {
            voice.release()
        }
    }

    /** Renders the actual app-state-backed composer with the requested shortcut capabilities. */
    @Suppress("LongMethod") // Keeps the app-state-backed renderer and its callback instrumentation together.
    private fun render(
        settings: ComposerToolbarSettings,
        value: TextFieldValue = TextFieldValue(),
        direction: LayoutDirection = LayoutDirection.Ltr,
        height: Int = 600,
        fontScale: Float = 1f,
        attachmentSend: ((String, (Boolean) -> Unit) -> Unit)? = null,
        voiceController: VoiceRecordingController? = null,
    ) {
        context
            .getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("composer_toolbar_v1", settings.encode())
            .commit()
        appState =
            if (identity) {
                emptyAppState(
                    accounts = listOf(testAccount("first", "aa".repeat(32)), testAccount("second", "bb".repeat(32))),
                    activeAccountRef = "first",
                )
            } else {
                emptyAppState()
            }
        text = ComposerTextState(value)
        customizing = false
        val registrar =
            ComposerOverlayBackRegistrar { _, callback ->
                back = callback
                { if (back === callback) back = null }
            }
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides direction,
                LocalSoftwareKeyboardController provides keyboard,
            ) {
                WhiteNoiseTheme(fontScale = fontScale) {
                    ExplanationHost {
                        Surface {
                            Box(Modifier.width(360.dp).height(height.dp), contentAlignment = Alignment.BottomCenter) {
                                if (customizing) {
                                    val preference = appState.composerToolbarPreference
                                    ComposerToolbarEditor(
                                        rememberComposerSettingsEditState(preference.requestedValue),
                                        preference.saving,
                                        preference::save,
                                        { customizing = false },
                                        Modifier.fillMaxSize(),
                                        overlayBackRegistrar = registrar,
                                        saveFailed = preference.failed,
                                    )
                                    return@Box
                                }
                                ComposerBar(
                                    appState = appState,
                                    onCustomizeToolbar = { customizing = true },
                                    draftAccountRef = if (identity) "first" else null,
                                    replyingTo = null,
                                    messageTextCopy = MessageTextCopy.Default,
                                    onCancelReply = {},
                                    onSend = { _, _ -> },
                                    hasPendingAttachments = attachmentSend != null,
                                    onSendAttachments = attachmentSend,
                                    softwareKeyboardController = keyboard,
                                    onComposerFocusChanged = { focused = it },
                                    overlayBackRegistrar = registrar,
                                    onCaptureFromCamera = if (attachments) ({ cameraCalls++ }) else null,
                                    onPickFromGallery = if (attachments) ({ galleryCalls++ }) else null,
                                    onPickDocument = if (attachments) ({ fileCalls++ }) else null,
                                    onShareLocation = if (extraInventory) ({ recordExtra("location") }) else null,
                                    onShareUser = if (extraInventory) ({ recordExtra("person") }) else null,
                                    onShareContact = if (extraInventory) ({ recordExtra("contact") }) else null,
                                    onCreatePoll = if (extraInventory || pollOnly) ({ recordExtra("poll") }) else null,
                                    onOpenStickers = if (extraInventory) ({ recordExtra("sticker") }) else null,
                                    textState = text,
                                    voiceRecordingController = voiceController,
                                    modifier = Modifier.testTag("composer.toolbar.production"),
                                )
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    /**
     * Waits for the host preference save to settle. Its disk commit runs on Dispatchers.IO, and `saving` clears only
     * once the save resumes on the main looper, which Robolectric runs only when idled — `waitUntil` advances the
     * Compose clock but never idles the looper. A commit that outlasts the preceding idle, as on a loaded CI fork,
     * would otherwise never settle, so each poll idles the looper itself.
     */
    private fun awaitToolbarSaveSettled() {
        composeRule.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            !appState.composerToolbarPreference.saving
        }
    }

    /** Counts invocations of one optional attachment action. */
    private fun recordExtra(id: String) {
        extraCalls[id] = (extraCalls[id] ?: 0) + 1
    }
}

/** Several actions on the right, so the crowded right side must wrap its own rows. */
private val WRAPPING_RIGHT_SHORTCUTS =
    setOf(ComposerShortcut.Emoji, ComposerShortcut.Paste, ComposerShortcut.Dictation, ComposerShortcut.VoiceNote)
