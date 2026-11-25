package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import dev.ipf.whitenoise.android.ui.common.Explanation

/** One visible native capability; dispatch remains in the owning message. */
internal data class FocusedMessageAction(
    val label: String,
    val supportingLabel: String?,
    val enabled: Boolean,
    val destructive: Boolean,
    val icon: @Composable () -> Unit,
    val onClick: () -> Unit,
    val onClickAt: ((Rect?) -> Unit)? = null,
    val explanation: Explanation? = null,
    val dismissBeforeActivation: Boolean = true,
    /** False puts the action behind the menu's More actions disclosure (#4077). */
    val firstLevel: Boolean = true,
)
