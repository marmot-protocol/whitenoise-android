package dev.ipf.whitenoise.android.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.ipf.marmotkit.GroupRecoveryStatusFfi
import dev.ipf.marmotkit.GroupRejoinInvitationFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.share.ShareImportError
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareRequest
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.common.LocalSystemAnimationsEnabled
import dev.ipf.whitenoise.android.ui.conversation.GroupRecoveryCard
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceReviewUnavailableNotice
import dev.ipf.whitenoise.android.ui.conversation.media.GroupPictureFailureDialog
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorScreen
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.share.ShareImportErrorDialog
import dev.ipf.whitenoise.android.ui.share.ShareImportStatus
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "en-w360dp-h780dp-mdpi")
class TerminalPopupMotionTest {
    @get:Rule val rule = createComposeRule()
    private val visible = mutableStateOf(false)
    private val busy = mutableStateOf(false)
    private val calls = mutableListOf<String>()
    private val callback = mutableStateOf<() -> Unit>({ calls += "accepted" })

    private fun show(content: @Composable () -> Unit) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            WhiteNoiseTheme(darkTheme = true, amoled = true, fontScale = 1f) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                CompositionLocalProvider(LocalSystemAnimationsEnabled provides true) { content() }
            }
        }
        rule.mainClock.advanceTimeBy(400)
    }

    private fun exercise(
        name: String,
        open: () -> Unit,
        accept: () -> Unit,
        invalidate: (() -> Unit)? = null,
    ) {
        capture(name, "forward", action = open)
        capture(name, "reverse") {
            accept()
            rule.runOnIdle {
                assertEquals(emptyList<String>(), calls)
                callback.value = { calls += "current" }
                invalidate?.invoke()
            }
        }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(if (invalidate == null) listOf("current") else emptyList<String>(), calls) }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(if (invalidate == null) listOf("current") else emptyList<String>(), calls) }
    }

    @Test fun cancelledImportKeepsItsWindowUntilTerminalExit() {
        val importing = mutableStateOf(true)
        val request = mutableStateOf<ShareRequest?>(null)
        show {
            if (visible.value) {
                ShareImportStatus(
                    importing = importing.value,
                    progress = null,
                    request = request.value,
                    importRequestId = "captured-import",
                    onCancel = {
                        calls += "cancelled"
                        visible.value = false
                    },
                ) { if (it) Text("Recipients") }
            }
        }
        capture("import-cancel", "forward") { visible.value = true }
        capture("import-cancel", "reverse") {
            rule.onNodeWithText("Cancel").performClick()
            rule.runOnIdle {
                importing.value = false
                request.value =
                    ShareRequest(SharePayload("Shared text", emptyList(), "text/plain"), null, "captured-import")
            }
        }
        rule.runOnIdle { assertEquals(listOf("cancelled"), calls) }
        rule.onNodeWithText("Recipients").assertDoesNotExist()
    }

    @Test fun refusedRecordingReopensBeforeAnotherAcceptedRetry() {
        var attempts = 0
        show {
            if (visible.value) {
                VoiceReviewUnavailableNotice(
                    onDismiss = { visible.value = false },
                    onRecordAgain = {
                        attempts++
                        if (attempts == 2) {
                            calls += "started"
                            visible.value = false
                            true
                        } else {
                            false
                        }
                    },
                )
            }
        }
        capture("voice-retry", "forward") { visible.value = true }
        capture("voice-retry", "refused", frames = 64) { rule.onNodeWithText("Record again").performClick() }
        rule.runOnIdle {
            assertEquals(1, attempts)
            assertEquals(emptyList<String>(), calls)
        }
        rule.onNodeWithText("Record again").assertExists()
        capture("voice-retry", "reverse") { rule.onNodeWithText("Record again").performClick() }
        rule.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(listOf("started"), calls)
        }
    }

    @Test fun pictureRetryWaitsForExitAndUsesCurrentCallback() {
        show { if (visible.value) GroupPictureFailureDialog(callback.value, { visible.value = false }) }
        exercise("picture-retry", { visible.value = true }, { rule.onNodeWithText("Retry").performClick() })
    }

    @Test fun pictureSourceRemovalCancelsRetry() {
        show { if (visible.value) GroupPictureFailureDialog(callback.value, {}) }
        capture("picture-source-removal", "forward") { visible.value = true }
        rule.onNodeWithText("Retry").performClick()
        rule.runOnIdle { visible.value = false }
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(emptyList<String>(), calls) }
    }

    @Test fun shareContinueWaitsForExitAndUsesCurrentCallback() {
        val payload = SharePayload("Shared text", emptyList(), "text/plain")
        show { if (visible.value) ShareImportErrorDialog(payload, true, {}, callback.value) }
        exercise("share-continue", { visible.value = true }, { rule.onNodeWithText("Share to").performClick() })
    }

    @Test fun sharePayloadReplacementDuringExitCancelsContinue() {
        val payload = mutableStateOf(SharePayload("Shared text", emptyList(), "text/plain"))
        show { if (visible.value) ShareImportErrorDialog(payload.value, true, {}, callback.value) }
        exercise(
            "share-replacement",
            { visible.value = true },
            { rule.onNodeWithText("Share to").performClick() },
            { payload.value = SharePayload("Replacement text", emptyList(), "text/plain") },
        )
        rule.onNodeWithText("Share to").performClick()
        rule.mainClock.advanceTimeBy(800)
        rule.runOnIdle { assertEquals(listOf("current"), calls) }
    }

    @Test fun shareEqualPayloadNewRequestNeedsItsOwnAcceptance() {
        val payload =
            SharePayload("Shared text", emptyList(), "text/plain", importErrors = listOf(ShareImportError.Unreadable))
        val request = mutableStateOf(ShareRequest(payload, null, "first"))
        show {
            ShareImportStatus(false, null, request.value, {}, content = { ready ->
                if (ready) Text("Destination ready")
            })
        }
        rule.onNodeWithText("Share to").performClick()
        rule.runOnIdle { request.value = request.value.copy(requestId = "replacement") }
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Destination ready").assertDoesNotExist()
        rule.onNodeWithText("Share to").performClick()
        rule.mainClock.advanceTimeBy(800)
        rule.onNodeWithText("Destination ready").assertExists()
    }

    @Test fun rejoinConfirmWaitsForExit() = rejoin("Rejoin group", "rejoin-confirm")

    @Test fun rejoinDeclineWaitsForExit() = rejoin("Decline invitation", "rejoin-decline")

    @Test fun rejoinBusyDuringExitCancelsDecision() = rejoin("Rejoin group", "rejoin-busy", true)

    private fun rejoin(
        label: String,
        name: String,
        cancelForBusy: Boolean = false,
    ) {
        val invitation = GroupRejoinInvitationFfi("welcome", "welcomer", 9u, "token")
        val status = GroupRecoveryStatusFfi("group", true, 1u, 1u, listOf(invitation))
        show {
            val onDecision = callback.value
            GroupRecoveryCard(
                status,
                busy.value,
                { "Marmot Friend" },
                { "Inviter" },
                { onDecision() },
                { onDecision() },
            )
        }
        exercise(
            name,
            { rule.onNodeWithText("Review invitation from Marmot Friend · epoch 9").performClick() },
            { rule.onNodeWithText(label).performClick() },
            if (cancelForBusy) {
                { busy.value = true }
            } else {
                null
            },
        )
        if (cancelForBusy) {
            capture(name, "recovery-forward") { busy.value = false }
            capture(name, "recovery-reverse") { rule.onNodeWithText(label).performClick() }
            rule.mainClock.advanceTimeBy(800)
            rule.runOnIdle { assertEquals(listOf("current"), calls) }
        }
    }

    @Test fun markdownOpenWaitsForExit() = markdownOpen(false)

    @Test fun markdownReplacementDuringExitCancelsOpen() = markdownOpen(true)

    private fun markdownOpen(replaceDuringExit: Boolean) {
        val block =
            MarkdownBlockFfi.Paragraph(
                listOf(
                    MarkdownInlineFfi.Link(
                        "https://example.com",
                        null,
                        listOf(MarkdownInlineFfi.Text("other.example")),
                        MarkdownLinkDestinationKindFfi.WEB,
                    ),
                ),
            )
        val document = mutableStateOf(MarkdownDocumentFfi(listOf(block), false, byteArrayOf()))
        show {
            val onOpen = callback.value
            MarkdownMessageBody(document.value, onWebLinkActivated = {
                onOpen()
                true
            })
        }
        exercise(
            if (replaceDuringExit) "markdown-replacement" else "markdown-open",
            { rule.onNodeWithText("other.example").performClick() },
            { rule.onNodeWithText("Open").performClick() },
            if (replaceDuringExit) {
                { document.value = MarkdownDocumentFfi(emptyList(), false, byteArrayOf()) }
            } else {
                null
            },
        )
    }

    @Test fun sentenceChoiceWaitsForExit() = sentenceChoice(false)

    @Test fun sentenceOwnerReplacementDuringExitCancelsReading() = sentenceChoice(true)

    private fun sentenceChoice(replaceDuringExit: Boolean) {
        val choices = listOf(TtsSentenceChoice("r", "s0", 0, "One."), TtsSentenceChoice("r", "s1", 1, "Two."))
        val owner =
            mutableStateOf(
                TtsSentenceActions({ _, _ -> choices }, {
                    callback.value()
                    true
                }),
            )
        show {
            val actions = ttsSentenceAccessibilityActions("leaf", "One. Two.", owner.value)
            Text("source", Modifier.semantics { customActions = actions })
        }
        exercise(
            if (replaceDuringExit) "sentence-replacement" else "sentence-choice",
            {
                val actions = rule.onNodeWithText("source").fetchSemanticsNode().config[SemanticsActions.CustomActions]
                rule.runOnIdle { actions.single().action() }
            },
            { rule.onNodeWithText("2. Two.").performClick() },
            if (replaceDuringExit) {
                (
                    {
                        owner.value =
                            TtsSentenceActions({ _, _ -> choices }, {
                                calls += "replacement"
                                true
                            })
                    }
                )
            } else {
                null
            },
        )
    }

    @Test fun photoDiscardWaitsForExit() = photoDiscard(false)

    @Test fun photoInputInterruptionAllowsDiscardAgain() = photoDiscard(false, true)

    @Test fun photoSavingDuringExitCancelsDiscard() = photoDiscard(true)

    private fun photoDiscard(
        saveDuringExit: Boolean,
        interruptInput: Boolean = false,
    ) {
        val inputEnabled = mutableStateOf(true)
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        val holder =
            PhotoEditorStateHolder(
                PhotoEditRecipe.Original,
                MediaQuality.Standard,
                EditorPixelSize(64, 48),
                newStrokeId = { "stroke" },
            )
        try {
            show {
                PhotoEditorScreen(
                    bitmap,
                    PhotoEditorSourceInfo(EditorPixelSize(64, 48), EditorPixelSize(64, 48), 1, "image/png", true),
                    holder,
                    callback.value,
                    { _, _ -> },
                    inputEnabled = { inputEnabled.value },
                )
            }
            rule.onNodeWithContentDescription("Rotate clockwise 90 degrees").performClick()
            exercise(
                when {
                    saveDuringExit -> "photo-saving"
                    interruptInput -> "photo-input-interruption"
                    else -> "photo-discard"
                },
                { rule.onNodeWithContentDescription("Close").performClick() },
                { rule.onNodeWithText("Discard").performClick() },
                when {
                    saveDuringExit -> ({ holder.beginSaving() })
                    interruptInput -> ({ inputEnabled.value = false })
                    else -> null
                },
            )
            if (interruptInput || saveDuringExit) {
                val recovery = if (saveDuringExit) "photo-saving" else "photo-input-interruption"
                capture(recovery, "recovery-forward") {
                    inputEnabled.value = true
                    if (saveDuringExit) holder.finishSaving()
                }
                capture(recovery, "recovery-reverse") { rule.onNodeWithText("Discard").performClick() }
                rule.mainClock.advanceTimeBy(800)
                rule.runOnIdle { assertEquals(listOf("current"), calls) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    @OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
    private fun capture(
        name: String,
        direction: String,
        frames: Int = 32,
        action: () -> Unit,
    ) {
        val recording = System.getenv("ANIMATION_CAPTURE_FRAMES") == "1"
        val folder =
            File(
                System.getenv("TERMINAL_POPUP_CAPTURE_DIR") ?: "build/animation-frames/terminal-popups",
                "$name/$direction",
            )
        if (recording) {
            folder.mkdirs()
            captureScreenRoboImage(File(folder, "source.png").path)
        }
        action()
        rule.runOnIdle {
            androidx.compose.runtime.snapshots.Snapshot
                .sendApplyNotifications()
        }
        repeat(frames) { frame ->
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
            if (direction == "reverse" && frame == 4) rule.runOnIdle { assertEquals(emptyList<String>(), calls) }
            if (recording) captureScreenRoboImage(File(folder, "frame-%03d.png".format(frame)).path)
        }
    }
}
