package dev.ipf.whitenoise.android.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The one owner of "a staged-attachment send is in flight" for a mounted conversation composer.
 *
 * Keyboard Send and dictation Send reach the same attachment send, and each captures the staged shelf when it
 * starts but only removes it once the send is accepted. Two sends that overlap in that window would capture the
 * same shelf and queue every attachment twice, so the claim is taken here, on the main thread, before the shelf
 * is captured, and a competing send is refused without touching the first send's ownership.
 *
 * [isHeld] is Compose snapshot state, so the shelf UI that locks while a send is pending recomposes on release.
 * Claiming and releasing belong on the main thread, where the check-then-claim in [tryClaim] cannot interleave.
 */
@Stable
internal class StagedAttachmentSendClaim {
    private var holder by mutableStateOf<Hold?>(null)

    /** Whether a send currently owns the shelf. Safe to read from any thread, only the main thread may claim. */
    val isHeld: Boolean
        get() = holder != null

    /** Takes the claim and returns its [Hold], or null, changing nothing, when a send already owns it. */
    fun tryClaim(): Hold? {
        if (holder != null) return null
        return Hold().also { holder = it }
    }

    /**
     * Runs one attachment send under the claim, reporting through [onResult] whether it was accepted.
     *
     * A held claim refuses with `onResult(false)` and changes nothing else, so the earlier send keeps its
     * ownership. Otherwise [canStart] gets a say before anything is claimed (returning false refuses the same
     * way, after reporting its own reason), then the claim is taken and [start] captures the shelf and begins
     * the send. [start] must call exactly one of the callbacks it is handed once the send settles, and either
     * one releases the claim before reporting. A [start] that throws before returning releases the claim and
     * rethrows, so a failed dispatch cannot lock the shelf.
     */
    fun send(
        onResult: (accepted: Boolean) -> Unit,
        canStart: () -> Boolean = { true },
        start: (onAccepted: () -> Unit, onRejected: () -> Unit) -> Unit,
    ) {
        if (isHeld || !canStart()) {
            onResult(false)
            return
        }
        val hold = tryClaim()
        if (hold == null) {
            onResult(false)
            return
        }
        var started = false
        try {
            start(
                {
                    hold.release()
                    onResult(true)
                },
                {
                    hold.release()
                    onResult(false)
                },
            )
            started = true
        } finally {
            if (!started) hold.release()
        }
    }

    /** Proof of ownership, so only the send that took the claim can give it up, and only once. */
    inner class Hold internal constructor() {
        /** Gives the claim up if this hold still owns it, so a stale or repeated release cannot free a newer send. */
        fun release() {
            if (holder === this) holder = null
        }
    }
}
