package dev.ipf.whitenoise.android.audio

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Capture can end automatically before a person has chosen how to deliver its final text. */
internal class ConversationDictationCompletionIntent {
    private enum class Phase { Open, Automatic, Explicit, Committed }

    private var phase by mutableStateOf(Phase.Open)
    var explicit by mutableStateOf(false)
        private set
    var mode by mutableStateOf<ConversationDictationDeliveryMode?>(null)
        private set

    val canChooseExplicit: Boolean
        get() = phase == Phase.Open || phase == Phase.Automatic

    /** The first explicit gesture overrides the automatic default until delivery starts. */
    fun choose(
        delivery: ConversationDictationDeliveryMode?,
        automatic: Boolean = false,
    ): Boolean {
        if (!canChooseExplicit || (automatic && phase != Phase.Open)) return false
        mode = delivery
        explicit = !automatic
        phase = if (automatic) Phase.Automatic else Phase.Explicit
        return true
    }

    fun commit() {
        phase = Phase.Committed
    }

    fun reset() {
        phase = Phase.Open
        mode = null
        explicit = false
    }
}
