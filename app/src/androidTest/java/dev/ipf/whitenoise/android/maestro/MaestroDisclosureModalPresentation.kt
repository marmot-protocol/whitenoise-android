package dev.ipf.whitenoise.android.maestro

import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarkdownLinkDestinationKindFfi
import dev.ipf.marmotkit.MarkdownTimestampStyleFfi
import dev.ipf.whitenoise.android.ui.MarkdownMessageBody
import dev.ipf.whitenoise.android.ui.MarkdownTimestampText
import dev.ipf.whitenoise.android.ui.TtsSentenceActions
import dev.ipf.whitenoise.android.ui.TtsSentenceChoice
import dev.ipf.whitenoise.android.ui.appendMarkdownTimestamp
import dev.ipf.whitenoise.android.ui.common.LocalDeleteSnackbar
import dev.ipf.whitenoise.android.ui.common.ToastSnackbarVisuals
import dev.ipf.whitenoise.android.ui.conversation.composer.VoiceReviewUnavailableNotice
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrLinkedImageDialog
import dev.ipf.whitenoise.android.ui.ttsSentenceAccessibilityActions

internal const val MAESTRO_LOCAL_DELETE_REPORT = "Fixture local deletion diagnostic report"
internal const val MAESTRO_LINK_DESTINATION = "https://destination.example.invalid/path"

/** Actual production disclosures; test controls invoke their real entrypoints, never replacement dialogs. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroDisclosureModalPresentation(fixture: MaestroPresentationFixture) {
    val family = fixture.scenario.removePrefix("modal-").substringBefore('-')
    when (family) {
        "timestamp" -> TimestampPresentation(fixture)
        "link" -> LinkConfirmationPresentation(fixture)
        "sentences" -> SentenceChooserPresentation(fixture)
        "local" -> LocalDeletePresentation(fixture)
        "voice" -> UnavailableRecordingPresentation(fixture)
        "nostr" -> LinkedImagePresentation(fixture)
        else -> OtherModalPresentation(fixture)
    }
}

@Composable
@Suppress("FunctionNaming")
private fun OtherModalPresentation(fixture: MaestroPresentationFixture) {
    when (fixture.scenario.removePrefix("modal-").substringBefore('-')) {
        "translation" -> MaestroTranslationPresentation(fixture)
        "widget" -> MaestroWidgetPresentation(fixture)
        "dictation", "chats" -> MaestroRootModalPresentation(fixture)
        "departure" -> MaestroDepartureModalPresentation(fixture)
        "group", "viewer" -> GroupOrViewerPresentation(fixture)
        "key" -> MaestroKeyFeedbackPresentation(fixture)
        "share" -> MaestroShareMenuPresentation(fixture)
        "import" -> MaestroImportStatusPresentation(fixture)
        "image", "developer" -> MaestroActionModalPresentation(fixture)
        "signin", "retained", "scan" -> MaestroConsentModalPresentation(fixture)
        else -> error("Unknown production modal presentation")
    }
}

@Composable
@Suppress("FunctionNaming")
private fun GroupOrViewerPresentation(fixture: MaestroPresentationFixture) {
    if (fixture.scenario.startsWith("modal-group-details-") || fixture.scenario.startsWith("modal-viewer-failure-")) {
        MaestroGroupConfirmationPresentation(fixture)
    } else {
        MaestroActionModalPresentation(fixture)
    }
}

/** The real linked-image dialog starts its request; only the image fetch boundary is controlled. */
@Composable
@Suppress("FunctionNaming")
private fun LinkedImagePresentation(fixture: MaestroPresentationFixture) {
    val url = "https://example.invalid/maestro-linked-image.png"
    var attempts by remember { mutableStateOf(0) }
    NostrLinkedImageDialog(
        url = url,
        onDismiss = { fixture.finish("dismiss") },
        loadImage = { requested, width ->
            check(requested == url && width > 0)
            attempts++
            fixture.record("linked-image-load")
            when {
                fixture.scenario.endsWith("failure") -> null
                fixture.scenario.endsWith("retry") && attempts == 1 -> null
                else -> fixture.image
            }
        },
    )
}

@Composable
@Suppress("FunctionNaming")
private fun TimestampPresentation(fixture: MaestroPresentationFixture) {
    val view = LocalView.current
    val pointer = remember(view, fixture) { MaestroHoverPointer(view.rootView, fixture) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    DisposableEffect(pointer) { onDispose { pointer.close() } }
    val text =
        remember {
            buildAnnotatedString {
                appendMarkdownTimestamp(
                    MarkdownInlineFfi.Timestamp(1_800_000_000L, MarkdownTimestampStyleFfi.LONG_DATE_TIME),
                )
            }
        }
    Column {
        MarkdownTimestampText(
            text,
            Modifier.testTag("modal.timestamp.text").onGloballyPositioned { bounds = it.boundsInWindow() },
        )
        if (fixture.scenario.endsWith("hover")) {
            Button(onClick = { pointer.dispatch(bounds, exit = false) }) { Text("Hover timestamp") }
            Button(onClick = { pointer.dispatch(bounds, exit = true) }) { Text("End timestamp hover") }
        }
        Button(onClick = { fixture.finish("done") }) { Text("Finish disclosure check") }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun LinkConfirmationPresentation(fixture: MaestroPresentationFixture) {
    val base = LocalContext.current
    val context =
        remember(base, fixture) {
            object : ContextWrapper(base) {
                override fun startActivity(intent: Intent) {
                    check(intent.action == Intent.ACTION_VIEW && intent.dataString == MAESTRO_LINK_DESTINATION) {
                        "External-link confirmation handed off an unexpected intent"
                    }
                    fixture.finish("open-link")
                }
            }
        }
    val document =
        remember {
            MarkdownDocumentFfi(
                blocks =
                    listOf(
                        MarkdownBlockFfi.Paragraph(
                            listOf(
                                MarkdownInlineFfi.Link(
                                    dest = MAESTRO_LINK_DESTINATION,
                                    title = null,
                                    children = listOf(MarkdownInlineFfi.Text("https://visible.example.invalid")),
                                    classification = MarkdownLinkDestinationKindFfi.WEB,
                                ),
                            ),
                        ),
                    ),
                truncated = false,
                blankLinesBefore = ByteArray(0),
            )
        }
    CompositionLocalProvider(LocalContext provides context) { MarkdownMessageBody(document) }
}

/** This invokes the production custom-action callback. TalkBack/platform dispatch remains separate proof. */
@Composable
@Suppress("FunctionNaming")
private fun SentenceChooserPresentation(fixture: MaestroPresentationFixture) {
    val large = fixture.scenario.endsWith("boundary")
    val owner =
        remember(fixture, large) {
            TtsSentenceActions(
                choices = { _, _ ->
                    (1..if (large) 201 else 3).map {
                        TtsSentenceChoice("fixture-revision", "sentence-$it", it - 1, "Fixture sentence $it")
                    }
                },
                select = {
                    val selected = if (large) 200 else 2
                    check(it.revision == "fixture-revision" && it.sentenceId == "sentence-$selected")
                    check(it.ordinal == selected - 1)
                    fixture.finish("select-sentence-$selected")
                    true
                },
            )
        }
    val actions = ttsSentenceAccessibilityActions("fixture-leaf", "Fixture original text", owner)
    Column {
        Text("Fixture sentence leaf", Modifier.semantics { customActions = actions })
        Button(onClick = {
            check(actions.single().action()) { "Production sentence chooser callback rejected its action" }
            fixture.record("chooser-dispatch")
        }) { Text("Invoke sentence chooser") }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun LocalDeletePresentation(fixture: MaestroPresentationFixture) {
    val notice =
        remember {
            ToastSnackbarVisuals(
                message = "Fixture local deletion needs attention",
                details = MAESTRO_LOCAL_DELETE_REPORT,
                copyable = fixture.scenario.endsWith("copy"),
                copyText = MAESTRO_LOCAL_DELETE_REPORT,
            )
        }
    val data =
        remember(fixture, notice) {
            object : SnackbarData {
                override val visuals: SnackbarVisuals = notice

                override fun dismiss() = fixture.finish("dismiss")

                override fun performAction() = error("Details cannot authorize a native deletion retry")
            }
        }
    LocalDeleteSnackbar(data, notice, Modifier.testTag("modal.delete-snackbar"))
}

@Composable
@Suppress("FunctionNaming")
private fun UnavailableRecordingPresentation(fixture: MaestroPresentationFixture) {
    var showing by remember { mutableStateOf(true) }
    if (showing) {
        VoiceReviewUnavailableNotice(
            onDismiss = {
                fixture.record("notice-dismiss")
                showing = false
            },
            onRecordAgain = { fixture.finish("record-again") },
        )
    } else {
        Button(onClick = { showing = true }) { Text("Reopen recording notice") }
    }
}
