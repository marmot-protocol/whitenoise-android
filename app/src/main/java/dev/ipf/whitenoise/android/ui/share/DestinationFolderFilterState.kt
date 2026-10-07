package dev.ipf.whitenoise.android.ui.share

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ChatFolder
import java.util.Locale

/** Transient picker browsing state; it never writes recipient selection or home-screen preferences. */
@Stable
internal class DestinationFolderFilterState {
    var folderId by mutableStateOf<String?>(null)
        private set
    var reviewingSelected by mutableStateOf(false)
        private set

    /** Selects one browsing filter without changing any recipients. */
    fun selectFolder(id: String?) {
        folderId = id
        reviewingSelected = false
    }

    /** Reveals the complete explicit selection, including recipients hidden by a previous filter. */
    fun reviewSelected() {
        folderId = null
        reviewingSelected = true
    }

    /** Intersects the existing search results with eligible folder membership or the selected-only view. */
    fun accepts(
        id: String,
        rows: List<Pair<ChatFolder, List<String>>>,
        selected: List<String>,
    ): Boolean {
        val normalized = id.lowercase(Locale.ROOT)
        return when {
            reviewingSelected && selected.isNotEmpty() -> normalized in selected
            folderId != null -> rows.firstOrNull { it.first.id == folderId }?.second?.contains(normalized) ?: true
            else -> true
        }
    }
}
