package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.state.ComposerShortcut
import dev.ipf.whitenoise.android.state.ComposerShortcutSide
import dev.ipf.whitenoise.android.state.ComposerToolbarSettings

/** Customization saves completed changes; drag projection stays transient until a valid drop. */
internal class ComposerToolbarEditState(
    initial: ComposerToolbarSettings,
) {
    var onChange: ((ComposerToolbarSettings) -> Unit)? = null
    var draft by mutableStateOf(initial.normalized())
        private set
    var dragging by mutableStateOf<ComposerShortcut?>(null)
        private set
    var release by mutableStateOf<ComposerToolbarRelease?>(null)
        private set
    private var grabOffset = Offset.Zero
    val dragCenter: Offset
        get() = pointer - grabOffset
    var insertion by mutableStateOf<Int?>(null)
        private set
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var insertionSide by mutableStateOf(ComposerShortcutSide.Left)
        private set
    val previewSettings: ComposerToolbarSettings
        get() = dragging?.let { id -> insertion?.let { draft.insertOnSide(id, insertionSide, it) } } ?: draft
    private var dragBounds = emptyMap<ComposerShortcut, Rect>()
    private var dragSource: ComposerToolbarSettings? = null
    private val selectedBounds = mutableMapOf<ComposerShortcut, Rect>()
    var previewRtl = false
    var selectedArea = Rect.Zero
        set(value) {
            field = value
            updateInsertion()
        }

    /** Replaces the draft after cancelling any in-progress drag. */
    fun change(settings: ComposerToolbarSettings) {
        cancelDrag()
        val next = settings.normalized()
        if (next == draft) return
        draft = next
        onChange?.invoke(next)
    }

    /** Updates a selected row’s bounds for drag insertion hit testing. */
    fun record(
        shortcut: ComposerShortcut,
        bounds: Rect,
        released: ComposerToolbarRelease? = null,
    ) {
        selectedBounds[shortcut] = bounds
        if (release === released && released != null && released.shortcut == shortcut && released.placed) {
            if (bounds.isEmpty) finishRelease(released) else released.target = bounds.center
        }
        updateInsertion()
    }

    /** Removes geometry when an icon leaves the preview. */
    fun forget(shortcut: ComposerShortcut) {
        selectedBounds.remove(shortcut)
        if (dragging == null) updateInsertion()
    }

    /** Captures the draft and pointer position for a new drag transaction. */
    fun start(
        shortcut: ComposerShortcut,
        position: Offset,
    ) {
        release = null
        grabOffset = position - (selectedBounds[shortcut]?.center ?: position)
        dragSource = draft
        dragBounds = selectedBounds.toMap()
        dragging = shortcut
        pointer = position
        updateInsertion()
    }

    /** Advances an active drag and recomputes its insertion target. */
    fun drag(delta: Offset) {
        if (dragging == null) return
        pointer += delta
        updateInsertion()
    }

    /** Finds the insertion index among the remaining rows inside the selected area. */
    private fun updateInsertion() {
        val id = dragging ?: return
        if (!selectedArea.contains(pointer)) {
            insertion = null
            return
        }
        // Freeze source centers for the transaction: live slot movement must not chase its own hit test.
        val nearest =
            dragBounds.filter { (key, rect) -> key != id && !rect.isEmpty }.minByOrNull { (_, rect) ->
                val delta = rect.center - pointer
                delta.x * delta.x + delta.y * delta.y
            }
        insertionSide = nearest?.let { draft.side(it.key) } ?: draft.side(id)
        val physicalLeft = pointer.x < selectedArea.center.x
        val hasLeft = draft.onSide(ComposerShortcutSide.Left).any { it != id }
        val hasRight = draft.onSide(ComposerShortcutSide.Right).any { it != id }
        if (physicalLeft && !hasLeft) insertionSide = ComposerShortcutSide.Left
        if (!physicalLeft && !hasRight) insertionSide = ComposerShortcutSide.Right
        val remaining = draft.onSide(insertionSide).filterNot { it == id }
        insertion = previewInsertion(remaining, dragBounds, pointer, previewRtl)
    }

    /** The parent owns pointer input so an icon can cross sides without cancelling its gesture. */
    fun startNearest(position: Offset): Boolean {
        val nearest =
            selectedBounds.entries
                .filter { (_, rect) -> rect.contains(position) }
                .minByOrNull { (_, rect) ->
                    val delta = rect.center - position
                    delta.x * delta.x + delta.y * delta.y
                } ?: return false
        start(nearest.key, position)
        return true
    }

    /** Commits a valid drop only while its captured draft is still current. */
    fun drop() {
        val id = dragging
        val from = dragCenter
        val index = insertion
        if (id != null && index != null && dragSource == draft) change(draft.insertOnSide(id, insertionSide, index))
        cancelDrag()
        if (id != null) release = ComposerToolbarRelease(id, from)
    }

    /** Marks an actual placement for the current release before accepting its root geometry. */
    fun placed(
        shortcut: ComposerShortcut,
        released: ComposerToolbarRelease,
    ) {
        if (release === released && released.shortcut == shortcut) released.placed = true
    }

    /** Retires only the current visual settling transaction. */
    fun finishRelease(expected: ComposerToolbarRelease) {
        if (release === expected) release = null
    }

    /** Clears transient drag state without changing the draft. */
    fun cancelDrag() {
        release = null
        dragging = null
        insertion = null
        dragSource = null
        dragBounds = emptyMap()
    }
}

/** One released glyph remains lifted until it reaches the final production slot. */
internal class ComposerToolbarRelease(
    val shortcut: ComposerShortcut,
    val from: Offset,
) {
    var placed = false
    var target by mutableStateOf<Offset?>(null)
}

/** Moves an existing shortcut by a bounded relative offset. */
internal fun ComposerToolbarEditState.move(
    shortcut: ComposerShortcut,
    delta: Int,
) {
    val side = draft.side(shortcut)
    val index = draft.onSide(side).indexOf(shortcut)
    if (index >= 0) change(draft.insertOnSide(shortcut, side, index + delta))
}

/** Appends a shortcut, moving an existing selection to the end. */
internal fun ComposerToolbarEditState.add(shortcut: ComposerShortcut) {
    change(draft.insert(shortcut, draft.shortcuts.size))
}

/** Removes an optional shortcut from the unsaved draft. */
internal fun ComposerToolbarEditState.remove(shortcut: ComposerShortcut) = change(draft.without(shortcut))

/** Chooses the nearest vertical row before its horizontal icon, then the logical leading/trailing half. */
private fun previewInsertion(
    remaining: List<ComposerShortcut>,
    selectedBounds: Map<ComposerShortcut, Rect>,
    pointer: Offset,
    previewRtl: Boolean,
): Int? {
    val nearest =
        remaining
            .mapIndexedNotNull { index, shortcut ->
                selectedBounds[shortcut]?.takeUnless { it.isEmpty }?.let { index to it }
            }.minWithOrNull(
                compareBy<Pair<Int, Rect>> { (_, bounds) ->
                    (bounds.top - pointer.y).coerceAtLeast(0f) + (pointer.y - bounds.bottom).coerceAtLeast(0f)
                }.thenBy { (_, bounds) ->
                    (bounds.left - pointer.x).coerceAtLeast(0f) + (pointer.x - bounds.right).coerceAtLeast(0f)
                },
            ) ?: return if (remaining.isEmpty()) 0 else null
    val (index, bounds) = nearest
    val after =
        when {
            pointer.y < bounds.top -> false
            pointer.y >= bounds.bottom -> true
            previewRtl -> pointer.x < bounds.center.x
            else -> pointer.x > bounds.center.x
        }
    return index + if (after) 1 else 0
}
