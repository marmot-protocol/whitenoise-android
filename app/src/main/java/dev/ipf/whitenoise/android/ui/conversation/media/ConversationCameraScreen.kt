@file:Suppress("FunctionNaming") // Unit-returning Compose controls follow the app convention.

package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** The camera's controls over a black preview: white chrome, as a phone camera shows it in every theme. */
private val CameraChrome = Color.White
private val CameraRecordRed = Color(0xFFE53935)
private val CameraScrim = Color.Black.copy(alpha = 0.5f)
private val CameraChipScrim = Color.Black.copy(alpha = 0.4f)
private val CameraButtonSize = 48.dp
private val PillShape = RoundedCornerShape(percent = 50)
private const val SECONDS_PER_MINUTE = 60

/** What the capture controls show: the chosen mode, the lens, and a recording's progress. */
internal data class ConversationCameraUiState(
    val mode: CameraMode = CameraMode.Photo,
    val recording: Boolean = false,
    val recordedSeconds: Long = 0,
    val busy: Boolean = false,
    val videoWithoutSound: Boolean = false,
)

/**
 * The camera's controls over [preview]: Close, a recording timer, a Photo / Video switch, the shutter and the lens
 * switch. The switch and the lens button disappear while recording; the shutter stops a recording in progress.
 */
@Composable
internal fun ConversationCameraContent(
    state: ConversationCameraUiState,
    onClose: () -> Unit,
    onModeChange: (CameraMode) -> Unit,
    onShutter: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxSize().background(Color.Black)) {
        preview()
        CameraTopBar(state, onClose)
        CameraBottomControls(
            state = state,
            onModeChange = onModeChange,
            onShutter = onShutter,
            onSwitchCamera = onSwitchCamera,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** Close at the start, and the recording timer centered while a recording runs. */
@Composable
private fun CameraTopBar(
    state: ConversationCameraUiState,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("camera.close")) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.camera_close), tint = CameraChrome)
        }
        Spacer(Modifier.weight(1f))
        if (state.recording) RecordingTimer(state.recordedSeconds)
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.size(CameraButtonSize))
    }
}

/** The no-sound notice, the mode switch, and the shutter between a spacer and the lens switch. */
@Composable
private fun CameraBottomControls(
    state: ConversationCameraUiState,
    onModeChange: (CameraMode) -> Unit,
    onShutter: () -> Unit,
    onSwitchCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state.mode == CameraMode.Video && state.videoWithoutSound) NoSoundNotice()
        if (!state.recording) CameraModeSwitch(state.mode, enabled = !state.busy, onModeChange = onModeChange)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.size(CameraButtonSize))
            ShutterButton(state, onShutter)
            if (state.recording) {
                Spacer(Modifier.size(CameraButtonSize))
            } else {
                IconButton(
                    onClick = onSwitchCamera,
                    enabled = !state.busy,
                    modifier = Modifier.testTag("camera.switch"),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_cameraswitch),
                        stringResource(R.string.camera_switch),
                        tint = CameraChrome,
                    )
                }
            }
        }
    }
}

/** Says that videos record without sound because microphone access was refused. */
@Composable
private fun NoSoundNotice() {
    Text(
        stringResource(R.string.camera_video_without_sound),
        color = CameraChrome,
        style = MaterialTheme.typography.bodySmall,
        textAlign = TextAlign.Center,
        modifier =
            Modifier
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .background(CameraScrim, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("camera.noSound"),
    )
}

/** The Photo / Video choice, a phone camera's mode row with the chosen mode in bold. */
@Composable
private fun CameraModeSwitch(
    mode: CameraMode,
    enabled: Boolean,
    onModeChange: (CameraMode) -> Unit,
) {
    Row(
        modifier = Modifier.selectableGroup().testTag("camera.modes"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CameraMode.entries.forEach { option ->
            val selected = option == mode
            val label = if (option == CameraMode.Photo) R.string.camera_mode_photo else R.string.camera_mode_video
            Text(
                stringResource(label),
                color = if (selected) Color.Black else CameraChrome,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .clip(PillShape)
                        .background(if (selected) CameraChrome else CameraChipScrim)
                        .selectable(
                            selected = selected,
                            enabled = enabled,
                            role = Role.Tab,
                            onClick = { onModeChange(option) },
                        ).padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("camera.mode.${option.name.lowercase()}"),
            )
        }
    }
}

/** A white ring around a white disc for a photo, a red disc for video, and a red square while recording. */
@Composable
private fun ShutterButton(
    state: ConversationCameraUiState,
    onShutter: () -> Unit,
) {
    val description =
        stringResource(
            when {
                state.recording -> R.string.camera_stop_recording
                state.mode == CameraMode.Video -> R.string.camera_start_recording
                else -> R.string.camera_take_photo
            },
        )
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(76.dp)
                .border(4.dp, CameraChrome, CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !state.busy, role = Role.Button, onClick = onShutter)
                .semantics { contentDescription = description }
                .testTag("camera.shutter"),
    ) {
        when {
            state.recording -> Box(Modifier.size(28.dp).background(CameraRecordRed, RoundedCornerShape(6.dp)))
            state.mode == CameraMode.Video -> Box(Modifier.size(58.dp).background(CameraRecordRed, CircleShape))
            else -> Box(Modifier.size(58.dp).background(CameraChrome, CircleShape))
        }
    }
}

/** A red dot and the recorded length, as minutes and seconds. */
@Composable
private fun RecordingTimer(seconds: Long) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .background(CameraScrim, PillShape)
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .testTag("camera.timer"),
    ) {
        Box(Modifier.size(10.dp).background(CameraRecordRed, CircleShape))
        Text(
            formatRecordingLength(seconds),
            color = CameraChrome,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/** A recording's length as `m:ss`, the form a phone camera's timer uses. */
internal fun formatRecordingLength(seconds: Long): String = "${seconds / SECONDS_PER_MINUTE}:${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}"
