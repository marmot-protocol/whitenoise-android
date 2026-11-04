package dev.ipf.whitenoise.android.ui.conversation.composer

import android.content.Context
import android.window.OnBackInvokedCallback
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerShortcutSide
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w420dp-h1000dp-mdpi")
class ComposerToolbarEditorInteractionTest {
    @get:Rule val composeRule = createComposeRule()

    /** Icon-only shortcuts distinguish account identity, people, contacts, files and polls. */
    @Test
    fun identityContactAndDocumentShortcutsHaveDistinctIcons() {
        val icons =
            listOf(
                ComposerShortcut.SendingIdentity,
                ComposerShortcut.Person,
                ComposerShortcut.Contact,
                ComposerShortcut.Files,
                ComposerShortcut.Poll,
            ).map { it.iconRes }
        assertEquals(icons.size, icons.distinct().size)
    }

    @Test
    fun failedSaveAnnouncesItsErrorAndKeepsTheWorkingCopyOpen() {
        val edit =
            ComposerToolbarEditState(
                ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)),
            )
        var cancellations = 0
        render(edit, { _, complete -> complete(false) }, { cancellations++ })
        val remove =
            composeRule
                .onNodeWithTag("composer.customize.label.camera")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Remove shortcut" }
        composeRule.runOnIdle { assertTrue(remove.action()) }
        val draft = edit.draft
        val message = ApplicationProvider.getApplicationContext<Context>().getString(R.string.composer_save_failed)
        val error = composeRule.onNodeWithText(message).fetchSemanticsNode()
        assertEquals(LiveRegionMode.Polite, error.config[SemanticsProperties.LiveRegion])
        composeRule.onNodeWithTag("composer.customize").assertExists()
        assertEquals(draft, edit.draft)
        assertEquals(0, cancellations)
    }

    @Test
    fun visibilityAndSideClicksSaveWhileAnEarlierRequestIsPending() {
        val edit = ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        val saved = mutableListOf<ComposerToolbarSettings>()
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(420.dp).height(900.dp)) {
                    ComposerToolbarEditor(edit, true, { value, _ -> saved += value }, {})
                }
            }
        }
        composeRule.onNodeWithTag("composer.customize.label.camera").performScrollTo().performClick()
        val right =
            composeRule
                .onNodeWithTag("composer.customize.label.camera")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Right" }
        composeRule.runOnIdle { assertTrue(right.action()) }
        assertEquals(2, saved.size)
        assertTrue(ComposerShortcut.Camera in saved.first().shortcuts)
        assertEquals(ComposerShortcutSide.Right, saved.last().side(ComposerShortcut.Camera))
    }

    @Test
    fun retainedFailedIntentCanBeRetriedAfterReopening() {
        val failedChoice = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera))
        val edit = ComposerToolbarEditState(failedChoice)
        var retried: ComposerToolbarSettings? = null
        composeRule.setContent {
            WhiteNoiseTheme {
                ComposerToolbarEditor(
                    edit,
                    false,
                    { value, complete ->
                        retried = value
                        complete(true)
                    },
                    {},
                    saveFailed = true,
                )
            }
        }
        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(failedChoice, retried)
        composeRule
            .onNodeWithText(
                ApplicationProvider.getApplicationContext<Context>().getString(R.string.composer_save_failed),
            ).assertDoesNotExist()
    }

    @Test
    fun draggingAnIconSavesOnceOnlyAfterAValidDrop() {
        val original = ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera))
        val edit = ComposerToolbarEditState(original)
        val saved = mutableListOf<ComposerToolbarSettings>()
        render(edit, { config, complete ->
            saved += config
            complete(true)
        }, {})
        val source = previewIcon(ComposerShortcut.Camera)
        val target = previewIcon(ComposerShortcut.More)
        val from = source.fetchSemanticsNode().boundsInRoot.center
        val targetBounds = target.fetchSemanticsNode().boundsInRoot
        val to = Offset(targetBounds.left + 4f, targetBounds.center.y)
        source.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(to - from)
            up()
        }
        assertEquals(listOf(ComposerShortcut.Camera, ComposerShortcut.More), edit.draft.shortcuts)
        assertEquals(listOf(edit.draft), saved)
        composeRule.onNodeWithTag("composer.customize.done").assertDoesNotExist()
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun preImeBackCancelsWithoutSavingAndCancelledDragCannotInsert() {
        val edit =
            ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)))
        var back: OnBackInvokedCallback? = null
        var cancels = 0
        var saves = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(420.dp).height(900.dp)) {
                    ComposerToolbarEditor(
                        edit,
                        false,
                        { _, _ -> saves++ },
                        { cancels++ },
                        overlayBackRegistrar =
                            ComposerOverlayBackRegistrar { _, callback ->
                                back = callback;
                                {}
                            },
                    )
                }
            }
        }
        val source = previewIcon(ComposerShortcut.Camera)
        source.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(0f, -100f))
            cancel()
        }
        assertEquals(listOf(ComposerShortcut.More, ComposerShortcut.Camera), edit.draft.shortcuts)
        composeRule.runOnIdle {
            assertNotNull(back)
            back!!.onBackInvoked()
        }
        assertEquals(1, cancels)
        assertEquals(0, saves)
    }

    @Test
    fun resetSavesImmediatelyWithoutDoneOrCancelControls() {
        val edit = ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        var saves = 0
        var cancels = 0
        render(edit, { _, _ -> saves++ }, { cancels++ })
        composeRule.onNodeWithText("Reset to default").performScrollTo().performClick()
        assertEquals(ComposerToolbarSettings(), edit.draft)
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        composeRule.onNodeWithText("Done").assertDoesNotExist()
        assertEquals(0, cancels)
        assertEquals(1, saves)
    }

    @Test
    fun previousSessionSaveCompletionCannotChangeTheReplacementEditor() {
        val current = mutableStateOf(ComposerToolbarEditState(ComposerToolbarSettings()))
        val pending = mutableListOf<(Boolean) -> Unit>()
        var cancellations = 0
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(420.dp).height(900.dp)) {
                    ComposerToolbarEditor(
                        current.value,
                        false,
                        { _, complete -> pending += complete },
                        { cancellations++ },
                    )
                }
            }
        }
        composeRule.runOnIdle { current.value.remove(ComposerShortcut.Emoji) }
        composeRule.runOnIdle {
            current.value =
                ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { pending.single()(true) }
        assertEquals(0, cancellations)
        composeRule.onNodeWithText("Cancel").assertDoesNotExist()
        assertEquals(0, cancellations)
    }

    @Test
    fun accessibleMoveKeepsTheSameShortcutNodeAcrossRepeatedReorders() {
        val edit =
            ComposerToolbarEditState(
                ComposerToolbarSettings(
                    listOf(ComposerShortcut.More, ComposerShortcut.Emoji, ComposerShortcut.Camera),
                    rightShortcuts = emptySet(),
                ),
            )
        render(edit, { _, _ -> }, {})
        val tag = "composer.customize.label.camera"
        val originalNode = composeRule.onNodeWithTag(tag).fetchSemanticsNode().id
        repeat(2) {
            val earlier =
                composeRule
                    .onNodeWithTag(tag)
                    .fetchSemanticsNode()
                    .config[SemanticsActions.CustomActions]
                    .single { it.label == "Move earlier" }
            composeRule.runOnIdle { assertTrue(earlier.action()) }
            composeRule.waitForIdle()
            assertEquals(originalNode, composeRule.onNodeWithTag(tag).fetchSemanticsNode().id)
        }
        assertEquals(
            listOf(ComposerShortcut.Camera, ComposerShortcut.More, ComposerShortcut.Emoji),
            edit.draft.shortcuts,
        )
    }

    @Test
    fun accessibleMoveOffersOnlyReachablePositionsOnTheSelectedSide() {
        val paste = ComposerShortcut.Paste
        val camera = ComposerShortcut.Camera
        val edit =
            ComposerToolbarEditState(
                ComposerToolbarSettings(
                    listOf(ComposerShortcut.More, camera, paste, ComposerShortcut.Files),
                    rightShortcuts = setOf(paste),
                ),
            )
        render(edit, { _, _ -> }, {})
        val pasteActions =
            composeRule
                .onNodeWithTag("composer.customize.label.paste")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
        assertFalse(pasteActions.any { it.label == "Move earlier" || it.label == "Move later" })
        val cameraNode = composeRule.onNodeWithTag("composer.customize.label.camera")
        val actions = cameraNode.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.any { it.label == "Move later" })
        composeRule.runOnIdle { assertTrue(actions.single { it.label == "Move earlier" }.action()) }
        composeRule.waitForIdle()
        val firstActions = cameraNode.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertFalse(firstActions.any { it.label == "Move earlier" })
        assertEquals(
            listOf(camera, ComposerShortcut.More, ComposerShortcut.Files),
            edit.draft.onSide(ComposerShortcutSide.Left),
        )
        composeRule.runOnIdle { assertTrue(firstActions.single { it.label == "Move later" }.action()) }
        composeRule.waitForIdle()
        assertEquals(
            listOf(ComposerShortcut.More, camera, ComposerShortcut.Files),
            edit.draft.onSide(ComposerShortcutSide.Left),
        )
        assertEquals(listOf(paste), edit.draft.onSide(ComposerShortcutSide.Right))
    }

    @Test
    fun addingOneAvailableShortcutDoesNotRebindTheNextAvailableNode() {
        val edit = ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        render(edit, { _, _ -> }, {})
        val remainingTag = "composer.customize.label.emoji"
        val originalNode = composeRule.onNodeWithTag(remainingTag).fetchSemanticsNode().id
        val add =
            composeRule
                .onNodeWithTag("composer.customize.label.sending_identity")
                .fetchSemanticsNode()
                .config[SemanticsActions.CustomActions]
                .single { it.label == "Add shortcut" }
        composeRule.runOnIdle { assertTrue(add.action()) }
        composeRule.waitForIdle()
        assertEquals(originalNode, composeRule.onNodeWithTag(remainingTag).fetchSemanticsNode().id)
        assertEquals(listOf(ComposerShortcut.More, ComposerShortcut.SendingIdentity), edit.draft.shortcuts)
    }

    /** Layout choices expose one radio role each and update the working selection. */
    @Test
    fun layoutOptionsExposeRadioRolesAndRemainActivatable() {
        val edit = ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        render(edit, { _, _ -> }, {})
        val expanded = composeRule.onNodeWithText("Always expanded")
        assertEquals(Role.RadioButton, expanded.fetchSemanticsNode().config[SemanticsProperties.Role])
        expanded.performClick()
        assertTrue(expanded.fetchSemanticsNode().config[SemanticsProperties.Selected])
        assertFalse(composeRule.onNodeWithText("Automatic").fetchSemanticsNode().config[SemanticsProperties.Selected])
    }

    @Test
    fun uniformVisibilityToggleKeepsItsTargetAndNodeAcrossChanges() {
        val edit = ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More)))
        render(edit, { _, _ -> }, {}, width = 240, fontScale = 2f)
        val toggle = composeRule.onNodeWithTag("composer.customize.label.camera").performScrollTo()
        val originalNode = toggle.fetchSemanticsNode().id
        toggle.assertTouchHeightIsEqualTo(48.dp)
        toggle.performClick()
        assertTrue(ComposerShortcut.Camera in edit.draft.shortcuts)
        assertEquals(originalNode, toggle.fetchSemanticsNode().id)
        toggle.performClick()
        assertFalse(ComposerShortcut.Camera in edit.draft.shortcuts)
        composeRule.onNodeWithText("Selected shortcuts").assertDoesNotExist()
        composeRule.onNodeWithText("Available actions").assertDoesNotExist()
    }

    @Test
    fun directPreviewDragAcrossWrappedRtlRowsKeepsLogicalOrder() {
        val edit =
            ComposerToolbarEditState(
                ComposerToolbarSettings(
                    listOf(
                        ComposerShortcut.More,
                        ComposerShortcut.Emoji,
                        ComposerShortcut.Paste,
                        ComposerShortcut.Dictation,
                        ComposerShortcut.Camera,
                        ComposerShortcut.Gallery,
                    ),
                ),
            )
        render(edit, { _, _ -> }, {}, width = 240, rtl = true)
        val source = previewIcon(ComposerShortcut.Camera)
        val target = previewIcon(ComposerShortcut.More)
        val from = source.fetchSemanticsNode().boundsInRoot.center
        val targetBounds = target.fetchSemanticsNode().boundsInRoot
        val to = Offset(targetBounds.right - 4f, targetBounds.center.y)
        source.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(to - from)
            up()
        }
        assertEquals(ComposerShortcut.Camera, edit.draft.shortcuts.first())
        assertEquals(ComposerShortcut.More, edit.draft.shortcuts[1])
    }

    @Test
    fun savingAnEarlierChoiceDoesNotCancelTheCurrentHeldDrag() {
        val edit =
            ComposerToolbarEditState(ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)))
        val saving = mutableStateOf(false)
        composeRule.setContent {
            WhiteNoiseTheme {
                Box(Modifier.width(420.dp).height(900.dp)) {
                    ComposerToolbarEditor(edit, saving.value, { _, _ -> }, {})
                }
            }
        }
        val source = previewIcon(ComposerShortcut.Camera)
        val from = source.fetchSemanticsNode().boundsInRoot.center
        val target = previewIcon(ComposerShortcut.More).fetchSemanticsNode().boundsInRoot
        source.performTouchInput {
            down(center)
            advanceEventTime(600)
            moveBy(Offset(target.left + 4f, target.center.y) - from)
        }
        composeRule.runOnIdle { saving.value = true }
        composeRule.waitForIdle()
        source.performTouchInput { up() }
        assertEquals(listOf(ComposerShortcut.Camera, ComposerShortcut.More), edit.draft.shortcuts)
        assertEquals(null, edit.dragging)
    }

    /** A scrolled slot's clipped hit bounds must not replace its full local coordinate origin. */
    @Test
    fun draggingPartiallyClippedPreviewIconUsesItsUnclippedOrigin() {
        val edit =
            ComposerToolbarEditState(
                ComposerToolbarSettings(listOf(ComposerShortcut.More, ComposerShortcut.Camera)),
            )
        composeRule.setContent {
            WhiteNoiseTheme {
                Row(
                    Modifier
                        .width(72.dp)
                        .height(48.dp)
                        .testTag("partial.preview")
                        .onGloballyPositioned { edit.selectedArea = it.boundsInRoot() }
                        .composerPreviewGestures(edit, false)
                        .horizontalScroll(
                            rememberScrollState(with(LocalDensity.current) { 24.dp.roundToPx() }),
                            enabled = false,
                        ),
                ) {
                    edit.draft.shortcuts.forEach { shortcut ->
                        Box(
                            Modifier
                                .size(48.dp)
                                .testTag("partial.${shortcut.id}")
                                .then(composerPreviewDragModifier(shortcut, edit, false)),
                        )
                    }
                }
            }
        }
        val viewport = composeRule.onNodeWithTag("partial.preview")
        val viewportBounds = viewport.fetchSemanticsNode().boundsInRoot
        val clippedSource = composeRule.onNodeWithTag("partial.more").fetchSemanticsNode().boundsInRoot
        assertEquals(viewportBounds.width / 3f, clippedSource.width, 1f)
        viewport.performTouchInput {
            down(Offset(center.x / 3f, center.y))
            advanceEventTime(600)
            moveBy(Offset(center.x * 4f / 3f, 0f))
            up()
        }
        assertEquals(listOf(ComposerShortcut.Camera, ComposerShortcut.More), edit.draft.shortcuts)
    }

    private fun previewIcon(shortcut: ComposerShortcut) =
        composeRule.onNode(
            hasTestTag("composer.shortcut.${shortcut.id}") and
                hasAnyAncestor(hasTestTag("composer.toolbar.preview")),
        )

    private fun render(
        edit: ComposerToolbarEditState,
        save: (ComposerToolbarSettings, (Boolean) -> Unit) -> Unit,
        cancel: () -> Unit,
        width: Int = 420,
        fontScale: Float = 1f,
        rtl: Boolean = false,
    ) {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                WhiteNoiseTheme(fontScale = fontScale) {
                    Box(Modifier.width(width.dp).height(900.dp)) { ComposerToolbarEditor(edit, false, save, cancel) }
                }
            }
        }
    }
}
