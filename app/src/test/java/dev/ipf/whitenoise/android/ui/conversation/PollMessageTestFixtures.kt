package dev.ipf.whitenoise.android.ui.conversation

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.LocalSendAcceptanceFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PollOptionResultFfi
import dev.ipf.marmotkit.PollProjectionFfi
import dev.ipf.marmotkit.PollTypeFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
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
import java.lang.reflect.Proxy
import java.util.Locale

/** Real bubble/controller fixtures with a bounded fake native transport, without network work. */
open class PollMessageTestFixtures : MessageBubbleFileAttachmentFixtures() {
    protected val nativeCalls = mutableListOf<Pair<String, List<Any?>>>()
    protected var reactionFailure: Throwable? = null
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
                    when (name) {
                        "castPollVote", "reactToMessage", "deleteMessage" ->
                            SendSummaryFfi(
                                1u,
                                listOf("aa".repeat(32)),
                                SendAcceptDispositionFfi.PUBLISHED,
                                SendMaintenanceDispositionFfi.READY,
                            )
                        "replyToMessageWithClientToken" -> LocalSendAcceptanceFfi(args.orEmpty()[4] as String, "bb".repeat(32))
                        "parseMarkdown" -> markdown(args.orEmpty().first() as String)
                        "messages" -> emptyList<dev.ipf.marmotkit.AppMessageRecordFfi>()
                        else -> null
                    }
                }
            }
        } as MarmotInterface
    protected val pollState =
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
        )
    protected val pollController =
        ConversationController(
            appState = pollState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            groupRosterReader = { _, _ -> authoritativeRoster() },
        )
    private val composerTextState = ComposerTextState(TextFieldValue(""))

    protected fun pollMessage(
        closed: Boolean = false,
        mine: Boolean = false,
        status: MessageStatus = if (mine) MessageStatus.Sent else MessageStatus.Received,
    ): TimelineMessage {
        val source =
            fileTimelineMessage(70, "", mine = mine, caption = "{\"private-envelope\":true}", attachments = emptyList(), status = status)
        return source.copy(
            record = source.record.copy(kind = 1068uL),
            projected =
                checkNotNull(source.projected).copy(
                    kind = 1068uL,
                    poll =
                        PollProjectionFfi(
                            question = "Lunch?",
                            options = listOf(PollOptionResultFfi("a", "Soup", 0uL), PollOptionResultFfi("b", "Salad", 0uL)),
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

    protected fun retain(item: TimelineMessage) {
        pollController.timelineItemsById[item.record.messageIdHex] = item
        item.projected?.let { pollController.timelineRecords[item.record.messageIdHex] = it }
    }

    protected fun recordedCalls(): List<Pair<String, List<Any?>>> = synchronized(nativeCalls) { nativeCalls.toList() }

    @Composable
    @Suppress("FunctionNaming")
    protected fun RealPollMessage(
        item: TimelineMessage,
        menuOpen: Boolean,
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
            composerGate = ComposerGate.COMPOSER,
            onBack = {},
            collapseLongMessages = true,
            mentionCandidates = emptyList(),
            mentionPickerEnabled = false,
        )
    }
}
