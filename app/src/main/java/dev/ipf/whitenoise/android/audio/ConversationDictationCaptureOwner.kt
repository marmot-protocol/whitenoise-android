package dev.ipf.whitenoise.android.audio

/** Main-looper owner retains a discarded recorder until its native closure is acknowledged. */
internal class ConversationDictationCaptureOwner(
    private val create: () -> ConversationDictationCallerAudio?,
) {
    private var current: ConversationDictationCallerAudio? = null
    private var closing: ConversationDictationCallerAudio? = null

    /** Old closure callbacks remain registered, but cannot claim a replacement session. */
    fun beginSession() {
        closing = null
    }

    fun acquire(): ConversationDictationCallerAudio? = current ?: create().also { current = it }

    fun hasPending(): Boolean = current?.hasPending() == true

    fun silenceMillis(): Long? = current?.silenceMillis()

    /** Consumes only the current retained recorder's already-published terminal receipt. */
    fun acknowledgeRetainedFailure() {
        current?.acknowledgeRetainedFailure()
    }

    fun finish(onClosed: () -> Unit): Boolean {
        val capture = current ?: closing ?: return false
        capture.finish(mainThreadDictationCaptureClosure(onClosed))
        return true
    }

    fun forceClose(onClosed: () -> Unit): Boolean {
        val capture = current ?: closing ?: return false
        capture.forceFinish(mainThreadDictationCaptureClosure(onClosed))
        return true
    }

    fun discard(onClosed: () -> Unit): Boolean {
        val capture = current ?: closing ?: return false
        current = null
        closing = capture
        capture.discard(
            mainThreadDictationCaptureClosure {
                if (closing === capture) closing = null
                onClosed()
            },
        )
        return true
    }
}
