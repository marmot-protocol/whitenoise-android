package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Remembers, for this process only, the account that just linked this device. Its chats are added one by one by
 * the account's other devices, so for a while an empty chat list means "not here yet", not "none". It is
 * presentation state: it reads nothing from MDK and is never persisted.
 */
internal object ChatsArrivingHint {
    private var accountIdHex by mutableStateOf<String?>(null)

    /** Whether the hint is showing for some account, which the host uses to time it out. */
    val shown: Boolean get() = accountIdHex != null

    /** [id] just linked this device, so its chats are still on their way. */
    fun mark(id: String) {
        accountIdHex = id.trim().lowercase(Locale.ROOT)
    }

    /** Whether an empty chat list for [id] should say its chats are arriving. */
    fun showsFor(id: String?): Boolean = id != null && accountIdHex == id.trim().lowercase(Locale.ROOT)

    /** Ends the hint, because time passed. A chat appearing replaces the empty state on its own. */
    fun end() {
        accountIdHex = null
    }
}
