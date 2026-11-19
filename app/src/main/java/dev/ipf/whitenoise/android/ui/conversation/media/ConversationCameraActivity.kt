@file:Suppress("FunctionNaming") // Unit-returning Compose controls follow the app convention.

package dev.ipf.whitenoise.android.ui.conversation.media

import android.Manifest
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseTheme

/**
 * The in-app camera a conversation opens for its Camera action: a phone-style camera that takes a photo or, after
 * switching to Video, records one, into the private outputs the launch reserved. It returns the kind it captured
 * through [CaptureConversationMedia]; closing it returns nothing, and a recording in progress is discarded.
 */
class ConversationCameraActivity : ComponentActivity() {
    /** Shows the camera over its reserved outputs, or finishes at once when the launch carried none. */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setResult(Activity.RESULT_CANCELED)
        val outputs = CaptureConversationMedia.outputs(intent)
        if (outputs == null) {
            finish()
            return
        }
        setContent {
            WhiteNoiseTheme(darkTheme = true) {
                ConversationCameraScreen(
                    outputs = outputs,
                    onCaptured = { kind ->
                        setResult(Activity.RESULT_OK, CaptureConversationMedia.result(kind))
                        finish()
                    },
                    onClose = ::finish,
                    onUnavailable = {
                        Toast.makeText(this, R.string.camera_unavailable, Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onCaptureFailed = {
                        Toast.makeText(this, R.string.camera_capture_failed, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

/**
 * Drives [ConversationCameraContent] with CameraX. The screen starts in Photo mode on the back camera; switching to
 * Video asks once for microphone access and records without sound when it is refused. A capture that writes its
 * output ends the screen with [onCaptured]; one that fails stays on the camera after [onCaptureFailed].
 */
@Composable
internal fun ConversationCameraScreen(
    outputs: CameraOutputs,
    onCaptured: (CameraCaptureKind) -> Unit,
    onClose: () -> Unit,
    onUnavailable: () -> Unit,
    onCaptureFailed: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val controller = remember(outputs) { ConversationCameraController(context, outputs) }
    val session = remember { ConversationCameraSession() }
    var microphoneAllowed by remember { mutableStateOf(hasMicrophoneAccess(context)) }
    var microphoneAsked by rememberSaveable { mutableStateOf(false) }
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val microphone =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { microphoneAllowed = it }
    DisposableEffect(controller) { onDispose { controller.release() } }
    LaunchedEffect(controller, session.mode, session.front) {
        controller.bind(lifecycleOwner, previewView, session.mode, session.front) {
            if (session.front) session.front = false else onUnavailable()
        }
    }
    BackHandler(onBack = onClose)
    ConversationCameraContent(
        state = session.uiState(videoWithoutSound = microphoneAsked && !microphoneAllowed),
        onClose = onClose,
        onModeChange = { next ->
            session.mode = next
            if (next == CameraMode.Video && !microphoneAllowed && !microphoneAsked) {
                microphoneAsked = true
                microphone.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onShutter = {
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            controller.pressShutter(session, rotation, onCaptured, onCaptureFailed)
        },
        onSwitchCamera = { session.front = !session.front },
        preview = { AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize()) },
    )
}

/** The camera screen's own state: the chosen mode and lens, and a photo or recording in progress. */
@Stable
internal class ConversationCameraSession {
    var mode by mutableStateOf(CameraMode.Photo)
    var front by mutableStateOf(false)
    var recording by mutableStateOf(false)
    var recordedSeconds by mutableLongStateOf(0L)
    var busy by mutableStateOf(false)

    /** What the controls show, with the no-sound notice when microphone access was refused. */
    fun uiState(videoWithoutSound: Boolean): ConversationCameraUiState =
        ConversationCameraUiState(
            mode = mode,
            recording = recording,
            recordedSeconds = recordedSeconds,
            busy = busy,
            videoWithoutSound = videoWithoutSound,
        )
}

/**
 * Stops a recording in progress, or takes a photo or starts a recording for [session]'s mode, turned to [rotation].
 * A photo or a finished recording that wrote its output goes to [onCaptured]; a failed one to [onCaptureFailed].
 */
internal fun ConversationCameraController.pressShutter(
    session: ConversationCameraSession,
    rotation: Int,
    onCaptured: (CameraCaptureKind) -> Unit,
    onCaptureFailed: () -> Unit,
) {
    when {
        session.recording -> stopRecording()
        session.mode == CameraMode.Photo -> {
            session.busy = true
            takePhoto(
                rotation,
                onSaved = { onCaptured(CameraCaptureKind.Photo) },
                onError = {
                    session.busy = false
                    onCaptureFailed()
                },
            )
        }
        else -> {
            session.recordedSeconds = 0
            session.recording =
                startRecording(
                    rotation,
                    onSeconds = { session.recordedSeconds = it },
                    onFinished = { usable ->
                        session.recording = false
                        if (usable) onCaptured(CameraCaptureKind.Video) else onCaptureFailed()
                    },
                )
            if (!session.recording) onCaptureFailed()
        }
    }
}
