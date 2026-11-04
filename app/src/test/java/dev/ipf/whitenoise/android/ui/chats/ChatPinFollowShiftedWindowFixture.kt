package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.ChatListPageDirectionFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListViewFfi
import dev.ipf.marmotkit.ChatListWindowSnapshotFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatListWindowHandle
import dev.ipf.whitenoise.android.state.ChatsSubscriptionHandle
import dev.ipf.whitenoise.android.state.ConversationTimelineTestIds
import dev.ipf.whitenoise.android.state.MarmotWindowTestFakes
import dev.ipf.whitenoise.android.state.ScriptedConversationLiveSubscriptions
import dev.ipf.whitenoise.android.state.ScriptedConversationTimelineSubscription
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.conversationTimelineGroupRoster
import dev.ipf.whitenoise.android.state.conversationTimelineTestAppState
import dev.ipf.whitenoise.android.state.conversationTimelineTestGroup
import dev.ipf.whitenoise.android.state.emptyGroupRecord
import dev.ipf.whitenoise.android.state.emptyTimelinePage
import dev.ipf.whitenoise.android.state.noChatRowActions
import dev.ipf.whitenoise.android.state.notificationChatListRow
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A bound account whose native chat-list window starts shifted [SHIFT] rows below the true head, with
 * [PRE_PINNED] older pins outside it. A pin appends to the native pin order, and only a return to top
 * installs the head window, so the restored head row differs from the shifted window's first row (#3555).
 * Like MDK, the window is re-sent after a pin-order change, still shifted, so the new pin leaves it.
 */
internal class ChatPinFollowShiftedWindowFixture {
    val calls = ConcurrentLinkedQueue<String>()
    private val rows =
        List(ROW_COUNT) { index ->
            notificationChatListRow().copy(
                groupIdHex = "%02x".format(index + 0x10).repeat(32),
                title = "Window chat $index",
                groupName = "Window chat $index",
                activitySortAt = (ROW_COUNT - index).toULong() * 1_000uL,
                pinned = index < PRE_PINNED,
                pinnedPosition = index.takeIf { it < PRE_PINNED }?.toUInt(),
            )
        }.associateBy { it.groupIdHex }.toMutableMap()
    private val pinnedOrder =
        rows.values
            .filter { it.pinned }
            .sortedBy { it.pinnedPosition }
            .map { it.groupIdHex }
            .toMutableList()

    /** The native head order: pinned rows in pin order, then the rest by activity. */
    val headOrder: List<ChatListRowFfi>
        get() =
            synchronized(pinnedOrder) {
                pinnedOrder.map(rows::getValue) +
                    rows.values.filterNot { it.pinned }.sortedByDescending { it.activitySortAt }
            }

    /** The row id at native head position [index] when the fixture was built. */
    fun initialRowId(index: Int): String = "%02x".format(index + 0x10).repeat(32)

    val window = ShiftedPinWindow(this)

    val appState: WhiteNoiseAppState =
        conversationTimelineTestAppState(
            ScriptedConversationLiveSubscriptions(
                listOf(ScriptedConversationTimelineSubscription(emptyTimelinePage())),
                conversationTimelineTestGroup(),
            ).subscriptions,
        ).also { state ->
            WhiteNoiseAppState::class.java.getDeclaredField("marmotRuntime").apply { isAccessible = true }.set(
                state,
                AppMarmotRuntime(rootPath = "test", marmot = runtime()),
            )
            state.liveSubscriptionOverrides.chatList =
                ChatListLiveSubscriptions(
                    openChatListWindow = { _, view ->
                        if (view == ChatListViewFfi.CHATS) window else EmptyWindow(view)
                    },
                    openChats = { _, _ -> StaticGroups(rows.values.map { emptyGroupRecord(it) }) },
                )
        }

    /** Appends [id] to (or removes it from) the native pin order and updates the authoritative rows. */
    private fun pinCommand(args: Array<out Any?>?): ChatPinStateFfi {
        val id = (args!![1] as String).lowercase()
        val pinned = args[2] as Boolean
        calls += "pin:$pinned:$id"
        synchronized(pinnedOrder) {
            pinnedOrder.remove(id)
            if (pinned) pinnedOrder.add(id)
            rows.keys.toList().forEach { key ->
                val position = pinnedOrder.indexOf(key)
                rows[key] =
                    rows.getValue(key).copy(
                        pinned = position >= 0,
                        pinnedPosition = position.takeIf { it >= 0 }?.toUInt(),
                    )
            }
            window.republishShifted()
            return ChatPinStateFfi(pinnedOrder.toList())
        }
    }

    /** Native calls the mounted shell makes; anything else fails loudly. */
    private fun runtime(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "onboardingRecoveryRequired" -> false
                "onboardingSnapshot" -> null
                "subscribeAccountAttention" -> MarmotWindowTestFakes.accountAttention()
                "subscribeBlockedUsers" -> MarmotWindowTestFakes.blockList()
                "groupRoster" -> conversationTimelineGroupRoster()
                "groupMembers" ->
                    listOf(
                        AppGroupMemberRecordFfi(
                            ConversationTimelineTestIds.ACCOUNT_ID,
                            ConversationTimelineTestIds.ACCOUNT_REF,
                            true,
                        ),
                    )
                "setChatPinned" -> pinCommand(args)
                "chatListRow" -> rows[(args!![1] as String).lowercase()]
                "requestAvatarAssets" -> emptyList<Any>()
                else -> objectMethod(proxy, method.name, args)
            }
        } as MarmotInterface

    /** Implements object identity; any other native call fails loudly. */
    private fun objectMethod(
        proxy: Any,
        name: String,
        args: Array<out Any?>?,
    ): Any =
        when (name) {
            "toString" -> "ShiftedPinWindowRuntime"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected shifted-window call: $name")
        }

    companion object {
        const val ROW_COUNT = 40
        const val SHIFT = 10
        const val PRE_PINNED = 2
    }
}

/**
 * Starts [SHIFT][ChatPinFollowShiftedWindowFixture.SHIFT] rows below the head. After a pin, each backward
 * page moves the window up by [PAGE_ROWS] rows without reaching the head, and [returnToTop] answers only
 * after the controller's projection debounce, so a page published during the return races the head frame.
 */
internal class ShiftedPinWindow(
    private val fixture: ChatPinFollowShiftedWindowFixture,
) : ChatListWindowHandle {
    @Volatile private var shift = ChatPinFollowShiftedWindowFixture.SHIFT

    @Volatile private var frame = shifted(0uL)
    private val updates = Channel<ChatListWindowSnapshotFfi>(Channel.UNLIMITED)

    /** Whether a return to top has been asked for. */
    @Volatile var returnedToTop = false
        private set

    /** The current native order minus its first rows, with earlier rows retained by MDK. */
    private fun shifted(sequence: ULong): ChatListWindowSnapshotFfi {
        val retained = fixture.headOrder.drop(shift)
        return frame(retained, sequence).copy(hasMoreBefore = true)
    }

    /** A window snapshot of [rows] whose rows allow pinning and unpinning. */
    private fun frame(
        rows: List<ChatListRowFfi>,
        sequence: ULong,
    ): ChatListWindowSnapshotFfi {
        val snapshot = MarmotWindowTestFakes.windowSnapshot(ChatListViewFfi.CHATS, rows, sequence)
        val actions = noChatRowActions().copy(canPin = true, canUnpin = true)
        return snapshot.copy(rows = snapshot.rows.map { it.copy(actions = actions) })
    }

    /** Re-sends the still-shifted window after a pin-order change, as MDK's PIN_ORDER_CHANGED replacement does. */
    fun republishShifted() {
        if (returnedToTop) return
        frame = shifted(frame.sequence + 1uL)
        check(updates.trySend(frame).isSuccess)
    }

    /** Supplies the shifted initial window. */
    override fun snapshot(): ChatListWindowSnapshotFfi = frame

    /** Holds the live stream until its owner cancels it. */
    override suspend fun next(): ChatListWindowSnapshotFfi? = updates.receiveCatching().getOrNull()

    /**
     * A backward page after a pin moves the window [PAGE_ROWS] rows nearer the head but never onto it, so
     * only the follow's own return to top restores the head; any other page keeps the frame.
     */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi {
        val roomToPage = direction == ChatListPageDirectionFfi.BACKWARD && !returnedToTop && shift > PAGE_ROWS
        if (roomToPage && fixture.calls.any { it.startsWith("pin:true") }) {
            shift -= PAGE_ROWS
            frame = shifted(frame.sequence + 1uL)
        }
        return frame
    }

    /** The viewport anchor keeps the same retained rows. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = frame

    /** Installs the true head window, pinned block first, answering later than the projection debounce. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi {
        Thread.sleep(RETURN_TO_TOP_LATENCY_MILLIS)
        returnedToTop = true
        frame = frame(fixture.headOrder, frame.sequence + 1uL)
        return frame
    }

    /** The controller cancels the only pending receive. */
    override fun close() {
        updates.close()
    }

    private companion object {
        /** Rows one backward page moves the window by. */
        const val PAGE_ROWS = 2

        /** Longer than the controller's 16 ms projection debounce, so a page can publish first. */
        const val RETURN_TO_TOP_LATENCY_MILLIS = 60L
    }
}

/** An archive or folder view this account never shows. */
private class EmptyWindow(
    view: ChatListViewFfi,
) : ChatListWindowHandle {
    private val frame = MarmotWindowTestFakes.windowSnapshot(view, emptyList())

    /** Supplies an empty view. */
    override fun snapshot(): ChatListWindowSnapshotFfi = frame

    /** Never changes. */
    override suspend fun next(): ChatListWindowSnapshotFfi? = awaitCancellation()

    /** Has nothing to page. */
    override suspend fun page(
        sequence: ULong,
        direction: ChatListPageDirectionFfi,
        count: UInt,
    ): ChatListWindowSnapshotFfi = frame

    /** Has nothing to anchor. */
    override suspend fun setVisibleAnchor(
        sequence: ULong,
        groupIdHex: String,
    ): ChatListWindowSnapshotFfi = frame

    /** Is always at its top. */
    override suspend fun returnToTop(sequence: ULong): ChatListWindowSnapshotFfi = frame

    /** Holds no native resource. */
    override fun close() = Unit
}

/** Group metadata matching the window's rows. */
private class StaticGroups(
    private val groups: List<AppGroupRecordFfi>,
) : ChatsSubscriptionHandle {
    /** Supplies the native group projection used by the list. */
    override fun snapshot(): List<AppGroupRecordFfi> = groups

    /** Metadata stays stable for the test. */
    override suspend fun next(): AppGroupRecordFfi? = awaitCancellation()

    /** Receives are owned and cancelled by the production controller. */
    override fun close() = Unit
}
