package dev.ipf.whitenoise.android.ui.profile

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.share.QrShareCardRenderer
import dev.ipf.whitenoise.android.share.QrShareCardSpec
import dev.ipf.whitenoise.android.ui.conversation.media.saveImageToGallery
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** A screen-owned export: no work or feedback survives a replaced origin before the storage write starts. */
internal class ProfileQrGallerySave(
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    var inProgress by mutableStateOf(false)
        private set

    /** Null means the sheet lost ownership or another save already owns the action. */
    suspend fun save(
        context: Context,
        spec: QrShareCardSpec,
        ownsSheet: () -> Boolean,
        render: suspend (QrShareCardSpec) -> ByteArray = QrShareCardRenderer::pngBytes,
        persist: suspend (ByteArray) -> Boolean = { bytes ->
            withContext(storageDispatcher) {
                coroutineContext.ensureActive()
                if (ownsSheet()) {
                    saveImageToGallery(
                        context.applicationContext,
                        bytes,
                        "profile_card_${UUID.randomUUID()}.png",
                        "image/png",
                    )
                } else {
                    false
                }
            }
        },
    ): Boolean? {
        if (inProgress || !ownsSheet()) return null
        inProgress = true
        try {
            val bytes = render(spec)
            return if (ownsSheet()) persist(bytes).takeIf { ownsSheet() } else null
        } finally {
            inProgress = false
        }
    }
}
