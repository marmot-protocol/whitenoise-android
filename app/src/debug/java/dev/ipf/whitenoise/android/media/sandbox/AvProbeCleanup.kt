package dev.ipf.whitenoise.android.media.sandbox

/** Positive resource/connection receipts, never inferred from a cleanup attempt or elapsed time. */
internal class AvProbeCleanup {
    enum class State { PENDING, SUCCESS, HELD }

    enum class HoldReason { NONE, UNBIND_FAILED, SCOPES_NOT_CLOSED }

    private var inputClosed = true
    private var outputClosed = true
    private var revoked = false
    private var finished = false
    private var bindComplete = false
    private var proven = false
    private var attempted = false
    private var unbound: Boolean? = null

    @Synchronized fun inputScopeClosed(value: Boolean) {
        inputClosed = value
    }

    @Synchronized fun outputScopeClosed(value: Boolean) {
        outputClosed = value
    }

    @Synchronized fun revoked() {
        revoked = true
    }

    @Synchronized fun finished() {
        finished = true
    }

    @Synchronized fun bindComplete() {
        bindComplete = true
    }

    @Synchronized fun lifetimeProven() {
        proven = true
    }

    @Synchronized
    fun beginUnbind(): Boolean {
        val complete = revoked && finished && bindComplete
        val ready = !attempted && complete && proven
        if (ready) attempted = true
        return ready
    }

    @Synchronized
    fun unbound(success: Boolean) {
        check(attempted && unbound == null)
        unbound = success
    }

    @Synchronized
    fun holdReason(): HoldReason =
        when {
            unbound == false -> HoldReason.UNBIND_FAILED
            unbound == true && !(inputClosed && outputClosed) -> HoldReason.SCOPES_NOT_CLOSED
            else -> HoldReason.NONE
        }

    @Synchronized
    fun state(): State {
        val handlesClosed = inputClosed && outputClosed
        val operationComplete = revoked && finished
        return when {
            unbound == false -> State.HELD
            unbound == true && handlesClosed && operationComplete -> State.SUCCESS
            unbound == true -> State.HELD
            else -> State.PENDING
        }
    }
}
