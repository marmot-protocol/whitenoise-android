package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import dev.ipf.whitenoise.android.ui.conversation.media.MediaPreviewScreen
import dev.ipf.whitenoise.android.ui.conversation.media.PendingMediaSlot

/** Decode a generated local image in the production staged preview; never dispatch an attachment. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroPreviewPresentation(fixture: MaestroPresentationFixture) {
    val slot = PendingMediaSlot("generated-image", fixture.imageUri)
    MediaPreviewScreen(
        mediaSlots = listOf(slot),
        documentUris = emptyList(),
        chatTitle = "Fixture destination",
        initialCaption = "Fixture caption",
        onDismiss = { fixture.finish("dismiss") },
        onSend = { caption, accepted ->
            check(caption == "Fixture caption")
            if (fixture.scenario == "preview-rejected") {
                fixture.record("send-rejected")
                accepted(false)
            } else {
                accepted(true)
                fixture.finish("send-handoff")
            }
        },
        onRemoveAt = {
            check(it == 0)
            fixture.record("remove-image")
        },
        onRemoveDocumentAt = { error("Generated image preview has no documents") },
        onAddPhotos = { fixture.finish("photos-handoff") },
        onAddDocuments = { fixture.finish("documents-handoff") },
        onEditMediaAt = {
            check(it == 0)
            fixture.finish("edit-handoff")
        },
        previewOnly = fixture.scenario == "preview-view-only",
    )
}
