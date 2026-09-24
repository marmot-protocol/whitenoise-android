package dev.ipf.whitenoise.android.state

/**
 * Keeps an edit of an in-flight local send bound to its client token until the
 * original has an authoritative event id. This is bounded process-lifetime
 * state; MDK remains the owner of the original send and its durable outcome.
 */
internal class PendingMessageEditHandoff {
    private data class Entry(
        var confirmedId: String? = null,
        var ready: Boolean = false,
        var editing: Boolean = true,
        var queuedText: String? = null,
    )

    private val entries = mutableMapOf<String, Entry>()

    fun begin(clientToken: String) {
        entries.getOrPut(clientToken, ::Entry).editing = true
    }

    sealed interface Submission {
        data class Publish(
            val targetId: String,
        ) : Submission

        data object Deferred : Submission
    }

    fun submit(
        scopedClientToken: String,
        targetId: String,
        text: String,
    ): Submission {
        val entry = entries[scopedClientToken] ?: return Submission.Publish(targetId)
        entry.editing = false
        val confirmedId = entry.confirmedId
        return if (entry.ready && confirmedId != null) {
            entries.remove(scopedClientToken)
            Submission.Publish(confirmedId)
        } else {
            entry.queuedText = text
            Submission.Deferred
        }
    }

    /** Returns one latest queued revision only when MDK has published the original. */
    fun confirm(
        clientToken: String,
        confirmedId: String,
        ready: Boolean,
    ): String? {
        val entry = entries[clientToken] ?: return null
        return if (confirmedId == clientToken) {
            null
        } else {
            entry.confirmedId = confirmedId
            entry.ready = entry.ready || ready
            if (!entry.ready || entry.editing) {
                null
            } else {
                entries.remove(clientToken)
                entry.queuedText
            }
        }
    }

    fun cancel(clientToken: String) {
        val entry = entries[clientToken] ?: return
        entry.editing = false
        if (entry.queuedText == null) entries.remove(clientToken)
    }

    fun abandon(clientToken: String) {
        entries.remove(clientToken)
    }

    fun removeAccount(accountRef: String) {
        entries.keys.removeAll { it.startsWith("$accountRef|") }
    }

    fun hasSession(clientToken: String): Boolean = clientToken in entries
}
