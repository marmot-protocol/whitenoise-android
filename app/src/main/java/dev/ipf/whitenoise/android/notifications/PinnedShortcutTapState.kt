package dev.ipf.whitenoise.android.notifications

import android.content.Intent
import androidx.lifecycle.ViewModel

/** Retains only an unresolved pin tap across Activity recreation; capabilities never enter saved instance state. */
internal class PinnedShortcutTapState : ViewModel() {
    /** Cleared by replay or a newer accepted route; another task receives its own ViewModelStore. */
    var held: Intent? = null

    /** A finished task cannot retain its pending launcher capability through an abandoned owner. */
    override fun onCleared() {
        held = null
    }
}
