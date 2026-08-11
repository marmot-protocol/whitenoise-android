package dev.ipf.whitenoise.android.search

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
