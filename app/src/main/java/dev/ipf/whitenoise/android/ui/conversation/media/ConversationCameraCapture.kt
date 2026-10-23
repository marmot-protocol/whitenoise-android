package dev.ipf.whitenoise.android.ui.conversation.media

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * One account/conversation-owned capture, retained by the protected shell's saved-state registry.
 * [onCaptured] returns false when the shelf refuses the photo — its private output is then removed
 * and [onRejected] explains why, so a readable capture is never dropped silently.
 */
@Composable
internal fun rememberConversationCameraCapture(
    origin: String,
    onCaptured: (Uri) -> Boolean,
    onFailure: () -> Unit,
    isCurrent: () -> Boolean,
    onRejected: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val captureFiles = LocalPendingCameraCaptureFiles.current
    var requestOrigin by rememberSaveable { mutableStateOf<String?>(null) }
    var outputUri by rememberSaveable(stateSaver = NullableUriSaver) { mutableStateOf<Uri?>(null) }
    var outputFile by rememberSaveable(stateSaver = NullableFileSaver) { mutableStateOf<File?>(null) }
    SideEffect {
        val file = outputFile
        val uri = outputUri
        if (file != null && uri != null) captureFiles?.hold(file, uri, origin)
    }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            val uri = outputUri
            val file = outputFile
            val owner = requestOrigin
            // Claim before staging: a duplicate result cannot append another slot.
            outputUri = null
            outputFile = null
            requestOrigin = null
            if (owner == null) return@rememberLauncherForActivityResult
            val current = owner == origin && isCurrent()
            if (current && readableCameraCapture(success, file, uri)) {
                // A restored registry may deliver before SideEffect reestablishes
                // the new process hold. Acquire it before the shelf can dispose.
                captureFiles?.hold(requireNotNull(file), requireNotNull(uri), origin)
                if (!onCaptured(requireNotNull(uri))) {
                    captureFiles?.release(requireNotNull(file))
                    file?.delete()
                    onRejected()
                }
            } else {
                file?.let { captureFiles?.release(it) }
                file?.delete()
                if (success && current) onFailure()
            }
        }
    return {
        if (outputUri == null) {
            val files = checkNotNull(captureFiles) { "capture requires retained shell file ownership" }
            val file = createImageCaptureFile(context)
            if (file == null) {
                onFailure()
            } else {
                val uri = fileProviderUri(context, file)
                requestOrigin = origin
                outputFile = file
                outputUri = uri
                files.hold(file, uri, origin)
                try {
                    launcher.launch(uri)
                } catch (_: RuntimeException) {
                    outputUri = null
                    outputFile = null
                    requestOrigin = null
                    files.release(file)
                    file.delete()
                    onFailure()
                }
            }
        }
    }
}

/** True only for an accepted result whose exact private output now holds readable bytes. */
private fun readableCameraCapture(
    success: Boolean,
    file: File?,
    uri: Uri?,
): Boolean = success && uri != null && file?.isFile == true && file.canRead() && file.length() > 0L
