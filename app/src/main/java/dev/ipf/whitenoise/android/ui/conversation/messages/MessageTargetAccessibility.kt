package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics

/** Exposes the temporary destination cue without moving focus or merging away child actions. */
internal fun messageTargetAccessibility(highlighted: Boolean): Modifier =
    if (highlighted) {
        Modifier.semantics {
            selected = true
            liveRegion = LiveRegionMode.Polite
        }
    } else {
        Modifier
    }
