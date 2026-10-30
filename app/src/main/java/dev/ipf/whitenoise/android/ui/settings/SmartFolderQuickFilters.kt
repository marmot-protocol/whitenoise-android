package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter

internal val quickFolderFields =
    listOf(
        FolderField.UNREAD,
        FolderField.MENTIONS,
        FolderField.DRAFT,
        FolderField.MUTED,
        FolderField.PINNED,
        FolderField.ARCHIVED,
        FolderField.TYPE,
    )

/** Only canonical flat conjunctions can be edited without translating or flattening stored logic. */
internal fun SmartFolderFilter.Group.supportsQuickFilters(): Boolean {
    val conditions = children.filterIsInstance<SmartFolderFilter.Condition>()
    return all &&
        !not &&
        SmartFolderCodec.valid(this) &&
        conditions.size == children.size &&
        conditions.map { it.field }.distinct().size == conditions.size &&
        conditions.all {
            !it.not &&
                it.values.isEmpty() &&
                (it.field in quickFolderFields || it.field == FolderField.ACCEPTED)
        }
}

/** The selected mode of [field], or null when that paired control is neutral. */
internal fun SmartFolderFilter.Group.quickMode(field: FolderField): FolderMode? =
    children.filterIsInstance<SmartFolderFilter.Condition>().firstOrNull { it.field == field }?.mode

/**
 * Re-picking a cleared choice appends it, so two quick payloads that differ only in condition order are the
 * same rule. Anything else, including any rule quick controls cannot edit, still compares exactly.
 */
internal fun sameQuickFilterPayload(
    first: String?,
    second: String?,
): Boolean =
    first == second ||
        listOf(first, second)
            .map { payload ->
                payload
                    ?.let(SmartFolderCodec::decode)
                    ?.takeIf { it.supportsQuickFilters() }
                    ?.children
                    ?.toSet()
            }.let { (left, right) -> left != null && left == right }

/** Replace in place, append a new restriction, or clear just this predicate; untouched order is retained. */
internal fun SmartFolderFilter.Group.toggleQuickFilter(
    field: FolderField,
    mode: FolderMode,
): SmartFolderFilter.Group {
    val modes =
        if (field == FolderField.TYPE) {
            setOf(FolderMode.DIRECT, FolderMode.GROUP)
        } else {
            setOf(FolderMode.PRESENT, FolderMode.NONE)
        }
    if (!supportsQuickFilters() || field !in quickFolderFields || mode !in modes) return this
    val previous = quickMode(field)
    val next = if (previous == mode) null else SmartFolderFilter.Condition(field, mode)
    val updated =
        if (previous == null) {
            children + listOfNotNull(next)
        } else {
            children.flatMap {
                if ((it as? SmartFolderFilter.Condition)?.field == field) {
                    listOfNotNull(next)
                } else {
                    listOf(it)
                }
            }
        }
    return copy(children = updated)
}

/** Reset only the visible paired predicates, retaining the explicitly disclosed accepted-chat scope. */
internal fun SmartFolderFilter.Group.resetQuickFilters(): SmartFolderFilter.Group =
    if (!supportsQuickFilters()) {
        this
    } else {
        copy(
            children = children.filterNot { (it as? SmartFolderFilter.Condition)?.field in quickFolderFields },
        )
    }
