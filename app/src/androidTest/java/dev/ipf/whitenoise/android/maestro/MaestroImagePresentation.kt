package dev.ipf.whitenoise.android.maestro

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.ipf.whitenoise.android.media.IdentityImageCropShape
import dev.ipf.whitenoise.android.media.editor.EditorPixelSize
import dev.ipf.whitenoise.android.media.editor.PhotoEditRecipe
import dev.ipf.whitenoise.android.media.editor.PhotoEditorSourceInfo
import dev.ipf.whitenoise.android.state.MediaQuality
import dev.ipf.whitenoise.android.ui.common.IdentityImageCropDialog
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorDialog
import dev.ipf.whitenoise.android.ui.conversation.media.editor.PhotoEditorStateHolder
import dev.ipf.whitenoise.android.ui.profile.AvatarFullScreenViewer

/** Generated pixels exercise real crop/editor/viewer controls without changing a profile or uploading an image. */
@Composable
@Suppress("FunctionNaming")
internal fun MaestroImagePresentation(fixture: MaestroPresentationFixture) {
    val size = EditorPixelSize(fixture.image.width, fixture.image.height)
    when (fixture.scenario) {
        "image-avatar" ->
            AvatarFullScreenViewer(
                title = "Maestro synthetic avatar",
                seed = "maestro-presentation",
                picture = fixture.image,
                onDismiss = { fixture.finish("dismiss") },
            )
        "image-crop-cancel", "image-crop-rotate", "image-crop-square" ->
            IdentityImageCropDialog(
                preview = fixture.image,
                sourceSize = size,
                shape =
                    if (fixture.scenario == "image-crop-square") {
                        IdentityImageCropShape.RoundedSquare
                    } else {
                        IdentityImageCropShape.Circle
                    },
                onDismiss = { fixture.finish("dismiss") },
                onConfirm = {
                    check(it.quarterTurnsClockwise == if (fixture.scenario == "image-crop-rotate") 1 else 0)
                    fixture.finish("crop-confirm")
                },
            )
        "image-editor-cancel", "image-editor-coordinates", "image-editor-draw", "image-editor-quality" -> {
            val holder = remember { PhotoEditorStateHolder(PhotoEditRecipe.Original, MediaQuality.Standard, size) }
            PhotoEditorDialog(
                previewBitmap = fixture.bitmap,
                sourceInfo = PhotoEditorSourceInfo(size, size, 1, "image/png", false),
                stateHolder = holder,
                onCancel = { fixture.finish("dismiss") },
                onSave = { _, _ -> fixture.finish("editor-save-handoff") },
            )
        }
        else -> error("Unknown image presentation")
    }
}
