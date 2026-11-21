package dev.ipf.whitenoise.android.audio

import android.os.Handler
import android.os.Looper

/** Native recorder closure never mutates controller ownership from a capture thread. */
internal fun mainThreadDictationCaptureClosure(onClosed: () -> Unit): () -> Unit =
    {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onClosed()
        } else {
            Handler(Looper.getMainLooper()).post(onClosed)
        }
    }

/** Closed PCM can leave the active slot without being discarded or acquiring a microphone. */
internal interface ConversationDictationParkedAudio {
    fun restore(): Boolean

    fun discard()
}

/** Main-looper owner retains a discarded recorder until its native closure is acknowledged. */
@Suppress("TooManyFunctions")
internal class ConversationDictationCaptureOwner(
    private val create: () -> ConversationDictationCallerAudio?,
) {
    private var current: ConversationDictationCallerAudio? = null
    private var closing: ConversationDictationCallerAudio? = null
    private var followUp: ConversationDictationCallerAudio? = null

    /** Whether this gesture's session recorded through an app-owned capture. */
    var usedThisSession = false
        private set

    /** Old closure callbacks remain registered, but cannot claim a replacement session. */
    fun beginSession() {
        closing = null
        usedThisSession = false
    }

    /** Preserve a retained recording even if a later provider lookup cannot authorize a new one. */
    fun acquire(allowCreate: Boolean = true): ConversationDictationCallerAudio? {
        val capture =
            current ?: if (allowCreate) {
                create().also { current = it }
            } else {
                null
            }
        if (capture != null) usedThisSession = true
        return capture
    }

    /** Records beside the session capture without attaching a provider stream. */
    fun beginFollowUp(): Boolean {
        val capture = if (followUp == null) create() else null
        if (capture == null) return false
        val started = capture.start()
        if (started) {
            followUp = capture
        } else {
            capture.discard()
        }
        return started
    }

    fun finishFollowUp(onClosed: () -> Unit): Boolean {
        val capture = followUp ?: return false
        capture.finish(mainThreadDictationCaptureClosure(onClosed))
        return true
    }

    fun discardFollowUp(onClosed: () -> Unit): Boolean {
        val capture = followUp ?: return false
        followUp = null
        capture.discard(mainThreadDictationCaptureClosure(onClosed))
        return true
    }

    /** The follow-up becomes the session capture only when the previous one has left the slot. */
    fun adoptFollowUp(): Boolean {
        val capture = followUp?.takeIf { current == null } ?: return false
        followUp = null
        closing = null
        current = capture
        usedThisSession = true
        return true
    }

    fun hasPending(): Boolean = current?.hasPending() == true

    /** Transfers only fully closed captures; the caller owns the returned bounded PCM lease. */
    fun park(): ConversationDictationParkedAudio? {
        val capture = current?.takeIf { it.closed && it.hasPending() } ?: return null
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
