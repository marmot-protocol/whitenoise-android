package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/** Layout inputs whose changes can move the conversation's usable bottom edge. */
internal data class ConversationTailInsetSnapshot(
    val bottomChromeHeightPx: Int,
    val snackbarContentInsetPx: Int,
    val bottomInputRevision: Long,
)

/**
 * Distinguishes initial bottom-layout measurement from subsequent transitions.
 * The first complete snapshot is already owned by the hidden initial anchor.
 */
internal class ConversationTailInsetObserver {
    private var handled: ConversationTailInsetSnapshot? = null
    private var pending: ConversationTailInsetSnapshot? = null

    /** Returns a changed snapshot until its layout transition is handled. */
    fun pendingSnapshot(snapshot: ConversationTailInsetSnapshot): ConversationTailInsetSnapshot? {
        if (handled == null) {
            handled = snapshot
            return null
        }
        pending = snapshot.takeUnless { it == handled }
        return pending
    }

    /** Accepts the snapshot only if it is still the latest pending transition. */
    fun markHandled(snapshot: ConversationTailInsetSnapshot) {
        if (pending != snapshot) return
        handled = snapshot
        pending = null
    }
}

/**
 * Reanchors a tail follower after composer, IME, or snackbar geometry changes.
 * A single keyed effect makes rapid related measurements latest-wins, while
 * [ConversationScrollCoordinator] rejects the write for a history reader.
 */
@Composable
@Suppress("FunctionNaming") // Jetpack Compose functions use UpperCamelCase.
internal fun ConversationTailInsetReanchorEffect(
    scrollCoordinator: ConversationScrollCoordinator,
    bottomChromeHeightPx: Int?,
    snackbarContentInsetPx: Int,
    bottomInputRevision: Long,
    hasTimeline: Boolean,
    initialTimelineAnchored: Boolean,
    routePresentationFrozen: Boolean,
    foregroundRestoreInProgress: Boolean,
    currentTailIndex: () -> Int,
) {
    val observer = remember(scrollCoordinator) { ConversationTailInsetObserver() }
    val currentTailIndexProvider = rememberUpdatedState(currentTailIndex)
    LaunchedEffect(
        observer,
        bottomChromeHeightPx,
        snackbarContentInsetPx,
        bottomInputRevision,
        hasTimeline,
        initialTimelineAnchored,
        routePresentationFrozen,
        foregroundRestoreInProgress,
    ) {
        val measuredBottomChromeHeightPx = bottomChromeHeightPx ?: return@LaunchedEffect
        // Foreground restoration and route transitions own list geometry until
        // their transactions settle. Do not inspect or consume the changed
        // snapshot: the explicit effect keys retry it after ownership releases.
        if (conversationTailInsetOwnedElsewhere(foregroundRestoreInProgress, routePresentationFrozen)) {
            return@LaunchedEffect
        }
        val pendingSnapshot =
            observer.pendingSnapshot(
                ConversationTailInsetSnapshot(
                    bottomChromeHeightPx = measuredBottomChromeHeightPx,
                    snackbarContentInsetPx = snackbarContentInsetPx,
                    bottomInputRevision = bottomInputRevision,
                ),
            )
                ?: return@LaunchedEffect
        if (!hasTimeline || !initialTimelineAnchored) {
            observer.markHandled(pendingSnapshot)
            return@LaunchedEffect
        }
        // A history reader owns their stable pixel anchor. Consume the inset
        // observation without issuing a tail write, including when an explicit
        // command is currently settling toward history mode.
        if (!scrollCoordinator.isFollowingTail) {
            observer.markHandled(pendingSnapshot)
            return@LaunchedEffect
        }
        // A transient writer can temporarily hide FollowingTail in `mode` even
        // though it remains the durable intent. Wait for that owner to settle,
        // then retry if another command wins the small gap between observation
        // and registration. Consuming the snapshot earlier would permanently
        // lose the IME/inset transition. Each registered attempt suspends for
        // the coordinator's layout frame; a refused command therefore cannot
        // retry as a tight Main-thread loop. A drag also changes the durable
        // intent to ReadingHistory before the refusal returns, ending the loop.
        // This effect is deliberately not keyed on `mode`, so its own
        // ProgrammaticJump phase cannot cancel itself.
        while (scrollCoordinator.isFollowingTail) {
            if (scrollCoordinator.foregroundRestoreInProgress) return@LaunchedEffect
            if (scrollCoordinator.mode !is ConversationScrollMode.FollowingTail) {
                snapshotFlow { scrollCoordinator.mode }
                    .first { mode ->
                        mode is ConversationScrollMode.FollowingTail ||
                            !scrollCoordinator.isFollowingTail
                    }
            }
            if (!scrollCoordinator.isFollowingTail) break
            val applied =
                scrollCoordinator.followTailIfAllowed(
                    resolveTailIndex = { currentTailIndexProvider.value() },
                    reason = ConversationScrollReason.BottomInput,
                )
            if (applied) {
                observer.markHandled(pendingSnapshot)
                return@LaunchedEffect
            }
        }
        observer.markHandled(pendingSnapshot)
    }
}

/** Whether a higher-priority restoration transaction currently owns list geometry. */
private fun conversationTailInsetOwnedElsewhere(
    foregroundRestoreInProgress: Boolean,
    routePresentationFrozen: Boolean,
): Boolean = foregroundRestoreInProgress || routePresentationFrozen
