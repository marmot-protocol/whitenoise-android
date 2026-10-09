package dev.ipf.whitenoise.android.ui.conversation

/** Separates one UI attempt result from the logical send's eventual, identity-checked shelf cleanup. */
internal class StagedMediaSendCompletion(
    private val onAccepted: () -> Unit,
    private val onRejected: () -> Unit,
    private val onSettled: () -> Unit,
) {
    private var reported = false
    private var settled = false

    /** Durable success can follow a rejected attempt via bubble Retry, but never reports that attempt twice. */
    fun accept() {
        if (!settled) {
            settled = true
            onSettled()
        }
        if (!reported) {
            reported = true
            onAccepted()
        }
    }

    /** Releases the attempt's claim once while preserving the logical send's separate completion owner. */
    fun reject() {
        if (!reported) {
            reported = true
            onRejected()
        }
    }
}
