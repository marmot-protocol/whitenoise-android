package dev.ipf.whitenoise.android.state

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext

internal class NotificationBootstrapTestFixture(
    context: Context,
    initiallyFailSubscriptions: Boolean = false,
    initiallyBlockSubscriptions: Boolean = false,
    initiallyBlockSubscriptionsSynchronously: Boolean = false,
    delayFirstNotificationDispatchAfterRuntimeStart: Boolean = false,
    receiverTimeoutMillis: Long = 100L,
    notificationUsersHaveDisplayNames: Boolean = true,
    private val accounts: List<AccountSummaryFfi> = emptyList(),
    private val chatListRows: List<ChatListRowFfi> = emptyList(),
    private val chatGroups: List<AppGroupRecordFfi> = emptyList(),
    private val markReadRow: ChatListRowFfi? = null,
    private val signInFailure: Throwable? = null,
) {
    private val appContext = context.applicationContext
    private val updates = Channel<NotificationUpdateFfi>(Channel.UNLIMITED)
    private val subscriptionGate =
        CompletableDeferred<Unit>().also { gate ->
            if (!initiallyBlockSubscriptions) gate.complete(Unit)
        }
    private val synchronousSubscriptionGate =
        CountDownLatch(if (initiallyBlockSubscriptionsSynchronously) 1 else 0)
    private val subscriberAttached = AtomicBoolean(false)
    private val emittedPostStartUpdate = AtomicBoolean(false)
    private val runtimeStarted = AtomicBoolean(false)
    private val notificationDispatchGate =
        PostStartNotificationDispatchGate(runtimeStarted).takeIf {
            delayFirstNotificationDispatchAfterRuntimeStart
        }
    private val subscriptionFailures = AtomicBoolean(initiallyFailSubscriptions)
    private val consumedUpdates = AtomicInteger(0)
    private val receiverTimeoutMillisState = AtomicLong(receiverTimeoutMillis)

    val runtimeStartCalls = AtomicInteger(0)
    val subscriptionCalls = AtomicInteger(0)
    val localSnapshotSubscriptionCalls = AtomicInteger(0)
    val localSnapshotGroupSubscriptionCalls = AtomicInteger(0)
    val localSnapshotReadCalls = AtomicInteger(0)
    val memberProjectionCalls = AtomicInteger(0)
    val signerRegistrationCalls = AtomicInteger(0)
    val markReadCalls = AtomicInteger(0)
    val npubCalls = AtomicInteger(0)

    @Volatile
    var receiverWasAttachedAtPostStartEmission = false
        private set

    @Volatile
    var channelsWereReadyAtPostStartEmission = false
        private set

    val update =
        NotificationUpdateFfi(
            notificationKey = "startup:account-a:message-a",
            conversationKey = "conversation:account-a:group-a",
            trigger = NotificationTriggerFfi.NEW_MESSAGE,
            trafficClass = NotificationTrafficClassFfi.STANDARD,
            accountRef = "account-a",
            accountIdHex = "account-a",
            groupIdHex = "group-a",
            groupName = "General",
            isDm = false,
            isMention = false,
            messageIdHex = "message-a",
            sender =
                NotificationUserFfi(
                    accountIdHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    displayName = "Alice".takeIf { notificationUsersHaveDisplayNames },
                    pictureUrl = null,
                ),
            receiver =
                NotificationUserFfi(
                    accountIdHex = "self",
                    displayName = "Me".takeIf { notificationUsersHaveDisplayNames },
                    pictureUrl = null,
                ),
            previewText = "Delivered while bootstrap is still running",
            reactionEmoji = null,
            reactedToPreview = null,
            timestampMs = 1_982L,
            isFromSelf = false,
        )

    private val marmot =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "start" -> {
                    runtimeStartCalls.incrementAndGet()
                    runtimeStarted.set(true)
                    Unit
                }
                "telemetryInstallId" -> "test-install"
                "setRelayTelemetryRuntimeConfig" -> Unit
                "setAuditLogTrackerConfig" -> arguments?.first()
                "relayTelemetrySettings" -> {
                    emitAtFirstPostStartFfiBoundary()
                    RelayTelemetrySettingsFfi(exportEnabled = false, exportIntervalSeconds = 60uL)
                }
                "auditLogSettings" -> AuditLogSettingsFfi(enabled = false)
                "setAuditLogSettings" -> arguments?.first()
                "chatNotificationSettings" ->
                    ChatNotificationSettingsFfi(
                        accountRef = arguments?.get(0) as String,
                        accountIdHex = "account-a",
                        groupIdHex = arguments[1] as String,
                        muted = false,
                        mutedUntilMs = null,
                        updatedAtMs = 0L,
                    )
                "notificationSettings" ->
                    NotificationSettingsFfi(
                        accountRef = arguments?.get(0) as String,
                        accountIdHex = "account-a",
                        localNotificationsEnabled = true,
                        nativePushEnabled = false,
                    )
                "markTimelineMessageRead" -> {
                    markReadCalls.incrementAndGet()
                    markReadRow
                }
                "npub" -> {
                    npubCalls.incrementAndGet()
                    "npub1coldidentityfallback"
                }
                "listAccounts" -> accounts
                "subscribeChatList" -> {
                    localSnapshotSubscriptionCalls.incrementAndGet()
                    emptyChatListSubscription()
                }
                "subscribeChats" -> {
                    localSnapshotGroupSubscriptionCalls.incrementAndGet()
                    emptyChatsSubscription()
                }
                "chatList" -> emptyList<Any>()
                "groupMemberIdsPage" -> {
                    memberProjectionCalls.incrementAndGet()
                    emptyList<Any>()
                }
                "userProfile" -> null
                "displayName" -> "Alice"
                "registerExternalSigner" -> {
                    signerRegistrationCalls.incrementAndGet()
                    Unit
                }
                "signInAccount" -> signInFailure?.let { throw it } ?: Unit
                "toString" -> "NotificationBootstrapMarmotFake"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> throw UnsupportedOperationException("Unexpected Marmot call: ${method.name}")
            }
        } as MarmotInterface

    val appState =
        WhiteNoiseAppState(
            context = appContext,
            draftStore = DraftStore(EmptyDraftPersistence),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = accounts.firstOrNull()?.label.orEmpty(),
            marmotRuntimeFactory = { AppMarmotRuntime(rootPath = "test", marmot = marmot) },
            notificationSubscriber = { subscribe() },
            notificationDispatcher = notificationDispatchGate ?: Dispatchers.IO,
            notificationReceiverTimeoutMillis = receiverTimeoutMillisState::get,
        )

    private fun emptyChatListSubscription(): ChatListSubscription =
        allocateWithoutConstructor(EmptyChatListSubscription::class.java).apply {
            onSnapshot = localSnapshotReadCalls::incrementAndGet
            rows = chatListRows
        }

    private fun emptyChatsSubscription(): ChatsSubscription =
        allocateWithoutConstructor(EmptyChatsSubscription::class.java).apply {
            onSnapshot = localSnapshotReadCalls::incrementAndGet
            groups = chatGroups
        }

    /** UniFFI's no-pointer constructor registers Android's cleaner, which the
     * Robolectric JVM module boundary cannot access. These inert subclasses
     * override every exercised method, so bypassing that native-only setup is
     * the faithful local-projection fake and never allocates a native handle. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> allocateWithoutConstructor(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private class EmptyChatListSubscription : ChatListSubscription(NoPointer) {
        lateinit var onSnapshot: () -> Unit
        lateinit var rows: List<ChatListRowFfi>

        override fun snapshot(): List<ChatListRowFfi> {
            onSnapshot()
            return rows
        }

        override fun close() = Unit
    }

    private class EmptyChatsSubscription : ChatsSubscription(NoPointer) {
        lateinit var onSnapshot: () -> Unit
        lateinit var groups: List<AppGroupRecordFfi>

        override fun snapshot(): List<AppGroupRecordFfi> {
            onSnapshot()
            return groups
        }

        override fun close() = Unit
    }

    fun allowSubscriptions(recoveryTimeoutMillis: Long? = null) {
        recoveryTimeoutMillis?.let(receiverTimeoutMillisState::set)
        subscriptionFailures.set(false)
        synchronousSubscriptionGate.countDown()
        subscriptionGate.complete(Unit)
    }

    suspend fun bootstrap() {
        runWithMainLooperPumping { appState.bootstrap() }
    }

    suspend fun ensureNotificationRuntimeStarted() {
        runWithMainLooperPumping { appState.ensureNotificationRuntimeStarted() }
    }

    suspend fun awaitUpdateConsumed() {
        withTimeout(5_000L) {
            while (consumedUpdates.get() == 0) delay(10L)
        }
    }

    suspend fun awaitNotificationPosted() {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        withTimeout(5_000L) {
            while (manager.activeNotifications.none { it.tag == "account-a|group-a" }) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    fun close() {
        notificationDispatchGate?.release()
        synchronousSubscriptionGate.countDown()
        subscriptionGate.complete(Unit)
        updates.close(CancellationException("test complete"))
    }

    /**
     * WhiteNoiseAppState owns bootstrap on Dispatchers.Main so it survives a
     * caller timeout. Robolectric's paused main looper does not advance while a
     * runBlocking test awaits that process-owned job, so pump it explicitly
     * while the real production call runs from a background caller.
     */
    private suspend fun <T> runWithMainLooperPumping(block: suspend () -> T): T =
        coroutineScope {
            val call = async(Dispatchers.Default) { block() }
            try {
                while (!call.isCompleted) {
                    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                    delay(1L)
                }
                shadowOf(Looper.getMainLooper()).idle()
                call.await()
            } finally {
                call.cancel()
            }
        }

    private suspend fun subscribe(): AppNotificationSubscription {
        subscriptionCalls.incrementAndGet()
        synchronousSubscriptionGate.await()
        if (subscriptionFailures.get()) throw IllegalStateException("subscription unavailable")
        subscriptionGate.await()
        subscriberAttached.set(true)
        return object : AppNotificationSubscription {
            override suspend fun next(): NotificationUpdateFfi? = nextUpdate()

            override fun close() {
                subscriberAttached.set(false)
            }
        }
    }

    private suspend fun nextUpdate() = updates.receive().also { consumedUpdates.incrementAndGet() }

    private fun emitAtFirstPostStartFfiBoundary() {
        if (!runtimeStarted.get() || !emittedPostStartUpdate.compareAndSet(false, true)) return
        receiverWasAttachedAtPostStartEmission = subscriberAttached.get()
        channelsWereReadyAtPostStartEmission =
            appContext
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(NotificationChannelSpec.GROUP_MESSAGES.id) != null
        if (subscriberAttached.get()) updates.trySend(update)
    }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private class PostStartNotificationDispatchGate(
        private val runtimeStarted: AtomicBoolean,
    ) : CoroutineDispatcher() {
        private data class PendingDispatch(
            val context: CoroutineContext,
            val block: Runnable,
        )

        private val intercepted = AtomicBoolean(false)
        private val pending = AtomicReference<PendingDispatch?>()

        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            if (runtimeStarted.get() && intercepted.compareAndSet(false, true)) {
                pending.set(PendingDispatch(context, block))
                return
            }
            Dispatchers.IO.dispatch(context, block)
        }

        fun release() {
            pending.getAndSet(null)?.let { dispatch ->
                Dispatchers.IO.dispatch(dispatch.context, dispatch.block)
            }
        }
    }
}
