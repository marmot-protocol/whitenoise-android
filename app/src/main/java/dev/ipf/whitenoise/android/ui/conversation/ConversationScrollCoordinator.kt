package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.CancellationException

/** The durable reading intent behind the conversation's transient list geometry. */
internal sealed interface ConversationScrollMode {
    data object FollowingTail : ConversationScrollMode

    data class ReadingHistory(
        val anchorMessageId: String?,
        val pixelOffset: Int,
    ) : ConversationScrollMode

    data class Restoring(
        val anchorMessageId: String?,
        val pixelOffset: Int,
    ) : ConversationScrollMode

    data class ProgrammaticJump(
        val targetMessageId: String?,
        val reason: ConversationScrollReason,
    ) : ConversationScrollMode
}

internal enum class ConversationScrollReason {
    InitialAnchor,
    SavedRestore,
    LifecycleResume,
    ImeTransition,
    ViewportChange,
    TimelineHydration,
    NewMessage,
    ReactionLayout,
    BottomInput,
    Reply,
    Mention,
    Search,
    FocusMessage,
    JumpToNewest,
    Send,
}

/** Stable logical anchor; [listIndex] is only the fallback when the ids are gone. */
internal data class ConversationScrollAnchor(
    val listIndex: Int,
    val pixelOffset: Int,
    val itemId: String?,
    val messageId: String?,
)

internal data class ConversationScrollBookmark(
    val anchor: ConversationScrollAnchor,
    val settledMode: ConversationScrollMode,
    internal val intentRevision: Long,
)

/**
 * The sole mutation boundary for a conversation's [LazyListState].
 *
 * The coordinator keeps durable user intent separate from transient layout
 * geometry. Every command supersedes the previous command, and a drag invalidates
 * its token before cancelling its coroutine so a stale frame chase cannot resume
 * after the gesture.
 */
internal class ConversationScrollCoordinator(
    private val writer: ConversationScrollWriter,
    initialMode: ConversationScrollMode = ConversationScrollMode.FollowingTail,
) {
    private var settledMode = initialMode.requireSettled()
    private var readingAnchor: ConversationScrollAnchor? = null
    private var commandSerial = 0L
    private var intentRevision = 0L
    private var activeCommand: Job? = null
    private var userGestureInProgress = false

    var mode by mutableStateOf(settledMode)
        private set

    val isFollowingTail: Boolean
        get() = settledMode is ConversationScrollMode.FollowingTail

    fun bookmark(anchor: ConversationScrollAnchor): ConversationScrollBookmark {
        val stableAnchor =
            if (settledMode is ConversationScrollMode.ReadingHistory) {
                readingAnchor ?: anchor.also { readingAnchor = it }
            } else {
                anchor
            }
        return ConversationScrollBookmark(
            anchor = stableAnchor,
            settledMode = settledMode,
            intentRevision = intentRevision,
        )
    }

    fun onUserGestureStarted(anchor: ConversationScrollAnchor) {
        invalidateActiveCommand()
        userGestureInProgress = true
        readingAnchor = anchor
        setSettledMode(
            ConversationScrollMode.ReadingHistory(
                anchorMessageId = anchor.messageId,
                pixelOffset = anchor.pixelOffset,
            ),
            forceRevision = true,
        )
    }

    fun onUserGestureSettled(
        anchor: ConversationScrollAnchor,
        nearBottom: Boolean,
    ) {
        invalidateActiveCommand()
        userGestureInProgress = false
        if (nearBottom) {
            readingAnchor = null
            setSettledMode(ConversationScrollMode.FollowingTail, forceRevision = true)
        } else {
            readingAnchor = anchor
            setSettledMode(
                ConversationScrollMode.ReadingHistory(anchor.messageId, anchor.pixelOffset),
                forceRevision = true,
            )
        }
    }

    fun settleReadingAt(anchor: ConversationScrollAnchor) {
        invalidateActiveCommand()
        userGestureInProgress = false
        readingAnchor = anchor
        setSettledMode(
            ConversationScrollMode.ReadingHistory(anchor.messageId, anchor.pixelOffset),
            forceRevision = true,
        )
    }

    suspend fun reanchorReadingHistory(resolveAnchorIndex: (ConversationScrollAnchor) -> Int?): Boolean {
        val anchor = readingAnchor
        val canRestore =
            !userGestureInProgress &&
                settledMode is ConversationScrollMode.ReadingHistory &&
                mode is ConversationScrollMode.ReadingHistory
        return if (anchor == null || !canRestore) {
            false
        } else {
            runCommand(
                transientMode = ConversationScrollMode.Restoring(anchor.messageId, anchor.pixelOffset),
                resultingMode = settledMode,
            ) {
                scrollToItem(resolveAnchorIndex(anchor) ?: anchor.listIndex, anchor.pixelOffset)
            }
        }
    }

    suspend fun restoreBookmark(
        bookmark: ConversationScrollBookmark,
        force: Boolean = false,
        resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
    ): Boolean {
        if (!force && bookmark.intentRevision != intentRevision) return false
        val anchor = bookmark.anchor
        readingAnchor = anchor.takeIf { bookmark.settledMode is ConversationScrollMode.ReadingHistory }
        return runCommand(
            transientMode = ConversationScrollMode.Restoring(anchor.messageId, anchor.pixelOffset),
            resultingMode = bookmark.settledMode,
        ) {
            scrollToItem(resolveAnchorIndex(anchor) ?: anchor.listIndex, anchor.pixelOffset)
        }
    }

    suspend fun followTailIfAllowed(
        tailIndex: Int,
        reason: ConversationScrollReason,
        frameCount: Int = 1,
        awaitFrame: suspend () -> Unit = { withFrameNanos { } },
    ): Boolean {
        if (!isFollowingTail || mode !is ConversationScrollMode.FollowingTail) return false
        return programmaticJump(
            targetMessageId = null,
            reason = reason,
            resultingMode = ConversationScrollMode.FollowingTail,
        ) {
            repeat(frameCount.coerceAtLeast(1)) {
                awaitFrame()
                scrollToItem(tailIndex, 0)
            }
        }
    }

    suspend fun programmaticJump(
        targetMessageId: String?,
        reason: ConversationScrollReason,
        resultingMode: ConversationScrollMode? = null,
        operation: suspend ConversationScrollCommandScope.() -> Unit,
    ): Boolean =
        runCommand(
            transientMode = ConversationScrollMode.ProgrammaticJump(targetMessageId, reason),
            resultingMode = resultingMode ?: settledMode,
            operation = operation,
        )

    private suspend fun runCommand(
        transientMode: ConversationScrollMode,
        resultingMode: ConversationScrollMode,
        operation: suspend ConversationScrollCommandScope.() -> Unit,
    ): Boolean {
        val commandJob = currentCoroutineContext()[Job]
        val previous = activeCommand
        val serial = ++commandSerial
        if (previous != null && previous !== commandJob) previous.cancel()
        activeCommand = commandJob
        mode = transientMode
        return try {
            ConversationScrollCommandScope(serial).operation()
            if (serial != commandSerial) return false
            setSettledMode(resultingMode.requireSettled())
            true
        } finally {
            if (serial == commandSerial) {
                activeCommand = null
                mode = settledMode
            }
        }
    }

    private fun invalidateActiveCommand() {
        commandSerial++
        activeCommand?.cancel()
        activeCommand = null
    }

    private fun setSettledMode(
        newMode: ConversationScrollMode,
        forceRevision: Boolean = false,
    ) {
        val settled = newMode.requireSettled()
        if (forceRevision || settled != settledMode) intentRevision++
        settledMode = settled
        mode = settled
        if (settled is ConversationScrollMode.FollowingTail) readingAnchor = null
    }

    internal inner class ConversationScrollCommandScope internal constructor(
        private val serial: Long,
    ) {
        suspend fun scrollToItem(
            index: Int,
            scrollOffset: Int = 0,
        ) {
            ensureCurrent()
            writer.scrollToItem(index.coerceAtLeast(0), scrollOffset)
        }

        suspend fun animateScrollToItem(
            index: Int,
            scrollOffset: Int = 0,
        ) {
            ensureCurrent()
            writer.animateScrollToItem(index.coerceAtLeast(0), scrollOffset)
        }

        private suspend fun ensureCurrent() {
            currentCoroutineContext().ensureActive()
            if (serial != commandSerial) throw CancellationException("Superseded conversation scroll command")
        }
    }
}

internal suspend fun ConversationScrollCoordinator.restoreViewport(
    snapshot: ConversationScrollBookmark,
    resolveAnchorIndex: (ConversationScrollAnchor) -> Int?,
    tailIndex: Int,
    frameCount: Int = 24,
    awaitFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean =
    when (snapshot.settledMode) {
        ConversationScrollMode.FollowingTail ->
            followTailIfAllowed(
                tailIndex = tailIndex,
                reason = ConversationScrollReason.LifecycleResume,
                frameCount = frameCount,
                awaitFrame = awaitFrame,
            )
        is ConversationScrollMode.ReadingHistory ->
            restoreBookmark(snapshot, resolveAnchorIndex = resolveAnchorIndex)
        else -> false
    }

internal interface ConversationScrollWriter {
    suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int = 0,
    )

    suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int = 0,
    )
}

/** The only implementation that writes the Compose list state. */
internal class LazyListConversationScrollWriter(
    private val listState: LazyListState,
) : ConversationScrollWriter {
    override suspend fun scrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        listState.scrollToItem(index, scrollOffset)
    }

    override suspend fun animateScrollToItem(
        index: Int,
        scrollOffset: Int,
    ) {
        listState.animateScrollToItem(index, scrollOffset)
    }
}

private fun ConversationScrollMode.requireSettled(): ConversationScrollMode {
    require(this is ConversationScrollMode.FollowingTail || this is ConversationScrollMode.ReadingHistory) {
        "Transient scroll mode cannot be persisted: $this"
    }
    return this
}
