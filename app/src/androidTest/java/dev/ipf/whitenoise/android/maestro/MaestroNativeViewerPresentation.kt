package dev.ipf.whitenoise.android.maestro

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.whitenoise.android.ui.conversation.media.ConversationMediaViewer
import dev.ipf.whitenoise.android.ui.conversation.media.MediaViewerPage
import java.io.ByteArrayOutputStream

/** Actual gallery/pager/decoder UI, with generated PNG bytes at its existing presentation boundary. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroNativeViewerPresentation(fixture: MaestroPresentationFixture) {
    val chat = checkNotNull(fixture.nativeChat)
    val message = checkNotNull(chat.latest)
    val count = if (fixture.scenario.endsWith("gallery")) 2 else 1
    val pages =
        remember(fixture) {
            (0 until count).map { index ->
                nativeViewerPage(fixture, message.messageIdHex, index)
            }
        }
    val bytes =
        remember(fixture) {
            ByteArrayOutputStream().use { output ->
                check(fixture.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        }
    ConversationMediaViewer(
        controller = checkNotNull(fixture.nativeController),
        appState = fixture.appState,
        conversationVisualPages = pages,
        messageIdHex = message.messageIdHex,
        attachments = pages.map { IndexedValue(it.attachmentIndex, it.reference) },
        tappedAttachmentIndex = 0,
        sender = checkNotNull(fixture.appState.activeAccount).accountIdHex,
        recordedAt = 1_800_000_000u,
        mine = true,
        onDismiss = { fixture.finish("dismiss") },
        imageBytes = { page ->
            check(page in pages)
            fixture.imagePagesLoaded.add(page.attachmentIndex)
            if (fixture.scenario.endsWith("malformed")) byteArrayOf(1, 2, 3) else bytes
        },
    )
}

private fun nativeViewerPage(
    fixture: MaestroPresentationFixture,
    messageIdHex: String,
    index: Int,
) = MediaViewerPage(
    messageIdHex = messageIdHex,
    attachmentIndex = index,
    reference =
        MediaAttachmentReferenceFfi(
            locators = emptyList(),
            ciphertextSha256 = "11".repeat(32),
            plaintextSha256 = "22".repeat(32),
            nonceHex = "33".repeat(12),
            fileName = "Fixture viewer $index.png",
            mediaType = "image/png",
            version = EncryptedMediaVersionFfi.V1,
            sourceEpoch = 1u,
            dim = null,
            thumbhash = null,
        ),
    mine = true,
    sender = checkNotNull(fixture.appState.activeAccount).accountIdHex,
    recordedAt = 1_800_000_000u,
)
