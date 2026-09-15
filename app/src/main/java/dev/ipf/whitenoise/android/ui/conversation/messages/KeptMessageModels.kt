package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Immutable
import dev.ipf.whitenoise.android.state.TimelineMessage

/**
 * A reference to one kept message. References only: a kept card always resolves
 * against the live timeline, so a message that is deleted or expires disappears
 * from the stack without anything having to notify it.
 */
@Immutable
internal data class KeptMessageKey(
    val accountRef: String,
    val groupIdHex: String,
    val messageIdHex: String,
)

/** A resolved kept message: its key paired with the live row it still points at. */
@Immutable
internal data class KeptMessageEntry(
    val key: KeptMessageKey,
    val message: TimelineMessage,
)

/**
 * One account's kept-message stack, newest last, with the entry the card is
 * currently showing. Every mutation returns a new stack so the holder can
 * publish it as snapshot state.
 */
@Immutable
internal data class KeptMessageStack(
    val keys: List<KeptMessageKey> = emptyList(),
    val selected: KeptMessageKey? = null,
) {
    /** Adds [key] if it is new and selects it either way. */
    fun add(key: KeptMessageKey): KeptMessageStack = copy(keys = if (key in keys) keys else keys + key, selected = key)

    /** Selects [key] when the stack still holds it, and is otherwise inert. */
    fun select(key: KeptMessageKey): KeptMessageStack = if (key in keys) copy(selected = key) else this

    /**
     * Drops every key that is no longer [valid] — deleted, expired, or signed
     * out — and moves the selection to the nearest survivor so the card keeps
     * showing something rather than going blank.
     */
    fun retain(valid: Set<KeptMessageKey>): KeptMessageStack {
        val remaining = keys.filter { it in valid }
        val fallbackIndex =
            keys
                .indexOf(selected)
                .coerceAtLeast(0)
                .coerceAtMost((remaining.size - 1).coerceAtLeast(0))
        return copy(
            keys = remaining,
            selected = selected?.takeIf { it in remaining } ?: remaining.getOrNull(fallbackIndex),
        )
    }

    /** Removes one key, moving the selection as [retain] does. */
    fun remove(key: KeptMessageKey): KeptMessageStack = retain((keys - key).toSet())
}

/**
 * Page indices for the kept-message pager. A stack of more than one message
 * gets a copy of its last entry before the first page and a copy of its first
 * after the last, so swiping past either end continues instead of stopping; the
 * pager recentres invisibly once the swipe settles.
 */
@Immutable
internal data class KeptMessagePages(
    val count: Int,
) {
    /** Total pages including the two boundary copies. */
    val pageCount: Int get() = if (count <= 1) 1 else count + 2

    /** The page showing message [index]. */
    fun pageFor(index: Int): Int = if (count <= 1) 0 else index + 1

    /** The message shown on [page], unwrapping the boundary copies. */
    fun messageAt(page: Int): Int = if (count <= 1) 0 else (page + count - 1) % count

    /** Whether [page] is one of the wrap-around copies that must be recentred. */
    fun isBoundary(page: Int): Boolean = count > 1 && (page == 0 || page == pageCount - 1)
}
