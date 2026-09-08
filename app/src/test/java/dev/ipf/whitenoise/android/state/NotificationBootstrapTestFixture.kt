package dev.ipf.whitenoise.android.state

import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarkdownInlineFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.NotificationTrafficClassFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.marmotkit.PushRegistrationShareOutcomeFfi
import dev.ipf.marmotkit.PushRegistrationShareStatusFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.whitenoise.android.notifications.NotificationChannelSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows.shadowOf
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.CoroutineContext

/** Typed-update fixture that preserves AppState startup, local MDK, and platform posting paths. */
internal class NotificationBootstrapTestFixture(
    context: Context,
    initiallyFailSubscriptions: Boolean = false,
    initiallyBlockSubscriptions: Boolean = false,
    initiallyBlockSubscriptionsSynchronously: Boolean = false,
    initiallyBlockRuntimeStartSynchronously: Boolean = false,
    delayFirstNotificationDispatchAfterRuntimeStart: Boolean = false,
    receiverTimeoutMillis: Long = 100L,
    bootstrapActionableTimeoutMillis: Long = 15_000L,
    notificationUsersHaveDisplayNames: Boolean = true,
    notificationReceiverHasDisplayName: Boolean = notificationUsersHaveDisplayNames,
    private val localDisplayName: String? = "Alice",
    previewText: String = "Delivered while bootstrap is still running",
    messageIdHex: String = "message-a",
    senderPictureUrl: String? = null,
    isDm: Boolean = false,
    private val accounts: List<AccountSummaryFfi> = emptyList(),
    private val chatListRows: List<ChatListRowFfi> = emptyList(),
    private val chatGroups: List<AppGroupRecordFfi> = emptyList(),
    private val markReadRow: ChatListRowFfi? = null,
    private val signInFailure: Throwable? = null,
    initialNotificationSettings: NotificationSettingsFfi? = null,
    // Optional behavior hooks so worker/reconciliation tests can steer the FFI
    // boundary per call; every default preserves the fixture's original shape.
    private val onOnboardingSnapshot: (() -> OnboardingSnapshotFfi?)? = null,
    private val onAuditLogSettings: (() -> Unit)? = null,
    private val onChatList: ((accountRef: String) -> List<ChatListRowFfi>)? = null,
    private val onGroupMemberIdsPage: ((groupIds: List<String>) -> List<AppGroupMemberIdsFfi>)? = null,
    private val onMarkTimelineMessageRead: (() -> ChatListRowFfi?)? = null,
    private val onSendText: ((accountRef: String, groupIdHex: String, text: String) -> SendSummaryFfi)? = null,
    private val onReactToMessage: (() -> SendSummaryFfi)? = null,
    private val onCatchUpAccounts: (() -> Unit)? = null,
    private val emitStartupNotification: Boolean = true,
    private val onSetNativePushEnabled: ((accountRef: String, enabled: Boolean) -> NotificationSettingsFfi)? = null,
    private val onClearPushRegistration: ((accountRef: String) -> PushRegistrationShareOutcomeFfi)? = null,
    private val onNotificationSettings: ((accountRef: String) -> NotificationSettingsFfi)? = null,
    nativePushFallbackPlatform: NativePushFallbackPlatform = AndroidNativePushFallbackPlatform(context),
    private val onDisplayName: ((call: Int, accountIdHex: String) -> String?)? = null,
    private val accountIdHexResolver: suspend (String) -> String? = { null },
    private val markdownDocumentFactory: ((String?) -> MarkdownDocumentFfi)? = null,
    private val profileRefreshRequest: (suspend (String) -> Unit)? = null,
    private val timelinePage: TimelinePageFfi? = null,
    private val messageRecords: List<AppMessageRecordFfi> = emptyList(),
    private val profileImageDownload: ((url: String, maxBytes: ULong) -> ByteArray)? = null,
    notificationFirstPostTimingObserver: ((NotificationFirstPostTimingEvent) -> Unit)? = null,
    notificationDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val appContext = context.applicationContext
    private val updates = Channel<NotificationUpdateFfi>(Channel.UNLIMITED)
    private val subscriptionGate =
        CompletableDeferred<Unit>().also { gate ->
            if (!initiallyBlockSubscriptions) gate.complete(Unit)
        }
    private val synchronousSubscriptionGate =
        CountDownLatch(if (initiallyBlockSubscriptionsSynchronously) 1 else 0)
    private val runtimeStartGate =
        CountDownLatch(if (initiallyBlockRuntimeStartSynchronously) 1 else 0)
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
    private val notificationSettingsState =
        ConcurrentHashMap<String, NotificationSettingsFfi>().apply {
            accounts.forEach { account -> put(account.label, defaultNotificationSettings(account.label)) }
            initialNotificationSettings?.let { settings -> put(settings.accountRef, settings) }
        }

    val runtimeStartCalls = AtomicInteger(0)
    val subscriptionCalls = AtomicInteger(0)
    val localSnapshotSubscriptionCalls = AtomicInteger(0)
    val localSnapshotGroupSubscriptionCalls = AtomicInteger(0)
    val localSnapshotReadCalls = AtomicInteger(0)
    val directChatListCalls = AtomicInteger(0)
    val memberProjectionCalls = AtomicInteger(0)
    val signerRegistrationCalls = AtomicInteger(0)
    val markReadCalls = AtomicInteger(0)
    val sendTextCalls = AtomicInteger(0)
    val reactToMessageCalls = AtomicInteger(0)
    val npubCalls = AtomicInteger(0)
    val senderDisplayNameCalls = AtomicInteger(0)
    val nativePushSettingWrites = CopyOnWriteArrayList<Pair<String, Boolean>>()
    val clearedPushRegistrations = CopyOnWriteArrayList<String>()
    val markdownParseCalls = AtomicInteger(0)
    val notificationTimelineCalls = AtomicInteger(0)
    val notificationMessageHistoryCalls = AtomicInteger(0)
    val notificationGroupDetailsCalls = AtomicInteger(0)

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
            groupName = "General".takeUnless { isDm },
            isDm = isDm,
            isMention = false,
            messageIdHex = messageIdHex,
            sender =
                NotificationUserFfi(
                    accountIdHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                    displayName = "Alice".takeIf { notificationUsersHaveDisplayNames },
                    pictureUrl = senderPictureUrl,
                ),
            receiver =
                NotificationUserFfi(
                    accountIdHex = "self",
                    displayName = "Me".takeIf { notificationReceiverHasDisplayName },
                    pictureUrl = null,
                ),
            previewText = previewText,
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
            // Kotlin value-class parameters mangle JVM method names (for example,
            // `messages-HqaIMu8` and `downloadProfileImage-z13BHRw`). Tests route
            // on the source-level Marmot method so these real bridge calls reach
            // their intended fakes.
            when (method.name.substringBefore('-')) {
                "onboardingSnapshot" -> onOnboardingSnapshot?.invoke()
                "start" -> {
                    runtimeStartCalls.incrementAndGet()
                    runtimeStartGate.await()
                    runtimeStarted.set(true)
                    Unit
                }
                "catchUpAccounts" -> {
                    val hook =
                        onCatchUpAccounts
                            ?: throw UnsupportedOperationException("Unexpected Marmot call: catchUpAccounts")
                    hook()
                }
                "telemetryInstallId" -> "test-install"
                "setRelayTelemetryRuntimeConfig", "setProductAnalyticsRuntimeConfig" -> Unit
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "setAuditLogTrackerConfig" -> arguments?.first()
                "relayTelemetrySettings" -> {
                    emitAtFirstPostStartFfiBoundary()
                    RelayTelemetrySettingsFfi(exportEnabled = false, exportIntervalSeconds = 60uL)
                }
                "auditLogSettings" -> {
                    onAuditLogSettings?.invoke()
                    AuditLogSettingsFfi(enabled = false)
                }
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
                "notificationSettings" -> {
                    val accountRef = arguments?.get(0) as String
                    onNotificationSettings?.invoke(accountRef)
                        ?: notificationSettingsState.computeIfAbsent(accountRef, ::defaultNotificationSettings)
                }
                "setLocalNotificationsEnabled" -> {
                    val accountRef = arguments?.get(0) as String
                    val enabled = arguments[1] as Boolean
                    val current = notificationSettingsState.computeIfAbsent(accountRef, ::defaultNotificationSettings)
                    NotificationSettingsFfi(
                        accountRef = accountRef,
                        accountIdHex = current.accountIdHex,
                        localNotificationsEnabled = enabled,
                        nativePushEnabled = current.nativePushEnabled,
                    ).also { notificationSettingsState[accountRef] = it }
                }
                "setNativePushEnabled" -> {
                    val accountRef = arguments?.get(0) as String
                    val enabled = arguments[1] as Boolean
                    nativePushSettingWrites += accountRef to enabled
                    val current = notificationSettingsState.computeIfAbsent(accountRef, ::defaultNotificationSettings)
                    val updated =
                        onSetNativePushEnabled?.invoke(accountRef, enabled)
                            ?: NotificationSettingsFfi(
                                accountRef = accountRef,
                                accountIdHex = current.accountIdHex,
                                localNotificationsEnabled = current.localNotificationsEnabled,
                                nativePushEnabled = enabled,
                            )
                    notificationSettingsState[accountRef] = updated
                    updated
                }
                "clearPushRegistration" -> {
                    val accountRef = arguments?.get(0) as String
                    clearedPushRegistrations += accountRef
                    onClearPushRegistration?.invoke(accountRef) ?: completePushRegistrationClear()
                }
                "markTimelineMessageRead" -> {
                    markReadCalls.incrementAndGet()
                    onMarkTimelineMessageRead?.invoke() ?: markReadRow
                }
                "sendText" -> {
                    sendTextCalls.incrementAndGet()
                    val hook = onSendText ?: throw UnsupportedOperationException("Unexpected Marmot call: sendText")
                    hook(arguments?.get(0) as String, arguments[1] as String, arguments[2] as String)
                }
                "reactToMessage" -> {
                    reactToMessageCalls.incrementAndGet()
                    val hook =
                        onReactToMessage
                            ?: throw UnsupportedOperationException("Unexpected Marmot call: reactToMessage")
                    hook()
                }
                "groupMembers" -> {
                    val accountRef = arguments?.get(0) as String
                    // A loaded roster missing the querying account suppresses that
                    // row's unread count, so answer with the account itself.
                    accounts
                        .filter { it.label == accountRef }
                        .map { member ->
                            AppGroupMemberRecordFfi(
                                memberIdHex = member.accountIdHex,
                                account = member.label,
                                local = true,
                            )
                        }
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
                "chatList" -> {
                    directChatListCalls.incrementAndGet()
                    onChatList?.invoke(arguments?.get(0) as String) ?: chatListRows
                }
                "timelineMessages" -> {
                    notificationTimelineCalls.incrementAndGet()
                    timelinePage
                        // An exhausted, empty page: recovery probes conclude
                        // NotCommitted deterministically instead of erroring.
                        ?: TimelinePageFfi(messages = emptyList(), hasMoreBefore = false, hasMoreAfter = false)
                }
                "messages" -> {
                    notificationMessageHistoryCalls.incrementAndGet()
                    messageRecords
                }
                "parseMarkdown" -> {
                    markdownParseCalls.incrementAndGet()
                    val raw = arguments?.firstOrNull() as? String
                    markdownDocumentFactory?.invoke(raw) ?: markdownDocument(raw)
                }
                "groupMemberIdsPage" -> {
                    memberProjectionCalls.incrementAndGet()
                    @Suppress("UNCHECKED_CAST")
                    val groupIds = arguments?.get(1) as List<String>
                    onGroupMemberIdsPage?.invoke(groupIds) ?: emptyList<AppGroupMemberIdsFfi>()
                }
                "userProfile" -> null
                "downloadProfileImage" -> {
                    val download = profileImageDownload ?: error("Unexpected Marmot call: downloadProfileImage")
                    download(arguments?.get(0) as String, (arguments[1] as Long).toULong())
                }
                "groupDetails" -> {
                    notificationGroupDetailsCalls.incrementAndGet()
                    throw UnsupportedOperationException(
                        "Notification avatar group details are unavailable in this fixture",
                    )
                }
                "displayName" -> {
                    val accountIdHex = arguments?.firstOrNull() as String
                    val call =
                        if (accountIdHex == update.sender.accountIdHex) {
                            senderDisplayNameCalls.incrementAndGet()
                        } else {
                            0
                        }
                    onDisplayName?.invoke(call, accountIdHex) ?: localDisplayName
                }
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
            accountIdHexResolver = accountIdHexResolver,
            accounts = emptyList(),
            activeAccountRef = accounts.firstOrNull()?.label.orEmpty(),
            profileReader = if (profileRefreshRequest != null) { _ -> null } else null,
            profileRefreshRequest = profileRefreshRequest,
            marmotRuntimeFactory = { AppMarmotRuntime(rootPath = "test", marmot = marmot) },
            notificationSubscriber = { subscribe() },
            notificationDispatcher = notificationDispatchGate ?: notificationDispatcher,
            notificationReceiverTimeoutMillis = receiverTimeoutMillisState::get,
            bootstrapActionableTimeoutMillis = { bootstrapActionableTimeoutMillis },
            nativePushFallbackPlatform = nativePushFallbackPlatform,
            notificationFirstPostTimingObserver = notificationFirstPostTimingObserver,
        )

    /** Supplies an inert local chat-list snapshot without a native pointer. */
    private fun emptyChatListSubscription(): ChatListSubscription =
        allocateWithoutConstructor(EmptyChatListSubscription::class.java).apply {
            onSnapshot = localSnapshotReadCalls::incrementAndGet
            rows = chatListRows
        }

    /** Supplies an inert local group snapshot without a native pointer. */
    private fun emptyChatsSubscription(): ChatsSubscription =
        allocateWithoutConstructor(EmptyChatsSubscription::class.java).apply {
            onSnapshot = localSnapshotReadCalls::incrementAndGet
            groups = chatGroups
        }

    /** Keeps existing bootstrap tests on locally enabled notifications with native push off. */
    private fun defaultNotificationSettings(accountRef: String) =
        NotificationSettingsFfi(
            accountRef = accountRef,
            accountIdHex = "account-a",
            localNotificationsEnabled = true,
            nativePushEnabled = false,
        )

    /** Returns the fixture's authoritative per-account notification state. */
    fun notificationSettings(accountRef: String): NotificationSettingsFfi =
        notificationSettingsState
            .computeIfAbsent(accountRef, ::defaultNotificationSettings)

    /** Replaces one account's authoritative settings without affecting another fixture account. */
    fun replaceNotificationSettings(settings: NotificationSettingsFfi) {
        notificationSettingsState[settings.accountRef] = settings
    }

    /** Builds the successful empty sharing result used by uncustomized registration clears. */
    private fun completePushRegistrationClear() =
        PushRegistrationShareOutcomeFfi(
            status = PushRegistrationShareStatusFfi.COMPLETE,
            attemptedGroups = 0u,
            succeededGroups = 0u,
            failedGroups = 0u,
            pendingGroups = 0u,
        )

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

    /** Releases both asynchronous and synchronous subscription failure gates. */
    fun allowSubscriptions(recoveryTimeoutMillis: Long? = null) {
        recoveryTimeoutMillis?.let(receiverTimeoutMillisState::set)
        subscriptionFailures.set(false)
        synchronousSubscriptionGate.countDown()
        subscriptionGate.complete(Unit)
    }

    /** Releases a synchronously blocked Marmot runtime start. */
    fun allowRuntimeStart() {
        runtimeStartGate.countDown()
    }

    /** Runs the production bootstrap while pumping Robolectric's paused main looper. */
    suspend fun bootstrap() {
        runWithMainLooperPumping { appState.bootstrap() }
    }

    /** Runs the production explicit-retry path with main-looper pumping. */
    suspend fun retryBootstrap() {
        runWithMainLooperPumping { appState.retryBootstrap() }
    }

    /** Starts notification runtime ownership while pumping its main-thread receiver work. */
    suspend fun ensureNotificationRuntimeStarted() {
        runWithMainLooperPumping { appState.ensureNotificationRuntimeStarted() }
    }

    /** Exercises the service entry point while advancing Robolectric’s main looper. */
    suspend fun awaitPushDrain(timeoutMillis: Long): Boolean =
        runWithMainLooperPumping { appState.ensureNotificationRuntimeStartedAndAwaitPushDrain(timeoutMillis) }

    /** Delivers an engine update through the real process-owned notification listener. */
    fun emitNotification(notification: NotificationUpdateFfi = update) {
        check(updates.trySend(notification).isSuccess)
    }

    /** Delivers one service-supervisor acknowledgement and waits for its scheduled sync. */
    suspend fun acknowledgeNativePushFallbackRuntime(generation: Long) {
        runWithMainLooperPumping {
            withContext(Dispatchers.Main.immediate) {
                appState.onNativePushFallbackRuntimeStarted(generation)
            }?.join()
        }
    }

    /** Starts one acknowledged fallback sync without waiting, for held-FFI lifecycle tests. */
    suspend fun beginNativePushFallbackRuntimeAcknowledgement(generation: Long) =
        runWithMainLooperPumping {
            withContext(Dispatchers.Main.immediate) {
                appState.onNativePushFallbackRuntimeStarted(generation)
            }
        }

    /** Delivers one service-unavailable callback on the same Main-owned boundary as production. */
    suspend fun rejectNativePushFallbackRuntime(generation: Long) {
        runWithMainLooperPumping {
            withContext(Dispatchers.Main.immediate) {
                appState.onNativePushFallbackRuntimeUnavailable(generation)
            }
        }
    }

    /** Waits for the fake engine update to cross the subscription boundary, before Android posting is required. */
    suspend fun awaitUpdateConsumed() {
        withTimeout(5_000L) {
            while (consumedUpdates.get() == 0) delay(10L)
        }
    }

    /**
     * Waits for the stable card while optionally advancing Robolectric's Android
     * clock. Disabling clock advancement is rendering-only control, not
     * wall-clock or device-latency evidence.
     */
    suspend fun awaitNotificationPosted(advanceMainClock: Boolean = true) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        withTimeout(5_000L) {
            while (manager.activeNotifications.none { it.tag == "account-a|group-a" }) {
                val mainLooper = shadowOf(Looper.getMainLooper())
                if (advanceMainClock) {
                    mainLooper.idleFor(Duration.ofMillis(1L))
                } else {
                    mainLooper.idle()
                }
                delay(1L)
            }
        }
    }

    /** Waits until the active fixture card carries the expected user-visible body. */
    suspend fun awaitNotificationBody(expected: String) {
        val manager = appContext.getSystemService(NotificationManager::class.java)
        withTimeout(5_000L) {
            while (
                manager.activeNotifications
                    .firstOrNull { it.tag == "account-a|group-a" }
                    ?.notification
                    ?.extras
                    ?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                    ?.toString() != expected
            ) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Waits until the post-first-write avatar group lookup has begun. */
    suspend fun awaitNotificationEnrichmentAttempt() {
        withTimeout(5_000L) {
            while (notificationGroupDetailsCalls.get() == 0) delay(1L)
        }
    }

    /** Waits for a precise minimum of local sender identity reads. */
    suspend fun awaitSenderDisplayNameCalls(expected: Int) {
        withTimeout(5_000L) {
            while (senderDisplayNameCalls.get() < expected) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1L))
                delay(1L)
            }
        }
    }

    /** Queues an additional typed update on the live production subscription. */
    fun emitUpdate(update: NotificationUpdateFfi) {
        check(updates.trySend(update).isSuccess) { "Notification fixture channel is closed" }
    }

    /** Seeds then resets npub call accounting for a cache-only assertion. */
    fun warmNpubCache() {
        appState.shortNpub(update.sender.accountIdHex)
        npubCalls.set(0)
    }

    /** Releases the optional post-subscription dispatch gate. */
    fun releaseNotificationDispatch() {
        notificationDispatchGate?.release()
    }

    /** Releases all gates and closes the update stream without touching app data. */
    fun close() {
        runtimeStartGate.countDown()
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
     * Use the same boundary when joining Main-owned service acknowledgement jobs.
     */
    suspend fun <T> runWithMainLooperPumping(block: suspend () -> T): T =
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

    /** Implements the fixture's restartable notification subscription boundary. */
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

    /** Counts only updates actually consumed by the process-owned notification subscription. */
    private suspend fun nextUpdate() = updates.receive().also { consumedUpdates.incrementAndGet() }

    /** Emits startup traffic only when the scenario opts into the original bootstrap-race probe. */
    private fun emitAtFirstPostStartFfiBoundary() {
        if (!emitStartupNotification) return
        if (!runtimeStarted.get() || !emittedPostStartUpdate.compareAndSet(false, true)) return
        receiverWasAttachedAtPostStartEmission = subscriberAttached.get()
        channelsWereReadyAtPostStartEmission =
            appContext
                .getSystemService(NotificationManager::class.java)
                .getNotificationChannel(NotificationChannelSpec.GROUP_MESSAGES.id) != null
        if (subscriberAttached.get()) updates.trySend(update)
    }

    /** Builds the minimal production-shaped Markdown tree for fixture text. */
    private fun markdownDocument(raw: String?): MarkdownDocumentFfi {
        val source = raw.orEmpty()
        val inline =
            if (source.length >= 4 && source.startsWith("**") && source.endsWith("**")) {
                MarkdownInlineFfi.Strong(listOf(MarkdownInlineFfi.Text(source.substring(2, source.length - 2))))
            } else {
                MarkdownInlineFfi.Text(source)
            }
        return MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(listOf(inline))),
            blankLinesBefore = ByteArray(0),
        )
    }

    private object EmptyDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    /** Holds the first post-start dispatch until release, then permanently passes every dispatch through. */
    internal class PostStartNotificationDispatchGate(
        private val runtimeStarted: AtomicBoolean,
        private val delegate: CoroutineDispatcher = Dispatchers.IO,
        private val beforePendingPublication: (() -> Unit)? = null,
    ) : CoroutineDispatcher() {
        private data class PendingDispatch(
            val context: CoroutineContext,
            val block: Runnable,
        )

        private val stateLock = Any()
        private var intercepted = false
        private var released = false
        private var pending: PendingDispatch? = null

        /** Holds only the first qualifying dispatch while preserving release as a permanent state transition. */
        override fun dispatch(
            context: CoroutineContext,
            block: Runnable,
        ) {
            val dispatchNow =
                synchronized(stateLock) {
                    if (released || !runtimeStarted.get() || intercepted) {
                        true
                    } else {
                        intercepted = true
                        beforePendingPublication?.invoke()
                        pending = PendingDispatch(context, block)
                        false
                    }
                }
            if (dispatchNow) {
                delegate.dispatch(context, block)
            }
        }

        /** Opens the gate permanently and dispatches an already intercepted continuation at most once. */
        fun release() {
            val ready =
                synchronized(stateLock) {
                    released = true
                    pending.also { pending = null }
                }
            ready?.let { dispatch ->
                delegate.dispatch(dispatch.context, dispatch.block)
            }
        }
    }
}
