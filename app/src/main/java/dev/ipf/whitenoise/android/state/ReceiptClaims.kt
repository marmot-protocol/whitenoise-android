package dev.ipf.whitenoise.android.state

/**
 * The receipts whose bounded history lookup has already run, so a message that both the notification stream and
 * the runtime event stream report is looked up once. Bounded to the most recent [capacity] claims: an older
 * duplicate looked up again only repeats an idempotent enqueue.
 */
internal class ReceiptClaims(
    private val capacity: Int,
) {
    private val claimed = LinkedHashSet<String>()

    /** Claims [messageIdHex] of [accountRef] for a lookup, returning false when another source already did. */
    fun claim(
        accountRef: String,
        messageIdHex: String,
    ): Boolean =
        synchronized(this) {
            val added = claimed.add(key(accountRef, messageIdHex))
            if (added && claimed.size > capacity) claimed.remove(claimed.first())
            added
        }

    /** Releases a claim whose lookup failed, so a later receipt for the same message can look it up again. */
    fun release(
        accountRef: String,
        messageIdHex: String,
    ) {
        synchronized(this) { claimed.remove(key(accountRef, messageIdHex)) }
    }

    /** One claim per account and case-insensitive message id. */
    private fun key(
        accountRef: String,
        messageIdHex: String,
    ): String = "$accountRef\u0000${messageIdHex.lowercase()}"
}
