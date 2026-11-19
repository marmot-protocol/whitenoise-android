package dev.ipf.whitenoise.android.ui.conversation.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** The in-app camera's capture modes: a still photo, or a video recorded until it is stopped. */
internal enum class CameraMode {
    Photo,
    Video,
}

/**
 * Binds CameraX for one camera screen and writes each capture into the private output [outputs] reserved for it.
 * Each mode binds only the use cases it needs, so every device that can preview can also capture. Main-thread only;
 * [release] unbinds, and discards a recording in progress.
 */
internal class ConversationCameraController(
    private val context: Context,
    private val outputs: CameraOutputs,
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var recordingOutput: ParcelFileDescriptor? = null
    private var discardRecording = false
    private var released = false

    /**
     * Binds the preview and [mode]'s capture use case to [owner] on the front or back camera. [onUnavailable] runs
     * when the camera cannot be opened, for example when [front] asks for a camera the device does not have.
     */
    fun bind(
        owner: LifecycleOwner,
        previewView: PreviewView,
        mode: CameraMode,
        front: Boolean,
        onUnavailable: () -> Unit,
    ) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                val cameraProvider = runCatching { future.get() }.getOrNull()
                when {
                    released -> Unit
                    cameraProvider == null -> onUnavailable()
                    else -> bindUseCases(cameraProvider, owner, previewView, mode, front, onUnavailable)
                }
            },
            mainExecutor,
        )
    }

    /** Unbinds everything, then binds the preview with the one capture use case [mode] needs. */
    private fun bindUseCases(
        cameraProvider: ProcessCameraProvider,
        owner: LifecycleOwner,
        previewView: PreviewView,
        mode: CameraMode,
        front: Boolean,
        onUnavailable: () -> Unit,
    ) {
        provider = cameraProvider
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val capture =
            when (mode) {
                CameraMode.Photo ->
                    ImageCapture
                        .Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build()
                        .also { imageCapture = it }
                CameraMode.Video ->
                    VideoCapture
                        .withOutput(
                            Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.HD)).build(),
                        ).also { videoCapture = it }
            }
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        runCatching {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(owner, selector, preview, capture)
        }.onFailure {
            imageCapture = null
            videoCapture = null
            onUnavailable()
        }
    }

    /** Writes one photo into the photo output, turned to [rotation], then runs [onSaved] or [onError]. */
    fun takePhoto(
        rotation: Int,
        onSaved: () -> Unit,
        onError: () -> Unit,
    ) {
        val capture = imageCapture
        val stream = runCatching { context.contentResolver.openOutputStream(outputs.photo, "wt") }.getOrNull()
        if (capture == null || stream == null) {
            stream?.closeQuietly()
            onError()
            return
        }
        capture.targetRotation = rotation
        capture.takePicture(
            ImageCapture.OutputFileOptions.Builder(stream).build(),
            mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    stream.closeQuietly()
                    if (!released) onSaved()
                }

                override fun onError(exception: ImageCaptureException) {
                    stream.closeQuietly()
                    if (!released) onError()
                }
            },
        )
    }

    /**
     * Records into the video output, turned to [rotation] and with sound only when microphone access is granted.
     * [onSeconds] reports the recorded length; [onFinished] reports whether a usable video was written, once the
     * recording stops. Returns false when recording could not start.
     */
    @SuppressLint("MissingPermission") // Sound is enabled only after the RECORD_AUDIO check below.
    fun startRecording(
        rotation: Int,
        onSeconds: (Long) -> Unit,
        onFinished: (Boolean) -> Unit,
    ): Boolean {
        val capture = videoCapture
        val output = runCatching { context.contentResolver.openFileDescriptor(outputs.video, "rwt") }.getOrNull()
        if (capture == null || output == null || recording != null) {
            output?.closeQuietly()
            return false
        }
        capture.targetRotation = rotation
        discardRecording = false
        recordingOutput = output
        val pending = capture.output.prepareRecording(context, FileDescriptorOutputOptions.Builder(output).build())
        if (hasMicrophoneAccess(context)) pending.withAudioEnabled()
        recording =
            pending.start(mainExecutor) { event ->
                when (event) {
                    is VideoRecordEvent.Status ->
                        onSeconds(TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos))
                    is VideoRecordEvent.Finalize -> finishRecording(event, onFinished)
                    else -> Unit
                }
            }
        return true
    }

    /** Closes the finished recording's output and reports it, unless it was discarded. */
    private fun finishRecording(
        event: VideoRecordEvent.Finalize,
        onFinished: (Boolean) -> Unit,
    ) {
        recording = null
        recordingOutput?.closeQuietly()
        recordingOutput = null
        // A recording that reached a size or length limit still holds everything recorded until then.
        val usable =
            !event.hasError() ||
                event.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED ||
                event.error == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
        if (!discardRecording && !released) onFinished(usable)
    }

    /** Stops the recording in progress; its result arrives through [startRecording]'s `onFinished`. */
    fun stopRecording() {
        recording?.stop()
    }

    /** Unbinds the camera and discards any recording in progress. Later callbacks are ignored. */
    fun release() {
        released = true
        discardRecording = true
        recording?.stop()
        runCatching { provider?.unbindAll() }
        provider = null
        imageCapture = null
        videoCapture = null
    }
}

/** Whether the app may record sound with a video. */
internal fun hasMicrophoneAccess(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Closes an output whose capture has finished or failed; a close failure leaves nothing more to clean up. */
private fun OutputStream.closeQuietly() {
    runCatching { close() }
}

/** Closes a recording output once its recording has finalized. */
private fun ParcelFileDescriptor.closeQuietly() {
    runCatching { close() }
}
