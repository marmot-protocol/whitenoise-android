package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.whitenoise.android.state.ForwardRequestPersistence
import dev.ipf.whitenoise.android.state.PendingForwardRequest

/** Owns one cold restore opportunity for the bound conversation, even when its account summaries refresh. */
internal class ForwardPickerRestoration {
    private var checked = false
    var visible by mutableStateOf<PendingForwardRequest?>(null)
        private set

    /** Only the restored request can remain visible after the encrypted slot or either account changes. */
    suspend fun refresh(
        persistence: ForwardRequestPersistence,
        hasInventory: Boolean,
        disposition: (PendingForwardRequest) -> RestoredForwardDisposition,
        onDiscard: () -> Unit,
    ) {
        if ((!hasInventory && !checked) || (checked && visible == null)) return
        val stored = persistence.load()
        if (checked && stored?.requestId != visible?.requestId) {
            dismiss()
            return
        }
        if (!hasInventory) return
        checked = true
        if (stored == null || stored.requestId in persistence.openPickerIds) return
        when (disposition(stored)) {
            RestoredForwardDisposition.Restore -> if (visible == null) visible = stored
            RestoredForwardDisposition.Ignore -> dismiss()
            RestoredForwardDisposition.Discard -> {
                dismiss()
                persistence.discard(stored.requestId)
                onDiscard()
            }
        }
    }

    /** Dismissal consumes this conversation's restore opportunity until it leaves composition. */
    fun dismiss() {
        checked = true
        visible = null
    }
}
