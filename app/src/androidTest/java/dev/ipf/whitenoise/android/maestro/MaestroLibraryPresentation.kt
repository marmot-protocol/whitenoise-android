package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.conversation.media.rememberDocumentSaveFallback
import dev.ipf.whitenoise.android.ui.medialibrary.FileLibraryRow
import dev.ipf.whitenoise.android.ui.medialibrary.SharedMediaRow

/** Opens and dismisses the production file popup; no download, export, share or file-row click is requested. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroLibraryPresentation(fixture: MaestroPresentationFixture) {
    val message = checkNotNull(checkNotNull(fixture.nativeChat).latest)
    FileLibraryRow(
        row =
            SharedMediaRow(
                messageIdHex = message.messageIdHex,
                attachmentIndex = 0,
                reference =
                    MediaAttachmentReferenceFfi(
                        locators = emptyList(),
                        ciphertextSha256 = "11".repeat(32),
                        plaintextSha256 = "22".repeat(32),
                        nonceHex = "33".repeat(12),
                        fileName = "Fixture library document.txt",
                        mediaType = "text/plain",
                        version = EncryptedMediaVersionFfi.V1,
                        sourceEpoch = 1u,
                        dim = null,
                        thumbhash = null,
                    ),
                mine = true,
                recordedAt = 1_800_000_000u,
                sender = checkNotNull(fixture.appState.activeAccount).accountIdHex,
            ),
        controller = checkNotNull(fixture.nativeController),
        appState = fixture.appState,
        documentSaveFallback = rememberDocumentSaveFallback(),
    )
}
