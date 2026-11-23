package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.audio.VoiceRecordingController

/** The lock target above the held microphone; it rises with the finger and fills once release would lock. */
@Composable
@Suppress("FunctionNaming")
internal fun LockHintAbove(
    controller: VoiceRecordingController,
    modifier: Modifier = Modifier,
) {
    if (controller.locked || !controller.isRecording) return
    val density = LocalDensity.current
    val rawDp = with(density) { (-controller.hold.verticalOffsetPx).toDp() }
    val rise = rawDp.value.coerceIn(0f, 80f).dp
    val armed = controller.hold.willLock
    Box(
        modifier =
            modifier
                .offset(y = -rise - 56.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (armed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painterResource(R.drawable.ic_lock),
            contentDescription = null,
            tint =
                if (armed) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            modifier = Modifier.size(18.dp),
        )
    }
}
