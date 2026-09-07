package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessageDeliveryStateFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Scriptable bounded-window subscription with observable lifecycle calls. */
internal class ScriptedConversationTimelineSubscription(
    private val snapshotPage: TimelinePageFfi?,
    private val backwardsPage: TimelinePageFfi = emptyTimelinePage(),
    private val forwardsPage: TimelinePageFfi = emptyTimelinePage(),
) : ConversationTimelineSubscriptionHandle {
    private val lifecycleEvents = CopyOnWriteArrayList<String>()
    private val windows = Channel<TimelinePageFfi>(Channel.UNLIMITED)

    val lifecycleEventOrder: List<String>
        get() = lifecycleEvents.toList()

    val snapshotCallCount: Int
        get() = lifecycleEvents.count { it == "snapshot" }

    val nextWindowCallCount: Int
        get() = lifecycleEvents.count { it == "nextWindow" }

    val closeCallCount: Int
        get() = lifecycleEvents.count { it == "close" }

    /** Records and returns the configured initial authoritative window. */
    override fun snapshot(): TimelinePageFfi? {
        lifecycleEvents += "snapshot"
        return snapshotPage
    }

    /** Suspends until a scripted complete window arrives or the stream ends. */
    override suspend fun nextWindow(): TimelinePageFfi? {
        lifecycleEvents += "nextWindow"
        return windows.receiveCatching().getOrNull()
    }

    /** Ends live delivery so controller retry paths can be exercised. */
    fun endWindows() {
        windows.close()
    }

    /** Enqueues one authoritative live window for the controller pump. */
    fun emitWindow(page: TimelinePageFfi) {
        check(windows.trySend(page).isSuccess) { "timeline window channel is closed" }
    }

    /** Returns the configured backward-pagination window. */
    override suspend fun paginateBackwards(count: UInt): TimelinePageFfi = backwardsPage

    /** Returns the configured forward-pagination window. */
    override suspend fun paginateForwards(count: UInt): TimelinePageFfi = forwardsPage

    /** Records closure and unblocks any pending live-window read. */
    override fun close() {
        lifecycleEvents += "close"
        windows.close()
    }
}

internal class ScriptedConversationGroupStateSubscription(
    private val group: AppGroupRecordFfi,
) : ConversationGroupStateSubscriptionHandle {
    private val closed = CompletableDeferred<Unit>()

    override fun snapshot(): AppGroupRecordFfi = group

    override suspend fun next(): AppGroupRecordFfi? {
        closed.await()
        return null
    }

    override fun close() {
        closed.complete(Unit)
    }
}

internal class ScriptedConversationLiveSubscriptions(
    timelineScripts: List<ScriptedConversationTimelineSubscription>,
    group: AppGroupRecordFfi,
) {
    private val timelineOpenIndex = AtomicInteger(0)

    val subscriptions: ConversationLiveSubscriptions =
        ConversationLiveSubscriptions(
            openTimeline = { _, _, _ ->
                val index = timelineOpenIndex.getAndIncrement()
                require(index < timelineScripts.size) {
                    "unexpected timeline subscription open index=$index"
                }
                timelineScripts[index]
            },
            openGroupState = { _, _ -> ScriptedConversationGroupStateSubscription(group) },
        )

    val timelineScripts: List<ScriptedConversationTimelineSubscription> = timelineScripts

    val timelineSubscriptionOpenCount: Int
        get() = timelineOpenIndex.get()
}

internal fun emptyTimelinePage(): TimelinePageFfi =
    TimelinePageFfi(
        messages = emptyList(),
        hasMoreBefore = false,
        hasMoreAfter = false,
    )

internal fun timelinePage(vararg messages: TimelineMessageRecordFfi): TimelinePageFfi =
    TimelinePageFfi(
        messages = messages.toList(),
        hasMoreBefore = false,
        hasMoreAfter = false,
    )

internal fun timelineRecord(
    messageId: String,
    timelineAt: ULong,
    plaintext: String = "body-$messageId",
): TimelineMessageRecordFfi =
    TimelineMessageRecordFfi(
        messageIdHex = messageId,
        sourceMessageIdHex = messageId,
        direction = "received",
        groupIdHex = ConversationTimelineTestIds.GROUP_ID,
        sender = ConversationTimelineTestIds.SENDER_ID,
        plaintext = plaintext,
        contentTokens = emptyMarkdown(),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = timelineAt,
        receivedAt = timelineAt,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = null,
        retentionSeconds = null,
        retentionExpiresAt = null,
    )

/** Builds an isolated live-subscription state with explicit conversation-account ownership. */
internal fun conversationTimelineTestAppState(
    liveSubscriptions: ConversationLiveSubscriptions,
    recoveryDiagnostics: NotificationNetworkRecoveryDiagnostics = NotificationNetworkRecoveryDiagnostics(),
    accountRef: String = ConversationTimelineTestIds.ACCOUNT_REF,
): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = ApplicationProvider.getApplicationContext<Context>(),
        draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
        accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = accountRef,
                    accountIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                ),
            ),
        activeAccountRef = accountRef,
        notificationNetworkRecoveryDiagnostics = recoveryDiagnostics,
    ).also { state ->
        state.liveSubscriptionOverrides.conversation = liveSubscriptions
    }

internal fun conversationTimelineTestGroup(): AppGroupRecordFfi =
    AppGroupRecordFfi(
        groupIdHex = ConversationTimelineTestIds.GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Timeline group",
        description = "",
        admins = listOf(ConversationTimelineTestIds.ACCOUNT_ID),
        relays = emptyList(),
        nostrGroupIdHex = "04".repeat(32),
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia =
            AppGroupEncryptedMediaComponentFfi(
                componentId = 0x8008u,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                version = EncryptedMediaVersionFfi.V1,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = listOf("blossom-v1"),
                defaultBlobEndpoints =
                    listOf(
                        AppBlobEndpointFfi(
                            locatorKind = "blossom-v1",
                            baseUrl = "https://blossom.example",
                        ),
                    ),
            ),
        disappearingMessageSecs = 0uL,
        archived = false,
        pendingConfirmation = false,
        unrecoverable = false,
        selfMembership = SelfMembershipFfi.MEMBER,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

internal fun conversationTimelineMemberSnapshot(): GroupMemberSnapshot =
    GroupMemberSnapshot(
        listOf(
            AppGroupMemberRecordFfi(
                memberIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                account = ConversationTimelineTestIds.ACCOUNT_REF,
                local = true,
            ),
        ),
    )

internal fun conversationTimelineGroupRoster(): GroupRosterFfi =
    GroupRosterFfi(
        groupIdHex = ConversationTimelineTestIds.GROUP_ID,
        members =
            listOf(
                GroupMemberDetailsFfi(
                    memberIdHex = ConversationTimelineTestIds.ACCOUNT_ID,
                    account = ConversationTimelineTestIds.ACCOUNT_REF,
                    local = true,
                    isAdmin = true,
                    isSelf = true,
                    npub = "npub-self",
                    displayName = null,
                ),
            ),
        epoch = 0uL,
        rosterRevision = 0uL,
        selfMembership = SelfMembershipFfi.MEMBER,
        memberCount = 1u,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
    )

internal fun notifiedMessagePreview(): ChatListMessagePreviewFfi =
    ChatListMessagePreviewFfi(
        messageIdHex = ConversationTimelineTestIds.MESSAGE_B,
        sender = ConversationTimelineTestIds.SENDER_ID,
        senderDisplayName = "Peer",
        plaintext = "notified body",
        contentTokens = emptyMarkdown(),
        kind = 9uL,
        timelineAt = 2uL,
        deleted = false,
        attachmentKind = null,
        attachmentCount = 0u,
        deliveryState = ChatListMessageDeliveryStateFfi.NOT_APPLICABLE,
    )

internal fun notificationChatListRow(): ChatListRowFfi =
    ChatListRowFfi(
        selfMembership = SelfMembershipFfi.MEMBER,
        unreadMentionCount = 0uL,
        unreadMention = false,
        groupIdHex = ConversationTimelineTestIds.GROUP_ID,
        archived = false,
        pendingConfirmation = false,
        title = "Timeline group",
        groupName = "Timeline group",
        avatarUrl = null,
        avatar = null,
        lastMessage = notifiedMessagePreview(),
        unreadCount = 1uL,
        hasUnread = true,
        firstUnreadMessageIdHex = ConversationTimelineTestIds.MESSAGE_B,
        lastReadMessageIdHex = ConversationTimelineTestIds.MESSAGE_A,
        lastReadTimelineAt = 1uL,
        conversationCreatedAt = 0uL,
        activitySortAt = 2uL,
        updatedAt = 2uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        manuallyMarkedUnread = false,
        conversationKind = ChatConversationKindFfi.GROUP,
        muted = false,
        mutedUntilMs = null,
        pinned = false,
        pinnedPosition = null,
        lifecycleState = GroupLifecycleStateFfi.STABLE,
        disbanding = false,
        disbandRequest = null,
    )

@Suppress("MaxLineLength")
internal fun timelineMessageIds(controller: ConversationController): List<String> = controller.timeline.map { it.record.messageIdHex }

internal fun awaitConversationCondition(
    timeoutMs: Long = 5_000,
    condition: () -> Boolean,
) {
    val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() <= deadlineNanos) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        Thread.sleep(10)
    }
    throw AssertionError("Condition not met within ${timeoutMs}ms")
}

internal fun awaitOpenedTimelineSubscriptionsClosed(subscriptions: ScriptedConversationLiveSubscriptions) {
    awaitConversationCondition {
        subscriptions.timelineScripts
            .take(subscriptions.timelineSubscriptionOpenCount)
            .all { it.closeCallCount >= 1 }
    }
}

private fun emptyMarkdown(): MarkdownDocumentFfi =
    MarkdownDocumentFfi(
        truncated = false,
        blocks = emptyList(),
        blankLinesBefore = ByteArray(0),
    )

internal class ConversationTimelineTestDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}

internal data class ConversationTimelineReconnectFixtures(
    val firstSubscription: ScriptedConversationTimelineSubscription,
    val replacementSubscription: ScriptedConversationTimelineSubscription,
    val scriptedSubscriptions: ScriptedConversationLiveSubscriptions,
)

internal fun conversationTimelineReconnectFixtures(): ConversationTimelineReconnectFixtures {
    val firstSubscription =
        ScriptedConversationTimelineSubscription(
            timelinePage(
                timelineRecord(
                    messageId = ConversationTimelineTestIds.MESSAGE_A,
                    timelineAt = 1uL,
                ),
            ),
        )
    val replacementSubscription =
        ScriptedConversationTimelineSubscription(
            timelinePage(
                timelineRecord(
                    messageId = ConversationTimelineTestIds.MESSAGE_A,
                    timelineAt = 1uL,
                ),
                timelineRecord(
                    messageId = ConversationTimelineTestIds.MESSAGE_B,
                    timelineAt = 2uL,
                    plaintext = "notified body",
                ),
            ),
        )
    return ConversationTimelineReconnectFixtures(
        firstSubscription = firstSubscription,
        replacementSubscription = replacementSubscription,
        scriptedSubscriptions =
            ScriptedConversationLiveSubscriptions(
                timelineScripts = listOf(firstSubscription, replacementSubscription),
                group = conversationTimelineTestGroup(),
            ),
    )
}

internal typealias ScriptedTimelineSubscription = ScriptedConversationTimelineSubscription

/** Asserts the controller consumes the snapshot before awaiting its first live window. */
internal fun assertTimelineSubscriptionSnapshotBeforeFirstNextWindow(subscription: ScriptedTimelineSubscription) {
    assertEquals("expected exactly one snapshot read", 1, subscription.snapshotCallCount)
    assertTrue(
        "expected at least one nextWindow read, got ${subscription.nextWindowCallCount}",
        subscription.nextWindowCallCount >= 1,
    )
    val events = subscription.lifecycleEventOrder
    val snapshotIndex = events.indexOf("snapshot")
    val nextWindowIndex = events.indexOf("nextWindow")
    assertTrue(
        "snapshot must be consumed before the first nextWindow, got $events",
        snapshotIndex >= 0 && nextWindowIndex > snapshotIndex,
    )
}

internal object ConversationTimelineTestIds {
    const val ACCOUNT_REF = "alice"
    val ACCOUNT_ID = "aa".repeat(32)
    val GROUP_ID = "bb".repeat(32)
    val SENDER_ID = "cc".repeat(32)
    val MESSAGE_A = "d1".repeat(32)
    val MESSAGE_B = "d2".repeat(32)
}
