package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Hoisted state for the anchored composer attachment menu. */
@Stable
internal class ComposerAttachmentSheetState {
    var isOpen by mutableStateOf(false)
        private set

    /** Opens the anchored action menu without taking focus from the editor. */
    fun open() {
        isOpen = true
    }

    /** Closes the anchored action menu. */
    fun dismiss() {
        isOpen = false
    }
}

/** Remembers the attachment menu state for the current composer owner. */
@Composable
internal fun rememberAttachmentState(): ComposerAttachmentSheetState = remember { ComposerAttachmentSheetState() }
