package dev.ipf.whitenoise.android.ui.chats.newchat

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription

/** Adds followed and selection state to rows that expose either relationship. */
internal fun Modifier.recipientRelationshipSemantics(
    followedStateDescription: String?,
    selectionState: Boolean?,
): Modifier {
    if (followedStateDescription == null && selectionState == null) return this
    return semantics(mergeDescendants = true) {
        followedStateDescription?.let { stateDescription = it }
        selectionState?.let { selected = it }
    }
}
