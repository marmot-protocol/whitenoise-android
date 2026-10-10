package dev.ipf.whitenoise.android.audio

/** Closed PCM can leave the active slot without being discarded or acquiring a microphone. */
internal interface ConversationDictationParkedAudio {
    fun restore(): Boolean

    fun discard()
}

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

    /** Transfers only fully closed captures; the caller owns the returned bounded PCM lease. */
    fun park(): ConversationDictationParkedAudio? {
        val capture = current ?: return null
        if (!capture.closed || !capture.hasPending()) return null
        current = null
        return object : ConversationDictationParkedAudio {
            private var owned = true

            override fun restore(): Boolean {
                if (!owned || current != null || closing != null) return false
                current = capture
                owned = false
                return true
            }

            override fun discard() {
                if (!owned) return
                owned = false
                capture.discard {}
            }
        }
    }

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
