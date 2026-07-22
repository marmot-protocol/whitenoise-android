package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Hold inbound draft hydration while an edit session owns the composer. Once
 * editing ends, the newest share revision is applied and rehydrates the normal
 * draft instead of ever entering the edit send path.
 */
@Composable
internal fun rememberComposerShareRevision(
    externalRevision: Int,
    editingMessageId: String?,
): Int {
    var appliedRevision by remember { mutableIntStateOf(externalRevision) }
    LaunchedEffect(externalRevision, editingMessageId) {
        if (editingMessageId == null) {
            appliedRevision = externalRevision
        }
    }
    return appliedRevision
}
