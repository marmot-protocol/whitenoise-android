package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.FileUploadProgress

/**
 * The play disc's upload ring, reading [progress] here so only the ring recomposes as bytes move. It is
 * determinate while a share of work is known and spins otherwise, and it names the step and its bytes for
 * TalkBack, as the download ring on a video tile does.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun VideoUploadIndicator(progress: () -> FileUploadProgress?) {
    val current = progress()
    val fraction = current?.ringFraction
    val description = current?.let { uploadProgressDescription(it) }
    val spoken =
        if (description != null) {
            Modifier.semantics { contentDescription = description }
        } else {
            Modifier
        }
    if (fraction != null) {
        CircularProgressIndicator(
            progress = { fraction },
            modifier = Modifier.size(24.dp).then(spoken),
            strokeWidth = 2.5.dp,
            color = LocalContentColor.current,
        )
    } else {
        CircularProgressIndicator(
            modifier = Modifier.size(24.dp).then(spoken),
            strokeWidth = 2.5.dp,
            color = LocalContentColor.current,
        )
    }
}

/** The video play disc's progress ring while its clip downloads, named as the voice note's Downloading state is. */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun VideoDownloadingRing() {
    val downloading = stringResource(R.string.media_downloading)
    CircularProgressIndicator(
        modifier = Modifier.size(24.dp).semantics { contentDescription = downloading },
        strokeWidth = 2.dp,
        color = LocalContentColor.current,
    )
}
