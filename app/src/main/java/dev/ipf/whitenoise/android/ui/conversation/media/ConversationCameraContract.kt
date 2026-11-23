package dev.ipf.whitenoise.android.ui.conversation.media

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.content.IntentCompat

/** What the in-app camera wrote: a photo or a video, each into its own private output. */
internal enum class CameraCaptureKind {
    Photo,
    Video,
}

/** The private outputs the camera may write; the kind it returns says which one now holds the capture. */
internal data class CameraOutputs(
    val photo: Uri,
    val video: Uri,
)

/**
 * Opens [ConversationCameraActivity] on [CameraOutputs] and returns the kind it captured, or `null` when the
 * person closed the camera without capturing. It replaces the system photo intent, which cannot switch to video.
 */
internal class CaptureConversationMedia : ActivityResultContract<CameraOutputs, CameraCaptureKind?>() {
    override fun createIntent(
        context: Context,
        input: CameraOutputs,
    ): Intent =
        Intent(context, ConversationCameraActivity::class.java)
            .putExtra(EXTRA_PHOTO_OUTPUT, input.photo)
            .putExtra(EXTRA_VIDEO_OUTPUT, input.video)

    override fun parseResult(
        resultCode: Int,
        intent: Intent?,
    ): CameraCaptureKind? =
        intent
            ?.getStringExtra(EXTRA_CAPTURED_KIND)
            ?.takeIf { resultCode == Activity.RESULT_OK }
            ?.let { name -> CameraCaptureKind.entries.firstOrNull { it.name == name } }

    companion object {
        const val EXTRA_PHOTO_OUTPUT = "dev.ipf.whitenoise.android.camera.PHOTO_OUTPUT"
        const val EXTRA_VIDEO_OUTPUT = "dev.ipf.whitenoise.android.camera.VIDEO_OUTPUT"
        const val EXTRA_CAPTURED_KIND = "dev.ipf.whitenoise.android.camera.CAPTURED_KIND"

        /** The result the camera returns after writing [kind] into its output. */
        fun result(kind: CameraCaptureKind): Intent = Intent().putExtra(EXTRA_CAPTURED_KIND, kind.name)

        /** The outputs a camera launch carries, or `null` when either is missing. */
        fun outputs(intent: Intent): CameraOutputs? {
            val photo = IntentCompat.getParcelableExtra(intent, EXTRA_PHOTO_OUTPUT, Uri::class.java)
            val video = IntentCompat.getParcelableExtra(intent, EXTRA_VIDEO_OUTPUT, Uri::class.java)
            return if (photo != null && video != null) CameraOutputs(photo, video) else null
        }
    }
}
