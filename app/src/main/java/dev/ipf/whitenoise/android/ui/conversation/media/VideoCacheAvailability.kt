package dev.ipf.whitenoise.android.ui.conversation.media

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import java.io.File

/** The off-main local-file probe used by the production video tiles. */
internal typealias VideoAttachmentCacheProbe = (Context, String, Int, MediaAttachmentReferenceFfi) -> File?

/** Keeps an unresolved local-file probe distinct from a known cache miss. */
internal class VideoCacheAvailability(
    val file: MutableState<File?>,
    val resolved: State<Boolean>,
)
