package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.EventsSubscription
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.PollVotePageFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineUserReactionFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.MessageStatus
import dev.ipf.whitenoise.android.state.TimelineMessage
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerGate
import dev.ipf.whitenoise.android.ui.conversation.composer.ComposerTextState
import dev.ipf.whitenoise.android.ui.conversation.messages.MessageBubbleFileAttachmentFixtures
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val POLL_INTERVAL_MS = 25L

/** Real bubble/controller fixtures with a bounded fake native transport, without network work. */
open class PollMessageTestFixtures : MessageBubbleFileAttachmentFixtures() {
    protected val nativeCalls = mutableListOf<Pair<String, List<Any?>>>()
    protected var reactionFailure: Throwable? = null
    protected var beforeVoteReturn: (() -> Unit)? = null

    /** Answers `pollVotes` with the fixture's scripted per-voter page; the default is an empty page. */
    protected var pollVotesResponder: (List<Any?>) -> PollVotePageFfi = { PollVotePageFfi(emptyList(), false) }

    /** Complete exact-message reactions returned by the native read, independently of chip previews. */
    protected var reactionDetailsResponder: (List<Any?>) -> List<TimelineUserReactionFfi> = { emptyList() }

    /** The controller's wall clock, so disappearing-message deadlines can be crossed deterministically. */
    protected var pollClockMillis: Long = 1_000_000_000_000L

    /** Runtime events the fixture's `subscribeEvents` stream delivers in order. */
    protected val projectionEvents = LinkedBlockingQueue<MarmotEventFfi>()
    private val projectionStreamClosed = AtomicBoolean(false)

    /** Ends the fixture's event stream so no polling thread outlives the test. */
    protected fun closeProjectionStream() {
        projectionStreamClosed.set(true)
    }

    /** The native event stream: replays queued events, then idles until the test closes it. */
    private class QueuedEventsSubscription : EventsSubscription(NoPointer) {
        private lateinit var events: LinkedBlockingQueue<MarmotEventFfi>
        private lateinit var closed: AtomicBoolean

        /** Supplies the shared queue and flag, since allocation skips the constructor. */
        fun bind(
            queue: LinkedBlockingQueue<MarmotEventFfi>,
            flag: AtomicBoolean,
        ) {
            events = queue
            closed = flag
        }

        /** Waits briefly for the next queued event and ends the stream once the test closed it. */
        override suspend fun next(): MarmotEventFfi? {
            while (!closed.get()) events.poll(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)?.let { return it }
            return null
        }

        /** Nothing native to release. */
        override fun close() = Unit
    }

    /**
     * UniFFI's no-pointer constructor registers Android's cleaner, which the Robolectric JVM module boundary
     * cannot access, so the inert subclass is allocated without running it.
     */
    private fun queuedEventsSubscription(): EventsSubscription {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val instance = allocate.invoke(unsafe, QueuedEventsSubscription::class.java)
        return (instance as QueuedEventsSubscription).also { it.bind(projectionEvents, projectionStreamClosed) }
    }

    private val native =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (val name = method.name.substringBefore('-')) {
                "toString" -> "poll-actions-fixture"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    synchronized(nativeCalls) { nativeCalls += name to args.orEmpty().toList() }
                    if (name == "reactToMessage") reactionFailure?.let { throw it }
                    if (name == "castPollVote") beforeVoteReturn?.invoke()
                    when (name) {
                        "castPollVote", "reactToMessage", "deleteMessage" ->
                            SendSummaryFfi(
                                1u,
                                listOf("aa".repeat(32)),
                                SendAcceptDispositionFfi.PUBLISHED,
                                SendMaintenanceDispositionFfi.READY,
                            )
                        "replyToMessageWithClientToken" ->
                            LocalSendAcceptanceFfi(args.orEmpty()[4] as String, "bb".repeat(32))
                        "subscribeEvents" -> queuedEventsSubscription()
                        "pollVotes" -> pollVotesResponder(args.orEmpty().toList())
                        "messageReactions" -> reactionDetailsResponder(args.orEmpty().toList())
                        "parseMarkdown" -> markdown(args.orEmpty().first() as String)
                        "messages" -> emptyList<dev.ipf.marmotkit.AppMessageRecordFfi>()
                        else -> null
                    }
                }
            }
        } as MarmotInterface
    protected val pollState = stateWithNativeDispatcher()

    /** Reuses the native fixture with a dispatcher that can deterministically complete a read inline. */
    protected fun stateWithNativeDispatcher(dispatcher: CoroutineDispatcher = Dispatchers.IO) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore =
                DraftStore(
                    object : DraftPersistence {
                        override fun read(): Map<String, String> = emptyMap()

                        override fun write(
                            key: String,
                            value: String?,
                        ) = Unit
                    },
                ),
            accountIdHexResolver = { null },
            accounts = listOf(AccountSummaryFfi("personal", "01" + "00".repeat(31), true, false, false, true)),
            activeAccountRef = "personal",
            initialMarmotRuntime = AppMarmotRuntime("test", native),
            marmotIoDispatcher = dispatcher,
        )
    protected val pollController =
        ConversationController(
            appState = pollState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
            clockMillis = { pollClockMillis },
        )
    private val composerTextState = ComposerTextState(TextFieldValue(""))

    protected fun pollMessage(
        closed: Boolean = false,
        mine: Boolean = false,
        status: MessageStatus = if (mine) MessageStatus.Sent else MessageStatus.Received,
    ): TimelineMessage {
        val source =
            fileTimelineMessage(
                70,
                "",
                mine = mine,
                caption = "{\"private-envelope\":true}",
                attachments = emptyList(),
                status = status,
            )
        return source.copy(
            record = source.record.copy(kind = 1068uL),
            projected =
                checkNotNull(source.projected).copy(
                    kind = 1068uL,
                    poll =
                        PollProjectionFfi(
                            question = "Lunch?",
                            options =
                                listOf(PollOptionResultFfi("a", "Soup", 0uL), PollOptionResultFfi("b", "Salad", 0uL)),
                            pollType = PollTypeFfi.SINGLE_CHOICE,
                            participants = 0uL,
                            localSelection = emptyList(),
                            creator = source.record.sender,
                            endsAt = if (closed) 1uL else null,
                            open = !closed,
                        ),
                ),
        )
    }

    /** Retains [item] the way production does, keyed by its item id (`msg:<hex>`) and listed in the order index. */
    protected fun retain(item: TimelineMessage) {
        pollController.retainTimelineItemForTest(item)
        item.projected?.let { pollController.timelineRecords[item.record.messageIdHex] = it }
    }

    /** Puts [records] into the controller through a real timeline page apply instead of direct map writes. */
    protected fun applyPage(vararg records: TimelineMessageRecordFfi) {
        runBlocking {
            pollController.applyTimelinePage(
                page = TimelinePageFfi(records.toList(), hasMoreBefore = false, hasMoreAfter = false),
                replaceWindow = true,
                updatePagination = true,
            )
        }
    }

    protected fun recordedCalls(): List<Pair<String, List<Any?>>> = synchronized(nativeCalls) { nativeCalls.toList() }

    /** Exercises the actual timeline dispatch, with an optional read-only membership gate. */
    @Composable
    @Suppress("FunctionNaming")
    protected fun RealPollMessage(
        item: TimelineMessage,
        menuOpen: Boolean,
        readOnly: Boolean = false,
        onMenuChange: (Boolean) -> Unit,
    ) {
        TimelineRow(
            item = item,
            older = null,
            newer = null,
            transcriptLocale = Locale.US,
            entryUnreadCount = 0,
            entryUnreadDividerRetired = true,
            entryFirstUnreadMessageId = null,
            onMeasured = { _, _ -> },
            controller = pollController,
            appState = pollState,
            composerTextState = composerTextState,
            highlighted = false,
            selectionMode = false,
            textSelectionMode = false,
            onTextSelectionModeChange = {},
            onTextSelectionBoundsChange = {},
            // ConversationScreen's selection map accepts only native kind-9 chat records.
            batchSelectable = false,
            selected = false,
            onToggleSelection = {},
            rangeDragActive = false,
            onDragSelectionStart = {},
            onDragSelection = { false },
            onDragSelectionEnd = {},
            onDragSelectionCancel = {},
            quickReactionEmojis = listOf("👍", "❤️"),
            recentEmojis = emptyList(),
            onEmojiUsed = {},
            isActionMenuOpen = menuOpen,
            onActionMenuOpenChange = onMenuChange,
            onQuickReactionsSave = {},
            onReplyPreviewClick = {},
            composerGate = if (readOnly) ComposerGate.NOTICE else ComposerGate.COMPOSER,
            onBack = {},
            collapseLongMessages = true,
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
        )
    }
}
