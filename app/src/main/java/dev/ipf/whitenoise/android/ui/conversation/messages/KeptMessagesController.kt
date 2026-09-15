package dev.ipf.whitenoise.android.ui.conversation.messages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.ipf.whitenoise.android.state.TimelineMessage

/**
 * The session-scoped stack of messages the reader asked to keep on screen,
 * partitioned per account so signing into another identity never surfaces
 * another account's content. Nothing here is persisted or leaves the process:
 * the holder stores references, and the card resolves them against the live
 * timeline every time it draws.
 */
@Stable
internal class KeptMessagesController {
    private var stacks by mutableStateOf<Map<String, KeptMessageStack>>(emptyMap())

    /** The kept keys for [accountRef], oldest first. */
    fun keys(accountRef: String): List<KeptMessageKey> = stacks[accountRef]?.keys.orEmpty()

    /** The key the card is currently showing for [accountRef], if any. */
    fun selected(accountRef: String): KeptMessageKey? {
        val stack = stacks[accountRef] ?: return null
        return stack.selected ?: stack.keys.firstOrNull()
    }

    /** Whether [key] is already kept, used to hide a redundant menu action. */
    fun isKept(key: KeptMessageKey): Boolean = key in keys(key.accountRef)

    /** Keeps [key] on screen and makes it the visible card. */
    fun keep(key: KeptMessageKey) {
        mutate(key.accountRef) { it.add(key) }
    }

    /** Brings [key] to the front of the pager. */
    fun select(key: KeptMessageKey) {
        mutate(key.accountRef) { it.select(key) }
    }

    /** Stops keeping [key], advancing the card to the nearest survivor. */
    fun remove(key: KeptMessageKey) {
        mutate(key.accountRef) { it.remove(key) }
    }

    /** Stops keeping everything for [accountRef]. */
    fun clear(accountRef: String) {
        stacks = stacks - accountRef
    }

    /**
     * Drops every key for [accountRef] that no longer resolves — deleted or
     * expired messages leave the card on their own this way, with no deletion
     * event having to reach it.
     */
    fun retain(
        accountRef: String,
        valid: Set<KeptMessageKey>,
    ) {
        mutate(accountRef) { it.retain(valid) }
    }

    private fun mutate(
        accountRef: String,
        transform: (KeptMessageStack) -> KeptMessageStack,
    ) {
        val next = transform(stacks[accountRef] ?: KeptMessageStack())
        stacks =
            if (next.keys.isEmpty()) {
                stacks - accountRef
            } else {
                stacks + (accountRef to next)
            }
    }
}

/**
 * The conversation's kept-message holder. Null wherever no conversation has
 * provided one, which makes "Keep on screen" hide itself rather than fail.
 */
internal val LocalKeptMessages = staticCompositionLocalOf<KeptMessagesController?> { null }

/** Remembers one holder per runtime generation so a restart never resurrects stale references. */
@Composable
internal fun rememberKeptMessagesController(runtimeGeneration: Any): KeptMessagesController {
    val controller = remember(runtimeGeneration) { KeptMessagesController() }
    return controller
}

/**
 * Resolves the kept keys for [accountRef] against the live timeline and prunes
 * the ones that no longer exist, so the returned entries are always renderable.
 */
@Composable
internal fun rememberKeptMessageEntries(
    controller: KeptMessagesController,
    accountRef: String,
    resolve: (KeptMessageKey) -> TimelineMessage?,
): List<KeptMessageEntry> {
    val keys = controller.keys(accountRef)
    val entries = keys.mapNotNull { key -> resolve(key)?.let { KeptMessageEntry(key, it) } }
    val stale = entries.size != keys.size
    // Pruning is a write, so it lands after composition rather than inside it.
    SideEffect {
        if (stale) controller.retain(accountRef, entries.map { it.key }.toSet())
    }
    return entries
}
