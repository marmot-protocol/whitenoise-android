package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardKind
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventCardModel
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventImagePane
import dev.ipf.whitenoise.android.ui.conversation.nostr.NostrEventReaderDialog

/** Reader presentation begins after resolution; signature/relay verification remains a native campaign. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroNostrPresentation(fixture: MaestroPresentationFixture) {
    when (fixture.scenario) {
        "nostr-image", "nostr-image-failure" ->
            NostrEventImagePane(
                url = "https://example.invalid/maestro.png",
                loadImage = { _, _ ->
                    fixture.record("image-load")
                    if (fixture.scenario == "nostr-image") fixture.image else null
                },
            )
        "nostr-note", "nostr-article" -> {
            val article = fixture.scenario == "nostr-article"
            val card =
                NostrEventCardModel(
                    kind = if (article) NostrEventCardKind.Article else NostrEventCardKind.Note,
                    eventIdHex = "a".repeat(64),
                    authorPubkeyHex = "b".repeat(64),
                    createdAt = 1_700_000_000,
                    eventKind = if (article) 30_023 else 1,
                    title = if (article) "Maestro article title" else null,
                    summary = null,
                    readerBody = "Maestro resolved reader body",
                )
            NostrEventReaderDialog(
                card = card,
                authoredReference = null,
                authorDisplayName = { "Maestro author" },
                mentionDisplayName = { null },
                onNostrProfileTap = { fixture.record("profile") },
                parseMarkdown = { MarkdownDocumentFfi(emptyList(), false) },
                onDismiss = { fixture.finish("dismiss") },
            )
        }
        else -> error("Unknown Nostr presentation")
    }
}
