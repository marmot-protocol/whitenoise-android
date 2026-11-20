package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.input.TextFieldValue
import androidx.work.NetworkType
import androidx.work.WorkManager
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.ChatConversationKindFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EventsSubscription
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.ProductAnalyticsActivityFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsStatusFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.RuntimePolicyHooks
import dev.ipf.whitenoise.android.amber.AmberSignerController
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationSendRequest
import dev.ipf.whitenoise.android.audio.DictationDiagnosticLifecycle
import dev.ipf.whitenoise.android.audio.DictationDiagnostics
import dev.ipf.whitenoise.android.audio.MicrophoneCaptureCoordinator
import dev.ipf.whitenoise.android.audio.tts.AndroidTtsSpeechEngine
import dev.ipf.whitenoise.android.audio.tts.TtsEngineHandle
import dev.ipf.whitenoise.android.audio.tts.TtsEngineResolver
import dev.ipf.whitenoise.android.audio.tts.TtsEngineSelectionResult
import dev.ipf.whitenoise.android.audio.tts.TtsEngineSelectionSnapshot
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryPager
import dev.ipf.whitenoise.android.audio.tts.TtsHistorySession
import dev.ipf.whitenoise.android.audio.tts.TtsPlaybackForegroundService
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.TtsState
import dev.ipf.whitenoise.android.audio.tts.TtsVoiceResolution
import dev.ipf.whitenoise.android.audio.tts.adoptTtsEngineSelection
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.resolveTtsOnDispatcher
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import dev.ipf.whitenoise.android.core.DateFormatPreference
import dev.ipf.whitenoise.android.core.DateTimeFormatState
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.core.MarmotClientRootGate
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.core.TimeFormatPreference
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.exceedsOutgoingMessageLimit
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.DmCreationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrace
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnosticStage
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnostics
import dev.ipf.whitenoise.android.diagnostics.StartupPerformanceDiagnostics
import dev.ipf.whitenoise.android.media.MediaInventory
import dev.ipf.whitenoise.android.media.MediaThumbnail
import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorSourceStore
import dev.ipf.whitenoise.android.media.editor.MarmotMessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.notifications.ConversationNotificationRouting
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPreferences
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationBatteryPolicy
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PinnedConversationCapability
import dev.ipf.whitenoise.android.notifications.PinnedConversationPicture
import dev.ipf.whitenoise.android.notifications.PinnedConversationPresentation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushStorageRecovery
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import dev.ipf.whitenoise.android.notifications.PushWakeRecoveryScheduler
import dev.ipf.whitenoise.android.notifications.hasLegacyContactShortcuts
import dev.ipf.whitenoise.android.notifications.readNotificationBatteryPolicy
import dev.ipf.whitenoise.android.share.ShareInboundStager
import dev.ipf.whitenoise.android.share.ShareShortcutPublisher
import dev.ipf.whitenoise.android.share.ShareStagingStore
import dev.ipf.whitenoise.android.share.shareResolveMime
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.relaysConnectedFromHealth
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import dev.ipf.whitenoise.android.ui.onboarding.SignUpController
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupCoordinator
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupOptions
import dev.ipf.whitenoise.android.updates.AppSelfUpdateFlows
import dev.ipf.whitenoise.android.updates.AppSelfUpdateState
import dev.ipf.whitenoise.android.updates.AppUpdateForegroundState
import dev.ipf.whitenoise.android.updates.AppUpdateInfo
import dev.ipf.whitenoise.android.updates.AppUpdateNotifier
import dev.ipf.whitenoise.android.updates.AppUpdateRepository
import dev.ipf.whitenoise.android.updates.shouldPostAppUpdateNotification
import dev.ipf.whitenoise.android.updates.shouldStartInAppSelfUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import dev.ipf.whitenoise.android.audio.ConversationDictationTargetValidation as TargetValidation

private const val NATIVE_ATTACHMENT_PERMISSION_RETRY_LIMIT = 3
private const val NATIVE_ATTACHMENT_PERMISSION_RETRY_DELAY_MILLIS = 500L

/** How long durable automatic work waits for the native runtime to apply the live approval before retrying. */
internal const val NATIVE_ATTACHMENT_PERMISSION_SETTLE_MILLIS = 10_000L

/** Automatic requests made per durable run before a refusal or a lasting pause becomes a retryable failure. */
private const val AUTOMATIC_ADMISSION_ATTEMPTS = 2
internal const val INCOMING_ATTACHMENT_TAG = "DMAttachmentReceipt"

/**
 * How inviting one profile to several groups went: of [attempted] groups, [failures] failed and in [waited] the user
 * chose to wait on a group-change question, so those groups were not invited either.
 */
internal data class ProfileGroupInviteOutcome(
    val attempted: Int,
    val failures: Int,
    val firstFailure: AppText? = null,
    val waited: Int = 0,
) {
    val delivered: Int = attempted - failures - waited
    val completedSuccessfully: Boolean = attempted > 0 && failures == 0 && waited == 0
}

internal fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

internal data class AppMarmotRuntime(
    val rootPath: String,
    val marmot: MarmotInterface,
)

/** One runtime identity captured by an Activity-owned host-performance reporter. */
internal data class HostPerformanceRuntimeOwner(
    val runtime: AppMarmotRuntime,
    val generation: Int,
)

private fun openMarmotRuntime(context: Context) = MarmotClient(context).let { AppMarmotRuntime(it.rootPath, it.marmot) }

private const val APP_STATE_SCOPE_LOG_TAG = "WhiteNoiseAppState"
private const val FORWARD_BACKGROUND_RETRY_ATTEMPTS = 3
private const val FORWARD_BACKGROUND_RETRY_DELAY_MS = 1_000L
private const val FORWARD_TERMINAL_STATUS_DURATION_MS = 2_000L

// NotificationManager cancellation must not run on main or wait behind the
// long-lived listener and other blocking work on Dispatchers.IO. One ordered,
// process-lifetime lane is sufficient because each transaction is bounded.
private val processNotificationCardCancellationDispatcher: CoroutineDispatcher by lazy {
    Executors
        .newSingleThreadExecutor { task ->
            Thread(task, "wn-notification-cancel").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}

internal fun appStateScopeExceptionHandler(
    report: (Throwable) -> Unit = { throwable ->
        if (BuildConfig.DEBUG) {
            Log.w(APP_STATE_SCOPE_LOG_TAG, "unhandled AppState scope failure", throwable)
        } else {
            Log.w(APP_STATE_SCOPE_LOG_TAG, "unhandled AppState scope failure")
        }
    },
): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable -> report(throwable) }

private data class PendingAccountSwitchTrace(
    val accountRef: String,
    val startedAtMs: Long,
    val performance: PerformanceTrace? = null,
)

private data class StartupUnreadRefresh(
    val accounts: List<AccountSummaryFfi>,
    val accountListRevision: Long,
)

class WhiteNoiseAppState private constructor(
    context: Context,
    val draftStore: DraftStore,
    startPlatformServices: Boolean,
    private val accountIdHexResolver: (suspend (String) -> String?)?,
    internal val profileReader: (suspend (String) -> UserProfileMetadataFfi?)?,
    internal val profileDisplayNameReader: (suspend (String) -> String?)?,
    private val npubPresentationReader: (suspend (String) -> String?)?,
    private val profileRefreshRequest: (suspend (String) -> Unit)?,
    private val identityLoginCalls: IdentityLoginCalls?,
    private val marmotAccessObserver: (() -> Unit)?,
    private val marmotRuntimeFactory: (Context) -> AppMarmotRuntime,
    initialMarmotRuntime: AppMarmotRuntime?,
    internal val notificationSubscriber: suspend (MarmotInterface) -> AppNotificationSubscription,
    internal val notificationDispatcher: CoroutineDispatcher,
    private val marmotIoDispatcher: CoroutineDispatcher,
    private val notificationCardCancellationDispatcher: CoroutineDispatcher,
    private val notificationReceiverTimeoutMillis: () -> Long,
    private val pushWakeNowMs: () -> Long,
    private val pushWakeStorageDispatcher: CoroutineDispatcher,
    private val schedulePushWakeRecovery: () -> Boolean,
    private val bootstrapActionableTimeoutMillis: () -> Long,
    internal val notificationFirstPostTimingObserver: ((NotificationFirstPostTimingEvent) -> Unit)?,
    private val notificationNetworkRecoveryDiagnostics: NotificationNetworkRecoveryDiagnostics,
    private val inboundShareTextStager: ((String, String, String) -> Unit)?,
    private val messageDraftRepositoryOverride: MessageDraftRepository?,
    private val nativePushFallbackPlatform: NativePushFallbackPlatform,
    private val pushServerConfigProvider: () -> PushServerConfig?,
    private val fcmTokenReaderOverride: (suspend () -> String?)?,
    private val nativePushCapabilityResolver: (PushServerConfig?) -> NativePushCapability,
    preferencesOverride: SharedPreferences?,
    pushTokenStoreOverride: PushTokenStore?,
    initialAccounts: List<AccountSummaryFfi>,
    initialActiveAccountRef: String?,
    private val appLockTimestampWrite: ((Long) -> Unit)? = null,
    private val appLockPersistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dictationDiagnosticLifecycle = DictationDiagnosticLifecycle()

    /** Interactive imports stay separate from the active account until MDK certifies readiness. */
    internal val accountSetup by lazy {
        AccountSetupCoordinator(
            this,
            mutationsScope,
            onPhaseChange = { phase = it },
            awaitActivationReadiness = ::completeReceiverGatedStartup,
        ) { runtime, account, isMountCurrent ->
            if (!isMountCurrent()) throw CancellationException("Setup mount was replaced")
            val reported = withContext(Dispatchers.IO) { amberSigner.requestPublicKey() }
            withAccountLifetime {
                if (!isMountCurrent() || marmot() !== runtime) throw CancellationException("Setup mount was replaced")
                val canonical = withContext(Dispatchers.IO) { runtime.accountIdHex(reported) }
                check(canonical == account) { "signer account does not match setup" }
                withContext(Dispatchers.IO) {
                    runtime.registerExternalSigner(account, amberSigner.buildSigner(account))
                }
            }
        }
    }

    /** Process-owned, bounded composer geometry keyed by account and conversation. */
    internal val composerExpansionStateRetention = ComposerExpansionStateRetention()

    /** Bounded in-flight edit handoffs survive conversation-controller replacement. */
    internal val pendingMessageEditHandoff = PendingMessageEditHandoff()

    constructor(context: Context) :
        this(
            context = context,
            draftStore = DraftStore.forContext(context.applicationContext),
            startPlatformServices = true,
            accountIdHexResolver = null,
            profileReader = null,
            profileDisplayNameReader = null,
            npubPresentationReader = null,
            profileRefreshRequest = null,
            identityLoginCalls = null,
            marmotAccessObserver = null,
            marmotRuntimeFactory = ::openMarmotRuntime,
            initialMarmotRuntime = null,
            notificationSubscriber = ::subscribeToNotifications,
            notificationDispatcher = Dispatchers.IO,
            marmotIoDispatcher = Dispatchers.IO,
            notificationCardCancellationDispatcher = processNotificationCardCancellationDispatcher,
            notificationReceiverTimeoutMillis = { NOTIFICATION_STARTUP_RECEIVER_TIMEOUT_MILLIS },
            pushWakeNowMs = System::currentTimeMillis,
            pushWakeStorageDispatcher = Dispatchers.IO,
            schedulePushWakeRecovery = { PushWakeRecoveryScheduler.schedule(context.applicationContext) },
            bootstrapActionableTimeoutMillis = { BOOTSTRAP_ACTIONABLE_TIMEOUT_MILLIS },
            notificationFirstPostTimingObserver = null,
            notificationNetworkRecoveryDiagnostics = NotificationNetworkRecoveryDiagnostics(),
            inboundShareTextStager = null,
            messageDraftRepositoryOverride = null,
            nativePushFallbackPlatform = AndroidNativePushFallbackPlatform(context),
            pushServerConfigProvider = PushServerConfig::current,
            fcmTokenReaderOverride = null,
            nativePushCapabilityResolver = { nativePushCapabilityForContext(context.applicationContext, it) },
            preferencesOverride = null,
            pushTokenStoreOverride = null,
            initialAccounts = emptyList(),
            initialActiveAccountRef = null,
        )

    /** Tests inject runtime/state seams; a null account override preserves production preference restoration. */
    internal constructor(
        context: Context,
        draftStore: DraftStore,
        accountIdHexResolver: suspend (String) -> String?,
        accounts: List<AccountSummaryFfi>,
        activeAccountRef: String?,
        profileReader: (suspend (String) -> UserProfileMetadataFfi?)? = null,
        profileDisplayNameReader: (suspend (String) -> String?)? = null,
        npubPresentationReader: (suspend (String) -> String?)? = { null },
        profileRefreshRequest: (suspend (String) -> Unit)? = null,
        identityLoginCalls: IdentityLoginCalls? = null,
        marmotAccessObserver: (() -> Unit)? = null,
        marmotRuntimeFactory: (Context) -> AppMarmotRuntime = ::openMarmotRuntime,
        initialMarmotRuntime: AppMarmotRuntime? = null,
        notificationSubscriber: suspend (MarmotInterface) -> AppNotificationSubscription = ::subscribeToNotifications,
        notificationDispatcher: CoroutineDispatcher = Dispatchers.IO,
        marmotIoDispatcher: CoroutineDispatcher = Dispatchers.IO,
        notificationCardCancellationDispatcher: CoroutineDispatcher = processNotificationCardCancellationDispatcher,
        notificationReceiverTimeoutMillis: () -> Long = { NOTIFICATION_STARTUP_RECEIVER_TIMEOUT_MILLIS },
        pushWakeNowMs: () -> Long = System::currentTimeMillis,
        pushWakeStorageDispatcher: CoroutineDispatcher = Dispatchers.IO,
        schedulePushWakeRecovery: () -> Boolean = { PushWakeRecoveryScheduler.schedule(context.applicationContext) },
        bootstrapActionableTimeoutMillis: () -> Long = { BOOTSTRAP_ACTIONABLE_TIMEOUT_MILLIS },
        notificationFirstPostTimingObserver: ((NotificationFirstPostTimingEvent) -> Unit)? = null,
        notificationNetworkRecoveryDiagnostics: NotificationNetworkRecoveryDiagnostics =
            NotificationNetworkRecoveryDiagnostics(),
        inboundShareTextStager: ((String, String, String) -> Unit)? = null,
        messageDraftRepository: MessageDraftRepository? = null,
        nativePushFallbackPlatform: NativePushFallbackPlatform = AndroidNativePushFallbackPlatform(context),
        pushServerConfigProvider: () -> PushServerConfig? = PushServerConfig::current,
        fcmTokenReader: (suspend () -> String?)? = null,
        nativePushCapabilityResolver: (PushServerConfig?) -> NativePushCapability = {
            nativePushCapabilityForContext(context.applicationContext, it)
        },
        preferences: SharedPreferences? = null,
        pushTokenStore: PushTokenStore? = null,
        appLockTimestampWrite: ((Long) -> Unit)? = null,
        appLockPersistenceDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) : this(
        context = context,
        draftStore = draftStore,
        startPlatformServices = false,
        accountIdHexResolver = accountIdHexResolver,
        profileReader = profileReader,
        profileDisplayNameReader = profileDisplayNameReader,
        npubPresentationReader = npubPresentationReader,
        profileRefreshRequest = profileRefreshRequest,
        identityLoginCalls = identityLoginCalls,
        marmotAccessObserver = marmotAccessObserver,
        marmotRuntimeFactory = marmotRuntimeFactory,
        initialMarmotRuntime = initialMarmotRuntime,
        notificationSubscriber = notificationSubscriber,
        notificationDispatcher = notificationDispatcher,
        marmotIoDispatcher = marmotIoDispatcher,
        notificationCardCancellationDispatcher = notificationCardCancellationDispatcher,
        notificationReceiverTimeoutMillis = notificationReceiverTimeoutMillis,
        pushWakeNowMs = pushWakeNowMs,
        pushWakeStorageDispatcher = pushWakeStorageDispatcher,
        schedulePushWakeRecovery = schedulePushWakeRecovery,
        bootstrapActionableTimeoutMillis = bootstrapActionableTimeoutMillis,
        notificationFirstPostTimingObserver = notificationFirstPostTimingObserver,
        notificationNetworkRecoveryDiagnostics = notificationNetworkRecoveryDiagnostics,
        inboundShareTextStager = inboundShareTextStager,
        messageDraftRepositoryOverride = messageDraftRepository,
        nativePushFallbackPlatform = nativePushFallbackPlatform,
        pushServerConfigProvider = pushServerConfigProvider,
        fcmTokenReaderOverride = fcmTokenReader,
        nativePushCapabilityResolver = nativePushCapabilityResolver,
        preferencesOverride = preferences,
        pushTokenStoreOverride = pushTokenStore,
        initialAccounts = accounts,
        initialActiveAccountRef = activeAccountRef,
        appLockTimestampWrite = appLockTimestampWrite,
        appLockPersistenceDispatcher = appLockPersistenceDispatcher,
    )

    internal val appContext = context.applicationContext
    internal val publicJpegPreviewPreferences =
        dev.ipf.whitenoise.android.media
            .PublicJpegPreviewPreferences(appContext) { id -> isLocalContactAccount(accounts, id) }
    internal val publicJpegPreviewLoader =
        dev.ipf.whitenoise.android.media
            .PublicJpegPreviewLoader()

    /** Emoji images already uploaded for the current epoch, so sends and retries never upload one twice. */
    internal val emojiUploads = EmojiUploadCache()
    internal val emojiSetLibrary = EmojiSetLibrary()
    internal val preferences =
        preferencesOverride ?: appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
    internal val contactPictureStore =
        ContactPictureStore(preferences, appContext.noBackupFilesDir.resolve("contact-pictures"))
            .also {
                dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
                    .attach(it) { activeAccountRef }
                dev.ipf.whitenoise.android.core.AvatarImageLoader
                    .attachActiveAccount { activeAccountRef }
            }
    internal val notificationContactRowPictures = NotificationContactRowPictures()
    internal val localGroupDeleteCleanupJournal =
        LocalGroupDeleteCleanupJournal(appContext.noBackupFilesDir.resolve("local-group-delete-cleanup"))
    internal val localGroupDeleteCleanupMutex = Mutex()
    internal val defaultDisappearingMessagesPreferences =
        DefaultDisappearingMessagesPreferences(appContext, preferences)
    internal val swipePreferences = SwipePreferences(preferences)
    internal val conversationDictationPreferences = ConversationDictationPreferences(appContext)
    internal val microphoneCaptureCoordinator = MicrophoneCaptureCoordinator()
    private val dictationMicrophoneOwner = Any()
    private var initializedDictation by mutableStateOf<ConversationDictationController?>(null)
    internal val conversationDictation: ConversationDictationController
        get() = conversationDictationOwner.value

    /** Lifecycle checks and the chat list observe dictation without creating a controller in an idle process. */
    internal fun initializedConversationDictation(): ConversationDictationController? = initializedDictation

    private val conversationDictationOwner =
        lazy {
            ConversationDictationController(
                context = appContext,
                microphoneRecoveryAllowed = { appInForeground && !appLock.screenVisible && !appLock.evaluationPending },
                readDraft = ::conversationDictationDraftSnapshot,
                writeDraft = ::setConversationDictationDraftIfCurrent,
                completionWrite = ::writeCompletedDictationDraft,
                // Chat rows are a refreshable projection, not proof that the immutable origin was removed.
                targetAvailable = { accountRef, _ ->
                    accounts.any { it.label == accountRef && it.signedOut != true }
                },
                targetReplyAvailable = { account, group, reply ->
                    conversationDictationReplyTargetResolution(account, group, reply).available
                },
                targetValidator = { account, group ->
                    val cached =
                        synchronized(conversationControllerLock) {
                            newestMatchingController(conversationControllers) { it.matchesConversation(account, group) }
                        }
                    when {
                        accounts.none { it.label == account && it.signedOut != true } ->
                            TargetValidation.DefinitelyRemoved
                        cached?.membersVerified == true && cached.isSelfMember -> TargetValidation.Available
                        cached?.membersVerified == true -> TargetValidation.DefinitelyRemoved
                        else ->
                            runCatchingCancellable {
                                marmotIo {
                                    val member =
                                        groupDetails(account, group).group.selfMembership ==
                                            SelfMembershipFfi.MEMBER
                                    if (member) TargetValidation.Available else TargetValidation.DefinitelyRemoved
                                }
                            }.getOrDefault(TargetValidation.Indeterminate)
                    }
                },
                targetValidationScope = mutationsScope,
                onBeforeRecognition = conversationDictationMediaHandoff::beforeRecognition,
                onAfterAudioCapture = conversationDictationMediaHandoff::afterAudioCapture,
                tryAcquireMicrophone = { microphoneCaptureCoordinator.tryAcquire(dictationMicrophoneOwner) },
                releaseMicrophone = { microphoneCaptureCoordinator.release(dictationMicrophoneOwner) },
                finishAfterSilenceMillis = {
                    conversationDictationPreferences.current().finishAfterSilenceMillis
                },
                pauseOtherAudio = { conversationDictationPreferences.current().pauseOtherAudio },
                silenceDeliveryMode = {
                    conversationDictationPreferences.current().silenceDeliveryMode
                },
                sendTranscriptIfOriginUnchanged = ::sendDictationTranscriptIfOriginUnchanged,
                onSendBlocked = { presentTransient(R.string.dictation_send_blocked) },
            ).also { initializedDictation = it }
        }

    internal val legacyDraftMigrationSource by lazy { LegacyDraftMigrationSource(appContext) }
    internal val editorSourceStore: EditorSourceStore = EditorSourceStore.create(appContext)

    /** Which draft each picked file belongs to, per account and conversation, so its outgoing worker never changes. */
    internal val outgoingDrafts =
        dev.ipf.whitenoise.android.media
            .OutgoingDraftRegistry()
    internal val editorSessionStore: EditorSessionStore = EditorSessionStore.create(appContext)
    internal val messageDraftRepository: MessageDraftRepository =
        messageDraftRepositoryOverride
            ?: MessageDraftRepository(
                gateway = MarmotMessageDraftGateway(::marmot),
                editorSessions = editorSessionStore,
            )
    internal val draftAttachmentRemovalTombstones = DraftAttachmentRemovalTombstones()
    internal val chatMuteRepository = ChatMuteRepository(MarmotChatMuteGateway(::marmot), marmotIoDispatcher)

    // Which of the two sequential signer round-trips the Amber sign-in is
    // waiting on (1 = identity request, 2 = identity proof), or null when
    // idle. The prompts are protocol-sequential — the proof can't be built
    // before the signer reveals which key signs it — so the fix for the
    // "sign-in hangs" perception is telling the user where they are.
    var amberSignInStage by mutableStateOf<Int?>(null)
        private set

    /**
     * App-lifetime cache of decrypted attachment bytes, keyed by the globally
     * unique `messageIdHex`. Lives here (not on the per-conversation
     * controller) so re-opening a chat doesn't re-download media already
     * fetched this session. Bounded in bytes; see [dev.ipf.whitenoise.android.media.ByteSizeLruCache].
     * staleness-exempt: this observable cache-mutation version is consumed by attachment UI.
     */
    private val mediaCacheRevisionState = MutableStateFlow(0L)
    internal val mediaCacheRevision: StateFlow<Long> = mediaCacheRevisionState.asStateFlow()

    /** Decoded-thumbnail membership changes independently of plaintext availability. */
    private val mediaThumbnailRevisionState = MutableStateFlow(0L)
    internal val mediaThumbnailRevision: StateFlow<Long> = mediaThumbnailRevisionState.asStateFlow()

    /** Publishes one observable revision for an L1 or encrypted-L2 cache mutation. */
    private fun bumpMediaCacheRevision() {
        mediaCacheRevisionState.update { it + 1L }
    }

    private val mediaPlaintextCache =
        dev.ipf.whitenoise.android.media.ByteSizeLruCache<String, ByteArray>(
            maxBytes = MEDIA_PLAINTEXT_CACHE_MAX_BYTES,
            maxEntryBytes = MEDIA_PLAINTEXT_CACHE_MAX_ENTRY_BYTES,
            sizeOf = { it.size },
        )

    /** UI leases prevent trimming a kept card's only decoded pixels; explicit removal still wins. */
    private val mediaThumbnailHolds = mutableMapOf<String, MutableSet<Any>>()

    /**
     * App-lifetime cache of *decoded* attachment thumbnails (sampled bitmaps),
     * keyed identically to [mediaPlaintextCache]. Lets a bubble render its
     * image on the first frame — no decode spinner — for anything already
     * fetched/sent this session. Bounded by total and per-entry bytes.
     *
     * Evicted bitmaps are NOT recycle()'d on purpose: the cached instance is the
     * same Bitmap the UI draws via `asImageBitmap()` (no copy), so recycling on
     * evict/clear/replace would crash a still-composed bubble with "trying to
     * use a recycled bitmap". On the minSdk bitmaps live on the native heap and
     * are GC-reclaimed once unreferenced, so the caps bound retention and GC
     * frees the rest — no recycle() needed, and adding one back is unsafe.
     */
    private val mediaThumbnailCache =
        dev.ipf.whitenoise.android.media.ByteSizeLruCache<String, dev.ipf.whitenoise.android.media.MediaThumbnail>(
            maxBytes = mediaThumbnailCacheMaxBytes(context),
            maxEntryBytes = mediaThumbnailCacheMaxBytes(context),
            sizeOf = { it.bitmap.allocationByteCount },
        )

    internal fun cachedMediaPlaintext(cacheKey: String): ByteArray? {
        assertMainThread { "cachedMediaPlaintext" }
        return mediaPlaintextCache.get(cacheKey)
    }

    internal fun cacheMediaPlaintext(
        cacheKey: String,
        plaintext: ByteArray,
    ): Boolean {
        assertMainThread { "cacheMediaPlaintext" }
        val previous = mediaPlaintextCache.put(cacheKey, plaintext)
        val retained = mediaPlaintextCache.get(cacheKey) === plaintext
        // Rejected oversized entries can still remove a previous value. Only
        // publish when cache membership actually changed; a no-op rejection
        // must not restart every visible attachment's availability probe.
        if (retained || previous != null) bumpMediaCacheRevision()
        return retained
    }

    internal val outgoingAttachmentCachePublications by lazy { OutgoingAttachmentCachePublications(mutationsScope) }

    /** Registers encrypted disk publication before a confirmed own-file card can observe an oversized L1 miss. */
    internal fun cacheUploadedAttachment(
        cacheKey: String,
        plaintext: ByteArray,
        ciphertextTag: String?,
    ) {
        cacheMediaPlaintext(cacheKey, plaintext)
        // Disk expiry is hash-scoped; an untagged durable entry could outlive its retention window.
        if (ciphertextTag == null) return
        val token = diskMediaCache.capturePublicationToken()
        outgoingAttachmentCachePublications.publish(cacheKey, listOf(token, ciphertextTag)) {
            withContext(Dispatchers.IO) { diskMediaCache.put(cacheKey, plaintext, token, ciphertextTag) }
        }
    }

    /** Returns a placeholder or legacy thumbnail without asserting that it satisfies a larger image request. */
    internal fun cachedMediaThumbnail(cacheKey: String): android.graphics.Bitmap? {
        assertMainThread { "cachedMediaThumbnail" }
        return mediaThumbnailCache.get(cacheKey)?.bitmap
    }

    /** Retains decode provenance so a thumbnail cannot silently satisfy a larger presentation request. */
    internal fun cachedMediaThumbnailEntry(
        cacheKey: String,
        requestedMaxEdgePx: Int,
    ): dev.ipf.whitenoise.android.media.MediaThumbnail? {
        assertMainThread { "cachedMediaThumbnailEntry" }
        return mediaThumbnailCache.get(cacheKey)?.takeIf { it.satisfies(requestedMaxEdgePx) }
    }

    /** Keeps at most one resolution per attachment, without allowing a late smaller decode to replace a larger one. */
    internal fun cacheMediaThumbnail(
        cacheKey: String,
        thumbnail: android.graphics.Bitmap,
        maxEdgePx: Int = dev.ipf.whitenoise.android.media.MediaPipeline.THUMBNAIL_MAX_EDGE_PX,
    ) {
        assertMainThread { "cacheMediaThumbnail" }
        val cached = mediaThumbnailCache.get(cacheKey)
        if (cached != null && cached.maxEdgePx > maxEdgePx) return
        val entry = MediaThumbnail(thumbnail, maxEdgePx)
        val previous = mediaThumbnailCache.put(cacheKey, entry)
        if (mediaThumbnailCache.get(cacheKey) === entry || previous != null) {
            mediaThumbnailRevisionState.update { it + 1L }
        }
    }

    /**
     * Releases expendable L1 references on the main thread. Displayed bitmaps remain valid and existing
     * transfer/upload owners, retained retry bytes, account fences and durable L2/native media survive.
     */
    internal fun trimPresentationMemory(trim: dev.ipf.whitenoise.android.media.PresentationMemoryTrim) {
        assertMainThread { "trimPresentationMemory" }
        val plaintextRemoved = mediaPlaintextCache.trimToSize(trim.targetBytes(MEDIA_PLAINTEXT_CACHE_MAX_BYTES))
        val thumbnailsRemoved =
            mediaThumbnailCache.trimToSize(
                targetBytes = mediaThumbnailTrimTargetBytes(trim),
                keep = mediaThumbnailHolds::containsKey,
            )
        if (plaintextRemoved > 0) bumpMediaCacheRevision()
        if (thumbnailsRemoved > 0) mediaThumbnailRevisionState.update { it + 1L }
        publicJpegPreviewLoader.trimMemory(trim)
        accountStickers.values.forEach { it.assets.trimMemory(trim) }
    }

    private val accountStickers = HashMap<String?, AccountStickers>()

    /**
     * The sticker packs and images of [account], shared by its conversations. Main-confined; kept until the account
     * is removed, so a conversation of another account never holds released stickers. Memory trims shrink them.
     */
    internal fun stickersFor(account: String?) = accountStickers.getOrPut(account) { AccountStickers(account, this) }

    /** Drops [account]'s sticker state and cancels its downloads after the account is removed. */
    private fun forgetStickers(account: String) {
        accountStickers.remove(account)?.close()
    }

    /** Holds an actively displayed kept thumbnail through cache trims, never through explicit invalidation. */
    internal fun retainMediaThumbnail(cacheKey: String): AutoCloseable {
        assertMainThread { "retainMediaThumbnail" }
        val token = Any()
        mediaThumbnailHolds.getOrPut(cacheKey) { mutableSetOf() }.add(token)
        return AutoCloseable {
            assertMainThread { "releaseMediaThumbnail" }
            val holds = mediaThumbnailHolds[cacheKey]
            if (holds?.remove(token) == true && holds.isEmpty()) mediaThumbnailHolds.remove(cacheKey)
        }
    }

    internal fun removeMediaMemoryCacheEntry(cacheKey: String) {
        assertMainThread { "removeMediaMemoryCacheEntry" }
        displayThumbnails.retirePending(cacheKey)
        mediaPlaintextCache.remove(cacheKey)
        if (mediaThumbnailCache.remove(cacheKey) != null) mediaThumbnailRevisionState.update { it + 1L }
        bumpMediaCacheRevision()
    }

    /** Every key currently resident in either in-memory tier, for group-scoped eviction. */
    internal fun mediaMemoryCacheKeysSnapshot(): Set<String> {
        assertMainThread { "mediaMemoryCacheKeysSnapshot" }
        return (mediaPlaintextCache.keysSnapshot() + mediaThumbnailCache.keysSnapshot()).toSet()
    }

    /** Encrypted retained originals, separate from the bounded display derivatives. */
    internal val diskMediaCache = createOriginalMediaCache(appContext, ::bumpMediaCacheRevision)
    internal val displayThumbnails = createDisplayThumbnailCache(appContext)

    /** Serializes presentation-owned encoders whose queued bitmap references are weak and cancellable. */
    internal val displayThumbnailWritePermit = kotlinx.coroutines.sync.Semaphore(1)
    internal val displayThumbnailReconciliationMutex = kotlinx.coroutines.sync.Mutex()
    internal var displayThumbnailReconciliationJob: Job? = null
    internal var displayThumbnailReconciliationRequested = false

    @Volatile
    private var marmotRuntime: AppMarmotRuntime? = initialMarmotRuntime
    private val nativeAttachmentPermissions = NativeAttachmentPermissions()

    /** Questions about overriding MDK's advisory refusals of a group change, shown at app level (#3289). */
    internal val groupChangeConfirmations = GroupChangeConfirmations()

    /** Whether a network callback asked for a permission refresh that has not started applying yet. */
    private val networkPermissionRefreshRequested =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Whether the coalesced network refresh is running, from its start until its apply has finished. */
    private val networkPermissionRefreshRunning =
        java.util.concurrent.atomic
            .AtomicBoolean(false)

    /** Makes a refresh's invalidate and in-flight mark, and a settle timeout's check before it, one step. */
    private val permissionRefreshLock = Any()

    /** Bounds concurrent receipt history lookups across the notification and runtime-event receipt sources. */
    internal val receiptLookupGate = Semaphore(RECEIPT_LOOKUP_CONCURRENCY)

    /** Recently looked-up receipts, so a message reported by both receipt sources is looked up once. */
    internal val receiptClaims = ReceiptClaims(RECEIPT_CLAIM_CAPACITY)

    /** Guards [startupReceiptSubscription], which only the startup listener and its first receipt loop touch. */
    internal val startupReceiptLock = Any()

    /** The receipt event subscription opened before the runtime started, with its runtime, until a loop takes it. */
    internal var startupReceiptSubscription: Pair<MarmotInterface, EventsSubscription>? = null

    /**
     * Refreshes runtime-scoped permissions after network, preference, or account changes. When [widenWaiting], it
     * also widens any automatic downloads still waiting on a network narrower than the refreshed policy now allows.
     * Network callbacks pass false: the widening target comes from the matrix and account state, which a network
     * edge cannot change, and those callbacks arrive often.
     */
    internal fun refreshNativeAttachmentPermissions(widenWaiting: Boolean = true): Job? =
        refreshNativeAttachmentPermissions(widenWaiting, networkCallback = false)

    private fun refreshNativeAttachmentPermissions(
        widenWaiting: Boolean,
        networkCallback: Boolean,
    ): Job? {
        val accountRefs = accounts.filterNot { it.signedOut }.map { it.label }
        AutomaticAttachmentNetworkWaits.policyNetworkType = ::liveAutomaticNetworkType
        if (widenWaiting) widenWaitingAutomaticDownloads(accountRefs)
        val runtime: AppMarmotRuntime?
        val revision: Long
        synchronized(permissionRefreshLock) {
            runtime = marmotRuntime
            revision =
                if (networkCallback && runtime != null) {
                    nativeAttachmentPermissions.beginNetworkRefresh(
                        attachmentPermissionNetworkSnapshot(),
                        accountRefs.toSet(),
                        runtime,
                    ) ?: return null
                } else {
                    nativeAttachmentPermissions.invalidate(runtime).also {
                        if (runtime != null) nativeAttachmentPermissions.beginRefresh(it)
                    }
                }
        }
        return runtime?.let { activeRuntime ->
            val engine = activeRuntime.marmot
            // Marmot binding calls remain on the mutations scope's Main.immediate dispatcher.
            mutationsScope.launch {
                try {
                    applyNativeAttachmentPermissions(revision, activeRuntime, engine, accountRefs, networkCallback)
                } finally {
                    nativeAttachmentPermissions.endRefresh(revision)
                }
            }
        }
    }

    /** Detached platform facts used for both network-only evaluation and its equivalence key. */
    private fun attachmentPermissionNetworkSnapshot(): AttachmentPermissionNetworkSnapshot =
        AttachmentPermissionNetworkSnapshot(
            defaultNetworkHandle = activeDefaultNetwork.identitySnapshot(),
            validatedNetworkHandles = validatedInternetNetworks.identitySnapshot(),
            hasActiveNetwork = hasActiveNetworkSnapshot,
            hasValidatedInternet = hasValidatedInternet(),
            networkTypes = activeNetworkTypes().toSet(),
        )

    /**
     * Coalesces refreshes requested by network callbacks, which can arrive in bursts. One refresh runs at a time and
     * stays running until its apply has finished; callbacks that arrive meanwhile fold into a single follow-up that
     * reads the network when it starts, so the latest state applies without invalidating the generation still
     * applying. Such refreshes never widen waiting work, since a network edge cannot change its target.
     */
    private fun requestNetworkPermissionRefresh() {
        networkPermissionRefreshRequested.set(true)
        if (!networkPermissionRefreshRunning.compareAndSet(false, true)) return
        mutationsScope.launch {
            try {
                while (networkPermissionRefreshRequested.getAndSet(false)) {
                    refreshNativeAttachmentPermissions(widenWaiting = false, networkCallback = true)?.join()
                }
            } finally {
                networkPermissionRefreshRunning.set(false)
                // A callback that landed after the last check but before the flag cleared still gets its refresh.
                if (networkPermissionRefreshRequested.get()) requestNetworkPermissionRefresh()
            }
        }
    }

    /**
     * Starts a refresh for a settle timeout unless one under the latest revision is already applying. The check and
     * the refresh's invalidate share [permissionRefreshLock], so album workers that time out together start at
     * most one refresh instead of each superseding the generation another just began.
     */
    private fun refreshAfterSettleTimeout() {
        synchronized(permissionRefreshLock) {
            if (!nativeAttachmentPermissions.refreshInFlight(marmotRuntime)) {
                refreshNativeAttachmentPermissions(widenWaiting = false)
            }
        }
    }

    /** Applies [revision]'s permissions for [accountRefs], retrying failed accounts while it stays current. */
    private suspend fun applyNativeAttachmentPermissions(
        revision: Long,
        runtime: AppMarmotRuntime,
        engine: MarmotInterface,
        accountRefs: List<String>,
        networkCallback: Boolean = false,
    ) {
        var pending = accountRefs.toSet()
        var attempt = 0
        while (pending.isNotEmpty() && nativeAttachmentPermissions.isCurrent(revision, runtime)) {
            pending =
                nativeAttachmentPermissions.update(revision, runtime, engine, pending.toList()) { account ->
                    // The editor commits off-main. Revoke first, then wait for queued
                    // settings saves so this generation never restores an obsolete matrix.
                    hostPreferenceCommitMutex.withLock {
                        val evaluatedNetwork = if (networkCallback) attachmentPermissionNetworkSnapshot() else null
                        if (evaluatedNetwork != null) {
                            nativeAttachmentPermissions.noteNetworkEvaluation(revision, runtime, evaluatedNetwork)
                        }
                        loadMediaAutoDownloadMatrix(account).nativePermission(
                            evaluatedNetwork?.networkTypes ?: activeNetworkTypes(),
                            evaluatedNetwork?.hasValidatedInternet ?: hasValidatedInternet(),
                            attachmentDownloadIntents.isAutomaticPaused(account),
                        )
                    }
                }
            if (!nativeAttachmentPermissions.isCurrent(revision, runtime)) return
            if (pending.isEmpty()) {
                attachmentDownloadPolicyRevision += 1
                return
            }
            attempt += 1
            if (attempt >= NATIVE_ATTACHMENT_PERMISSION_RETRY_LIMIT) break
            delay(NATIVE_ATTACHMENT_PERMISSION_RETRY_DELAY_MILLIS * attempt)
        }
        if (pending.isNotEmpty() && nativeAttachmentPermissions.isCurrent(revision, runtime)) {
            Log.w("AttachmentPermissions", "permission_update_failed accounts=${pending.size}")
        }
    }

    /**
     * The network [accountRef]'s live policy allows [type] on, for settling a receipt against the current policy.
     * Null for an account that is not loaded and signed in, so an unknown policy never widens a waiting spec.
     */
    private fun liveAutomaticNetworkType(
        accountRef: String,
        type: MediaAutoDownloadType,
    ): NetworkType? {
        if (accounts.none { it.label == accountRef && !it.signedOut }) return null
        return runCatching { automaticAttachmentPolicy(accountRef).networkType(type) }.getOrNull()
    }

    /**
     * Widens waiting automatic downloads of the signed-in [accountRefs] to the network each one's type is allowed
     * on now (#3308), so a policy that starts allowing a wider network, such as metered downloads turned on, reaches
     * work queued under the old one without a new receipt or a reconnect. Only work recorded as narrowed is read;
     * the policy is computed after queued settings saves, and the WorkManager reads run off the main thread.
     */
    private fun widenWaitingAutomaticDownloads(accountRefs: List<String>) {
        if (accountRefs.isEmpty()) return
        val waits = AutomaticAttachmentNetworkWaits.get(appContext)
        mutationsScope.launch {
            val policies =
                hostPreferenceCommitMutex.withLock { accountRefs.associateWith(::automaticAttachmentPolicy) }
            withContext(Dispatchers.IO) {
                // One read of the sealed records serves every account of the sweep.
                runCatching {
                    waits.widenAll(WorkManager.getInstance(appContext)) { account, type ->
                        policies[account]?.networkType(type)
                    }
                }.onSuccess { count ->
                    if (count > 0) Log.i(INCOMING_ATTACHMENT_TAG, "attachment_network_widened count=$count")
                }.onFailure { Log.w(INCOMING_ATTACHMENT_TAG, "attachment_network_widening_failed") }
            }
        }
    }

    /** Publishes a runtime, invalidates obsolete permission work, and seeds synchronization for retained accounts. */
    private fun publishMarmotRuntime(runtime: AppMarmotRuntime) {
        if (marmotRuntime !== runtime) AvatarImageLoader.clearStoredAvatars()
        marmotRuntime = runtime
        StartupDiagnostics.bindNative(runtime.marmot)
        hostPerformance.publishEmitter(runtime, runtimeGeneration, runtimeHostPerformanceEmitter(runtime))
        nativeAttachmentPermissions.invalidate(runtime)
        mutationsScope.launch {
            if (marmotRuntime === runtime) refreshNativeAttachmentPermissions()
        }
    }

    /** Clears only the runtime that failed and immediately fences its permission callbacks. */
    private fun clearMarmotRuntime(runtime: AppMarmotRuntime) {
        if (marmotRuntime !== runtime) return
        StartupDiagnostics.unbindNative(runtime.marmot)
        AvatarImageLoader.clearStoredAvatars()
        hostPerformance.clearEmitter(runtime)
        marmotRuntime = null
        nativeAttachmentPermissions.invalidate(null)
    }

    private val bootstrapRuntime = BootstrapRuntimeCoordinator<AppMarmotRuntime>()
    private val startupPerformance = StartupPerformanceDiagnostics()
    private var startupSystemSplashHandoffRecorded = false
    private var startupLocalRowsRecorded = false
    private var startupMemberDerivedLocalRecorded = false
    private var startupFirstLocalFrameRecorded = false
    private var startupRelayCatchUpRecorded = false
    internal val accountListLifetime = StalenessGuard()
    private var pendingStartupUnreadRefresh: StartupUnreadRefresh? = null

    @Volatile
    private var bootstrapCompleted = false
    internal val ttsRefreshMutex = Mutex()
    private val auditLogSettingsMutex = Mutex()
    private val auditUploadConsent = AuditUploadConsent(preferences)
    val auditUploadConsentRequired: Boolean get() = auditUploadConsent.requiresChoice
    internal val conversationVibrationChannelMutex = Mutex()

    // Treat preference I/O plus observable-state publication as one transaction;
    // otherwise an older successful hide can publish after a newer hide or wipe.
    internal val hiddenMessageMutationMutex = Mutex()
    internal val conversationVibrationPreferences = ConversationVibrationPreferences(appContext)
    internal val conversationNotificationRouting by lazy { ConversationNotificationRouting(appContext) }
    internal val notificationPreviewSettings by lazy { NotificationPreviewSettings.forContext(appContext) }
    internal val localNotificationPresenter =
        LocalNotificationPresenter(
            appContext,
            contactAvatarOverride = ::contactAvatarOverrideForScope,
        )
    internal val notificationContentInvalidations =
        NotificationContentInvalidations(NOTIFICATION_CONTENT_INVALIDATION_CAPACITY)
    internal val notificationReconcileLocks = Array(64) { Mutex() }
    internal val inviteNotificationIdentityRefreshStore = GroupInviteNotificationIdentityRefreshStore()
    private val appUpdateRepository =
        AppUpdateRepository(
            appContext,
            selfUpdateEnabled = startPlatformServices && BuildConfig.SELF_UPDATE_ENABLED,
        )
    private val appUpdateNotifier = AppUpdateNotifier(appContext)
    private val appSelfUpdateFlow = AppSelfUpdateFlows.create(appContext)
    internal val chatMutePreferences = ChatMutePreferences(appContext)
    internal val chatMuteOwner = ChatMuteOwner()
    internal val chatFolderPreferences = ChatFolderPreferences(appContext)
    internal val ttsWarningPreferences = TtsWarningPreferences(appContext)
    internal val ttsPlaybackTrust = TtsPlaybackTrustConsent(this) { attachedTtsHandle }

    /** Reports whether the connected engine still requires playback consent. */
    internal fun ttsEngineNeedsAcknowledgment(): Boolean = ttsPlaybackTrust.needsAcknowledgment()

    /** Checks engine consent and the playback owner before any text can be sent. */
    internal suspend fun confirmTtsPlaybackTrust(current: () -> Boolean): Boolean = ttsPlaybackTrust.confirm(current)

    internal val ttsEnginePreferences = TtsEnginePreferences(appContext)
    internal val ttsVoicePreferences = TtsVoicePreferences(appContext)
    internal val ttsEngineResolver =
        TtsEngineResolver(appContext, selectedVoice = ttsVoicePreferences::selectedVoice)
    internal val ttsRatePreferences = TtsRatePreferences(appContext)
    internal val ttsMediaMixPreferences = TtsMediaMixPreferences(appContext)

    // Process-wide read-aloud playback: survives navigation between chats and
    // back to the chat list, matching VoicePlaybackController's lifetime.
    val ttsController = createAppTtsController(appContext, ttsRatePreferences, ttsMediaMixPreferences)
    private val conversationDictationMediaHandoff by lazy {
        createConversationDictationMediaHandoff(ttsController, appContext) {
            conversationDictation.interruptForAudioFocusLoss()
        }
    }
    var ttsResolution by mutableStateOf<TtsResolutionResult?>(null)
        private set
    var ttsVoiceResolution by mutableStateOf(TtsVoiceResolution.Empty)
        private set

    // The (account, conversation) pair that owns the current auto-read
    // session, or null when speech is manual or idle. Live continuation
    // appends only for the owner: a manual Speak aloud replaces the queue and
    // ends the session; another chat's — or another ACCOUNT'S view of the
    // same group — must never extend it.
    internal var ttsAutoReadSessionKey by mutableStateOf<String?>(null)
    private val ttsAutoReadContinuation by lazy { createTtsAutoReadContinuation(this) }

    // Manual speech deliberately has no auto-read session key, but it still
    // owns decrypted text that must stop when its account is removed.
    internal var ttsSpeechAccountRef: String? = null
    internal val attachmentSpeechDestination =
        MutableStateFlow<dev.ipf.whitenoise.android.audio.AttachmentSpeechDestination?>(null)

    /** Reader controls may only address speech owned by the current local account. */
    internal fun ownsCurrentAccountSpeech(): Boolean = ttsSpeechAccountRef?.let { it == activeAccountRef } == true

    fun ownsTtsAutoReadSession(groupIdHex: String): Boolean {
        val key = ttsAutoReadSessionKey ?: return false
        return key == ttsAutoReadKey(activeAccountRef, groupIdHex)
    }

    /** Starts read-aloud for one or more speakable messages after any required approval. */
    fun speakAloud(
        entries: List<TtsSpeakableEntry>,
        locale: java.util.Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        val started =
            synchronized(ttsController) {
                val previousGeneration = ttsController.state.value.messageProgressGeneration
                val previousCallbackGeneration = ttsController.playbackCallbackGeneration()
                val accepted =
                    !ttsEngineNeedsAcknowledgment() && ttsController.speak(entries, locale, startSentenceIndex)
                // A pre-playback refusal (offline-language) tears down the queue without a new message generation.
                if (accepted ||
                    ttsController.state.value.messageProgressGeneration != previousGeneration ||
                    ttsController.playbackCallbackGeneration() != previousCallbackGeneration
                ) {
                    clearTtsPlaybackOwnership()
                }
                if (accepted) ttsSpeechAccountRef = activeAccountRef
                accepted
            }
        if (!started) return false

        // Stop private speech if its foreground service fails to provide a visible stop control.
        val foregroundStarted = TtsPlaybackForegroundService.start(appContext)
        if (!foregroundStarted) stopSpeaking()
        return foregroundStarted
    }

    /** [speakAloud] for an auto-read backlog, marking the owning conversation. */
    fun speakAloudAutoRead(
        groupIdHex: String,
        entries: List<TtsSpeakableEntry>,
        locale: java.util.Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        val owner = ttsAutoReadKey(activeAccountRef, groupIdHex) ?: return false
        val started = speakAloud(entries, locale, startSentenceIndex)
        if (started) {
            ttsAutoReadSessionKey = owner
            ttsHistorySession.onConversationSessionStarted(activeAccountRef, groupIdHex)
            activeAccountRef?.let { ttsAutoReadContinuation.start(it, groupIdHex, locale) }
        }
        return started
    }

    /**
     * Prepares reader text off the controller lock, preserving caller/account ownership through playback commit.
     * Account-switch generation prevents an A-B-A return from reviving old work. Cleanup is session-scoped so
     * a revoked or cancelled request cannot clear a replacement queue's ownership.
     * Accepting a new queue also revokes prior history/attachment source ownership.
     */
    suspend fun speakAloudPrepared(
        entries: List<TtsSpeakableEntry>,
        locale: Locale,
        startSentenceIndex: Int = 0,
        startRenderedHit: dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit? = null,
        isCurrent: () -> Boolean = { true },
    ): Boolean {
        if (ttsEngineNeedsAcknowledgment()) return false
        val ownerAccount = activeAccountRef
        val ownerGeneration = accountSwitchHandoff.capture()
        var preparingSessionId: Long? = null
        try {
            val started =
                ttsController.speakAsync(
                    entries,
                    locale,
                    startSentenceIndex,
                    startRenderedHit,
                    isCurrent = {
                        isCurrent() &&
                            !ttsEngineNeedsAcknowledgment() &&
                            activeAccountRef == ownerAccount &&
                            accountSwitchHandoff.isCurrent(ownerGeneration)
                    },
                ) {
                    preparingSessionId = ttsController.state.value.sessionId
                    ttsSpeechAccountRef = ownerAccount
                    TtsPlaybackForegroundService.start(appContext)
                }
            if (started) {
                ttsSpeechAccountRef = ownerAccount
                ttsAutoReadSessionKey = null
                ttsHistorySession.onSessionCleared()
            }
            return started
        } finally {
            // Check and clear under the same monitor as queue starts, not against a stale Idle snapshot.
            synchronized(ttsController) {
                val state = ttsController.state.value
                if (state is TtsState.Idle &&
                    state.sessionId == preparingSessionId &&
                    ttsSpeechAccountRef == ownerAccount
                ) {
                    clearTtsPlaybackOwnership()
                }
            }
        }
    }

    suspend fun speakAloudAutoRead(
        groupIdHex: String,
        entries: List<TtsSpeakableEntry>,
        locale: java.util.Locale,
        startSentenceIndex: Int = 0,
        startRenderedHit: dev.ipf.whitenoise.android.audio.tts.speech.PreparedRenderedHit? = null,
        backgroundPreparation: Boolean,
        canCommit: () -> Boolean = { true },
    ): Boolean {
        if (!canCommit()) return false
        val ownerAccount = activeAccountRef
        return if (!backgroundPreparation) {
            speakAloudAutoRead(groupIdHex, entries, locale, startSentenceIndex)
        } else {
            ttsAutoReadKey(ownerAccount, groupIdHex)?.let { owner ->
                speakAloudPrepared(
                    entries,
                    locale,
                    startSentenceIndex,
                    startRenderedHit,
                    isCurrent = canCommit,
                ).also { started ->
                    if (started) {
                        ttsAutoReadSessionKey = owner
                        ttsHistorySession.onConversationSessionStarted(ownerAccount, groupIdHex)
                        ownerAccount?.let { ttsAutoReadContinuation.start(it, groupIdHex, locale) }
                    }
                }
            } ?: false
        }
    }

    /**
     * History paging for the read-aloud transport. The backing conversation
     * controller is resolved per edge request, so a controller recreated by
     * navigation keeps serving the same playback session.
     */
    val ttsHistorySession: TtsHistorySession by lazy {
        TtsHistorySession(
            controller = ttsController,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + scopeExceptionHandler),
            resolvePager = ::ttsHistoryPagerFor,
        )
    }

    private fun ttsHistoryPagerFor(
        accountRef: String?,
        groupIdHex: String,
    ): TtsHistoryPager? {
        val controller =
            synchronized(conversationControllerLock) {
                newestMatchingController(conversationControllers) { it.matchesConversation(accountRef, groupIdHex) }
            } ?: return null
        return object : TtsHistoryPager {
            override val hasMoreBefore: Boolean get() = controller.hasMoreBefore
            override val hasMoreAfter: Boolean get() = controller.hasMoreAfterTimeline

            override fun timelineRecords(): List<AppMessageRecordFfi> = canonicalTimelineRecords(controller.timeline)

            override suspend fun loadOlder(): Boolean = controller.loadOlderTimelinePage()

            override suspend fun loadNewer(): Boolean = controller.loadNewerTimelinePage()

            override suspend fun ensureLoaded(
                id: String,
                timelineAt: ULong,
            ): Boolean = controller.loadTimelineMessageAvailable(id, timelineAt)

            override suspend fun projectSpeakable(record: AppMessageRecordFfi): TtsSpeakableEntry? =
                projectTtsSpeakableEntry(
                    message = record,
                    editedText = controller.editsByTarget[record.messageIdHex]?.latestText,
                    senderDisplayName = displayName(record.sender),
                    conversationMemberCount = controller.memberCount.takeIf { controller.membersLoaded },
                    parseMarkdown = { parseMarkdownOrEmpty(it) },
                    mentionDisplayName = ::mentionSpeechName,
                )
        }
    }

    internal val ttsAutoReadPreferences = TtsAutoReadPreferences(appContext)

    fun isConversationAutoRead(groupIdHex: String): Boolean {
        val accountRef = activeAccountRef ?: return false
        return conversationAutoReadFor(accountRef, groupIdHex)
    }

    internal fun conversationAutoReadFor(
        accountRef: String,
        groupIdHex: String,
    ): Boolean = ttsAutoReadPreferences.isConversationAutoRead(accountRef, groupIdHex)

    fun setTtsAutoReadGlobalDefault(enabled: Boolean) {
        ttsAutoReadPreferences.setGlobalDefaultEnabled(enabled)
        reconcileOwnedTtsAutoReadSession()
    }

    fun setConversationAutoReadOverride(
        groupIdHex: String,
        override: TtsAutoReadOverride?,
    ) {
        val accountRef = activeAccountRef ?: return
        if (override == null) {
            ttsAutoReadPreferences.clearConversationOverride(accountRef, groupIdHex)
        } else {
            ttsAutoReadPreferences.setConversationOverride(accountRef, groupIdHex, override)
        }
        reconcileOwnedTtsAutoReadSession()
    }

    private fun reconcileOwnedTtsAutoReadSession() {
        val sessionKey = ttsAutoReadSessionKey ?: return
        val separator = sessionKey.indexOf('|')
        if (separator <= 0 || separator == sessionKey.lastIndex) return
        val accountRef = sessionKey.substring(0, separator)
        val groupIdHex = sessionKey.substring(separator + 1)
        if (!conversationAutoReadFor(accountRef, groupIdHex)) {
            stopOwnedTtsAutoReadSession()
        }
    }

    /** Stops an owned conversation auto-read queue and revokes its account/history navigation ownership. */
    internal fun stopOwnedTtsAutoReadSession() {
        if (ttsAutoReadSessionKey == null) return
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
        attachmentSpeechDestination.value = null
    }

    /** Account removal ends any owned speech so its decrypted text stops being spoken. */
    internal fun stopTtsForRemovedAccount(accountRef: String) {
        val speechOwner = ttsSpeechAccountRef
        if (speechOwner != null && speechOwner != accountRef) return
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
        attachmentSpeechDestination.value = null
    }

    /** Live continuation for auto-read: extends an active read-aloud queue. */
    fun appendSpeech(
        entry: TtsSpeakableEntry,
        locale: java.util.Locale,
    ): Boolean {
        // A session paged away from the live tail must not have arrivals
        // spliced next to unrelated older history — they stay reachable
        // through next-message paging instead.
        if (ttsEngineNeedsAcknowledgment() || !ttsHistorySession.allowsLiveAppend()) return false
        return ttsController.appendSpeech(entry, locale)
    }

    /** Stops the active speech queue and immediately revokes every transient source-navigation credential. */
    fun stopSpeaking() {
        ttsPlaybackTrust.cancel()
        ttsController.stop()
        clearTtsPlaybackOwnership()
    }

    fun setTtsRateOverride(rate: Float?) {
        ttsRatePreferences.setRateOverride(rate)
        ttsController.onSpeechRateChanged()
    }

    internal var attachedTtsHandle: TtsEngineHandle? = null
        private set

    /** Publishes discovery state and atomically replaces the controller's engine adapter. */
    internal fun publishTtsResolution(resolution: TtsResolutionResult?) {
        ttsResolution = resolution
        val handle = resolution?.handle
        // Reattaching the same healthy handle would stop speech and reset its current voice.
        if (handle === attachedTtsHandle) return
        ttsPlaybackTrust.onHandleReplaced()
        clearTtsPlaybackOwnership()
        ttsVoiceResolution = handle?.voiceResolution ?: TtsVoiceResolution.Empty
        attachedTtsHandle = handle
        if (handle != null) {
            ttsController.attachEngine(
                AndroidTtsSpeechEngine(
                    textToSpeech = handle.textToSpeech,
                    enginePackage = handle.enginePackage,
                    selectedVoice = { ttsVoicePreferences.selectedVoice(handle.enginePackage) },
                    onVoiceResolved = { voiceResolution -> ttsVoiceResolution = voiceResolution },
                    requireOfflineVoice = handle.voiceResolution.effectiveKey != null,
                    onConnectionUncertain = { retireFailedTtsConnection(handle) },
                ),
                engineKey = handle.enginePackage,
            )
        } else {
            ttsController.detachEngine()
        }
    }

    val ttsDiscoveryComplete: Boolean
        get() = ttsResolution != null
    val ttsHasUsableEngine: Boolean
        get() = ttsResolution?.hasUsableEngine == true

    /** Tests inject a scripted store; production seals push state in the Keystore-backed default. */
    private val pushTokenStore = pushTokenStoreOverride ?: PushTokenStore.create(appContext)

    private val amberSigner = AmberSignerController(appContext)

    /** Isolated sign-in tests replace only the signer round trip. */
    internal var amberPublicKeyRequest: () -> String = amberSigner::requestPublicKey

    var phase by mutableStateOf<AppPhase>(AppPhase.Bootstrapping)
        private set

    var retainedAccountReactivationRef by mutableStateOf<String?>(null)
        private set

    var accounts by mutableStateOf(initialAccounts)
        private set

    /**
     * Whether the current [accounts] came from a successful account read (or the process's initial accounts), so a
     * missing account can tell "removed" from "not read". Automatic work for an account an authoritative list lacks
     * ends; any other waits (#3308). A failed read clears it, since the list it leaves behind says nothing about
     * which accounts survive, so a surviving account's work is never ended by a refresh that did not complete.
     */
    @Volatile
    internal var accountListLoaded = initialAccounts.isNotEmpty()

    internal val accountUnreadStore = AccountUnreadStore()
    internal val runtimeMirrors = RuntimeMirrors()
    internal val freshGroupCreations = FreshGroupCreationRegistry()

    var activeAccountRef by mutableStateOf(initialActiveAccountRef ?: preferences.getString(ACTIVE_ACCOUNT_KEY, null))
        private set

    var developerMode by mutableStateOf(preferences.getBoolean(DEVELOPER_MODE_KEY, false))
        private set

    /**
     * Developer-only streaming-debug toggle. When ON, the conversation renders
     * agent-stream and MLS-signaling kinds as debug rows inline. Stored
     * independently, but only takes effect through
     * [streamingDebugEnabled], which also requires [developerMode] — turning
     * developer mode off suppresses the debug rows without clearing this value.
     */
    var streamingDebugMode by mutableStateOf(preferences.getBoolean(STREAMING_DEBUG_MODE_KEY, false))
        private set

    // Set while a sign-out / sign-out-and-wipe teardown is running so the UI can
    // show a blocking spinner. Lives here (not in the screen) because the wipe
    // runs on the mutation scope and pops the identity screen mid-teardown.
    var signOutInProgress by mutableStateOf(false)

    // Set while the destructive Sign Out & Wipe FFI call is in flight (#350).
    // Drives the non-cancellable staged progress sheet, which is hosted at the
    // app root (not the identity screen) because the wipe flips the active
    // account partway through and the resulting nav reset pops the screen that
    // started it.
    var wipeInProgress by mutableStateOf(false)

    // Structured outcome of a wipe that finished with issues (#350): drives
    // the "Wipe finished with N issues" sheet at the app root, over whatever
    // end state the wipe navigated to. Null when nothing is pending; the
    // sheet's dismiss clears it. Clean wipes never set this — they toast.
    var pendingWipeReport by mutableStateOf<WipeReport?>(null)

    /**
     * True only when both developer mode and the streaming-debug toggle are on.
     * The conversation timeline must read this (never [streamingDebugMode]
     * alone) so debug rows never leak when developer mode is off.
     */
    val streamingDebugEnabled: Boolean
        get() = developerMode && streamingDebugMode

    /**
     * Force the IME into incognito mode for every text field in the app (#405).
     * Default ON to match the app's privacy positioning: messages typed in an
     * E2EE chat must not leak back out through keyboard learning / cloud sync.
     * This is an Android platform preference (UI behavior), not Marmot protocol
     * data, so SharedPreferences is the correct home per AGENTS.md.
     */
    var forceIncognitoKeyboard by mutableStateOf(preferences.getBoolean(FORCE_INCOGNITO_KEYBOARD_KEY, true))
        private set

    /**
     * User override for chat-surface FLAG_SECURE (#800, #1091). Default ON
     * permits chat screenshots and real Recents thumbnails; turning the
     * "Hide app preview in Recents" setting ON clears this and keeps the flag
     * set only for message surfaces. Identity / secret-key surfaces stay secure
     * unconditionally at their call sites.
     */
    var allowChatScreenshotsInChats by mutableStateOf(ChatScreenshotPreferences.readAllowChatScreenshots(preferences))
        private set

    var onAllowChatScreenshotsChanged: ((Boolean) -> Unit)? = null

    var themeMode by mutableStateOf(AppThemeMode.fromPreference(preferences.getString(THEME_MODE_KEY, null)))
        private set

    /**
     * In-app font-size step (#403). Multiplies the theme typography's sp
     * sizes on top of the OS font scale. Android platform preference (UI
     * behavior), so SharedPreferences is the correct home per AGENTS.md.
     */
    var fontScale by mutableStateOf(AppFontScale.fromPreference(preferences.getString(FONT_SCALE_KEY, null)))
        private set

    private var durableFontScale = fontScale
    private var fontScaleSaveRevision = 0L

    var appFont by mutableStateOf(AppFont.fromPreference(preferences.getString(APP_FONT_KEY, null)))
        private set

    private val accountScopedCaches = ScopedCacheRegistry()
    internal val profilePresentationLock = Any()
    private val groupMemberSnapshotLock = Any()

    internal val globalBubbleColors =
        ScopedCache<AccountBubbleColorSlot, MutableState<Long?>>(
            registry = accountScopedCaches,
            name = "global-bubble-colors",
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
            observable = true,
        )
    internal val actionColors =
        ScopedCache<AccountActionColorSlot, MutableState<Long?>>(
            registry = accountScopedCaches,
            name = "action-colors",
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
            observable = true,
        )
    internal val chatBubbleColors =
        ScopedCache<String, MutableState<Long?>>(
            registry = accountScopedCaches,
            name = "chat-bubble-colors",
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
            observable = true,
        )

    /**
     * Per-account media auto-download matrix (issue #407). Reloaded whenever
     * the active account changes (see [reloadMediaAutoDownloadMatrix]); the
     * bubble call sites key their gate `remember` on this so flipping a toggle
     * re-gates undownloaded media immediately.
     */
    var mediaAutoDownloadMatrix by mutableStateOf(loadMediaAutoDownloadMatrix(activeAccountRef))
        private set

    var mediaQuality by mutableStateOf(
        MediaQuality.fromPreference(preferences.getString(MEDIA_QUALITY_KEY, null)),
    )
        private set

    var enterKeyBehavior by mutableStateOf(
        EnterKeyBehavior.fromPreference(preferences.getString(ENTER_KEY_BEHAVIOR_KEY, null)),
    )
        private set

    /** App-wide preference owner, retained across account changes and reset by erased app preferences. */
    internal val quickAccountSwitchingPreference = QuickAccountSwitchingPreference(preferences)

    internal val composerToolbarPreference by lazy {
        ComposerToolbarPreference(preferences, mutationsScope, hostPreferenceCommitMutex)
    }

    var languageTag by mutableStateOf(preferences.getString(APP_LANGUAGE_TAG_KEY, null).orEmpty())
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    /** Queued-change size refusals already shown, so a redelivered refusal is not shown twice. Main thread only. */
    internal val shownQueuedSizeRefusals = ShownQueuedRefusals()

    var transientNotice by mutableStateOf<TransientNotice?>(null)
        private set

    // staleness-exempt: monotonically unique notice identity, not an async publication fence.
    private var transientNoticeSequence = 0L

    /** Launch geometry belongs to this one profile presentation, never the next request. */
    internal var pendingProfileContour: dev.ipf.whitenoise.android.ui.settings.SettingsContour? = null
        private set
    internal var pendingProfileOrigin by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
        private set
    internal var pendingProfilePresentationId by mutableLongStateOf(0L)
        private set
    var pendingProfileNpub by mutableStateOf<String?>(null)
        private set

    /** Ephemeral metadata carried by a live user-directory result. */
    var pendingProfileMetadata by mutableStateOf<UserProfileMetadataFfi?>(null)
        private set

    /**
     * Whether the presented profile was opened from user search. Tracked
     * separately from [pendingProfileMetadata], which is also the fallback for
     * profile data generally and so cannot identify the entry point.
     */
    var pendingProfileFromDiscovery by mutableStateOf(false)
        private set

    // staleness-exempt: observable relationship version consumed as a Compose key.
    var relationshipRevision by mutableLongStateOf(0L)
        private set

    /** Presentation only: encrypted/native storage remains the authority for push cleanup admission. */
    var pushStorageRecovery by mutableStateOf(PushStorageRecovery.Checking)
        private set

    /** Prevents concurrent user retries without clearing unresolved cleanup or import fences. */
    var pushStorageRecoveryBusy by mutableStateOf(false)
        private set

    internal val diagnostics = UsageDiagnosticsController()
    val relayTelemetrySettings: RelayTelemetrySettingsFfi?
        get() = diagnostics.snapshot?.relayTelemetry
    val usageDiagnosticsSettings: UsageDiagnosticsSettingsFfi?
        get() = diagnostics.snapshot?.settings
    val usageDiagnosticsStatus: UsageDiagnosticsStatusFfi?
        get() = diagnostics.snapshot?.status

    var auditLogSettings by mutableStateOf<AuditLogSettingsFfi?>(null)
        private set

    // staleness-exempt: observable process identity serialized through destructive-wipe state transitions.
    var runtimeGeneration by mutableIntStateOf(0)
        private set

    var appUpdateInfo by mutableStateOf(appUpdateRepository.loadInfo())
        private set

    var appSelfUpdateState by mutableStateOf<AppSelfUpdateState>(AppSelfUpdateState.Idle)
        private set

    private var defaultNotificationsEnableAttempted by mutableStateOf(
        preferences.getBoolean(DEFAULT_NOTIFICATIONS_ENABLE_ATTEMPTED_KEY, false),
    )
    private var defaultNotificationPermissionPromptInFlight by mutableStateOf(false)

    // One-time onboarding hint for the conversation disappearing-timer chip (#335):
    // shown the first time the user opens a conversation whose timer is enabled.
    var disappearingTooltipShown by mutableStateOf(
        preferences.getBoolean(DISAPPEARING_TOOLTIP_SHOWN_KEY, false),
    )
        private set

    private val npubs =
        BoundedNpubCache(
            registry = accountScopedCaches,
            maxEntries = BoundedNpubCache.DEFAULT_MAX_ENTRIES,
        )

    // staleness-exempt: observable profile versions invalidate narrowly keyed Compose projections.
    internal var profileRevision by mutableIntStateOf(0)
    private var contactNicknameRevision by mutableIntStateOf(0)
    private val profileAccountRevisions = ProfileAccountRevisions(MAX_PROFILE_PRESENTATION_CACHE_ENTRIES)

    /**
     * Read-only Compose-tracked snapshot of profile-presentation invalidations
     * so callers outside this file can subscribe without exposing the mutable
     * backing fields. Includes this in a `remember(...)` key list to re-fire a
     * derivation when a profile update or private contact nickname edit lands
     * (e.g. the chat-list search filter must re-evaluate its visible title).
     */
    internal val profileRevisionForCompose: ProfilePresentationRevision
        get() = ProfilePresentationRevision(profileRevision, contactNicknameRevision)

    internal fun profileAccountRevisionForCompose(accountIdHex: String) = profileAccountRevisions.revision(accountIdHex)

    internal fun bumpProfileAccountRevision(accountIdHex: String) = profileAccountRevisions.bump(accountIdHex)

    internal val profilePresentations =
        ScopedCache<String, ProfilePresentation>(
            registry = accountScopedCaches,
            name = "profile-presentations",
            maxEntries = MAX_PROFILE_PRESENTATION_CACHE_ENTRIES,
            lock = profilePresentationLock,
        )
    internal val notificationDisplayNameHints =
        ScopedCache<String, String>(
            registry = accountScopedCaches,
            name = "notification-display-name-hints",
            maxEntries = MAX_PROFILE_PRESENTATION_CACHE_ENTRIES,
            lock = profilePresentationLock,
        )

    // Materialized profile metadata, populated off-main by [refreshProfile].
    // Read accessors serve from here so composition never crosses the FFI.
    private val userProfiles =
        ScopedCache<String, UserProfileMetadataFfi>(
            registry = accountScopedCaches,
            name = "user-profiles",
            maxEntries = MAX_USER_PROFILE_CACHE_ENTRIES,
            lock = profilePresentationLock,
        )

    // Profile ids whose avatar image was requested by a recent/live conversation
    // projection. Kept separate from general profile materialization so opening a
    // large roster does not trigger unsolicited image-host traffic.
    private val pendingAvatarPreWarmAccountIds =
        ScopedSet<String>(
            registry = accountScopedCaches,
            name = "pending-avatar-prewarms",
            maxEntries = MAX_PENDING_AVATAR_PREWARMS,
            lock = profilePresentationLock,
        )

    // Completion shared by lazy and awaited local materialization paths. Keeping
    // the reservation account-scoped prevents duplicate FFI reads while also
    // ensuring account switches cannot expose an old account's in-flight work.
    private val profileMaterializations =
        ScopedCache<String, CompletableDeferred<Unit>>(
            registry = accountScopedCaches,
            name = "profile-materializations",
            maxEntries = MAX_MATERIALIZING_PROFILES,
            lock = profilePresentationLock,
        )
    private val groupMemberSnapshots =
        ScopedCache<String, GroupMemberSnapshot>(
            registry = accountScopedCaches,
            name = "group-member-snapshots",
            maxEntries = MAX_GROUP_MEMBER_SNAPSHOT_CACHE_ENTRIES,
            lock = groupMemberSnapshotLock,
        )
    private val conversationStateLock = Any()
    internal val longMessageCollapseState =
        LongMessageCollapseState(
            registry = accountScopedCaches,
            preferences = preferences,
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
        )
    internal val hiddenMessageIdsByAccountGroup =
        ScopedCache<String, MutableState<Set<String>>>(
            registry = accountScopedCaches,
            name = "hidden-message-ids",
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
            observable = true,
        )
    private val optimisticMessagesByConversation = mutableMapOf<String, SnapshotStateMap<String, TimelineMessage>>()

    // A failed optimistic send can be retried by a replacement controller after
    // navigation. Keep its one-shot acceptance cleanup beside the retained row,
    // under the same bounded conversation-state lifecycle.
    private val durableAcceptanceCallbacksByConversation = mutableMapOf<String, MutableMap<String, () -> Unit>>()
    private val projectedMessageIdsByConversation = mutableMapOf<String, MutableSet<String>>()
    private val timelineOrderOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()
    private val timelineTimestampOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()
    private val optimisticSendPositionPreservesByConversation =
        mutableMapOf<String, OptimisticSendPositionPreserves>()
    private val retentionAtSendByConversation = mutableMapOf<String, MutableMap<String, ULong>>()

    // Canonical MDK app-event id -> local optimistic text id. MDK can accept a
    // text intent before publishing it, so the eventual projection needs this
    // exact bridge instead of matching identical pending texts heuristically.
    private val acceptedPendingTextOptimisticIdsByConversation =
        mutableMapOf<String, MutableMap<String, String>>()

    // Stable client-key phase for each optimistic send. The controller keeps
    // this beside the retained optimistic row so a replacement conversation
    // can still distinguish cancellable pre-acceptance work from an intent
    // already owned by MDK.
    private val optimisticSendPhasesByConversation =
        mutableMapOf<String, MutableMap<String, OptimisticSendPhase>>()
    private val optimisticCancellationGenerationByConversation = mutableMapOf<String, MutableStateFlow<Long>>()

    // Retained-upload bytes survive screen disposal so a user who navigates
    // out of a chat mid-send and returns sees the pending bubble still carry
    // its preview/filename instead of an empty placeholder. Cap (and sizeOf
    // policy) match the controller-local version they replace.
    private val retainedMediaUploadsByConversation = mutableMapOf<String, dev.ipf.whitenoise.android.media.ByteSizeLruCache<String, RetainedMediaUpload>>()
    private val activeUploadKeysByConversation = mutableMapOf<String, MutableSet<String>>()
    private val pendingProjectionsAwaitingBridgeByConversation =
        mutableMapOf<String, MutableMap<String, dev.ipf.marmotkit.TimelineMessageRecordFfi>>()

    // In-flight attachment downloads, keyed by the mediaCacheKey. Routed
    // through `mutationsScope` so the FFI download continues even when the
    // calling screen disposes (e.g., user tapped a file then swiped away).
    // Memoized so a re-entry / sibling tile / retry tap shares the same
    // Deferred instead of spawning a second Blossom fetch.
    private val inFlightDownloads = mutableMapOf<String, Deferred<ByteArray>>()
    private val inFlightDownloadsLock = Any()
    private val inFlightMediaUploads = InFlightMediaUploads()

    // Bound attachment fetches without making a visible album wait for one
    // network/decrypt round-trip per tile. The gate still prevents an
    // unbounded burst from swamping the Blossom / FFI stack, and bounded
    // retries keep transient queued-behind failures from sticking tiles in
    // `failed` before the user has a chance to see the media.
    private val attachmentDownloadGate = AttachmentDownloadGate()
    internal val attachmentDownloadIntents =
        AttachmentDownloadIntentStore(
            preferences,
            EncryptedAttachmentInstallerHandoffRecordStore.create(appContext),
        )

    // staleness-exempt: observable preference version consumed as a Compose key.
    private var attachmentDownloadPolicyRevision by mutableIntStateOf(0)
    private val conversationStateRetention =
        ConversationStateRetention(MAX_RETAINED_CONVERSATION_STATES, isProtected = inFlightMediaUploads::hasUploads)

    val shareStaging: ShareStagingStore = ShareStagingStore()

    // staleness-exempt: observable draft version combined into the composer revision.
    private var draftHydrationRevision by mutableIntStateOf(0)

    // Transient view invalidation, bounded with the existing retained conversation state.
    private val nativeComposerCleanupRevisions = mutableStateMapOf<String, Long>()

    // Never reuse a stamp after conversation eviction or account-cache cleanup.
    private var nativeComposerCleanupSequence = 0L

    /** Only committed cleanup of this exact owner may refresh its native-restored media shelf. */
    internal fun nativeComposerCleanupRevision(
        accountRef: String?,
        groupIdHex: String,
    ): Long = nativeComposerCleanupRevisions[conversationKey(accountRef, groupIdHex)] ?: 0L

    /** Changes when content is staged so an already-open chat consumes repeat shares. */
    val inboundShareRevision: Int
        get() = shareStaging.revision + draftHydrationRevision
    internal val shareInboundStager =
        ShareInboundStager(
            stageText =
                inboundShareTextStager ?: { accountRef, groupIdHex, text ->
                    mutationsScope.launch {
                        val completion =
                            draftWriter.mergeText(accountRef, groupIdHex, text, trimIncoming = false)
                        draftWriter.hydrateMergedDraft(draftStore, accountRef, groupIdHex, completion) {
                            draftHydrationRevision += 1
                        }
                        when (val result = completion.result) {
                            is MessageDraftMutationResult.Failure ->
                                appStateDebug(result.cause) { "shared text staging failed group=${groupIdHex.take(8)}" }
                            else -> Unit
                        }
                    }
                    Unit
                },
            shareStaging = shareStaging,
            resolveMime = { context, uri -> shareResolveMime(context, uri) },
        )
    private val shareShortcutPublisher =
        ShareShortcutPublisher(appContext, avatarOverride = { account, item -> contactPictureOverride(account, item) })
    private var shareShortcutPublishJob: Job? = null
    private var pinnedShortcutRefreshJob: Job? = null
    private val pinnedShortcutPresentationRevision = AtomicLong()

    /**
     * `SupervisorJob` isolates siblings but does not swallow exceptions — an
     * uncaught throw in a direct child reaches `Thread.uncaughtExceptionHandler`,
     * which on the main thread kills the process. Only covers `launch`; `async`
     * parks its failure in the `Deferred` until `await()`.
     */
    private val scopeExceptionHandler = appStateScopeExceptionHandler()

    internal val profileScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + scopeExceptionHandler)
    private val profileRefreshFanoutGate = Semaphore(PROFILE_REFRESH_FANOUT)
    internal val mutationsScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + scopeExceptionHandler)
    private val hostPerformance =
        HostPerformanceRecorder(generation = { runtimeGeneration }).also { recorder ->
            initialMarmotRuntime?.let { runtime ->
                recorder.publishEmitter(runtime, runtimeGeneration, runtimeHostPerformanceEmitter(runtime))
            }
        }
    private val hostPreferenceCommitMutex = Mutex()
    internal val appLock =
        AppLockController(
            context = appContext,
            preferences = preferences,
            scope = mutationsScope,
            persistSetting = ::persistHostSetting,
            timestampWrite = appLockTimestampWrite,
            persistenceDispatcher = appLockPersistenceDispatcher,
            appInForeground = { appInForeground },
            stopSpeaking = ::stopSpeaking,
            presentNotice = { present(it) },
            resumeForegroundWork = { if (appInForeground) initializedConversationDictation()?.onAppForegrounded() },
            resumeDeferredWork = ::resumePendingInviteNotificationIdentityRefreshes,
            onUnlockSucceeded = { dismissRetainedVisibleConversation ->
                pushWakeRecovery.resumePushWakeEpisodeFromLifecycle()
                if (dismissRetainedVisibleConversation) dismissVisibleConversationNotifications()
            },
        )
    private val inFlightAttachmentAcquisitions =
        InFlightAttachmentAcquisitions(mutationsScope, attachmentDownloadGate)

    /** Captures one runtime so later replacement cannot receive an older owner's sample. */
    private fun runtimeHostPerformanceEmitter(runtime: AppMarmotRuntime): HostPerformanceEmitter =
        HostPerformanceEmitter { operation, durationMs, outcome ->
            mutationsScope.launch(Dispatchers.IO) {
                runCatching {
                    runtime.marmot.recordHostPerformance(operation, durationMs.toULong(), outcome)
                }
            }
        }

    internal val attachmentUserActions = AttachmentUserActions(mutationsScope)

    /** Attachments whose download the reader started and MDK parked for storage, for this process only. */
    internal val readerParkedDownloads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    internal val attachmentOpens =
        AttachmentOpenCoordinator(
            intentStore = attachmentDownloadIntents,
            scope = mutationsScope,
            enqueue = ::enqueueAttachmentDownload,
            visibility = { destination, request ->
                attachmentOpenDestinationVisible(
                    destination,
                    request,
                    appInForeground,
                    activeConversationAccountRef,
                    activeConversationGroupIdHex,
                )
            },
        )
    internal val attachmentInstallerHandoffs =
        AttachmentInstallerHandoffCoordinator(
            intentStore = attachmentDownloadIntents,
            scope = mutationsScope,
            enqueue = ::enqueueAttachmentDownload,
            foregroundEligible = { appInForeground && !appLock.screenVisible },
        )
    private val forwardTerminalDismiss =
        ForwardTerminalDismissPolicy(
            scope = mutationsScope,
            displayDurationMillis = FORWARD_TERMINAL_STATUS_DURATION_MS,
            currentSnapshot = { activeForwardOperation.value },
            dismiss = { dismissActiveForwardOperation() },
        )
    private val forwardOperationOwner =
        ForwardOperationOwner(
            scope = mutationsScope,
            automaticRetryAttempts = FORWARD_BACKGROUND_RETRY_ATTEMPTS,
            retryDelayMillis = { attempt -> FORWARD_BACKGROUND_RETRY_DELAY_MS shl attempt },
            onTerminal = { snapshot ->
                activeForwardDiagnostics?.terminal(snapshot)
                forwardTerminalDismiss.onTerminal(snapshot)
            },
        )
    internal val activeForwardOperation: StateFlow<ForwardOperationSnapshot?> = forwardOperationOwner.state

    /** Phase timings of the visible forward operation, present only while local diagnostics are active. */
    private var activeForwardDiagnostics: ForwardDiagnostics? = null

    /** Destination owner of the visible forward operation, for account-scoped progress UI. */
    internal var activeForwardDestinationAccountRef by mutableStateOf<String?>(null)
        private set

    /** Destination chat titles captured at forward acceptance, keyed by lowercase group id. */
    internal var activeForwardTargetTitles by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private val draftWriter =
        CoalescingMessageDraftWriter(
            scope = mutationsScope,
            drafts = messageDraftRepository,
            onResult = { accountRef, groupIdHex, _, result ->
                when (result) {
                    is MessageDraftMutationResult.Success -> {
                        draftStore.applyAuthoritativeTimestamp(accountRef, groupIdHex, result.draft?.updatedAtMs)
                    }
                    is MessageDraftMutationResult.Failure -> {
                        appStateDebug(result.cause) {
                            "draft save failed group=${groupIdHex.take(8)}: ${result.cause.readableMessage()}"
                        }
                    }
                    else -> Unit
                }
            },
        )
    private val draftSummaryRefreshLifetime = StalenessGuard()
    internal val notificationReplyDraftHandoff by lazy {
        NotificationReplyDraftHandoff(
            mutationsScope,
            draftWriter,
            draftStore,
            available = { target -> accounts.any { it.label == target.accountRef && !it.signedOut } },
            onFailed = { present(R.string.toast_reply_draft_restore_failed) },
        ) { draftHydrationRevision += 1 }
    }
    private val composerDraftExpansionBridge =
        ComposerDraftExpansionBridge(
            draftWriter = draftWriter,
            draftStore = draftStore,
            draftRepository = messageDraftRepository,
            expansionRetention = composerExpansionStateRetention,
            scope = mutationsScope,
            onDraftPresentationChanged = { accountRef, groupIdHex, nativeDraftConsumed ->
                if (nativeDraftConsumed) {
                    synchronized(conversationStateLock) {
                        val key = retainConversationState(accountRef, groupIdHex)
                        nativeComposerCleanupSequence += 1
                        nativeComposerCleanupRevisions[key] = nativeComposerCleanupSequence
                    }
                } else {
                    draftHydrationRevision += 1
                }
            },
            onCleanupFailure = { groupIdHex, cause ->
                appStateDebug(cause) { "sent draft cleanup failed group=${groupIdHex.take(8)}" }
            },
        )
    internal val notificationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + scopeExceptionHandler)

    internal val notificationDelivery =
        NotificationDeliveryController(
            appContext = appContext,
            marmotIoDispatcher = marmotIoDispatcher,
            pushWakeStorageDispatcher = pushWakeStorageDispatcher,
            pushServerConfigProvider = pushServerConfigProvider,
            fcmTokenReaderOverride = fcmTokenReaderOverride,
            nativePushCapabilityResolver = nativePushCapabilityResolver,
            nativePushFallbackPlatform = nativePushFallbackPlatform,
            pushTokenStore = pushTokenStore,
            localNotificationPresenter = localNotificationPresenter,
            notificationScope = notificationScope,
            host =
                object : NotificationDeliveryHost {
                    override val activeAccountRef get() = this@WhiteNoiseAppState.activeAccountRef
                    override val marmotRuntime get() = this@WhiteNoiseAppState.marmotRuntime
                    override val runtimeGeneration get() = this@WhiteNoiseAppState.runtimeGeneration
                    override val accountSwitchHandoff get() = this@WhiteNoiseAppState.accountSwitchHandoff
                    override val signOutInProgress get() = this@WhiteNoiseAppState.signOutInProgress
                    override val wipeInProgress get() = this@WhiteNoiseAppState.wipeInProgress
                    override val pushWakeServiceOwned get() = pushWakeRecovery.pushWakeServiceOwned

                    override fun nativePushAccountRefs(): List<String> =
                        accounts
                            .filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
                            .map { it.label }

                    override suspend fun refreshAccounts() = this@WhiteNoiseAppState.refreshAccounts()

                    @Suppress("MaxLineLength")
                    override suspend fun <T> marmotIo(block: suspend MarmotInterface.() -> T): T = this@WhiteNoiseAppState.marmotIo(block)

                    @Suppress("MaxLineLength")
                    override suspend fun <T> withAccountLifetime(block: suspend () -> T): T = this@WhiteNoiseAppState.withAccountLifetime(block)

                    override fun present(titleRes: Int) = this@WhiteNoiseAppState.present(titleRes)

                    override fun present(
                        titleRes: Int,
                        detailRes: Int,
                        copyable: Boolean,
                    ) = this@WhiteNoiseAppState.present(titleRes, detailRes, copyable)

                    override fun presentTransient(titleRes: Int) = this@WhiteNoiseAppState.presentTransient(titleRes)

                    override fun presentFailure(
                        titleRes: Int,
                        operationCode: String,
                        throwable: Throwable,
                    ) = this@WhiteNoiseAppState.presentFailure(titleRes, operationCode, throwable)
                },
        )

    val localNotificationSettings: NotificationSettingsFfi?
        get() = notificationDelivery.localNotificationSettings
    val localNotificationPermissionGranted: Boolean
        get() = notificationDelivery.localNotificationPermissionGranted
    val backgroundConnectionEnabled: Boolean
        get() = notificationDelivery.backgroundConnectionEnabled
    val notificationDeliveryModeBusy: Boolean
        get() = notificationDelivery.notificationDeliveryModeBusy
    internal val notificationBatteryPolicy: NotificationBatteryPolicy
        get() = notificationDelivery.notificationBatteryPolicy
    internal val notificationCandidateReads = NotificationCandidateReadCoordinator(notificationScope, Dispatchers.IO)
    internal val notificationFirstPostContentCoordinator =
        NotificationFirstPostContentCoordinator(notificationScope, notificationDispatcher, SystemClock::elapsedRealtime)
    internal val notificationContentResolution by lazy {
        createNotificationContentResolutionServices(appContext, NotificationContentReads())
    }
    private val notificationNicknameRefresh by lazy {
        NotificationNicknameRefreshCoordinator(
            notificationScope,
            notificationContentResolution.identity,
            localNotificationPresenter,
        )
    }
    private val conversationOpenDismissals by lazy {
        ConversationOpenNotificationDismissalCoordinator(
            notificationScope,
            notificationCardCancellationDispatcher,
            localNotificationPresenter,
        )
    }

    /** Delegates live notification reads without creating a callback class for each dependency. */
    @Suppress("TooManyFunctions")
    private inner class NotificationContentReads : NotificationContentSource {
        override fun contactNickname(
            accountRef: String?,
            accountIdHex: String,
        ): String? = contactNicknameFor(accountRef, accountIdHex)

        override suspend fun readDisplayName(accountIdHex: String): String? =
            marmotIo(
                MarmotTraceSection.DISPLAY_NAME_READ,
            ) { displayName(accountIdHex) }

        override fun displayNameHint(accountIdHex: String): String? = notificationDisplayNameHints[accountIdHex]

        override fun cachedShortNpub(accountIdHex: String): String = cachedShortNpubOrUnknown(accountIdHex)

        override fun hydratedDisplayName(accountIdHex: String): String? =
            synchronized(profilePresentationLock) { profilePresentations[accountIdHex]?.displayName }

        override fun requestProfile(accountIdHex: String) = this@WhiteNoiseAppState.requestProfile(accountIdHex)

        override suspend fun accountIdHex(bech32: String): String? = this@WhiteNoiseAppState.accountIdHex(bech32)

        override suspend fun parseMarkdown(raw: String) = parseMarkdownOrEmpty(raw)

        override fun recipientAccountIdHex(ref: String) = accounts.firstOrNull { it.label == ref }?.accountIdHex

        override suspend fun timelineRecord(update: NotificationUpdateFfi) = notificationTimelineRecord(update)

        override suspend fun groupMembers(update: NotificationUpdateFfi): List<AppGroupMemberRecordFfi> =
            runCatchingCancellable { marmotIo { groupMembers(update.accountRef, update.groupIdHex) } }
                .getOrNull()
                .orEmpty()

        /** Uses the receiver's chat row, including when its chat list is cold or another account is active. */
        override suspend fun isDirectConversation(update: NotificationUpdateFfi): Boolean {
            val cachedKind = notificationChatListItem(update)?.projection?.conversationKind
            val kind =
                cachedKind?.takeUnless { it == ChatConversationKindFfi.UNKNOWN }
                    ?: presentedNotificationChatRow(update)?.row?.conversationKind
            return kind == ChatConversationKindFfi.DIRECT
        }

        override suspend fun mediaKind(update: NotificationUpdateFfi): ReplyMediaKind =
            resolveNotificationMediaKind(
                update,
                ::notificationMessageRecord,
            )

        /** Projects only identities that can currently sign notification actions. */
        override fun signedInAccountIds(): Set<String> = accounts.signedInSigningAccountIds()
    }

    internal val notificationAvatarCoordinator by lazy {
        NotificationAvatarCoordinator(
            appLocked = { appLock.screenVisible },
            shouldPost = this::shouldPostNotification,
            canPost = localNotificationPresenter::canPostNotifications,
            senderAvatarUrl = { update -> notificationSenderAvatarUrl(update, ::loadUserProfile) },
            groupAvatarUrl = { update ->
                ProfileSanitizer.protocolImageUrl(
                    marmotIo { groupDetails(update.accountRef, update.groupIdHex) }.group.avatarUrl,
                )
            },
        )
    }
    internal val notificationEnrichmentGate = Semaphore(NOTIFICATION_ENRICHMENT_FANOUT)
    internal val accountSession by lazy {
        AppAccountSessionCoordinator(
            mutationScope = mutationsScope,
            stateDispatcher = Dispatchers.Main.immediate,
            listenerTeardown = { runtimeListenerTeardownOwner },
            activation = AccountSessionActivation(),
        )
    }
    internal val pushWakeRecovery: PushWakeRecoveryController =
        PushWakeRecoveryController(
            pushTokenStore = pushTokenStore,
            pushWakeStorageDispatcher = pushWakeStorageDispatcher,
            pushWakeNowMs = pushWakeNowMs,
            schedulePushWakeRecovery = schedulePushWakeRecovery,
            notificationScope = notificationScope,
            catchUpWindow = localNotificationPresenter.catchUpWindow,
            host =
                object : PushWakeRecoveryHost {
                    override val activeAccountRef get() = this@WhiteNoiseAppState.activeAccountRef
                    override val runtimeGeneration get() = this@WhiteNoiseAppState.runtimeGeneration
                    override val phase get() = this@WhiteNoiseAppState.phase
                    override val appInForeground get() = this@WhiteNoiseAppState.appInForeground
                    override val bootstrapCompleted get() = this@WhiteNoiseAppState.bootstrapCompleted
                    override val networkNotificationRecoverySuppressed
                        get() = this@WhiteNoiseAppState.networkNotificationRecoverySuppressed
                    override val validatedConnectivityRecoveryGeneration
                        get() = validatedConnectivityRecoveryGenerationMutable.value
                    override val networkRecoveryActive get() = notificationNetworkRecovery.isActive()

                    override fun captureNetworkGeneration(): Long = connectivitySignalOwner.captureNetworkGeneration()

                    @Suppress("MaxLineLength")
                    override fun isNetworkGenerationCurrent(generation: Long): Boolean = connectivitySignalOwner.isNetworkGenerationCurrent(generation)

                    override fun recordStartupRelayCatchUpReady() {
                        this@WhiteNoiseAppState.recordStartupRelayCatchUpReady()
                    }

                    override suspend fun <T> marmotIo(
                        traceSection: String,
                        block: suspend MarmotInterface.() -> T,
                    ): T = this@WhiteNoiseAppState.marmotIo(traceSection, block)

                    @Suppress("MaxLineLength")
                    override fun notificationRuntimeRecoveryGeneration(): Long = this@WhiteNoiseAppState.notificationRuntimeRecoveryGeneration()

                    override fun notificationRuntimeRecoveryAllowed(generation: Long): Boolean =
                        this@WhiteNoiseAppState.notificationRuntimeRecoveryAllowed(generation)

                    override suspend fun ensureNotificationRuntimeStartedAndAwaitPushDrain(): Boolean =
                        this@WhiteNoiseAppState.ensureNotificationRuntimeStartedAndAwaitPushDrain()

                    @Suppress("MaxLineLength")
                    override suspend fun ensureNotificationRuntimeStarted(): Boolean = this@WhiteNoiseAppState.ensureNotificationRuntimeStarted()

                    @Suppress("MaxLineLength")
                    override suspend fun syncNativePushRegistrationIfEnabled(): Boolean = this@WhiteNoiseAppState.syncNativePushRegistrationIfEnabled()
                },
        )
    private var pendingAccountSwitchTrace: PendingAccountSwitchTrace? = null

    /** Native identity commands and lifecycle transitions share the account-session owner. */
    internal suspend fun <T> withAccountLifetime(block: suspend () -> T): T = accountSession.withLifetime(block)

    /** A queued native command cannot silently retarget a replacement runtime or active owner. */
    internal suspend fun <T> withCurrentAccountLifetime(block: suspend () -> T): T {
        val runtime = marmotRuntime
        val epoch = runtimeGeneration
        val owner = activeAccountRef
        return withAccountLifetime {
            if (marmotRuntime !== runtime || runtimeGeneration != epoch || activeAccountRef != owner) {
                throw CancellationException("Native account command owner was replaced")
            }
            block()
        }
    }

    internal val accountSwitchHandoff get() = accountSession.handoff

    // Bumped whenever cross-account caches are cleared (switch / sign-out). An
    // in-flight profile refresh captures it at start and discards its result if
    // the epoch moved, so a job that resolves after a switch can't write the
    // old account's data back into the just-cleared caches.
    internal val profileCacheLifetime = StalenessGuard()
    private val mediaUploadSessionLifetime = StalenessGuard()
    internal val notificationJob = NotificationJobSlot()

    @Volatile
    internal var networkNotificationRecoverySuppressed = false
    private val notificationReceiverActive = MutableStateFlow(false)
    internal val notificationReceiverRetryWake = MutableStateFlow(0L)

    // staleness-exempt: ordered notification-drain watermark emitted as data to a SharedFlow.
    internal val notificationDrainSequence = AtomicLong(0)
    private val notificationRuntimeRecovery = StalenessGuard()
    internal val notificationPostEpoch = StalenessGuard()
    internal val notificationDrainSignals = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Coalesces per-account unread refreshes across a notification burst so a
    // catch-up flood drains to one expensive (chat-list + per-group roster)
    // refresh per account per window instead of one per update (#729). Holds
    // only short-lived lifecycle state; the count still comes from the
    // suppression-preserving refreshAccountUnreadCount source of truth.
    internal val unreadRefreshScheduler =
        UnreadRefreshScheduler(scope = notificationScope) { accountRef ->
            refreshAccountUnreadCount(accountRef)
        }

    // Single source of truth for notification suppression: whether an Activity
    // is on screen, plus the conversation that will be suppressed only while it
    // is foreground-visible. The active conversation is scoped to its account so
    // viewing a shared group under one account doesn't mute the other account's
    // alerts. Lifecycle transitions (foreground/background/task-removed) live
    // on the value type so the foreground gate and active-chat reset cannot
    // drift across the separate places that update them (issue #821).
    @Volatile
    internal var suppression = NotificationSuppression()
    private val conversationReadVisibilityState = mutableStateOf(suppression)

    /** Read-only Compose view of notification suppression's foreground and conversation ownership. */
    internal val conversationReadVisibility: State<NotificationSuppression>
        get() = conversationReadVisibilityState

    /** Publishes shared visibility to notifications, visible reads and dictation diagnostics. */
    private fun updateNotificationSuppression(next: NotificationSuppression) {
        if (next != suppression) notificationPostEpoch.advance()
        suppression = next
        conversationReadVisibilityState.value = next
        dictationDiagnosticLifecycle.originVisibility({ conversationDictation }) {
            isConversationDictationOriginVisible(it.accountRef, it.groupIdHex)
        }
    }

    internal val appInForeground: Boolean
        get() = suppression.inForeground
    internal val activeConversationGroupIdHex: String?
        get() = suppression.activeConversationGroupIdHex
    internal val activeConversationAccountRef: String?
        get() = suppression.activeConversationAccountRef

    /** Whether the exact dictation origin is the unobscured foreground conversation. */
    internal fun isConversationDictationOriginVisible(
        accountRef: String,
        groupIdHex: String,
    ): Boolean =
        conversationDictationOriginVisible(
            appInForeground = appInForeground,
            appLockScreenVisible = appLock.screenVisible,
            pendingProfileNpub = pendingProfileNpub,
            activeAccountRef = activeConversationAccountRef,
            activeGroupIdHex = activeConversationGroupIdHex,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
        )

    private val profileRefreshGate = ProfileRefreshGate(PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS)
    internal var chatsController: ChatsController? = null
    private val conversationControllerLock = Any()
    private val conversationControllers = linkedSetOf<ConversationController>()

    val activeAccount: AccountSummaryFfi?
        get() = activeAccountRef?.let { ref -> accounts.firstOrNull { it.label == ref } }

    /** Return [accountRef]'s draft for [groupIdHex], or null when the conversation is unbound. */
    fun draftFor(
        accountRef: String?,
        groupIdHex: String,
    ): String? = draftSnapshotFor(accountRef, groupIdHex)?.textFieldValue?.text

    internal fun chatRowDraftFor(
        accountRef: String?,
        groupIdHex: String,
    ): String? = draftFor(accountRef, groupIdHex)

    /** Applies accepted-send presentation without changing MDK's durable draft or selected projection. */
    internal fun chatRowSelectedPreviewFor(
        accountRef: String?,
        groupIdHex: String,
        nativePreview: dev.ipf.marmotkit.SelectedChatPreviewFfi?,
        hasOptimisticSendPreview: Boolean = false,
    ): dev.ipf.marmotkit.SelectedChatPreviewFfi? =
        if (accountRef == null) {
            nativePreview
        } else {
            composerDraftExpansionBridge.selectedPreview(
                accountRef = accountRef,
                groupIdHex = groupIdHex,
                nativePreview = nativePreview,
                hasOptimisticSendPreview = hasOptimisticSendPreview,
            )
        }

    /** Return [accountRef]'s restored composer draft for [groupIdHex]. */
    fun draftSnapshotFor(
        accountRef: String?,
        groupIdHex: String,
    ): ComposerDraftSnapshot? = accountRef?.let { draftStore.getDraft(it, groupIdHex) }

    /** Write [accountRef]'s draft for [groupIdHex]. Empty/blank clears. */
    fun setDraft(
        accountRef: String?,
        groupIdHex: String,
        value: TextFieldValue,
        textChanged: Boolean? = null,
    ) {
        accountRef ?: return
        setDraftForAccount(accountRef, groupIdHex, value, textChanged)
    }

    private fun setDraftForAccount(
        accountRef: String,
        groupIdHex: String,
        value: TextFieldValue,
        textChanged: Boolean? = null,
    ) {
        composerDraftExpansionBridge.setDraft(accountRef, groupIdHex, value, textChanged)
    }

    /** Current draft generation used to bind an explicit composer resize. */
    internal fun composerDraftGeneration(
        accountRef: String,
        groupIdHex: String,
    ): Long = composerDraftExpansionBridge.generation(accountRef, groupIdHex)

    private fun conversationDictationDraftSnapshot(
        accountRef: String,
        groupIdHex: String,
    ): ConversationDictationDraftSnapshot {
        // A repository/attachment mutation can advance the generation from a
        // worker while this main-thread lifecycle cache is read. Take a second
        // generation sample and retry once so the pair normally describes one
        // stable observation; the conditional write still fails closed if a
        // later mutation wins the race.
        repeat(2) {
            val before = draftWriter.generation(accountRef, groupIdHex)
            val value = draftStore.getDraft(accountRef, groupIdHex)?.textFieldValue ?: TextFieldValue("")
            val after = draftWriter.generation(accountRef, groupIdHex)
            if (before == after) return ConversationDictationDraftSnapshot(value, after.value)
        }
        val latest = draftWriter.generation(accountRef, groupIdHex)
        val value = draftStore.getDraft(accountRef, groupIdHex)?.textFieldValue ?: TextFieldValue("")
        return ConversationDictationDraftSnapshot(value, latest.value)
    }

    private fun writeCompletedDictationDraft(
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ): dev.ipf.whitenoise.android.audio.ConversationDictationDraftWrite =
        if (appLock.screenVisible || appLock.evaluationPending) {
            dev.ipf.whitenoise.android.audio.ConversationDictationDraftWrite.DeferredByHost
        } else {
            composerDraftExpansionBridge
                .writeDraftIfCurrent(accountRef, groupIdHex, expectedRevision, value)
                ?.let(dev.ipf.whitenoise.android.audio.ConversationDictationDraftWrite::Written)
                ?: dev.ipf.whitenoise.android.audio.ConversationDictationDraftWrite.Refused
        }

    private fun setConversationDictationDraftIfCurrent(
        accountRef: String,
        groupIdHex: String,
        expectedRevision: Long,
        value: TextFieldValue,
    ): Long? =
        writeConversationDictationDraftWhenUnlocked(appLock.screenVisible, appLock.evaluationPending) {
            composerDraftExpansionBridge.writeDraftIfCurrent(accountRef, groupIdHex, expectedRevision, value)
        }

    /**
     * Runs on the process mutationsScope's main dispatcher, as do composer mode changes.
     * Origin resolution, the draft claim and mounted-controller admission have no suspension between them.
     * Failed or unknown sends preserve their captured text instead of borrowing another editor's state.
     */
    internal suspend fun sendDictationTranscriptIfOriginUnchanged(request: ConversationDictationSendRequest): Boolean {
        val current = conversationDictationDraftSnapshot(request.accountRef, request.groupIdHex)
        val initialReply =
            conversationDictationReplyTargetResolution(
                request.accountRef,
                request.groupIdHex,
                request.replyToMessageIdHex,
            )
        val draftMatches =
            current.revision == request.expectedDraftRevision &&
                current.value.text == request.expectedDraftText
        val replyOriginAvailable =
            when (initialReply) {
                ConversationDictationReplyTargetResolution.Detached -> request.replyToMessageIdHex == null
                ConversationDictationReplyTargetResolution.Mismatch -> false
                is ConversationDictationReplyTargetResolution.Mounted -> true
            }
        val dispatchStarted = draftMatches && replyOriginAvailable && request.beginDispatch()
        if (!dispatchStarted) return false
        return when (
            val liveReply =
                conversationDictationReplyTargetResolution(
                    request.accountRef,
                    request.groupIdHex,
                    request.replyToMessageIdHex,
                )
        ) {
            ConversationDictationReplyTargetResolution.Detached ->
                when {
                    request.replyToMessageIdHex != null -> rejectDictationDispatchBeforeTransport(request)
                    // No composer is mounted to refuse it, and this path sends straight to MDK.
                    exceedsOutgoingMessageLimit(request.payload.trim()) -> {
                        present(R.string.toast_message_too_long)
                        rejectDictationDispatchBeforeTransport(request)
                    }
                    else -> sendDetachedDictationText(request)
                }
            ConversationDictationReplyTargetResolution.Mismatch -> rejectDictationDispatchBeforeTransport(request)
            is ConversationDictationReplyTargetResolution.Mounted ->
                sendDictationThroughMountedController(liveReply.controller, request)
        }
    }

    private fun rejectDictationDispatchBeforeTransport(request: ConversationDictationSendRequest): Boolean {
        request.onDispatchRejectedBeforeTransport()
        return false
    }

    /** Sends through the mounted composer: with its staged attachments when it holds any, else as plain text. */
    private suspend fun sendDictationThroughMountedController(
        controller: ConversationController,
        request: ConversationDictationSendRequest,
    ): Boolean {
        controller.stagedAttachmentSender
            ?.takeIf(StagedAttachmentSender::hasStagedAttachments)
            ?.let { staged -> return sendDictationWithStagedAttachments(staged, request) }
        val replyTarget = controller.replyingTo
        var pendingShown = false
        var durablyAccepted = false
        return try {
            durablyAccepted =
                sendConversationText(controller, request.payload, onAccepted = {
                    pendingShown = true
                    request.onPendingShown()
                })
            if (!durablyAccepted && !pendingShown) rejectDictationDispatchBeforeTransport(request) else durablyAccepted
        } finally {
            if (!durablyAccepted && replyTarget != null && controller.replyingTo == null) {
                controller.replyingTo = replyTarget
            }
        }
    }

    /**
     * Sends the dictated text together with the composer's staged attachments, as an ordinary Send would.
     *
     * A refusal publishes nothing and releases the dispatch claim, so the transcript returns to the draft
     * with the attachments still staged instead of leaving as a text-only message.
     */
    private suspend fun sendDictationWithStagedAttachments(
        staged: StagedAttachmentSender,
        request: ConversationDictationSendRequest,
    ): Boolean {
        if (staged.sendWithCaption(request.payload, request.onPendingShown)) return true
        return rejectDictationDispatchBeforeTransport(request)
    }

    private fun conversationDictationReplyTargetResolution(
        accountRef: String,
        groupIdHex: String,
        replyToMessageIdHex: String?,
    ): ConversationDictationReplyTargetResolution =
        synchronized(conversationControllerLock) {
            resolveConversationDictationReplyTarget(
                conversationControllers,
                accountRef,
                groupIdHex,
                replyToMessageIdHex,
            )
        }

    /** Hydrates the selected composer from MDK without retaining attachment plaintext in Android state. */
    fun loadDraft(
        accountRef: String?,
        groupIdHex: String,
    ) {
        accountRef ?: return
        mutationsScope.launch {
            notificationReplyDraftHandoff.retryPending(accountRef, groupIdHex)
            val generation = draftWriter.generation(accountRef, groupIdHex)
            draftWriter
                .loadIfCurrent(accountRef, groupIdHex, generation)
                ?.onSuccess { draft ->
                    draftWriter.runHydrationIfCurrent(accountRef, groupIdHex, generation) {
                        draftStore.replaceFromAuthoritative(
                            accountRef,
                            groupIdHex,
                            draft?.content,
                            draft?.updatedAtMs,
                        )
                        draftHydrationRevision += 1
                    }
                }?.onFailure { appStateDebug(it) { "draft load failed group=${groupIdHex.take(8)}" } }
        }
    }

    internal fun captureDraftForSend(
        accountRef: String?,
        groupIdHex: String,
    ): DraftSendClearToken? = composerDraftExpansionBridge.captureForSend(accountRef, groupIdHex)

    /** Clears only the draft and geometry generation durably accepted by MDK. */
    internal fun clearDraftAfterSuccessfulSend(pendingClear: DraftSendClearToken) {
        composerDraftExpansionBridge.clearAfterDurableAcceptance(pendingClear)
    }

    internal suspend fun sendConversationText(
        controller: ConversationController,
        text: String,
        onPresentationAccepted: (String) -> Unit = {},
        onAccepted: () -> Unit = {},
    ): Boolean {
        val pendingClear = captureDraftForSend(controller.boundAccountRef, controller.group.groupIdHex)
        var accepted = false
        var durablyAccepted = false
        controller.send(
            text = text,
            onPresentationAccepted = onPresentationAccepted,
            onAccepted = {
                accepted = true
                pendingClear?.let(composerDraftExpansionBridge::hideForPendingSend)
                onAccepted()
            },
            onDurablyAccepted = {
                durablyAccepted = true
                pendingClear?.let(::clearDraftAfterSuccessfulSend)
            },
            onTerminalFailure = {
                if (accepted && !durablyAccepted) {
                    pendingClear?.let(composerDraftExpansionBridge::restoreAfterTerminalFailure)
                }
            },
        )
        return durablyAccepted
    }

    internal suspend fun deleteDraftBeforeGroupRemoval(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftMutationResult = composerDraftExpansionBridge.deleteBeforeGroupRemoval(accountRef, groupIdHex)

    /**
     * Refreshes draft summaries behind account, request, and local-fingerprint fences. Returns the
     * refresh job, so tests can wait for its fenced publication rather than only the native read.
     */
    internal fun refreshDraftSummaries(accountRef: String): Job {
        val refresh = draftSummaryRefreshLifetime.advance()
        val expected = draftStore.captureSummaryRefresh(accountRef)
        return mutationsScope.launch {
            messageDraftRepository
                .summaries(accountRef)
                .onSuccess { summaries ->
                    draftSummaryRefreshLifetime.runIfCurrent(refresh) {
                        if (activeAccountRef == accountRef) {
                            draftStore.replaceSummaries(
                                accountRef,
                                summaries.associate { it.groupIdHex to it.updatedAtMs },
                                expected = expected,
                            )
                        }
                    }
                }.onFailure { appStateDebug(it) { "draft summaries load failed account=${accountRef.take(8)}" } }
        }
    }

    /**
     * The native client when one is installed, or null before startup finishes or after teardown. For composition-
     * time readers that must not throw while the runtime is still coming up; it reads the same runtime as [marmot].
     */
    internal fun marmotOrNull(): MarmotInterface? = marmotRuntime?.marmot

    fun marmot(): MarmotInterface {
        RuntimePolicyHooks.noteSlowCall("marmot-ffi-access")
        marmotAccessObserver?.invoke()
        return requireNotNull(marmotRuntime) { "Marmot is not initialized" }.marmot
    }

    /** Starts one generation-fenced MDK host performance attempt. */
    internal fun beginHostPerformance(
        operation: HostPerformanceOperationFfi,
        startedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): HostPerformanceAttempt = hostPerformance.begin(operation, startedAtElapsedMs)

    /** Records a completed host stage measured from an existing monotonic boundary. */
    internal fun recordHostPerformanceSince(
        operation: HostPerformanceOperationFfi,
        startedAtElapsedMs: Long,
        outcome: HostPerformanceOutcomeFfi = HostPerformanceOutcomeFfi.SUCCESS,
    ) {
        hostPerformance.record(
            operation = operation,
            durationMs = SystemClock.elapsedRealtime() - startedAtElapsedMs,
            outcome = outcome,
        )
    }

    /** Records an already measured closed-schema host duration. */
    internal fun recordHostPerformance(
        operation: HostPerformanceOperationFfi,
        durationMs: Long,
        outcome: HostPerformanceOutcomeFfi,
    ) {
        hostPerformance.record(operation, durationMs, outcome)
    }

    /** Captures the current runtime for an Activity-owned frame reporter. */
    internal fun captureHostPerformanceRuntimeOwner(): HostPerformanceRuntimeOwner? =
        marmotRuntime
            ?.let { HostPerformanceRuntimeOwner(it, runtimeGeneration) }

    /** Checks that a frame reporter still belongs to the current runtime identity. */
    internal fun ownsHostPerformanceRuntimeOwner(owner: HostPerformanceRuntimeOwner): Boolean =
        marmotRuntime === owner.runtime && runtimeGeneration == owner.generation

    /** Records a frame sample only while its captured runtime owner remains current. */
    internal fun recordHostPerformance(
        owner: HostPerformanceRuntimeOwner,
        operation: HostPerformanceOperationFfi,
        durationMs: Long,
        outcome: HostPerformanceOutcomeFfi,
    ): Boolean {
        if (!ownsHostPerformanceRuntimeOwner(owner)) return false
        hostPerformance.record(operation, durationMs, outcome)
        return true
    }

    /** Measures a suspending Android-owned stage without changing its failure semantics. */
    internal suspend fun <T> measureHostPerformance(
        operation: HostPerformanceOperationFfi,
        block: suspend () -> T,
    ): T = hostPerformance.measure(operation, block)

    /**
     * Launches a group/account mutation on a process-lifetime scope so it
     * survives if the host composable (bottom sheet, dialog) dismisses
     * mid-flight. Without this, MLS commits + Nostr publishes can be cancelled
     * by `rememberCoroutineScope()` going away — the FFI work may still
     * succeed, but the post-await refreshMembers + present(toast) never run.
     */
    fun launchMutation(block: suspend () -> Unit) {
        mutationsScope.launch { block() }
    }

    // Serializes commit-producing FFI calls for the same (account, group) across
    // ChatsController and ConversationController so concurrent mutations don't race
    // the per-account actor and surface PendingPublish as a generic toast.
    private val groupCommitLocks = KeyedMutexPool()
    private val conversationTextSendOrderLocks = KeyedMutexPool()

    /** Runs [block] as the only commit-producing call for this account's group. */
    suspend fun <T> withGroupCommitLock(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> T,
    ): T = groupCommitLocks.withLock("$accountRef|$groupIdHex", block)

    /** The chat list's title for [groupIdHex] while the chat list shows [accountRef], or null when it is not listed. */
    internal fun chatTitle(
        accountRef: String,
        groupIdHex: String,
    ): String? {
        val controller = chatsController?.takeIf { it.accountRef == accountRef }
        val item =
            controller
                ?.let { (it.items + it.archivedItems) }
                ?.firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        return item?.let { chatListItemDisplayTitle(it, this, notificationGroupTitleCopy(appContext)) }
    }

    /** Runs [block] holding this group's commit lock, taking it only when the caller does not hold it already. */
    internal suspend fun <T> withGroupCommitLockHeld(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> T,
    ): T = groupCommitLocks.withLockUnlessHeld("$accountRef|$groupIdHex", block)

    /**
     * Runs [block], which produces no commit, with this group's commit lock released if the caller holds it, so sends
     * and other changes to the group are not held up while the user answers a question.
     */
    internal suspend fun <T> outsideGroupCommitLock(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> T,
    ): T = groupCommitLocks.releasedWhile("$accountRef|$groupIdHex", block)

    suspend fun <T> withConversationTextSendOrder(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> T,
    ): T = conversationTextSendOrderLocks.withLock("$accountRef|$groupIdHex", block)

    private fun pruneIdleGroupCommitLocks() = groupCommitLocks.pruneIdle()

    internal fun optimisticMessages(
        accountRef: String?,
        groupIdHex: String,
    ): SnapshotStateMap<String, TimelineMessage> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            optimisticMessagesByConversation.getOrPut(key) { mutableStateMapOf() }
        }

    internal fun durableAcceptanceCallbacks(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, () -> Unit> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            durableAcceptanceCallbacksByConversation.getOrPut(key) { mutableMapOf() }
        }

    internal fun projectedMessageIds(
        accountRef: String?,
        groupIdHex: String,
    ): MutableSet<String> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            projectedMessageIdsByConversation.getOrPut(key) { mutableSetOf() }
        }

    internal fun timelineOrderOverrides(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, ULong> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            timelineOrderOverridesByConversation.getOrPut(key) { mutableMapOf() }
        }

    internal fun timelineTimestampOverrides(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, ULong> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            timelineTimestampOverridesByConversation.getOrPut(key) { mutableMapOf() }
        }

    internal fun optimisticSendPositionPreserves(
        accountRef: String?,
        groupIdHex: String,
    ): OptimisticSendPositionPreserves =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            optimisticSendPositionPreservesByConversation.getOrPut(key) {
                OptimisticSendPositionPreserves()
            }
        }

    internal fun retentionAtSend(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, ULong> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            retentionAtSendByConversation.getOrPut(key) { mutableMapOf() }
        }

    internal fun acceptedPendingTextOptimisticIds(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, String> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            acceptedPendingTextOptimisticIdsByConversation.getOrPut(key) { mutableMapOf() }
        }

    internal fun optimisticSendPhases(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, OptimisticSendPhase> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            optimisticSendPhasesByConversation.getOrPut(key) { mutableMapOf() }
        }

    /** Shares retry wakeups across controllers for the same account and conversation. */
    internal fun optimisticCancellationGeneration(
        accountRef: String?,
        groupIdHex: String,
    ): MutableStateFlow<Long> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            optimisticCancellationGenerationByConversation.getOrPut(key) { MutableStateFlow(0L) }
        }

    /** Resolves a delivered chat-list projection to the exact accepted-pending optimistic send. */
    internal fun acceptedPendingTextOptimisticId(
        accountRef: String?,
        groupIdHex: String,
        confirmedMessageIdHex: String,
    ): String? =
        synchronized(conversationStateLock) {
            acceptedPendingTextOptimisticIdsByConversation[conversationKey(accountRef, groupIdHex)]
                ?.get(confirmedMessageIdHex)
        }

    /** Returns the account/group retained queue whose removal callback releases private source ownership. */
    internal fun retainedMediaUploads(
        accountRef: String?,
        groupIdHex: String,
    ): dev.ipf.whitenoise.android.media.ByteSizeLruCache<String, RetainedMediaUpload> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            retainedMediaUploadsByConversation.getOrPut(key) {
                dev.ipf.whitenoise.android.media.ByteSizeLruCache(
                    maxBytes = ConversationController.MEDIA_RETAINED_MAX_BYTES,
                    sizeOf = { upload -> upload.attachments.sumOf { it.plaintextBytes.size } },
                    onEntryRemoved = { upload -> upload.releaseSource(::closeStagedUploadSourceOffMain) },
                    // Evicting a send whose items all live in private files frees no heap, only its retry.
                    isEvictable = { upload -> upload.attachments.any { it.inMemoryBytes != null } },
                )
            }
        }

    /** Deletes a released private upload snapshot on the IO pool; unlinking a large file must not block Main. */
    internal fun closeStagedUploadSourceOffMain(source: StagedUploadSource) {
        launchMutation { withContext(Dispatchers.IO) { source.closeQuietly() } }
    }

    internal fun activeUploadKeys(
        accountRef: String?,
        groupIdHex: String,
    ): MutableSet<String> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            activeUploadKeysByConversation.getOrPut(key) { mutableSetOf() }
        }

    /** Captures the account-scoped media-upload lifetime for a suspended send. */
    internal fun mediaUploadSessionEpoch(): Long = mediaUploadSessionLifetime.capture()

    internal suspend fun trackInFlightMediaUpload(
        accountRef: String?,
        groupIdHex: String,
        uploadKey: String,
    ): Job? {
        val context = currentCoroutineContext()
        context.ensureActive()
        val job = context[Job] ?: return null
        inFlightMediaUploads.track(conversationKey(accountRef, groupIdHex), uploadKey, job)
        return job
    }

    /** Removes only the matching upload job, then prunes conversations whose active-source protection has ended. */
    internal fun untrackInFlightMediaUpload(
        accountRef: String?,
        groupIdHex: String,
        uploadKey: String,
        job: Job?,
    ) {
        if (job != null) {
            inFlightMediaUploads.untrack(conversationKey(accountRef, groupIdHex), uploadKey, job)
            synchronized(conversationStateLock) {
                conversationStateRetention.prune(activeConversationStateKey()).forEach(::removeConversationState)
            }
        }
    }

    internal fun pendingProjectionsAwaitingBridge(
        accountRef: String?,
        groupIdHex: String,
    ): MutableMap<String, dev.ipf.marmotkit.TimelineMessageRecordFfi> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            pendingProjectionsAwaitingBridgeByConversation.getOrPut(key) { linkedMapOf() }
        }

    private fun retainConversationState(
        accountRef: String?,
        groupIdHex: String,
    ): String {
        val key = conversationKey(accountRef, groupIdHex)
        conversationStateRetention
            .retain(key, protectedKey = activeConversationStateKey())
            .forEach(::removeConversationState)
        return key
    }

    private fun promoteConversationState(
        accountRef: String?,
        groupIdHex: String,
    ) {
        val key = conversationKey(accountRef, groupIdHex)
        conversationStateRetention
            .promote(key, protectedKey = key)
            .forEach(::removeConversationState)
    }

    /** Drops all cached conversation overlays and clears the retained queue to release its source owners. */
    private fun removeConversationState(staleKey: String) {
        nativeComposerCleanupRevisions.remove(staleKey)
        optimisticMessagesByConversation.remove(staleKey)
        durableAcceptanceCallbacksByConversation.remove(staleKey)?.clear()
        projectedMessageIdsByConversation.remove(staleKey)
        timelineOrderOverridesByConversation.remove(staleKey)
        timelineTimestampOverridesByConversation.remove(staleKey)
        optimisticSendPositionPreservesByConversation.remove(staleKey)
        retentionAtSendByConversation.remove(staleKey)
        acceptedPendingTextOptimisticIdsByConversation.remove(staleKey)
        optimisticSendPhasesByConversation.remove(staleKey)
        optimisticCancellationGenerationByConversation.remove(staleKey)
        retainedMediaUploadsByConversation.remove(staleKey)?.clear()
        activeUploadKeysByConversation.remove(staleKey)
        pendingProjectionsAwaitingBridgeByConversation.remove(staleKey)
    }

    private fun activeConversationStateKey(): String? =
        activeConversationGroupIdHex?.let { groupIdHex ->
            conversationKey(activeConversationAccountRef, groupIdHex)
        }

    private fun conversationKey(
        accountRef: String?,
        groupIdHex: String,
    ): String = "${accountRef.orEmpty()}\u0000$groupIdHex"

    /** The attached chat list's current rows, read to name the worker domain of a durable avatar. */
    internal fun chatListItemsForAvatarDomains(): List<ChatListItem> = chatsController?.items.orEmpty()

    /** The bound chat list's native folder memberships, or null before a chat list exists. */
    internal val chatFolderMembership: ChatFolderMembership? get() = chatsController?.folderMembership

    /** Attaches or permanently detaches the chat-list projection without transferring private row state. */
    fun attachChatsController(controller: ChatsController?) {
        chatsController = controller
        bindDraftSortOrderCallback()
    }

    /** Atomically transfers in-flight previews only for a live shell replacement. */
    internal fun replaceChatsController(
        outgoing: ChatsController,
        replacement: ChatsController,
    ) {
        val handoff = outgoing.takeIf { chatsController === it }?.captureOptimisticPreviewHandoff()
        chatsController = replacement
        handoff?.let { replacement.restoreOptimisticPreviewHandoff(it, activeAccountRef) }
        bindDraftSortOrderCallback()
    }

    /** Routes future draft-order invalidations to the controller that is attached at callback time. */
    private fun bindDraftSortOrderCallback() {
        // Route draft start/clear and coalesced authoritative-time re-sorts to
        // whichever controller is attached; resolve the field at call time so
        // a later re-attach still receives the callback.
        draftStore.onDraftSortOrderChanged = { chatsController?.onDraftSortOrderChanged() }
    }

    fun attachConversationController(controller: ConversationController) {
        synchronized(conversationControllerLock) { conversationControllers.add(controller) }
    }

    fun detachConversationController(controller: ConversationController) {
        synchronized(conversationControllerLock) { conversationControllers.remove(controller) }
    }

    /**
     * Forwards an authoritative chat-list row to every mounted copy of its
     * conversation so classification and details metadata stay live even when
     * the timeline consumes window-only subscription results.
     */
    internal fun publishConversationChatListRow(
        accountRef: String?,
        row: ChatListRowFfi,
    ) {
        val controllers = synchronized(conversationControllerLock) { conversationControllers.toList() }
        controllers.forEach { controller -> controller.applyAuthoritativeChatListRow(accountRef, row) }
    }

    internal val liveSubscriptionOverrides = LiveSubscriptionOverrides()

    internal fun deliverConfirmedMediaHandoff(
        accountRef: String?,
        groupIdHex: String,
        confirmedId: String,
        deferredProjection: TimelineMessageRecordFfi?,
    ): Boolean {
        val controller =
            synchronized(conversationControllerLock) {
                newestMatchingController(conversationControllers) {
                    it.matchesConversation(accountRef, groupIdHex)
                }
            } ?: return false
        return controller.acceptConfirmedMediaHandoff(confirmedId, deferredProjection)
    }

    private fun conversationControllersForAccountTeardown(): List<ConversationController> =
        synchronized(conversationControllerLock) { conversationControllers.toList() }

    private fun destructiveWipeRuntimeState(): DestructiveAccountWipeRuntimeState =
        DestructiveAccountWipeRuntimeState(
            activeAccountRef = activeAccountRef,
            activeConversationAccountRef = activeConversationAccountRef,
            activeConversationGroupIdHex = activeConversationGroupIdHex,
            runtimeGeneration = runtimeGeneration,
        )

    /** Retires observation tickets whenever destructive recovery replaces the runtime generation. */
    private fun applyDestructiveWipeRuntimeState(state: DestructiveAccountWipeRuntimeState) {
        if (runtimeGeneration != state.runtimeGeneration) {
            AvatarImageLoader.clearStoredAvatars()
            diagnostics.observations.reset()
            notificationDelivery.nativePushFallback.invalidateAll()
        }
        activeAccountRef = state.activeAccountRef
        updateNotificationSuppression(
            suppression.copy(
                activeConversationAccountRef = state.activeConversationAccountRef,
                activeConversationGroupIdHex = state.activeConversationGroupIdHex,
            ),
        )
        runtimeGeneration = state.runtimeGeneration
    }

    /** Fences runtime recovery and drains account subscriptions before the destructive engine wipe. */
    private suspend fun prepareForDestructiveAccountWipe(accountRef: String): Boolean =
        accountSession.prepareForAccountTeardown {
            // Fence recovery before setup cancellation suspends; capture controller handles before their drain.
            notificationRuntimeRecovery.advance()
            networkNotificationRecoverySuppressed = true
            val restartNotifications =
                backgroundConnectionEnabled ||
                    notificationJob.isActive() ||
                    notificationNetworkRecovery.isActive() ||
                    pushWakeRecovery.isDrainActive()
            val oldChats = chatsController
            val oldConversations = conversationControllersForAccountTeardown()
            AccountSessionTeardown(
                restartNotifications = restartNotifications,
                invalidateActiveSession = {
                    applyDestructiveWipeRuntimeState(
                        prepareDestructiveAccountWipeRuntimeState(destructiveWipeRuntimeState()),
                    )
                    reloadMediaAutoDownloadMatrix()
                },
                closeControllers = {
                    oldChats?.closeLiveSubscriptionsForAccountTeardown(accountRef)
                    oldConversations.forEach { it.closeLiveSubscriptionsForAccountTeardown(accountRef) }
                },
            )
        }

    /** Restores account and notification state after a wipe fails before commit. */
    private suspend fun restoreAfterFailedDestructiveAccountWipe(
        accountRef: String,
        restartNotifications: Boolean,
    ) {
        applyDestructiveWipeRuntimeState(
            restoreFailedDestructiveAccountWipeRuntimeState(destructiveWipeRuntimeState(), accountRef),
        )
        reloadMediaAutoDownloadMatrix()
        configurePrivacyRuntime()
        diagnostics.refresh(marmot())
        refreshLocalNotificationSettings()
        networkNotificationRecoverySuppressed = false
        if (restartNotifications) startNotificationListener()
        notificationNetworkRecovery.resumeIfPending()
    }

    /** Cancels reconnect producers before stopping the passive notification receiver. */
    internal suspend fun stopNotificationListenerForAccountTeardown() {
        accountSession.stopListenersForAccountTeardown()
    }

    /** Bridges native projection gaps for matching accounts; null retains active-controller routing. */
    fun applyLocalGroupUpdate(
        record: AppGroupRecordFfi,
        accountRef: String? = null,
        clearedName: String? = null,
    ) {
        chatsController.applyLocalGroupUpdateForAccount(record, accountRef, clearedName)
    }

    // Same temporary projection backchannel as [applyLocalGroupUpdate], but for
    // authoritative group-details reads/mutations that also carry the live MLS
    // roster. Keeping the chat-list member snapshot current prevents Start-DM
    // from reusing an abandoned unnamed DM after a member removal (#825).
    fun applyLocalGroupDetails(
        accountRef: String,
        record: AppGroupRecordFfi,
        members: List<AppGroupMemberRecordFfi>,
    ) {
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.applyLocalGroupDetails(record, members)
    }

    /**
     * Publishes a materialized optimistic preview only into the sending account's controller.
     * The row key is a bare group id, so an account-pinned conversation must never write
     * into a different account's row for that group.
     */
    internal fun applyOptimisticSentPreview(
        accountRef: String?,
        groupIdHex: String,
        preview: ChatListMessagePreviewFfi,
    ): Boolean =
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.applyOptimisticSentPreview(groupIdHex, preview) == true

    /** Reserves acceptance-time chat-list order without exposing unparsed Markdown. */
    internal fun reserveOptimisticSentPreview(
        accountRef: String?,
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ): Boolean =
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.reserveOptimisticSentPreview(groupIdHex, optimisticMessageIdHex) == true

    /** Publishes a parsed preview only when its account-scoped reservation still exists. */
    internal fun applyReservedOptimisticSentPreview(
        accountRef: String?,
        groupIdHex: String,
        preview: ChatListMessagePreviewFfi,
    ): Boolean =
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.applyReservedOptimisticSentPreview(groupIdHex, preview) == true

    /** Reconciles an optimistic preview to its confirmed id within the sending account. */
    internal fun commitOptimisticSentPreview(
        accountRef: String?,
        groupIdHex: String,
        optimisticMessageIdHex: String,
        confirmedMessageIdHex: String,
    ) {
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.commitOptimisticSentPreview(groupIdHex, optimisticMessageIdHex, confirmedMessageIdHex)
    }

    internal fun failOptimisticSentPreview(
        accountRef: String?,
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ) {
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.failOptimisticSentPreview(groupIdHex, optimisticMessageIdHex)
    }

    /**
     * Begin a presentation-only chat-list archive intent for a
     * conversation-surface archive/restore, scoped to the bound
     * [ChatsController] account like the other chat-list bridges so an intent
     * from one account cannot land on another controller after a switch.
     */
    internal fun beginChatListArchiveIntent(
        accountRef: String?,
        groupIdHex: String,
        archived: Boolean,
    ): OptimisticArchiveIntent? =
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.beginConversationArchiveIntent(groupIdHex, archived)

    /** Retire [intent] once its engine commit settled; a rebound controller ignores it. */
    internal fun finishChatListArchiveIntent(
        accountRef: String?,
        groupIdHex: String,
        intent: OptimisticArchiveIntent?,
    ) {
        intent ?: return
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.finishConversationArchiveIntent(groupIdHex, intent)
    }

    /**
     * Apply the authoritative chat-list row returned by [markTimelineMessageRead].
     * Scoped to the bound [ChatsController] account so a mark-read on one
     * account cannot fold onto another after a switch. Returns whether the row
     * reached that bound controller — when it did, the fold's recompute also
     * reconciles the acting account's per-account unread aggregate.
     */
    fun applyChatListRowFromMarkRead(
        accountRef: String,
        row: ChatListRowFfi?,
    ): Boolean {
        val projected = row ?: return false
        val boundController = chatsController?.takeIf { it.boundAccountRef == accountRef }
        boundController?.applyChatListRow(projected)
        return boundController != null
    }

    /** Returns only the mounted list bound to this account. */
    internal fun boundChats(owner: String): ChatsController? = chatsController?.takeIf { it.boundAccountRef == owner }

    internal fun rollbackOptimisticSentPreview(
        accountRef: String?,
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ) {
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.rollbackOptimisticSentPreview(groupIdHex, optimisticMessageIdHex)
    }

    // A self-leave stops that group's subscription, so the engine pushes no
    // chat-list update to flip the row to its left state. The chat-list
    // leaveGroup updates its own row state inline, but a leave from the
    // conversation Details screen runs on a different controller; forward the
    // removal here so the active ChatsController flips the row immediately
    // regardless of which surface initiated the leave (issue #767). Scoped to
    // the leaving account so a switch mid-leave can't flip the row on a
    // controller that has since rebound to a different account.
    fun markGroupLeftOnChatList(
        accountRef: String,
        groupIdHex: String,
    ) {
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.markGroupLeft(groupIdHex)
    }

    /** Encrypted no-backup persistence for the single unresolved forward request. */
    internal val forwardRequestPersistence =
        ForwardRequestPersistence(mutationsScope) {
            EncryptedPendingForwardRequestStore.forContext(appContext)
        }

    /** Cancels the visible forward operation while it is still cancellable. */
    internal fun cancelActiveForwardOperation(): Boolean = forwardOperationOwner.cancel()

    /** Retries the visible forward operation's incomplete destinations. */
    internal fun retryActiveForwardOperation(): Boolean = forwardOperationOwner.retry()

    /** Dismisses the terminal forward operation and clears its presentation state. */
    internal fun dismissActiveForwardOperation(): Boolean =
        forwardOperationOwner.dismiss().also { dismissed ->
            if (dismissed) {
                activeForwardDestinationAccountRef = null
                activeForwardTargetTitles = emptyMap()
            }
        }

    /** Publishes Direct Share, then refreshes pinned labels, replacing any earlier publication still in flight. */
    fun publishShareShortcuts(chats: List<ChatListItem>) {
        val accountRef = activeAccountRef ?: return
        val runtime = runtimeGeneration
        val publicationGeneration = PinnedConversationTokens.captureRequest()
        val presentationRevision = pinnedShortcutPresentationRevision.incrementAndGet()
        val titleCopy = notificationGroupTitleCopy(appContext)
        val shareTitles =
            chats
                .filterNot { it.group.pendingConfirmation }
                .take(dev.ipf.whitenoise.android.share.MAX_SHARE_SHORTCUTS)
                .associate { item -> item.group.groupIdHex to chatListItemDisplayTitle(item, this, titleCopy) }
        shareShortcutPublishJob?.cancel()
        shareShortcutPublishJob =
            mutationsScope.launch {
                runCatchingCancellable {
                    withContext(Dispatchers.IO) {
                        shareShortcutPublisher.publish(
                            accountRef,
                            chats,
                            {
                                val ownsRuntime =
                                    isActive && activeAccountRef == accountRef && runtimeGeneration == runtime
                                val ownsPublication =
                                    PinnedConversationTokens.isPublicationCurrent(publicationGeneration)
                                ownsRuntime && ownsPublication
                            },
                        ) { item -> shareTitles[item.group.groupIdHex].orEmpty() }
                    }
                    refreshPinnedConversationPresentation(
                        accountRef,
                        runtime,
                        publicationGeneration,
                        presentationRevision,
                        chats,
                        titleCopy,
                    )
                }.onFailure { failure -> appStateDebug(failure) { "share shortcut publication failed" } }
            }
    }

    /**
     * Decoded avatar pixels change only pin presentation, so they never re-issue the Direct Share inventory;
     * a newer refresh replaces an older one still waiting for the launcher.
     */
    fun refreshPinnedShortcuts(chats: List<ChatListItem>) {
        val accountRef = activeAccountRef ?: return
        val runtime = runtimeGeneration
        val publicationGeneration = PinnedConversationTokens.captureRequest()
        val presentationRevision = pinnedShortcutPresentationRevision.incrementAndGet()
        val titleCopy = notificationGroupTitleCopy(appContext)
        pinnedShortcutRefreshJob?.cancel()
        pinnedShortcutRefreshJob =
            mutationsScope.launch {
                runCatchingCancellable {
                    refreshPinnedConversationPresentation(
                        accountRef,
                        runtime,
                        publicationGeneration,
                        presentationRevision,
                        chats,
                        titleCopy,
                    )
                }.onFailure { failure -> appStateDebug(failure) { "pinned shortcut presentation refresh failed" } }
            }
    }

    /** Resolves private contact pixels at the final launcher write; group-owned pictures retain precedence. */
    internal fun pinnedConversationPresentation(
        account: String,
        item: ChatListItem,
        title: String,
    ): PinnedConversationPresentation {
        val peer =
            GroupProjector
                .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
                ?.takeUnless { GroupProjector.ownsGroupPicture(item) }
        return PinnedConversationPresentation(
            title = title,
            contact = peer,
            currentPicture = { pinnedConversationPicture(account, item, peer) },
        )
    }

    /** Resolve pixels and their opaque selection together; cache eviction cannot impersonate a picture removal. */
    private fun pinnedConversationPicture(
        account: String,
        item: ChatListItem,
        peer: String?,
    ): PinnedConversationPicture {
        val available = accounts.any { it.label == account && !it.signedOut }
        if (!available || appLock.screenVisible || isContactRefBeingCleared(account)) {
            return PinnedConversationPicture(null)
        }
        val privatePeer = peer?.takeIf { contactNicknameAccountRefForAccess(account, accounts, it) != null }
        val privateRef = privatePeer?.let { contactPictureStore.reference(account, it) }
        val publicFallback =
            privatePeer?.takeIf { contactPictureStore.hasChoice(account, it) }?.let {
                synchronized(profilePresentationLock) { profilePresentations[it]?.avatarUrl }
            }
        val sources =
            sequence {
                privateRef?.let { ref ->
                    val read = { AvatarImageLoader.cachedImage(ref.cacheKey)?.asAndroidBitmap() }
                    yield("private:${ref.cacheKey}" to read)
                }
                publicFallback?.let { url -> yield("public:$url" to { AvatarImageLoader.peekBitmap(url) }) }
                val nativeSources =
                    firstFrameGroupAvatarSources(item, account) { contact ->
                        synchronized(profilePresentationLock) { profilePresentations[contact]?.avatarUrl }
                    }
                for ((kind, key) in nativeSources) {
                    val read = { firstFrameGroupAvatarSeed(sequenceOf(kind to key), account)?.image?.asAndroidBitmap() }
                    yield("$kind:$key" to read)
                }
            }
        return dev.ipf.whitenoise.android.notifications.resolvePinnedConversationPicture(
            account,
            AvatarImageLoader.currentCacheLifetime(),
            sources,
        )
    }

    /**
     * Approval may outlive a private-picture edit and the bounded chat projection. Re-read its exact native row;
     * failed lookup, account changes and cold approval leave the scrubbed pin generic until a later refresh.
     * Existing broad jobs finish first; this narrow update never invalidates another pin's queued presentation.
     */
    internal suspend fun refreshApprovedPinnedShortcut(capability: PinnedConversationCapability) {
        val account = capability.accountRef
        val runtime = runtimeGeneration
        if (!canRefreshApprovedPin(account, runtime)) return
        val revision = pinnedShortcutPresentationRevision.get()
        shareShortcutPublishJob?.join()
        pinnedShortcutRefreshJob?.join()
        if (pinnedShortcutPresentationRevision.get() != revision) return
        val item = preloadNotificationChatListItem(account, capability.groupIdHex)
        val available = item.group.selfMembership == SelfMembershipFfi.MEMBER && !item.group.pendingConfirmation
        if (available && canRefreshApprovedPin(account, runtime)) {
            val title = chatListItemDisplayTitle(item, this, notificationGroupTitleCopy(appContext))
            val presentation = pinnedConversationPresentation(account, item, title)
            withContext(Dispatchers.IO) {
                val tokens = PinnedConversationTokens.create(appContext)
                PinnedConversationShortcuts(appContext).refresh(account, mapOf(capability.groupIdHex to presentation)) {
                    isActive &&
                        canRefreshApprovedPin(account, runtime) &&
                        pinnedShortcutPresentationRevision.get() == revision &&
                        tokens.isValid(capability)
                }
            }
        }
    }

    /** A background callback never switches accounts or publishes through an undecided or showing App Lock. */
    private fun canRefreshApprovedPin(
        account: String,
        runtime: Int,
    ): Boolean =
        activeAccountRef == account &&
            runtimeGeneration == runtime &&
            !appLock.screenVisible &&
            !appLock.evaluationPending &&
            accounts.any { it.label == account && it.isSignedInSigningAccount() }

    /** Rebuilds approved pins' labels and cached pixels for one captured account/runtime; no pins means no work. */
    private suspend fun refreshPinnedConversationPresentation(
        accountRef: String,
        runtime: Int,
        publicationGeneration: Long,
        presentationRevision: Long,
        chats: List<ChatListItem>,
        titleCopy: dev.ipf.whitenoise.android.core.GroupTitleCopy,
    ) {
        val shortcuts = withContext(Dispatchers.IO) { PinnedConversationShortcuts(appContext) }
        val hasPins = withContext(Dispatchers.IO) { shortcuts.hasPinnedConversations(accountRef) }
        val ownsRuntime =
            currentCoroutineContext().isActive && activeAccountRef == accountRef && runtimeGeneration == runtime
        val ownsPublication =
            withContext(Dispatchers.IO) { PinnedConversationTokens.isPublicationCurrent(publicationGeneration) }
        if (!hasPins || !ownsRuntime || !ownsPublication) return
        if (pinnedShortcutPresentationRevision.get() != presentationRevision) return
        val presentations =
            chats.associate { item ->
                item.group.groupIdHex.lowercase(Locale.ROOT) to
                    pinnedConversationPresentation(accountRef, item, chatListItemDisplayTitle(item, this, titleCopy))
            }
        withContext(Dispatchers.IO) {
            shortcuts.refresh(accountRef, presentations) {
                isActive &&
                    activeAccountRef == accountRef &&
                    runtimeGeneration == runtime &&
                    pinnedShortcutPresentationRevision.get() == presentationRevision
            }
        }
    }

    /**
     * Start one ordered text/media forward operation with explicit account
     * ownership. Source attachments are materialized through their original
     * group only under [sourceAccountRef]; destination upload, commit
     * locking, publish, convergence recovery, and retries run only under
     * [destinationAccountRef]. Switching the globally active account cannot
     * redirect either side: neither boundary ever re-reads the live active
     * account. Each boundary instead revalidates that its own bound account
     * is still a signed-in signing account, so removal or sign-out of either
     * owner stops the operation without falling back to another account.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    internal fun startForwardMessages(
        targetGroupIds: List<String>,
        messages: List<ForwardMessagePayload>,
        sourceAccountRef: String? = activeAccountRef,
        destinationAccountRef: String? = sourceAccountRef,
        targetTitles: Map<String, String> = emptyMap(),
    ): Boolean {
        val sourceGroupIds =
            messages
                .map(ForwardMessagePayload::sourceGroupIdHex)
                .filter(String::isNotBlank)
        val targets =
            MessageProjector
                .normalizeForwardTargets(targetGroupIds)
                .filterNot { target -> sourceGroupIds.any { it.equals(target, ignoreCase = true) } }
        val sourceAccount = sourceAccountRef?.takeIf(String::isNotBlank)?.takeIf { isForwardOwnerSignedIn(it) }
        val account = destinationAccountRef?.takeIf(String::isNotBlank)?.takeIf { isForwardOwnerSignedIn(it) }
        val startable =
            sourceAccount != null && account != null && messages.isNotEmpty() && targets.isNotEmpty()
        if (!startable || sourceAccount == null || account == null) return false
        val diagnostics = ForwardDiagnostics.begin()
        val transport = forwardTransport(sourceAccount, account, diagnostics)
        val session =
            ForwardSession(
                scope = mutationsScope,
                messages = messages,
                targetGroupIds = targets,
                transport = transport,
                onFailure = { _, stage, throwable ->
                    if (BuildConfig.DEBUG) Log.w("DMMessageForward", "forward failed stage=$stage", throwable)
                },
            )
        // The app-scoped owner mirrors live per-target state into the global
        // non-modal activity strip and retains terminal failures for explicit
        // retry/dismiss. Its bounded retries preserve the same safe recovery
        // boundary as the session: uncertain publishes converge, never resend.
        // Main.immediate may deliver terminal completion inside start, so install its trace first.
        // A rejected start still belongs to the previous operation and must retain that trace.
        val previousDiagnostics = activeForwardDiagnostics
        activeForwardDiagnostics = diagnostics
        val started = forwardOperationOwner.start(session)
        if (!started) {
            activeForwardDiagnostics = previousDiagnostics
            session.release()
        } else {
            activeForwardDestinationAccountRef = account
            activeForwardTargetTitles = targetTitles
        }
        return started
    }

    /**
     * Compose-tracked snapshot of the active account's projected chat list.
     * Reads the controller's observable `items`, which is reassigned by every
     * projection rebuild ([ChatsController.recompute]) — i.e. whenever the
     * group set, per-group membership snapshots, or group names change.
     *
     * Intended as a `remember(...)` invalidation key for derivations of the
     * group set (e.g. the profile sheet's shared-groups list): subscribing to
     * it re-fires the derivation exactly when the underlying groups change, and
     * — because it does *not* bump on peer-profile (avatar/display-name)
     * resolution — leaves those unrelated recompositions memoized. Empty when no
     * chats controller is attached yet (the chat-list stream hasn't bound).
     */
    val chatListItems: List<ChatListItem>
        get() = chatsController?.items.orEmpty()

    /** Archived counterpart to [chatListItems], from the same recompute. */
    val archivedChatListItems: List<ChatListItem>
        get() = chatsController?.archivedItems.orEmpty()

    fun existingDirectChat(reference: String): ChatListItem? = chatsController?.existingDirectChat(reference)

    /** Revalidates picker provenance through the attached native-backed controller and keeps trace attribution. */
    internal suspend fun resolveProvenanceDirectChat(
        provenanceGroupIdHex: String?,
        targetReference: String,
        diagnosticAttempt: DmCreationAttempt? = null,
    ): NewMessageDirectChatResolution =
        chatsController?.resolveProvenanceDirectChat(provenanceGroupIdHex, targetReference, diagnosticAttempt)
            ?: NewMessageDirectChatResolution(item = null, createRequired = false)

    /** Performs the authoritative existing-DM lookup; a missing controller remains unavailable, never a create miss. */
    internal suspend fun resolveExistingDirectChat(
        targetReference: String,
        excludingGroupIdHex: String? = null,
        diagnosticAttempt: DmCreationAttempt? = null,
    ): NewMessageDirectChatResolution =
        chatsController?.resolveExistingDirectChat(targetReference, excludingGroupIdHex, diagnosticAttempt)
            ?: NewMessageDirectChatResolution(item = null, createRequired = false)

    private val marmotBridgeTracer = MarmotBridgeTracer()

    suspend fun <T> marmotIo(block: suspend MarmotInterface.() -> T): T =
        withContext(marmotIoDispatcher) {
            marmot().block()
        }

    /** Measures one bridge attempt only if it began under the current analytics permission. */
    suspend fun <T> marmotIo(
        traceSection: String,
        block: suspend MarmotInterface.() -> T,
    ): T {
        val ticket = diagnostics.observations.ticket()
        return withContext(marmotIoDispatcher) {
            val runtime = marmot()
            marmotBridgeTracer.trace(
                traceSection,
                recordTiming = { name, durationMs, outcome ->
                    diagnostics.observations.record(ticket) {
                        runtime.recordHostTiming(name, durationMs.toULong(), outcome)
                    }
                },
            ) { runtime.block() }
        }
    }

    /**
     * Memoize an in-flight attachment download keyed on [cacheKey] and route
     * the work through [mutationsScope] so it survives caller cancellation
     * (e.g. the user tapped a file and swiped away). Concurrent callers for
     * the same key share the same Deferred; the entry is dropped when the
     * Deferred completes so a later retry can re-attempt. Recheck both cache
     * tiers after admission: the caller's earlier miss can become stale while
     * another owner publishes or this request waits for a permit.
     */
    internal fun memoizedDownload(
        cacheKey: String,
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
        block: suspend CoroutineScope.() -> ByteArray,
    ): Deferred<ByteArray> {
        synchronized(inFlightDownloadsLock) {
            inFlightDownloads[cacheKey]?.takeIf { it.isActive }?.let { active ->
                if (priority == AttachmentDownloadPriority.Interactive) {
                    attachmentDownloadGate.promote(cacheKey)
                }
                return active
            }
            val deferred =
                mutationsScope.async {
                    // Cap concurrent attachment fetches so an N-tile album
                    // doesn't saturate the underlying network or Blossom
                    // stack. MDK already walks every eligible locator with its
                    // own timeout; repeating that complete operation here made
                    // one bad endpoint look like a multi-minute spinner. A
                    // durable worker may perform one later, backed-off attempt.
                    // The gate is acquired inside the Deferred so callers only
                    // suspend at `await()`.
                    val downloadScope = this
                    attachmentDownloadGate.withPermit(
                        key = cacheKey,
                        accountRef = request.accountRef,
                        priority = priority,
                    ) {
                        cachedMediaPlaintext(cacheKey)
                            ?: withContext(Dispatchers.IO) { diskMediaCache.get(cacheKey) }
                                ?.also { cacheMediaPlaintext(cacheKey, it) }
                            // Publication can also win while the disk lookup
                            // is suspended. L1 remains confined to this scope.
                            ?: cachedMediaPlaintext(cacheKey)
                            ?: downloadScope.block()
                    }
                }
            inFlightDownloads[cacheKey] = deferred
            // Drop the map entry via `invokeOnCompletion` (fires AFTER the
            // Deferred has transitioned to completed/cancelled — a `finally`
            // inside `async`'s body races against that transition and can
            // observe `isCompleted == false`, leaking the entry. A completed
            // Deferred<ByteArray> retains the plaintext result, so a leaked
            // entry keeps the bytes alive forever). Identity check ensures
            // a concurrent retry that registered a fresh Deferred under the
            // same key survives.
            deferred.invokeOnCompletion {
                synchronized(inFlightDownloadsLock) {
                    if (inFlightDownloads[cacheKey] === deferred) {
                        inFlightDownloads.remove(cacheKey)
                    }
                }
            }
            return deferred
        }
    }

    /**
     * Chooses and owns exactly one network path before any native or legacy acquisition starts. A later explicit tap
     * joins an automatic owner and promotes it instead of starting MarmotKit beside the already-running legacy
     * download. A [foreground] request, one a visible bubble made, queues ahead of background automatic work.
     */
    internal fun memoizedAttachmentAcquisition(
        cacheKey: String,
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
        foreground: Boolean = false,
        block: suspend CoroutineScope.() -> AttachmentAcquisitionOutcome,
    ): Deferred<AttachmentAcquisitionOutcome> =
        inFlightAttachmentAcquisitions.acquire(cacheKey, priority, foreground) {
            val owner = this
            val queueAttempt = beginHostPerformance(HostPerformanceOperationFfi.MEDIA_QUEUE_WAIT)
            try {
                attachmentDownloadGate.withPermit(cacheKey, request.accountRef, priority, foreground) {
                    queueAttempt.success()
                    val cached =
                        cachedMediaPlaintext(cacheKey)
                            ?: withContext(Dispatchers.IO) { diskMediaCache.getIfSmall(cacheKey) }
                            ?: cachedMediaPlaintext(cacheKey)
                    cached?.let(AttachmentAcquisitionOutcome::LegacyBytes) ?: owner.block()
                }
            } catch (timeout: TimeoutCancellationException) {
                queueAttempt.timeout()
                throw timeout
            } catch (cancellation: CancellationException) {
                queueAttempt.cancel()
                throw cancellation
            } catch (throwable: Throwable) {
                queueAttempt.failure()
                throw throwable
            } finally {
                queueAttempt.unavailable()
            }
        }

    /** Exposes only active owner lifetime, never a second retained attachment record. */
    @Suppress("MaxLineLength") // Kept as an expression body by ktlint's formatter.
    internal fun hasActiveAttachmentAcquisition(cacheKey: String): Boolean = inFlightAttachmentAcquisitions.isActive(cacheKey)

    /** Promotes a queued owner and reports whether native work is already admitted for that identity. */
    internal fun promoteAdmittedAttachmentAcquisition(cacheKey: String): Boolean {
        attachmentDownloadGate.promote(cacheKey)
        return attachmentDownloadGate.isAdmitted(cacheKey)
    }

    /**
     * Cancels one account-scoped memoized source attempt after its forwarding
     * owner times out or is cancelled. The identity-safe completion hook leaves
     * any newer retry registered under the same cache key intact.
     */
    internal fun cancelMemoizedAttachmentDownload(request: AttachmentTransferRequest): Boolean {
        val cacheKey =
            mediaCacheKey(
                request.accountRef,
                request.groupIdHex,
                request.messageIdHex,
                request.attachmentIndex,
            )
        val active =
            synchronized(inFlightDownloadsLock) {
                inFlightDownloads[cacheKey]?.takeIf { it.isActive }
            }
        active?.cancel(CancellationException("forward attachment preparation stopped"))
        return active != null
    }

    /**
     * Resolve one attachment from MDK's authoritative media projection. This is
     * intentionally the only recovery path used by durable Android work: the
     * WorkManager request stores identity, never a duplicate media reference.
     */
    @Suppress("MaxLineLength") // Kept as an expression body by ktlint's formatter.
    internal suspend fun resolveAttachmentReference(request: AttachmentTransferRequest): MediaAttachmentReferenceFfi? = findNativeAttachment(request)?.reference

    /**
     * Persists durable work and promotes explicit requests above automatic-download policy. Automatic work waits
     * for [networkType], the narrowest network its policy could allow it on, and [onAutomaticNotQueued] runs when
     * automatic work queues nothing, so a receipt can release its claim and be scheduled again.
     */
    internal fun enqueueAttachmentDownload(
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority = AttachmentDownloadPriority.Automatic,
        networkType: NetworkType = NetworkType.CONNECTED,
        type: MediaAutoDownloadType? = null,
        onAutomaticNotQueued: (() -> Unit)? = null,
    ) {
        if (priority == AttachmentDownloadPriority.Interactive) {
            attachmentDownloadIntents.setInteractive(request, interactive = true)
            attachmentDownloadGate.promote(request.cacheKey())
        } else if (attachmentDownloadIntents.isAutomaticSuppressed(request)) {
            onAutomaticNotQueued?.invoke()
            return
        }
        AttachmentDownloadWorker.enqueue(
            appContext,
            request,
            priority,
            appInForeground,
            networkType,
            type,
            onAutomaticNotQueued,
        )
    }

    /** Clears a completed foreground request without changing its automatic-download policy. */
    internal fun clearInteractiveAttachmentDownloadIntent(request: AttachmentTransferRequest) {
        attachmentDownloadIntents.setInteractive(request, interactive = false)
    }

    /** Bridges scheduler registration until its first observable work state. */
    internal fun hasInteractive(request: AttachmentTransferRequest) = attachmentDownloadIntents.isInteractive(request)

    /**
     * Revokes one attachment's durable download so a cancel survives both a
     * worker retry and process death, and so recreating the card cannot let the
     * automatic policy restart what the user just stopped.
     */
    internal fun cancelAttachmentDownload(
        request: AttachmentTransferRequest,
        onNativeResult: (Boolean) -> Unit = {},
    ) {
        attachmentDownloadIntents.suppressAutomatic(request)
        attachmentDownloadIntents.setInteractive(request, interactive = false)
        AttachmentDownloadWorker.cancelForRequest(appContext, request)
        cancelMemoizedAttachmentDownload(request)
        inFlightAttachmentAcquisitions.cancel(request.cacheKey(), AttachmentTransferCancelledByUserException())
        attachmentUserActions.cancel(request.cacheKey(), { cancelNativeAttachmentBounded(request) }, onNativeResult)
        attachmentDownloadPolicyRevision += 1
    }

    /** Only an accepted fresh Retry clears cancellation suppression and permits viewer/platform delivery. */
    internal fun retryAttachmentDownload(
        request: AttachmentTransferRequest,
        onAccepted: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean =
        attachmentUserActions.retry(
            key = request.cacheKey(),
            admit = { requestAttachmentRetry(request) },
            onAccepted = {
                attachmentDownloadIntents.restoreAutomatic(request)
                attachmentDownloadPolicyRevision += 1
                onAccepted()
            },
            onFailure = onFailure,
        )

    /** True while the user's cancel of this exact attachment still blocks automatic work. */
    internal fun automaticAttachmentDownloadSuppressed(request: AttachmentTransferRequest): Boolean {
        attachmentDownloadPolicyRevision
        return attachmentDownloadIntents.isAutomaticSuppressed(request)
    }

    fun automaticAttachmentDownloadsPaused(): Boolean {
        attachmentDownloadPolicyRevision
        return activeAccountRef?.let(attachmentDownloadIntents::isAutomaticPaused) == true
    }

    fun stopAutomaticAttachmentDownloads() {
        val accountRef = activeAccountRef ?: return
        attachmentDownloadIntents.pauseAutomatic(accountRef)
        refreshNativeAttachmentPermissions()
        attachmentDownloadPolicyRevision += 1
        attachmentDownloadGate.cancelQueuedAutomatic(accountRef)
        mutationsScope.launch {
            AttachmentDownloadWorker.cancelQueuedAutomatic(appContext, accountRef)
        }
    }

    fun restartAutomaticAttachmentDownloads() {
        val accountRef = activeAccountRef ?: return
        attachmentDownloadIntents.restartAutomatic(accountRef)
        refreshNativeAttachmentPermissions()
        // Resuming the backlog is a resume-everything signal, and it is the one
        // point where per-file cancel records can be dropped in bulk instead of
        // accumulating for attachments the user never opens again.
        attachmentDownloadIntents.clearSuppressedAutomatic(accountRef)
        attachmentDownloadPolicyRevision += 1
    }

    /** Checks host retention without crossing MDK or hydrating a plaintext payload. */
    internal suspend fun hasHostCachedAttachmentAfterHydration(request: AttachmentTransferRequest): Boolean =
        resolveAttachmentCacheAvailability(
            cacheKey = request.run { mediaCacheKey(accountRef, groupIdHex, messageIdHex, attachmentIndex) },
            memoryContains = { cachedMediaPlaintext(it) != null },
            diskContains = { key ->
                outgoingAttachmentCachePublications.await(key)
                diskMediaCache.containsAfterHydration(key)
            },
        )

    /** Native retention bypasses a pending own-file host copy; other probes keep the usual host-first order. */
    internal suspend fun hasCachedAttachmentAfterHydration(request: AttachmentTransferRequest): Boolean {
        val key = request.run { mediaCacheKey(accountRef, groupIdHex, messageIdHex, attachmentIndex) }
        return if (outgoingAttachmentCachePublications.isPending(key)) {
            hasNativeAttachment(request) || hasHostCachedAttachmentAfterHydration(request)
        } else {
            hasHostCachedAttachmentAfterHydration(request) || hasNativeAttachment(request)
        }
    }

    /**
     * Ensures durable work consumes large cache hits as leases instead of full heap copies. Automatic work that a
     * bubble already made local finishes at once. Otherwise it is held to the live policy for its type, since the
     * network or settings may have changed since it was queued, and waits for the native runtime to hold that
     * approval (#3308).
     */
    internal suspend fun downloadAttachmentForDurableWork(
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
    ): Boolean {
        val automatic = priority == AttachmentDownloadPriority.Automatic
        if (automatic) {
            if (request.sourceMessageIdHex != null && hasCachedAttachmentAfterHydration(request)) return true
            // Account-wide holds are decided before the history lookup the type check needs.
            automaticAttachmentPolicy(request.accountRef).accountHold()?.let { hold ->
                throw AttachmentAutomaticPolicyDeferredException(type = null, hold = hold)
            }
        }
        val match = findNativeAttachment(request) ?: throw AttachmentReferenceNotReadyException()
        val resolved = request.copy(sourceMessageIdHex = match.target.sourceMessageIdHex)
        val download =
            suspend {
                downloadAttachmentPlaintextSource(
                    request = resolved,
                    reference = match.reference,
                    priority = priority,
                    demandIntent = AttachmentDemandIntent.Observe,
                ).use { }
            }
        if (automatic) {
            downloadAutomaticAttachment(resolved.accountRef, match.category.autoDownloadType(), download)
        } else {
            download()
        }
        return hasCachedAttachmentAfterHydration(resolved)
    }

    /**
     * Runs automatic [download] once the live policy allows [type] and the native runtime holds that approval. A
     * network change revokes approval before re-evaluating it, and a request that lands in that window is
     * refused without recording demand, so a refusal is asked again once the host's approval has moved on. A
     * refusal under an unchanged approval that allows the type is MDK's own final answer, such as a size cap,
     * and ends the work. A transfer MDK keeps paused, usually because its type was turned off mid-download, is
     * re-admitted the same way after it frees its download slot.
     */
    private suspend fun downloadAutomaticAttachment(
        accountRef: String,
        type: MediaAutoDownloadType?,
        download: suspend () -> Unit,
    ) {
        repeat(AUTOMATIC_ADMISSION_ATTEMPTS) {
            val revision = awaitAutomaticAttachmentAdmission(accountRef, type)
            try {
                download()
                return
            } catch (blocked: NativeAttachmentTerminalException) {
                val approvalUnchanged = nativeAttachmentPermissions.appliedRevision(accountRef) == revision
                if (blocked.state != AttachmentTransferStateFfi.POLICY_BLOCKED || approvalUnchanged) throw blocked
            } catch (_: NativeAttachmentPausedException) {
                Log.i(INCOMING_ATTACHMENT_TAG, "attachment_work_paused type=${type?.preferenceKey ?: "unknown"}")
            }
        }
        throw AttachmentPermissionPendingException()
    }

    /**
     * Holds automatic work to the live policy for [type], then waits until the native runtime holds an approval
     * that allows [type], and returns that approval's host revision. Live inputs can change before their refresh
     * runs, so an approval that was merely applied could still be the previous network's denial. An approval
     * that never lands asks for a fresh one before the work retries. A network hold carries the narrowest network
     * the policy could allow [type] on, so the worker can wait for it in WorkManager.
     */
    private suspend fun awaitAutomaticAttachmentAdmission(
        accountRef: String,
        type: MediaAutoDownloadType?,
    ): Long {
        val policy = automaticAttachmentPolicy(accountRef)
        val hold = if (type == null) AutomaticAttachmentHold.Disabled else policy.hold(type)
        if (hold != null || type == null) {
            throw AttachmentAutomaticPolicyDeferredException(
                type = type,
                hold = hold ?: AutomaticAttachmentHold.Disabled,
                networkType = type?.let(policy::networkType) ?: NetworkType.CONNECTED,
            )
        }
        return withTimeoutOrNull(NATIVE_ATTACHMENT_PERMISSION_SETTLE_MILLIS) {
            nativeAttachmentPermissions.awaitApplied(accountRef) { it.allows(type) }
        } ?: run {
            // A refresh still applying under the latest revision is left to land: starting another would revoke and
            // supersede it, and every album worker that timed out together would keep superseding the others.
            refreshAfterSettleTimeout()
            throw AttachmentPermissionPendingException()
        }
    }

    /**
     * Bootstrap belongs to the process, not the first composition that awaited
     * it. A caller gets an actionable timeout while the single underlying
     * attempt remains alive; retry re-awaits that attempt instead of starting a
     * second Marmot runtime beside a blocked native call.
     */
    suspend fun bootstrap() {
        val attempt = accountSession.bootstrapAttempt { bootstrapLocked() }
        val completed = awaitBootstrapAttempt(attempt, bootstrapActionableTimeoutMillis())
        if (!completed) {
            withContext(Dispatchers.Main.immediate) {
                if (attempt.isActive && phase == AppPhase.Bootstrapping) {
                    phase =
                        AppPhase.Failed(
                            privacySafeErrorPresentation(
                                operationCode = "APP_BOOTSTRAP_TIMEOUT",
                                throwable = IllegalStateException("bootstrap stage exceeded deadline"),
                                message = AppText.Resource(R.string.startup_taking_too_long),
                                startupDetail = StartupDiagnostics.timeoutDetail(),
                            ),
                        )
                }
            }
        }
    }

    /**
     * Retries bootstrap after an actionable failure was shown to the user.
     *
     * Only this explicit UI action restores the loading surface. Process and
     * background callers use [bootstrap], so attaching to the same in-flight
     * attempt cannot hide an error that the user can act on.
     */
    suspend fun retryBootstrap() {
        withContext(Dispatchers.Main.immediate) {
            if (phase is AppPhase.Failed) phase = AppPhase.Bootstrapping
        }
        bootstrap()
    }

    /** Owns one bootstrap through native start and local activation; diagnostic observations never retry it. */
    private suspend fun bootstrapLocked() {
        try {
            if (resumeCompletedBootstrap()) return
            StartupDiagnostics.beginAttempt()
            startupPerformance.stage(PerformancePhase.NOTIFICATION_PLATFORM_SETUP) {
                // Tray readiness must precede Marmot.start(): the passive MDK
                // broadcast has no replay once the startup receiver attaches.
                withContext(Dispatchers.Default) {
                    localNotificationPresenter.ensureChannels()
                }
                refreshLocalNotificationPermission()
            }
            startBootstrapRuntime()
            val refreshedAccounts = startupPerformance.stage(PerformancePhase.ACCOUNT_REFRESH, ::refreshAccountSnapshot)
            restoreStartupLocalState(refreshedAccounts)
            // Resolve interrupted editor commits against MDK before any draft
            // is reopened, and reclaim encrypted sources with no live session.
            startupPerformance.stage(PerformancePhase.DRAFT_RECONCILIATION) {
                messageDraftRepository.reconcileEditorState(editorSourceStore).onFailure {
                    appStateDebug(it) { "photo editor reconciliation deferred: ${it.readableMessage()}" }
                }
            }
            appStateDebug {
                "accounts loaded count=${accounts.size} active=$activeAccountRef labels=${accounts.map { it.label.take(8) to it.running }}"
            }
            if (accounts.isNotEmpty()) {
                // Retained Amber profiles still need their callbacks when the user later chooses Continue.
                startupPerformance.stage(PerformancePhase.EXTERNAL_SIGNER_REGISTRATION) { reregisterExternalSigners() }
            }
            val targetAccount = startupAccount()
            if (targetAccount == null) {
                completeStartupWithoutAccount()
            } else {
                val target = targetAccount.label
                val activated =
                    startupPerformance.stage(PerformancePhase.ACCOUNT_ACTIVATION) {
                        // The callback is the local-ready boundary. The shell mounts before
                        // profile/notification/push warmup and the cross-account unread fold.
                        setActiveAccount(
                            label = target,
                            deferUnreadRefresh = true,
                            preloadPolicy = AccountSwitchPreloadPolicy.STARTUP_RESTORATION,
                            awaitPostActivationWork = { completeReceiverGatedStartup() },
                            onActivated = { phase = AppPhase.Ready },
                        )
                    }
                check(activated || accountSetup.controller != null) { "startup account activation did not complete" }
            }
            bootstrapCompleted = true
            scheduleDisplayThumbnailReconciliation()
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // The process-owned listener keeps retrying independently, but
                // a cancelled UI bootstrap must not strand the splash forever.
                phase = AppPhase.Failed(bootstrapFailure(error))
                throw error
            }
            appStateDebug(error) { "bootstrap failed: ${error.readableMessage()}" }
            phase = AppPhase.Failed(bootstrapFailure(error))
        }
    }

    private suspend fun completeStartupWithoutAccount() {
        val startupRuntime = marmotRuntime
        val startupEpoch = runtimeGeneration
        val startupSelection = accountSession.handoff.capture()
        completeReceiverGatedStartup()
        withAccountLifetime {
            if (marmotRuntime !== startupRuntime ||
                runtimeGeneration != startupEpoch ||
                !accountSession.handoff.isCurrent(startupSelection)
            ) {
                return@withAccountLifetime
            }
            activeAccountRef = null
            preferences.edit().remove(ACTIVE_ACCOUNT_KEY).apply()
            notificationDelivery.localNotificationSettings = null
            pendingStartupUnreadRefresh = null
            phase = AppPhase.Onboarding
        }
    }

    /** Observes existing local preference restoration without placing native queries in the diagnostic recorder. */
    private suspend fun restoreStartupLocalState(accounts: List<AccountSummaryFfi>) =
        StartupDiagnostics.stage(StartupDiagnosticStage.LOCAL_RESTORATION) {
            schedulePendingLocalGroupDeleteCleanup()
            prepareStartupUnreadRefresh(accounts)
            migrateLegacyDrafts()
            migrateLegacyMutePreferences()
        }

    /** Adds host stage attribution after an exception has unwound without retaining its sensitive text. */
    private fun bootstrapFailure(error: Throwable): ErrorPresentation =
        privacySafeErrorPresentation("APP_BOOTSTRAP", error, startupDetail = StartupDiagnostics.failureDetail())

    /** Restores only a native signed-in profile; retained signed-out identities require an explicit selection. */
    private fun startupAccount(): AccountSummaryFfi? =
        accounts.firstOrNull { it.label == activeAccountRef && !it.signedOut }
            ?: accounts.firstOrNull { !it.signedOut }

    /**
     * Starts the process-owned listener with Marmot while deferring only its bounded readiness wait until a safe
     * local shell can render. Notification-sensitive reads remain behind that barrier and signer restoration.
     */
    private suspend fun completeReceiverGatedStartup() {
        val receiverReady =
            StartupDiagnostics.stage(StartupDiagnosticStage.NOTIFICATION_RECEIVER) {
                awaitNotificationReceiverForStartupWithin(notificationReceiverTimeoutMillis())
            }
        appStateDebug { "marmot started; notification receiver active=$receiverReady" }
        startupPerformance.stage(PerformancePhase.NOTIFICATION_PRIVACY_SETUP) { refreshSecurityPrivacySettings() }
    }

    /** Binds the consent projection before configuring and starting the process-owned native runtime. */
    @Suppress("LongMethod", "ThrowsCount") // Native startup stages share one terminal telemetry boundary.
    private suspend fun startBootstrapRuntime(): AppMarmotRuntime =
        withAccountLifetime {
            startBootstrapRuntimeLocked()
        }

    private suspend fun startBootstrapRuntimeLocked(): AppMarmotRuntime {
        val startedAtElapsedMs = SystemClock.elapsedRealtime()
        return try {
            val opened = openBootstrapRuntimeLocked()
            publishMarmotRuntime(opened)
            recordHostPerformanceSince(HostPerformanceOperationFfi.RUNTIME_INIT, startedAtElapsedMs)
            opened
        } catch (error: Throwable) {
            val outcome = bootstrapFailureOutcome(error)
            recordHostPerformanceSince(HostPerformanceOperationFfi.RUNTIME_INIT, startedAtElapsedMs, outcome)
            throw error
        }
    }

    private fun bootstrapFailureOutcome(error: Throwable): HostPerformanceOutcomeFfi =
        when (error) {
            is TimeoutCancellationException -> HostPerformanceOutcomeFfi.TIMEOUT
            is CancellationException -> HostPerformanceOutcomeFfi.CANCELLED
            else -> HostPerformanceOutcomeFfi.FAILURE
        }

    private suspend fun openBootstrapRuntimeLocked(): AppMarmotRuntime =
        bootstrapRuntime.open(
            construct = {
                startupPerformance.stage(PerformancePhase.CLIENT_CONSTRUCTION) {
                    withContext(Dispatchers.IO) {
                        StartupDiagnostics.stage(StartupDiagnosticStage.ROOT_LEASE) {
                            MarmotClientRootGate.withLease {
                                marmotRuntimeFactory(appContext).also { runtime ->
                                    // Publish before start so lifecycle consumers
                                    // and later listener retries can resolve Marmot.
                                    publishMarmotRuntime(runtime)
                                    diagnostics.bind(runtime.marmot)
                                    AvatarImageLoader.attachProfileImageFetcher { url, maxBytes ->
                                        runtime.marmot.downloadProfileImage(url, maxBytes)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            configure = { runtime ->
                appStateDebug { "bootstrap root=${runtime.rootPath}" }
                startupPerformance.stage(PerformancePhase.PRIVACY_RUNTIME_CONFIGURATION) {
                    withContext(Dispatchers.IO) {
                        runtime.marmot.configurePrivacyRuntime()
                        runtime.marmot.enforceAppOwnedAttachmentAcquisitionForKnownAccounts()
                    }
                }
            },
            start = { runtime ->
                startMarmotWithNotificationListener(runtime)
                appStateDebug { "marmot started" }
            },
            closeAfterFailure = { runtime ->
                startupPerformance.stage(PerformancePhase.FAILED_RUNTIME_CLOSE) {
                    notificationDelivery.notificationDeliveryModeMutex.withLock {
                        notificationDelivery.nativePushSyncMutex.withLock {
                            withContext(Dispatchers.IO) { runtime.marmot.shutdownAndClose() }
                            clearMarmotRuntime(runtime)
                        }
                    }
                }
            },
        )

    /**
     * Start MDK and enter its passive subscription from one IO coroutine. The
     * slot only sees an enqueued job, so a synchronous native subscribe cannot
     * block its lock; once start returns, no second dispatcher hop can widen
     * the no-replay boundary before the subscription call begins.
     */
    @Suppress("TooGenericExceptionCaught") // The startup boundary reports every non-cancellation runtime failure.
    private suspend fun startMarmotWithNotificationListener(runtime: AppMarmotRuntime) {
        val runtimeStartResult = CompletableDeferred<Result<Unit>>()
        var installedStartupListener = false
        val listenerJob =
            notificationJob.currentOrStart {
                installedStartupListener = true
                notificationScope.launch(notificationDispatcher) {
                    try {
                        // Opened before start, so no message delivered once the runtime starts can precede the
                        // receipt subscription; the receipt loop the listener starts takes it over.
                        openStartupReceiptSubscription(runtime.marmot)
                        startupPerformance.stage(PerformancePhase.MARMOT_START) { runtime.marmot.start() }
                        runtimeStartResult.complete(Result.success(Unit))
                        runNotificationListenerLoop(runtime.marmot)
                    } catch (cancel: CancellationException) {
                        runtimeStartResult.complete(Result.failure(cancel))
                        throw cancel
                    } catch (failure: Throwable) {
                        runtimeStartResult.complete(Result.failure(failure))
                    } finally {
                        takeStartupReceiptSubscription(runtime.marmot)?.let { runCatching { it.destroy() } }
                    }
                }
            }
        check(listenerJob != null && installedStartupListener) {
            "notification listener unavailable before Marmot startup"
        }
        runtimeStartResult.await().getOrThrowAtStartupStage(BootstrapStage.RUNTIME_START)
        StartupDiagnostics.stage(StartupDiagnosticStage.POST_START_READINESS) {
            runtime.marmot.emitAuditRuntimeReadinessAfterStart(auditUploadConsent.readyForStartupMarker)
            runtimeMirrors.attention.start(this, runtime.marmot)
        }
    }

    private suspend fun resumeCompletedBootstrap(): Boolean {
        if (!bootstrapCompleted) return false
        schedulePendingLocalGroupDeleteCleanup()
        if (accounts.isNotEmpty() && activeAccountRef != null) phase = AppPhase.Ready
        val receiverReady = awaitNotificationReceiverForStartupWithin(notificationReceiverTimeoutMillis())
        appStateDebug { "bootstrap resumed; notification receiver active=$receiverReady" }
        if (accounts.isEmpty()) phase = AppPhase.Onboarding
        return true
    }

    private fun receiverUnavailable() = IllegalStateException("notification receiver unavailable during bootstrap")

    /** Await the process receiver within a caller-specific bounded budget. */
    private suspend fun awaitNotificationReceiverForStartupWithin(timeoutMillis: Long): Boolean {
        if (networkNotificationRecoverySuppressed) return false
        return awaitNotificationReceiverForStartup(
            notificationJob = notificationJob,
            receiverActive = notificationReceiverActive,
            receiverRetryWake = notificationReceiverRetryWake,
            timeoutMillis = timeoutMillis,
            launchListener = ::launchNotificationListenerLoop,
        )
    }

    /**
     * Starts the receiver and reports pending push catch-up success; ordinary startup can use local state on failure.
     * Push-wake owners must inspect the result and retry within their bounded background supervisor.
     */
    suspend fun ensureNotificationRuntimeStarted(): Boolean {
        if (!bootstrapCompleted) {
            bootstrap()
            val receiverReady = bootstrapCompleted && notificationReceiverActive.value
            if (!receiverReady) throw receiverUnavailable()
            pushWakeRecovery.drainPendingNativePushRegistrationSyncIfNeeded()
            return pushWakeRecovery.drainPendingPushWakeCatchUpIfNeeded()
        }
        localNotificationPresenter.ensureChannels()
        refreshLocalNotificationPermission()
        if (!awaitNotificationReceiverForStartupWithin(notificationReceiverTimeoutMillis())) throw receiverUnavailable()
        if (accounts.isEmpty()) refreshAccounts()
        refreshLocalNotificationSettings()
        pushWakeRecovery.drainPendingNativePushRegistrationSyncIfNeeded()
        return pushWakeRecovery.drainPendingPushWakeCatchUpIfNeeded()
    }

    /** Captures the active notification-runtime recovery lifetime. */
    internal fun notificationRuntimeRecoveryGeneration(): Long = notificationRuntimeRecovery.capture()

    /** Reports whether a reconnect recovery may still start notification work. */
    internal fun notificationRuntimeRecoveryAllowed(generation: Long): Boolean =
        !networkNotificationRecoverySuppressed && notificationRuntimeRecovery.isCurrent(generation)

    /** Re-establishes receiver readiness within the reconnect budget before admitting native catch-up. */
    private suspend fun ensureNotificationReceiverForNetworkReconnect(): Boolean {
        if (!bootstrapCompleted) bootstrap()
        if (!bootstrapCompleted || networkNotificationRecoverySuppressed) return false
        localNotificationPresenter.ensureChannels()
        refreshLocalNotificationPermission()
        val receiverReady =
            awaitNotificationReceiverForStartupWithin(
                timeoutMillis =
                    minOf(
                        notificationReceiverTimeoutMillis(),
                        NOTIFICATION_NETWORK_RECOVERY_RECEIVER_TIMEOUT_MILLIS,
                    ),
            )
        if (receiverReady && !networkNotificationRecoverySuppressed) {
            if (accounts.isEmpty()) refreshAccounts()
            if (!networkNotificationRecoverySuppressed && notificationReceiverActive.value) {
                refreshLocalNotificationSettings()
                pushWakeRecovery.drainPendingNativePushRegistrationSyncIfNeeded()
            }
        }
        return receiverReady &&
            !networkNotificationRecoverySuppressed &&
            notificationReceiverActive.value &&
            notificationJob.isActive()
    }

    /** Starts push catch-up with the drain waiter armed before the runtime can emit an update. */
    suspend fun ensureNotificationRuntimeStartedAndAwaitPushDrain(timeoutMs: Long = NOTIFICATION_PUSH_DRAIN_TIMEOUT_MILLIS): Boolean =
        awaitNotificationPushDrain(
            sequenceBeforeStart = notificationDrainSequence.get(),
            notificationDrainSignals = notificationDrainSignals,
            timeoutMs = timeoutMs,
            startRuntime = ::ensureNotificationRuntimeStarted,
            keepConnected = { backgroundConnectionEnabled },
        )

    private val profileSignUp =
        AppProfileSignUp(
            this,
            ::activateCreatedIdentity,
            ::configurePrivacyRuntime,
            ::warmProfile,
        ) { phase = AppPhase.Ready }

    /** Process-owned resume receipt and coordinator for the Developer Tools conversation demo. */
    internal val appReviewDemo: AppReviewDemo by lazy {
        AppReviewDemo(
            backend = AppReviewDemoNative(this),
            store =
                SecureReviewDemoStore(
                    KeystoreSecureStore(
                        context,
                        "review_demo_receipt_secure_v1",
                        AndroidKeystoreSecretKeyProvider("white-noise-review-demo-receipt-v1"),
                    ),
                    preferences,
                ),
            scope = mutationsScope,
        )
    }

    /** Process-owned receipt survives recreation while native creation or profile publication runs. */
    internal val pendingProfileSignUp: SignUpController?
        get() = profileSignUp.pending

    /** Presents the still-owned attempt above phase dispatch, including bootstrap re-entry. */
    internal val profileSignUpForPresentation: SignUpController?
        get() = profileSignUp.forPresentation

    /** Opening the form performs no native creation or publication. */
    internal fun beginProfileSignUp() = profileSignUp.begin()

    /** Discards only an unsubmitted form or stale route; native accepted work remains intact. */
    internal fun dismissProfileSignUp(): Boolean = profileSignUp.dismiss()

    /** Creates and contains a native identity before publishing it as the active Android account. */
    suspend fun createIdentity() = profileSignUp.createIdentityWithoutProfile()

    /** Activates a newly created identity and invalidates older account-list reads atomically. */
    private suspend fun activateCreatedIdentity(
        summary: AccountSummaryFfi,
        mayPublish: () -> Boolean,
    ) = accountSession.publishCreatedIdentity(summary.label) {
        if (!mayPublish()) throw CancellationException("Creating runtime was replaced")
        if (summary.label != activeAccountRef) {
            clearInMemoryMediaCaches()
            clearCrossAccountCaches()
        }
        accountListLifetime.advance {
            accounts = accountSummariesWithCreatedIdentity(accounts, summary)
        }
        activeAccountRef = summary.label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, summary.label).apply()
        notificationDelivery.localNotificationSettings = null
        reloadMediaAutoDownloadMatrix()
    }

    /** Runs post-create enrichment without delaying the accepted identity or actionable route. */
    private fun launchIdentityPostCreateWarmup(summary: AccountSummaryFfi) = profileSignUp.launchIdentityPostCreateWarmup(summary)

    /**
     * Reports how the import ended. Failures are reported to the caller (not
     * toasted from here) so the identity-entry form can show a readable inline
     * error next to the field — the raw engine message only goes to the debug
     * log.
     */
    internal suspend fun importIdentity(identity: String): IdentityImportOutcome {
        val trimmed = identity.trim()
        if (!permitsDirectIdentityImport(trimmed)) return IdentityImportOutcome.Failed
        return try {
            // Read without requiring a runtime, so an injected login seam reaches its engine calls unchanged.
            val originRuntime = marmotRuntime?.marmot
            val originEpoch = runtimeGeneration
            val originSelection = accountSession.handoff.capture()
            val ownsOrigin = {
                runtimeGeneration == originEpoch &&
                    marmotRuntime?.marmot === originRuntime &&
                    accountSession.handoff.isCurrent(originSelection)
            }
            val setup = beginIdentitySetup(trimmed, originRuntime, originEpoch, ownsOrigin)
            if (setup != null) {
                IdentityImportOutcome.SetupStarted
            } else {
                val summary = engineLogin(trimmed, ownsOrigin)
                activateImportedIdentity(summary, originRuntime, originEpoch, originSelection)
                IdentityImportOutcome.Success
            }
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            logRedactedIdentityFailure("identity import", error)
            identityImportOutcome(error)
        }
    }

    /**
     * Consent-gated recovery for an account whose pre-journal setup left an
     * ambiguous partial shape behind. Reachable only after the engine reported
     * [IdentityImportOutcome.SetupRecoveryRequired] and the user affirmed that
     * an already-published KeyPackage may be left orphaned — the engine refuses
     * the rotation without that acknowledgement, so it is never inferred here.
     *
     * The one-call engine API clears the partial shape and retries login
     * itself, so no separate reset step is issued.
     */
    internal suspend fun recoverIncompleteIdentitySetup(identity: String): IdentityImportOutcome {
        val trimmed = identity.trim()
        if (!permitsDirectIdentityImport(trimmed)) return IdentityImportOutcome.Failed
        return try {
            val originRuntime = marmotRuntime?.marmot
            val originEpoch = runtimeGeneration
            val originSelection = accountSession.handoff.capture()
            val ownsOrigin = {
                runtimeGeneration == originEpoch &&
                    marmotRuntime?.marmot === originRuntime &&
                    accountSession.handoff.isCurrent(originSelection)
            }
            val summary = engineRecoveringLogin(trimmed, ownsOrigin)
            activateImportedIdentity(summary, originRuntime, originEpoch, originSelection)
            IdentityImportOutcome.Success
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            logRedactedIdentityFailure("identity setup recovery", error)
            identityImportOutcome(error)
        }
    }

    /** The import's captured runtime, failing like any native call when the import started without one. */
    private fun requireImportRuntime(runtime: MarmotInterface?): MarmotInterface = requireNotNull(runtime) { "Marmot is not initialized" }

    /** Only MDK's explicit legacy-account refusal retains the consent-gated old login path. */
    private suspend fun beginIdentitySetup(
        nsec: String,
        originRuntime: MarmotInterface?,
        originEpoch: Int,
        ownsOrigin: () -> Boolean,
    ): OnboardingSnapshotFfi? {
        identityLoginCalls?.let { calls ->
            val snapshot =
                withAccountLifetime {
                    if (!ownsOrigin()) throw CancellationException("Import origin was replaced")
                    calls.beginOnboarding(nsec)
                }
            snapshot?.let { accountSetup.open(it, requireImportRuntime(originRuntime), originEpoch, ownsOrigin) }
            return snapshot
        }
        return try {
            accountSetup.begin(nsec, ownsOrigin)
        } catch (_: MarmotKitException.OnboardingActionUnavailable) {
            // An unfinished interactive checkpoint is returned by begin, never routed here.
            // MDK also rejects legacy login for interactive ownership as a second barrier.
            null
        }
    }

    /** Imports an identity with public NIP-65 defaults and White Noise key-package and inbox routes. */
    private suspend fun engineLogin(
        nsec: String,
        ownsOrigin: () -> Boolean,
    ): AccountSummaryFfi =
        withAccountLifetime {
            if (!ownsOrigin()) throw CancellationException("Import origin was replaced")
            engineLoginLocked(nsec)
        }

    private suspend fun engineLoginLocked(nsec: String): AccountSummaryFfi {
        val relays = MarmotClient.bootstrapRelays
        val injected = identityLoginCalls
        if (injected != null) return injected.login(nsec, whiteNoiseNip65Relays, relays)
        return marmotIo { login(nsec, whiteNoiseNip65Relays, relays, whiteNoiseInboxRelays) }
    }

    /**
     * Recovers an incomplete setup only after the consent prompt confirms the invitation-key risk.
     * The acknowledgement is constant; recovery keeps the same NIP-65 and inbox defaults as login.
     */
    private suspend fun engineRecoveringLogin(
        nsec: String,
        ownsOrigin: () -> Boolean,
    ): AccountSummaryFfi =
        withAccountLifetime {
            if (!ownsOrigin()) throw CancellationException("Import origin was replaced")
            engineRecoveringLoginLocked(nsec)
        }

    private suspend fun engineRecoveringLoginLocked(nsec: String): AccountSummaryFfi {
        val relays = MarmotClient.bootstrapRelays
        val injected = identityLoginCalls
        if (injected != null) {
            return injected.loginRecoveringIncompleteSetup(
                nsec,
                whiteNoiseNip65Relays,
                relays,
                acknowledgePossibleKeyPackageOrphan = true,
            )
        }
        return marmotIo {
            loginRecoveringIncompleteSetup(
                nsec,
                whiteNoiseNip65Relays,
                relays,
                acknowledgePossibleKeyPackageOrphan = true,
                inboxRelays = whiteNoiseInboxRelays,
            )
        }
    }

    /**
     * Pending setup keeps its route; only a completed activation receipt can announce an imported identity.
     * An import that started without a runtime fails its ownership check like any native call.
     */
    private suspend fun activateImportedIdentity(
        summary: AccountSummaryFfi,
        expectedRuntime: MarmotInterface?,
        expectedEpoch: Int,
        expectedSelection: Long? = null,
    ) {
        val ownsRuntime = { runtimeGeneration == expectedEpoch && marmot() === expectedRuntime }
        withAccountLifetime {
            if (!ownsRuntime()) throw CancellationException("Import runtime was replaced")
            marmotIo { enforceAppOwnedAttachmentAcquisitionPolicy(listOf(summary.label)) }
        }
        refreshAccounts()
        if (expectedSelection != null && !accountSession.handoff.isCurrent(expectedSelection)) {
            throw CancellationException("Import selection was replaced")
        }
        val receipt =
            activateAccountSession(AccountSessionActivationRequest(summary.label, shouldActivate = ownsRuntime))
        if (receipt != null) {
            accountSession.finishImportedIdentity(
                receipt = receipt,
                refreshSettings = ::refreshLocalNotificationSettings,
                publishReady = {
                    phase = AppPhase.Ready
                    presentTransient(R.string.toast_identity_imported)
                },
                warmProfile = { warmProfile(summary.accountIdHex) },
            )
        }
    }

    // The submitted nsec can surface in the engine's error text, so redact
    // before logging (mirrors exportActiveAccountNsec) and don't pass the raw
    // throwable to the logger, whose stack trace would echo the unredacted
    // message.
    private fun logRedactedIdentityFailure(
        stage: String,
        error: Throwable,
    ) {
        appStateDebug { "$stage failed: ${DiagnosticFormatter.redactError(error.readableMessage())}" }
    }

    /** Whether a NIP-55 external signer (Amber) is installed — gates the UI entry point. */
    fun isAmberSignerInstalled(): Boolean = amberSigner.isSignerInstalled()

    /**
     * Opens staged Amber setup or legacy login with public NIP-65 defaults and White Noise inbox routes.
     * Re-registers the signer before exposing the account; failures keep existing accounts intact.
     */
    suspend fun loginWithAmber() {
        try {
            val runtime = marmot()
            val epoch = runtimeGeneration
            val selection = accountSession.handoff.capture()
            amberSignInStage = 1
            val reportedPubkey = withContext(Dispatchers.IO) { amberPublicKeyRequest() }

            // Normalize npub/hex to the canonical hex the account is keyed by, so
            // the login-time signer and the startup re-registration signer share
            // the same current_user.
            fun requireRuntime() {
                if (
                    runtimeGeneration != epoch ||
                    marmot() !== runtime ||
                    !accountSession.handoff.isCurrent(selection)
                ) {
                    throw CancellationException("Amber owner was replaced")
                }
            }
            val pubkeyHex =
                withAccountLifetime {
                    requireRuntime()
                    withContext(marmotIoDispatcher) { runtime.accountIdHex(reportedPubkey) }
                }
                    ?: throw MarmotKitException.Runtime("signer returned an invalid public key")
            amberSignInStage = 2
            val snapshot =
                try {
                    withAccountLifetime {
                        requireRuntime()
                        withContext(marmotIoDispatcher) {
                            runtime.beginExternalSignerOnboarding(
                                pubkeyHex,
                                amberSigner.buildSigner(pubkeyHex),
                                setupOptions(),
                            )
                        }
                    }
                } catch (_: MarmotKitException.OnboardingActionUnavailable) {
                    null
                }
            if (snapshot != null) {
                requireRuntime()
                accountSetup.open(snapshot, runtime, epoch) { accountSession.handoff.isCurrent(selection) }
                return
            }
            val summary = loginAmberFallback(runtime, pubkeyHex, ::requireRuntime)
            activateImportedIdentity(summary, runtime, epoch, selection)
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            if (error is MarmotKitException.ExternalSignerRejected) {
                presentTransient(R.string.toast_amber_sign_in_cancelled)
            } else {
                appStateDebug(error) { "amber login failed: ${error.readableMessage()}" }
                presentFailure(
                    R.string.toast_couldnt_login_amber,
                    "AMBER_SIGN_IN",
                    error,
                    detail = amberSignInFailureDetail(error),
                )
            }
        } finally {
            amberSignInStage = null
        }
    }

    private suspend fun loginAmberFallback(
        runtime: MarmotInterface,
        pubkeyHex: String,
        requireRuntime: () -> Unit,
    ): AccountSummaryFfi {
        val summary =
            withAccountLifetime {
                requireRuntime()
                withContext(marmotIoDispatcher) {
                    runtime.loginExternalSigner(
                        pubkeyHex,
                        amberSigner.buildSigner(pubkeyHex),
                        whiteNoiseNip65Relays,
                        MarmotClient.bootstrapRelays,
                        whiteNoiseInboxRelays,
                    )
                }
            }
        // Mirror the cold-start restore barrier before exposing the main
        // shell. loginExternalSigner creates and reconciles the account,
        // but a fresh account's worker/relay runtime can still be replacing
        // its setup-time signer as that call returns. Re-registering through
        // the restore path performs one final reconciliation with a stable
        // callback, so an immediate createGroup cannot race that transition
        // and fail until the next process restart (issue #1551).
        withAccountLifetime {
            requireRuntime()
            withContext(marmotIoDispatcher) {
                runtime.registerExternalSigner(summary.label, amberSigner.buildSigner(pubkeyHex))
            }
        }
        return summary
    }

    /**
     * Re-install the NIP-55 signer callback for every external-signing account
     * after an engine (re)start. MDK persists only the account pubkey for these
     * accounts, so the `ExternalAccountSignerFfi` must be re-registered before
     * any signing happens. Best-effort per account: a failure (e.g. Amber
     * uninstalled) is logged and leaves the account intact rather than aborting
     * bootstrap; that account's signing will later surface a typed error.
     */
    private suspend fun reregisterExternalSigners() {
        accounts.filter { it.externalSigning }.forEach { account ->
            runCatchingCancellable {
                marmotIo { registerExternalSigner(account.label, amberSigner.buildSigner(account.accountIdHex)) }
            }.onFailure {
                appStateDebug(it) { "external signer re-register failed for ${account.label.take(8)}: ${it.readableMessage()}" }
            }
        }
    }

    /** Publishes the newest engine account snapshot and rejects older list reads. */
    private suspend fun refreshAccountSnapshot(): List<AccountSummaryFfi> =
        measureHostPerformance(HostPerformanceOperationFfi.ACCOUNT_LOAD) {
            val requestToken = accountListLifetime.advance()
            val refreshedAccounts = marmotIo(MarmotTraceSection.ACCOUNT_LIST) { listAccountsWithAppAttachmentPolicy() }
            val setupAccounts = accountSetup.accountsState(refreshedAccounts)
            val bubbleColorMigrationSucceeded =
                withContext(Dispatchers.IO) {
                    LegacyBubbleColorMigration.migrate(
                        preferences = preferences,
                        accountRefs = refreshedAccounts.map(AccountSummaryFfi::label),
                    )
                }
            if (bubbleColorMigrationSucceeded) {
                // A getter may have cached null while bootstrap was still loading
                // accounts. Re-read migrated slots now so the copied color appears
                // in this process instead of waiting for a restart.
                globalBubbleColors.clear()
            }
            var publishedAccounts = accounts
            val accepted =
                accountListLifetime.runIfCurrent(requestToken) {
                    accountSetup.acceptAccounts(setupAccounts)
                    accounts = refreshedAccounts
                    accountListLoaded = true
                    refreshNativeAttachmentPermissions()
                    releaseContactClearGuardForSignedInAccounts(refreshedAccounts)
                    publishedAccounts = refreshedAccounts
                }
            if (accepted) {
                retainProfileAlertsForAccounts(refreshedAccounts) { accountListLifetime.isCurrent(requestToken) }
            }
            if (accountListLifetime.isCurrent(requestToken)) publishedAccounts else accounts
        }

    /** Publishes the newest account snapshot, then refreshes unread state for that accepted set. */
    suspend fun refreshAccounts() {
        val refreshedAccounts = refreshAccountSnapshot()
        schedulePendingLocalGroupDeleteCleanup()
        refreshAccountUnreadCounts(refreshedAccounts)
    }

    /** Whether the saved account is blocked on explicit 0.9.20 checkpoint recovery. */
    internal fun onboardingRecoveryRequired(accountRef: String): Boolean = accountSetup.needsRecovery(accountRef)

    /** Performs the consent-gated checkpoint replacement and refreshes the authoritative account list. */
    internal suspend fun recoverSetup(ref: String): Boolean = accountSetup.recoverAndRefresh(ref, ::refreshAccounts)

    /** Rebuilds the account list after sign-out without allowing an earlier refresh to restore it. */
    private suspend fun accountsAfterSignOut(
        signedOutRef: String,
        engineCompleted: Boolean,
    ): List<AccountSummaryFfi> {
        val latestAccounts =
            runCatchingCancellable {
                refreshAccounts()
                accounts
            }.onFailure { failure ->
                appStateDebug(failure) { "post-sign-out account refresh failed; reconciling cached accounts" }
            }.getOrElse {
                if (engineCompleted) {
                    accountListLifetime.advance {
                        accounts = reconcileCachedAccountsAfterSignOut(accounts, signedOutRef)
                    }
                }
                accounts
            }
        return latestAccounts
    }

    internal fun recordAccountSwitchPreloadStage(
        accountRef: String,
        stage: String,
        rowCount: Int,
    ) {
        val trace = pendingAccountSwitchTrace ?: return
        if (trace.accountRef != accountRef) return
        val elapsedMs = (SystemClock.elapsedRealtime() - trace.startedAtMs).coerceAtLeast(0L)
        recordAccountSwitchPerformance(trace.performance, stage, elapsedMs, rowCount)
        appStateDebug { "account-switch $stage +${elapsedMs}ms rows=$rowCount" }
    }

    internal fun recordAccountSwitchIdentityState(
        accountRef: String,
        snapshot: AccountSwitchLocalSnapshot,
    ) {
        val trace = pendingAccountSwitchTrace ?: return
        if (trace.accountRef != accountRef) return
        val elapsedMs = (SystemClock.elapsedRealtime() - trace.startedAtMs).coerceAtLeast(0L)
        val counts =
            accountSwitchIdentityStateCounts(
                snapshot = snapshot,
                topBarProfileIds = accountSwitchProfileSeedIds(emptyList(), accounts, accountRef),
            )
        appStateDebug { "account-switch identity-state +${elapsedMs}ms ${counts.privacySafeTrace()}" }
    }

    internal fun consumeAccountSwitchLocalSnapshot(accountRef: String?) = accountSwitchHandoff.consume(accountRef)

    private suspend fun restoreSignedOutAccountForActivation(
        target: AccountSummaryFfi?,
        label: String,
        deferUnreadRefresh: Boolean,
    ): Boolean =
        if (target?.signedOut != true) {
            true
        } else {
            val signInFailure = runCatchingCancellable { marmotIo { signInAccount(label) } }.exceptionOrNull()
            if (signInFailure != null) {
                presentFailure(R.string.toast_couldnt_sign_in_account, "ACCOUNT_SIGN_IN", signInFailure)
                false
            } else {
                val refreshedAccounts = refreshAccountSnapshot()
                if (deferUnreadRefresh) {
                    prepareStartupUnreadRefresh(refreshedAccounts)
                } else {
                    refreshAccountUnreadCounts(refreshedAccounts)
                }
                true
            }
        }

    /** Diverts pending account selection before legacy sign-in or chat projection starts. */
    private suspend fun routePendingAccountSetup(
        label: String,
        selectionGeneration: Long,
    ): Boolean {
        val runtime = marmotRuntime
        val epoch = runtimeGeneration
        val required =
            runtime != null &&
                accountSetup.routeIfPending(label) {
                    accountSession.handoff.isCurrent(selectionGeneration)
                }
        if (required) {
            withAccountLifetime {
                val runtimeCurrent = marmotRuntime === runtime && runtimeGeneration == epoch
                if (runtimeCurrent &&
                    activeAccountRef == label &&
                    accountSwitchHandoff.isCurrent(selectionGeneration)
                ) {
                    activeAccountRef = null
                }
            }
        }
        return required
    }

    /** Keeps the screen-facing Boolean contract while the session owner retains a precise activation receipt. */
    suspend fun setActiveAccount(
        label: String,
        deferUnreadRefresh: Boolean = false,
        shouldActivate: () -> Boolean = { true },
        preloadPolicy: AccountSwitchPreloadPolicy = AccountSwitchPreloadPolicy.FULL_LOCAL_SNAPSHOT,
        awaitPostActivationWork: suspend () -> Unit = {},
        expectedSelection: Long? = null,
        onActivated: () -> Unit = {},
    ): Boolean =
        activateAccountSession(
            AccountSessionActivationRequest(label, deferUnreadRefresh, shouldActivate, preloadPolicy),
            awaitPostActivationWork,
            {
                phase = AppPhase.Ready
                onActivated()
            },
            expectedSelection,
        ) != null

    /** Retains host telemetry and pending setup routing without recapturing the accepted activation's lifetime. */
    @Suppress("ThrowsCount") // Preserve the separate timeout, cancellation and failure telemetry outcomes.
    private suspend fun activateAccountSession(
        request: AccountSessionActivationRequest,
        awaitPostActivationWork: suspend () -> Unit = {},
        onActivated: () -> Unit = {},
        expectedSelection: Long? = null,
    ): AccountSessionActivationReceipt? {
        val accountSwitchAttempt =
            if (request.label != activeAccountRef) {
                beginHostPerformance(HostPerformanceOperationFfi.ACCOUNT_SWITCH)
            } else {
                null
            }
        var accountSwitchOutcome = HostPerformanceOutcomeFfi.UNAVAILABLE
        updatePendingAccountSwitchTrace(request.label, switchingAccounts = request.label != activeAccountRef)
        try {
            val receipt =
                accountSession.activate(
                    request,
                    awaitPostActivationWork,
                    onActivated,
                    beforePrepare = { generation -> !routePendingAccountSetup(request.label, generation) },
                    expectedGeneration = expectedSelection,
                )
            if (receipt != null) accountSwitchOutcome = HostPerformanceOutcomeFfi.SUCCESS
            return receipt
        } catch (timeout: TimeoutCancellationException) {
            accountSwitchOutcome = HostPerformanceOutcomeFfi.TIMEOUT
            throw timeout
        } catch (cancel: CancellationException) {
            accountSwitchOutcome = HostPerformanceOutcomeFfi.CANCELLED
            throw cancel
        } catch (throwable: Throwable) {
            accountSwitchOutcome = HostPerformanceOutcomeFfi.FAILURE
            throw throwable
        } finally {
            accountSwitchAttempt?.complete(accountSwitchOutcome)
        }
    }

    /** Adapts existing MDK reads and Android publication without owning another account lifetime. */
    private inner class AccountSessionActivation : AppAccountSessionActivation {
        override val activeAccount: String? get() = activeAccountRef

        /** Restores native sign-in and captures the runtime it ran against, leaving local reads to the projection. */
        override suspend fun prepare(
            request: AccountSessionActivationRequest,
            generation: Long,
            switchingAccounts: Boolean,
        ): PreparedAccountSessionActivation? {
            val target = accounts.firstOrNull { it.label == request.label }
            if (!restoreSignedOutAccountForActivation(target, request.label, request.deferUnreadRefresh)) return null
            return PreparedAccountSessionActivation(
                runtimeGeneration,
                StartupSelfProfilePresentation(revision = null, seeds = emptyList()),
                snapshot = null,
            )
        }

        /** Reads the selected policy's local profile and rows outside the lifetime gate, before publication. */
        override suspend fun loadLocalProjection(
            request: AccountSessionActivationRequest,
            generation: Long,
            switchingAccounts: Boolean,
            restored: PreparedAccountSessionActivation,
        ): PreparedAccountSessionActivation {
            val target = accounts.firstOrNull { it.label == request.label }
            val activationStillWanted = request.shouldActivate() && isAccountSwitchCurrent(generation)
            val plan = accountSwitchPreloadPlan(switchingAccounts, activationStillWanted, request.preloadPolicy)
            val profile =
                preloadStartupSelfProfile(request.preloadPolicy, target?.accountIdHex, activationStillWanted) {
                    loadAccountSwitchProfileSeed(it)
                }
            val snapshot =
                if (plan.loadLocalRows) loadAccountSwitchLocalSnapshot(request.label, generation, plan) else null
            return restored.copy(profilePresentation = profile, snapshot = snapshot)
        }

        /** Rejects revoked identities, replacement runtimes and publication during destructive account teardown. */
        override fun canPublish(
            label: String,
            runtime: Int,
        ): Boolean = !networkNotificationRecoverySuppressed && canPublishAccountActivation(label, runtime)

        /** Hides platform share targets before publishing a different active account. */
        override suspend fun hidePreviousAccountShortcuts() {
            hideConversationShortcutsFromDirectShare()
            recordAccountSwitchMark("previous-shortcuts-hide-finished")
        }

        /** Commits the prepared projection and active label together without a suspension. */
        override fun publish(
            label: String,
            generation: Long,
            switchingAccounts: Boolean,
            prepared: PreparedAccountSessionActivation,
        ) {
            val currentStartupProfiles = startupSeeds(prepared.profilePresentation)
            if (switchingAccounts) {
                clearInMemoryMediaCaches()
                clearCrossAccountCaches()
            }
            stageAccountSwitchLocalSnapshot(label, switchingAccounts, generation, prepared.snapshot)
            currentStartupProfiles.forEach(::applyAccountSwitchProfileSeed)
            activeAccountRef = label
            preferences.edit().putString(ACTIVE_ACCOUNT_KEY, label).apply()
            recordAccountSwitchMark("published", label)
            reloadMediaAutoDownloadMatrix()
        }

        /** Warms only the activated account's existing self-profile cache. */
        override suspend fun warmAccountProfile(label: String) {
            accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        }

        /** Applies the existing native privacy runtime configuration. */
        override suspend fun configurePrivacy() = configurePrivacyRuntime()

        /** Refreshes the current Android notification settings using the existing native path. */
        override suspend fun refreshNotificationSettings() = refreshLocalNotificationSettings()

        /** Preserves foreground/startup delivery admission and background-account mode reconciliation. */
        override suspend fun reconcileNotificationDelivery(includeBackgroundAccountModes: Boolean) {
            notificationDelivery.reconcileNotificationDeliveryModeAfterActivation(includeBackgroundAccountModes)
        }

        /** Reads foreground state at delivery admission rather than capturing it before the frame wait. */
        override fun isInForeground(): Boolean = appInForeground
    }

    /**
     * Starts or clears the latency trace when a switch is requested, before setup routing and restoration, so its
     * elapsed time covers the whole path from the tap. Debug builds always trace, and any build traces while a
     * WNPerf diagnostic session is active, so release switches can be measured.
     */
    private fun updatePendingAccountSwitchTrace(
        label: String,
        switchingAccounts: Boolean,
    ) {
        // A first activation has no previous account, so it is not a switch and is not traced.
        val tracked = switchingAccounts && activeAccountRef != null
        val performance =
            if (tracked) {
                PerformanceDiagnostics.begin(PerformanceOperation.ACCOUNT_SWITCH)
            } else {
                null
            }
        if (tracked && (BuildConfig.DEBUG || performance != null)) {
            pendingAccountSwitchTrace =
                PendingAccountSwitchTrace(
                    accountRef = label,
                    startedAtMs = SystemClock.elapsedRealtime(),
                    performance = performance,
                )
        } else {
            // Any newer request supersedes a pending trace, so its stages cannot land on an older operation.
            pendingAccountSwitchTrace = null
        }
    }

    /**
     * Restores an account retained by non-destructive sign-out and crosses the
     * onboarding boundary only after [setActiveAccount] reaches local-ready.
     * This deliberately uses MDK's account reactivation path rather than
     * repeating identity import or external-signer account setup.
     */
    fun reactivateRetainedAccount(label: String) {
        if (retainedAccountReactivationRef != null) return
        if (accounts.none { it.label == label }) {
            present(R.string.toast_notification_account_unavailable)
            return
        }
        retainedAccountReactivationRef = label
        launchMutation {
            try {
                setActiveAccount(
                    label = label,
                    onActivated = { phase = AppPhase.Ready },
                )
            } finally {
                retainedAccountReactivationRef = null
            }
        }
    }

    /**
     * Records the local SQLite chat-row boundary independently from roster and
     * profile projection. Logs timing and a small count only—never account ids.
     */
    internal fun recordAccountSwitchLocalRowsReady(
        accountRef: String,
        rowCount: Int,
    ) {
        if (activeAccountRef != accountRef) return
        if (!startupLocalRowsRecorded) {
            startupLocalRowsRecorded = true
            startupPerformance.record(PerformancePhase.CACHED_CHAT_ROWS_READY)
        }
        recordPendingAccountSwitchStage(accountRef, "cached-chat-rows-ready", rowCount)
    }

    /** Records the point where every cached row has a local member projection. */
    internal fun recordAccountSwitchMemberDerivedLocalReady(
        accountRef: String,
        rowCount: Int,
    ) {
        if (activeAccountRef != accountRef) return
        if (!startupMemberDerivedLocalRecorded) {
            startupMemberDerivedLocalRecorded = true
            startupPerformance.record(PerformancePhase.MEMBER_DERIVED_LOCAL_READY)
        }
        recordPendingAccountSwitchStage(accountRef, "member-derived-local-ready", rowCount)
    }

    /**
     * Logs an elapsed-time mark for the account switch in flight. Debug builds only. A mark naming [accountRef]
     * is dropped unless that account is the switch's target, so the outgoing account's rebind cannot pose as it.
     */
    internal fun recordAccountSwitchMark(
        stage: String,
        accountRef: String? = null,
    ) {
        val trace = pendingAccountSwitchTrace ?: return
        if (accountRef != null && trace.accountRef != accountRef) return
        val elapsedMs = (SystemClock.elapsedRealtime() - trace.startedAtMs).coerceAtLeast(0L)
        recordAccountSwitchPerformance(trace.performance, stage, elapsedMs)
        appStateDebug { "account-switch $stage +${elapsedMs}ms" }
    }

    private fun recordPendingAccountSwitchStage(
        accountRef: String,
        stage: String,
        rowCount: Int,
    ) {
        val trace = pendingAccountSwitchTrace ?: return
        if (trace.accountRef != accountRef) return
        val elapsedMs = (SystemClock.elapsedRealtime() - trace.startedAtMs).coerceAtLeast(0L)
        recordAccountSwitchPerformance(trace.performance, stage, elapsedMs, rowCount)
        appStateDebug { "account-switch $stage +${elapsedMs}ms rows=$rowCount" }
    }

    /**
     * Records switch-to-first-local-frame latency without logging an account id.
     * A stale controller cannot finish a newer switch's trace.
     */
    @Suppress("ReturnCount") // Stale controllers and absent/superseded traces are independent terminal states.
    internal fun recordAccountSwitchLocalSnapshotRendered(
        accountRef: String,
        rowCount: Int,
    ) {
        // A controller that was superseded during startup can still finish its
        // already-awaited draw. It must not publish the process-wide local-ready
        // boundary or start the deferred unread fold for the new account.
        if (activeAccountRef != accountRef) return
        recordStartupLocalSnapshotRendered()
        val trace = pendingAccountSwitchTrace ?: return
        if (trace.accountRef != accountRef) return
        pendingAccountSwitchTrace = null
        val elapsedMs = (SystemClock.elapsedRealtime() - trace.startedAtMs).coerceAtLeast(0L)
        recordAccountSwitchPerformance(trace.performance, "first-local-frame", elapsedMs, rowCount)
        appStateDebug { "account-switch first-local-frame +${elapsedMs}ms rows=$rowCount" }
    }

    /** Starts startup unread reconciliation under the current account-list lifetime. */
    private fun prepareStartupUnreadRefresh(accountSummaries: List<AccountSummaryFfi>) {
        pendingStartupUnreadRefresh =
            StartupUnreadRefresh(
                accounts = accountSummaries,
                accountListRevision = accountListLifetime.capture(),
            )
    }

    /** Called only after the controller's authoritative SQLite snapshot drew. */
    private fun recordStartupLocalSnapshotRendered() {
        if (!startupFirstLocalFrameRecorded) {
            startupFirstLocalFrameRecorded = true
            startupPerformance.record(PerformancePhase.FIRST_LOCAL_FRAME)
        }
        val pending = pendingStartupUnreadRefresh ?: return
        pendingStartupUnreadRefresh = null
        val accountListToken = pending.accountListRevision
        val accountListIsCurrent = { accountListLifetime.isCurrent(accountListToken) }
        mutationsScope.launch {
            startupPerformance.stage(PerformancePhase.UNREAD_AGGREGATE_REFRESH) {
                refreshAccountUnreadCounts(pending.accounts, stillCurrent = accountListIsCurrent)
            }
            if (accountListIsCurrent()) {
                startupPerformance.record(PerformancePhase.UNREAD_AGGREGATE_READY)
            }
        }
    }

    /** Records the first frame where app-owned Compose UI replaces the system splash. */
    internal fun recordStartupSystemSplashHandoff() {
        if (startupSystemSplashHandoffRecorded) return
        startupSystemSplashHandoffRecorded = true
        startupPerformance.record(PerformancePhase.SYSTEM_SPLASH_HANDOFF)
    }

    private fun recordStartupRelayCatchUpReady() {
        if (startupRelayCatchUpRecorded) return
        startupRelayCatchUpRecorded = true
        startupPerformance.record(PerformancePhase.RELAY_CATCH_UP_READY)
    }

    /**
     * Wipe per-account in-memory media caches on account switch. The
     * URL-keyed avatar LRU stays put — it is already byte-budgeted, holds
     * no per-account secret material, and the same URL points at the same
     * bytes regardless of the active account, so re-fetching every group
     * and profile picture on every switch is gratuitous network + battery
     * cost. The L2 disk cache is also deliberately preserved.
     */
    private fun clearInMemoryMediaCaches() {
        assertMainThread { "clearInMemoryMediaCaches" }
        displayThumbnails.retirePending()
        pendingSendDiagnostics.clear()
        mediaPlaintextCache.clear()
        mediaThumbnailCache.clear()
        mediaThumbnailRevisionState.update { it + 1L }
        bumpMediaCacheRevision()
        MediaInventory.clear()
        mediaUploadSessionLifetime.advance()
        // Uploads run on the app-lifetime mutation scope so they can survive
        // conversation-screen disposal. Account switch/sign-out is different:
        // cancel those old-account sends before dropping the retained bytes so
        // a cancelled upload cannot resume against an emptied retained-upload
        // map and falsely mark the bubble Failed (or publish after the switch).
        inFlightMediaUploads.cancelAll()
        // The per-conversation maps below hold (or potentially hold)
        // decrypted plaintext keyed by account/group. Wiping them at the
        // same call site keeps account-switch and sign-out symmetric with
        // the L1 plaintext clear above; an unwiped retained-upload cache
        // would otherwise let the next signed-in account see the previous
        // account's outgoing bytes.
        synchronized(conversationStateLock) {
            retainedMediaUploadsByConversation.values.forEach { it.clear() }
            retainedMediaUploadsByConversation.clear()
            activeUploadKeysByConversation.values.forEach { it.clear() }
            activeUploadKeysByConversation.clear()
            pendingProjectionsAwaitingBridgeByConversation.values.forEach { it.clear() }
            pendingProjectionsAwaitingBridgeByConversation.clear()
            // The optimistic message map holds decrypted outgoing records
            // (AppMessageRecordFfi plaintext); the override maps hold its
            // ordering keys. They were previously pruned only per-key by the
            // LRU, so a signed-out account's sent plaintext lingered in memory.
            optimisticMessagesByConversation.values.forEach { it.clear() }
            optimisticMessagesByConversation.clear()
            durableAcceptanceCallbacksByConversation.values.forEach { it.clear() }
            durableAcceptanceCallbacksByConversation.clear()
            projectedMessageIdsByConversation.values.forEach { it.clear() }
            projectedMessageIdsByConversation.clear()
            timelineOrderOverridesByConversation.values.forEach { it.clear() }
            timelineOrderOverridesByConversation.clear()
            timelineTimestampOverridesByConversation.values.forEach { it.clear() }
            timelineTimestampOverridesByConversation.clear()
            optimisticSendPositionPreservesByConversation.values.forEach { it.clear() }
            optimisticSendPositionPreservesByConversation.clear()
            retentionAtSendByConversation.values.forEach { it.clear() }
            retentionAtSendByConversation.clear()
            acceptedPendingTextOptimisticIdsByConversation.values.forEach { it.clear() }
            acceptedPendingTextOptimisticIdsByConversation.clear()
            optimisticSendPhasesByConversation.values.forEach { it.clear() }
            optimisticSendPhasesByConversation.clear()
            optimisticCancellationGenerationByConversation.clear()
            nativeComposerCleanupRevisions.clear()
        }
        // Cancel any in-flight downloads (their Deferred may hold plaintext or
        // a retained-media outcome) and drop both indexes so the next session
        // starts cold.
        synchronized(inFlightDownloadsLock) {
            inFlightDownloads.values.forEach { it.cancel() }
            inFlightDownloads.clear()
        }
        inFlightAttachmentAcquisitions.cancelAll()
    }

    // Guards the window where a sign-out/wipe clears an account's contact
    // details on IO while the account can still read as active on Main: the
    // setters reject writes for guarded refs instead of racing the clear and
    // resurrecting data on disk. A ref is released only once a fresh account
    // list shows it signed in again (a failed sign-out, or a later re-login).
    private val contactClearGuard = Any()
    private val contactRefsBeingCleared = mutableSetOf<String>()

    private fun isContactRefBeingCleared(accountRef: String): Boolean =
        synchronized(contactClearGuard) {
            contactRefsBeingCleared.any { it.equals(accountRef.trim(), ignoreCase = true) }
        }

    private fun releaseContactClearGuardForSignedInAccounts(refreshed: List<AccountSummaryFfi>) {
        synchronized(contactClearGuard) {
            if (contactRefsBeingCleared.isEmpty()) return
            contactRefsBeingCleared.removeAll { ref ->
                refreshed.any { it.label.trim().equals(ref, ignoreCase = true) }
            }
        }
    }

    // Durable (commit-backed) but off the main thread: the writes must land
    // before sign-out/wipe completes, and the blocking flush must not stall
    // the UI. The revision bump stays on the caller's (main) context.

    /**
     * Commits private-field and picture removal off main before account teardown completes, then invalidates
     * display revisions.
     */
    private suspend fun clearContactPrivateDetailsForAccount(accountRef: String) {
        val normalized = accountRef.trim()
        if (normalized.isEmpty()) return
        synchronized(contactClearGuard) { contactRefsBeingCleared.add(normalized) }
        val nicknamesCleared =
            withContext(Dispatchers.IO) {
                val cleared = clearContactPrivateDetails(preferences, normalized, contactPictureStore)
                AvatarImageLoader.clearStoredAvatars()
                // Folder state is account-private UI organization; it must not
                // survive the account it belongs to.
                chatFolderPreferences.clearAllForAccount(normalized)
                cleared
            }
        if (nicknamesCleared) {
            contactNicknameRevision += 1
            profileAccountRevisions.reset()
        }
    }

    private suspend fun hideConversationShortcutsFromDirectShare() {
        withContext(Dispatchers.IO) {
            localNotificationPresenter.hideConversationShortcutsFromDirectShare()
        }
    }

    /** Local alert write failures are retryable; completed native removal must still reconcile the active account. */
    private suspend fun clearRevokedConversationShortcutsForAccount(
        accountRef: String,
        includeUnscopedLegacy: Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val cleared = runCatching { profileNotificationOverrides.clearAccount(accountRef) }.getOrDefault(false)
            if (!cleared) {
                appStateDebug { "profile alert preference cleanup will retry after an authoritative account refresh" }
            }
            localNotificationPresenter.clearRevokedConversationShortcutsForAccount(accountRef, includeUnscopedLegacy)
        }
    }

    /**
     * Drop every registered account-scoped cache so account A's data isn't
     * reachable after switching to B. New caches participate by construction;
     * this boundary must never grow another hand-maintained field list.
     * Also retires in-flight observations before another identity becomes active.
     */
    private fun clearCrossAccountCaches() {
        assertMainThread { "clearCrossAccountCaches" }
        dev.ipf.whitenoise.android.media.sandbox.IsolatedMediaDecoder
            .resetForAccount()
        // Revoke decrypted speech before another identity can use platform Resume.
        stopSpeaking()
        diagnostics.observations.invalidate()
        marmotRuntime?.marmot?.let { engine ->
            notificationScope.launch { diagnostics.activity(engine, ProductAnalyticsActivityFfi.ACCOUNT_CHANGED) }
        }
        profileCacheLifetime.advance()
        inviteNotificationIdentityRefreshStore.clear()
        accountScopedCaches.clearAll()
        chatMuteOwner.clear()
        AvatarImageLoader.clearStoredAvatars()
        GroupAvatarImageLoader.clear()
        pruneIdleGroupCommitLocks()
        profileRevision += 1
        profileAccountRevisions.reset()
    }

    /** Exercises the production account-cache invalidation boundary without starting an account switch. */
    internal fun clearCrossAccountCachesForTest() {
        clearCrossAccountCaches()
    }

    /**
     * Applies what an account switch does to the media session, in the production order: the in-memory media caches
     * are cleared and the session epoch advances, then [activeAccountRef] moves to [label] when one is given.
     */
    internal fun switchMediaSessionForTest(label: String?) {
        clearInMemoryMediaCaches()
        if (label != null) activeAccountRef = label
    }

    /**
     * Non-destructive MDK sign-out (#349, #2132). A thrown engine call requires an authoritative
     * native signed-out observation before local deactivation; unfinished or unknown local cleanup
     * retains the active session. Credential revocation failure
     * returns an incomplete result before
     * native admission; errors after admission retain their established propagation.
     * Final deactivation retires selections through the account session; native per-account cleanup remains
     * here, while the shared process runtime/listener is retained for other identities and later sign-in.
     * Returns null when no account is active.
     */
    private data class OwnedSignOutCompletion(
        val completion: SignOutCompletion,
        val successor: String? = null,
        val selectionGeneration: Long = 0,
        val nativeCancellation: CancellationException? = null,
    )

    private data class NativeSignOutAttempt(
        val result: Result<dev.ipf.marmotkit.SignOutOutcomeFfi>?,
        val nativeFalse: Boolean,
        val cancellation: CancellationException? = null,
    )

    /** A queued request retains its requested owner; accepted cleanup finishes before cancellation escapes. */
    @Suppress("ReturnCount")
    suspend fun signOutActiveAccount(deleteKeyPackages: Boolean = true): SignOutCompletion? {
        val requested = activeAccountRef ?: return null
        val runtime = marmotRuntime
        val runtimeEpoch = runtimeGeneration
        val selectionEpoch = accountSwitchHandoff.capture()
        var completedOwned: OwnedSignOutCompletion? = null
        var boundaryCancellation: CancellationException? = null
        val owned =
            try {
                withAccountLifetime {
                    val ownerCurrent =
                        activeAccountRef == requested && marmotRuntime === runtime && runtimeGeneration == runtimeEpoch
                    if (!ownerCurrent ||
                        !accountSwitchHandoff.isCurrent(selectionEpoch)
                    ) {
                        return@withAccountLifetime OwnedSignOutCompletion(SignOutCompletion.AccountCleanupIncomplete)
                    }
                    revokeAndFinishSignOutLocked(
                        SignOutRequest(requested, runtime, runtimeEpoch, selectionEpoch, deleteKeyPackages),
                    ) {
                        completedOwned = it
                    }
                }
            } catch (cancelled: CancellationException) {
                // Pinned credential cleanup may discard delivery when its IO finally returns to cancelled Main.
                boundaryCancellation = cancelled
                completedOwned ?: throw cancelled
            }
        val successor =
            withContext(NonCancellable) {
                runCatching {
                    owned.successor?.let { next ->
                        accountSession.activateAfterSignOutIfCurrent(
                            owned.selectionGeneration,
                            AccountSessionActivationRequest(next),
                            beforePrepare = { generation -> !routePendingAccountSetup(next, generation) },
                            onActivated = { phase = AppPhase.Ready },
                            ownerIsCurrent = { marmotRuntime === runtime && runtimeGeneration == runtimeEpoch },
                        )
                    }
                }
            }
        rethrowSignOutCancellation(owned.nativeCancellation, boundaryCancellation)
        successor.getOrThrow()?.let { accountSession.refreshAfterSignOut(it) }
        return owned.completion
    }

    private data class SignOutRequest(
        val requested: String,
        val runtime: AppMarmotRuntime?,
        val runtimeEpoch: Int,
        val selectionEpoch: Long,
        val deleteKeyPackages: Boolean,
    )

    private suspend fun revokeAndFinishSignOutLocked(
        request: SignOutRequest,
        completed: (OwnedSignOutCompletion) -> Unit,
    ): OwnedSignOutCompletion {
        val draftsSaved = draftWriter.flushAccount(request.requested)
        var admitted = false
        return runCatchingCancellable {
            withRevokedPinnedTarget(request.requested) {
                admitted = true
                finishRevokedAccountSignOut(
                    request.requested,
                    request.runtime,
                    request.runtimeEpoch,
                    request.selectionEpoch,
                    request.deleteKeyPackages,
                    draftsSaved,
                ).also(completed)
            }
        }.getOrElse { failure ->
            if (admitted) throw failure
            appStateDebug(failure) { "account sign-out credential revocation failed before native work" }
            OwnedSignOutCompletion(SignOutCompletion.AccountCleanupIncomplete)
        }
    }

    private suspend fun rethrowSignOutCancellation(
        native: CancellationException?,
        boundary: CancellationException?,
    ) {
        val cancellation = native ?: boundary
        if (cancellation != null) throw cancellation
        currentCoroutineContext().ensureActive()
    }

    /** Lifetime is already owned; acquire both push locks cancellably before admitting any cleanup intent. */
    @Suppress("LongMethod", "ReturnCount")
    private suspend fun finishRevokedAccountSignOut(
        account: String,
        runtime: AppMarmotRuntime?,
        runtimeEpoch: Int,
        selectionEpoch: Long,
        deleteKeyPackages: Boolean,
        draftsSaved: Boolean,
    ): OwnedSignOutCompletion {
        notificationDelivery.notificationDeliveryModeMutex.lock()
        var pushOwned = false
        var deliveryOwned = true
        try {
            notificationDelivery.nativePushSyncMutex.lock()
            pushOwned = true
            currentCoroutineContext().ensureActive()
            val ownerCurrent =
                activeAccountRef == account && marmotRuntime === runtime && runtimeGeneration == runtimeEpoch
            if (!ownerCurrent ||
                !accountSwitchHandoff.isCurrent(selectionEpoch)
            ) {
                return OwnedSignOutCompletion(SignOutCompletion.AccountCleanupIncomplete)
            }
            // After this point the explicit logout owns its cleanup intent, including failed disk acknowledgement.
            val selectionGeneration = accountSession.retireSelectionsForSignOut()
            notificationDelivery.notificationDeliveryModeIntent.advance()
            notificationDelivery.nativePushFallback.invalidateAll()
            return withContext(NonCancellable) {
                val attempt =
                    try {
                        admittedNativeSignOutLocked(account, runtime, deleteKeyPackages)
                    } finally {
                        notificationDelivery.nativePushSyncMutex.unlock()
                        pushOwned = false
                        notificationDelivery.notificationDeliveryModeMutex.unlock()
                        deliveryOwned = false
                    }
                // Shared and account-keyed cleanup finishes while lifetime is still owned, before any successor.
                finishOwnedSignOutLocked(account, runtime, attempt, selectionGeneration, draftsSaved)
            }
        } finally {
            if (pushOwned) notificationDelivery.nativePushSyncMutex.unlock()
            if (deliveryOwned) notificationDelivery.notificationDeliveryModeMutex.unlock()
        }
    }

    /** All three locks are owned. A confirmed transactional false receipt is required before native logout. */
    private suspend fun admittedNativeSignOutLocked(
        account: String,
        runtime: AppMarmotRuntime?,
        deleteKeyPackages: Boolean,
    ): NativeSignOutAttempt {
        val recorded =
            withContext(pushWakeStorageDispatcher) {
                runCatching { pushTokenStore.recordPendingDisable(account) }.getOrDefault(false)
            }
        if (runtime == null) {
            return NativeSignOutAttempt(
                if (recorded) Result.failure(IllegalStateException("Native runtime unavailable")) else null,
                nativeFalse = false,
            )
        }
        return signOutAfterNativeDisableLocked(account, runtime, deleteKeyPackages)
    }

    private suspend fun signOutAfterNativeDisableLocked(
        account: String,
        runtime: AppMarmotRuntime,
        deleteKeyPackages: Boolean,
    ): NativeSignOutAttempt {
        notificationDelivery.perAccountSyncedFingerprints.remove(account)
        notificationDelivery.localNotificationSettings = null
        val disabled =
            runCatching {
                withContext(marmotIoDispatcher) { runtime.marmot.setNativePushEnabled(account, false) }
            }
        val settings = disabled.getOrNull()
        if (settings?.nativePushEnabled != false) {
            return NativeSignOutAttempt(null, false, disabled.exceptionOrNull() as? CancellationException)
        }
        notificationDelivery.localNotificationSettings = settings
        // The native SQLite transaction committed false plus durable registration removals before returning.
        withContext(pushWakeStorageDispatcher) { runCatching { pushTokenStore.clearPendingDisable(account) } }
        val result =
            runCatching {
                withContext(marmotIoDispatcher) { runtime.marmot.signOut(account, deleteKeyPackages) }
            }
        return NativeSignOutAttempt(result, true, result.exceptionOrNull() as? CancellationException)
    }

    /** A partial or lost native reply requires durable status before the host can retire its session. */
    @Suppress("LongMethod", "ReturnCount")
    private suspend fun finishOwnedSignOutLocked(
        account: String,
        runtime: AppMarmotRuntime?,
        attempt: NativeSignOutAttempt,
        selectionGeneration: Long,
        draftsSaved: Boolean,
    ): OwnedSignOutCompletion {
        val result =
            attempt.result ?: return OwnedSignOutCompletion(
                SignOutCompletion.AccountCleanupIncomplete,
                nativeCancellation = attempt.cancellation,
            )
        val native = result.getOrNull()
        val partial = native?.localCleanup?.completed == false
        var knownSignedOut = native?.localCleanup?.completed == true
        if (partial || result.isFailure) {
            val observed =
                runCatching {
                    withContext(marmotIoDispatcher) { checkNotNull(runtime).marmot.listAccounts() }
                }.getOrNull()?.firstOrNull { it.label == account }
            if (observed?.signedOut != true) {
                return OwnedSignOutCompletion(
                    SignOutCompletion.AccountCleanupIncomplete,
                    nativeCancellation = attempt.cancellation,
                )
            }
            // A lost reply leaves remote cleanup unknown; only a typed unfinished-local report is partial.
            knownSignedOut = true
        }
        if (knownSignedOut) {
            accountListLifetime.advance {
                accounts = reconcileCachedAccountsAfterSignOut(accounts, account)
            }
        }
        clearSignedOutAccountMemory(account)
        clearInMemoryMediaCaches()
        AvatarImageLoader.clear()
        clearCrossAccountCaches()
        clearRevokedConversationShortcutsForAccount(
            accountRef = account,
            includeUnscopedLegacy = accounts.none { it.label != account && it.isSignedInSigningAccount() },
        )
        clearContactPrivateDetailsForAccount(account)
        val refreshed = accountsAfterSignOut(account, knownSignedOut)
        val next = signOutOutcome(refreshed, account).nextActiveRef
        // Do not advance handoff here: an explicit selection queued after admission must remain newer.
        activeAccountRef = null
        preferences.edit().remove(ACTIVE_ACCOUNT_KEY).apply()
        reloadMediaAutoDownloadMatrix()
        phase = AppPhase.Onboarding
        wipeDecryptedMediaFromDisk()
        if (next == null && refreshed.isEmpty()) runCatching { pushTokenStore.clear() }
        notificationDelivery.localNotificationSettings = null
        if (!draftsSaved) present(R.string.toast_draft_save_failed)
        return OwnedSignOutCompletion(
            signOutCompletion(native),
            next,
            selectionGeneration,
            attempt.cancellation,
        )
    }

    /** Retires only the signed-out account's transient drafts and controllers, preserving a replacement account. */
    private fun clearSignedOutAccountMemory(account: String) {
        attachmentCollections.forgetAccount(account)
        composerExpansionStateRetention.removeAccount(account)
        composerDraftExpansionBridge.removeAccount(account)
        pendingMessageEditHandoff.removeAccount(account)
        notificationReplyDraftHandoff.removeAccount(account)
        draftWriter.removeAccount(account)
        forgetStickers(account)
        conversationDictation.onAccountUnavailable(account)
        stopTtsForRemovedAccount(account)
    }

    /** Leaves groups, deletes relay KeyPackages and reports native and Android cleanup outcomes. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    // One cancellation-safe bracket owns wipe, editor purge, account switch, and recovery.
    internal suspend fun signOutAndWipeActiveAccount(): AccountWipeCompletion? {
        val requested = activeAccountRef ?: return null
        val runtime = marmotRuntime
        val epoch = runtimeGeneration
        // A cancelled setup child may need lifetime for final native preview cleanup before it can join.
        accountSetup.close()
        return withAccountLifetime {
            if (activeAccountRef != requested || marmotRuntime !== runtime || runtimeGeneration != epoch) {
                return@withAccountLifetime null
            }
            signOutAndWipeActiveAccountLocked(requested)
        }
    }

    private suspend fun signOutAndWipeActiveAccountLocked(wipedRef: String): AccountWipeCompletion? {
        var admitted = false
        return runCatchingCancellable {
            withRevokedPinnedTarget(wipedRef) {
                admitted = true
                finishRevokedAccountWipe(wipedRef)
            }
        }.getOrElse { failure ->
            if (admitted) throw failure
            appStateDebug(failure) { "account wipe credential revocation failed before native work" }
            null
        }
    }

    /** Holds launcher revocation through cleanup and publishes native removal first. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun finishRevokedAccountWipe(wipedRef: String): AccountWipeCompletion? {
        if (!prepareSharedPlaintextWipe(wipedRef)) return null
        val wipedShareAccount = accounts.firstOrNull { it.label == wipedRef }?.accountIdHex
        conversationDictation.onAccountUnavailable(wipedRef)
        clearInMemoryMediaCaches()
        try {
            val restartNotifications = prepareForDestructiveAccountWipe(wipedRef)
            val wipeResult =
                notificationDelivery.nativePushSyncMutex.withSerializedNativePushWipe {
                    runCatching { marmotIo { signOutAndWipe(wipedRef) } }
                        .onSuccess { outcome ->
                            if (outcome.localCleanup.completed) {
                                // Publish native removal before encrypted push cleanup can throw.
                                accountListLifetime.advance { accounts = accounts.filterNot { it.label == wipedRef } }
                                runCatching { pushTokenStore.clearPendingDisable(wipedRef) }
                                // Native wipe invalidates registration while the push-sync mutex is held.
                                notificationDelivery.perAccountSyncedFingerprints.remove(wipedRef)
                                runCatching { pushTokenStore.clear() }
                            }
                        }
                }
            val failure = wipeResult.exceptionOrNull()
            if (failure != null) {
                rethrowIfCancellation(failure)
                appStateDebug(failure) {
                    "signOutAndWipe failed account=${wipedRef.take(8)}: ${failure.readableMessage()}"
                }
                restoreAfterFailedDestructiveAccountWipe(wipedRef, restartNotifications)
                return null
            }
            val outcome =
                wipeResult.getOrNull() ?: run {
                    restoreAfterFailedDestructiveAccountWipe(wipedRef, restartNotifications)
                    return null
                }
            if (!outcome.localCleanup.completed) {
                discardRejectedSharedPlaintextWipe(wipedRef)
                appStateDebug {
                    "signOutAndWipe kept active account=${wipedRef.take(8)} because engine cleanup did not complete"
                }
                restoreAfterFailedDestructiveAccountWipe(wipedRef, restartNotifications)
                return AccountWipeCompletion(outcome, wipeReport(outcome))
            }
            val mediaWipeFailed = wipeDecryptedMediaFromDisk(wipedRef)
            chatMutePreferences.removeAccount(wipedRef)
            defaultDisappearingMessagesPreferences.removeAccount(wipedRef)
            attachmentCollections.forgetAccount(wipedRef)
            composerExpansionStateRetention.removeAccount(wipedRef)
            composerDraftExpansionBridge.removeAccount(wipedRef)
            pendingMessageEditHandoff.removeAccount(wipedRef)
            notificationReplyDraftHandoff.removeAccount(wipedRef)
            draftWriter.removeAccount(wipedRef)
            forgetStickers(wipedRef)
            clearRevokedConversationShortcutsForAccount(
                accountRef = wipedRef,
                includeUnscopedLegacy = accounts.none { it.label != wipedRef && it.isSignedInSigningAccount() },
            )
            AvatarImageLoader.clear()
            clearCrossAccountCaches()
            stopTtsForRemovedAccount(wipedRef)
            clearContactPrivateDetailsForAccount(wipedRef)
            memberMutePreferences.clearAccount(wipedRef)
            publicJpegPreviewPreferences
                .retainAccounts(accounts.filter { it.label != wipedRef }.map { it.accountIdHex })
                .forEach(publicJpegPreviewLoader::clearPixels)
            withContext(NonCancellable + Dispatchers.IO) {
                val alertsCleared =
                    runCatching {
                        conversationAlertPreferences.clearAccount(wipedRef)
                    }.getOrDefault(false)
                if (!alertsCleared) {
                    appStateDebug { "local alert preference cleanup will retry during account retention" }
                }
            }
            if (!clearHiddenMessagesForAccount(wipedRef)) {
                appStateDebug { "hidden-message cleanup failed after wipe account=${wipedRef.take(8)}" }
            }
            withContext(NonCancellable + Dispatchers.IO) {
                wipedShareAccount?.takeIf(String::isNotBlank)?.let { account ->
                    runCatching {
                        val files =
                            dev.ipf.whitenoise.android.share
                                .PrivateShareFiles(appContext)
                        files.leases.releaseAccount(account)
                    }.onFailure {
                        appStateDebug(it) { "private share purge failed after wipe: ${it.readableMessage()}" }
                    }
                }
                runCatching {
                    if (editorSessionStore.removeAccount(wipedRef)) {
                        editorSessionStore.sourceLeaseReferenceCounts()?.let(editorSourceStore::reconcile)
                    }
                }.onFailure {
                    appStateDebug(it) { "editor purge failed after wipe: ${it.readableMessage()}" }
                }
            }
            val refreshedAccountsResult =
                runCatchingCancellable {
                    marmotIo(MarmotTraceSection.ACCOUNT_LIST) { listAccounts() }
                }
            val refreshedAccounts = refreshedAccountsResult.getOrDefault(emptyList())
            val retainedAccountGeneration =
                accountListLifetime.advance {
                    publishRefreshedAccountList(refreshedAccountsResult)
                    releaseContactClearGuardForSignedInAccounts(refreshedAccounts)
                    // An empty list here can mean "no accounts left" or "the read failed" -- retention
                    // is an allow-list, so only prune member mutes on a genuine successful read. A
                    // transient failure must not wipe every other account's mutes (#2782 follow-up).
                    refreshedAccountsResult.getOrNull()?.let { successfulAccounts ->
                        retainMemberMutesForAccounts(successfulAccounts.map(AccountSummaryFfi::label))
                    }
                }
            refreshedAccountsResult.getOrNull()?.let { successfulAccounts ->
                retainProfileAlertsForAccounts(successfulAccounts) {
                    accountListLifetime.isCurrent(retainedAccountGeneration)
                }
            }
            refreshAccountUnreadCounts(refreshedAccounts)
            val next = signOutOutcome(refreshedAccounts, wipedRef).nextActiveRef
            activeAccountRef = next
            preferences
                .edit()
                .apply {
                    if (next == null) remove(ACTIVE_ACCOUNT_KEY) else putString(ACTIVE_ACCOUNT_KEY, next)
                }.apply()
            reloadMediaAutoDownloadMatrix()
            phase = if (next == null) AppPhase.Onboarding else AppPhase.Ready
            next?.let { label ->
                refreshedAccounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
            }
            networkNotificationRecoverySuppressed = false
            if (restartNotifications) startNotificationListener()
            notificationNetworkRecovery.resumeIfPending()
            refreshLocalNotificationSettings()
            diagnostics.refresh(marmot())
            return AccountWipeCompletion(outcome, wipeReport(outcome, localMediaFailure(mediaWipeFailed)))
        } finally {
            // Backstop: the suppression bracket must not outlive this call
            // whatever throws above — a latched flag would silently disable
            // network-restore recovery until process death.
            networkNotificationRecoverySuppressed = false
        }
    }

    /**
     * Publishes the account list a post-wipe read produced. A failed read leaves an empty list that is not
     * authoritative, so surviving accounts' automatic work waits for the next successful read instead of ending.
     */
    internal fun publishRefreshedAccountList(result: Result<List<AccountSummaryFfi>>) {
        accounts = result.getOrDefault(emptyList())
        accountListLoaded = result.isSuccess
    }

    /** Commits one host preference transaction off-main and times actual durable completion. */
    @Suppress("TooGenericExceptionCaught") // Editor creation/configuration can throw provider-specific failures.
    private fun persistHostSetting(edit: SharedPreferences.Editor.() -> Unit) {
        val attempt = beginHostPerformance(HostPerformanceOperationFfi.SETTINGS_SAVE)
        val editor =
            try {
                preferences.edit().apply(edit)
            } catch (throwable: Throwable) {
                attempt.failure()
                throw throwable
            }
        mutationsScope
            .launch {
                hostPreferenceCommitMutex.withLock {
                    withContext(Dispatchers.IO) {
                        completeHostPreferenceCommit(attempt, editor::commit)
                    }
                }
            }.invokeOnCompletion { cause ->
                if (cause is CancellationException) attempt.cancel()
            }
    }

    fun updateDeveloperMode(enabled: Boolean) {
        developerMode = enabled
        persistHostSetting { putBoolean(DEVELOPER_MODE_KEY, enabled) }
    }

    fun updateStreamingDebugMode(enabled: Boolean) {
        streamingDebugMode = enabled
        persistHostSetting { putBoolean(STREAMING_DEBUG_MODE_KEY, enabled) }
    }

    fun updateForceIncognitoKeyboard(enabled: Boolean) {
        forceIncognitoKeyboard = enabled
        persistHostSetting { putBoolean(FORCE_INCOGNITO_KEYBOARD_KEY, enabled) }
    }

    fun updateAllowChatScreenshotsInChats(enabled: Boolean) {
        allowChatScreenshotsInChats = enabled
        persistHostSetting { ChatScreenshotPreferences.editAllowChatScreenshots(this, enabled) }
        onAllowChatScreenshotsChanged?.invoke(enabled)
    }

    /** Refreshes device-scoped native receipts and the independent diagnostic log choice. */
    suspend fun refreshSecurityPrivacySettings() {
        diagnostics.refresh(marmot())
        auditLogSettingsMutex.withLock {
            // A failed re-read keeps what is on screen rather than presenting a settled choice as off.
            runCatchingCancellable { marmotIo { auditLogSettings() } }.getOrNull()?.let {
                auditLogSettings = it
                DictationDiagnostics.setEnabled(it.enabled && auditUploadConsent.granted)
                DmCreationDiagnostics.setEnabled(it.enabled && auditUploadConsent.granted)
            }
        }
    }

    /** Gives the diagnostics screen a fresh controller bound to the runtime it opened with. */
    internal fun auditDiagnosticsSettings(): AuditDiagnosticsSettings {
        val generation = runtimeGeneration
        return AuditDiagnosticsSettings(
            loadRetention = { auditDiagnosticsIo(generation) { auditLogRetention() } },
            saveRetention = { days, confirmedShortening ->
                auditDiagnosticsIo(generation) {
                    val current = auditLogRetention()
                    check(days >= current.maxAgeDays || confirmedShortening) {
                        "Audit retention shortening requires confirmation"
                    }
                    if (days == current.maxAgeDays) current else setAuditLogRetention(current.copy(maxAgeDays = days))
                }
            },
            loadDelivery = {
                auditDiagnosticsIo(generation) {
                    if (auditUploadConsent.granted && auditLogSettings().enabled) {
                        summarizeAuditDelivery(auditDeliveryStatusV5())
                    } else {
                        AuditDeliverySummary(AuditDeliveryState.OFF)
                    }
                }
            },
            retryDelivery = {
                auditDiagnosticsIo(generation) {
                    val sharingEnabled = auditUploadConsent.granted && auditLogSettings().enabled
                    if (sharingEnabled && auditDeliveryStatusV5().any { it.destinationConfigured && it.paused }) {
                        postAuditLogTrackerUpdateV5()
                    }
                }
            },
        )
    }

    /** Runs against the captured runtime generation so a replaced screen cannot act on its successor. */
    private suspend fun <T> auditDiagnosticsIo(
        generation: Int,
        block: suspend MarmotInterface.() -> T,
    ): T =
        marmotIo {
            if (generation != runtimeGeneration) throw CancellationException("Audit settings runtime changed")
            block()
        }

    internal fun recordProductEvent(
        event: dev.ipf.marmotkit.ProductEventFfi,
        ticket: Long?,
    ) {
        val runtime = marmotRuntime?.marmot ?: return
        notificationScope.launch(Dispatchers.IO) {
            runCatchingCancellable {
                diagnostics.observations.record(ticket) { runtime.recordProductEvent(event) }
            }
        }
    }

    suspend fun setAuditLogsEnabled(enabled: Boolean): Boolean =
        runCatching {
            // Stop the local sink before a revoke/save, including a failed native update.
            DictationDiagnostics.setEnabled(false)
            DmCreationDiagnostics.setEnabled(false)
            // setAuditLogSettings now applies the switch to every live session
            // in place via a recorder hot-swap (enable → live recorder,
            // disable → flush + close); no session reopen or runtime restart
            // required on the host side.
            updateAuditLogSettingsSerialized(
                mutex = auditLogSettingsMutex,
                cachedSettings = { auditLogSettings },
                storeCachedSettings = {
                    auditLogSettings = it
                    DictationDiagnostics.setEnabled(it.enabled && auditUploadConsent.granted)
                    DmCreationDiagnostics.setEnabled(it.enabled && auditUploadConsent.granted)
                },
                loadFromEngine = { marmotIo { auditLogSettings() } },
                transform = { it.copy(enabled = enabled) },
                persistToEngine = { settings ->
                    withContext(Dispatchers.IO) {
                        val runtime = marmot()
                        auditUploadConsent.applyChoice(
                            settings,
                            configureUpload = { runtime.configureAuditRuntime(uploadConsentGranted = it) },
                            persistSettings = { runtime.setAuditLogSettings(it) },
                        )
                    }
                },
            )
            presentTransient(R.string.toast_security_privacy_updated)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_update_security_privacy, "SECURITY_PRIVACY_UPDATE", it)
            false
        }

    fun updateThemeMode(mode: AppThemeMode) {
        themeMode = mode
        persistHostSetting { putString(THEME_MODE_KEY, mode.preferenceValue) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun updateFontScale(scale: AppFontScale) {
        if (fontScale == scale) return
        fontScale = scale
        val revision = ++fontScaleSaveRevision
        val attempt = beginHostPerformance(HostPerformanceOperationFfi.SETTINGS_SAVE)
        mutationsScope
            .launch(start = CoroutineStart.ATOMIC) {
                var reconciled = false
                try {
                    hostPreferenceCommitMutex.withLock {
                        var committed = false
                        var commitAttempted = false
                        try {
                            withContext(Dispatchers.IO) {
                                runCatchingCancellable {
                                    val editor = preferences.edit().putString(FONT_SCALE_KEY, scale.preferenceValue)
                                    commitAttempted = true
                                    completeHostPreferenceCommit(attempt) { editor.commit().also { committed = it } }
                                }.onFailure { attempt.failure() }
                            }
                        } finally {
                            // The dispatcher may discard a completed IO result on cancellation.
                            // Finalize the actual commit while still owning its serialization lock.
                            withContext(NonCancellable) {
                                if (committed) {
                                    durableFontScale = scale
                                } else {
                                    if (commitAttempted) {
                                        // A failed disk commit can still change SharedPreferences' memory map.
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                preferences
                                                    .edit()
                                                    .putString(FONT_SCALE_KEY, durableFontScale.preferenceValue)
                                                    .commit()
                                            }
                                        }
                                    }
                                    if (revision == fontScaleSaveRevision) {
                                        fontScale = durableFontScale
                                        if (commitAttempted) presentTransient(R.string.font_size_save_failed)
                                    }
                                }
                                reconciled = true
                            }
                        }
                    }
                } finally {
                    if (!reconciled) {
                        // Cancellation before acquiring the mutex must wait for prior saves to settle.
                        withContext(NonCancellable) {
                            hostPreferenceCommitMutex.withLock {
                                if (revision == fontScaleSaveRevision) fontScale = durableFontScale
                            }
                        }
                    }
                }
            }.invokeOnCompletion { cause ->
                if (cause is CancellationException) attempt.cancel()
            }
    }

    fun updateAppFont(font: AppFont) {
        appFont = font
        persistHostSetting { putString(APP_FONT_KEY, font.preferenceValue) }
    }

    fun acknowledgeTtsTrustWarning(enginePackage: String) {
        ttsWarningPreferences.acknowledge(enginePackage)
    }

    fun selectTtsEngine(enginePackage: String) {
        mutationsScope.launch {
            selectTtsEngineLocked(enginePackage)
        }
    }

    internal suspend fun selectTtsEngineLocked(enginePackage: String) {
        ttsRefreshMutex.withLock {
            val current =
                TtsEngineSelectionSnapshot(
                    resolution = ttsResolution,
                    selectedOverride = ttsEnginePreferences.selectedEngine(),
                )
            val candidate = resolveTtsOnDispatcher { ttsEngineResolver.resolve(enginePackage) }
            when (val outcome = adoptTtsEngineSelection(current, candidate, enginePackage)) {
                is TtsEngineSelectionResult.Adopted -> {
                    ttsEnginePreferences.setSelectedEngine(outcome.selectedOverride)
                    publishTtsResolution(outcome.resolution)
                    outcome.releasedHandles.forEach { handle -> runCatching { handle.release() } }
                }
                is TtsEngineSelectionResult.Retained -> {
                    withContext(Dispatchers.IO) {
                        candidate.handle?.let { handle -> runCatching { handle.release() } }
                    }
                }
            }
        }
    }

    suspend fun refreshTtsAvailability() =
        ttsRefreshMutex.withLock {
            val previousHandle = ttsResolution?.handle
            val candidateHandles = mutableListOf<TtsEngineHandle>()
            var adoptedHandle: TtsEngineHandle? = null
            var replacementPublished = false
            try {
                val bootstrap = resolveTtsOnDispatcher { ttsEngineResolver.resolve(enginePackage = null) }
                bootstrap.handle?.let(candidateHandles::add)
                val selectedOverride = ttsEnginePreferences.selectedEngine()
                val preferredPackage =
                    ttsEngineResolver.preferredEnginePackage(
                        engines = bootstrap.engines,
                        defaultPackage = bootstrap.defaultEnginePackage,
                        selectedOverride = selectedOverride,
                    )
                val replacement =
                    when {
                        preferredPackage == null -> bootstrap.copy(handle = null)
                        preferredPackage == bootstrap.handle?.enginePackage -> bootstrap
                        else -> {
                            val selected = resolveTtsOnDispatcher { ttsEngineResolver.resolve(preferredPackage) }
                            selected.handle?.let(candidateHandles::add)
                            if (selected.handle?.enginePackage == preferredPackage) {
                                selected
                            } else {
                                bootstrap.copy(handle = null)
                            }
                        }
                    }

                // Same engine package resolved again: keep the ATTACHED
                // handle, so an availability refresh (settings ON_RESUME,
                // foreground) never swaps engines under in-flight speech.
                // resolve() always mints a new handle, so identity alone
                // can't provide this — the freshly resolved duplicate is
                // released with the other unadopted candidates below.
                val published =
                    if (previousHandle != null && previousHandle.enginePackage == replacement.handle?.enginePackage) {
                        replacement.copy(handle = previousHandle)
                    } else {
                        replacement
                    }
                adoptedHandle = published.handle
                publishTtsResolution(published)
                replacementPublished = true
            } finally {
                candidateHandles
                    .filter { it !== adoptedHandle }
                    .forEach { handle -> runCatching { handle.release() } }
                if (replacementPublished && previousHandle !== adoptedHandle) {
                    runCatching { previousHandle?.release() }
                }
            }
        }

    /**
     * Toggle one cell of the active account's auto-download matrix, persist it
     * immediately, and update the observable state so open bubbles re-gate.
     */
    fun setMediaAutoDownload(
        type: MediaAutoDownloadType,
        network: MediaAutoDownloadNetwork,
        enabled: Boolean,
    ) {
        val updated = mediaAutoDownloadMatrix.withToggle(type, network, enabled)
        if (updated == mediaAutoDownloadMatrix) return
        mediaAutoDownloadMatrix = updated
        // Don't persist to the shared "default" bucket when the active account's
        // hex can't be resolved yet (early bootstrap, or right after a switch
        // before refreshAccounts) — that silently diverges from the per-account
        // value. The in-memory matrix still updates so the UI reflects the toggle;
        // a later toggle once the account resolves persists it to the right bucket.
        val key = mediaAutoDownloadPrefKeyOrNull(activeAccountRef) ?: return
        persistHostSetting { editMediaAutoDownloadMatrix(this, key, updated) }
        refreshNativeAttachmentPermissions()
    }

    fun updateEnterKeyBehavior(behavior: EnterKeyBehavior) {
        enterKeyBehavior = behavior
        persistHostSetting { putString(ENTER_KEY_BEHAVIOR_KEY, behavior.preferenceValue) }
    }

    /** Sets the clock system for every time label (#3602); Automatic follows Android and the region. */
    fun updateTimeFormat(format: TimeFormatPreference) {
        DateTimeFormatState.timeFormat = format
        persistHostSetting { putString(TIME_FORMAT_KEY, format.preferenceValue) }
    }

    /** Sets the numeric date order for compact date labels (#3602); Automatic follows the region. */
    fun updateDateFormat(format: DateFormatPreference) {
        DateTimeFormatState.dateFormat = format
        persistHostSetting { putString(DATE_FORMAT_KEY, format.preferenceValue) }
    }

    /**
     * Update the outgoing-media quality ceiling (image downscale/JPEG quality
     * and voice-note bitrate). Persists immediately so the selection survives
     * process death; the next send reads [mediaQuality] directly.
     */
    fun updateMediaQuality(quality: MediaQuality) {
        mediaQuality = quality
        persistHostSetting { putString(MEDIA_QUALITY_KEY, quality.preferenceValue) }
    }

    /**
     * Whether an incoming attachment of [type] should be fetched/decrypted
     * automatically given the active account's matrix and every network the
     * live connection currently matches (most-restrictive rule, issue #407).
     */
    fun shouldAutoDownloadMedia(type: MediaAutoDownloadType): Boolean =
        !automaticAttachmentDownloadsPaused() &&
            mediaAutoDownloadMatrix.shouldAutoDownload(type, activeNetworkTypes())

    /**
     * True when the device currently has an active network connection. Used to
     * pick a "couldn't verify (no network)" message over a generic resolution
     * failure when an online validation (e.g. lud16, issue #795) fails. Reads
     * the callback-maintained snapshot — no binder IPC on the caller's thread.
     */
    fun hasActiveNetwork(): Boolean = hasActiveNetworkSnapshot

    /**
     * True when Android currently exposes a validated non-VPN internet
     * upstream. This is stricter than [hasActiveNetwork]: an app-default VPN
     * can remain present (and retain stale validation) after airplane mode
     * removes its Wi-Fi/cellular upstream. Network-gated setup uses this signal
     * so it fails fast without rejecting a healthy VPN whose upstream remains
     * validated. The active-default bit is also required because OEM network
     * stacks can briefly retain a validated physical handle after its route is
     * gone; such a handle cannot make setup reachable on its own.
     */
    fun hasValidatedInternet(): Boolean =
        hasUsableValidatedInternet(
            hasActiveDefaultNetwork = hasActiveNetworkSnapshot,
            hasValidatedPhysicalNetwork = validatedInternetNetworks.hasValidatedInternet(),
        )

    internal fun publicJpegPreviewNetworkAllowed(): Boolean =
        hasValidatedInternet() &&
            activeNetworkTypesSnapshot == setOf(MediaAutoDownloadNetwork.WiFi) &&
            shouldAutoDownloadMedia(MediaAutoDownloadType.Image)

    /**
     * Every [MediaAutoDownloadNetwork] the active connection currently matches.
     * A single connection can match several at once (e.g. cellular that is both
     * roaming and metered). An empty set (no/unknown connection) makes the
     * decision conservatively fall to "do not auto-download".
     *
     * Reads the snapshot maintained by [registerActiveNetworkListener]: the
     * gate runs during bubble composition, and querying ConnectivityManager
     * inline (getSystemService/activeNetwork/getNetworkCapabilities) is three
     * synchronous binder IPCs per call (#984).
     */
    internal fun activeNetworkTypes(): Set<MediaAutoDownloadNetwork> = activeNetworkTypesSnapshot

    private val previewNetworkTypesMutable = MutableStateFlow<Set<MediaAutoDownloadNetwork>>(emptySet())
    internal val publicJpegPreviewNetworkTypes = previewNetworkTypesMutable.asStateFlow()

    // Callback-maintained mirror of the default network's state, volatile so
    // composition-time reads see the connectivity thread's latest write.
    @Volatile
    private var activeNetworkTypesSnapshot: Set<MediaAutoDownloadNetwork> = emptySet()
        set(value) {
            field = value
            previewNetworkTypesMutable.value = value
        }

    @Volatile
    private var hasActiveNetworkSnapshot = false

    private val activeDefaultNetwork = ActiveDefaultNetworkTracker()
    private val validatedInternetNetworks = ValidatedInternetNetworkTracker()
    private val usableValidatedInternetRecovery = UsableValidatedInternetRecoveryTracker()
    private val validatedConnectivityRecoveryGenerationMutable = MutableStateFlow(0L)

    /** Monotonic usable-internet recovery edge consumed by pending foreground sends. */
    internal val validatedConnectivityRecoveryGeneration: StateFlow<Long> =
        validatedConnectivityRecoveryGenerationMutable.asStateFlow()

    /** Coordinates retained receiver-gated catch-up outside the main state source. */
    private val notificationNetworkRecovery =
        NotificationNetworkRecoveryCoordinator(
            scope = notificationScope,
            shouldContinue = { !networkNotificationRecoverySuppressed && hasValidatedInternet() },
            wakeDurableOutbound = {
                // One connectivity edge needs one outbound wake. Catch-up owns
                // later bounded retries, so they cannot amplify transport work.
                val wake = runCatchingCancellable { marmotIo { notifyConnectivityRestored() } }
                wake.onFailure { throwable ->
                    appStateDebug(throwable) {
                        "durable outbound connectivity wake failed: ${throwable.readableMessage()}"
                    }
                }
                wake.isSuccess
            },
            ensureNotificationReceiverActive = ::ensureNotificationReceiverForNetworkReconnect,
            catchUpAccounts = { pushWakeRecovery.catchUpAfterNetworkReconnect() },
            awaitRetry = { generation, attempt ->
                appStateDebug { "notification network recovery pending attempt=$attempt" }
                awaitNotificationRetryWindow(
                    retryWake = validatedConnectivityRecoveryGeneration,
                    capturedGeneration = generation,
                    backoffMillis = notificationNetworkRecoveryRetryDelayMillis(attempt),
                )
            },
            onRecoveryAttemptStarted = { networkGeneration ->
                pushWakeRecovery.noteNetworkRecoveryAttempt(networkGeneration)
            },
            onRecoveryExhausted = { networkGeneration, _ ->
                pushWakeRecovery.noteNetworkRecoveryExhausted(networkGeneration)
            },
            onDrainCompleted = { pushWakeRecovery.schedulePendingPushWakeCatchUpDrain() },
            diagnostics = notificationNetworkRecoveryDiagnostics,
        )

    internal val runtimeListenerTeardownOwner =
        AppRuntimeListenerTeardownOwner(
            cancelNetworkRecovery = { notificationNetworkRecovery.cancelAndJoin() },
            cancelPushWakeDrain = { pushWakeRecovery.cancelDrainAndJoin() },
            cancelListener = { notificationJob.cancelAndJoin() },
            clearUnreadRefresh = { unreadRefreshScheduler.cancelAndClear() },
            receiverActive = notificationReceiverActive,
        )

    /** Process-owned recovery attribution shared by projections and Compose. */
    internal val recoveryDiagnostics: NotificationNetworkRecoveryCoordinator
        get() = notificationNetworkRecovery

    /**
     * Reactive active-default-route availability, Android internet validation,
     * and aggregate relay fallback inputs for the chat-list connectivity banner.
     * Foreground readiness and relay sampling use the active route even when
     * OS validation probes fail. Active-account attempt and
     * application-readiness phases live in [ChatsController]; neither a local
     * subscription nor this device-wide relay count can mark that account
     * ready. The pool count is retained only to trigger an active-account
     * re-check when no relay is currently available.
     */
    private val connectivitySignalOwner = ConnectivitySignalOwner()
    val connectivitySignals = connectivitySignalOwner.signals

    /** Process-owned, bounded send tracing that survives conversation-screen disposal. */
    internal val pendingSendDiagnostics =
        createPendingSendDiagnosticTracker(mutationsScope) { connectivitySignals.value.toPerformanceConnectivity() }

    private fun updateConnectivitySignals(
        hasValidatedInternet: Boolean? = null,
        relaysConnected: Boolean? = null,
        hasActiveNetwork: Boolean? = null,
    ) = connectivitySignalOwner.update(hasValidatedInternet, relaysConnected, hasActiveNetwork)

    /**
     * Refresh [connectivitySignals] from the engine's relay-health snapshot.
     * No-op while backgrounded or without an active default route; OS internet
     * validation is not required. A failed read keeps the previous value rather
     * than flashing a guess, and a snapshot that straddled a
     * network transition is discarded via [connectivityNetworkGeneration].
     * The banner only changes state on evidence from the current network.
     */
    suspend fun refreshRelayConnectivity() {
        // No active default route needs no sample: the write clamp already pinned
        // the signal false, and pool counts read without a route are stale.
        if (!appInForeground || !connectivitySignals.value.hasAppNetwork) return
        val generation = connectivitySignalOwner.captureNetworkGeneration()
        val health = runCatchingCancellable { marmotIo { relayHealth() } }.getOrNull()
        // A snapshot that straddled a network transition describes the wrong
        // network; drop it — the next poll is ≤2s out.
        if (health == null || !connectivitySignalOwner.isNetworkGenerationCurrent(generation)) return
        updateConnectivitySignals(
            relaysConnected =
                relaysConnectedFromHealth(
                    connectedRelays = health.connected.toInt(),
                    totalRelays = health.totalRelays.toInt(),
                ),
        )
    }

    /**
     * Register the process-lifetime default-network callback that keeps
     * [activeNetworkTypesSnapshot]/[hasActiveNetworkSnapshot] current. Runs off
     * the main thread (see `init`): registration and the one-shot seed query
     * are themselves binder IPCs. The callback is never unregistered because
     * [WhiteNoiseAppState] has no teardown — it lives as long as the process.
     */
    private fun registerActiveNetworkListener() {
        val cm =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return
        // Seed before registering so the first composition doesn't read an
        // empty snapshot while the callback's initial dispatch is in flight;
        // that dispatch then overwrites the seed with the same current state.
        runCatchingCancellable {
            val network = cm.activeNetwork
            hasActiveNetworkSnapshot = activeDefaultNetwork.seed(network?.networkHandle)
            updateConnectivitySignals(
                hasValidatedInternet = hasValidatedInternet(),
                hasActiveNetwork = hasActiveNetworkSnapshot,
            )
            activeNetworkTypesSnapshot =
                network?.let { cm.getNetworkCapabilities(it) }?.let(::networkTypesFor) ?: emptySet()
            if (network != null) pushWakeRecovery.schedulePendingPushWakeCatchUpDrain()
        }.onFailure {
            // Restricted profiles can throw from the connectivity queries; the
            // empty snapshots are the same conservative offline default the
            // callback path falls back to.
            appStateDebug(it) { "network snapshot seed failed: ${it.readableMessage()}" }
        }
        runCatchingCancellable {
            cm.registerDefaultNetworkCallback(
                object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        // A new identity waits conservatively for capabilities. A duplicate announcement
                        // preserves capabilities already known for this same default network.
                        val update = activeDefaultNetwork.available(network.networkHandle)
                        if (update.identityChanged) connectivitySignalOwner.noteNetworkIdentityChange()
                        noteActiveNetworkSnapshot(
                            isOnline = true,
                            networkTypes = if (update.identityChanged) emptySet() else activeNetworkTypesSnapshot,
                        )
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        networkCapabilities: android.net.NetworkCapabilities,
                    ) {
                        if (!activeDefaultNetwork.isCurrent(network.networkHandle)) return
                        noteActiveNetworkSnapshot(
                            isOnline = true,
                            networkTypes = networkTypesFor(networkCapabilities),
                        )
                    }

                    override fun onLost(network: android.net.Network) {
                        val replacement = runCatching { cm.activeNetwork }.getOrNull()?.takeUnless { it == network }
                        val update =
                            activeDefaultNetwork.lost(
                                networkHandle = network.networkHandle,
                                replacementNetworkHandle = replacement?.networkHandle,
                            ) ?: return
                        if (update.identityChanged) connectivitySignalOwner.noteNetworkIdentityChange()
                        noteActiveNetworkSnapshot(isOnline = update.hasActiveNetwork)
                    }
                },
            )
        }.onFailure {
            // Too many callbacks / SecurityException: keep the seeded snapshot
            // rather than crash; it just won't track later connectivity changes.
            appStateDebug(it) { "default network callback registration failed: ${it.readableMessage()}" }
        }
        registerValidatedInternetListener(cm)
    }

    /**
     * Track validated physical upstreams independently from the app-default
     * network. A VPN is allowed to remain the default network, but it cannot by
     * itself prove that packets can leave the device: a stranded tunnel may
     * keep stale INTERNET/VALIDATED capabilities after losing its upstream.
     *
     * VALIDATED is mutable, so it is inspected in capability callbacks instead
     * of being placed in the request. NOT_VPN selects the Wi-Fi/cellular/etc.
     * upstream beneath a healthy tunnel and excludes the stale tunnel itself.
     */
    @Suppress("DEPRECATION") // Only platform API that can seed every non-default upstream before callbacks arrive.
    private fun registerValidatedInternetListener(cm: android.net.ConnectivityManager) {
        val request =
            android.net.NetworkRequest
                .Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
        val callback =
            object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    networkCapabilities: android.net.NetworkCapabilities,
                ) {
                    validatedInternetNetworks.update(
                        networkHandle = network.networkHandle,
                        available = networkCapabilities.providesValidatedNonVpnInternet(),
                    )
                    noteUsableValidatedInternetSnapshot()
                }

                override fun onLost(network: android.net.Network) {
                    validatedInternetNetworks.remove(network.networkHandle)
                    noteUsableValidatedInternetSnapshot()
                }
            }
        runCatchingCancellable {
            validatedInternetNetworks.seedAtomically {
                // Register before reading the snapshot so no transition can be
                // missed. Callback mutations block on the tracker's monitor and
                // are applied after this seed, so a concurrent loss cannot be
                // overwritten by stale capabilities from allNetworks.
                cm.registerNetworkCallback(request, callback)
                cm.allNetworks.associate { network ->
                    network.networkHandle to
                        (cm.getNetworkCapabilities(network)?.providesValidatedNonVpnInternet() == true)
                }
            }
            usableValidatedInternetRecovery.seed(
                hasActiveDefaultNetwork = hasActiveNetworkSnapshot,
                hasValidatedPhysicalNetwork = validatedInternetNetworks.hasValidatedInternet(),
            )
            noteUsableValidatedInternetSnapshot()
        }.onFailure {
            // Conservative failure mode: setup stays gated rather than treating
            // an unverified or VPN-only network as usable internet.
            appStateDebug(it) { "validated internet callback registration failed: ${it.readableMessage()}" }
        }
    }

    private fun noteActiveNetworkSnapshot(
        isOnline: Boolean,
        networkTypes: Set<MediaAutoDownloadNetwork>? = null,
    ) {
        if (!isOnline) {
            hasActiveNetworkSnapshot = false
            activeNetworkTypesSnapshot = emptySet()
            noteUsableValidatedInternetSnapshot()
            return
        }
        hasActiveNetworkSnapshot = true
        activeNetworkTypesSnapshot = networkTypes ?: emptySet()
        noteUsableValidatedInternetSnapshot()
        pushWakeRecovery.schedulePendingPushWakeCatchUpDrain()
    }

    /** Publish aggregate connectivity and wake retry owners on a genuine recovery edge. */
    private fun noteUsableValidatedInternetSnapshot() {
        val recovery =
            usableValidatedInternetRecovery.update(
                hasActiveDefaultNetwork = hasActiveNetworkSnapshot,
                hasValidatedPhysicalNetwork = validatedInternetNetworks.hasValidatedInternet(),
            )
        updateConnectivitySignals(
            hasValidatedInternet = recovery.hasUsableInternet,
            hasActiveNetwork = hasActiveNetworkSnapshot,
        )
        requestNetworkPermissionRefresh()
        if (!recovery.restored) return
        pushWakeRecovery.resumePushWakeEpisodeFromLifecycle()
        AvatarLoadRecovery.onNetworkRestored()
        validatedConnectivityRecoveryGenerationMutable.update { generation -> generation + 1 }
        notificationNetworkRecovery.noteNetworkRestored(validatedConnectivityRecoveryGenerationMutable.value)
    }

    private fun networkTypesFor(caps: android.net.NetworkCapabilities): Set<MediaAutoDownloadNetwork> =
        MediaAutoDownloadNetwork.matching(
            hasWifiTransport = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI),
            hasCellularTransport = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR),
            isRoaming = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING),
            isMetered = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )

    /**
     * Refreshes [mediaAutoDownloadMatrix] for the current active account.
     * Called whenever [activeAccountRef] changes so per-account toggles follow
     * an account switch, sign-out, or cold-start bind.
     */
    private fun reloadMediaAutoDownloadMatrix() {
        mediaAutoDownloadMatrix = loadMediaAutoDownloadMatrix(activeAccountRef)
    }

    /**
     * Loads (or seeds) the matrix for [accountRef]. A never-before-seen account
     * is seeded from [MediaAutoDownloadMatrix.DEFAULT], migrating any value the
     * device still carries under the legacy 3-state key. The seeded matrix is
     * persisted so subsequent reads are stable.
     */
    internal fun loadMediaAutoDownloadMatrix(accountRef: String?): MediaAutoDownloadMatrix {
        val account = accountRef?.let { ref -> accounts.firstOrNull { it.label == ref }?.accountIdHex }
        val key = mediaAutoDownloadPrefKeyOrNull(accountRef)
        val stored = key?.let { preferences.getString(it, null) }
        if (key != null && stored != null) {
            // An untouched pre-#2699 default becomes the metered-safe one exactly once.
            return migratedMediaAutoDownloadMatrix(preferences, key, MediaAutoDownloadMatrix.fromPreference(stored))
        }
        // Only consume the legacy global key once a real account is bound, so
        // the migrated value lands on the user's account rather than the
        // transient pre-bootstrap "default" bucket.
        val seeded = if (account != null) migratedDefaultMatrix() else MediaAutoDownloadMatrix.DEFAULT
        // Don't seed the shared "default" bucket when the account is unresolved;
        // keep DEFAULT in memory and persist once a real account key exists.
        if (key != null) persistMediaAutoDownloadMatrix(preferences, key, seeded)
        return seeded
    }

    /**
     * One-time migration from the legacy [MEDIA_AUTO_DOWNLOAD_KEY] 3-state
     * policy: Always -> all cells ON, Never -> all cells OFF, WifiOnly/absent
     * -> [MediaAutoDownloadMatrix.DEFAULT]. The legacy key is dropped once read.
     */
    private fun migratedDefaultMatrix(): MediaAutoDownloadMatrix {
        val legacy = preferences.getString(MEDIA_AUTO_DOWNLOAD_KEY, null)
        if (legacy != null) preferences.edit().remove(MEDIA_AUTO_DOWNLOAD_KEY).apply()
        return when (legacy) {
            "always" -> allCellsMatrix(on = true)
            "never" -> allCellsMatrix(on = false)
            else -> MediaAutoDownloadMatrix.DEFAULT
        }
    }

    private fun allCellsMatrix(on: Boolean): MediaAutoDownloadMatrix {
        var matrix = MediaAutoDownloadMatrix(emptySet())
        for (type in MediaAutoDownloadType.entries) {
            for (network in MediaAutoDownloadNetwork.entries) {
                matrix = matrix.withToggle(type, network, on)
            }
        }
        return matrix
    }

    // Null when the account hex can't be resolved, so the load and write paths
    // can decline to touch the shared "default" bucket.
    private fun mediaAutoDownloadPrefKeyOrNull(accountRef: String?): String? {
        val account = accountRef?.let { ref -> accounts.firstOrNull { it.label == ref }?.accountIdHex } ?: return null
        return "$MEDIA_AUTO_DOWNLOAD_MATRIX_KEY_PREFIX$account"
    }

    fun updateLanguageTag(tag: String) {
        val normalized = tag.trim()
        languageTag = normalized
        persistHostSetting { putString(APP_LANGUAGE_TAG_KEY, normalized) }
        applyApplicationLanguageTag(normalized)
    }

    /** Applies the Activity visibility boundary immediately and schedules native lifecycle work off-main. */
    fun setAppInForeground(
        foreground: Boolean,
        dismissRetainedVisibleConversation: Boolean = true,
    ) {
        // Backgrounding flips off suppression without forgetting the still-open
        // chat; returning to the same Activity then resumes foreground
        // suppression without waiting for an unchanged Compose effect to re-run.
        // Task removal is the destructive lifecycle edge that clears the open
        // chat entirely, via onTaskRemoved(), so a foreground-service-kept
        // process cannot keep silencing that chat after the UI is gone (#821).
        updateNotificationSuppression(if (foreground) suppression.onForeground() else suppression.onBackground())
        AppUpdateForegroundState.isForeground = foreground
        if (!foreground) pushWakeRecovery.transferUnownedPushWakeRecovery()
        diagnostics.setForeground(foreground)
        marmotRuntime?.marmot?.let { engine ->
            notificationScope.launch {
                diagnostics.activity(
                    engine,
                    if (foreground) {
                        ProductAnalyticsActivityFfi.FOREGROUND
                    } else {
                        ProductAnalyticsActivityFfi.BACKGROUND
                    },
                )
            }
        }
        if (foreground) {
            // Resolve app lock before any automatic microphone recovery can start capture.
            appLock.onAppForegrounded()
            dictationDiagnosticLifecycle.foreground { conversationDictation }
            NotificationStreamForegroundService.onAppForegrounded(initializedConversationDictation())
            if (dismissRetainedVisibleConversation) {
                dismissVisibleConversationNotifications()
            }
        } else {
            appLock.onAppBackgrounded()
            conversationDictation.onAppBackgrounded()
            mutationsScope.launch { draftWriter.flush() }
            appLock.scheduleTtsStopAtLockBoundary()
        }
        if (foreground) {
            refreshLocalNotificationPermission()
            localNotificationPresenter.reconcileNotificationGroup()
            refreshNotificationBatteryPolicy()
            notificationScope.launch { catchUpAfterForegroundActivation() }
            scheduleForegroundDisplayThumbnailReconciliation()
        }
        if (shouldStartBackgroundConnectionForForeground(foreground)) {
            notificationDelivery.startBackgroundConnectionService()
        }
        if (foreground) notificationScope.launch { notificationDelivery.reconcileNotificationDeliveryMode() }
        if (!foreground) notificationScope.launch { refreshAppUpdateIfStale(notifyIfNewer = true) }
        if (foreground) notificationScope.launch { refreshAppUpdateIfStale(notifyIfNewer = false) }
        if (foreground) refreshAppSelfUpdateInstallPermission()
    }

    /** Restarts a retained persistent mode only while the app can render its notifications. */
    private fun shouldStartBackgroundConnectionForForeground(foreground: Boolean): Boolean =
        foreground && backgroundConnectionEnabled && localNotificationPermissionGranted

    private fun dismissVisibleConversationNotifications() {
        val target =
            visibleConversationDismissalTarget(
                appInForeground = appInForeground,
                appLockScreenVisible = appLock.screenVisible,
                activeAccountRef = activeConversationAccountRef,
                groupIdHex = activeConversationGroupIdHex,
            ) ?: return
        notificationScope.launch {
            dismissConversationNotifications(target.accountRef, target.groupIdHex)
        }
    }

    /** Complete a plain foreground resume after Activity intent routing settles. */
    internal fun dismissRetainedVisibleConversationNotifications() {
        dismissVisibleConversationNotifications()
    }

    /**
     * Reset the in-memory foreground/visible-conversation suppression state when
     * the app's task is swiped away from recents. A running foreground service
     * keeps the process (and this state) alive across task removal, and
     * Activity `onStop` is not guaranteed on that path, so without this an
     * open chat's suppression could persist after the UI is gone (issue #821).
     */
    fun onTaskRemoved() {
        updateNotificationSuppression(suppression.onTaskRemoved())
        conversationDictation.onTaskRemoved()
        // The app-state lifecycle is authoritative even if the TTS foreground
        // service has not started yet or its start was rejected.
        stopSpeaking()
        mutationsScope.launch { draftWriter.flush() }
    }

    private fun applyActiveConversationTransition(
        accountRef: String?,
        groupIdHex: String?,
    ) {
        conversationOpenDismissals.invalidate()
        // Keep notification routing suppression and dismissal tied to the pinned conversation owner.
        updateNotificationSuppression(
            suppression.onActiveConversation(groupIdHex, accountRef = if (groupIdHex != null) accountRef else null),
        )
        if (groupIdHex != null) {
            synchronized(conversationStateLock) {
                promoteConversationState(activeConversationAccountRef, groupIdHex)
            }
        }
    }

    suspend fun setActiveConversation(
        accountRef: String?,
        groupIdHex: String?,
    ) {
        applyActiveConversationTransition(accountRef, groupIdHex)
        if (groupIdHex != null) {
            // Clear the conversation's tray cards on the first open, regardless
            // of whether mark-read later advances the read watermark. The
            // mark-read-driven dismissal in the conversation controllers stays
            // as-is; this is additive defense against the cold-open race where
            // the read anchor isn't ready yet or the mark-read is deduped, so a
            // reaction/message notification could otherwise survive until the
            // second open (issue #803).
            dismissConversationNotificationsOnOpen(accountRef, groupIdHex, ::dismissConversationNotifications)
        }
        appStateDebug {
            "active conversation=${groupIdHex?.take(8) ?: "<none>"} account=${activeConversationAccountRef?.take(8) ?: "<none>"}"
        }
    }

    /** Awaits off-main card cancellation before route commit, without claiming transcript suppression. */
    internal suspend fun dismissNotificationRouteCards(
        accountRef: String,
        groupIdHex: String,
    ) {
        val target = conversationOpenDismissalTarget(accountRef, groupIdHex) ?: return
        runCatchingCancellable {
            localNotificationPresenter.dismissConversationMessages(
                target.accountRef,
                target.groupIdHex,
                dispatcher = notificationCardCancellationDispatcher,
            )
        }.onFailure { appStateDebug { "notification route dismiss failed group=${target.groupIdHex.take(8)}" } }
    }

    /** Publish Compose ownership immediately, then dismiss existing cards off the main thread. */
    fun setActiveConversationFromUi(
        accountRef: String?,
        groupIdHex: String?,
    ) {
        // Publish ownership first so suppression is authoritative for the
        // visible route even if a platform cancellation call fails.
        applyActiveConversationTransition(accountRef, groupIdHex)
        conversationOpenDismissals.dismiss(accountRef, groupIdHex)
        appStateDebug {
            "active conversation=${groupIdHex?.take(8) ?: "<none>"} account=${activeConversationAccountRef?.take(8) ?: "<none>"}"
        }
    }

    fun clearActiveConversation() {
        applyActiveConversationTransition(accountRef = null, groupIdHex = null)
        appStateDebug {
            "active conversation=<none> account=${activeConversationAccountRef?.take(8) ?: "<none>"}"
        }
    }

    suspend fun dismissConversationNotifications(
        accountRef: String,
        groupIdHex: String,
    ) {
        runCatchingCancellable {
            localNotificationPresenter.dismissConversationMessages(
                accountRef,
                groupIdHex,
                dispatcher = notificationCardCancellationDispatcher,
            )
        }.onFailure {
            appStateDebug { "notification dismiss failed group=${groupIdHex.take(8)}" }
        }
    }

    /** Drops the notification presenter's shortcut snapshot for a conversation whose shortcut was just removed. */
    internal fun forgetNotificationConversationShortcut(
        accountRef: String,
        groupIdHex: String,
    ) {
        localNotificationPresenter.forgetConversationShortcut(accountRef, groupIdHex)
    }

    /** Reuses the live native client for a retention preflight when one already exists. */
    internal fun retentionSweepRuntimeOrNull(): MarmotInterface? = marmotRuntime?.marmot

    /** Explicit checks bypass automatic cooldown; lifecycle calls share worker admission in the repository. */
    suspend fun refreshAppUpdate(
        force: Boolean = false,
        notifyIfNewer: Boolean = false,
    ): AppUpdateInfo {
        val info =
            runCatchingCancellable {
                if (force) {
                    appUpdateRepository.refresh()
                } else {
                    appUpdateRepository.refreshAutomatically() ?: appUpdateRepository.loadInfo()
                }
            }.onFailure {
                appStateDebug(it) { "app update check failed: ${it.readableMessage()}" }
            }.getOrElse {
                appUpdateRepository.loadInfo()
            }
        appUpdateInfo = info
        maybeShowAppUpdateNotification(info, notifyIfNewer)
        return info
    }

    private fun maybeShowAppUpdateNotification(
        info: AppUpdateInfo,
        notifyIfNewer: Boolean,
    ) {
        if (shouldPostAppUpdateNotification(info, notifyIfNewer, appInForeground)) {
            appUpdateNotifier.show(info)
        }
    }

    /** Startup, foreground and background triggers all use automatic admission. */
    suspend fun refreshAppUpdateIfStale(notifyIfNewer: Boolean = false): AppUpdateInfo = refreshAppUpdate(force = false, notifyIfNewer = notifyIfNewer)

    fun dismissAppUpdateBanner() {
        appUpdateInfo = appUpdateRepository.dismissLatest()
    }

    fun showAppUpdateBannerFromNotification() {
        appUpdateInfo = appUpdateRepository.loadInfo()
    }

    fun startAppSelfUpdate(version: String? = appUpdateInfo.latestVersion) {
        val targetVersion = version ?: return
        if (
            !shouldStartInAppSelfUpdate(
                selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
                installedVersion = appUpdateInfo.installedVersion,
                targetVersion = targetVersion,
            )
        ) {
            return
        }
        appSelfUpdateFlow.start(
            scope = notificationScope,
            version = targetVersion,
            onStateChanged = { appSelfUpdateState = it },
        )
    }

    fun confirmAppSelfUpdateDownload() {
        appSelfUpdateFlow.confirmDownload(
            scope = notificationScope,
            onStateChanged = { appSelfUpdateState = it },
        )
    }

    fun cancelAppSelfUpdate() {
        appSelfUpdateFlow.cancel(deleteVerifiedApk = true) { appSelfUpdateState = it }
    }

    fun retryAppSelfUpdate() {
        val version = appUpdateInfo.latestVersion ?: return
        if (
            !shouldStartInAppSelfUpdate(
                selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
                installedVersion = appUpdateInfo.installedVersion,
                targetVersion = version,
            )
        ) {
            return
        }
        appSelfUpdateFlow.retry(
            scope = notificationScope,
            version = version,
            onStateChanged = { appSelfUpdateState = it },
        )
    }

    fun refreshAppSelfUpdateInstallPermission() {
        appSelfUpdateFlow.refreshInstallPermission { appSelfUpdateState = it }
    }

    fun openAppSelfUpdateInstallPermissionSettings(context: Context = appContext) {
        appSelfUpdateFlow.openInstallPermissionSettings(context)
    }

    fun launchVerifiedAppSelfUpdate(context: Context = appContext): Boolean = appSelfUpdateFlow.launchInstall(context) { appSelfUpdateState = it }

    /** Reads retained push recovery state off Main, including before an active account exists. */
    suspend fun refreshPushStorageRecovery() {
        pushStorageRecovery = withContext(pushWakeStorageDispatcher) { pushTokenStore.recoveryState() }
    }

    /** Retries original sources and normal cleanup; unknown cleanup provenance remains explicitly held. */
    suspend fun retryPushStorageRecovery() {
        if (pushStorageRecoveryBusy) return
        pushStorageRecoveryBusy = true
        try {
            withContext(pushWakeStorageDispatcher) { pushTokenStore.recoveryState(retry = true) }
            notificationDelivery.notificationDeliveryModeMutex.withLock {
                if (!notificationDeliveryModeBusy) syncNativePushRegistrationIfEnabled()
            }
            refreshPushStorageRecovery()
        } finally {
            pushStorageRecoveryBusy = false
        }
    }

    /** Publishes native settings only for the same account selection and runtime that requested them. */
    suspend fun refreshLocalNotificationSettings() {
        refreshPushStorageRecovery()
        val account = activeAccountRef
        val activationGeneration = accountSwitchHandoff.capture()
        val requestRuntimeGeneration = runtimeGeneration
        val settings =
            if (account == null) {
                null
            } else {
                runCatchingCancellable {
                    marmotIo { notificationSettings(account) }
                }.getOrNull()
            }
        if (
            activeAccountRef == account &&
            runtimeGeneration == requestRuntimeGeneration &&
            accountSwitchHandoff.isCurrent(activationGeneration)
        ) {
            notificationDelivery.localNotificationSettings = settings
        }
    }

    @Suppress("MaxLineLength")
    suspend fun setLocalNotificationsEnabled(enabled: Boolean): Boolean = notificationDelivery.setLocalNotificationsEnabled(enabled)

    internal fun notificationDeliveryMode(): NotificationDeliveryMode = notificationDelivery.notificationDeliveryMode()

    fun refreshNotificationBatteryPolicy() = notificationDelivery.refreshNotificationBatteryPolicy()

    @Suppress("MaxLineLength")
    internal suspend fun setNotificationDeliveryMode(mode: NotificationDeliveryMode): Boolean = notificationDelivery.setNotificationDeliveryMode(mode)

    @Suppress("MaxLineLength")
    suspend fun setBackgroundConnectionEnabled(enabled: Boolean): Boolean = notificationDelivery.setBackgroundConnectionEnabled(enabled)

    fun onBackgroundConnectionStartRejected() = notificationDelivery.onBackgroundConnectionStartRejected()

    fun onBackgroundConnectionRuntimeExhausted() = notificationDelivery.onBackgroundConnectionRuntimeExhausted()

    internal fun nativePushCapability(config: PushServerConfig? = pushServerConfigProvider()): NativePushCapability =
        notificationDelivery.nativePushCapability(config)

    fun onPushTokenRotated(token: String): Job? = notificationDelivery.onPushTokenRotated(token)

    @Suppress("MaxLineLength")
    suspend fun syncNativePushRegistrationIfEnabled(): Boolean = notificationDelivery.syncNativePushRegistrationIfEnabled()

    @Suppress("MaxLineLength")
    internal fun onNativePushFallbackRuntimeStarted(generation: Long): Job? = notificationDelivery.onNativePushFallbackRuntimeStarted(generation)

    internal fun onNativePushFallbackRuntimeUnavailable(generation: Long) {
        notificationDelivery.onNativePushFallbackRuntimeUnavailable(generation)
    }

    suspend fun setNativePushEnabled(enabled: Boolean): Boolean = notificationDelivery.setNativePushEnabled(enabled)

    fun refreshLocalNotificationPermission() = notificationDelivery.refreshLocalNotificationPermission()

    fun shouldRequestDefaultNotificationPermission(): Boolean =
        activeAccountRef != null &&
            !defaultNotificationsEnableAttempted &&
            !defaultNotificationPermissionPromptInFlight &&
            !localNotificationPermissionGranted &&
            backgroundConnectionEnabled

    fun markDefaultNotificationPermissionPromptLaunched() {
        defaultNotificationPermissionPromptInFlight = true
        appStateDebug { "default notification permission prompt launched" }
    }

    fun markDefaultNotificationsEnableAttempted() {
        defaultNotificationPermissionPromptInFlight = false
        if (defaultNotificationsEnableAttempted) return
        defaultNotificationsEnableAttempted = true
        preferences.edit().putBoolean(DEFAULT_NOTIFICATIONS_ENABLE_ATTEMPTED_KEY, true).apply()
        appStateDebug { "default notifications enable attempted" }
    }

    fun markDisappearingTooltipShown() {
        if (disappearingTooltipShown) return
        disappearingTooltipShown = true
        // commit() (not apply()) so the flag is on disk before we return — the
        // mark-before-show ordering means an apply()'s async write losing a
        // process-death race would re-arm the one-time tooltip on next launch.
        preferences.edit().putBoolean(DISAPPEARING_TOOLTIP_SHOWN_KEY, true).commit()
    }

    /**
     * Performs the one-time default delivery setup, falling back to the
     * persistent connection whenever native push cannot be used or enabled.
     */
    suspend fun enableDefaultNotificationsIfReady(): Boolean {
        if (defaultNotificationsEnableAttempted) return false
        activeAccountRef ?: return false
        refreshLocalNotificationPermission()
        if (!localNotificationPermissionGranted) return false
        markDefaultNotificationsEnableAttempted()
        return setNotificationDeliveryMode(
            if (nativePushCapability().isAvailable) {
                NotificationDeliveryMode.Fcm
            } else {
                NotificationDeliveryMode.Local
            },
        )
    }

    /** The active account's name for [accountIdHex]: its private nickname, else its network display name. */
    fun displayName(accountIdHex: String): String = displayNameForAccount(activeAccountRef, accountIdHex)

    /** The account's published or local name, or its short npub when only the key can name it. */
    fun networkDisplayName(accountIdHex: String): String {
        val name = networkDisplayNameOrNull(accountIdHex)
        return name ?: shortNpub(accountIdHex)
    }

    /** The published or local account name for [accountIdHex], or null when only its public key could name it. */
    fun networkDisplayNameOrNull(accountIdHex: String): String? {
        profileDisplayName(accountIdHex)?.let { return it }
        requestProfile(accountIdHex)
        val accountLabel = accounts.firstOrNull { it.accountIdHex == accountIdHex }?.label
        return networkDisplayNameFallback(accountLabel, accountIdHex) { "" }.takeIf(String::isNotEmpty)
    }

    fun chatMemberTitle(accountIdHex: String): String {
        contactNicknameFor(activeAccountRef, accountIdHex)?.let { return it }
        profileDisplayName(accountIdHex)?.let { return it }
        requestProfile(accountIdHex)
        return cachedShortNpubOrUnknown(accountIdHex)
    }

    fun contactNickname(accountIdHex: String): String? = contactNicknameFor(activeAccountRef, accountIdHex)

    /** Stores an account-scoped nickname and silently reconciles active notification sender lines. */
    fun setContactNickname(
        accountIdHex: String,
        nickname: String,
    ) {
        val account = contactNicknameAccountRefForAccess(activeAccountRef, accounts, accountIdHex) ?: return
        // Reject instead of racing an in-flight sign-out clear: a write landing
        // after the clearing commit would resurrect the account's contact data.
        if (isContactRefBeingCleared(account)) return
        if (ContactNicknamePreferences.writeNickname(preferences, account, accountIdHex, nickname)) {
            contactNicknameRevision += 1
            profileAccountRevisions.bump(accountIdHex)
            notificationNicknameRefresh.refresh(account, accountIdHex)
        }
    }

    fun contactNotes(accountIdHex: String): String? {
        val account = contactNicknameAccountRefForAccess(activeAccountRef, accounts, accountIdHex) ?: return null
        return ContactNotesPreferences.readNotes(preferences, account, accountIdHex)
    }

    fun setContactNotes(
        accountIdHex: String,
        notes: String,
    ) {
        val account = contactNicknameAccountRefForAccess(activeAccountRef, accounts, accountIdHex) ?: return
        if (isContactRefBeingCleared(account)) return
        ContactNotesPreferences.writeNotes(preferences, account, accountIdHex, notes)
    }

    private fun displayNameForAccount(
        accountRef: String?,
        accountIdHex: String,
    ): String {
        contactNicknameFor(accountRef, accountIdHex)?.let { return it }
        return networkDisplayName(accountIdHex)
    }

    /** Reads a private nickname for the explicitly captured account that owns an asynchronous UI result. */
    internal fun contactNicknameFor(
        accountRef: String?,
        accountIdHex: String,
    ): String? {
        // Intentional Compose snapshot read: cached title helpers are often
        // called from remember{} blocks that otherwise wouldn't observe local
        // nickname edits.
        contactNicknameRevision
        val account = contactNicknameAccountRefForAccess(accountRef, accounts, accountIdHex) ?: return null
        return ContactNicknamePreferences.readNickname(preferences, account, accountIdHex)
    }

    private fun isLocalAccount(accountIdHex: String): Boolean = isLocalContactAccount(accounts, accountIdHex)

    // Pure read for use inside remember{}: returns the cached display name or the
    // short npub. Reads the presentation map directly (not profilePresentation(),
    // whose lazy ensureProfileMaterialized is a side effect); touches
    // profileRevision only for Compose invalidation. Callers drive the prefetch
    // from a LaunchedEffect (e.g. requestProfiles over the roster).
    private fun chatMemberNameCached(accountIdHex: String): String? {
        profileRevision
        return synchronized(profilePresentationLock) {
            resolvedProfileDisplayName(
                profileDisplayName = profilePresentations[accountIdHex]?.displayName,
                notificationDisplayNameHint = notificationDisplayNameHints[accountIdHex],
            )
        }
    }

    fun chatMemberTitleCached(accountIdHex: String): String {
        val cachedName = chatMemberNameCached(accountIdHex)
        return cachedName ?: cachedShortNpubOrUnknown(accountIdHex)
    }

    internal fun contactDisplayNameCachedOrNull(accountIdHex: String): String? =
        contactDisplayNameCachedOrNull(
            accountRef = activeAccountRef,
            accountIdHex = accountIdHex,
        )

    internal fun contactDisplayNameCachedOrNull(
        accountRef: String?,
        accountIdHex: String,
    ): String? {
        contactNicknameFor(accountRef, accountIdHex)?.let { return it }
        return chatMemberNameCached(accountIdHex)
    }

    fun contactDisplayNameCached(accountIdHex: String): String {
        val cachedName = contactDisplayNameCachedOrNull(accountIdHex)
        return cachedName ?: cachedShortNpubOrUnknown(accountIdHex)
    }

    /**
     * Presentation-only account title for composition. Unlike [displayName],
     * this never performs a synchronous Marmot fallback when the npub cache is
     * cold; callers can request profile hydration from a side effect.
     */
    internal fun accountDisplayNameCached(accountIdHex: String): String {
        chatMemberNameCached(accountIdHex)?.let { return it }
        val accountLabel = accounts.firstOrNull { it.accountIdHex == accountIdHex }?.label
        return networkDisplayNameFallback(accountLabel, accountIdHex, ::cachedShortNpubOrUnknown)
    }

    internal fun contactDisplayNameCached(
        accountRef: String?,
        accountIdHex: String,
    ): String = contactDisplayNameCachedOrNull(accountRef, accountIdHex) ?: cachedShortNpubOrUnknown(accountIdHex)

    private fun profileDisplayName(accountIdHex: String): String? =
        resolvedProfileDisplayName(
            profileDisplayName = profilePresentation(accountIdHex).displayName,
            notificationDisplayNameHint = notificationDisplayNameHints[accountIdHex],
        )

    fun shortNpub(accountIdHex: String): String {
        val npub = npubForDisplay(accountIdHex)
        if (npub.isEmpty()) return ""
        return IdentityFormatter.short(npub, prefix = 10, suffix = 8)
    }

    /**
     * Main-thread presentation fallbacks may consult only the in-memory
     * encoding cache. A cold cache must not synchronously cross the
     * Marmot/UniFFI boundary; notification enrichment and UI side effects can
     * resolve the canonical identity later.
     */
    internal fun cachedShortNpubOrUnknown(accountIdHex: String): String =
        npubs
            .get(accountIdHex)
            ?.takeIf(String::isNotBlank)
            ?.let { IdentityFormatter.short(it, prefix = 10, suffix = 8) }
            ?: appContext.getString(R.string.unknown)

    /** Returns a canonical npub for presentation, or empty rather than exposing raw hex. */
    fun npubForDisplay(accountIdHex: String): String {
        val cached = npubs.get(accountIdHex)
        return npubPresentation(accountIdHex, cached) {
            marmot().npub(accountIdHex)
        }.also { resolved ->
            if (resolved.isNotEmpty() && cached == null) {
                npubs.put(accountIdHex, resolved)
            }
        }
    }

    /**
     * Returns a non-empty operational identity reference. This intentionally
     * falls back to [accountIdHex] when encoding fails; UI must use
     * [npubForDisplay] or [shortNpub] instead.
     */
    fun npub(accountIdHex: String): String {
        val cached = npubs.get(accountIdHex)
        val resolved =
            operationalNpub(accountIdHex, cached) {
                marmot().npub(accountIdHex)
            }
        if (cached == null && resolved.startsWith("npub1")) {
            npubs.put(accountIdHex, resolved)
        }
        return resolved
    }

    suspend fun accountIdHex(reference: String): String? {
        accountIdHexResolver?.invoke(reference)?.let { return it }
        return runCatchingCancellable { marmotIo { accountIdHex(reference) } }.getOrNull()
    }

    internal suspend fun resolveAccountIdHex(reference: String): String? {
        accountIdHexResolver?.invoke(reference)?.let { return it }
        return marmotIo { accountIdHex(reference) }
    }

    // Pure profile-record read for Compose remember blocks. The caller owns prefetching.
    internal fun userProfileCached(accountIdHex: String): UserProfileMetadataFfi? {
        profileRevision
        return cachedUserProfile(accountIdHex)
    }

    fun userProfile(accountIdHex: String): UserProfileMetadataFfi? {
        // Observe profile cache invalidations for Compose callers.
        profileRevision
        return cachedUserProfile(accountIdHex) ?: run {
            ensureProfileMaterialized(accountIdHex)
            requestProfile(accountIdHex)
            null
        }
    }

    suspend fun loadUserProfile(accountIdHex: String): UserProfileMetadataFfi? {
        val hostAttempt = beginHostPerformance(HostPerformanceOperationFfi.PROFILE_READ)
        val profile =
            try {
                runCatchingCancellable {
                    marmotIo(MarmotTraceSection.PROFILE_READ) { userProfile(accountIdHex) }
                }.fold(
                    onSuccess = { value ->
                        if (value == null) hostAttempt.unavailable() else hostAttempt.success()
                        value
                    },
                    onFailure = {
                        hostAttempt.failure()
                        null
                    },
                )
            } catch (cancel: CancellationException) {
                hostAttempt.cancel()
                throw cancel
            }
        if (profile == null) requestProfile(accountIdHex)
        return profile
    }

    /**
     * Pure cache snapshot used at the final Android write, including a cleared private choice. Without private pixels
     * in memory, as after a Clear, the choice shows what the chat row shows: the public picture, or the row's stored
     * copy (#3635), and only then the monogram. The app lock always shows the monogram.
     */
    @Suppress("ReturnCount") // Reject ineligible or revoked owners before accessing private pixels.
    internal fun contactAvatarOverride(
        account: String,
        contact: String,
    ): Pair<Boolean, android.graphics.Bitmap?> {
        if (isContactRefBeingCleared(account)) return true to null
        if (contactNicknameAccountRefForAccess(account, accounts, contact) == null ||
            !contactPictureStore.hasChoice(account, contact)
        ) {
            return false to null
        }
        if (appLock.screenVisible) return true to null
        val private = contactPictureStore.reference(account, contact)?.cacheKey?.let(AvatarImageLoader::cachedImage)
        val published = synchronized(profilePresentationLock) { profilePresentations[contact]?.avatarUrl }
        return true to
            (
                private?.asAndroidBitmap()
                    ?: AvatarImageLoader.peekBitmap(published)
                    ?: contactRowPicture(account, contact)
            )
    }

    /**
     * #3635: the stored picture [account]'s chat rows show for [contact], archived ones included, when the chat list
     * already decoded it, else the one a notification's late refresh last decoded for their row, such as for a push
     * into a dead process or an account whose chat list is not open.
     */
    private fun contactRowPicture(
        account: String,
        contact: String,
    ): android.graphics.Bitmap? =
        chatsController
            ?.takeIf { it.boundAccountRef == account }
            ?.let { it.items + it.archivedItems }
            ?.readyContactRowPicture(account, contact)
            ?: notificationContactRowPictures.ready(account, contact)

    /** Bounded local file decode shares the first-post deadline; it can never acquire a public URL. */
    internal suspend fun warmPrivateContactAvatar(
        account: String,
        contact: String,
    ) {
        val eligible = contactNicknameAccountRefForAccess(account, accounts, contact) != null
        if (!eligible || isContactRefBeingCleared(account)) return
        val ref = contactPictureStore.reference(account, contact) ?: return
        val lifetime = AvatarImageLoader.currentCacheLifetime()
        runCatchingCancellable {
            val domain =
                dev.ipf.whitenoise.android.media.MediaTrustDomain
                    .local(account)
            AvatarImageLoader.loadStored(ref.cacheKey, lifetime, domain) {
                withContext(Dispatchers.IO) { contactPictureStore.read(ref) }
            }
        }
    }

    /** Display-only private avatar handle; public profile and export callers keep using [avatarUrl]. */
    @Suppress("ReturnCount") // Every ineligible/private-missing state preserves the public identity fallback.
    fun contactAvatarSource(
        accountIdHex: String,
        accountRef: String? = activeAccountRef,
    ): String? {
        contactNicknameRevision
        val publicUrl = avatarUrl(accountIdHex)
        val account = contactNicknameAccountRefForAccess(accountRef, accounts, accountIdHex) ?: return publicUrl
        if (isContactRefBeingCleared(account) || accounts.none { it.label == account && !it.signedOut }) {
            return publicUrl
        }
        val picture = contactPictureStore.reference(account, accountIdHex) ?: return publicUrl
        return dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
            .source(picture, publicUrl)
    }

    /** Resolves platform-owned opaque metadata only against currently signed-in local identities. */
    private fun contactAvatarOverrideForScope(
        scope: String,
        contact: String,
    ): Pair<Boolean, android.graphics.Bitmap?> {
        val owner =
            accounts.firstOrNull {
                !it.signedOut &&
                    dev.ipf.whitenoise.android.notifications
                        .conversationShortcutAccountScope(it.label) == scope
            } ?: return false to null
        return contactAvatarOverride(owner.label, contact)
    }

    /** Commits one captured editor atomically and invalidates every account-owned identity presentation. */
    internal suspend fun saveContactPrivateDetails(
        account: String,
        contact: String,
        nickname: String,
        notes: String,
        picture: ContactPictureChange,
        ownerIsCurrent: () -> Boolean,
    ): Boolean {
        val saved =
            withContext(Dispatchers.IO) {
                contactPictureStore.save(account, contact, nickname, notes, picture) {
                    ownerIsCurrent() &&
                        activeAccountRef == account &&
                        !signOutInProgress &&
                        !wipeInProgress &&
                        !isContactRefBeingCleared(account) &&
                        !isLocalAccount(contact)
                }
            }
        if (saved) {
            // The editor's scope may already be cancelled by a configuration change while the record has landed.
            withContext(NonCancellable) {
                // A nickname-only Save changes no pixels, so every cached avatar stays decoded. A picture change
                // retires only this contact's private pixels, so the stored chat row picture a Clear falls back to
                // stays decoded (#3635).
                val retired = ContactPictureStore.cacheKeyPrefix(account, contact)
                if (picture !is ContactPictureChange.Keep && retired != null) {
                    AvatarImageLoader.clearStoredAvatarsWithPrefix(retired)
                }
                warmPrivateContactAvatar(account, contact)
                contactNicknameRevision += 1
                profileAccountRevisions.bump(contact)
                mutationsScope.launch { refreshPrivateContactPresentation(account, contact, adoptLegacy = true) }
            }
        }
        return saved
    }

    /** Reconciles existing platform pixels from the current cache without starting a public image fetch. */
    private suspend fun refreshPrivateContactPresentation(
        account: String,
        contact: String,
        adoptLegacy: Boolean = false,
    ) {
        notificationNicknameRefresh.refresh(account, contact)
        // An evicted private bitmap must not downgrade a current platform icon to its monogram.
        warmPrivateContactAvatar(account, contact)
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val platform =
                    dev.ipf.whitenoise.android.notifications
                        .ContactPictureShortcutPlatform(appContext)
                val legacy =
                    if (adoptLegacy && platform.hasLegacyContactShortcuts(account)) {
                        withTimeoutOrNull(1_500L) { legacyPrivatePictureConversation(account, contact) }
                    } else {
                        null
                    }
                dev.ipf.whitenoise.android.notifications.refreshContactPictureShortcuts(
                    appContext,
                    account,
                    contact,
                    currentAvatar = { contactAvatarOverride(account, contact).second },
                    isCurrent = {
                        val signedIn = accounts.any { it.label == account && !it.signedOut }
                        signedIn && !isContactRefBeingCleared(account)
                    },
                    platform = platform,
                    legacyConversationId = legacy?.first,
                    legacyConversationIcon = legacy?.second ?: false,
                )
            }
        }
    }

    /** At most two local native reads adopt the canonical DM without scanning or caching the account's chats. */
    @Suppress("ReturnCount") // Fail closed at each unavailable or mismatched native ownership boundary.
    private suspend fun legacyPrivatePictureConversation(
        account: String,
        contact: String,
    ): Pair<String, Boolean>? {
        val existing = marmotIo { existingDirectConversation(account, contact) }?.takeIf { it.reusable } ?: return null
        val row = marmotIo { presentedChatListRow(account, existing.groupIdHex) } ?: return null
        val item = chatListItemFromProjection(row.row, row.presentation, row.avatarAsset)
        val peer =
            GroupProjector.avatarAccount(
                item.group,
                item.presentationOtherMemberAccount,
                item.presentationMemberCount,
            )
        if (peer != contact || row.row.groupIdHex != existing.groupIdHex) return null
        val contactIcon =
            !GroupProjector.ownsGroupPicture(item.group, row.avatarAsset, row.presentation.avatarSource.isPeerSourced())
        return existing.groupIdHex to contactIcon
    }

    /** Public profile picture URL for display, requesting the profile once when no avatar is known yet. */
    fun avatarUrl(accountIdHex: String): String? {
        val avatar = profilePresentation(accountIdHex).avatarUrl
        if (avatar == null) requestProfile(accountIdHex)
        return avatar
    }

    /** Distinguishes a pending local profile read from a resolved profile without a picture. */
    internal fun isProfilePresentationUnresolved(accountIdHex: String): Boolean =
        synchronized(profilePresentationLock) { !profilePresentations.containsKey(accountIdHex) }

    /** Profile publication or account-cache reset invalidates last-drawn top-bar pixels. */
    internal val profilePresentationEpochForCompose: Int get() = profileAccountRevisions.epoch

    /**
     * Start local profile materialization and image decoding without waiting for
     * either. Chat/message projections call this before notification delivery,
     * and [applyProfilePresentation] queues the image as soon as local storage (or
     * a relay refresh) yields a sanitized URL. The decode runs in [domain], the
     * caller's account's public profiles. Only the active account's caller queues
     * that later warm, which runs in the active account's worker.
     */
    internal fun preWarmProfileAvatar(
        accountIdHex: String,
        domain: dev.ipf.whitenoise.android.media.MediaTrustDomain = AvatarImageLoader.publicProfiles(),
    ) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        val deferrable = domain == AvatarImageLoader.publicProfiles()
        val cachedAvatar =
            synchronized(profilePresentationLock) {
                val avatar = profilePresentations[id]?.avatarUrl
                if (avatar != null) {
                    pendingAvatarPreWarmAccountIds.remove(id)
                } else if (deferrable) {
                    // Reinsert to keep the set in recent-conversation order, so a
                    // live sender never sits behind old avatarless profiles.
                    pendingAvatarPreWarmAccountIds.remove(id)
                    pendingAvatarPreWarmAccountIds.add(id)
                }
                avatar
            }
        AvatarImageLoader.preWarm(cachedAvatar, domain)
        // Starts an ungated local materialization on a miss and observes its
        // revision; applyProfilePresentation consumes the pending warm above once
        // the URL lands. The relay refresh remains independently cooldown-gated.
        profilePresentation(id)
        requestProfile(id)
    }

    /** Starts a latest-wins profile refresh for one account presentation. */
    fun requestProfile(accountIdHex: String) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        // Always materialize the local SQLite record independently of relay freshness.
        // This only schedules off-main binding work; render/timeline callers stay non-blocking.
        ensureProfileMaterialized(id, includeMissingIdentity = true)
        if (!profileRefreshGate.tryStart(id, System.currentTimeMillis())) return
        // Snapshot the cache epoch now, before the job is queued. A switch or
        // sign-out can clear the caches in the gap before this coroutine starts,
        // so the staleness check must compare against the epoch at request time,
        // not whatever it has become by the time the body runs.
        val requestEpoch = profileCacheLifetime.capture()
        profileScope.launch {
            profileRefreshFanoutGate.withPermit {
                refreshProfile(id, requestEpoch)
            }
        }
    }

    fun requestProfiles(accountIdHexes: Iterable<String>) {
        accountIdHexes.forEach { requestProfile(it) }
    }

    /**
     * Eagerly materialize the *local* profile presentation (display name +
     * avatar URL) for each id from on-device storage, ahead of any row asking
     * for it. The lazy path ([profilePresentation] → [ensureProfileMaterialized])
     * only fires when a composable first reads `displayName`/`avatarUrl` during
     * its initial paint, so for a chat whose history is already on device the
     * sender name + avatar slot render empty for a few frames and then pop in
     * once the off-main local read lands and bumps [profileRevision] (#609).
     * This suspends until every sender's presentation is materialized into the
     * caches, so a caller that awaits it before publishing a timeline page
     * guarantees the first composition observes a populated presentation rather
     * than [ProfilePresentation.Empty] — closing the per-row hydration flicker
     * entirely (rather than just narrowing it, as a fire-and-forget warm would:
     * a launch-and-return warm races the synchronous publish and can still lose,
     * leaving rows blank on the first frame).
     *
     * The reads run off the main thread via [marmotIo]; only cache writes touch
     * the main thread. This is *not* a new Android-owned data cache: it reuses
     * the existing in-memory presentation materialization, just scheduled
     * proactively and awaited. The gated relay refresh for freshness stays the
     * job of [requestProfile]/[requestProfiles].
     */
    suspend fun warmProfilePresentationsBlocking(accountIdHexes: Iterable<String>) {
        val ids =
            synchronized(profilePresentationLock) {
                profilePresentationIdsNeedingWarm(
                    accountIdHexes = accountIdHexes,
                    hasCachedPresentation = profilePresentations::containsKey,
                    hasMaterialization = profileMaterializations::containsKey,
                )
            }
        if (ids.isEmpty()) return
        val gate = Semaphore(PROFILE_PRESENTATION_WARM_FANOUT)
        coroutineScope {
            ids
                .map { id ->
                    async {
                        gate.withPermit {
                            val reservation = reserveProfileMaterialization(id) ?: return@withPermit
                            if (reservation.ownsRead) {
                                completeProfileMaterialization(id, reservation.completion)
                            } else {
                                reservation.completion.await()
                            }
                        }
                    }
                }.awaitAll()
        }
    }

    fun cachedGroupMemberSnapshot(
        accountRef: String?,
        groupIdHex: String,
    ): GroupMemberSnapshot? {
        val key = groupMemberSnapshotKey(accountRef, groupIdHex) ?: return null
        return synchronized(groupMemberSnapshotLock) {
            groupMemberSnapshots[key]
        }
    }

    fun cacheGroupMemberSnapshot(
        accountRef: String?,
        groupIdHex: String,
        members: List<AppGroupMemberRecordFfi>,
    ): GroupMemberSnapshot {
        val snapshot = GroupMemberSnapshot(members)
        val key = groupMemberSnapshotKey(accountRef, groupIdHex) ?: return snapshot
        synchronized(groupMemberSnapshotLock) {
            groupMemberSnapshots.put(key, snapshot)
        }
        return snapshot
    }

    /**
     * Synchronously drop the active account from the cached member snapshot for
     * [groupIdHex] after a successful leave (issue #545). The snapshot seeds the
     * next [ConversationController]'s `seededSelfMember`; without this, a stale
     * positive snapshot would still place self in the group and flash the active
     * composer when the just-left conversation is re-opened. No-op when there is
     * no cached entry — the seed then falls back to a fresh roster fetch.
     */
    fun removeActiveAccountFromGroupMemberSnapshot(
        accountRef: String?,
        groupIdHex: String,
    ) {
        if (accountRef != null) conversationDictation.onTargetRemoved(accountRef, groupIdHex)
        val key = groupMemberSnapshotKey(accountRef, groupIdHex) ?: return
        val activeAccountIdHex = activeAccount?.accountIdHex
        synchronized(groupMemberSnapshotLock) {
            val current = groupMemberSnapshots[key] ?: return
            groupMemberSnapshots.put(
                key,
                GroupMemberSnapshot(
                    GroupProjector.membersWithoutActiveAccount(current.members, activeAccountIdHex),
                ),
            )
        }
    }

    /** Publishes refreshed profile data only inside the active account-cache lifetime. */
    suspend fun refreshProfile(
        accountIdHex: String,
        epoch: Long = profileCacheLifetime.capture(),
    ) {
        val profile =
            try {
                val result =
                    runCatching {
                        if (profileReader != null) {
                            profileRefreshRequest?.invoke(accountIdHex)
                            profileReader.invoke(accountIdHex)
                        } else {
                            marmotIo {
                                val activeAccountRelays =
                                    activeAccountRef
                                        ?.let { runCatchingCancellable { accountNip65Relays(it) }.getOrNull() }
                                        .orEmpty()
                                val relays =
                                    profileLookupRelays(
                                        bootstrapRelays = MarmotClient.bootstrapRelays,
                                        activeAccountRelays = activeAccountRelays,
                                    )
                                refreshProfile(accountIdHex, relays)
                                userProfile(accountIdHex)
                            }
                        }
                    }
                // Don't let runCatching swallow cancellation: rethrow so the
                // profileScope job actually stops. finish() still runs in finally,
                // so the refresh gate is released either way (no stuck in-flight).
                result.exceptionOrNull()?.let(::rethrowIfCancellation)
                result.getOrNull()
            } finally {
                profileRefreshGate.finish(accountIdHex, System.currentTimeMillis())
            }
        val rawDisplayName = readRefreshedProfileDisplayName(accountIdHex, profile)
        val canonicalNpub = readNpubPresentation(accountIdHex)
        val hasProfileData = profile != null || rawDisplayName != null
        if (hasProfileData || canonicalNpub != null) {
            // Drop the result if an account switch / sign-out cleared the caches
            // while this refresh was in flight, so we don't repopulate them with
            // the previous account's data.
            withContext(Dispatchers.Main.immediate) {
                if (profileCacheLifetime.isCurrent(epoch)) {
                    publishNpubPresentation(accountIdHex, canonicalNpub)
                    if (!hasProfileData) return@withContext
                    val current =
                        synchronized(profilePresentationLock) {
                            profilePresentations[accountIdHex] ?: ProfilePresentation.Empty
                        }
                    applyProfilePresentation(
                        accountIdHex = accountIdHex,
                        profile = profile,
                        presentation = refreshedProfilePresentation(current, profile, rawDisplayName),
                    )
                }
            }
        }
    }

    /** A missing relay result leaves the shared local materialization in charge of the name. */
    private suspend fun readRefreshedProfileDisplayName(
        accountIdHex: String,
        profile: UserProfileMetadataFfi?,
    ): String? {
        // A total miss is not an authoritative deletion and must not blank a
        // first-frame value while relay/local refresh is pending (#2094).
        val localReadInFlight =
            synchronized(profilePresentationLock) { profileMaterializations.containsKey(accountIdHex) }
        return if (profile == null && localReadInFlight) {
            null // The shared local materialization owns this read.
        } else if (profileDisplayNameReader != null) {
            runCatchingCancellable { profileDisplayNameReader.invoke(accountIdHex) }.getOrNull()
        } else {
            runCatchingCancellable {
                marmotIo(MarmotTraceSection.DISPLAY_NAME_READ) { displayName(accountIdHex) }
            }.getOrNull()
        }
    }

    /**
     * Signed-in accounts whose local chat list holds [groupIdHex], in account order (#3658). Null when any read
     * fails, so a storage error is never reported as a missing chat or skips an account the user could pick.
     */
    internal suspend fun accountsHoldingChat(groupIdHex: String): List<String>? =
        runCatchingCancellable {
            accounts.filterNot { it.signedOut }.map { it.label }.filter { account ->
                marmotIo(MarmotTraceSection.CHAT_ROW_READ) { chatListRow(account, groupIdHex) }
                    ?.groupIdHex
                    .equals(groupIdHex, ignoreCase = true)
            }
        }.getOrNull()

    fun presentProfilePayload(raw: String): Boolean {
        val link = ProfileLink.parse(raw) ?: return false
        pendingProfilePresentationId++
        pendingProfileContour = null
        pendingProfileOrigin = null
        pendingProfileGroupsInCommon = false
        pendingProfileMetadata = null
        pendingProfileFromDiscovery = false
        pendingProfileNpub = link.npub
        return true
    }

    internal var pendingProfileGroupsInCommon by mutableStateOf(false)
        private set

    /** Requests a profile with its initial groups-in-common view, clearing discovery-only metadata. */
    fun presentProfile(
        npub: String,
        groupsInCommon: Boolean = false,
        origin: androidx.compose.ui.geometry.Rect? = null,
    ) = presentProfileWithContour(npub, groupsInCommon, origin, null)

    internal fun presentProfileWithContour(
        npub: String,
        groupsInCommon: Boolean = false,
        origin: androidx.compose.ui.geometry.Rect? = null,
        contour: dev.ipf.whitenoise.android.ui.settings.SettingsContour? = null,
    ) {
        pendingProfilePresentationId++
        pendingProfileOrigin = origin?.takeUnless { it.isEmpty }
        pendingProfileContour = contour
        pendingProfileGroupsInCommon = groupsInCommon
        pendingProfileMetadata = null
        pendingProfileFromDiscovery = false
        pendingProfileNpub = npub
    }

    fun presentDiscoveredProfile(
        npub: String,
        profile: UserProfileMetadataFfi?,
        origin: androidx.compose.ui.geometry.Rect? = null,
    ) {
        pendingProfilePresentationId++
        pendingProfileContour = null
        pendingProfileOrigin = origin?.takeUnless { it.isEmpty }
        pendingProfileGroupsInCommon = false
        pendingProfileMetadata = profile
        pendingProfileFromDiscovery = true
        pendingProfileNpub = npub
    }

    // Null means the relationship is unreadable, not "not following" — the write
    // side throws without an active account, so a false here would enable a
    // Follow the user cannot perform.
    suspend fun isFollowingProfile(userRef: String): Boolean? {
        val account = activeAccountRef ?: return null
        return marmotIo { isFollowing(account, userRef) }
    }

    suspend fun setProfileFollowing(
        userRef: String,
        following: Boolean,
    ) {
        val account = activeAccountRef ?: throw StartProfileChatNoActiveAccountException()
        marmotIo {
            if (following) followUser(account, userRef) else unfollowUser(account, userRef)
        }
        relationshipRevision += 1L
    }

    /**
     * Display name for a markdown mention entity (npub or nprofile bech32).
     * Null when the reference doesn't normalize to a pubkey or the profile is
     * unknown, so the renderer keeps its shortened-bech32 fallback. A miss
     * schedules a relay profile fetch; the profile-revision read inside
     * [profilePresentation] re-renders observers when the name lands.
     */
    fun mentionDisplayName(bech32: String): String? {
        val accountIdHex = nostrEntityAccountIdHex(bech32) ?: return null
        profileDisplayName(accountIdHex)?.let { return it }
        requestProfile(accountIdHex)
        return null
    }

    /**
     * Mention names for speech. A bubble can fall back to a shortened key
     * because it is readable, selectable, and tappable; read aloud, that key is
     * a minute of spelled base32. Naming the person generically keeps the
     * mention audible without reciting it, and keeps the sentence intact - an
     * omitted mention on a line of its own leaves nothing to speak.
     *
     * Both the speaking side and the bubble's projection must resolve names the
     * same way, or their projection ids differ and the highlight is dropped.
     */
    val unknownMentionSpeech: String by lazy { appContext.getString(R.string.group_system_someone) }

    fun mentionSpeechName(bech32: String): String? = mentionDisplayName(bech32) ?: unknownMentionSpeech

    /**
     * In-app route for a tapped nostr profile entity in a message body. The
     * tap must never become an ACTION_VIEW nostr: intent — identity taps stay
     * in the app's own profile sheet. Unresolvable references no-op.
     */
    fun presentNostrProfile(bech32: String) {
        val accountIdHex = nostrEntityAccountIdHex(bech32) ?: return
        presentProfile(npub(accountIdHex))
    }

    private fun nostrEntityAccountIdHex(bech32: String): String? {
        val trimmed = bech32.trim()
        return runCatching { marmot().accountIdHex(trimmed) }.getOrNull()
            ?: accountIdHexResolver?.let { resolve -> runBlocking { resolve(trimmed) } }
    }

    /**
     * Public bech32 (npub/nprofile) → hex pubkey resolver for the renderer's
     * self-mention detection (#414). Pure FFI/local encoding (no storage read),
     * so it's safe to call from the receiver-bubble path; returns null when the
     * reference doesn't normalize to a pubkey.
     */
    fun accountIdHexForMention(bech32: String): String? = nostrEntityAccountIdHex(bech32)

    /**
     * Same pure decode for surfaces that must know the identity on their first
     * composed frame. The profile sheet uses it so its height is settled before
     * the open animation starts (#1432); callers still need [accountIdHex] for
     * references this can't normalize locally.
     */
    fun profileReferenceAccountIdHex(reference: String): String? = nostrEntityAccountIdHex(reference)

    /**
     * Whether a mention/profile [bech32] (npub/nprofile) resolves to an account
     * that is in [members] — the active group's roster snapshot for this
     * message render. Roster membership is separate from profile-name
     * presentation: external profile references use the same username styling.
     * Pure FFI/local encoding + roster comparison (case-insensitive, as hex
     * casing round-trips through the FFI); an unresolvable reference is never a
     * member.
     */
    fun isRosterMember(
        bech32: String,
        members: List<AppGroupMemberRecordFfi>,
    ): Boolean {
        val hex = nostrEntityAccountIdHex(bech32)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return members.any { it.memberIdHex.equals(hex, ignoreCase = true) }
    }

    fun clearPresentedProfile() {
        pendingProfilePresentationId++
        pendingProfileContour = null
        pendingProfileOrigin = null
        pendingProfileGroupsInCommon = false
        pendingProfileNpub = null
        pendingProfileMetadata = null
        pendingProfileFromDiscovery = false
    }

    private val chatCreateOpenTiming = ChatCreateOpenTimingTracker()

    fun beginChatCreateOpenTiming(
        stage: String = ChatCreateOpenTiming.STAGE_CONFIRM_TAP,
        restart: Boolean = false,
    ) = chatCreateOpenTiming.begin(stage, restart)

    fun markChatCreateOpenStage(stage: String) {
        chatCreateOpenTiming.mark(stage)
    }

    fun hasActiveChatCreateOpenTiming(): Boolean = chatCreateOpenTiming.isActive()

    fun completeChatCreateOpenTiming(stage: String) {
        chatCreateOpenTiming.finish(stage)
    }

    fun abandonChatCreateOpenTiming(stage: String) {
        chatCreateOpenTiming.finish(stage)
    }

    /**
     * Targeted authoritative read for a freshly created group. Opens the
     * conversation without polling the broad chat-list subscription (#1729).
     */
    suspend fun loadCreatedChatListItem(groupIdHex: String): ChatListItem {
        val account = activeAccountRef ?: throw StartProfileChatNoActiveAccountException()
        markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_START)
        val item = loadAuthoritativeChatListItem(account, groupIdHex)
        markChatCreateOpenStage(ChatCreateOpenTiming.STAGE_AUTHORITATIVE_READ_RETURN)
        return item
    }

    /**
     * Targeted local read for a message-notification tap. This avoids waiting
     * for the target account's broad chat-list projection to bind.
     */
    suspend fun loadNotificationChatListItem(
        accountRef: String,
        groupIdHex: String,
    ): ChatListItem {
        check(activeAccountRef == accountRef) { "notification target account is not active" }
        return preloadNotificationChatListItem(accountRef, groupIdHex)
    }

    /**
     * Request-scoped local read that may run while [accountRef] is activating.
     * An early-open caller must pin the returned item and controller to this
     * account until activation lands; it must never infer ownership from the
     * still-active source account.
     */
    suspend fun preloadNotificationChatListItem(
        accountRef: String,
        groupIdHex: String,
    ): ChatListItem {
        val projection = loadNotificationChatListProjection(accountRef, groupIdHex)
        return chatListItemFromNotificationProjection(projection)
    }

    /**
     * Read the notification target's exact pre-read chat-list row. Failure or a
     * missing row intentionally fails the targeted fast path so navigation waits
     * for the broad authoritative list instead of opening with a fabricated zero
     * unread count.
     */
    private suspend fun loadNotificationChatListProjection(
        accountRef: String,
        groupIdHex: String,
    ): ChatListRowFfi =
        marmotIo(MarmotTraceSection.CHAT_ROW_READ) { chatListRow(accountRef, groupIdHex) }
            ?.takeIf { it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            ?: throw NoSuchElementException("notification chat-list projection unavailable")

    private suspend fun loadAuthoritativeChatListItem(
        accountRef: String,
        groupIdHex: String,
    ): ChatListItem {
        val details = marmotIo { groupDetails(accountRef, groupIdHex) }
        val activeAccountIdHex = accounts.firstOrNull { it.label == accountRef }?.accountIdHex
        return chatListItemFromAuthoritativeGroupDetails(details, activeAccountIdHex)
    }

    suspend fun publishProfile(profile: UserProfileMetadataFfi): Boolean {
        val account = activeAccountRef ?: return false
        val result =
            runCatchingCancellable {
                val profileRelayCount =
                    marmotIo {
                        val relayLists = accountRelayLists(account)
                        val profileRelays =
                            accountNip65Relays(account).ifEmpty {
                                relayLists.defaultRelays.ifEmpty { MarmotClient.bootstrapRelays }
                            }
                        val bootstrapRelays = relayLists.bootstrapRelays.ifEmpty { MarmotClient.bootstrapRelays }
                        publishUserProfile(account, profile, profileRelays, bootstrapRelays)
                        profileRelays.size
                    }
                notifyProfilesChanged()
                presentTransient(
                    AppText.Resource(R.string.toast_profile_published),
                    AppText.Resource(R.string.toast_profile_published_detail, listOf(profileRelayCount)),
                )
            }
        result.onFailure {
            presentFailure(R.string.toast_couldnt_publish_profile, "PROFILE_PUBLISH", it)
        }
        return result.isSuccess
    }

    fun present(
        title: String,
        detail: String? = null,
        copyable: Boolean = false,
    ) {
        presentText(AppText.Plain(title), detail?.let { AppText.Plain(it) }, copyable)
    }

    fun present(
        @StringRes titleRes: Int,
        copyable: Boolean = false,
    ) {
        presentText(AppText.Resource(titleRes), copyable = copyable)
    }

    fun present(
        @StringRes titleRes: Int,
        @StringRes detailRes: Int,
        copyable: Boolean = false,
    ) {
        presentText(AppText.Resource(titleRes), AppText.Resource(detailRes), copyable)
    }

    fun present(
        @StringRes titleRes: Int,
        detail: AppText,
        copyable: Boolean = false,
    ) {
        presentText(AppText.Resource(titleRes), detail, copyable)
    }

    fun presentText(
        title: AppText,
        detail: AppText? = null,
        copyable: Boolean = false,
        diagnosticReport: String? = null,
        tier: NoticeTier = NoticeTier.ActionableError,
        sendAttempt: SendFailureAttempt? = null,
    ) {
        presentText(
            ToastMessage(
                title = title,
                detail = detail,
                copyable = copyable,
                tier = tier,
                diagnosticReport = diagnosticReport,
                sendAttempt = sendAttempt,
            ),
        )
    }

    /** Publishes a scoped UI notice through the same privacy-safe copy gate as ordinary errors. */
    internal fun presentText(notice: ToastMessage) {
        val safeReport = notice.diagnosticReport?.trim()?.takeIf(String::isNotEmpty)
        toast =
            notice.copy(
                copyable = notice.copyable && safeReport != null,
                diagnosticReport = safeReport,
            )
    }

    fun presentTransient(
        @StringRes titleRes: Int,
        detail: AppText? = null,
    ) {
        presentTransient(AppText.Resource(titleRes), detail)
    }

    fun presentTransient(
        title: AppText,
        detail: AppText? = null,
    ) {
        setTransientNotice(title, detail)
    }

    fun presentConversationTransient(
        accountRef: String,
        groupIdHex: String,
        @StringRes titleRes: Int,
        detail: AppText? = null,
    ): TransientNotice = presentConversationTransient(accountRef, groupIdHex, AppText.Resource(titleRes), detail)

    fun presentConversationTransient(
        accountRef: String,
        groupIdHex: String,
        title: AppText,
        detail: AppText? = null,
    ): TransientNotice =
        setTransientNotice(
            title = title,
            detail = detail,
            conversation = ConversationNoticeDestination(accountRef, groupIdHex),
        )

    private fun setTransientNotice(
        title: AppText,
        detail: AppText? = null,
        conversation: ConversationNoticeDestination? = null,
    ): TransientNotice {
        transientNoticeSequence += 1L
        val notice =
            TransientNotice(
                id = transientNoticeSequence,
                title = title,
                detail = detail,
                conversation = conversation,
            )
        transientNotice = notice
        return notice
    }

    fun presentTransient(
        title: String,
        detail: String? = null,
    ) {
        presentTransient(AppText.Plain(title), detail?.let(AppText::Plain))
    }

    /** Retire only the toast observed by the caller, preserving a newer notice. */
    fun clearToast(notice: ToastMessage? = toast) {
        if (toast === notice) toast = null
    }

    fun clearTransientNotice(notice: TransientNotice? = transientNotice) {
        if (transientNotice === notice) transientNotice = null
    }

    private suspend fun configurePrivacyRuntime() {
        runCatchingCancellable {
            withContext(Dispatchers.IO) {
                marmot().configurePrivacyRuntime()
            }
        }.onFailure {
            appStateDebug(it) { "privacy runtime config failed: ${it.readableMessage()}" }
        }
    }

    private suspend fun MarmotInterface.configurePrivacyRuntime() =
        configureAndroidPrivacyRuntime(this, auditUploadConsent, auditLogSettingsMutex) { configureTelemetryRuntime() }

    private fun warmProfile(accountIdHex: String) {
        userProfile(accountIdHex)
        requestProfile(accountIdHex)
    }

    private fun cachedUserProfile(accountIdHex: String): UserProfileMetadataFfi? = synchronized(profilePresentationLock) { userProfiles[accountIdHex] }

    private fun profilePresentation(accountIdHex: String): ProfilePresentation {
        profileRevision
        synchronized(profilePresentationLock) {
            profilePresentations[accountIdHex]?.let { return it }
        }
        // Cache miss: never cross the FFI on the caller's thread — these
        // accessors run during composition. Materialize from local storage
        // off-main (instant, ungated — survives an account-switch cache clear)
        // and return an empty presentation; it bumps [profileRevision] to
        // recompose once resolved. The relay refresh for freshness is the
        // wrappers' job (displayName/avatarUrl call requestProfile). See #4, #49.
        ensureProfileMaterialized(accountIdHex)
        return ProfilePresentation.Empty
    }

    /**
     * Populate the profile caches from *local* storage off the main thread.
     * `displayName`/`userProfile` are local reads (no relay/network), so this is
     * cheap and deliberately ungated — unlike the relay refresh, it must always
     * run after a cache clear (e.g. account switch) so names re-resolve at once
     * instead of waiting on a gated network round-trip.
     */
    private fun ensureProfileMaterialized(
        accountIdHex: String,
        includeMissingIdentity: Boolean = false,
    ) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        val reservation = reserveProfileMaterialization(id, includeMissingIdentity)
        if (reservation?.ownsRead == true) {
            profileScope.launch {
                completeProfileMaterialization(id, reservation.completion)
            }
        }
    }

    /**
     * Join or own [id]'s in-flight local materialization. Both the lazy path and
     * the blocking first-frame warm use this reservation, so they perform at
     * most one user-profile/display-name read for an uncached id.
     */
    private fun reserveProfileMaterialization(
        id: String,
        includeMissingIdentity: Boolean = false,
    ): ProfileMaterializationReservation? =
        synchronized(profilePresentationLock) {
            val needsIdentity =
                includeMissingIdentity && npubs.get(id) == null && profilePresentations[id]?.displayName.isNullOrBlank()
            profileMaterializations[id]?.let { running ->
                ProfileMaterializationReservation(running, ownsRead = false)
            } ?: if (profilePresentations.containsKey(id) && !needsIdentity) {
                null
            } else {
                CompletableDeferred<Unit>().let { completion ->
                    profileMaterializations.put(id, completion)
                    ProfileMaterializationReservation(completion, ownsRead = true)
                }
            }
        }

    private suspend fun completeProfileMaterialization(
        id: String,
        completion: CompletableDeferred<Unit>,
    ) {
        try {
            materializeProfileLocally(id)
        } finally {
            synchronized(profilePresentationLock) {
                if (profileMaterializations[id] === completion) {
                    profileMaterializations.remove(id)
                }
            }
            completion.complete(Unit)
        }
    }

    /**
     * Read the *local* presentation (display name + avatar URL) for [id] off the
     * main thread and publish it into the caches, dropping the result if an
     * account switch / sign-out cleared the caches mid-read (epoch guard).
     *
     * This is the single source of truth for the local materialization that both
     * the lazy async path ([ensureProfileMaterialized]) and the blocking warm
     * path ([warmProfilePresentationsBlocking]) run, so they can never diverge.
     * Reservation bookkeeping is owned by [completeProfileMaterialization], so
     * every caller shares the same in-flight local read.
     */
    private suspend fun materializeProfileLocally(id: String) {
        val epoch = profileCacheLifetime.capture()
        // Account-switch seeds can already own the profile while identity encoding is still cold.
        // Reuse the same flight without rereading or replacing that authoritative presentation.
        val hasProfile = synchronized(profilePresentationLock) { profilePresentations.containsKey(id) }
        val seed = if (hasProfile) null else loadAccountSwitchProfileSeed(id)
        val localName =
            seed?.displayName ?: synchronized(profilePresentationLock) { profilePresentations[id]?.displayName }
        val canonicalNpub = if (localName.isNullOrBlank()) readNpubPresentation(id) else null
        withContext(Dispatchers.Main.immediate) {
            if (profileCacheLifetime.isCurrent(epoch)) {
                publishNpubPresentation(id, canonicalNpub)
                seed?.let(::applyAccountSwitchProfileSeed)
            }
        }
    }

    /** Uses the existing materialization/refresh owners; identity encoding never runs in name composition. */
    private suspend fun readNpubPresentation(accountIdHex: String): String? =
        runCatchingCancellable {
            val cached = npubs.get(accountIdHex)
            val encoded =
                cached ?: if (npubPresentationReader != null) {
                    withContext(marmotIoDispatcher) { npubPresentationReader.invoke(accountIdHex) }
                } else {
                    marmotIo { this.npub(accountIdHex) }
                }
            npubPresentation(accountIdHex, encoded) { null }.takeIf(String::isNotEmpty)
        }.getOrNull()

    /** Canonical encoding is presentation-only; publish its revision behind the caller's lifetime fence. */
    private fun publishNpubPresentation(
        accountIdHex: String,
        canonicalNpub: String?,
    ) {
        if (canonicalNpub == null || npubs.get(accountIdHex) == canonicalNpub) return
        npubs.put(accountIdHex, canonicalNpub)
        profileRevision += 1
        profileAccountRevisions.bump(accountIdHex)
    }

    /** Read one persisted profile without mutating the active account caches. */
    internal suspend fun loadAccountSwitchProfileSeed(id: String): AccountSwitchProfileSeed =
        readLocalAccountProfileSeed(
            id = id,
            readProfile = profileReader ?: { marmotIo(MarmotTraceSection.PROFILE_READ) { userProfile(it) } },
            readDisplayName =
                profileDisplayNameReader ?: { marmotIo(MarmotTraceSection.DISPLAY_NAME_READ) { displayName(it) } },
        )

    /** Publishes sanitized authoritative metadata, including explicit profile-field removal. */
    internal fun applyAccountSwitchProfileSeed(seed: AccountSwitchProfileSeed) {
        applyProfilePresentation(
            accountIdHex = seed.accountIdHex,
            profile = seed.profile,
            presentation =
                ProfilePresentation(
                    displayName = seed.displayName,
                    avatarUrl = seed.avatarUrl,
                ),
        )
    }

    /**
     * Store a freshly-resolved [presentation] and bump [profileRevision] if it
     * changed. Pure in-memory state work, no FFI — safe on the main thread.
     * The blocking FFI reads are the caller's job to run off-main (see
     * [refreshProfile]).
     */
    private fun applyProfilePresentation(
        accountIdHex: String,
        profile: UserProfileMetadataFfi?,
        presentation: ProfilePresentation,
    ) {
        assertMainThread { "applyProfilePresentation" }
        val (changed, shouldPreWarm) =
            synchronized(profilePresentationLock) {
                val profileChanged = profile?.let { userProfiles.put(accountIdHex, it) != it } ?: false
                val presentationChanged = profilePresentations.put(accountIdHex, presentation) != presentation
                val changed = profileChanged || presentationChanged
                if (presentation.displayName != null) notificationDisplayNameHints.remove(accountIdHex)
                val shouldPreWarm =
                    presentation.avatarUrl != null && pendingAvatarPreWarmAccountIds.remove(accountIdHex)
                changed to shouldPreWarm
            }
        if (shouldPreWarm) {
            AvatarImageLoader.preWarm(presentation.avatarUrl, AvatarImageLoader.publicProfiles())
        }
        if (changed) {
            profileRevision += 1
            profileAccountRevisions.bump(accountIdHex)
            scheduleInviteNotificationIdentityRefresh(accountIdHex, presentation)
            refreshProfileNotificationChannelLabels(accountIdHex, presentation.displayName)
            val viewers = accounts.filter { !it.signedOut && contactPictureStore.hasChoice(it.label, accountIdHex) }
            viewers.forEach { account ->
                mutationsScope.launch { refreshPrivateContactPresentation(account.label, accountIdHex) }
            }
        }
    }

    /** Drops every cached profile presentation so each surface re-reads identity from the engine. */
    private fun notifyProfilesChanged() {
        assertMainThread { "notifyProfilesChanged" }
        synchronized(profilePresentationLock) {
            profilePresentations.clear()
            userProfiles.clear()
            pendingAvatarPreWarmAccountIds.clear()
            profileMaterializations.clear()
        }
        profileRevision += 1
        profileAccountRevisions.reset()
    }

    // Keep platform callbacks at the end of instance initialization. These
    // coroutines can execute immediately on another thread and must not observe
    // fields declared later in this class before their initializers have run.
    init {
        // DateTimeFormatState owns the live values every formatter reads; this state persists them.
        val storedTimeFormat = preferences.getString(TIME_FORMAT_KEY, null)
        val storedDateFormat = preferences.getString(DATE_FORMAT_KEY, null)
        DateTimeFormatState.timeFormat = TimeFormatPreference.fromPreference(storedTimeFormat)
        DateTimeFormatState.dateFormat = DateFormatPreference.fromPreference(storedDateFormat)
        if (startPlatformServices) {
            mutationsScope.launch { notificationPreviewSettings.recover() }
            mutationsScope.launch {
                val policy = withContext(Dispatchers.IO) { readNotificationBatteryPolicy(appContext) }
                notificationDelivery.notificationBatteryPolicy = policy
            }
            if (BuildConfig.SELF_UPDATE_ENABLED) {
                // Off-main: sweeping stale APKs touches the cache dir (listFiles + deletes).
                mutationsScope.launch(Dispatchers.IO) { appSelfUpdateFlow.sweepStaleApks() }
                notificationScope.launch { refreshAppUpdateIfStale(notifyIfNewer = false) }
            }
            // Off-main: the ConnectivityManager registration + seed query are
            // binder IPCs and this constructor runs on the main thread. Until the
            // seed lands, the snapshot reads as offline/no-networks — the same
            // conservative answer the auto-download gate gives for "unknown".
            mutationsScope.launch(Dispatchers.IO) { registerActiveNetworkListener() }
            // Wipe pre-encryption cache entries promptly after upgrade without doing
            // directory I/O in this main-thread constructor.
            mutationsScope.launch(Dispatchers.IO) {
                diskMediaCache.prepare()
                displayThumbnails.prepare()
            }
            mutationsScope.launch(Dispatchers.IO) { contactPictureStore.cleanOrphans() }
            // Load the persisted per-chat channel scopes before the settings UI
            // can request them, without blocking the main-thread constructor.
            mutationsScope.launch(Dispatchers.IO) { conversationNotificationRouting }
            mutationsScope.launch(Dispatchers.IO) { conversationAlertPreferences }
            appLock.prewarmUnlockTimestamp()
            mutationsScope.launch { refreshTtsAvailability() }
        }
    }

    companion object {
        private const val ACTIVE_ACCOUNT_KEY = "active_account"
        private const val DEVELOPER_MODE_KEY = "developer_mode"
        private const val STREAMING_DEBUG_MODE_KEY = "streaming_debug_mode"
        private const val FORCE_INCOGNITO_KEYBOARD_KEY = "force_incognito_keyboard"
        private const val THEME_MODE_KEY = "theme_mode"
        private const val FONT_SCALE_KEY = "font_scale"
        private const val APP_FONT_KEY = "app_font"
        private const val MEDIA_AUTO_DOWNLOAD_KEY = "media_auto_download"

        // Per-account matrix prefs (issue #407), keyed by accountIdHex (or a
        // "default" bucket when no account is bound). Distinct from the legacy
        // 3-state key, which this migrates from on first per-account load.
        private const val MEDIA_AUTO_DOWNLOAD_MATRIX_KEY_PREFIX = "media_auto_download_matrix:"
        private const val MEDIA_QUALITY_KEY = "media_quality"
        private const val ENTER_KEY_BEHAVIOR_KEY = "enter_key_behavior"
        private const val TIME_FORMAT_KEY = "time_format"
        private const val DATE_FORMAT_KEY = "date_format"
        private const val DEFAULT_NOTIFICATIONS_ENABLE_ATTEMPTED_KEY = "default_notifications_enable_attempted"
        private const val DISAPPEARING_TOOLTIP_SHOWN_KEY = "disappearing_tooltip_shown"

        private const val PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS = 60_000L
        internal const val PROFILE_PRESENTATION_WARM_FANOUT = 6
        private const val PROFILE_REFRESH_FANOUT = 6
        private const val NOTIFICATION_ENRICHMENT_FANOUT = 4

        private const val MAX_PROFILE_PRESENTATION_CACHE_ENTRIES = 4096
        private const val MAX_USER_PROFILE_CACHE_ENTRIES = 4096
        private const val MAX_PENDING_AVATAR_PREWARMS = 64
        private const val MAX_MATERIALIZING_PROFILES = 4096
        private const val MAX_GROUP_MEMBER_SNAPSHOT_CACHE_ENTRIES = 1024
        private const val MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES = 4096
        private const val NOTIFICATION_CONTENT_INVALIDATION_CAPACITY = 512

        /** How many receipt history lookups run at once; more wait their turn rather than fan out. */
        private const val RECEIPT_LOOKUP_CONCURRENCY = 4

        /** How many recent receipts are remembered for de-duplicating the two receipt sources. */
        private const val RECEIPT_CLAIM_CAPACITY = 512
        private const val NOTIFICATION_STARTUP_RECEIVER_TIMEOUT_MILLIS = 5_000L
        private const val NOTIFICATION_NETWORK_RECOVERY_RECEIVER_TIMEOUT_MILLIS = 3_000L

        private const val NOTIFICATION_PUSH_DRAIN_TIMEOUT_MILLIS = 10_000L
        private const val MAX_RETAINED_CONVERSATION_STATES = 32
    }
}
