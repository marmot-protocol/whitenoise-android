package dev.ipf.whitenoise.android.state

import android.app.NotificationManager
import android.content.Context
import dev.ipf.marmotkit.AuditDataModeFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class NotificationBootstrapTestFixture(
    context: Context,
    initiallyFailSubscriptions: Boolean = false,
    initiallyBlockSubscriptions: Boolean = false,
    receiverTimeoutMillis: Long = 100L,
) {
    private val appContext = context.applicationContext
    private val updates = Channel<NotificationUpdateFfi>(Channel.UNLIMITED)
    private val subscriptionGate =
        CompletableDeferred<Unit>().also { gate ->
            if (!initiallyBlockSubscriptions) gate.complete(Unit)
        }
    private val subscriberAttached = AtomicBoolean(false)
    private val emittedPostStartUpdate = AtomicBoolean(false)
    private val runtimeStarted = AtomicBoolean(false)
    private val subscriptionFailures = AtomicBoolean(initiallyFailSubscriptions)
    private val consumedUpdates = AtomicInteger(0)

    val runtimeStartCalls = AtomicInteger(0)
    val subscriptionCalls = AtomicInteger(0)

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
                    displayName = "Alice",
                    pictureUrl = null,
                ),
            receiver =
                NotificationUserFfi(
                    accountIdHex = "self",
                    displayName = "Me",
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
                "auditLogSettings" ->
                    AuditLogSettingsFfi(
                        enabled = false,
                        dataMode = AuditDataModeFfi.OBFUSCATED_SENSITIVE_DATA,
                    )
                "setAuditLogSettings" -> arguments?.first()
                "listAccounts", "chatList" -> emptyList<Any>()
                "displayName" -> "Alice"
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
            activeAccountRef = "",
            marmotRuntimeFactory = { AppMarmotRuntime(rootPath = "test", marmot = marmot) },
            notificationSubscriber = { subscribe() },
            notificationReceiverTimeoutMillis = receiverTimeoutMillis,
        )

    fun allowSubscriptions() {
        subscriptionFailures.set(false)
        subscriptionGate.complete(Unit)
    }

    suspend fun awaitUpdateConsumed() {
        withTimeout(5_000L) {
            while (consumedUpdates.get() == 0) delay(10L)
        }
    }

    fun close() {
        subscriptionGate.complete(Unit)
        updates.close(CancellationException("test complete"))
    }

    private suspend fun subscribe(): AppNotificationSubscription {
        subscriptionCalls.incrementAndGet()
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
}
