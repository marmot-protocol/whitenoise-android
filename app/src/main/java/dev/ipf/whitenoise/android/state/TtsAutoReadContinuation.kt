package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsQueuedMessage
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.core.TimelineProjector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Transient tail identity for a single playback session, never a second message store. */
internal class TtsAutoReadTail(
    initial: String?,
) {
    var messageId: String? = initial
        private set

    fun following(page: TimelinePageFfi): List<TimelineMessageRecordFfi>? {
        val anchor = messageId ?: return emptyList()
        val index = page.messages.indexOfFirst { it.messageIdHex == anchor }
        return if (index < 0) null else page.messages.drop(index + 1)
    }

    fun accepted(messageId: String) {
        this.messageId = messageId
    }
}

/** Native and playback boundaries supplied by the current process owner. */
internal interface TtsAutoReadContinuationHost {
    val controller: TtsController

    fun owns(
        account: String,
        group: String,
        session: Long,
    ): Boolean

    fun allowsAppend(): Boolean

    suspend fun open(
        account: String,
        group: String,
    ): ConversationTimelineSubscriptionHandle

    suspend fun project(record: TimelineMessageRecordFfi): TtsSpeakableEntry?
}

/** Reuses the native projection and subscription seams; plaintext remains only in the speech queue. */
internal fun createTtsAutoReadContinuation(appState: WhiteNoiseAppState): TtsAutoReadContinuation =
    TtsAutoReadContinuation(
        object : TtsAutoReadContinuationHost {
            override val controller = appState.ttsController

            override fun owns(
                account: String,
                group: String,
                session: Long,
            ): Boolean =
                appState.activeAccountRef == account &&
                    appState.ownsTtsAutoReadSession(group) &&
                    controller.state.value.sessionId == session

            override fun allowsAppend(): Boolean =
                appState.ttsHistorySession.allowsLiveAppend(
                    reconciledNativeWindow = true,
                )

            override suspend fun open(
                account: String,
                group: String,
            ): ConversationTimelineSubscriptionHandle =
                appState.conversationLiveSubscriptions().openTimeline(
                    account,
                    group,
                    CONVERSATION_WINDOW_MAX_ROWS,
                )

            override suspend fun project(record: TimelineMessageRecordFfi): TtsSpeakableEntry? =
                projectTtsSpeakableEntry(
                    message = TimelineProjector.toAppMessageRecord(record),
                    editedText = null,
                    senderDisplayName = appState.displayName(record.sender),
                    parseMarkdown = { appState.parseMarkdownOrEmpty(it) },
                    mentionDisplayName = appState::mentionSpeechName,
                )
        },
    )

private data class TtsAutoReadRun(
    val account: String,
    val group: String,
    val session: Long,
    val tail: TtsAutoReadTail,
    val locale: Locale,
)

/** One native subscription owned by speech, independent of the visible conversation's lifecycle. */
internal class TtsAutoReadContinuation(
    private val host: TtsAutoReadContinuationHost,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val maxRetainedMessages: Int = CONVERSATION_WINDOW_MAX_ROWS.toInt(),
    private val maxRetainedTextChars: Int = 1_048_576,
) {
    private var job: Job? = null

    fun start(
        account: String,
        groupId: String,
        locale: Locale,
    ) {
        job?.cancel()
        val session = host.controller.state.value.sessionId
        val queued = host.controller.queuedMessagesSnapshot()
        if (queued.size > maxRetainedMessages || retainedTextChars(queued) > maxRetainedTextChars) {
            host.controller.stop()
            return
        }
        // The initial projection may exceed the controller's bounded queue.
        // Anchor to what was accepted, never to a row that has not been queued.
        val anchor =
            queued
                .lastOrNull()
                ?.messageIdHex
                ?.takeIf(String::isNotEmpty) ?: return
        val run = TtsAutoReadRun(account, groupId, session, TtsAutoReadTail(anchor), locale)
        job =
            scope.launch {
                coroutineScope {
                    val consumer =
                        launch {
                            consume(run)
                        }
                    host.controller.state.first { !owns(run, it) }
                    consumer.cancelAndJoin()
                }
            }
    }

    private fun owns(
        run: TtsAutoReadRun,
        state: TtsState,
    ): Boolean =
        host.owns(run.account, run.group, run.session) &&
            state.sessionId == run.session &&
            (state is TtsState.Speaking || state is TtsState.Paused)

    @Suppress("TooGenericExceptionCaught") // Native authority loss must revoke captured speech.
    private suspend fun consume(run: TtsAutoReadRun) {
        var subscription: ConversationTimelineSubscriptionHandle? = null
        try {
            // Native open has its own finite deadline. Adopt its handle even when
            // the caller cancels during IO, then close it instead of leaking it.
            val active = withContext(NonCancellable) { host.open(run.account, run.group) }
            subscription = active
            currentCoroutineContext().ensureActive()
            val initial = withContext(io) { active.snapshot() }
            if (initial != null && !appendPage(active, initial, run)) return
            var reading = true
            while (reading && owns(run, host.controller.state.value)) {
                val page = withContext(io) { active.nextWindow() }
                reading = page != null && appendPage(active, page, run)
            }
            // No disconnected retained text may reach the engine through Resume.
            if (owns(run, host.controller.state.value)) host.controller.stop()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (owns(run, host.controller.state.value)) host.controller.stop()
        } finally {
            withContext(NonCancellable + io) {
                try {
                    try {
                        subscription?.cancel()
                    } finally {
                        subscription?.close()
                    }
                } catch (_: Exception) {
                    if (owns(run, host.controller.state.value)) host.controller.stop()
                }
            }
        }
    }

    private suspend fun appendPage(
        subscription: ConversationTimelineSubscriptionHandle,
        initial: TimelinePageFfi,
        run: TtsAutoReadRun,
    ): Boolean {
        var page = reconcileAnchor(subscription, initial, run) ?: return stopForGap(run)
        var complete = false
        var healthy = true
        while (healthy && !complete && owns(run, host.controller.state.value)) {
            if (invalidatesQueuedSpeech(page)) {
                host.controller.stop()
                healthy = false
            } else {
                healthy = appendFollowing(page, run)
                if (healthy && page.hasMoreAfter) {
                    val next = withContext(io) { subscription.paginateForwards(CONVERSATION_WINDOW_MAX_ROWS) }
                    val advanced = (next as? TimelinePageOutcome.Advanced)?.page
                    if (advanced != null && run.tail.following(advanced)?.isEmpty() == false) {
                        page = advanced
                    } else {
                        healthy = stopForGap(run)
                    }
                } else {
                    complete = healthy
                }
            }
        }
        return complete
    }

    private suspend fun reconcileAnchor(
        subscription: ConversationTimelineSubscriptionHandle,
        initial: TimelinePageFfi,
        run: TtsAutoReadRun,
    ): TimelinePageFfi? {
        if (run.tail.following(initial) != null) return initial
        val jump = withContext(io) { subscription.jumpToMessage(requireNotNull(run.tail.messageId)) }
        val outcome = (jump as? ConversationJumpOutcome.Window)?.outcome as? TimelinePageOutcome.Advanced
        return outcome?.page
    }

    private suspend fun appendFollowing(
        page: TimelinePageFfi,
        run: TtsAutoReadRun,
    ): Boolean {
        val following = run.tail.following(page) ?: return stopForGap(run)
        val records = following.iterator()
        var healthy = true
        while (records.hasNext() && healthy && owns(run, host.controller.state.value)) {
            currentCoroutineContext().ensureActive()
            val record = records.next()
            healthy = appendRecord(record, run)
            if (healthy) run.tail.accepted(record.messageIdHex)
        }
        return healthy && owns(run, host.controller.state.value)
    }

    private suspend fun appendRecord(
        record: TimelineMessageRecordFfi,
        run: TtsAutoReadRun,
    ): Boolean {
        if (!host.allowsAppend() || record.deleted || record.invalidationStatus != null) return true
        return if (record.plaintext.length > maxRetainedTextChars) {
            stopForGap(run)
        } else {
            val entry = withContext(io) { host.project(record) }
            if (entry != null && owns(run, host.controller.state.value)) {
                if (canRetain(entry)) {
                    host.controller.appendSpeech(entry, run.locale)
                    owns(run, host.controller.state.value)
                } else {
                    stopForGap(run)
                }
            } else {
                owns(run, host.controller.state.value)
            }
        }
    }

    /**
     * MDK supplies the accepted edited body in plaintext (and parses its content tokens).
     * Compare that effective body, not a raw event or a visible controller's optimistic overlay.
     * A subsequent native edit, deletion or invalidation revokes captured speech without a screen.
     */
    private fun invalidatesQueuedSpeech(page: TimelinePageFfi): Boolean {
        val queued = host.controller.queuedMessagesSnapshot().associateBy { it.messageIdHex }
        return page.messages.any { record ->
            val message = queued[record.messageIdHex]
            message != null &&
                (
                    record.deleted ||
                        record.invalidationStatus != null ||
                        message.presentationEntry?.sourceText?.let { it != record.plaintext } == true
                )
        }
    }

    /** Bound transient speech retention while an unattended session is paused. */
    private fun canRetain(entry: TtsSpeakableEntry): Boolean {
        val queued = host.controller.queuedMessagesSnapshot()
        if (queued.any { it.messageIdHex == entry.messageIdHex }) return true
        val incomingChars = maxOf(entry.text.length, entry.sourceText?.length ?: 0)
        return queued.size < maxRetainedMessages && retainedTextChars(queued) + incomingChars <= maxRetainedTextChars
    }

    private fun retainedTextChars(queued: List<TtsQueuedMessage>): Long =
        queued.sumOf { message ->
            message.presentationEntry?.let { maxOf(it.text.length, it.sourceText?.length ?: 0).toLong() }
                ?: message.chunks.sumOf { it.text.length.toLong() }
        }

    private fun stopForGap(run: TtsAutoReadRun): Boolean {
        if (owns(run, host.controller.state.value)) host.controller.stop()
        return false
    }
}
