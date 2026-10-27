package dev.ipf.whitenoise.android.ui.chats

import android.content.Context
import androidx.compose.runtime.MutableIntState
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.ChatPinStateFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.ChatListLiveSubscriptions
import dev.ipf.whitenoise.android.state.ChatsController
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.chatRow
import dev.ipf.whitenoise.android.state.emptyGroupRecord
import dev.ipf.whitenoise.android.state.noChatRowActions
import dev.ipf.whitenoise.android.state.presentedRow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal const val ACTION_ACCOUNT = "unread-actions-account"
internal val ACTION_ACCOUNT_ID = "a7".repeat(32)
internal val ACTION_TARGET = "b7".repeat(16)
internal val ACTION_SURVIVOR = "c7".repeat(16)

/** Populated native boundary: each successful command changes subsequent keyed and whole-list reads. */
internal class OffWindowActionFixture(
    scope: TestScope,
    ioDispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler),
    memberLoader: suspend (String, String) -> List<AppGroupMemberRecordFfi> = { _, _ -> emptyList() },
) {
    val calls = ConcurrentLinkedQueue<String>()
    val row = AtomicReference(chatRow(ACTION_TARGET).copy(manuallyMarkedUnread = true))
    val retainedRow = AtomicReference(chatRow(ACTION_SURVIVOR).copy(manuallyMarkedUnread = true))
    var beforeSnapshotRead: (suspend () -> Unit)? = null
    var snapshotExtras: List<ChatListRowFfi> = emptyList()
    var snapshotReads = 0
    var keyedReads = 0
    var lookupEntered: CompletableDeferred<Unit>? = null
    var releaseLookup: CompletableDeferred<Unit>? = null
    var archiveEntered: CompletableDeferred<Unit>? = null
    var releaseArchive: CompletableDeferred<Unit>? = null
    var nativeEntered: CountDownLatch? = null
    var releaseNative: CountDownLatch? = null
    var archiveFails = false
    val state: WhiteNoiseAppState
    val controller: ChatsController

    init {
        val context = ApplicationProvider.getApplicationContext<Context>()
        state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { ACTION_ACCOUNT_ID },
                accounts = listOf(AccountSummaryFfi(ACTION_ACCOUNT, ACTION_ACCOUNT_ID, true, false, false, true)),
                activeAccountRef = ACTION_ACCOUNT,
                initialMarmotRuntime = AppMarmotRuntime("unread-actions-test", native()),
                marmotIoDispatcher = ioDispatcher,
            )
        state.liveSubscriptionOverrides.chatList =
            ChatListLiveSubscriptions(
                openChatListWindow = { _, _ -> error("unused window") },
                openChats = { _, _ -> error("unused groups") },
                presentedChatList = { account, _ ->
                    check(account == ACTION_ACCOUNT)
                    snapshotReads++
                    beforeSnapshotRead?.invoke()
                    listOf(presented(row.get()), presented(retainedRow.get())) + snapshotExtras.map(::presented)
                },
                presentedRowByGroup = { account, id ->
                    check(account == ACTION_ACCOUNT && id in listOf(ACTION_TARGET, ACTION_SURVIVOR))
                    keyedReads++
                    lookupEntered?.complete(Unit)
                    releaseLookup?.await()
                    presented(if (id == ACTION_TARGET) row.get() else retainedRow.get())
                },
            )
        controller =
            ChatsController(
                appState = state,
                initialAccountRef = ACTION_ACCOUNT,
                memberSnapshotLoader = memberLoader,
                groupArchivedUpdater = { account, id, archived ->
                    check(account == ACTION_ACCOUNT && id in listOf(ACTION_TARGET, ACTION_SURVIVOR))
                    calls += if (id == ACTION_TARGET) "archive:$archived" else "archive-survivor:$archived"
                    archiveEntered?.complete(Unit)
                    releaseArchive?.await()
                    check(!archiveFails || id != ACTION_TARGET) { "archive rejected" }
                    val selected = if (id == ACTION_TARGET) row else retainedRow
                    selected.updateAndGet { it.copy(archived = archived, pinned = false, pinnedPosition = null) }
                    emptyGroupRecord(selected.get())
                },
            )
        // Only this unrelated row is retained. The target enters solely through the unread snapshot.
        controller.setChatListVisible(false)
        controller.applyChatListRow(chatRow(ACTION_SURVIVOR).copy(manuallyMarkedUnread = true))
        controller.setChatListVisible(true)
    }

    /** Change the actual observable account field without invoking unrelated account-session services. */
    fun replaceAccount() {
        WhiteNoiseAppState::class.java
            .getDeclaredMethod("setActiveAccountRef", String::class.java)
            .apply { isAccessible = true }
            .invoke(state, "replacement-actions-account")
    }

    /** Advance the actual runtime generation used by the production action owner. */
    fun replaceRuntimeGeneration() {
        val field =
            WhiteNoiseAppState::class.java
                .getDeclaredField("runtimeGeneration\$delegate")
                .apply { isAccessible = true }
        (field.get(state) as MutableIntState).intValue++
    }

    private fun presented(value: ChatListRowFfi) =
        presentedRow(value.groupIdHex).copy(
            row = value,
            actions =
                noChatRowActions().copy(
                    canMarkRead = true,
                    canMarkUnread = true,
                    canPin = true,
                    canUnpin = true,
                    canMute = true,
                    canUnmute = true,
                    canArchive = true,
                    canRestore = true,
                ),
        )

    private fun readCommand(args: Array<out Any?>?): ChatListRowFfi {
        check(args!![0] == ACTION_ACCOUNT && args[1] == ACTION_TARGET)
        calls += "read:${args[2]}"
        holdNativeCommand()
        return row.updateAndGet { it.copy(manuallyMarkedUnread = args[2] as Boolean) }
    }

    private fun pinCommand(args: Array<out Any?>?): ChatPinStateFfi {
        check(args!![0] == ACTION_ACCOUNT && args[1] == ACTION_TARGET)
        val pinned = args[2] as Boolean
        calls += "pin:$pinned"
        holdNativeCommand()
        row.updateAndGet { it.copy(pinned = pinned, pinnedPosition = if (pinned) 0u else null) }
        return ChatPinStateFfi(if (pinned) listOf(ACTION_TARGET) else emptyList())
    }

    private fun muteCommand(
        name: String,
        args: Array<out Any?>?,
    ): ChatNotificationSettingsFfi {
        check(args!![0] == ACTION_ACCOUNT && args[1] == ACTION_TARGET)
        if (name != "chatNotificationSettings") {
            val muted = name == "setChatMuted"
            calls += "mute:$muted"
            holdNativeCommand()
            row.updateAndGet { it.copy(muted = muted) }
        }
        return ChatNotificationSettingsFfi(
            accountRef = ACTION_ACCOUNT,
            accountIdHex = ACTION_ACCOUNT_ID,
            groupIdHex = ACTION_TARGET,
            muted = row.get().muted,
            mutedUntilMs = null,
            updatedAtMs = 1L,
        )
    }

    private fun holdNativeCommand() {
        nativeEntered?.countDown()
        check(releaseNative?.await(5, TimeUnit.SECONDS) != false) { "Native command gate timed out" }
    }

    private fun native(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (val name = method.name.substringBefore('-')) {
                "setChatManuallyUnread" -> readCommand(args)
                "setChatPinned" -> pinCommand(args)
                "setChatMuted", "clearChatMuted", "chatNotificationSettings" -> muteCommand(name, args)
                "chatListRow" -> row.get()
                "listMedia" -> emptyList<Any>()
                "catchUpAccounts" -> Unit
                "toString" -> "OffWindowActionNative"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected unread action native call: $name")
            }
        } as MarmotInterface
}
