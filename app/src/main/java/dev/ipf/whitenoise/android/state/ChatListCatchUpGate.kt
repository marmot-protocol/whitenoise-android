package dev.ipf.whitenoise.android.state

/**
 * Limits automatic full account catch-up to the initial local projection of one chat-list bind.
 *
 * Local subscription streams can end and reopen without indicating that the network session has
 * changed. Those stream retries must not amplify into repeated full account synchronization.
 */
internal class ChatListCatchUpGate {
    private var claimed = false

    /** Claims the bind's single automatic full account catch-up. */
    fun claimInitial(): Boolean {
        if (claimed) return false
        claimed = true
        return true
    }
}
