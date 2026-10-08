package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.ui.geometry.Rect

/** The editor and navigation rectangles whose reservation must survive text-only edits. */
internal data class DraftTopBounds(val editor: Rect, val action: Rect)
