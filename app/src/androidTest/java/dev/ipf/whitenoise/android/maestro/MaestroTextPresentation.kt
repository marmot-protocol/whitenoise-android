package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentAttachmentKind
import dev.ipf.whitenoise.android.ui.conversation.media.DocumentAttachmentViewer
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentCandidate
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentFormat
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentNativeActions
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentPreview
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentReaderDialog
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentReaderScreen
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentReaderState
import dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentUnavailableReason

/** Decoder result presentation is distinct from the retained native media/transfer campaigns. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroTextPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("text-dialog-")) {
        NativeReaderDialogPresentation(fixture)
        return
    }
    if (fixture.scenario == "text-document-html") {
        LocalHtmlDocumentPresentation(fixture)
        return
    }
    val candidate =
        TextAttachmentCandidate("Maestro complete filename.txt", "text/plain", TextAttachmentFormat.PlainText)
    val body = "Maestro document first line\nMaestro document last line"
    val state =
        when (fixture.scenario) {
            "text-loading" -> TextAttachmentReaderState.Loading
            "text-binary" -> TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.Binary)
            "text-too-large" -> TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.TooLarge)
            "text-invalid" -> TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.InvalidEncoding)
            "text-retry" -> TextAttachmentReaderState.Unavailable(TextAttachmentUnavailableReason.DownloadFailed)
            "text-ready", "text-filename", "text-copy", "text-save", "text-read" ->
                TextAttachmentReaderState.Ready(TextAttachmentPreview(candidate, body, byteCount = 55))
            else -> error("Unknown text presentation")
        }
    TextAttachmentReaderScreen(
        candidate = candidate,
        state = state,
        onDismiss = { fixture.finish("dismiss") },
        onRetry = { fixture.finish("retry") },
        onCopy = {
            check(it == body || it == candidate.displayName) { "Unexpected reader copy payload" }
            fixture.record(if (it == body) "copy-body" else "copy-filename")
        },
        onReadAloud = {
            check(it.text == body && it.candidate == candidate)
            fixture.finish("read-handoff")
        },
        onOpenExternal = { fixture.finish("external-handoff") },
        onSave = { fixture.finish("save-handoff") },
    )
}

/** Production dialog owns decoding, retry and clipboard; bounded ports never download or export native media. */
@Composable
@Suppress("FunctionNaming")
private fun NativeReaderDialogPresentation(fixture: MaestroPresentationFixture) {
    val actions =
        remember(fixture) {
            TextAttachmentNativeActions(
                sourceIsCurrent = { true },
                saveSource = { fixture.finish("save-handoff") },
            )
        }
    DisposableEffect(actions) { onDispose { actions.release() } }
    TextAttachmentReaderDialog(
        actions = actions,
        candidate = TextAttachmentCandidate("Fixture decoded.txt", "text/plain", TextAttachmentFormat.PlainText),
        appState = fixture.appState,
        speechContext =
            dev.ipf.whitenoise.android.ui.conversation.media.TextAttachmentSpeechContext(
                "11".repeat(32),
                "Fixture sender",
                "22".repeat(32),
                0,
            ),
        loadBytes = {
            fixture.record("load-bytes")
            if (fixture.scenario.endsWith("failure")) error("Synthetic reader acquisition failure")
            "Fixture decoded first line\nFixture decoded last line".toByteArray(Charsets.UTF_8)
        },
        onOpenExternal = { fixture.finish("external-handoff") },
        onDismiss = { fixture.finish("dismiss") },
    )
}

/** The production document reader owns sanitising and the Rendered/Source tabs; the fixture supplies bytes only. */
@Composable
@Suppress("FunctionNaming")
private fun LocalHtmlDocumentPresentation(fixture: MaestroPresentationFixture) {
    val actions =
        remember(fixture) {
            TextAttachmentNativeActions(
                sourceIsCurrent = { true },
                saveSource = { fixture.finish("save-handoff") },
            )
        }
    DisposableEffect(actions) { onDispose { actions.release() } }
    DocumentAttachmentViewer(
        kind = DocumentAttachmentKind.Html,
        name = "Fixture page.html",
        account = "11".repeat(32),
        source = "22".repeat(32),
        actions = actions,
        loadBytes = {
            fixture.record("load-bytes")
            "<h1>Fixture heading</h1><p>Fixture source paragraph</p>".toByteArray(Charsets.UTF_8)
        },
        loadFile = { null },
        onOpenExternal = { fixture.finish("external-handoff") },
        onDismiss = { fixture.finish("dismiss") },
    )
}
