package dev.ipf.whitenoise.android.state

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupHydrationQuarantineReasonFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppQuarantinedGroupFfi
import dev.ipf.marmotkit.ChatListAnchorOutcomeFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ConversationPresentationFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.PresentationResolutionFfi
import dev.ipf.marmotkit.PresentationSourceFfi
import dev.ipf.marmotkit.PresentationTextFfi
import dev.ipf.marmotkit.PresentedChatRowFfi
import dev.ipf.marmotkit.SelectedAvatarFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/** Controlled SDK responses and streams exercise the actual chat controller, not MLS repair. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class QuarantinedGroupsProjectionIntegrationTest {
    @Test fun successfulRetryAppearsOnlyThroughTheNormalNativeWindow() = checkProjection(recovered = true)

    @Test fun unsuccessfulRetryCannotInsertAChatRow() = checkProjection(recovered = false)

    private fun checkProjection(recovered: Boolean) {
        val account = ConversationTimelineTestIds.ACCOUNT_REF
        val groupId = ConversationTimelineTestIds.GROUP_ID
        val quarantined = AtomicBoolean(true)
        val window = RecoveryWindow()
        val groups = RecoveryGroups()
        val native = recoveryNative(account, groupId, quarantined, window, recovered)
        val appState = projectionAppState(account)
        appState.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, view -> if (view == ChatListViewFfi.CHATS) window else RecoveryWindow(view) },
                openChats = { _, _ -> groups },
            )
        val chats =
            ChatsController(
                appState = appState,
                initialAccountRef = account,
                initialLocalSnapshot =
                    AccountSwitchLocalSnapshot(
                        account,
                        ConversationTimelineTestIds.ACCOUNT_ID,
                        emptyList(),
                        emptyList(),
                        emptyList(),
                        emptyList(),
                    ),
                memberSnapshotRetryDelay = { Long.MAX_VALUE },
                memberSnapshotLoader = { _, _ -> emptyList() },
            )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val access =
            NativeQuarantinedGroupsAccess(
                account,
                AppMarmotRuntime("private", native),
                { appState.activeAccountRef == account },
            )
        val recovery = QuarantinedGroupsController(access, scope)
        scope.launch { chats.bind(account) }
        try {
            awaitQuarantineProjection { window.receiving.isCompleted }
            assertTrue(chats.items.isEmpty())
            recovery.refresh()
            awaitQuarantineProjection { recovery.state.value.loaded }
            recovery.recover(groupId)
            awaitQuarantineProjection { !recovery.state.value.busy && recovery.state.value.outcome != null }
            assertRecoveryProjection(recovered, groupId, chats, recovery)
        } finally {
            recovery.close()
            chats.onCleared()
            window.close()
            groups.close()
            scope.cancel()
        }
    }

    private fun recoveryNative(
        account: String,
        groupId: String,
        quarantined: AtomicBoolean,
        window: RecoveryWindow,
        recovered: Boolean,
    ): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "quarantinedGroups" -> {
                    assertEquals(account, args!![0])
                    if (quarantined.get()) {
                        listOf(
                            AppQuarantinedGroupFfi(groupId, AppGroupHydrationQuarantineReasonFfi.OPEN_MLS_LOAD_FAILED),
                        )
                    } else {
                        emptyList()
                    }
                }
                "retryHydrateQuarantinedGroup" -> {
                    assertEquals(account, args!![0])
                    assertEquals(groupId, args[1])
                    if (recovered) {
                        quarantined.set(false)
                        window.deliverRecoveredGroup()
                    }
                    recovered
                }
                "toString" -> "QuarantineProjectionFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("unexpected native call")
            }
        } as MarmotInterface

    private fun projectionAppState(account: String) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(ConversationTimelineTestDraftPersistence()),
            accountIdHexResolver = { ConversationTimelineTestIds.ACCOUNT_ID },
            accounts =
                listOf(AccountSummaryFfi(account, ConversationTimelineTestIds.ACCOUNT_ID, true, false, false, true)),
            activeAccountRef = account,
        )

    private fun assertRecoveryProjection(
        recovered: Boolean,
        groupId: String,
        chats: ChatsController,
        recovery: QuarantinedGroupsController,
    ) {
        if (recovered) {
            awaitQuarantineProjection { chats.items.size == 1 }
            assertEquals(groupId, chats.items.single().id)
            assertTrue(
                recovery.state.value.rows
                    .isEmpty(),
            )
            assertEquals(QuarantineRecoveryOutcome.Recovered, recovery.state.value.outcome)
        } else {
            assertTrue(chats.items.isEmpty())
            assertEquals(
                listOf(groupId),
                recovery.state.value.rows
                    .map { it.groupId },
            )
            assertEquals(QuarantineRecoveryOutcome.StillQuarantined, recovery.state.value.outcome)
        }
    }
}

private class RecoveryGroups : ChatsSubscriptionHandle {
    private val closed = CompletableDeferred<Unit>()

    override fun snapshot(): List<AppGroupRecordFfi> = emptyList()

    override suspend fun next(): AppGroupRecordFfi? {
        closed.await()
        return null
    }

    override fun close() {
        closed.complete(Unit)
    }
}

private class RecoveryWindow(
    private val view: ChatListViewFfi = ChatListViewFfi.CHATS,
) : ChatListWindowHandle {
    private val updates = Channel<ChatListWindowSnapshotFfi>(Channel.CONFLATED)
    val receiving = CompletableDeferred<Unit>()
    private var current = frame(emptyList(), 0uL)

    private fun frame(
        rows: List<PresentedChatRowFfi>,
        sequence: ULong,
    ) = ChatListWindowSnapshotFfi(
        subscriptionGeneration = "recovery-test-$view",
        sequence = sequence,
        view = view,
        rows = rows,
        hasMoreBefore = false,
        hasMoreAfter = false,
        anchor = ChatListAnchorOutcomeFfi.Top,
    )

    override fun snapshot() = current

    override suspend fun next(): ChatListWindowSnapshotFfi? {
        receiving.complete(Unit)
        return updates.receiveCatching().getOrNull()?.also { current = it }
    }

    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ) = current

    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ) = current

    override suspend fun returnToTop(sequence: ULong) = current

    /** Emits a recovered group frame so quarantine observers can replace their previously hidden row. */
    fun deliverRecoveredGroup() {
        val row = notificationChatListRow().copy(lastMessage = null, unreadCount = 0uL, hasUnread = false)
        val presented =
            PresentedChatRowFfi(
                draftVersion = null,
                preview = emptyChatRowPreview(),
                actions = noChatRowActions(),
                row = row,
                avatarAsset = null,
                presentation =
                    ConversationPresentationFfi(
                        title = PresentationTextFfi.Literal(row.title),
                        avatar = SelectedAvatarFfi.Placeholder(row.groupIdHex, PresentationSourceFfi.GROUP_FALLBACK),
                        titleSource = PresentationSourceFfi.GROUP_FALLBACK,
                        avatarSource = PresentationSourceFfi.GROUP_FALLBACK,
                        peerId = null,
                        resolution = PresentationResolutionFfi.FALLBACK,
                    ),
            )
        check(updates.trySend(frame(listOf(presented), 1uL)).isSuccess)
    }

    override fun close() {
        updates.close()
    }
}

/** Advance the main clock through the production chat-list debounce as native frames arrive. */
private fun awaitQuarantineProjection(condition: () -> Boolean) {
    awaitConversationCondition {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
        condition()
    }
}
