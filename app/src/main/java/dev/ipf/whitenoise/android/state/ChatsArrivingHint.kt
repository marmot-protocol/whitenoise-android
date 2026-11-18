package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Remembers, for this process only, the account that just linked this device. Its chats are added one by one by
 * the account's other devices and MDK reports no progress for that, so for a while an empty chat list explains
 * where existing chats come from instead of only inviting a new one. The wording holds whether or not the account
 * has chats. It is presentation state: it reads nothing from MDK and is never persisted.
 */
internal object ChatsArrivingHint {
    private var accountIdHex by mutableStateOf<String?>(null)
    private var endsAtMs = 0L

    /** Whether the hint is showing for some account, which the host uses to time it out. */
    val shown: Boolean get() = accountIdHex != null

    /** [id] just linked this device at [nowMs], so the hint runs for [durationMs] from then. */
    fun mark(
        id: String,
        nowMs: Long,
        durationMs: Long,
    ) {
        endsAtMs = nowMs + durationMs
        accountIdHex = id.trim().lowercase(Locale.ROOT)
    }

    /** How long the hint still has at [nowMs], so a host that comes back does not start the wait again. */
    fun remainingMs(nowMs: Long): Long = (endsAtMs - nowMs).coerceAtLeast(0L)

    /** Whether an empty chat list for [id] should explain where its chats come from. */
    fun showsFor(id: String?): Boolean = id != null && accountIdHex == id.trim().lowercase(Locale.ROOT)

    /** Ends the hint, because time passed. A chat appearing replaces the empty state on its own. */
    fun end() {
        accountIdHex = null
    }
}
