package dev.ipf.whitenoise.android.search

import androidx.annotation.StringRes
import dev.ipf.whitenoise.android.R

enum class GlobalSearchContentKind {
    TEXT,
    LINKS,
    IMAGES_VIDEO,
    VOICE_AUDIO,
    FILES_DOCUMENTS,
    ANY_ATTACHMENT,
}

class GlobalSearchContentFilterSelection(
    selectedKinds: Set<GlobalSearchContentKind> = emptySet(),
) {
    val selectedKinds: Set<GlobalSearchContentKind> = selectedKinds.toSet()

    val isActive: Boolean
        get() = selectedKinds.isNotEmpty()

    fun toggle(kind: GlobalSearchContentKind): GlobalSearchContentFilterSelection =
        GlobalSearchContentFilterSelection(
            if (kind in selectedKinds) selectedKinds - kind else selectedKinds + kind,
        )

    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is GlobalSearchContentFilterSelection && selectedKinds == other.selectedKinds)

    override fun hashCode(): Int = selectedKinds.hashCode()

    override fun toString(): String = "GlobalSearchContentFilterSelection(selectedKinds=$selectedKinds)"

    companion object {
        val EMPTY = GlobalSearchContentFilterSelection()
    }
}

@StringRes
internal fun GlobalSearchContentKind.labelRes(): Int =
    when (this) {
        GlobalSearchContentKind.TEXT -> R.string.global_search_content_text
        GlobalSearchContentKind.LINKS -> R.string.global_search_content_links
        GlobalSearchContentKind.IMAGES_VIDEO -> R.string.global_search_content_images_video
        GlobalSearchContentKind.VOICE_AUDIO -> R.string.global_search_content_voice_audio
        GlobalSearchContentKind.FILES_DOCUMENTS -> R.string.global_search_content_files_documents
        GlobalSearchContentKind.ANY_ATTACHMENT -> R.string.global_search_content_any_attachment
    }
