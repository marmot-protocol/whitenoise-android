package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * One account/conversation-owned capture through the in-app camera, retained by the protected shell's saved-state
 * registry. Each launch reserves a private photo output and a private video output; the camera reports which one it
 * wrote, and the other is removed. [onCaptured] returns false when the shelf refuses the capture — its output is then
 * removed too and [onRejected] explains why, so a readable capture is never dropped silently.
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
    var pending by
        rememberSaveable(stateSaver = PendingCameraLaunchSaver) { mutableStateOf<PendingCameraLaunch?>(null) }
    SideEffect { pending?.outputs?.forEach { (file, uri) -> captureFiles?.hold(file, uri, origin) } }
    val launcher =
        rememberLauncherForActivityResult(CaptureConversationMedia()) { kind ->
            val launch = pending
            // Claim before staging: a duplicate result cannot append another slot.
            pending = null
            if (launch != null) {
                val current = launch.origin == origin && isCurrent()
                deliverCameraResult(launch, kind, current, captureFiles, onCaptured, onRejected, onFailure)
            }
        }
    return {
        if (pending == null) {
            val files = checkNotNull(captureFiles) { "capture requires retained shell file ownership" }
            val launch = reserveCameraLaunch(context, origin)
            if (launch == null) {
                onFailure()
            } else {
                pending = launch
                launch.outputs.forEach { (file, uri) -> files.hold(file, uri, origin) }
                try {
                    launcher.launch(CameraOutputs(launch.photo.second, launch.video.second))
                } catch (_: RuntimeException) {
                    pending = null
                    launch.outputs.forEach { (file, _) -> discard(files, file) }
                    onFailure()
                }
            }
        }
    }
}

/** One launch's reserved outputs, as (file, uri) pairs, and the conversation it was opened from. */
private data class PendingCameraLaunch(
    val origin: String,
    val photo: Pair<File, Uri>,
    val video: Pair<File, Uri>,
) {
    val outputs: List<Pair<File, Uri>> get() = listOf(photo, video)

    /** The output the camera reported writing for [kind], or null when it closed without capturing. */
    fun captured(kind: CameraCaptureKind?): Pair<File, Uri>? =
        when (kind) {
            CameraCaptureKind.Photo -> photo
            CameraCaptureKind.Video -> video
            null -> null
        }
}

/**
 * Saves a pending launch as its origin and both outputs' absolute paths and URIs, so a restored process still owns
 * them and a camera closed after process death can still remove its empty outputs.
 */
private val PendingCameraLaunchSaver =
    Saver<PendingCameraLaunch?, List<String>>(
        save = { launch ->
            launch?.let {
                listOf(it.origin) + it.outputs.flatMap { (file, uri) -> listOf(file.absolutePath, uri.toString()) }
            }
        },
        restore = { saved ->
            saved
                .takeIf { it.size == SAVED_LAUNCH_FIELDS }
                ?.let { PendingCameraLaunch(it[0], File(it[1]) to Uri.parse(it[2]), File(it[3]) to Uri.parse(it[4])) }
        },
    )

private const val SAVED_LAUNCH_FIELDS = 5

/**
 * Removes the output the camera did not use, then stages the one it reported when its launch is still [current] and
 * the output holds bytes. A refused capture is removed and explained; an unreadable one is removed and reported.
 */
@Suppress("LongParameterList")
private fun deliverCameraResult(
    launch: PendingCameraLaunch,
    kind: CameraCaptureKind?,
    current: Boolean,
    files: PendingCameraCaptureFiles?,
    onCaptured: (Uri) -> Boolean,
    onRejected: () -> Unit,
    onFailure: () -> Unit,
) {
    val captured = launch.captured(kind)
    launch.outputs.filter { it != captured }.forEach { (file, _) -> discard(files, file) }
    when {
        captured == null -> Unit
        current && readableCameraCapture(captured.first) -> {
            val (file, uri) = captured
            // A restored registry may deliver before SideEffect reestablishes
            // the new process hold. Acquire it before the shelf can dispose.
            files?.hold(file, uri, launch.origin)
            if (!onCaptured(uri)) {
                discard(files, file)
                onRejected()
            }
        }
        else -> {
            discard(files, captured.first)
            if (current) onFailure()
        }
    }
}

/** Reserves a private photo output and video output for [origin], or returns null and leaves neither behind. */
private fun reserveCameraLaunch(
    context: Context,
    origin: String,
): PendingCameraLaunch? {
    val photo = createImageCaptureFile(context)
    val video = createVideoCaptureFile(context)
    if (photo == null || video == null) {
        photo?.delete()
        video?.delete()
        return null
    }
    return PendingCameraLaunch(
        origin,
        photo to fileProviderUri(context, photo),
        video to fileProviderUri(context, video),
    )
}

/** Ends this output's hold, if any, and removes it, for an unused, refused or unreadable capture. */
private fun discard(
    files: PendingCameraCaptureFiles?,
    file: File,
) {
    files?.release(file)
    file.delete()
}

/** True only when the exact private output the camera reported now holds readable bytes. */
private fun readableCameraCapture(file: File): Boolean = file.isFile && file.canRead() && file.length() > 0L
