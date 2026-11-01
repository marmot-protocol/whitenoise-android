package dev.ipf.whitenoise.android.state

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
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
import com.google.firebase.messaging.FirebaseMessaging
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EventsSubscription
import dev.ipf.marmotkit.HostPerformanceOperationFfi
import dev.ipf.marmotkit.HostPerformanceOutcomeFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationsSubscription
import dev.ipf.marmotkit.OnboardingSnapshotFfi
import dev.ipf.marmotkit.ProductAnalyticsActivityFfi
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.marmotkit.RelayEndpointClassificationFfi
import dev.ipf.marmotkit.RelayEndpointPolicyFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.RetentionSweepGroupOutcomeFfi
import dev.ipf.marmotkit.RetentionSweepStatusFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.UsageDiagnosticsSettingsFfi
import dev.ipf.marmotkit.UsageDiagnosticsStatusFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.RuntimePolicyHooks
import dev.ipf.whitenoise.android.amber.AmberSignerController
import dev.ipf.whitenoise.android.audio.ConversationDictationController
import dev.ipf.whitenoise.android.audio.ConversationDictationDraftSnapshot
import dev.ipf.whitenoise.android.audio.ConversationDictationProvider
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
import dev.ipf.whitenoise.android.audio.tts.runtimeTrustForSelectionWarning
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.AvatarLoadRecovery
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.HostSafety
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.core.MarmotClientRootGate
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.core.encryptedGroupAvatarCacheKey
import dev.ipf.whitenoise.android.diagnostics.DmCreationAttempt
import dev.ipf.whitenoise.android.diagnostics.DmCreationDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceDiagnostics
import dev.ipf.whitenoise.android.diagnostics.PerformanceLayer
import dev.ipf.whitenoise.android.diagnostics.PerformanceOperation
import dev.ipf.whitenoise.android.diagnostics.PerformancePhase
import dev.ipf.whitenoise.android.diagnostics.PerformanceResult
import dev.ipf.whitenoise.android.diagnostics.PerformanceTrigger
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnosticStage
import dev.ipf.whitenoise.android.diagnostics.StartupDiagnostics
import dev.ipf.whitenoise.android.diagnostics.StartupPerformanceDiagnostics
import dev.ipf.whitenoise.android.media.AndroidKeystoreDiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.MediaInventory
import dev.ipf.whitenoise.android.media.MediaThumbnail
import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorSourceStore
import dev.ipf.whitenoise.android.media.editor.MarmotMessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.notifications.ActiveConversationCard
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.ConversationNotificationRouting
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPreferences
import dev.ipf.whitenoise.android.notifications.ForegroundStartTrigger
import dev.ipf.whitenoise.android.notifications.LocalNotificationContent
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
import dev.ipf.whitenoise.android.notifications.NativePushCapability
import dev.ipf.whitenoise.android.notifications.NotificationBatteryPolicy
import dev.ipf.whitenoise.android.notifications.NotificationChannels
import dev.ipf.whitenoise.android.notifications.NotificationReactionSendOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplyCommitProbe
import dev.ipf.whitenoise.android.notifications.NotificationReplyCompletionStore
import dev.ipf.whitenoise.android.notifications.NotificationReplyRecoveryBoundary
import dev.ipf.whitenoise.android.notifications.NotificationReplyRecoveryLookup
import dev.ipf.whitenoise.android.notifications.NotificationReplyRecoveryState
import dev.ipf.whitenoise.android.notifications.NotificationReplySendOutcome
import dev.ipf.whitenoise.android.notifications.NotificationReplyTimelinePage
import dev.ipf.whitenoise.android.notifications.NotificationReplyTimelineRecord
import dev.ipf.whitenoise.android.notifications.NotificationStreamForegroundService
import dev.ipf.whitenoise.android.notifications.PUSH_WAKE_MAX_ATTEMPTS
import dev.ipf.whitenoise.android.notifications.PinnedConversationCapability
import dev.ipf.whitenoise.android.notifications.PinnedConversationPicture
import dev.ipf.whitenoise.android.notifications.PinnedConversationPresentation
import dev.ipf.whitenoise.android.notifications.PinnedConversationShortcuts
import dev.ipf.whitenoise.android.notifications.PinnedConversationTokens
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushStorageRecovery
import dev.ipf.whitenoise.android.notifications.PushTokenStorageException
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import dev.ipf.whitenoise.android.notifications.PushWakeAdmission
import dev.ipf.whitenoise.android.notifications.PushWakeAttemptBudget
import dev.ipf.whitenoise.android.notifications.PushWakeAttemptClaim
import dev.ipf.whitenoise.android.notifications.PushWakeDiagnostics
import dev.ipf.whitenoise.android.notifications.PushWakeEvent
import dev.ipf.whitenoise.android.notifications.PushWakeRecoveryScheduler
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.hasLegacyContactShortcuts
import dev.ipf.whitenoise.android.notifications.normalizeNotificationReaction
import dev.ipf.whitenoise.android.notifications.notificationReactionOutcome
import dev.ipf.whitenoise.android.notifications.notificationReplyRecoveryBoundary
import dev.ipf.whitenoise.android.notifications.notificationReplySendWindowReady
import dev.ipf.whitenoise.android.notifications.readNotificationBatteryPolicy
import dev.ipf.whitenoise.android.share.CappedShareStreamStaging
import dev.ipf.whitenoise.android.share.SHARE_STREAM_MAX_ITEMS
import dev.ipf.whitenoise.android.share.ShareInboundStager
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareShortcutPublisher
import dev.ipf.whitenoise.android.share.ShareStagingStore
import dev.ipf.whitenoise.android.share.shareResolveMime
import dev.ipf.whitenoise.android.state.GroupInviteNotificationIdentityRefreshStore.RefreshCandidate
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.relaysConnectedFromHealth
import dev.ipf.whitenoise.android.ui.conversation.media.StagedUploadSource
import dev.ipf.whitenoise.android.ui.conversation.media.closeQuietly
import dev.ipf.whitenoise.android.ui.onboarding.SignUpController
import dev.ipf.whitenoise.android.ui.onboarding.setup.AccountSetupCoordinator
import dev.ipf.whitenoise.android.ui.onboarding.setup.setupOptions
import dev.ipf.whitenoise.android.updates.AppSelfUpdateFlows
import dev.ipf.whitenoise.android.updates.AppSelfUpdateState
import dev.ipf.whitenoise.android.updates.AppUpdateConstants
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import dev.ipf.whitenoise.android.audio.ConversationDictationTargetValidation as TargetValidation
import dev.ipf.whitenoise.android.notifications.notificationReplyCommitProbe as probeNotificationReplyCommit

private const val NATIVE_ATTACHMENT_PERMISSION_RETRY_LIMIT = 3
private const val NATIVE_ATTACHMENT_PERMISSION_RETRY_DELAY_MILLIS = 500L

/** How long durable automatic work waits for the native runtime to apply the live approval before retrying. */
internal const val NATIVE_ATTACHMENT_PERMISSION_SETTLE_MILLIS = 10_000L

/** Automatic requests made per durable run before a refusal or a lasting pause becomes a retryable failure. */
private const val AUTOMATIC_ADMISSION_ATTEMPTS = 2
private const val INCOMING_ATTACHMENT_TAG = "DMAttachmentReceipt"

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

internal object ChatScreenshotPreferences {
    private const val KEY_ALLOW_CHAT_SCREENSHOTS = "allow_chat_screenshots"

    fun readAllowChatScreenshots(preferences: SharedPreferences): Boolean = preferences.getBoolean(KEY_ALLOW_CHAT_SCREENSHOTS, true)

    fun readAllowChatScreenshots(context: Context): Boolean =
        readAllowChatScreenshots(
            context.applicationContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE),
        )

    fun writeAllowChatScreenshots(
        preferences: SharedPreferences,
        enabled: Boolean,
    ) {
        editAllowChatScreenshots(preferences.edit(), enabled).apply()
    }

    /** Adds the screenshot preference to a caller-owned durable editor transaction. */
    fun editAllowChatScreenshots(
        editor: SharedPreferences.Editor,
        enabled: Boolean,
    ): SharedPreferences.Editor = editor.putBoolean(KEY_ALLOW_CHAT_SCREENSHOTS, enabled)
}

internal object LongMessageCollapsePreferences {
    private const val KEY_PREFIX = "collapse_long_messages:"

    fun normalizedAccountRef(accountRef: String?): String? =
        accountRef
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    fun normalizedGroupId(groupIdHex: String): String? =
        groupIdHex
            .trim()
            .lowercase(Locale.ROOT)
            .takeIf { it.isNotEmpty() }

    fun preferenceKey(
        accountRef: String?,
        groupIdHex: String,
    ): String? {
        val account = normalizedAccountRef(accountRef) ?: return null
        val group = normalizedGroupId(groupIdHex) ?: return null
        return "$KEY_PREFIX$account:$group"
    }

    fun readCollapseLongMessages(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
    ): Boolean {
        val key = preferenceKey(accountRef, groupIdHex) ?: return true
        return readCollapseLongMessagesByKey(preferences, key)
    }

    fun readCollapseLongMessagesByKey(
        preferences: SharedPreferences,
        key: String,
    ): Boolean = preferences.getBoolean(key, true)

    fun writeCollapseLongMessages(
        preferences: SharedPreferences,
        accountRef: String?,
        groupIdHex: String,
        enabled: Boolean,
    ) {
        val key = preferenceKey(accountRef, groupIdHex) ?: return
        writeCollapseLongMessagesByKey(preferences, key, enabled)
    }

    fun writeCollapseLongMessagesByKey(
        preferences: SharedPreferences,
        key: String,
        enabled: Boolean,
    ) {
        val edit = preferences.edit()
        if (enabled) {
            edit.remove(key)
        } else {
            edit.putBoolean(key, false)
        }
        edit.apply()
    }
}

/** Observable, account-scoped view of the persisted long-message preference. */
internal class LongMessageCollapseState(
    registry: ScopedCacheRegistry,
    private val preferences: SharedPreferences,
    maxEntries: Int,
) {
    private val values =
        ScopedCache<String, MutableState<Boolean>>(
            registry = registry,
            name = "collapse-long-messages",
            maxEntries = maxEntries,
            observable = true,
        )

    fun collapseLongMessages(
        accountRef: String?,
        groupIdHex: String,
    ): Boolean {
        val key = LongMessageCollapsePreferences.preferenceKey(accountRef, groupIdHex) ?: return true
        return values
            .getOrPut(key) {
                mutableStateOf(LongMessageCollapsePreferences.readCollapseLongMessagesByKey(preferences, key))
            }.value
    }

    fun updateCollapseLongMessages(
        accountRef: String?,
        groupIdHex: String,
        enabled: Boolean,
    ) {
        val key = LongMessageCollapsePreferences.preferenceKey(accountRef, groupIdHex) ?: return
        values.getOrPut(key) { mutableStateOf(enabled) }.value = enabled
        LongMessageCollapsePreferences.writeCollapseLongMessagesByKey(preferences, key, enabled)
    }
}

private fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

/**
 * Only proven connect-phase relay failures are safe to resend automatically.
 * Other exceptions may be terminal or may occur after publication.
 */
internal fun notificationReplySendFailureOutcome(throwable: Throwable): NotificationReplySendOutcome =
    if (isTransientRelaySendError(throwable)) {
        NotificationReplySendOutcome.RetryableFailure
    } else {
        NotificationReplySendOutcome.NonRetryableFailure
    }

internal fun notificationReactionSendFailureOutcome(throwable: Throwable): NotificationReactionSendOutcome =
    if (isTransientRelaySendError(throwable)) {
        NotificationReactionSendOutcome.RetryableFailure
    } else {
        NotificationReactionSendOutcome.NonRetryableFailure
    }

internal data class ConversationNotificationTarget(
    val accountRef: String,
    val groupIdHex: String,
)

/**
 * The conversation whose pending tray cards should be cleared when a chat is
 * opened, or null when there's nothing concrete to dismiss.
 *
 * Opening a conversation must clear its notifications on the first open,
 * independent of whether the read watermark advances (issue #803). The
 * mark-read path can be deduped/swallowed or race a not-yet-ready read anchor
 * on a cold open, so this gives [WhiteNoiseAppState.setActiveConversation] a
 * target it can dismiss immediately and unconditionally. Returns a target only
 * when both the active account and the opened group are nonblank.
 */
internal fun conversationOpenDismissalTarget(
    activeAccountRef: String?,
    groupIdHex: String?,
): ConversationNotificationTarget? {
    val account = activeAccountRef?.takeIf { it.isNotBlank() } ?: return null
    val group = groupIdHex?.takeIf { it.isNotBlank() } ?: return null
    return ConversationNotificationTarget(account, group)
}

/**
 * The still-open conversation that becomes visible when the app returns to the
 * foreground. Backgrounding deliberately retains the active conversation, so
 * an unchanged Compose effect does not call [WhiteNoiseAppState.setActiveConversation]
 * again on resume. Do not clear its tray cards while the app lock covers it.
 */
internal fun visibleConversationDismissalTarget(
    appInForeground: Boolean,
    appLockScreenVisible: Boolean,
    activeAccountRef: String?,
    groupIdHex: String?,
): ConversationNotificationTarget? {
    if (!appInForeground || appLockScreenVisible) return null
    return conversationOpenDismissalTarget(activeAccountRef, groupIdHex)
}

internal suspend fun dismissConversationNotificationsOnOpen(
    activeAccountRef: String?,
    groupIdHex: String?,
    dismissConversationNotifications: suspend (String, String) -> Unit,
) {
    conversationOpenDismissalTarget(activeAccountRef, groupIdHex)?.let { target ->
        dismissConversationNotifications(target.accountRef, target.groupIdHex)
    }
}

/**
 * A live notification subscription is a healthy local broadcast receiver and
 * survives relay connectivity changes. Reuse the listener job so queued or
 * in-flight updates are never destroyed; if it is backing off, reconnect wakes
 * that same job and awaits the receiver state it owns.
 */
internal suspend fun awaitActiveNotificationReceiver(
    isReceiverActive: () -> Boolean,
    listenerJob: Job,
    awaitReceiverActive: suspend () -> Unit,
): Boolean {
    if (isReceiverActive()) return true
    return coroutineScope {
        val ready =
            async(start = CoroutineStart.UNDISPATCHED) {
                awaitReceiverActive()
                true
            }
        try {
            select {
                ready.onAwait { it }
                listenerJob.onJoin { isReceiverActive() }
            }
        } finally {
            ready.cancel()
        }
    }
}

/**
 * Establish the process-owned notification receiver at cold startup and wait
 * only for the bounded bootstrap budget. The listener itself outlives this
 * caller: timeout or bootstrap cancellation leaves its existing retry loop in
 * [notificationJob], while repeated startup callers reuse the same job.
 */
internal suspend fun awaitNotificationReceiverForStartup(
    notificationJob: NotificationJobSlot,
    receiverActive: StateFlow<Boolean>,
    receiverRetryWake: MutableStateFlow<Long>,
    timeoutMillis: Long,
    launchListener: () -> Job,
): Boolean {
    val listenerJob = notificationJob.currentOrStart(launchListener) ?: return false
    if (!receiverActive.value) receiverRetryWake.update { it + 1L }
    return withTimeoutOrNull(timeoutMillis) {
        awaitActiveNotificationReceiver(
            isReceiverActive = { receiverActive.value },
            listenerJob = listenerJob,
            awaitReceiverActive = { receiverActive.first { it } },
        )
    } ?: false
}

internal suspend fun awaitNotificationRetryWindow(
    retryWake: StateFlow<Long>,
    capturedGeneration: Long,
    backoffMillis: Long,
) {
    withTimeoutOrNull(backoffMillis) {
        retryWake.first { it != capturedGeneration }
    }
}

internal interface AppNotificationSubscription {
    suspend fun next(): NotificationUpdateFfi?

    fun close()
}

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

private class NativeNotificationSubscription(
    private val delegate: NotificationsSubscription,
) : AppNotificationSubscription {
    override suspend fun next(): NotificationUpdateFfi? = delegate.next()

    override fun close() = delegate.close()
}

private suspend fun subscribeToNotifications(marmot: MarmotInterface): AppNotificationSubscription =
    NativeNotificationSubscription(marmot.subscribeNotifications())

internal class NotificationJobSlot {
    private val lock = Any()
    private var job: Job? = null
    private var cancellation: CompletableDeferred<Unit>? = null

    fun isActive(): Boolean = synchronized(lock) { job?.isActive == true }

    // Called while holding [lock]; [start] must only enqueue work and return promptly.
    fun startIfInactive(start: () -> Job) {
        currentOrStart(start)
    }

    fun currentOrStart(start: () -> Job): Job? =
        synchronized(lock) {
            if (cancellation != null) return@synchronized null
            job?.takeIf { it.isActive } ?: start().also { job = it }
        }

    suspend fun cancelAndJoin() {
        var ownsCancellation = false
        var ownedJob: Job? = null
        val completion =
            synchronized(lock) {
                cancellation
                    ?: CompletableDeferred<Unit>().also {
                        cancellation = it
                        ownsCancellation = true
                        ownedJob = job
                        job = null
                    }
            }
        if (!ownsCancellation) {
            completion.await()
            return
        }
        try {
            withContext(NonCancellable) {
                ownedJob?.cancelAndJoin()
            }
        } finally {
            synchronized(lock) {
                if (cancellation === completion) cancellation = null
            }
            completion.complete(Unit)
        }
    }
}

/** Inline account profiles that the chat-list top bar can render at once. */
internal const val MAX_TOP_BAR_OTHER_ACCOUNTS = 3

/**
 * Signed-in signing accounts other than [activeLabel], for the chat-list top
 * bar's one-tap switcher row. Signed-out, read-only (neither local nor
 * external signer), and blank-label entries are excluded (they are not one-tap
 * switch targets from the chat-list chrome).
 *
 * Returns empty when [activeLabel] is null. A destructive Sign Out & Wipe
 * transiently nulls the active account while it drains the wiped account's
 * streams (#610), *before* [WhiteNoiseAppState.accounts] is refreshed. Without
 * this guard the "other accounts" filter (`label != activeLabel`) would match
 * every entry in the still-stale list and flash the just-wiped account — and
 * any account lingering in that pre-refresh snapshot — until the wipe settles
 * (#809). The row's premise is "accounts other than the active one," which is
 * undefined with no active account, so it must present nothing across that
 * transition.
 */

internal fun otherAccountAvatars(
    accounts: List<AccountSummaryFfi>,
    activeLabel: String?,
): List<AccountSummaryFfi> {
    if (activeLabel == null) return emptyList()
    return accounts.filter { account ->
        account.isSignedInSigningAccount() &&
            account.label != activeLabel
    }
}

/**
 * Profiles that must be materialized before an account-switch composition is
 * published. Direct-chat peers seed the target chat list, while the bounded
 * account ids seed the other-account avatars rendered in its top bar.
 */
internal fun accountSwitchProfileSeedIds(
    directPeerIds: List<String>,
    accounts: List<AccountSummaryFfi>,
    targetAccountRef: String,
): List<String> =
    (
        directPeerIds +
            otherAccountAvatars(accounts, targetAccountRef)
                .take(MAX_TOP_BAR_OTHER_ACCOUNTS)
                .map(AccountSummaryFfi::accountIdHex)
    ).distinctBy { it.lowercase(Locale.ROOT) }

enum class RelayListKind {
    Nip65,
    Inbox,
}

internal enum class RelayUrlValidationResult {
    Acceptable,
    Invalid,
}

internal data class RelayListEditPlan(
    val relays: List<String>,
    val requiredRelay: String? = null,
)

private enum class RelayPublishValidationError {
    Invalid,
    Blocked,
    Unavailable,
}

private sealed interface RelayPublishValidation {
    data class Ready(
        val relays: List<String>,
    ) : RelayPublishValidation

    data class Rejected(
        val error: RelayPublishValidationError,
    ) : RelayPublishValidation
}

/** Canonicalizes secure public relay URLs without restricting them to White Noise-owned hosts. */
internal fun normalizeRelayUrls(relays: Iterable<String>): List<String> =
    relays
        .mapNotNull(::canonicalRelayUrl)
        .distinct()

internal fun telemetryServiceVersion(
    versionName: String,
    versionCode: Int,
): String = "${versionName.trim()}+$versionCode"

internal fun telemetryDeploymentEnvironment(value: String): String =
    when (val normalized = value.trim().lowercase(Locale.ROOT)) {
        "production", "staging", "development", "test" -> normalized
        "android-release" -> "production"
        "dev" -> "development"
        else -> "production"
    }

internal fun telemetryDeviceModelIdentifier(model: String): String? = model.trim().takeIf { it.isNotEmpty() }

/** Returns whether a relay URL passes the synchronous structural and literal-host checks. */
internal fun isAcceptableRelayUrl(url: String): Boolean = relayUrlValidationResult(url) == RelayUrlValidationResult.Acceptable

/** Classifies a relay URL for immediate editor feedback; MarmotKit remains the publish boundary. */
internal fun relayUrlValidationResult(url: String): RelayUrlValidationResult =
    if (canonicalRelayUrl(url) == null) RelayUrlValidationResult.Invalid else RelayUrlValidationResult.Acceptable

/** Builds an add mutation while preserving every other structurally valid imported relay. */
internal fun relayListAfterAddition(
    currentRelays: List<String>,
    relayToAdd: String,
): RelayListEditPlan? {
    val canonicalRelay = canonicalRelayUrl(relayToAdd) ?: return null
    return RelayListEditPlan(
        relays = normalizeRelayUrls(currentRelays + canonicalRelay),
        requiredRelay = canonicalRelay,
    )
}

/** Builds a removal mutation that deletes only the selected relay and keeps safe imported siblings. */
internal fun relayListAfterRemoval(
    currentRelays: List<String>,
    relayToRemove: String,
    fallbackRelays: List<String> = MarmotClient.bootstrapRelays,
): RelayListEditPlan {
    val target = relayToRemove.trim()
    val remaining =
        currentRelays
            .map(String::trim)
            .filter { it.isNotEmpty() && it != target }
            .distinct()
    val supported = normalizeRelayUrls(remaining)
    return RelayListEditPlan(
        relays = supported.ifEmpty { normalizeRelayUrls(fallbackRelays) },
    )
}

/** Prevents removing the final publishable relay while permitting targeted cleanup. */
internal fun canRemoveRelay(
    currentRelays: List<String>,
    relay: String,
): Boolean = normalizeRelayUrls(currentRelays.filterNot { it.trim() == relay.trim() }).isNotEmpty()

/** Keeps MarmotKit-approved endpoints while requiring a requested addition to survive classification. */
internal fun allowedRelayUrlsForPublish(
    plan: RelayListEditPlan,
    classified: List<RelayEndpointClassificationFfi>,
): List<String>? {
    val requiredRelayAllowed =
        plan.requiredRelay == null ||
            classified.any {
                it.policy == RelayEndpointPolicyFfi.ALLOWED && canonicalRelayUrl(it.endpoint) == plan.requiredRelay
            }
    val allowedRelays =
        classified
            .filter { it.policy == RelayEndpointPolicyFfi.ALLOWED }
            .mapNotNull(RelayEndpointClassificationFfi::normalizedEndpoint)
            .mapNotNull(::canonicalRelayUrl)
            .distinct()
    return allowedRelays.takeIf { it.isNotEmpty() && requiredRelayAllowed }
}

internal enum class RelayResolveTimeCheckResult {
    Passed,
    Blocked,
    Unavailable,
}

/** Injectable DNS resolver for relay resolve-time SSRF checks (unit tests). */
internal typealias RelayHostResolver = (String) -> Array<InetAddress>?

/**
 * Resolve-time SSRF guard for app-owned relay fetches. Call from a background
 * dispatcher after cheap [canonicalRelayUrl] / [normalizeRelayUrls] canonicalization.
 */
internal fun relayUrlResolveTimeCheckResult(
    canonicalUrl: String,
    resolve: RelayHostResolver = ::resolveRelayHost,
): RelayResolveTimeCheckResult {
    val host =
        runCatching { URI(canonicalUrl).host?.removeSurrounding("[", "]") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return RelayResolveTimeCheckResult.Blocked
    val resolved = resolve(host) ?: return RelayResolveTimeCheckResult.Unavailable
    if (resolved.isEmpty()) return RelayResolveTimeCheckResult.Unavailable
    return if (resolved.none { HostSafety.isPrivateOrLoopbackAddress(it) }) {
        RelayResolveTimeCheckResult.Passed
    } else {
        RelayResolveTimeCheckResult.Blocked
    }
}

internal fun relayUrlPassesResolveTimeCheck(
    canonicalUrl: String,
    resolve: RelayHostResolver = ::resolveRelayHost,
): Boolean = relayUrlResolveTimeCheckResult(canonicalUrl, resolve) == RelayResolveTimeCheckResult.Passed

/** Resolve-checks only a newly requested relay; removal plans preserve unreachable imported relays. */
internal fun requiredRelayResolveTimeCheckResult(
    plan: RelayListEditPlan,
    resolve: RelayHostResolver = ::resolveRelayHost,
): RelayResolveTimeCheckResult =
    plan.requiredRelay
        ?.let { relayUrlResolveTimeCheckResult(it, resolve) }
        ?: RelayResolveTimeCheckResult.Passed

private fun resolveRelayHost(host: String): Array<InetAddress>? = runCatching { InetAddress.getAllByName(host) }.getOrNull()

private fun canonicalRelayUrl(url: String): String? {
    return runCatching {
        val uri = URI(url.trim())
        if (uri.scheme?.equals("wss", ignoreCase = true) != true || uri.userInfo != null) {
            return@runCatching null
        }
        if (uri.port != -1 && uri.port != 443) return@runCatching null
        val host = uri.host ?: uri.rawAuthority?.relayHostCandidate() ?: return@runCatching null
        val hostWithoutBrackets = host.removeSurrounding("[", "]")
        if (hostWithoutBrackets.any { it.isWhitespace() }) return@runCatching null
        val asciiHost =
            if (hostWithoutBrackets.contains(":")) {
                hostWithoutBrackets
            } else {
                IDN.toASCII(hostWithoutBrackets)
            }
        val canonicalHost =
            asciiHost.lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
                ?: return@runCatching null
        // SSRF guard: relay URLs can arrive from untrusted protocol messages, so
        // never accept one that points the client at loopback or the local
        // network. See issue #82.
        if (HostSafety.isPrivateOrLoopbackHost(canonicalHost)) return@runCatching null
        val authorityHost = if (canonicalHost.contains(":")) "[$canonicalHost]" else canonicalHost
        val port =
            uri.port
                .takeIf { it >= 0 }
                ?.let { ":$it" }
                .orEmpty()
        val path = uri.rawPath.orEmpty()
        val query = uri.rawQuery?.let { "?$it" }.orEmpty()
        "wss://$authorityHost$port$path$query"
    }.getOrNull()
}

private fun String.relayHostCandidate(): String? {
    if (isBlank() || contains("@")) return null
    if (startsWith("[")) return substringAfter("[", "").substringBefore("]", "").takeIf { it.isNotBlank() }
    return if (count { it == ':' } == 1) substringBefore(":") else this
}

/**
 * Access-order conversation-state retention with an optional protected key for
 * the chat currently on screen. The protected key is promoted before overflow
 * pruning so an active controller's optimistic/retry state cannot be aged out
 * by other conversations touching their own state maps.
 * Queued and running uploads also protect their conversation until settlement;
 * pruning then restores the ordinary window and releases inactive private sources.
 */
internal class ConversationStateRetention(
    private val maxEntries: Int,
    private val isProtected: (String) -> Boolean = { false },
) {
    private val recentKeys = LinkedHashMap<String, Unit>(16, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun retain(
        key: String,
        protectedKey: String? = null,
    ): List<String> {
        recentKeys[key] = Unit
        promoteProtected(protectedKey)
        return evictOverflow(protectedKey)
    }

    fun promote(
        key: String,
        protectedKey: String? = null,
    ): List<String> {
        if (recentKeys.containsKey(key)) {
            recentKeys[key] = Unit
        }
        promoteProtected(protectedKey)
        return evictOverflow(protectedKey)
    }

    fun keysSnapshot(): List<String> = recentKeys.keys.toList()

    /** Settled uploads return to the ordinary retention window without promoting stale state. */
    fun prune(protectedKey: String? = null): List<String> = evictOverflow(protectedKey)

    private fun promoteProtected(protectedKey: String?) {
        if (protectedKey != null && recentKeys.containsKey(protectedKey)) {
            recentKeys[protectedKey] = Unit
        }
    }

    /** Evicts idle conversations only; active uploads may temporarily exceed the ordinary retention window. */
    private fun evictOverflow(protectedKey: String?): List<String> {
        val evicted = mutableListOf<String>()
        while (recentKeys.size > maxEntries) {
            val staleKey = recentKeys.keys.firstOrNull { it != protectedKey && !isProtected(it) } ?: break
            recentKeys.remove(staleKey)
            evicted += staleKey
        }
        return evicted
    }
}

internal data class ProfilePresentationRevision(
    val profiles: Int,
    val contactNicknames: Int,
)

internal data class ProfileAccountRevision(
    val epoch: Int,
    val account: Int,
)

internal fun contactNicknameAccountRefForAccess(
    accountRef: String?,
    accounts: List<AccountSummaryFfi>,
    contactPubkeyHex: String,
): String? {
    val account = accountRef ?: return null
    if (isLocalContactAccount(accounts, contactPubkeyHex)) return null
    return account
}

internal fun isLocalContactAccount(
    accounts: List<AccountSummaryFfi>,
    accountIdHex: String,
): Boolean = accounts.any { it.accountIdHex.equals(accountIdHex, ignoreCase = true) }

internal fun networkDisplayNameFallback(
    accountLabel: String?,
    accountIdHex: String,
    shortNpub: (String) -> String,
): String {
    val label = accountLabel?.takeIf { it.isNotBlank() }
    if (label != null && !IdentityFormatter.isNostrIdentityFallback(label)) {
        return label
    }
    return shortNpub(accountIdHex)
}

internal fun npubPresentation(
    accountIdHex: String,
    cachedNpub: String?,
    encode: (String) -> String?,
): String {
    val candidate = cachedNpub ?: runCatching { encode(accountIdHex) }.getOrNull()
    return candidate
        ?.takeIf { it.startsWith("npub1") && !it.equals(accountIdHex, ignoreCase = true) }
        .orEmpty()
}

/**
 * Fail-closed npub for visible/copy profile and message surfaces. Keeps an
 * already-canonical [reference] when it is not the raw account hex; otherwise
 * derives through [npubForDisplay] from [resolvedAccountIdHex].
 */
internal fun presentationNpubFromReference(
    reference: String,
    resolvedAccountIdHex: String?,
    npubForDisplay: (String) -> String,
): String {
    if (
        reference.startsWith("npub1", ignoreCase = true) &&
        resolvedAccountIdHex?.equals(reference, ignoreCase = true) != true
    ) {
        return reference
    }
    return resolvedAccountIdHex?.let(npubForDisplay).orEmpty()
}

internal fun operationalNpub(
    accountIdHex: String,
    cachedNpub: String?,
    encode: (String) -> String?,
): String = cachedNpub ?: runCatching { encode(accountIdHex) }.getOrNull() ?: accountIdHex

private const val APP_STATE_SCOPE_LOG_TAG = "WhiteNoiseAppState"
private const val FCM_REGISTRATION_BATCH_SIZE = 3
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

private const val NOTIFICATION_REPLY_SEND_WINDOW_POLL_MILLIS = 25L

private data class AccountBubbleColorSlot(
    val accountRef: String,
    val theme: BubbleTheme,
    val side: BubbleSide,
)

private data class AccountActionColorSlot(
    val accountRef: String,
    val theme: BubbleTheme,
)

private data class PendingAccountSwitchTrace(
    val accountRef: String,
    val startedAtMs: Long,
)

private class AccountSwitchSnapshotSuperseded : RuntimeException()

private data class StartupUnreadRefresh(
    val accounts: List<AccountSummaryFfi>,
    val accountListRevision: Long,
)

private data class AccountUnreadFoldResult(
    val accountRef: String,
    val value: AccountUnreadValue,
)

class WhiteNoiseAppState private constructor(
    context: Context,
    val draftStore: DraftStore,
    startPlatformServices: Boolean,
    private val accountIdHexResolver: (suspend (String) -> String?)?,
    private val profileReader: (suspend (String) -> UserProfileMetadataFfi?)?,
    private val profileDisplayNameReader: (suspend (String) -> String?)?,
    private val npubPresentationReader: (suspend (String) -> String?)?,
    private val profileRefreshRequest: (suspend (String) -> Unit)?,
    private val identityLoginCalls: IdentityLoginCalls?,
    private val marmotAccessObserver: (() -> Unit)?,
    private val marmotRuntimeFactory: (Context) -> AppMarmotRuntime,
    initialMarmotRuntime: AppMarmotRuntime?,
    private val notificationSubscriber: suspend (MarmotInterface) -> AppNotificationSubscription,
    private val notificationDispatcher: CoroutineDispatcher,
    private val marmotIoDispatcher: CoroutineDispatcher,
    private val notificationCardCancellationDispatcher: CoroutineDispatcher,
    private val notificationReceiverTimeoutMillis: () -> Long,
    private val pushWakeNowMs: () -> Long,
    private val pushWakeStorageDispatcher: CoroutineDispatcher,
    private val schedulePushWakeRecovery: () -> Boolean,
    private val bootstrapActionableTimeoutMillis: () -> Long,
    private val notificationFirstPostTimingObserver: ((NotificationFirstPostTimingEvent) -> Unit)?,
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
            .PublicJpegPreviewPreferences(appContext)
    internal val publicJpegPreviewLoader =
        dev.ipf.whitenoise.android.media
            .PublicJpegPreviewLoader()

    /** Emoji images already uploaded for the current epoch, so sends and retries never upload one twice. */
    internal val emojiUploads = EmojiUploadCache()
    private val preferences = preferencesOverride ?: appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
    internal val contactPictureStore =
        ContactPictureStore(preferences, appContext.noBackupFilesDir.resolve("contact-pictures"))
            .also {
                dev.ipf.whitenoise.android.core.PrivateContactAvatarLoader
                    .attach(it) { activeAccountRef }
            }
    internal val localGroupDeleteCleanupJournal =
        LocalGroupDeleteCleanupJournal(appContext.noBackupFilesDir.resolve("local-group-delete-cleanup"))
    internal val localGroupDeleteCleanupMutex = Mutex()
    internal val defaultDisappearingMessagesPreferences =
        DefaultDisappearingMessagesPreferences(appContext, preferences)
    internal val swipePreferences = SwipePreferences(preferences)
    internal val conversationDictationPreferences = ConversationDictationPreferences(appContext)
    internal val microphoneCaptureCoordinator = MicrophoneCaptureCoordinator()
    private val dictationMicrophoneOwner = Any()
    internal val conversationDictation: ConversationDictationController
        get() = conversationDictationOwner.value

    /** Connection lifecycle checks must not create a speech controller in an idle process. */
    internal fun initializedConversationDictation(): ConversationDictationController? =
        if (conversationDictationOwner.isInitialized()) conversationDictationOwner.value else null

    private val conversationDictationOwner =
        lazy {
            ConversationDictationController(
                context = appContext,
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
            )
        }

    /** Settings discovery is lifecycle-local; protocol data never enters this platform snapshot. */
    internal suspend fun discoverDictationProviders(): List<ConversationDictationProvider> =
        discoverDictationProvidersForSettings(appContext, conversationDictationPreferences)

    private val legacyDraftMigrationSource by lazy { LegacyDraftMigrationSource(appContext) }
    internal val editorSourceStore: EditorSourceStore = EditorSourceStore.create(appContext)
    internal val editorSessionStore: EditorSessionStore = EditorSessionStore.create(appContext)
    internal val messageDraftRepository: MessageDraftRepository =
        messageDraftRepositoryOverride
            ?: MessageDraftRepository(
                gateway = MarmotMessageDraftGateway(::marmot),
                editorSessions = editorSessionStore,
            )
    internal val draftAttachmentRemovalTombstones = DraftAttachmentRemovalTombstones()
    private val chatMuteRepository = ChatMuteRepository(MarmotChatMuteGateway(::marmot), marmotIoDispatcher)

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
            maxBytes = MEDIA_THUMBNAIL_CACHE_MAX_BYTES,
            maxEntryBytes = MEDIA_THUMBNAIL_CACHE_MAX_BYTES,
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
                targetBytes = trim.targetBytes(MEDIA_THUMBNAIL_CACHE_MAX_BYTES),
                keep = mediaThumbnailHolds::containsKey,
            )
        if (plaintextRemoved > 0) bumpMediaCacheRevision()
        if (thumbnailsRemoved > 0) mediaThumbnailRevisionState.update { it + 1L }
        publicJpegPreviewLoader.trimMemory(trim)
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
        mediaPlaintextCache.remove(cacheKey)
        if (mediaThumbnailCache.remove(cacheKey) != null) mediaThumbnailRevisionState.update { it + 1L }
        bumpMediaCacheRevision()
    }

    /** Every key currently resident in either in-memory tier, for group-scoped eviction. */
    internal fun mediaMemoryCacheKeysSnapshot(): Set<String> {
        assertMainThread { "mediaMemoryCacheKeysSnapshot" }
        return (mediaPlaintextCache.keysSnapshot() + mediaThumbnailCache.keysSnapshot()).toSet()
    }

    /**
     * Persistent (L2) cache of decrypted attachment bytes. Survives process
     * restart so re-opening a chat after a kill doesn't re-download every
     * visible image. Sits behind [mediaPlaintextCache] (L1):
     *
     *   L1 hit → return
     *   L2 hit → hydrate L1, return
     *   miss   → FFI download, store in both
     *
     * Lives in `cacheDir/decrypted-media/` — Android's not-backed-up cache
     * surface, encrypted with a Keystore-backed AES-GCM key. Sign-out wipes it
     * alongside L1 so account A's decrypted media doesn't linger after switch.
     */
    internal val diskMediaCache =
        DiskByteCache(
            cacheDir = java.io.File(appContext.cacheDir, "decrypted-media"),
            maxBytes = DISK_MEDIA_CACHE_MAX_BYTES,
            maxEntryBytes = DISK_MEDIA_CACHE_MAX_ENTRY_BYTES,
            maxInMemoryEntryBytes = MEDIA_PLAINTEXT_CACHE_MAX_ENTRY_BYTES,
            keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(),
            onMutation = ::bumpMediaCacheRevision,
        )

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
    private val receiptLookupGate = Semaphore(RECEIPT_LOOKUP_CONCURRENCY)

    /** Recently looked-up receipts, so a message reported by both receipt sources is looked up once. */
    private val receiptClaims = ReceiptClaims(RECEIPT_CLAIM_CAPACITY)

    /** Guards [startupReceiptSubscription], which only the startup listener and its first receipt loop touch. */
    private val startupReceiptLock = Any()

    /** The receipt event subscription opened before the runtime started, with its runtime, until a loop takes it. */
    private var startupReceiptSubscription: Pair<MarmotInterface, EventsSubscription>? = null

    /**
     * Refreshes runtime-scoped permissions after network, preference, or account changes. When [widenWaiting], it
     * also widens any automatic downloads still waiting on a network narrower than the refreshed policy now allows.
     * Network callbacks pass false: the widening target comes from the matrix and account state, which a network
     * edge cannot change, and those callbacks arrive often.
     */
    internal fun refreshNativeAttachmentPermissions(widenWaiting: Boolean = true): Job? {
        val accountRefs = accounts.filterNot { it.signedOut }.map { it.label }
        AutomaticAttachmentNetworkWaits.policyNetworkType = ::liveAutomaticNetworkType
        if (widenWaiting) widenWaitingAutomaticDownloads(accountRefs)
        val runtime: AppMarmotRuntime?
        val revision: Long
        synchronized(permissionRefreshLock) {
            runtime = marmotRuntime
            revision = nativeAttachmentPermissions.invalidate(runtime)
            if (runtime != null) nativeAttachmentPermissions.beginRefresh(revision)
        }
        val engine = runtime?.marmot ?: return null
        // Marmot binding calls remain on the mutations scope's Main.immediate dispatcher.
        return mutationsScope.launch {
            try {
                applyNativeAttachmentPermissions(revision, runtime, engine, accountRefs)
            } finally {
                nativeAttachmentPermissions.endRefresh(revision)
            }
        }
    }

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
                    refreshNativeAttachmentPermissions(widenWaiting = false)?.join()
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
    ) {
        var pending = accountRefs.toSet()
        var attempt = 0
        while (pending.isNotEmpty() && nativeAttachmentPermissions.isCurrent(revision, runtime)) {
            pending =
                nativeAttachmentPermissions.update(revision, runtime, engine, pending.toList()) { account ->
                    // The editor commits off-main. Revoke first, then wait for queued
                    // settings saves so this generation never restores an obsolete matrix.
                    hostPreferenceCommitMutex.withLock {
                        loadMediaAutoDownloadMatrix(account).nativePermission(
                            activeNetworkTypes(),
                            hasValidatedInternet(),
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
    private val accountListLifetime = StalenessGuard()
    private var pendingStartupUnreadRefresh: StartupUnreadRefresh? = null

    @Volatile
    private var bootstrapCompleted = false
    private val nativePushSyncMutex = Mutex()
    private val pushTokenRotationSequence = AtomicLong()
    private val nativePushFallback = NativePushFallbackCoordinator(nativePushFallbackPlatform)
    private val notificationDeliveryModeMutex = Mutex()
    private val notificationDeliveryModeIntent = StalenessGuard()
    private val ttsRefreshMutex = Mutex()
    private val auditLogSettingsMutex = Mutex()
    private val auditUploadConsent = AuditUploadConsent(preferences)
    val auditUploadConsentRequired: Boolean get() = auditUploadConsent.requiresChoice
    private val conversationVibrationChannelMutex = Mutex()

    // Treat preference I/O plus observable-state publication as one transaction;
    // otherwise an older successful hide can publish after a newer hide or wipe.
    private val hiddenMessageMutationMutex = Mutex()
    internal val conversationVibrationPreferences = ConversationVibrationPreferences(appContext)
    internal val conversationNotificationRouting by lazy { ConversationNotificationRouting(appContext) }
    internal val notificationPreviewSettings by lazy { NotificationPreviewSettings.forContext(appContext) }
    private val localNotificationPresenter =
        LocalNotificationPresenter(
            appContext,
            contactAvatarOverride = ::contactAvatarOverrideForScope,
        )
    private val notificationContentInvalidations =
        NotificationContentInvalidations(NOTIFICATION_CONTENT_INVALIDATION_CAPACITY)
    private val notificationReconcileLocks = Array(64) { Mutex() }
    private val inviteNotificationIdentityRefreshStore = GroupInviteNotificationIdentityRefreshStore()
    private val appUpdateRepository =
        AppUpdateRepository(
            appContext,
            selfUpdateEnabled = startPlatformServices && BuildConfig.SELF_UPDATE_ENABLED,
        )
    private val appUpdateNotifier = AppUpdateNotifier(appContext)
    private val appSelfUpdateFlow = AppSelfUpdateFlows.create(appContext)
    internal val chatMutePreferences = ChatMutePreferences(appContext)
    private val authoritativeMuteOverrides = mutableStateMapOf<String, dev.ipf.marmotkit.ChatNotificationSettingsFfi>()
    private val pendingMuteCommands = mutableStateMapOf<String, Int>()
    internal val chatFolderPreferences = ChatFolderPreferences(appContext)
    internal val ttsWarningPreferences = TtsWarningPreferences(appContext)
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
    private var ttsAutoReadSessionKey by mutableStateOf<String?>(null)
    private val ttsAutoReadContinuation by lazy { createTtsAutoReadContinuation(this) }

    // Manual speech deliberately has no auto-read session key, but it still
    // owns decrypted text that must stop when its account is removed.
    private var ttsSpeechAccountRef: String? = null
    internal val attachmentSpeechDestination =
        MutableStateFlow<dev.ipf.whitenoise.android.audio.AttachmentSpeechDestination?>(null)

    /** Reader controls may only address speech owned by the current local account. */
    internal fun ownsCurrentAccountSpeech(): Boolean = ttsSpeechAccountRef?.let { it == activeAccountRef } == true

    fun ownsTtsAutoReadSession(groupIdHex: String): Boolean {
        val key = ttsAutoReadSessionKey ?: return false
        return key == ttsAutoReadKey(activeAccountRef, groupIdHex)
    }

    private fun ttsAutoReadKey(
        account: String?,
        group: String,
    ): String? = account?.let { "$it|${group.lowercase()}" }

    /** Starts read-aloud for one or more speakable messages. */
    fun speakAloud(
        entries: List<TtsSpeakableEntry>,
        locale: java.util.Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        val started = ttsController.speak(entries, locale, startSentenceIndex)
        if (started) {
            // Only successful speech replaces prior auto-read ownership; a failed start
            // (blank text or no engine) leaves the old queue playing and still owned.
            ttsSpeechAccountRef = activeAccountRef
            ttsAutoReadSessionKey = null
            ttsHistorySession.onSessionCleared()
            attachmentSpeechDestination.value = null
            // The session now exists, so the mediaPlayback service must too:
            // it mirrors the controller, keeps playback alive across app
            // switches, and stops itself when the controller goes terminal.
            if (!TtsPlaybackForegroundService.start(appContext)) {
                // A rejected foreground-service start must not leave private
                // read-aloud running without its user-visible stop surface.
                stopSpeaking()
                return false
            }
        }
        return started
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
                    ttsSpeechAccountRef = null
                    ttsAutoReadSessionKey = null
                    ttsHistorySession.onSessionCleared()
                    attachmentSpeechDestination.value = null
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
        if (!ttsHistorySession.allowsLiveAppend()) return false
        return ttsController.appendSpeech(entry, locale)
    }

    /** Stops the active speech queue and immediately revokes every transient source-navigation credential. */
    fun stopSpeaking() {
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
        attachmentSpeechDestination.value = null
    }

    fun setTtsRateOverride(rate: Float?) {
        ttsRatePreferences.setRateOverride(rate)
        ttsController.onSpeechRateChanged()
    }

    private var attachedTtsHandle: TtsEngineHandle? = null

    /** Publishes discovery state and atomically replaces the controller's engine adapter. */
    private fun publishTtsResolution(resolution: TtsResolutionResult?) {
        ttsResolution = resolution
        val handle = resolution?.handle
        // A refresh that kept the same engine handle must not re-attach:
        // attachEngine treats every attach as a replacement and stops any
        // in-flight speech. It must also preserve the utterance-locale voice
        // resolution most recently published by the attached adapter.
        if (handle === attachedTtsHandle) return
        ttsVoiceResolution = handle?.voiceResolution ?: TtsVoiceResolution.Empty
        attachedTtsHandle = handle
        if (handle != null) {
            ttsController.attachEngine(
                AndroidTtsSpeechEngine(
                    textToSpeech = handle.textToSpeech,
                    enginePackage = handle.enginePackage,
                    selectedVoice = { ttsVoicePreferences.selectedVoice(handle.enginePackage) },
                    onVoiceResolved = { voiceResolution -> ttsVoiceResolution = voiceResolution },
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

    private fun pushWakeAttemptBudget() =
        PushWakeAttemptBudget(
            store = pushTokenStore,
            storageDispatcher = pushWakeStorageDispatcher,
            nowMs = pushWakeNowMs,
        )

    private val amberSigner = AmberSignerController(appContext)

    /** Isolated sign-in tests replace only the signer round trip. */
    internal var amberPublicKeyRequest: () -> String = amberSigner::requestPublicKey

    // Per-account (platform, token, server-pubkey, relay-hint) fingerprint
    // of the most recent successful `upsertPushRegistration`. Skip redundant
    // FFI calls when nothing has changed across foreground/token-rotation/
    // account-bind events. Keyed per account so multi-account devices keep a
    // working registration on every enabled account, not just the active
    // one. An entry is removed when the corresponding account disables
    // native push, signs out, or hits a sync failure that may indicate the
    // registration is stale.
    private val perAccountSyncedFingerprints = mutableMapOf<String, PushFingerprint>()

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
    private var accountListLoaded = initialAccounts.isNotEmpty()

    internal val accountUnreadStore = AccountUnreadStore()
    internal val runtimeMirrors = RuntimeMirrors()
    internal val freshGroupCreations = FreshGroupCreationRegistry()

    val accountUnreadCounts: Map<String, ULong>
        get() = accountUnreadStore.retainedCounts

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

    var requireAppUnlock by mutableStateOf(preferences.getBoolean(REQUIRE_APP_UNLOCK_KEY, false))
        private set

    var appLockDelay by mutableStateOf(AppLockDelay.fromPreference(preferences.getString(APP_LOCK_DELAY_KEY, null)))
        private set

    var appLockCredentialAvailable by mutableStateOf(isAppLockCredentialAvailable(appContext))
        private set

    var appLockScreenVisible by mutableStateOf(false)
        private set

    private var appLockTtsBoundaryJob: Job? = null

    val notificationActionsAllowed: Boolean
        get() = notificationActionsAllowed(appLockScreenVisible)

    var appUnlockError by mutableStateOf<AppText?>(null)
        private set

    internal val appUnlockSessions = AppUnlockSessionController()
    internal val appUnlockPromptRequestId: Long get() = appUnlockSessions.latestSessionId

    // Populated by the off-main pre-warm (or the first unlock/background
    // write); the getter NEVER reads the Keystore-backed store itself — a
    // first foreground that beats the pre-warm would otherwise pay the
    // Tink/Keystore init on Main (or block on its lock), the exact cold-start
    // cost this replaced. Until the value lands, 0L errs toward showing the
    // lock prompt — the safe direction for a privacy feature.
    private var lastAppUnlockAtMillisBacking: Long? = null
    private var lastAppUnlockAtMillis: Long
        get() = lastAppUnlockAtMillisBacking ?: 0L
        set(value) {
            lastAppUnlockAtMillisBacking = value
        }

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
    private val profilePresentationLock = Any()
    private val groupMemberSnapshotLock = Any()

    private val globalBubbleColors =
        ScopedCache<AccountBubbleColorSlot, MutableState<Long?>>(
            registry = accountScopedCaches,
            name = "global-bubble-colors",
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
            observable = true,
        )
    private val actionColors =
        ScopedCache<AccountActionColorSlot, MutableState<Long?>>(
            registry = accountScopedCaches,
            name = "action-colors",
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
            observable = true,
        )
    private val chatBubbleColors =
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

    var languageTag by mutableStateOf(preferences.getString(APP_LANGUAGE_TAG_KEY, null).orEmpty())
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    var transientNotice by mutableStateOf<TransientNotice?>(null)
        private set

    // staleness-exempt: monotonically unique notice identity, not an async publication fence.
    private var transientNoticeSequence = 0L

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

    var localNotificationSettings by mutableStateOf<NotificationSettingsFfi?>(null)
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

    var localNotificationPermissionGranted by mutableStateOf(localNotificationPresenter.canPostNotifications())
        private set

    var appUpdateInfo by mutableStateOf(appUpdateRepository.loadInfo())
        private set

    var appSelfUpdateState by mutableStateOf<AppSelfUpdateState>(AppSelfUpdateState.Idle)
        private set

    var backgroundConnectionEnabled by mutableStateOf(BackgroundConnectionPreferences.isEnabled(appContext))
        private set

    var notificationDeliveryModeBusy by mutableStateOf(false)
        private set

    internal var notificationBatteryPolicy by mutableStateOf(NotificationBatteryPolicy.Unknown)
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
    private var profileRevision by mutableIntStateOf(0)
    private var contactNicknameRevision by mutableIntStateOf(0)
    private var profileAccountRevisionEpoch by mutableIntStateOf(0)

    // staleness-exempt: bounded per-account ordering value, not an async publication guard.
    private var profileAccountRevisionSequence = 0
    private val profileAccountRevisions = mutableStateMapOf<String, Int>()
    private val profileAccountRevisionOrder = linkedSetOf<String>()

    /**
     * Read-only Compose-tracked snapshot of profile-presentation invalidations
     * so callers outside this file can subscribe without exposing the mutable
     * backing fields. Includes this in a `remember(...)` key list to re-fire a
     * derivation when a profile update or private contact nickname edit lands
     * (e.g. the chat-list search filter must re-evaluate its visible title).
     */
    internal val profileRevisionForCompose: ProfilePresentationRevision
        get() = ProfilePresentationRevision(profileRevision, contactNicknameRevision)

    internal fun profileAccountRevisionForCompose(accountIdHex: String): ProfileAccountRevision {
        val normalized = accountIdHex.trim().lowercase(Locale.ROOT)
        return ProfileAccountRevision(
            epoch = profileAccountRevisionEpoch,
            account = profileAccountRevisions[normalized] ?: 0,
        )
    }

    private fun bumpProfileAccountRevision(accountIdHex: String) {
        val normalized = accountIdHex.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return
        profileAccountRevisionSequence += 1
        profileAccountRevisions[normalized] = profileAccountRevisionSequence
        profileAccountRevisionOrder.remove(normalized)
        profileAccountRevisionOrder.add(normalized)
        if (profileAccountRevisionOrder.size > MAX_PROFILE_PRESENTATION_CACHE_ENTRIES) {
            val evicted = profileAccountRevisionOrder.first()
            profileAccountRevisionOrder.remove(evicted)
            profileAccountRevisions.remove(evicted)
        }
    }

    private fun bumpAllProfileAccountRevisions() {
        profileAccountRevisionEpoch += 1
        profileAccountRevisionSequence = 0
        profileAccountRevisions.clear()
        profileAccountRevisionOrder.clear()
    }

    private val profilePresentations =
        ScopedCache<String, ProfilePresentation>(
            registry = accountScopedCaches,
            name = "profile-presentations",
            maxEntries = MAX_PROFILE_PRESENTATION_CACHE_ENTRIES,
            lock = profilePresentationLock,
        )
    private val notificationDisplayNameHints =
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
    private val longMessageCollapseState =
        LongMessageCollapseState(
            registry = accountScopedCaches,
            preferences = preferences,
            maxEntries = MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES,
        )
    private val hiddenMessageIdsByAccountGroup =
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
    private val attachmentDownloadIntents =
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
    private val shareInboundStager =
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
        ShareShortcutPublisher(appContext, avatarOverride = { account, item ->
            val peer =
                GroupProjector
                    .avatarAccount(item.group, item.presentationOtherMemberAccount, item.presentationMemberCount)
                    ?.takeUnless { GroupProjector.ownsGroupPicture(item) }
            peer?.let { contactAvatarOverride(account, it) } ?: (false to null)
        })
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

    private val profileScope =
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
    private val appLockTimestampWriteMutex = Mutex()
    private val inFlightAttachmentAcquisitions =
        InFlightAttachmentAcquisitions(mutationsScope, attachmentDownloadGate::promote)

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
            foregroundEligible = { appInForeground && !appLockScreenVisible },
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
    private val notificationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + scopeExceptionHandler)
    private val notificationCandidateReads = NotificationCandidateReadCoordinator(notificationScope, Dispatchers.IO)
    private val notificationFirstPostContentCoordinator =
        NotificationFirstPostContentCoordinator(notificationScope, notificationDispatcher, SystemClock::elapsedRealtime)
    private val notificationContentResolution by lazy {
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

        override suspend fun mediaKind(update: NotificationUpdateFfi): ReplyMediaKind =
            resolveNotificationMediaKind(
                update,
                ::notificationMessageRecord,
            )

        /** Projects only identities that can currently sign notification actions. */
        override fun signedInAccountIds(): Set<String> = accounts.signedInSigningAccountIds()
    }

    private val notificationAvatarCoordinator by lazy {
        NotificationAvatarCoordinator(
            appLocked = { appLockScreenVisible },
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
    private val notificationEnrichmentGate = Semaphore(NOTIFICATION_ENRICHMENT_FANOUT)
    internal val accountSession by lazy {
        AppAccountSessionCoordinator(
            mutationScope = mutationsScope,
            stateDispatcher = Dispatchers.Main.immediate,
            listenerTeardown = { runtimeListenerTeardownOwner },
            activation = AccountSessionActivation(),
        )
    }
    private val accountCatchUpCoordinator =
        AccountCatchUpCoordinator(notificationScope, localNotificationPresenter.catchUpWindow)
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

    private val accountSwitchHandoff get() = accountSession.handoff

    // Bumped whenever cross-account caches are cleared (switch / sign-out). An
    // in-flight profile refresh captures it at start and discards its result if
    // the epoch moved, so a job that resolves after a switch can't write the
    // old account's data back into the just-cleared caches.
    private val profileCacheLifetime = StalenessGuard()
    private val mediaUploadSessionLifetime = StalenessGuard()
    private val notificationJob = NotificationJobSlot()
    private val pushWakeCatchUpDrainJob = NotificationJobSlot()
    private val notificationPushWakeRecoveryCircuit = NotificationPushWakeRecoveryCircuit()

    @Volatile
    private var networkNotificationRecoverySuppressed = false
    private val notificationReceiverActive = MutableStateFlow(false)
    private val notificationReceiverRetryWake = MutableStateFlow(0L)

    // staleness-exempt: ordered notification-drain watermark emitted as data to a SharedFlow.
    private val notificationDrainSequence = AtomicLong(0)
    private val notificationRuntimeRecovery = StalenessGuard()
    private val notificationPostEpoch = StalenessGuard()
    private val notificationDrainSignals = MutableSharedFlow<Long>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Coalesces per-account unread refreshes across a notification burst so a
    // catch-up flood drains to one expensive (chat-list + per-group roster)
    // refresh per account per window instead of one per update (#729). Holds
    // only short-lived lifecycle state; the count still comes from the
    // suppression-preserving refreshAccountUnreadCount source of truth.
    private val unreadRefreshScheduler =
        UnreadRefreshScheduler(scope = notificationScope) { accountRef ->
            refreshAccountUnreadCount(accountRef)
        }

    @Volatile
    private var isForegroundCatchUpRunning = false

    // Single source of truth for notification suppression: whether an Activity
    // is on screen, plus the conversation that will be suppressed only while it
    // is foreground-visible. The active conversation is scoped to its account so
    // viewing a shared group under one account doesn't mute the other account's
    // alerts. Lifecycle transitions (foreground/background/task-removed) live
    // on the value type so the foreground gate and active-chat reset cannot
    // drift across the separate places that update them (issue #821).
    @Volatile
    private var suppression = NotificationSuppression()
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
            appLockScreenVisible = appLockScreenVisible,
            pendingProfileNpub = pendingProfileNpub,
            activeAccountRef = activeConversationAccountRef,
            activeGroupIdHex = activeConversationGroupIdHex,
            accountRef = accountRef,
            groupIdHex = groupIdHex,
        )

    private val profileRefreshGate = ProfileRefreshGate(PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS)
    private var chatsController: ChatsController? = null
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
    ) {
        accountRef ?: return
        setDraftForAccount(accountRef, groupIdHex, value)
    }

    private fun setDraftForAccount(
        accountRef: String,
        groupIdHex: String,
        value: TextFieldValue,
    ) {
        composerDraftExpansionBridge.setDraft(accountRef, groupIdHex, value)
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
        if (appLockScreenVisible || appUnlockEvaluationPending) {
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
        writeConversationDictationDraftWhenUnlocked(appLockScreenVisible, appUnlockEvaluationPending) {
            composerDraftExpansionBridge.writeDraftIfCurrent(accountRef, groupIdHex, expectedRevision, value)
        }

    /** Dictation conditionally empties only its unchanged origin; failed or unknown sends restore that exact text. */
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
                if (request.replyToMessageIdHex == null) {
                    sendDetachedDictationText(request)
                } else {
                    rejectDictationDispatchBeforeTransport(request)
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
        var durablyAccepted = false
        return try {
            durablyAccepted = sendConversationText(controller, request.payload, onAccepted = request.onPendingShown)
            durablyAccepted
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

    /** Refreshes draft summaries behind account, request, and local-fingerprint fences. */
    internal fun refreshDraftSummaries(accountRef: String) {
        val refresh = draftSummaryRefreshLifetime.advance()
        val expected = draftStore.captureSummaryRefresh(accountRef)
        mutationsScope.launch {
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
            nativePushFallback.invalidateAll()
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
                    pushWakeCatchUpDrainJob.isActive()
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
    ) {
        chatsController.applyLocalGroupUpdateForAccount(record, accountRef)
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

    /**
     * Confirmed chats the active account can forward a message into, recent
     * first. Empty when no chats controller is attached yet (the chat-list
     * stream hasn't bound) — the forward picker then shows its empty state.
     */
    fun forwardTargets(): List<ChatListItem> = chatsController?.forwardTargets().orEmpty()

    /** Account-wide forward targets beyond the active controller's window (#2618); null when none is attached. */
    internal suspend fun loadAccountWideForwardTargets(): AccountWideForwardTargets? {
        val controller = chatsController ?: return null
        return controller.loadAccountWideForwardTargets()
    }

    internal val forwardTargetsLoading: Boolean
        get() = chatsController?.isLoading == true

    internal val forwardTargetsError: ErrorPresentation?
        get() = chatsController?.error

    internal val forwardTargetMembersRevision: Long
        get() = chatsController?.memberSnapshotsRevision ?: 0L

    internal val forwardTargetsRevision: Long
        get() = chatsController?.forwardTargetsRevision ?: 0L

    internal fun requestForwardTargetMembers(groupIds: Iterable<String>) {
        chatsController?.requestMemberSnapshots(groupIds)
    }

    internal fun retryForwardTargets() {
        chatsController?.retryLoad()
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

    /**
     * Stage an inbound Android share for the explicitly chosen local account.
     *
     * The system-share picker can enumerate a non-active account, so resolving
     * through [activeAccountRef] here would silently move the draft and stream
     * ownership to whichever account happened to be active at commit time.
     * Return false when the chosen account is no longer a signed-in signing
     * account; callers keep the picker/recovery path visible in that case.
     */
    fun stageInboundShare(
        accountRef: String,
        targetGroupIds: List<String>,
        payload: SharePayload,
    ): Boolean {
        val target = validatedInboundShareTarget(accountRef, targetGroupIds) ?: return false
        shareInboundStager.stageToChats(
            context = appContext,
            accountIdHex = target.accountIdHex,
            groupIds = target.groupIds,
            payload = payload,
            draftAccountRef = accountRef,
        )
        return true
    }

    /**
     * Prepares provider MIME metadata on I/O for the automatic Direct Share
     * route, then applies draft/staging state on the calling Main coroutine.
     */
    suspend fun stageInboundShareForFirstFrame(
        accountRef: String,
        targetGroupIds: List<String>,
        payload: SharePayload,
        shouldCommit: () -> Boolean = { true },
    ): Boolean {
        val initialTarget = validatedInboundShareTarget(accountRef, targetGroupIds)
        if (initialTarget == null || !shouldCommit()) return false
        val prepared = withContext(Dispatchers.IO) { shareInboundStager.prepare(appContext, payload) }
        return dev.ipf.whitenoise.android.share.retainShareAtDestination(
            appContext,
            initialTarget.accountIdHex,
            initialTarget.groupIds,
            payload,
        ) { droppedCount ->
            if (!shouldCommit() || validatedInboundShareTarget(accountRef, targetGroupIds) != initialTarget) {
                false
            } else {
                shareInboundStager.stagePreparedToChats(
                    initialTarget.accountIdHex,
                    initialTarget.groupIds,
                    if (payload.importReady) prepared.copy(streamStaging = null) else prepared,
                    accountRef,
                )
                if (payload.importReady && payload.streamUris.isNotEmpty()) shareStaging.notifyTextStaged()
                if (droppedCount > 0) {
                    presentText(
                        AppText.Plain(
                            appContext.resources.getQuantityString(
                                R.plurals.toast_share_attachments_dropped,
                                droppedCount,
                                droppedCount,
                            ),
                        ),
                    )
                }
                true
            }
        }
    }

    /** Revalidates account ownership and de-duplicates recipient ids at the commit boundary. */
    private fun validatedInboundShareTarget(
        accountRef: String,
        targetGroupIds: List<String>,
    ): ValidatedInboundShareTarget? {
        val account =
            accounts.firstOrNull { summary ->
                summary.label == accountRef && summary.isSignedInSigningAccount()
            } ?: return null
        val validGroupIds = targetGroupIds.filter(String::isNotBlank).distinct()
        return validGroupIds
            .takeIf { it.isNotEmpty() }
            ?.let { ValidatedInboundShareTarget(account.accountIdHex, it) }
    }

    /** Account-bound destination resolved immediately before local staging. */
    private data class ValidatedInboundShareTarget(
        val accountIdHex: String,
        val groupIds: List<String>,
    )

    fun consumeInboundShareStreamsCapped(
        groupIdHex: String,
        existingMediaCount: Int,
        existingDocumentCount: Int,
        maxItems: Int = SHARE_STREAM_MAX_ITEMS,
    ): CappedShareStreamStaging? {
        val accountIdHex = activeAccount?.accountIdHex ?: return null
        return shareStaging.consumeCapped(
            accountIdHex = accountIdHex,
            groupIdHex = groupIdHex,
            existingMediaCount = existingMediaCount,
            existingDocumentCount = existingDocumentCount,
            maxItems = maxItems,
        )
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
        if (!available || appLockScreenVisible || isContactRefBeingCleared(account)) {
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
            !appLockScreenVisible &&
            !appUnlockEvaluationPending &&
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
     * Forward [text] into each of [targetGroupIds] as a fresh send.
     *
     * Each target is an independent [sendText] into that group, so the message
     * is re-encrypted under that group's own MLS state — there is no
     * cross-group key reuse and no source-group key material leaves the origin
     * conversation. The plain body is sent verbatim: it carries neither the
     * original sender's pubkey nor the source group id, so a forward never
     * leaks cross-group attribution (issue #390 privacy notes). The optional
     * receiver-visible "Forwarded" label is deliberately *not* embedded here —
     * the FFI text-send carries no source-free forward marker, so a
     * receiver-visible label would require either an engine change or a content
     * marker that crosses the group boundary; that is a separate product/privacy
     * decision tracked as a follow-up.
     *
     * Sends fan out on [launchMutation] so each MLS commit + Nostr publish
     * survives the picker sheet dismissing immediately after the user confirms.
     * Per-target failures are counted and surfaced in the result toast rather
     * than aborting the remaining targets — one unreachable group must not block
     * delivery to the others. Blank text and an empty target set are no-ops.
     */
    fun forwardText(
        targetGroupIds: List<String>,
        text: String,
    ) {
        val trimmed = text.trim()
        val targets = MessageProjector.normalizeForwardTargets(targetGroupIds)
        if (trimmed.isEmpty() || targets.isEmpty()) return
        val account = activeAccountRef?.takeIf { it.isNotBlank() } ?: return
        launchMutation {
            var failures = 0
            var firstFailure: Throwable? = null
            for (groupIdHex in targets) {
                val result =
                    runCatchingCancellable {
                        withGroupCommitLock(account, groupIdHex) {
                            marmotIo(MarmotTraceSection.TEXT_SEND) { sendText(account, groupIdHex, trimmed) }
                        }
                    }
                result.onFailure {
                    failures += 1
                    if (firstFailure == null) firstFailure = it
                }
            }
            val delivered = targets.size - failures
            when {
                failures == 0 ->
                    presentTransient(AppText.Resource(R.string.toast_forwarded_to_chats, listOf(delivered)))
                delivered == 0 ->
                    presentFailure(R.string.toast_forward_failed, "MESSAGE_FORWARD", requireNotNull(firstFailure))
                else -> {
                    val partialTitle =
                        AppText.Resource(
                            R.string.toast_forwarded_partial,
                            listOf("$delivered/${targets.size}"),
                        )
                    presentFailure(
                        title = partialTitle,
                        operationCode = "MESSAGE_FORWARD_PARTIAL",
                        throwable = requireNotNull(firstFailure),
                    )
                }
            }
        }
    }

    /**
     * Forward multiple text messages to each target chat in their original
     * timeline order. One target's batch holds the group commit lock for the
     * complete sequence so another mutation cannot interleave between messages.
     */
    @Suppress("CyclomaticComplexMethod") // Fan-out accounting keeps partial-success reporting in one transaction.
    fun forwardTexts(
        targetGroupIds: List<String>,
        texts: List<String>,
    ) {
        val bodies = MessageProjector.validatedForwardTextBodies(texts)
        val targets = MessageProjector.normalizeForwardTargets(targetGroupIds)
        if (bodies.isEmpty() || targets.isEmpty()) return
        val account = activeAccountRef?.takeIf { it.isNotBlank() } ?: return
        launchMutation {
            var completeTargets = 0
            var successfulSends = 0
            var firstFailure: Throwable? = null
            for (groupIdHex in targets) {
                var targetComplete = true
                try {
                    withGroupCommitLock(account, groupIdHex) {
                        for (body in bodies) {
                            try {
                                marmotIo(MarmotTraceSection.TEXT_SEND) { sendText(account, groupIdHex, body) }
                                successfulSends += 1
                            } catch (throwable: Throwable) {
                                if (throwable is CancellationException) throw throwable
                                if (firstFailure == null) firstFailure = throwable
                                targetComplete = false
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    if (firstFailure == null) firstFailure = throwable
                    targetComplete = false
                }
                if (targetComplete) completeTargets += 1
            }
            when {
                completeTargets == targets.size ->
                    presentTransient(AppText.Resource(R.string.toast_forwarded_to_chats, listOf(completeTargets)))
                successfulSends == 0 ->
                    presentFailure(
                        R.string.toast_forward_batch_failed,
                        "MESSAGE_FORWARD_BATCH",
                        requireNotNull(firstFailure),
                    )
                else ->
                    presentFailure(
                        R.string.toast_forwarded_batch_partial,
                        "MESSAGE_FORWARD_BATCH_PARTIAL",
                        requireNotNull(firstFailure),
                    )
            }
        }
    }

    fun profileAddableGroups(accountIdHex: String): List<ChatListItem> =
        chatsController?.profileAddableGroups(accountIdHex, activeAccount?.accountIdHex).orEmpty()

    internal val profileGroupPickerRevision: Long
        get() = chatsController?.memberSnapshotsRevision ?: 0L

    internal val groupPresentationChatsController: ChatsController?
        get() = chatsController

    /** Add to Groups candidates for [accountIdHex], with chats beyond the window from [accountWide]. */
    internal fun profileAddableGroupsState(
        accountIdHex: String,
        accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
    ): ProfileGroupPickerState =
        chatsController?.profileAddableGroupsState(accountIdHex, activeAccount?.accountIdHex, accountWide)
            ?: ProfileGroupPickerState.empty()

    /** Promote candidates for [accountIdHex], with chats beyond the window from [accountWide]. */
    internal fun profilePromotableGroupsState(
        accountIdHex: String,
        accountWide: ProfileAccountWideGroups = ProfileAccountWideGroups(),
    ): ProfileGroupPickerState =
        chatsController?.profilePromotableGroupsState(accountIdHex, activeAccount?.accountIdHex, accountWide)
            ?: ProfileGroupPickerState.empty()

    /** A non-started administration controller uses the existing authoritative mutation contract.
     * It owns no timeline subscriptions; accepted mutations are retained by launchMutation.
     */
    internal suspend fun profileMembershipController(
        account: String,
        item: ChatListItem,
    ): ConversationController? {
        if (activeAccountRef != account || item.isDm()) return null
        val controller =
            ConversationController(
                appState = this,
                initialGroup = item.group,
                accountRefOverride = account,
            )
        var retained = false
        return try {
            runCatchingCancellable { controller.refreshManagementState() }.getOrThrow()
            controller.takeIf { activeAccountRef == account && it.managementState != null }?.also { retained = true }
        } finally {
            if (!retained) controller.onCleared()
        }
    }

    /** Makes [targetRef] an admin of [groupIdHex], asking first if MDK holds the change back. True once committed. */
    suspend fun promoteProfileInGroup(
        targetRef: String,
        groupIdHex: String,
    ): Boolean {
        val target = targetRef.trim()
        val groupId = groupIdHex.trim()
        val account = activeAccountRef
        if (target.isEmpty() || groupId.isEmpty() || account == null) return false
        return runCatchingCancellable {
            // The lock is released only while the user decides, and held again for the acknowledgement and retry.
            withGroupCommitLock(account, groupId) {
                runGroupChange(
                    account,
                    groupId,
                    actionLabel = R.string.make_admin,
                    affectedMemberIds = listOf(target),
                ) {
                    val result =
                        marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                            promoteAdminDetailed(account, groupId, target)
                        }
                    chatsController?.applyProfileGroupDetails(account, result.details)
                }
            }
            presentConversationTransient(
                accountRef = account,
                groupIdHex = groupId,
                titleRes = R.string.toast_admin_added,
            )
            true
        }.onFailure { error ->
            presentFailure(R.string.toast_couldnt_update_admin, "PROFILE_GROUP_ADMIN_UPDATE", error)
        }.getOrDefault(false)
    }

    /**
     * Add one viewed profile to one or more selected groups. The profile sheet
     * does the eligibility filtering (admin-only, not already a member); this
     * method keeps the commit-producing calls serialized per group and surfaces
     * a single result toast after the fan-out completes. Returns true only when
     * every attempted group accepted the invite, so partial failures can keep the
     * picker open with the failed groups still selected for retry. Choosing Wait on
     * one group's change question skips only that group and is reported as part of
     * the result; cancellation still stops the fan-out.
     */
    @Suppress("LongMethod", "ReturnCount") // The fan-out and aggregate result share one failure accumulator.
    suspend fun inviteProfileToGroups(
        targetRef: String,
        targetGroupIds: List<String>,
    ): Boolean {
        val ref = targetRef.trim().takeIf { it.isNotEmpty() } ?: return false
        val targets =
            targetGroupIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }
        if (targets.isEmpty()) return false
        val account = activeAccountRef ?: return false
        val runtime = runtimeGeneration
        val stillCurrent = { activeAccountRef == account && runtimeGeneration == runtime }
        var failures = 0
        var waited = 0
        var firstFailure: AppText? = null
        var firstFailureThrowable: Throwable? = null
        for (groupIdHex in targets) {
            // A switch mid-fan-out ends it: the rest belonged to the previous account.
            if (!stillCurrent()) return false
            val attempt = inviteProfileToGroup(account, groupIdHex, ref)
            if (attempt == null) {
                waited += 1
                continue
            }
            attempt.onFailure { error ->
                failures += 1
                if (firstFailure == null) {
                    firstFailureThrowable = error
                    firstFailure = profileGroupInviteFailureDetail(error, ref)
                }
            }
        }
        if (!stillCurrent()) return false
        val outcome =
            ProfileGroupInviteOutcome(
                attempted = targets.size,
                failures = failures,
                firstFailure = firstFailure,
                waited = waited,
            )
        presentProfileGroupInvite(outcome, firstFailureThrowable)
        return outcome.completedSuccessfully
    }

    /** Shows the one toast that sums up a profile fan-out, with [failure]'s report when it is copyable. */
    private fun presentProfileGroupInvite(
        outcome: ProfileGroupInviteOutcome,
        failure: Throwable?,
    ) {
        profileGroupInviteToast(outcome)?.let { toast ->
            if (toast.copyable && failure != null) {
                presentFailure(
                    titleRes = toast.messageRes,
                    operationCode = "PROFILE_GROUP_INVITE",
                    throwable = failure,
                    detail = toast.detail ?: AppText.Resource(R.string.error_try_again),
                )
            } else if (!toast.copyable) {
                presentTransient(toast.messageRes, toast.detail)
            } else if (toast.detail == null) {
                present(toast.messageRes, copyable = toast.copyable)
            } else {
                present(toast.messageRes, toast.detail, copyable = toast.copyable)
            }
        }
    }

    /**
     * Invites [ref] to one group of a profile fan-out. Returns null when the user chose to wait on that group's
     * change question, so only that group stays uninvited; cancellation still propagates and stops the fan-out.
     */
    private suspend fun inviteProfileToGroup(
        account: String,
        groupIdHex: String,
        ref: String,
    ): Result<Unit>? =
        try {
            runCatchingCancellable {
                withGroupCommitLock(account, groupIdHex) {
                    runGroupChange(
                        account,
                        groupIdHex,
                        invite = true,
                        actionLabel = R.string.group_action_add_members,
                        affectedMemberIds = listOf(ref),
                    ) {
                        val result =
                            marmotIo(MarmotTraceSection.INVITE_MEMBERS) {
                                inviteMembersDetailed(account, groupIdHex, listOf(ref))
                            }
                        chatsController?.applyProfileGroupDetails(account, result.details)
                    }
                }
            }.map { }
        } catch (_: GroupChangeDeclinedException) {
            null
        }

    /** The detail the fan-out's toast gives for its first failed group, naming [ref] when it is about them. */
    private fun profileGroupInviteFailureDetail(
        error: Throwable,
        ref: String,
    ): AppText =
        if (isDuplicateSignatureKeyError(error.readableMessage())) {
            AppText.Resource(R.string.toast_couldnt_add_member_duplicate_detail, listOf(displayName(ref)))
        } else {
            inviteFailureDetail(
                error,
                ::displayName,
                if (error is MarmotKitException.InvalidKeyPackageEvent) displayName(ref) else null,
            )
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
     * Drive Marmot's per-account catch-up so every signed-in account on this
     * device processes the events its worker has pending — most importantly
     * the MLS commits / kind-1210 group-system rows that peers (including the
     * device's *other* local accounts) published while it was inactive.
     *
     * The engine owns one SQLite store per account-device identity, so an
     * inactive account never sees a sibling account's group rename, avatar
     * change, or membership commit until its own worker ingests that event.
     * `catchUpAccounts` pumps all running workers, so calling it before we
     * read an account's projection makes that account's source-of-truth store
     * current first — rather than caching the change Android-side (which the
     * repo's source-of-truth rule forbids). See issue #252 (group rename not
     * propagating to a second same-device account) and the adjacent
     * convergence cases #107 / #116 / #151.
     *
     * Best-effort: catch-up is a relay round-trip that can be slow or fail
     * offline, and a read surface must still render its last-known projection
     * in that case. Failures are swallowed (cancellation re-thrown) so a
     * caller can always `await` this without it becoming a hard gate.
     */
    suspend fun catchUpAccounts() {
        launchAccountCatchUp(
            mustStartAfter = null,
            trigger = PerformanceTrigger.EXPLICIT,
        ).await()
    }

    /**
     * Starts a process-owned catch-up after a chat list has rendered its local
     * snapshot. A blocked relay call must not block that controller's live
     * consumers. Callers for the same account, runtime, and network share one
     * in-flight result; a newer identity waits instead of overlapping native work.
     */
    internal fun launchCatchUpAccounts(): Deferred<AccountCatchUpResult> =
        launchAccountCatchUp(
            mustStartAfter = null,
            trigger = PerformanceTrigger.CHAT_LIST_READINESS,
        )

    /** Builds the current catch-up identity and serializes its native work process-wide. */
    private fun launchAccountCatchUp(
        mustStartAfter: Long?,
        trigger: PerformanceTrigger,
        publishReadiness: Boolean = true,
    ): Deferred<AccountCatchUpResult> =
        AccountCatchUpKey(
            accountRef = activeAccountRef,
            runtimeGeneration = runtimeGeneration,
            networkGeneration = connectivitySignalOwner.captureNetworkGeneration(),
        ).let { key ->
            val catchUp: suspend () -> Boolean = {
                performAccountCatchUp(key, trigger).also { succeeded ->
                    if (succeeded && publishReadiness) recordStartupRelayCatchUpReady()
                }
            }
            if (mustStartAfter == null) {
                accountCatchUpCoordinator.launch(key, catchUp)
            } else {
                accountCatchUpCoordinator.launchAfter(mustStartAfter, key, catchUp)
            }
        }

    /** Checks queued work again at native admission and settlement, including destructive teardown. */
    private fun isCatchUpKeyCurrent(key: AccountCatchUpKey): Boolean =
        activeAccountRef == key.accountRef &&
            runtimeGeneration == key.runtimeGeneration &&
            connectivitySignalOwner.isNetworkGenerationCurrent(key.networkGeneration) &&
            !networkNotificationRecoverySuppressed

    /** Charges only executed native work; every owner shares this durable failure budget. */
    private suspend fun performAccountCatchUp(
        key: AccountCatchUpKey,
        trigger: PerformanceTrigger,
    ): Boolean =
        if (!isCatchUpKeyCurrent(key)) {
            false
        } else {
            val admission =
                observePushState { pushWakeAttemptBudget().reserve() }
                    ?: if (trigger == PerformanceTrigger.PUSH_WAKE) {
                        PushWakeAdmission.Rejected
                    } else {
                        // Ordinary foreground/connectivity work is independently bounded
                        // and cannot settle an unknown wake.
                        PushWakeAdmission.Admitted(claim = null)
                    }
            when (admission) {
                PushWakeAdmission.Rejected -> false
                is PushWakeAdmission.Admitted -> {
                    if (isCatchUpKeyCurrent(key)) {
                        performAdmittedAccountCatchUp(key, trigger, admission.claim)
                    } else {
                        admission.claim?.let { reserved ->
                            observePushState { pushWakeAttemptBudget().release(reserved) }
                        }
                        false
                    }
                }
            }
        }

    /** Runs native catch-up only after admission and settles the same durable reservation. */
    private suspend fun performAdmittedAccountCatchUp(
        key: AccountCatchUpKey,
        trigger: PerformanceTrigger,
        claim: PushWakeAttemptClaim?,
    ): Boolean {
        val nativeSucceeded = instrumentedCatchUpAccounts(trigger)
        var settlementSucceeded = nativeSucceeded && isCatchUpKeyCurrent(key)
        if (claim != null) {
            settlementSucceeded =
                observePushState {
                    pushWakeAttemptBudget().settle(nativeSucceeded) { isCatchUpKeyCurrent(key) }
                } == true
        }
        val succeeded = settlementSucceeded && isCatchUpKeyCurrent(key)
        if (claim != null) {
            PushWakeDiagnostics.event(if (succeeded) PushWakeEvent.AttemptSucceeded else PushWakeEvent.AttemptFailed)
        }
        return succeeded
    }

    /** Runs catch-up work fresh enough to acknowledge the observed push-wake marker. */
    private suspend fun catchUpAfterObservedPushWake(
        pendingGeneration: Long,
        trigger: PerformanceTrigger,
    ): AccountCatchUpResult {
        if (pendingGeneration == 0L) {
            return launchAccountCatchUp(mustStartAfter = null, trigger = trigger).await()
        }
        val observedStartSequence = accountCatchUpCoordinator.captureStartSequence()
        val result =
            runCatchUpAfterTrigger(
                observedStartSequence = observedStartSequence,
                launchAfter = { sequence ->
                    launchAccountCatchUp(
                        mustStartAfter = sequence,
                        trigger = trigger,
                        publishReadiness = false,
                    )
                },
                onSucceeded = { result ->
                    observePushState {
                        result.key?.let { key -> acknowledgePendingPushWakeCatchUp(pendingGeneration, key) } == true
                    } == true
                },
            )
        if (result.succeeded) recordStartupRelayCatchUpReady()
        return result
    }

    /** Measures only native catch-up work admitted by the single-flight coordinator. */
    private suspend fun instrumentedCatchUpAccounts(trigger: PerformanceTrigger): Boolean {
        val startedAtMs = SystemClock.elapsedRealtime()
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.SYNC_CATCH_UP, trigger)
        PerformanceDiagnostics.record(
            trace = trace,
            phase = PerformancePhase.ACCOUNT_CATCH_UP_START,
            elapsedMs = 0L,
            result = PerformanceResult.PENDING,
            layer = PerformanceLayer.MDK,
        )
        val succeeded = RecoveryTrace.catchUp(trigger) { catchUpAccountsBestEffort() }
        val elapsedMs = (SystemClock.elapsedRealtime() - startedAtMs).coerceAtLeast(0L)
        PerformanceDiagnostics.record(
            trace = trace,
            phase =
                if (succeeded) {
                    PerformancePhase.ACCOUNT_CATCH_UP_READY
                } else {
                    PerformancePhase.ACCOUNT_CATCH_UP_RETRY
                },
            elapsedMs = elapsedMs,
            durationMs = elapsedMs,
            result = if (succeeded) PerformanceResult.SUCCESS else PerformanceResult.FAILURE,
            layer = PerformanceLayer.MDK,
        )
        return succeeded
    }

    private suspend fun catchUpAccountsBestEffort(): Boolean =
        runCatchingCancellable { marmotIo(MarmotTraceSection.CATCH_UP) { catchUpAccounts() } }
            .onFailure {
                appStateDebug(it) { "catchUpAccounts failed: ${it.readableMessage()}" }
            }.isSuccess

    /**
     * Best-effort account catch-up when the app returns to the foreground.
     * Mirrors iOS `catchUpAfterForegroundActivation`: pumps every running worker
     * so relay-ingested commits and projections converge without requiring an
     * account switch or process restart. Failures are swallowed; cancellation
     * propagates.
     */
    suspend fun catchUpAfterForegroundActivation() {
        if (
            !ForegroundCatchUpPolicy.shouldCatchUp(
                appPhase = phase,
                isCatchUpRunning = isForegroundCatchUpRunning,
                appInForeground = appInForeground,
            )
        ) {
            return
        }
        admitPushWakeEpisodeFromLifecycle()
        val pendingGeneration =
            withContext(pushWakeStorageDispatcher) {
                observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
            } ?: 0L // Unknown storage must not acknowledge a saved generation.
        isForegroundCatchUpRunning = true
        try {
            catchUpAfterObservedPushWake(
                pendingGeneration = pendingGeneration,
                trigger = PerformanceTrigger.FOREGROUND,
            )
        } finally {
            isForegroundCatchUpRunning = false
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
     * Chooses and owns exactly one network path before any native or legacy
     * acquisition starts. A later explicit tap joins an automatic owner (and
     * promotes its gate priority) instead of starting MarmotKit beside the
     * already-running legacy download.
     */
    internal fun memoizedAttachmentAcquisition(
        cacheKey: String,
        request: AttachmentTransferRequest,
        priority: AttachmentDownloadPriority,
        block: suspend CoroutineScope.() -> AttachmentAcquisitionOutcome,
    ): Deferred<AttachmentAcquisitionOutcome> =
        inFlightAttachmentAcquisitions.acquire(cacheKey, priority) {
            val owner = this
            val queueAttempt = beginHostPerformance(HostPerformanceOperationFfi.MEDIA_QUEUE_WAIT)
            try {
                attachmentDownloadGate.withPermit(cacheKey, request.accountRef, priority) {
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
        attachmentDownloadIntents.clearSuppressedAutomatic()
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
            localNotificationSettings = null
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
                    notificationDeliveryModeMutex.withLock {
                        nativePushSyncMutex.withLock {
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
            drainPendingNativePushRegistrationSyncIfNeeded()
            return drainPendingPushWakeCatchUpIfNeeded()
        }
        localNotificationPresenter.ensureChannels()
        refreshLocalNotificationPermission()
        if (!awaitNotificationReceiverForStartupWithin(notificationReceiverTimeoutMillis())) throw receiverUnavailable()
        if (accounts.isEmpty()) refreshAccounts()
        refreshLocalNotificationSettings()
        drainPendingNativePushRegistrationSyncIfNeeded()
        return drainPendingPushWakeCatchUpIfNeeded()
    }

    private val pushWakeAttemptMutex = kotlinx.coroutines.sync.Mutex()
    private var acceptedPushWakeJob: Job? = null
    private var acceptedPushWakeOwner: Any? = null
    private var pushWakeServiceOwner: Any? = null

    /** Fences teardown while preserving existing screen-lock notification redaction. */
    internal fun pushWakeRecoveryAllowed(): Boolean = !networkNotificationRecoverySuppressed

    /** Keeps storage failure in recovery bookkeeping from interrupting unlock or connectivity handling. */
    private suspend fun admitPushWakeEpisodeFromLifecycle() {
        val admitted =
            withContext(pushWakeStorageDispatcher) {
                observePushState { pushTokenStore.admitPushWakeEpisode(pushWakeNowMs()) }
            }
        if (admitted != true) {
            PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
        }
    }

    /** Defers passive push work when encrypted state is unavailable without inventing an empty queue. */
    private inline fun <T> observePushState(read: () -> T): T? =
        try {
            read()
        } catch (_: PushTokenStorageException) {
            android.util.Log.w("PushTokenStorage", "lifecycle_work_deferred")
            null
        }

    /** Acknowledges the concrete foreground-service instance that currently owns background runtime. */
    internal fun acknowledgePushWakeServiceOwner(owner: Any) {
        assertMainThread { "acknowledgePushWakeServiceOwner" }
        pushWakeServiceOwner = owner
    }

    /** Releases only the matching service lease and hands unresolved foreground work to durable scheduling. */
    internal fun releasePushWakeServiceOwner(owner: Any) {
        assertMainThread { "releasePushWakeServiceOwner" }
        if (pushWakeServiceOwner !== owner) return
        pushWakeServiceOwner = null
        transferUnownedPushWakeRecovery()
    }

    /** Accepts a process-owned drain only while an Activity or acknowledged service owns its lifetime. */
    internal fun acceptPushWakeRecovery(): Boolean {
        assertMainThread { "acceptPushWakeRecovery" }
        val ownsRuntime = bootstrapCompleted && (appInForeground || pushWakeServiceOwner != null)
        return when {
            !pushWakeRecoveryAllowed() || !ownsRuntime -> false
            acceptedPushWakeJob?.isActive == true -> acceptedPushWakeOwner != null
            else -> {
                val owner = Any()
                acceptedPushWakeOwner = owner
                acceptedPushWakeJob =
                    notificationScope.launch {
                        try {
                            runPushWakeRecoveryAttempt()
                        } finally {
                            if (acceptedPushWakeOwner === owner) {
                                acceptedPushWakeOwner = null
                                withContext(kotlinx.coroutines.NonCancellable + pushWakeStorageDispatcher) {
                                    schedulePushWakeRecovery()
                                }
                            }
                        }
                    }
                true
            }
        }
    }

    /** Revokes process ownership at its lifecycle boundary and immediately confirms a durable successor. */
    private fun transferUnownedPushWakeRecovery() {
        if (appInForeground || pushWakeServiceOwner != null) return
        acceptedPushWakeOwner = null
        acceptedPushWakeJob?.cancel()
        acceptedPushWakeJob = null
        notificationScope.launch(pushWakeStorageDispatcher) { schedulePushWakeRecovery() }
    }

    /**
     * Shares service/worker ownership; cancellation leaves native settlement with AccountCatchUpCoordinator.
     * Native/platform bootstrap failures preserve the shared retry obligation.
     */
    @Suppress("TooGenericExceptionCaught")
    internal suspend fun runPushWakeRecoveryAttempt() {
        pushWakeAttemptMutex.lock()
        try {
            val recoveryState =
                withContext(pushWakeStorageDispatcher) {
                    Triple(
                        pushTokenStore.pushWakeCatchUpPending(),
                        pushTokenStore.pushWakeAttempts(),
                        pushTokenStore.pushWakeRetryDelay(pushWakeNowMs()),
                    )
                }
            if (!pushWakeRecoveryAllowed() || !recoveryState.first) return
            val generation = notificationRuntimeRecoveryGeneration()
            val attempts = recoveryState.second
            if (attempts >= PUSH_WAKE_MAX_ATTEMPTS || recoveryState.third > 0L) return
            PushWakeDiagnostics.event(PushWakeEvent.AttemptStarted)
            try {
                ensureNotificationRuntimeStartedAndAwaitPushDrain()
                val pending = withContext(pushWakeStorageDispatcher) { pushTokenStore.pushWakeCatchUpPending() }
                if (notificationRuntimeRecoveryAllowed(generation) && !pending) {
                    PushWakeDiagnostics.complete()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Receiver/bootstrap failure must also consume this same finite episode.
                if (notificationRuntimeRecoveryAllowed(generation)) {
                    withContext(pushWakeStorageDispatcher) {
                        if (pushTokenStore.pushWakeAttempts() == attempts) {
                            val reclaimed = pushTokenStore.claimPushWakeAttempt(pushWakeNowMs())
                            if (reclaimed == null || !pushTokenStore.deferPushWakeRetry(pushWakeNowMs())) {
                                PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
                            }
                        }
                    }
                }
                PushWakeDiagnostics.event(PushWakeEvent.AttemptFailed)
                throw error
            }
        } finally {
            pushWakeAttemptMutex.unlock()
        }
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
                drainPendingNativePushRegistrationSyncIfNeeded()
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

    /** Retries a persisted token-registration obligation when notification startup reaches a usable runtime. */
    private suspend fun drainPendingNativePushRegistrationSyncIfNeeded() {
        val pending =
            withContext(pushWakeStorageDispatcher) {
                observePushState {
                    pushTokenStore.nativePushRegistrationSyncPending() || pushTokenStore.lastToken() != null
                }
            } ?: return
        if (pending) {
            syncNativePushRegistrationIfEnabled()
        }
    }

    /** Returns fetch success, acknowledging only the matching durable wake after fresh work. */
    private suspend fun drainPendingPushWakeCatchUpIfNeeded(): Boolean {
        val pendingGeneration =
            withContext(pushWakeStorageDispatcher) {
                observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
            } ?: return false
        return pendingGeneration == 0L ||
            catchUpAfterObservedPushWake(
                pendingGeneration = pendingGeneration,
                trigger = PerformanceTrigger.PUSH_WAKE,
            ).succeeded
    }

    /**
     * Clears only work whose recovery identity stays current across the durable acknowledgement commit.
     * Cancellation is deferred across this bounded transaction so a committed clear cannot skip marker restoration.
     */
    private suspend fun acknowledgePendingPushWakeCatchUp(
        pendingGeneration: Long,
        key: AccountCatchUpKey,
    ): Boolean {
        if (pendingGeneration == 0L || !isCatchUpKeyCurrent(key)) return false
        val acknowledged =
            withContext(NonCancellable) {
                val cleared =
                    withContext(pushWakeStorageDispatcher) {
                        pushTokenStore.clearPendingPushWakeCatchUp(pendingGeneration)
                    }
                if (!isCatchUpKeyCurrent(key)) {
                    if (cleared) {
                        val restored =
                            withContext(pushWakeStorageDispatcher) {
                                pushTokenStore.recordPendingPushWakeCatchUp()
                            }
                        if (!restored) PushWakeDiagnostics.event(PushWakeEvent.PersistenceFailed)
                    }
                    false
                } else {
                    if (cleared) {
                        appStateDebug { "pending push wake catch-up drained" }
                    } else {
                        appStateDebug { "newer pending push wake catch-up remains queued" }
                    }
                    true
                }
            }
        currentCoroutineContext().ensureActive()
        return acknowledged
    }

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
        localNotificationSettings = null
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
        if (injected != null) return injected.login(nsec, MarmotClient.accountRelays, relays)
        return marmotIo { login(nsec, MarmotClient.accountRelays, relays, relays) }
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
                MarmotClient.accountRelays,
                relays,
                acknowledgePossibleKeyPackageOrphan = true,
            )
        }
        return marmotIo {
            loginRecoveringIncompleteSetup(
                nsec,
                MarmotClient.accountRelays,
                relays,
                acknowledgePossibleKeyPackageOrphan = true,
                inboxRelays = relays,
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
                        MarmotClient.accountRelays,
                        MarmotClient.bootstrapRelays,
                        MarmotClient.bootstrapRelays,
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

    /** Retained count for reconciliation and diagnostics, even while freshness is unknown. */
    fun unreadCountForAccount(accountRef: String): ULong = accountUnreadStore.values[accountRef]?.unreadCount ?: 0uL

    /** Count safe to render as current. Unknown retained values intentionally present as no badge. */
    fun confirmedUnreadCountForAccount(accountRef: String): ULong = accountUnreadStore.values[accountRef].confirmedUnreadCount()

    /**
     * Whether [accountRef]'s avatar should light the unread dot, from that
     * account's own per-account aggregate (#805). Shared by the active avatar,
     * the secondary top-bar avatars, and the account switcher so no avatar can
     * light for another account's unread.
     */
    fun accountShowsUnreadDot(accountRef: String?): Boolean {
        val ref = accountRef?.takeIf { it.isNotBlank() } ?: return false
        return accountUnreadStore.values[ref].showsUnreadDot()
    }

    internal fun updateAccountUnreadCount(
        accountRef: String?,
        unreadCount: ULong,
    ) = accountUnreadStore.updateCount(accountRef, unreadCount)

    internal fun updateAccountUnreadProjection(
        accountRef: String?,
        unreadCount: ULong,
        hasManualUnread: Boolean,
    ) = accountUnreadStore.updateProjection(accountRef, unreadCount, hasManualUnread)

    /**
     * Accounts with at least one manually-marked-unread chat. A boolean
     * sidecar to [accountUnreadCounts]: the numeric map stays a real message
     * count (it feeds literal count badges), while this set only lights dots.
     */
    internal val accountManualUnreadRefs: Set<String>
        get() = accountUnreadStore.manualUnreadRefs

    internal fun updateAccountManualUnread(
        accountRef: String?,
        hasManualUnread: Boolean,
    ) = accountUnreadStore.updateManualUnread(accountRef, hasManualUnread)

    @Suppress(
        "ReturnCount", // Empty, stale-empty, and stale-in-flight snapshots each stop before publishing.
        "CyclomaticComplexMethod", // Raw and roster-aware paths deliberately share one guarded publication point.
    )
    /** Refreshes unread projections only while the captured account list remains current. */
    private suspend fun refreshAccountUnreadCounts(
        accountSummaries: List<AccountSummaryFfi> = accounts,
        loadMemberRosters: Boolean = true,
        stillCurrent: () -> Boolean = { true },
    ) {
        if (!stillCurrent()) return
        val accountListTokenAtStart = accountListLifetime.capture()
        val refreshGeneration = accountUnreadStore.beginRefresh()

        fun refreshIsCurrent(): Boolean =
            stillCurrent() &&
                accountListLifetime.isCurrent(accountListTokenAtStart) &&
                accountUnreadStore.isRefreshCurrent(refreshGeneration)

        val signingAccounts = accountSummaries.filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
        if (signingAccounts.isEmpty()) {
            if (!refreshIsCurrent()) return
            accountUnreadStore.publishRefresh(
                previous = accountUnreadStore.snapshot(),
                refreshed = emptyMap(),
                generation = refreshGeneration,
            )
            return
        }

        val previous = accountUnreadStore.snapshot()
        val previousValues = previous.mapValues { (_, versioned) -> versioned.value }
        val rawCountsByHex =
            runCatchingCancellable {
                accountAttentionCounts()
            }.onFailure { appStateDebug { "account unread summary refresh failed" } }
                .getOrNull()
        if (!refreshIsCurrent()) return
        val rawValues = rawAccountUnreadValues(signingAccounts, rawCountsByHex, previousValues)
        // Publish best-effort freshness before slower exact row folds: present
        // summary rows are current, while omitted/failed rows immediately stop
        // presenting their retained count as confirmed.
        val interimPublication =
            accountUnreadStore.publishRefresh(
                previous = previous,
                refreshed = rawValues,
                generation = refreshGeneration,
            )
        if (!loadMemberRosters) return
        if (!refreshIsCurrent()) return
        val foldBaselineValues = interimPublication.values.mapValues { (_, versioned) -> versioned.value }
        val accountGate = Semaphore(ACCOUNT_UNREAD_ACCOUNT_FANOUT)
        // Share one roster gate across the whole bulk refresh so member FFI
        // fan-out stays bounded across all signed-in accounts, not per account.
        val memberGate = Semaphore(ACCOUNT_UNREAD_MEMBER_FANOUT)
        val refreshedPairs =
            coroutineScope {
                signingAccounts
                    .map { summary ->
                        async {
                            accountGate.withPermit {
                                refreshAccountUnreadFold(
                                    summary = summary,
                                    rawCount = rawCountsByHex?.get(summary.accountIdHex),
                                    previous = foldBaselineValues[summary.label],
                                    memberGate = memberGate,
                                )
                            }
                        }
                    }.awaitAll()
            }
        // A sign-out/account refresh may have completed while the cold-start
        // fold was suspended. Discard that obsolete result rather than
        // resurrecting counts for an account no longer in the authoritative
        // list; the newer lifecycle operation owns convergence.
        if (!refreshIsCurrent()) return
        // Our own interim freshness writes are valid baselines for the exact
        // fold. Values preserved because a controller/notification writer won
        // the race keep their original baseline, so this bulk pass cannot
        // overwrite newer authoritative evidence.
        accountUnreadStore.publishRefresh(
            previous = accountUnreadExactBaseline(previous, interimPublication),
            refreshed = refreshedPairs.associate { it.accountRef to it.value },
            generation = refreshGeneration,
        )
    }

    private suspend fun refreshAccountUnreadFold(
        summary: AccountSummaryFfi,
        rawCount: ULong?,
        previous: AccountUnreadValue?,
        memberGate: Semaphore,
    ): AccountUnreadFoldResult {
        val cheapZero =
            rawCount == 0uL &&
                previous?.hasManualUnread == false
        // The cheap engine total can't see the client's manual-unread flag, so
        // an account believed to be manually flagged still takes the row fold.
        if (!cheapZero) {
            refreshEffectiveAccountUnreadCount(summary, memberGate)?.let { return it }
        }
        return AccountUnreadFoldResult(
            accountRef = summary.label,
            value =
                accountUnreadValueAfterRefresh(
                    rawCount = rawCount,
                    previous = previous,
                    exactUnreadCount = if (cheapZero) 0uL else null,
                    exactHasManualUnread = if (cheapZero) false else null,
                ),
        )
    }

    /**
     * Reads one account's durable chat-list rows and folds them with loaded
     * member rosters so removed/left groups no longer contribute frozen unread
     * totals to [accountUnreadCounts]. This intentionally uses the same
     * removed-group semantics as the active chat-list projection (#625), but is
     * scoped to [summary.accountIdHex] instead of the currently-active account so
     * cross-account indicators stay honest for background accounts too (#662).
     */
    private suspend fun refreshEffectiveAccountUnreadCount(
        summary: AccountSummaryFfi,
        memberGate: Semaphore = Semaphore(ACCOUNT_UNREAD_MEMBER_FANOUT),
    ): AccountUnreadFoldResult? {
        val ref = summary.label.takeIf { it.isNotBlank() } ?: return null
        return runCatchingCancellable {
            marmotIo {
                val rows = chatList(ref, includeArchived = true)
                val membersByGroupId =
                    loadUnreadMemberRosters(
                        rows = rows,
                        gate = memberGate,
                        onFailure = { _, _ -> appStateDebug { "account unread member refresh failed" } },
                    ) { groupIdHex ->
                        groupMembers(ref, groupIdHex)
                    }
                AccountUnreadFoldResult(
                    accountRef = ref,
                    value =
                        AccountUnreadValue(
                            unreadCount = accountUnreadCount(rows, summary.accountIdHex, membersByGroupId),
                            freshness = AccountUnreadFreshness.CONFIRMED,
                            hasManualUnread = accountHasManualUnread(rows, summary.accountIdHex, membersByGroupId),
                        ),
                )
            }
        }.onFailure { appStateDebug { "account unread refresh failed" } }
            .getOrNull()
    }

    /**
     * Refresh the unread count for a single account, merging the result into
     * [accountUnreadCounts] without disturbing the other accounts' counts.
     *
     * On a per-notification hot path we only ever need the one account that
     * changed, so we avoid fanning out an all-account scan (#473) while still
     * applying removed-group suppression for that account (#662). This remains
     * intentionally more expensive than the old raw-row fold: it loads the
     * account's chat list plus member rosters for unread rows, so keep callers
     * scoped to the changed account until Marmot exposes a suppressed summary.
     */
    private suspend fun refreshAccountUnreadCount(accountRef: String) {
        val ref = accountRef.takeIf { it.isNotBlank() } ?: return
        // Only signed-in signing accounts are tracked in accountUnreadCounts;
        // skip refs we don't know about (matches refreshAccountUnreadCounts'
        // filter).
        val summary = accounts.firstOrNull { it.isSignedInSigningAccount() && it.label == ref } ?: return
        val previous = accountUnreadStore.snapshot()[ref]
        val result = refreshEffectiveAccountUnreadCount(summary) ?: return
        accountUnreadStore.publishExactIfUnchanged(ref, previous, result.value)
    }

    private fun isAccountSwitchCurrent(generation: Long) = accountSwitchHandoff.isCurrent(generation)

    private fun ensureAccountSwitchRequestIsCurrent(generation: Long) {
        if (!isAccountSwitchCurrent(generation)) throw AccountSwitchSnapshotSuperseded()
    }

    private fun recordAccountSwitchPreloadStage(
        accountRef: String,
        stage: String,
        rowCount: Int,
    ) {
        val trace = pendingAccountSwitchTrace ?: return
        if (trace.accountRef != accountRef) return
        val elapsedMs = (SystemClock.elapsedRealtime() - trace.startedAtMs).coerceAtLeast(0L)
        appStateDebug { "account-switch $stage +${elapsedMs}ms rows=$rowCount" }
    }

    private fun recordAccountSwitchIdentityState(
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

    /**
     * Reads the target account's local chat rows, and decodes their stored first-frame
     * avatars, before publishing the new active account. The first target composition gets
     * these rows instead of a loading placeholder; its controller owns full group and live
     * subscription admission after that seeded frame is visible.
     */
    private suspend fun loadAccountSwitchLocalSnapshot(
        accountRef: String,
        generation: Long,
        plan: AccountSwitchPreloadPlan,
    ): AccountSwitchLocalSnapshot? =
        try {
            val presentedRows =
                marmotIo(MarmotTraceSection.CHAT_LIST_READ) {
                    presentedChatList(accountRef, includeArchived = true)
                }.rows
            val rows = presentedRows.map { it.row }
            val firstFrameAvatars = prewarmFirstFrameDurableAvatars(accountRef, presentedRows)
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(accountRef, "cached-chat-rows-ready", rows.size)
            val presentation =
                loadAccountSwitchPresentationSeeds(
                    accountRef = accountRef,
                    generation = generation,
                    rows = rows,
                    plan = plan,
                )
            ensureAccountSwitchRequestIsCurrent(generation)

            AccountSwitchLocalSnapshot(
                accountRef = accountRef,
                activeAccountIdHex = presentation.activeAccountIdHex,
                rows = rows,
                groups = emptyList(),
                memberIds = presentation.memberIds,
                profiles = presentation.profiles,
                presentedRows = presentedRows,
                firstFrameAvatars = firstFrameAvatars,
            ).also { snapshot ->
                if (plan.includePresentationSeeds) recordAccountSwitchIdentityState(accountRef, snapshot)
            }
        } catch (_: AccountSwitchSnapshotSuperseded) {
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            appStateDebug(throwable) {
                "account-switch local snapshot failed: ${throwable.readableMessage()}"
            }
            null
        }

    private suspend fun loadAccountSwitchPresentationSeeds(
        accountRef: String,
        generation: Long,
        rows: List<ChatListRowFfi>,
        plan: AccountSwitchPreloadPlan,
    ): AccountSwitchPresentationSeeds =
        coroutineScope {
            val activeAccountIdHex = accounts.firstOrNull { it.label == accountRef }?.accountIdHex
            // The bounded set the selector/overflow policy will actually render. Both
            // switch paths read it here, so an interactive switch owes its first frame
            // exactly the seeds the full-snapshot path already loads (#2155).
            val topBarProfileIds =
                if (plan.includeTopBarProfileSeeds) {
                    accountSwitchProfileSeedIds(emptyList(), accounts, accountRef)
                } else {
                    emptyList()
                }
            if (!plan.includePresentationSeeds) {
                recordAccountSwitchPreloadStage(accountRef, "member-derived-local-deferred", rows.size)
                // Off the main thread, and dropped wholesale if a later switch wins.
                val topBarProfiles = loadAccountSwitchProfileSeeds(topBarProfileIds)
                ensureAccountSwitchRequestIsCurrent(generation)
                recordAccountSwitchPreloadStage(accountRef, "top-bar-profiles-ready", rows.size)
                return@coroutineScope AccountSwitchPresentationSeeds(activeAccountIdHex, emptyList(), topBarProfiles)
            }
            // Overlap bounded top-bar reads with the identity-critical member page.
            val topBarProfilesDeferred = async { loadAccountSwitchProfileSeeds(topBarProfileIds) }
            val memberIds = loadAccountSwitchMemberIds(accountRef, rows)
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(
                accountRef,
                accountSwitchMemberStage(rows, memberIds),
                rows.size,
            )
            val directPeerProfileIds = accountSwitchDirectPeerProfileIds(rows, memberIds, activeAccountIdHex)
            val topBarKeys = topBarProfileIds.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
            val directProfiles =
                loadAccountSwitchProfileSeeds(
                    directPeerProfileIds.filterNot { it.lowercase(Locale.ROOT) in topBarKeys },
                )
            ensureAccountSwitchRequestIsCurrent(generation)
            val topBarProfiles = topBarProfilesDeferred.await()
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(accountRef, "persisted-profiles-ready", rows.size)
            AccountSwitchPresentationSeeds(
                activeAccountIdHex = activeAccountIdHex,
                memberIds = memberIds,
                profiles = (directProfiles + topBarProfiles).distinctBy { it.accountIdHex.lowercase(Locale.ROOT) },
            )
        }

    private suspend fun loadAccountSwitchMemberIds(
        accountRef: String,
        rows: List<ChatListRowFfi>,
    ): List<AppGroupMemberIdsFfi> {
        val identityGroupIds = accountSwitchFirstFrameMemberGroupIds(rows)
        if (identityGroupIds.isEmpty()) return emptyList()
        return runCatchingCancellable {
            loadGroupMemberIdsPages(identityGroupIds) { page ->
                marmotIo(MarmotTraceSection.MEMBER_IDS_READ) { groupMemberIdsPage(accountRef, page) }
            }
        }.onFailure { error ->
            appStateDebug(error) {
                "account-switch local member projection failed: ${error.readableMessage()}"
            }
        }.getOrDefault(emptyList())
    }

    private suspend fun loadAccountSwitchProfileSeeds(profileIds: List<String>): List<AccountSwitchProfileSeed> =
        if (profileReader != null || profileDisplayNameReader != null) {
            // Existing injected single-identity readers retain their fixture contract.
            coroutineScope {
                val gate = Semaphore(PROFILE_PRESENTATION_WARM_FANOUT)
                profileIds
                    .map { id -> async { gate.withPermit { loadAccountSwitchProfileSeed(id) } } }
                    .awaitAll()
            }
        } else {
            loadAccountSwitchIdentityPages(profileIds) { page ->
                marmotIo(MarmotTraceSection.PROFILE_READ) { cachedIdentityProjections(page) }
            }
        }

    internal fun consumeAccountSwitchLocalSnapshot(accountRef: String?) = accountSwitchHandoff.consume(accountRef)

    private fun stageAccountSwitchLocalSnapshot(
        label: String,
        switchingAccounts: Boolean,
        requestGeneration: Long,
        localSnapshot: AccountSwitchLocalSnapshot?,
    ) {
        if (localSnapshot != null) {
            // The target account's local SQLite rows are authoritative at the
            // same generation boundary as the first target composition. Replace
            // a stale/unknown picker value before activeAccountRef changes.
            accountUnreadStore.updateValue(label) {
                accountUnreadValueFromRows(
                    rows = localSnapshot.rows,
                    activeAccountIdHex = localSnapshot.activeAccountIdHex,
                )
            }
            localSnapshot.profiles.forEach(::applyAccountSwitchProfileSeed)
            accountSwitchHandoff.publish(requestGeneration, localSnapshot)
        } else if (switchingAccounts) {
            // A failed or intentionally deferred local snapshot cannot certify
            // the previous aggregate. Retain it for reconciliation, but stop
            // presenting it as current on the target account's first frame.
            accountUnreadStore.markUnknown(label)
        }
    }

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
            updatePendingAccountSwitchTrace(request.label, switchingAccounts)
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
            reconcileNotificationDeliveryModeAfterActivation(includeBackgroundAccountModes)
        }

        /** Reads foreground state at delivery admission rather than capturing it before the frame wait. */
        override fun isInForeground(): Boolean = appInForeground
    }

    /** Starts or clears the debug-only latency trace without adding policy branches to activation. */
    private fun updatePendingAccountSwitchTrace(
        label: String,
        switchingAccounts: Boolean,
    ) {
        if (switchingAccounts && BuildConfig.DEBUG) {
            pendingAccountSwitchTrace =
                PendingAccountSwitchTrace(
                    accountRef = label,
                    startedAtMs = SystemClock.elapsedRealtime(),
                )
        } else if (pendingAccountSwitchTrace?.accountRef != label) {
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

    /**
     * Wipe the device-side decrypted-media footprint that outlives the
     * in-memory caches: the L2 disk cache (cacheDir/decrypted-media) and the
     * decrypted voice/video plaintext and unsent pasted media that the
     * conversation UI materializes under cacheDir. Used at sign-out, when we
     * treat that footprint as ending with the session. Re-opening a chat after
     * the next sign-in re-downloads from Blossom.
     *
     * Suspends so the sign-out flow can await completion rather than racing a
     * fast re-sign-in against an unfinished wipe. `shared_media` is left to its
     * age-based janitor on purpose: those files can back a live external
     * "open with"/share reader and deleting them out from under it would break
     * an in-progress share.
     */
    private suspend fun wipeDecryptedMediaFromDisk() {
        withContext(Dispatchers.IO) {
            AttachmentCachePublication.onWipeStarted()
            try {
                // Each target holds decrypted plaintext, so wipe them independently
                // and best-effort: a failure in one (IO error, locked file) must not
                // skip the others, and a swallowed failure should still be visible.
                runCatchingCancellable { diskMediaCache.clear() }
                    .onFailure {
                        appStateDebug { "disk media cache wipe failed: ${it.readableMessage()}" }
                    }
                dev.ipf.whitenoise.android.media.wipeSessionAttachmentPlaintext(appContext.cacheDir) { name, failure ->
                    appStateDebug { "$name plaintext wipe failed: ${failure.readableMessage()}" }
                }
                // The single pending-forward entry can hold the wiped account's
                // message plaintext; a destructive wipe drops it unconditionally.
                runCatchingCancellable { forwardRequestPersistence.clear() }
                    .onFailure {
                        appStateDebug { "pending forward request wipe failed: ${it.readableMessage()}" }
                    }
            } finally {
                AttachmentCachePublication.onWipeFinished()
            }
        }
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
            bumpAllProfileAccountRevisions()
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
        authoritativeMuteOverrides.clear()
        pendingMuteCommands.clear()
        AvatarImageLoader.clearStoredAvatars()
        GroupAvatarImageLoader.clear()
        pruneIdleGroupCommitLocks()
        profileRevision += 1
        bumpAllProfileAccountRevisions()
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
        notificationDeliveryModeMutex.lock()
        var pushOwned = false
        var deliveryOwned = true
        try {
            nativePushSyncMutex.lock()
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
            notificationDeliveryModeIntent.advance()
            nativePushFallback.invalidateAll()
            return withContext(NonCancellable) {
                val attempt =
                    try {
                        admittedNativeSignOutLocked(account, runtime, deleteKeyPackages)
                    } finally {
                        nativePushSyncMutex.unlock()
                        pushOwned = false
                        notificationDeliveryModeMutex.unlock()
                        deliveryOwned = false
                    }
                // Shared and account-keyed cleanup finishes while lifetime is still owned, before any successor.
                finishOwnedSignOutLocked(account, runtime, attempt, selectionGeneration, draftsSaved)
            }
        } finally {
            if (pushOwned) nativePushSyncMutex.unlock()
            if (deliveryOwned) notificationDeliveryModeMutex.unlock()
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
        perAccountSyncedFingerprints.remove(account)
        localNotificationSettings = null
        val disabled =
            runCatching {
                withContext(marmotIoDispatcher) { runtime.marmot.setNativePushEnabled(account, false) }
            }
        val settings = disabled.getOrNull()
        if (settings?.nativePushEnabled != false) {
            return NativeSignOutAttempt(null, false, disabled.exceptionOrNull() as? CancellationException)
        }
        localNotificationSettings = settings
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
        localNotificationSettings = null
        if (!draftsSaved) present(R.string.toast_draft_save_failed)
        return OwnedSignOutCompletion(
            if (partial) SignOutCompletion.AccountCleanupIncomplete else signOutCompletion(native),
            next,
            selectionGeneration,
            attempt.cancellation,
        )
    }

    /** Retires only the signed-out account's transient drafts and controllers, preserving a replacement account. */
    private fun clearSignedOutAccountMemory(account: String) {
        composerExpansionStateRetention.removeAccount(account)
        composerDraftExpansionBridge.removeAccount(account)
        pendingMessageEditHandoff.removeAccount(account)
        notificationReplyDraftHandoff.removeAccount(account)
        draftWriter.removeAccount(account)
        conversationDictation.onAccountUnavailable(account)
        stopTtsForRemovedAccount(account)
    }

    suspend fun exportActiveAccountNsec(): String? {
        val accountRef = activeAccountRef ?: return null
        return runCatchingCancellable {
            marmotIo { revealNsec(accountRef) }
        }.onFailure {
            // Secret-key export holds the nsec in hand and the toast is not
            // behind FLAG_SECURE — scrub the FFI message before showing it (#846).
            presentFailure(R.string.toast_couldnt_export_nsec, "SECRET_KEY_EXPORT", it)
        }.getOrNull()
    }

    /**
     * Destructive sign-out: leave MLS groups, delete relay KeyPackages, and
     * wipe all local state for the active account via Marmot's
     * [dev.ipf.marmotkit.Marmot.signOutAndWipe]. Returns the structured
     * outcome so the UI can surface partial failures.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    // One cancellation-safe bracket owns wipe, editor purge, account switch, and recovery.
    suspend fun signOutAndWipeActiveAccount(): WipeOutcomeFfi? {
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

    private suspend fun signOutAndWipeActiveAccountLocked(wipedRef: String): WipeOutcomeFfi? {
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

    /** The caller holds launcher revocation through cleanup; completed native ownership is published first. */
    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private suspend fun finishRevokedAccountWipe(wipedRef: String): WipeOutcomeFfi? {
        val wipedShareAccount = accounts.firstOrNull { it.label == wipedRef }?.accountIdHex
        conversationDictation.onAccountUnavailable(wipedRef)
        clearInMemoryMediaCaches()
        try {
            val restartNotifications = prepareForDestructiveAccountWipe(wipedRef)
            val wipeResult =
                nativePushSyncMutex.withSerializedNativePushWipe {
                    runCatching { marmotIo { signOutAndWipe(wipedRef) } }
                        .onSuccess { outcome ->
                            if (outcome.localCleanup.completed) {
                                // Publish native removal before encrypted push cleanup can throw.
                                accountListLifetime.advance { accounts = accounts.filterNot { it.label == wipedRef } }
                                runCatching { pushTokenStore.clearPendingDisable(wipedRef) }
                                // The wipe invalidates server-side registration state for this account.
                                // withSerializedNativePushWipe already holds nativePushSyncMutex here.
                                perAccountSyncedFingerprints.remove(wipedRef)
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
                appStateDebug {
                    "signOutAndWipe kept active account=${wipedRef.take(8)} because engine cleanup did not complete"
                }
                restoreAfterFailedDestructiveAccountWipe(wipedRef, restartNotifications)
                return outcome
            }
            chatMutePreferences.removeAccount(wipedRef)
            defaultDisappearingMessagesPreferences.removeAccount(wipedRef)
            composerExpansionStateRetention.removeAccount(wipedRef)
            composerDraftExpansionBridge.removeAccount(wipedRef)
            pendingMessageEditHandoff.removeAccount(wipedRef)
            notificationReplyDraftHandoff.removeAccount(wipedRef)
            draftWriter.removeAccount(wipedRef)
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
            wipeDecryptedMediaFromDisk()
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
            return outcome
        } finally {
            // Backstop: the suppression bracket must not outlive this call
            // whatever throws above — a latched flag would silently disable
            // network-restore recovery until process death.
            networkNotificationRecoverySuppressed = false
        }
    }

    suspend fun exportEncryptedSecretKeyBackup(passphrase: String): String? {
        val account = activeAccountRef ?: return null
        return runCatchingCancellable {
            marmotIo { exportEncryptedSecretKey(account, passphrase) }
        }.onSuccess {
            presentTransient(R.string.toast_encrypted_backup_created)
        }.onFailure {
            appStateDebug { "encrypted backup failed: ${it.javaClass.simpleName}" }
            presentFailure(R.string.toast_couldnt_create_encrypted_backup, "ENCRYPTED_BACKUP_EXPORT", it)
        }.getOrNull()
    }

    suspend fun accountRelayLists(): AccountRelayListsFfi? = activeAccountRef?.let { loadAccountRelayLists(it) }

    suspend fun addAccountRelay(
        account: String?,
        kind: RelayListKind,
        relay: String,
    ): AccountRelayListsFfi? {
        val validationError =
            when (relayUrlValidationResult(relay)) {
                RelayUrlValidationResult.Acceptable -> null
                RelayUrlValidationResult.Invalid -> R.string.error_invalid_relay_url
            }
        val current = account?.takeIf { validationError == null }?.let { loadAccountRelayLists(it) }
        val plan = current?.let { relayListAfterAddition(it.relaysFor(kind), relay) }
        return when {
            validationError != null -> {
                present(R.string.toast_relay_update_failed, validationError)
                null
            }
            account == null -> {
                present(R.string.toast_relay_update_failed, R.string.no_active_account_period)
                null
            }
            current == null -> {
                present(R.string.toast_relay_update_failed, R.string.no_relay_projection)
                null
            }
            plan == null -> {
                present(R.string.toast_relay_update_failed, R.string.error_invalid_relay_url)
                null
            }
            else -> publishAccountRelays(account, kind, plan)
        }
    }

    suspend fun removeAccountRelay(
        account: String?,
        kind: RelayListKind,
        relay: String,
    ): AccountRelayListsFfi? {
        val current = account?.let { loadAccountRelayLists(it) }
        return when {
            account == null -> {
                present(R.string.toast_relay_update_failed, R.string.no_active_account_period)
                null
            }
            current == null -> {
                present(R.string.toast_relay_update_failed, R.string.no_relay_projection)
                null
            }
            else -> publishAccountRelays(account, kind, relayListAfterRemoval(current.relaysFor(kind), relay))
        }
    }

    /** Validates and publishes one relay-list edit after app-level and MarmotKit policy checks. */
    internal suspend fun publishAccountRelays(
        account: String,
        kind: RelayListKind,
        plan: RelayListEditPlan,
    ): AccountRelayListsFfi? =
        when (val validation = relayPublishValidation(plan)) {
            is RelayPublishValidation.Rejected -> {
                when (validation.error) {
                    RelayPublishValidationError.Invalid ->
                        present(R.string.toast_relay_update_failed, R.string.error_invalid_relay_url)
                    RelayPublishValidationError.Blocked ->
                        present(
                            R.string.toast_relay_update_failed,
                            R.string.error_remove_invalid_relay_urls_first,
                        )
                    RelayPublishValidationError.Unavailable ->
                        present(
                            R.string.toast_relay_update_failed,
                            R.string.error_couldnt_verify_relay_hosts,
                        )
                }
                null
            }

            is RelayPublishValidation.Ready ->
                runCatchingCancellable {
                    marmotIo {
                        when (kind) {
                            RelayListKind.Nip65 ->
                                setAccountNip65Relays(account, validation.relays, MarmotClient.bootstrapRelays)
                            RelayListKind.Inbox ->
                                setAccountInboxRelays(account, validation.relays, MarmotClient.bootstrapRelays)
                        }
                    }
                }.onSuccess {
                    presentTransient(R.string.toast_relay_list_updated)
                }.onFailure {
                    presentFailure(R.string.toast_relay_update_failed, "RELAY_LIST_UPDATE", it)
                }.getOrNull()
        }

    internal suspend fun loadAccountRelayLists(account: String): AccountRelayListsFfi? =
        runCatchingCancellable { marmotIo { accountRelayLists(account) } }.getOrNull()

    /** Applies MarmotKit's shared relay policy and strips only unsafe pre-existing entries. */
    private suspend fun relayPublishValidation(plan: RelayListEditPlan): RelayPublishValidation =
        if (plan.relays.isEmpty() || plan.relays.any { !isAcceptableRelayUrl(it) }) {
            RelayPublishValidation.Rejected(RelayPublishValidationError.Invalid)
        } else {
            when (withContext(Dispatchers.IO) { requiredRelayResolveTimeCheckResult(plan) }) {
                RelayResolveTimeCheckResult.Blocked ->
                    RelayPublishValidation.Rejected(RelayPublishValidationError.Blocked)
                RelayResolveTimeCheckResult.Unavailable ->
                    RelayPublishValidation.Rejected(RelayPublishValidationError.Unavailable)
                RelayResolveTimeCheckResult.Passed ->
                    runCatchingCancellable {
                        marmotIo { classifyRelayEndpoints(plan.relays) }
                    }.fold(
                        onSuccess = { classified ->
                            allowedRelayUrlsForPublish(plan, classified)
                                ?.let(RelayPublishValidation::Ready)
                                ?: RelayPublishValidation.Rejected(RelayPublishValidationError.Blocked)
                        },
                        onFailure = {
                            RelayPublishValidation.Rejected(RelayPublishValidationError.Unavailable)
                        },
                    )
            }
        }

    private fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> =
        when (kind) {
            RelayListKind.Nip65 -> nip65.relays
            RelayListKind.Inbox -> inbox.relays
        }

    fun bootstrapRelayCount(): Int = MarmotClient.bootstrapRelays.size

    /** Deletes a KeyPackage only for its still-active account through safe MDK-provided sources. */
    suspend fun deleteKeyPackage(
        accountRef: String,
        eventIdHex: String,
        sourceRelays: List<String>,
    ): Boolean =
        deleteKeyPackageWithDependencies(
            accountRef = accountRef,
            eventIdHex = eventIdHex,
            sourceRelays = sourceRelays,
            classify = { relays -> marmotIo { classifyRelayEndpoints(relays) } },
            resolve = { host -> resolveKeyPackageDeletionHost(host) },
            delete = { account, eventId, relays ->
                marmotIo { deleteAccountKeyPackage(account, eventId, relays) }
            },
        )

    /** Runs the account-fenced deletion route with injectable native and DNS boundaries. */
    internal suspend fun deleteKeyPackageWithDependencies(
        accountRef: String,
        eventIdHex: String,
        sourceRelays: List<String>,
        classify: suspend (List<String>) -> List<RelayEndpointClassificationFfi>,
        resolve: KeyPackageDeletionHostResolver,
        delete: suspend (accountRef: String, eventIdHex: String, relays: List<String>) -> Unit,
    ): Boolean {
        val account = activeAccountRef?.takeIf { it == accountRef }
        val accountSwitchGeneration = account?.let(accountSwitchHandoff::captureForAccount)
        if (account == null || accountSwitchGeneration == null) return false
        val result =
            deleteKeyPackageThroughSafeSourceRelays(
                sourceRelays = sourceRelays,
                classify = classify,
                resolve = resolve,
                accountStillActive = {
                    activeAccountRef == account && isAccountSwitchCurrent(accountSwitchGeneration)
                },
                delete = { relays -> delete(account, eventIdHex, relays) },
            )
        return presentKeyPackageDeletionResult(result, AppText.Resource(R.string.error_couldnt_verify_relay_hosts))
    }

    suspend fun publishNewKeyPackage(): Boolean {
        val account = activeAccountRef ?: return false
        return runCatching {
            marmotIo { publishNewKeyPackage(account) }
            presentTransient(R.string.toast_new_key_package_published)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_publish_key_package, "KEY_PACKAGE_PUBLISH", it)
            false
        }
    }

    suspend fun republishKeyPackage(): Boolean {
        val account = activeAccountRef ?: return false
        return runCatching {
            marmotIo { republishKeyPackage(account) }
            presentTransient(R.string.toast_key_package_republished)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_republish_key_package, "KEY_PACKAGE_REPUBLISH", it)
            false
        }
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

    fun refreshAppLockCredentialAvailability() {
        appLockCredentialAvailable = isAppLockCredentialAvailable(appContext)
        if (!appLockCredentialAvailable) {
            appUnlockSessions.clear()
            appLockScreenVisible = false
            appUnlockError = null
            resumePendingInviteNotificationIdentityRefreshes()
        }
    }

    fun updateRequireAppUnlock(enabled: Boolean) {
        refreshAppLockCredentialAvailability()
        if (enabled && !appLockCredentialAvailable) {
            requireAppUnlock = false
            persistHostSetting { putBoolean(REQUIRE_APP_UNLOCK_KEY, false) }
            present(R.string.toast_app_lock_screen_lock_required)
            return
        }
        requireAppUnlock = enabled
        persistHostSetting { putBoolean(REQUIRE_APP_UNLOCK_KEY, enabled) }
        if (enabled) {
            requestAppUnlock()
        } else {
            appUnlockSessions.clear()
            appLockScreenVisible = false
            appUnlockError = null
            resumePendingInviteNotificationIdentityRefreshes()
        }
    }

    fun updateAppLockDelay(delay: AppLockDelay) {
        appLockDelay = delay
        persistHostSetting { putString(APP_LOCK_DELAY_KEY, delay.preferenceValue) }
    }

    fun requestAppUnlock() {
        refreshAppLockCredentialAvailability()
        if (!requireAppUnlock || !appLockCredentialAvailable) return
        showAppLockScreen()
        if (appUnlockSessions.begin()) appUnlockError = null
    }

    private fun showAppLockScreen() {
        appLockScreenVisible = true
        stopSpeaking()
    }

    /** Capture on Main; serialize before dispatch so a slower old write cannot overtake a newer event. */
    private fun persistAppLockTimestamp(value: Long) {
        mutationsScope.launch {
            appLockTimestampWriteMutex.withLock {
                withContext(appLockPersistenceDispatcher) {
                    val write = appLockTimestampWrite
                    if (write != null) {
                        write(value)
                    } else {
                        AppLockPreferences.writeLastUnlockedAtMillis(appContext, value)
                    }
                }
            }
        }
    }

    /** Publish successful authentication immediately; persist its captured grace baseline asynchronously. */
    internal fun markAppUnlockSucceeded(
        nowMillis: Long = System.currentTimeMillis(),
        dismissRetainedVisibleConversation: Boolean = true,
    ) {
        val normalizedNow = nowMillis.coerceAtLeast(0L)
        lastAppUnlockAtMillis = normalizedNow
        persistAppLockTimestamp(normalizedNow)
        appLockScreenVisible = false
        appUnlockError = null
        if (appInForeground) initializedConversationDictation()?.onAppForegrounded()
        resumePendingInviteNotificationIdentityRefreshes()
        resumePushWakeEpisodeFromLifecycle()
        if (dismissRetainedVisibleConversation) {
            dismissVisibleConversationNotifications()
        }
    }

    internal fun markAppUnlockFailed(message: AppText = AppText.Resource(R.string.app_lock_auth_cancelled)) {
        if (!appLockScreenVisible) return
        appUnlockError = message
    }

    // True while the foreground lock decision waits for off-main unlock time. The secure scrim remains visible,
    // and the prompt waits for the real value so the grace period is not falsely treated as expired.
    var appUnlockEvaluationPending by mutableStateOf(false)
        private set

    fun maybeShowAppLockForForeground(nowMillis: Long = System.currentTimeMillis()) {
        refreshAppLockCredentialAvailability()
        // Skip timestamp work when app lock is disabled or the device credential is unavailable.
        if (!requireAppUnlock || !appLockCredentialAvailable) return
        val promptActive = appUnlockSessions.activeSessionId != null
        if (promptActive) showAppLockScreen()
        if (promptActive || appUnlockSessions.consumeForegroundReturn(SystemClock.elapsedRealtime())) return
        val knownLastUnlock = lastAppUnlockAtMillisBacking
        if (knownLastUnlock == null) {
            deferAppLockDecisionUntilTimestampLoads(nowMillis)
        } else if (
            shouldShowAppLock(
                requireUnlock = requireAppUnlock,
                credentialAvailable = appLockCredentialAvailable,
                lastUnlockedAtMillis = knownLastUnlock,
                nowMillis = nowMillis,
                delay = appLockDelay,
            )
        ) {
            requestAppUnlock()
        }
    }

    private fun deferAppLockDecisionUntilTimestampLoads(nowMillis: Long) {
        if (appUnlockEvaluationPending) return
        appUnlockEvaluationPending = true
        showAppLockScreen()
        mutationsScope.launch {
            val loaded = withContext(Dispatchers.IO) { AppLockPreferences.readLastUnlockedAtMillis(appContext) }
            if (lastAppUnlockAtMillisBacking == null) lastAppUnlockAtMillisBacking = loaded
            appUnlockEvaluationPending = false
            // Re-read the clock after IO so a grace period that expired during loading cannot dismiss the lock.
            val decisionNowMillis = maxOf(nowMillis, System.currentTimeMillis())
            if (
                shouldShowAppLock(
                    requireUnlock = requireAppUnlock,
                    credentialAvailable = appLockCredentialAvailable,
                    lastUnlockedAtMillis = lastAppUnlockAtMillis,
                    nowMillis = decisionNowMillis,
                    delay = appLockDelay,
                )
            ) {
                requestAppUnlock()
            } else {
                appLockScreenVisible = false
                if (appInForeground) initializedConversationDictation()?.onAppForegrounded()
                resumePendingInviteNotificationIdentityRefreshes()
            }
        }
    }

    fun shouldSecureAppLockWindowWhileBackgrounded(): Boolean = shouldSecureAppLockWindowWhileBackgrounded(requireUnlock = requireAppUnlock)

    /** Start the away-time grace period only for an authenticated, credentialed background transition. */
    private fun recordAppLockBackgrounded(nowMillis: Long = System.currentTimeMillis()) {
        refreshAppLockCredentialAvailability()
        if (
            !shouldRefreshAppLockDelayBaselineOnBackground(
                requireUnlock = requireAppUnlock,
                credentialAvailable = appLockCredentialAvailable,
                lockScreenVisible = appLockScreenVisible,
            )
        ) {
            return
        }
        val normalizedNow = nowMillis.coerceAtLeast(0L)
        // The delay means time spent away from the app, not time since the
        // last credential prompt while the user was actively reading chats.
        lastAppUnlockAtMillis = normalizedNow
        persistAppLockTimestamp(normalizedNow)
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

    /** Saves the explicit sharing choice and returns false while the retryable error remains visible. */
    suspend fun setTelemetryEnabled(enabled: Boolean): Boolean = diagnostics.choose(marmot(), enabled)

    /** Retries the failed user choice, rather than discarding it with a passive settings read. */
    internal suspend fun retryUsageDiagnostics() = diagnostics.retry(marmot())

    /** Whether the current MDK policy has a confirmed explicit grant. */
    fun isUsageDiagnosticsGranted(): Boolean = diagnostics.granted

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

    /** Archives audit, dictation, and performance data; empty or partial exports return null. */
    @Suppress("ReturnCount") // Each engine/cache failure is a distinct fail-closed export outcome.
    suspend fun prepareAuditLogArchiveForExport(): java.io.File? {
        val sourcePaths =
            runCatching { marmotIo { auditLogFiles().map { it.path } } }
                .getOrElse {
                    if (it is CancellationException) throw it
                    present(R.string.toast_couldnt_export_audit_logs)
                    return null
                }
        return runCatchingCancellable {
            withContext(Dispatchers.IO) {
                val nativePerformance =
                    if (PerformanceDiagnostics.isActive()) {
                        runCatchingCancellable {
                            marmotIo {
                                dev.ipf.whitenoise.android.diagnostics
                                    .formatNativePerformanceSnapshot(appPerformanceSnapshot())
                            }.toByteArray(Charsets.UTF_8)
                        }.getOrNull()
                    } else {
                        null
                    }
                prepareAuditAndDictationLogArchive(
                    appContext,
                    sourcePaths,
                    nativePerformanceLogBytes = nativePerformance,
                )
            }.also { if (it == null) presentTransient(R.string.toast_no_audit_logs_to_export) }
        }.getOrElse {
            present(R.string.toast_couldnt_export_audit_logs)
            return null
        }
    }

    /** Clears audit, dictation, and performance files while surfacing native deletion failures. */
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    suspend fun deleteAuditLogs(): Boolean {
        var engineFailure: Throwable? = null
        var cacheFailure: Throwable? = null
        val preparedDeleted =
            runCatchingCancellable {
                withContext(Dispatchers.IO) { clearAuditAndDictationLogShares(appContext.cacheDir) }
            }.onFailure { cacheFailure = it }.getOrDefault(false)
        val files =
            runCatching { marmotIo { auditLogFiles() } }
                .getOrElse {
                    if (it is CancellationException) throw it
                    presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
                    return false
                }
        if (files.isEmpty()) {
            cacheFailure?.let {
                presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
                return false
            }
            if (preparedDeleted) {
                presentTransient(R.string.toast_audit_logs_deleted)
                return true
            }
            presentTransient(R.string.toast_no_audit_logs_to_delete)
            return false
        }
        var anyDeleted = false
        for (file in files) {
            val outcome =
                runCatchingCancellable { marmotIo { deleteAuditLogFile(file.path) } }
                    .onFailure {
                        if (engineFailure == null) engineFailure = it
                        appStateDebug { "deleteAuditLogFile failed: ${it.readableMessage()}" }
                    }.getOrNull() ?: continue
            anyDeleted = true
            appStateDebug { "audit log deleted still_recording=${outcome.stillRecording}" }
        }
        cacheFailure?.let {
            presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
            return false
        }
        val deleted = anyDeleted || (preparedDeleted && engineFailure == null)
        if (deleted) {
            presentTransient(R.string.toast_audit_logs_deleted)
        } else {
            engineFailure?.let {
                presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
            } ?: present(R.string.toast_couldnt_delete_audit_logs)
        }
        return deleted
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

    internal fun globalBubbleColorArgb(
        theme: BubbleTheme,
        side: BubbleSide,
    ): Long? {
        val account = activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val slot = AccountBubbleColorSlot(account, theme, side)
        return globalBubbleColors
            .getOrPut(slot) {
                mutableStateOf(BubbleColorPreferences.readGlobalColor(preferences, account, theme, side))
            }.value
    }

    internal fun chatBubbleColorArgb(
        groupIdHex: String,
        side: BubbleSide,
    ): Long? {
        val key = BubbleColorPreferences.chatKey(activeAccountRef, groupIdHex, side) ?: return null
        return chatBubbleColors
            .getOrPut(key) {
                mutableStateOf(BubbleColorPreferences.readChatColor(preferences, activeAccountRef, groupIdHex, side))
            }.value
    }

    internal fun effectiveBubbleColorArgb(
        theme: BubbleTheme,
        side: BubbleSide,
        groupIdHex: String,
    ): Long? = chatBubbleColorArgb(groupIdHex, side) ?: globalBubbleColorArgb(theme, side)

    internal fun updateGlobalBubbleColor(
        theme: BubbleTheme,
        side: BubbleSide,
        argb: Long?,
    ) {
        val account = activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: return
        BubbleColorPreferences.writeGlobalColor(preferences, account, theme, side, argb)
        val color = BubbleColorPreferences.readGlobalColor(preferences, account, theme, side)
        globalBubbleColors
            .getOrPut(AccountBubbleColorSlot(account, theme, side)) { mutableStateOf(color) }
            .value = color
    }

    internal fun actionColorArgb(
        theme: BubbleTheme,
        accountRef: String? = activeAccountRef,
    ): Long? {
        val account = accountRef?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val slot = AccountActionColorSlot(account, theme)
        return actionColors
            .getOrPut(slot) {
                mutableStateOf(ActionColorPreferences.readColor(preferences, account, theme))
            }.value
    }

    internal fun updateActionColor(
        theme: BubbleTheme,
        argb: Long?,
    ) {
        val account = activeAccountRef?.trim()?.takeIf(String::isNotEmpty) ?: return
        ActionColorPreferences.writeColor(preferences, account, theme, argb)
        val color = ActionColorPreferences.readColor(preferences, account, theme)
        actionColors
            .getOrPut(AccountActionColorSlot(account, theme)) { mutableStateOf(color) }
            .value = color
    }

    internal fun updateChatBubbleColor(
        groupIdHex: String,
        side: BubbleSide,
        argb: Long?,
    ) {
        val key = BubbleColorPreferences.chatKey(activeAccountRef, groupIdHex, side) ?: return
        BubbleColorPreferences.writeChatColor(preferences, activeAccountRef, groupIdHex, side, argb)
        val color = BubbleColorPreferences.readChatColor(preferences, activeAccountRef, groupIdHex, side)
        chatBubbleColors.getOrPut(key) { mutableStateOf(color) }.value = color
    }

    /**
     * Local UI preference for the active account's per-group long-message collapse
     * gate (#1180). Default ON preserves the existing Read More behavior for every
     * account/group until the user disables it from that group's details screen.
     */
    fun collapseLongMessagesInGroup(groupIdHex: String): Boolean = longMessageCollapseState.collapseLongMessages(activeAccountRef, groupIdHex)

    fun updateCollapseLongMessagesInGroup(
        groupIdHex: String,
        enabled: Boolean,
    ) {
        longMessageCollapseState.updateCollapseLongMessages(activeAccountRef, groupIdHex, enabled)
    }

    fun isConversationMuted(groupIdHex: String): Boolean {
        val accountRef = activeAccountRef ?: return false
        return effectiveMuteOverride(
            authoritativeMuteOverrides[ChatMutePreferences.compositeKey(accountRef, groupIdHex)],
            System.currentTimeMillis(),
        )?.muted
            ?: engineConversationMuted(groupIdHex)
    }

    fun isConversationMutePending(groupIdHex: String): Boolean {
        val accountRef = activeAccountRef ?: return false
        return (pendingMuteCommands[ChatMutePreferences.compositeKey(accountRef, groupIdHex)] ?: 0) > 0
    }

    internal fun conversationMuteOverride(groupIdHex: String): dev.ipf.marmotkit.ChatNotificationSettingsFfi? {
        val accountRef = activeAccountRef ?: return null
        return effectiveMuteOverride(
            authoritativeMuteOverrides[ChatMutePreferences.compositeKey(accountRef, groupIdHex)],
            System.currentTimeMillis(),
        )
    }

    fun conversationNotifyMode(groupIdHex: String): ChatNotifyMode {
        val accountRef = activeAccountRef ?: return ChatNotifyMode.ALL
        return chatMutePreferences.mode(accountRef, groupIdHex)
    }

    fun conversationVibrationPattern(groupIdHex: String): ConversationVibrationPattern {
        val accountRef = activeAccountRef ?: return ConversationVibrationPattern.SYSTEM_DEFAULT
        return conversationVibrationPreferences.pattern(accountRef, groupIdHex)
    }

    /** Creates the immutable target channel before making it the persisted active choice. */
    fun setConversationVibrationPattern(
        groupIdHex: String,
        isDm: Boolean,
        conversationTitle: String,
        pattern: ConversationVibrationPattern,
    ) {
        val accountRef = activeAccountRef ?: return
        val shortcutId = conversationShortcutId(accountRef, groupIdHex) ?: return
        mutationsScope.launch {
            // Channel creation performs NotificationManager Binder calls. Keep
            // them off the UI dispatcher and serialize rapid choices so an
            // older operation cannot overwrite the newest persisted choice.
            conversationVibrationChannelMutex.withLock {
                val previous = conversationVibrationPreferences.pattern(accountRef, groupIdHex)
                if (previous == pattern) return@withLock
                val activeChannelId =
                    withContext(Dispatchers.Default) {
                        runCatchingCancellable {
                            NotificationChannels.ensureChannels(appContext)
                            ConversationNotificationChannels.ensureConversationChannel(
                                context = appContext,
                                parentChannelId = ConversationNotificationChannels.primaryMessageParent(isDm).id,
                                conversationShortcutId = shortcutId,
                                conversationTitle = conversationTitle,
                                vibrationPattern = pattern,
                                sourceVibrationPattern = previous,
                            )
                        }.onFailure { error ->
                            appStateDebug(error) {
                                "conversation vibration channel failed group=${groupIdHex.take(8)}"
                            }
                        }.getOrNull()
                    }
                // Never persist an app-only value when Android could not create
                // the channel that future posts and settings links need.
                if (activeChannelId != null) {
                    conversationVibrationPreferences.setPattern(accountRef, groupIdHex, pattern)
                }
            }
        }
    }

    /** The All/Only-mentions preference to show when the chat isn't muted. */
    fun conversationRestoreNotifyMode(groupIdHex: String): ChatNotifyMode {
        val accountRef = activeAccountRef ?: return ChatNotifyMode.ALL
        return chatMutePreferences.restoreNotifyMode(accountRef, groupIdHex)
    }

    fun setConversationNotifyMode(
        groupIdHex: String,
        mode: ChatNotifyMode,
    ) {
        val accountRef = activeAccountRef ?: return
        if (mode == ChatNotifyMode.NONE) {
            setConversationMuted(groupIdHex, true)
        } else {
            chatMutePreferences.setNotifyForMode(accountRef, groupIdHex, mode)
            setConversationMuted(groupIdHex, false)
        }
    }

    /** The engine's durable mute projection for the chat, from the live list. */
    fun engineConversationMuted(groupIdHex: String): Boolean =
        (chatsController?.items.orEmpty() + chatsController?.archivedItems.orEmpty())
            .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
            ?.engineMuted() == true

    internal fun acceptAuthoritativeMuteProjection(
        accountRef: String?,
        groupIdHex: String,
        muted: Boolean,
        mutedUntilMs: Long?,
    ) {
        accountRef ?: return
        val key = ChatMutePreferences.compositeKey(accountRef, groupIdHex)
        val override = authoritativeMuteOverrides[key] ?: return
        val commandPending = (pendingMuteCommands[key] ?: 0) > 0
        if (shouldDropMuteOverride(override, muted, mutedUntilMs, commandPending)) {
            authoritativeMuteOverrides.remove(key)
        }
    }

    fun setConversationNotifyForMode(
        groupIdHex: String,
        mode: ChatNotifyMode,
    ) {
        val accountRef = activeAccountRef ?: return
        chatMutePreferences.setNotifyForMode(accountRef, groupIdHex, mode)
    }

    /** Awaitable batch boundary, serialized by the same repository as single-chat mute commands. */
    @Suppress("ReturnCount") // Owner fences bracket the awaited native command and its committed result.
    internal suspend fun setConversationMutedAwaited(
        account: String,
        groupId: String,
        muted: Boolean,
        isCurrent: () -> Boolean,
    ): Boolean {
        if (!isCurrent()) return false
        val key = ChatMutePreferences.compositeKey(account, groupId)
        pendingMuteCommands[key] = (pendingMuteCommands[key] ?: 0) + 1
        try {
            val result =
                if (muted) {
                    chatMuteRepository.setMuted(account, groupId, null, isCurrent)
                } else {
                    chatMuteRepository.clearMuted(account, groupId, isCurrent)
                }
            if (!isCurrent()) return false
            return result.fold(
                onSuccess = {
                    authoritativeMuteOverrides[key] = it
                    true
                },
                onFailure = { false },
            )
        } finally {
            finishMuteCommand(key)
        }
    }

    fun setConversationMuted(
        groupIdHex: String,
        muted: Boolean,
        onCommitted: () -> Unit = {},
        isCurrent: () -> Boolean = { true },
        completionIsCurrent: () -> Boolean = isCurrent,
    ) {
        val accountRef = activeAccountRef ?: return
        if (muted) {
            submitMuteCommand(accountRef, groupIdHex, null, onCommitted, isCurrent, completionIsCurrent)
        } else {
            submitUnmuteCommand(accountRef, groupIdHex, onCommitted, isCurrent, completionIsCurrent)
        }
    }

    /** Mute the chat for [durationMillis], auto-restoring the current mode after. */
    fun muteConversationFor(
        groupIdHex: String,
        durationMillis: Long,
    ) {
        val accountRef = activeAccountRef ?: return
        val expiry = if (durationMillis <= 0) null else System.currentTimeMillis() + durationMillis
        submitMuteCommand(accountRef, groupIdHex, expiry)
    }

    /** Mute the chat until the exact future Unix epoch-millisecond [expiryMillis]. */
    fun muteConversationUntil(
        groupIdHex: String,
        expiryMillis: Long,
    ) {
        val accountRef = activeAccountRef ?: return
        if (expiryMillis > System.currentTimeMillis()) submitMuteCommand(accountRef, groupIdHex, expiryMillis)
    }

    /** Sends the command to MDK; UI changes only after MDK returns its authoritative settings. */
    @Suppress("LongParameterList") // Admission and committed-result ownership differ after presentation retires.
    private fun submitMuteCommand(
        accountRef: String,
        groupIdHex: String,
        mutedUntilMs: Long?,
        onCommitted: () -> Unit = {},
        isCurrent: () -> Boolean = { true },
        completionIsCurrent: () -> Boolean = isCurrent,
    ) {
        val key = ChatMutePreferences.compositeKey(accountRef, groupIdHex)
        val runtime = runtimeGeneration
        pendingMuteCommands[key] = (pendingMuteCommands[key] ?: 0) + 1
        mutationsScope.launch {
            try {
                val ownsSession = activeAccountRef == accountRef && runtimeGeneration == runtime
                if (!ownsSession || !isCurrent()) return@launch
                chatMuteRepository
                    .setMuted(accountRef, groupIdHex, mutedUntilMs) {
                        activeAccountRef == accountRef && runtimeGeneration == runtime && isCurrent()
                    }.onSuccess {
                        if (activeAccountRef == accountRef && runtimeGeneration == runtime && completionIsCurrent()) {
                            authoritativeMuteOverrides[key] = it
                            onCommitted()
                        }
                    }.onFailure {
                        if (activeAccountRef == accountRef && runtimeGeneration == runtime && isCurrent()) {
                            presentFailure(R.string.toast_couldnt_update_notifications, "CHAT_MUTE_UPDATE", it)
                        }
                    }
            } finally {
                finishMuteCommand(key)
            }
        }
    }

    private fun submitUnmuteCommand(
        accountRef: String,
        groupIdHex: String,
        onCommitted: () -> Unit = {},
        isCurrent: () -> Boolean = { true },
        completionIsCurrent: () -> Boolean = isCurrent,
    ) {
        val key = ChatMutePreferences.compositeKey(accountRef, groupIdHex)
        val runtime = runtimeGeneration
        pendingMuteCommands[key] = (pendingMuteCommands[key] ?: 0) + 1
        mutationsScope.launch {
            try {
                val ownsSession = activeAccountRef == accountRef && runtimeGeneration == runtime
                if (!ownsSession || !isCurrent()) return@launch
                chatMuteRepository
                    .clearMuted(accountRef, groupIdHex) {
                        activeAccountRef == accountRef && runtimeGeneration == runtime && isCurrent()
                    }.onSuccess {
                        if (activeAccountRef == accountRef && runtimeGeneration == runtime && completionIsCurrent()) {
                            authoritativeMuteOverrides[key] = it
                            onCommitted()
                        }
                    }.onFailure {
                        if (activeAccountRef == accountRef && runtimeGeneration == runtime && isCurrent()) {
                            presentFailure(R.string.toast_couldnt_update_notifications, "CHAT_MUTE_UPDATE", it)
                        }
                    }
            } finally {
                finishMuteCommand(key)
            }
        }
    }

    private fun finishMuteCommand(key: String) {
        val remaining = (pendingMuteCommands[key] ?: 0) - 1
        if (remaining <= 0) pendingMuteCommands.remove(key) else pendingMuteCommands[key] = remaining
    }

    private suspend fun migrateLegacyMutePreferences() {
        chatMutePreferences.legacyMuteEntries().forEach { legacy ->
            val authoritative =
                chatMuteRepository.settings(legacy.accountRef, legacy.groupIdHex).getOrNull()
                    ?: return@forEach
            if (legacy.expiryMillis?.let { it <= System.currentTimeMillis() } == true) {
                chatMutePreferences.setNotifyForMode(legacy.accountRef, legacy.groupIdHex, legacy.restoreMode)
                chatMutePreferences.confirmLegacyMuteMigrated(legacy.key)
                return@forEach
            }
            // Existing MDK state always wins. A local-only legacy mute is copied once.
            val confirmed =
                if (authoritative.muted || authoritative.updatedAtMs > 0L) {
                    authoritative
                } else {
                    chatMuteRepository
                        .setMuted(
                            legacy.accountRef,
                            legacy.groupIdHex,
                            legacy.expiryMillis?.takeIf { it > System.currentTimeMillis() },
                        ).getOrNull()
                }
            if (confirmed?.muted == true) {
                chatMutePreferences.setNotifyForMode(
                    legacy.accountRef,
                    legacy.groupIdHex,
                    legacy.restoreMode,
                )
                chatMutePreferences.confirmLegacyMuteMigrated(legacy.key)
            }
        }
    }

    private suspend fun migrateLegacyDrafts() {
        val accountRefsById = accounts.associate { it.accountIdHex to it.label }
        val legacyDrafts = withContext(Dispatchers.IO) { legacyDraftMigrationSource.read() }
        legacyDrafts.forEach { (legacyKey, encoded) ->
            migrateLegacyDraft(accountRefsById, legacyKey, encoded)
        }
    }

    private suspend fun migrateLegacyDraft(
        accountRefsById: Map<String, String>,
        legacyKey: String,
        encoded: String,
    ) {
        val separator = legacyKey.indexOf(' ')
        separator.takeIf { it > 0 && it < legacyKey.lastIndex }?.let { validSeparator ->
            accountRefsById[legacyKey.substring(0, validSeparator)]?.let { accountRef ->
                val groupIdHex = legacyKey.substring(validSeparator + 1)
                val legacyContent = decodeLegacyDraftForMigration(encoded)
                if (legacyContent == null) {
                    confirmLegacyDraftMigrated(legacyKey)
                } else {
                    migrateDecodedLegacyDraft(legacyKey, accountRef, groupIdHex, legacyContent)
                }
            }
        }
    }

    private suspend fun migrateDecodedLegacyDraft(
        legacyKey: String,
        accountRef: String,
        groupIdHex: String,
        legacyContent: String,
    ) {
        val currentResult = messageDraftRepository.draft(accountRef, groupIdHex)
        val current = currentResult.getOrNull()
        if (currentResult.isSuccess) {
            val result =
                if (current == null || (current.content.isBlank() && legacyContent.isNotBlank())) {
                    messageDraftRepository.saveText(accountRef, groupIdHex, legacyContent)
                } else {
                    MessageDraftMutationResult.Success(current)
                }
            (result as? MessageDraftMutationResult.Success)?.let { confirmed ->
                val matchesLegacy = confirmed.draft?.content == legacyContent
                val confirmedBlankDeletion = legacyContent.isBlank() && confirmed.draft == null
                val authoritativeContentWins = current?.content?.isNotBlank() == true
                if (matchesLegacy || confirmedBlankDeletion || authoritativeContentWins) {
                    confirmLegacyDraftMigrated(legacyKey)
                }
            }
        }
    }

    private suspend fun confirmLegacyDraftMigrated(legacyKey: String) {
        withContext(Dispatchers.IO) { legacyDraftMigrationSource.confirmMigrated(legacyKey) }
    }

    suspend fun authoritativeConversationMuteSettings(groupIdHex: String) =
        activeAccountRef?.let { accountRef -> chatMuteRepository.settings(accountRef, groupIdHex).getOrNull() }

    /** Remaining timed-mute expiry (epoch millis) for the chat, or null. */
    fun conversationMuteExpiryMillis(groupIdHex: String): Long? {
        val accountRef = activeAccountRef ?: return null
        val override = authoritativeMuteOverrides[ChatMutePreferences.compositeKey(accountRef, groupIdHex)]
        return if (override != null) {
            effectiveMuteOverride(override, System.currentTimeMillis())?.mutedUntilMs
        } else {
            (chatsController?.items.orEmpty() + chatsController?.archivedItems.orEmpty())
                .firstOrNull { it.group.groupIdHex.equals(groupIdHex, ignoreCase = true) }
                ?.projection
                ?.mutedUntilMs
        }
    }

    fun acknowledgeTtsTrustWarning(enginePackage: String) {
        ttsWarningPreferences.acknowledge(enginePackage)
    }

    fun runtimeTrustForTtsSelectionWarning(enginePackage: String) =
        runtimeTrustForSelectionWarning(
            enginePackage = enginePackage,
            adoptedHandle = ttsResolution?.handle,
            selectedOverride = ttsEnginePreferences.selectedEngine(),
        )

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

    fun hiddenMessageIdsInGroup(
        accountRef: String?,
        groupIdHex: String,
    ): Set<String> {
        val key = MessageHidePreferences.preferenceKey(accountRef, groupIdHex) ?: return emptySet()
        return hiddenMessageIdsByAccountGroup[key]?.value
            ?: MessageHidePreferences.readHiddenMessageIdsByKey(preferences, key)
    }

    /**
     * Hides one message on this device only, persisting it for its account and conversation, and retracts any
     * notification content that carried it (#3323). Returns whether the hide was stored.
     */
    suspend fun hideMessageForMe(
        accountRef: String?,
        groupIdHex: String,
        messageIdHex: String,
    ): Boolean {
        val hidden =
            hiddenMessageMutationMutex.withLock {
                val key = MessageHidePreferences.preferenceKey(accountRef, groupIdHex) ?: return@withLock false
                val updated =
                    withContext(Dispatchers.IO) {
                        MessageHidePreferences.hideMessage(preferences, accountRef, groupIdHex, messageIdHex)
                    }
                        ?: return@withLock false
                val state = hiddenMessageIdsByAccountGroup.getOrPut(key) { mutableStateOf(updated) }
                state.value = updated
                true
            }
        // A successful local hide retracts the message's notification content too (#3323).
        if (hidden && accountRef != null) {
            // The hide is already persisted, so its retraction completes even if the caller is cancelled.
            withContext(NonCancellable) {
                retractNotificationContent(RemovedNotificationMessage(accountRef, groupIdHex, messageIdHex))
            }
        }
        return hidden
    }

    suspend fun clearHiddenMessagesForAccount(accountRef: String): Boolean =
        withContext(NonCancellable) {
            hiddenMessageMutationMutex.withLock {
                val prefix = MessageHidePreferences.accountKeyPrefix(accountRef) ?: return@withLock false
                val cleared =
                    withContext(Dispatchers.IO) {
                        MessageHidePreferences.clearAccount(preferences, accountRef)
                    }
                if (!cleared) return@withLock false
                hiddenMessageIdsByAccountGroup.removeAll { it.startsWith(prefix) }
                true
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
    private fun activeNetworkTypes(): Set<MediaAutoDownloadNetwork> = activeNetworkTypesSnapshot

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
            catchUpAccounts = {
                val pendingGeneration =
                    withContext(pushWakeStorageDispatcher) {
                        observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
                    } ?: 0L
                // Unknown push storage cannot prevent ordinary relay catch-up or authorize marker acknowledgement.
                catchUpAfterObservedPushWake(
                    pendingGeneration = pendingGeneration,
                    trigger = PerformanceTrigger.NETWORK_RECONNECT,
                )
            },
            awaitRetry = { generation, attempt ->
                appStateDebug { "notification network recovery pending attempt=$attempt" }
                awaitNotificationRetryWindow(
                    retryWake = validatedConnectivityRecoveryGeneration,
                    capturedGeneration = generation,
                    backoffMillis = notificationNetworkRecoveryRetryDelayMillis(attempt),
                )
            },
            onRecoveryAttemptStarted = { networkGeneration ->
                observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }?.let { generation ->
                    notificationPushWakeRecoveryCircuit.noteRecoveryAttempt(
                        networkGeneration = networkGeneration,
                        pushWakeGeneration = generation,
                    )
                }
            },
            onRecoveryExhausted = { networkGeneration, _ ->
                notificationPushWakeRecoveryCircuit.noteRecoveryExhausted(networkGeneration)
            },
            onDrainCompleted = ::schedulePendingPushWakeCatchUpDrain,
            diagnostics = notificationNetworkRecoveryDiagnostics,
        )

    private val runtimeListenerTeardownOwner =
        AppRuntimeListenerTeardownOwner(
            cancelNetworkRecovery = { notificationNetworkRecovery.cancelAndJoin() },
            cancelPushWakeDrain = { pushWakeCatchUpDrainJob.cancelAndJoin() },
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
            if (network != null) schedulePendingPushWakeCatchUpDrain()
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
                        // Capabilities arrive in the onCapabilitiesChanged that
                        // follows; until then only the yes/no bit is known and
                        // the empty type set conservatively blocks auto-download.
                        val update = activeDefaultNetwork.available(network.networkHandle)
                        if (update.identityChanged) connectivitySignalOwner.noteNetworkIdentityChange()
                        noteActiveNetworkSnapshot(isOnline = true)
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
        schedulePendingPushWakeCatchUpDrain()
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
        resumePushWakeEpisodeFromLifecycle()
        AvatarLoadRecovery.onNetworkRestored()
        validatedConnectivityRecoveryGenerationMutable.update { generation -> generation + 1 }
        notificationNetworkRecovery.noteNetworkRestored(validatedConnectivityRecoveryGenerationMutable.value)
    }

    /** Starts push-wake catch-up only when network recovery does not own the receiver. */
    private fun schedulePendingPushWakeCatchUpDrain() {
        val pendingPushWakeGeneration =
            observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() } ?: return
        if (pendingPushWakeGeneration != 0L && !networkNotificationRecoverySuppressed) {
            notificationScope.launch(pushWakeStorageDispatcher) { schedulePushWakeRecovery() }
        }
        val networkGeneration = validatedConnectivityRecoveryGenerationMutable.value
        val runtimeUnavailable =
            networkNotificationRecoverySuppressed ||
                pendingPushWakeGeneration == 0L ||
                notificationNetworkRecovery.isActive()
        val triggerAlreadyConsumed =
            !notificationPushWakeRecoveryCircuit.allowsIndependentDrain(
                networkGeneration = networkGeneration,
                pushWakeGeneration = pendingPushWakeGeneration,
            )
        if (runtimeUnavailable || triggerAlreadyConsumed) {
            return
        }
        pushWakeCatchUpDrainJob.startIfInactive {
            notificationScope
                .launch {
                    if (
                        notificationPushWakeRecoveryCircuit.claimIndependentDrain(
                            networkGeneration = networkGeneration,
                            pushWakeGeneration = pendingPushWakeGeneration,
                        )
                    ) {
                        ensureNotificationRuntimeStarted()
                    }
                }.also { drainJob ->
                    drainJob.invokeOnCompletion {
                        val currentPushWakeGeneration =
                            observePushState { pushTokenStore.pendingPushWakeCatchUpGeneration() }
                                ?: return@invokeOnCompletion
                        val currentNetworkGeneration = validatedConnectivityRecoveryGenerationMutable.value
                        if (
                            currentPushWakeGeneration != 0L &&
                            (
                                currentPushWakeGeneration != pendingPushWakeGeneration ||
                                    currentNetworkGeneration != networkGeneration
                            )
                        ) {
                            notificationScope.launch {
                                // Completion can run inline while the job slot lock is held.
                                yield()
                                schedulePendingPushWakeCatchUpDrain()
                            }
                        }
                    }
                }
        }
    }

    /** Reopens an exhausted episode off-main before evaluating process and durable owners. */
    private fun resumePushWakeEpisodeFromLifecycle() {
        notificationScope.launch {
            admitPushWakeEpisodeFromLifecycle()
            schedulePendingPushWakeCatchUpDrain()
        }
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
    private fun loadMediaAutoDownloadMatrix(accountRef: String?): MediaAutoDownloadMatrix {
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
        if (!foreground) transferUnownedPushWakeRecovery()
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
            dictationDiagnosticLifecycle.foreground { conversationDictation }
            NotificationStreamForegroundService.onAppForegrounded(initializedConversationDictation())
            appLockTtsBoundaryJob?.cancel()
            appLockTtsBoundaryJob = null
            maybeShowAppLockForForeground()
            if (dismissRetainedVisibleConversation) {
                dismissVisibleConversationNotifications()
            }
        } else {
            appUnlockSessions.clearForegroundReturn()
            recordAppLockBackgrounded()
            conversationDictation.onAppBackgrounded()
            mutationsScope.launch { draftWriter.flush() }
            scheduleTtsStopAtAppLockBoundary()
        }
        if (foreground) {
            refreshLocalNotificationPermission()
            localNotificationPresenter.reconcileNotificationGroup()
            refreshNotificationBatteryPolicy()
            notificationScope.launch { catchUpAfterForegroundActivation() }
        }
        if (shouldStartBackgroundConnectionForForeground(foreground)) {
            startBackgroundConnectionService()
        }
        if (foreground) notificationScope.launch { reconcileNotificationDeliveryMode() }
        if (!foreground) notificationScope.launch { refreshAppUpdateIfStale(notifyIfNewer = true) }
        if (foreground) notificationScope.launch { refreshAppUpdateIfStale(notifyIfNewer = false) }
        if (foreground) refreshAppSelfUpdateInstallPermission()
    }

    /** Restarts a retained persistent mode only while the app can render its notifications. */
    private fun shouldStartBackgroundConnectionForForeground(foreground: Boolean): Boolean =
        foreground && backgroundConnectionEnabled && localNotificationPermissionGranted

    private fun scheduleTtsStopAtAppLockBoundary() {
        appLockTtsBoundaryJob?.cancel()
        appLockTtsBoundaryJob = null
        if (!requireAppUnlock || !appLockCredentialAvailable) return
        val delayMillis = appLockDelay.delayMillis
        if (delayMillis == 0L) {
            stopSpeaking()
            return
        }
        appLockTtsBoundaryJob =
            mutationsScope.launch {
                delay(delayMillis)
                if (!appInForeground && requireAppUnlock && appLockCredentialAvailable) {
                    stopSpeaking()
                }
            }
    }

    private fun dismissVisibleConversationNotifications() {
        val target =
            visibleConversationDismissalTarget(
                appInForeground = appInForeground,
                appLockScreenVisible = appLockScreenVisible,
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

    /**
     * Sweeps closed conversations across signed-in accounts (#745) on the coarse worker cadence.
     * Expired messages, decrypted L2 media, and stale tray cards are removed without reopening a
     * conversation; [ConversationController.start] handles the open-conversation path.
     *
     * One engine-owned `sweepExpiredRetention` call per account atomically applies clock-skew,
     * unread-anchor, and scan-cap deferrals on its serialized command worker. Android handles
     * tray-card dismissal (#333) and decrypted media-cache eviction (#334) per pruned group.
     *
     * Failures are isolated per account and logged; cancellation propagates. The worker checks
     * MDK's account inventory before starting the notification runtime, and eligible accounts
     * can still bootstrap after process death without a UI.
     */
    suspend fun sweepExpiredDisappearingMessages() {
        ensureNotificationRuntimeStarted()
        if (marmotRuntime == null) return
        val signedInAccounts = accounts.filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
        for (account in signedInAccounts) {
            currentCoroutineContext().ensureActive()
            runRetentionSweep(account.label, System.currentTimeMillis())
        }
    }

    /**
     * Run the engine-owned retention sweep for one account and apply the
     * Android-owned consequences for every pruned group. A group matching
     * [handledGroupIdHex] is skipped and its outcome returned instead, so an
     * open conversation's controller can process its own group against the
     * loaded timeline's precise cache keys. Returns null when the sweep
     * failed or no group matched [handledGroupIdHex].
     */
    internal suspend fun runRetentionSweep(
        accountRef: String,
        nowMillis: Long,
        handledGroupIdHex: String? = null,
    ): RetentionSweepGroupOutcomeFfi? {
        val report =
            runCatchingCancellable { marmotIo { sweepExpiredRetention(accountRef, nowMillis.toULong()) } }
                .onFailure {
                    appStateDebug(it) { "retention sweep failed acct=${accountRef.take(8)}: ${it.readableMessage()}" }
                }.getOrNull()
                ?: return null
        var handledOutcome: RetentionSweepGroupOutcomeFfi? = null
        for (outcome in report.groups) {
            currentCoroutineContext().ensureActive()
            // Keep the old per-group signal: a persistently deferring or
            // failing group must not be silent just because the account call
            // succeeded.
            if (outcome.status != RetentionSweepStatusFfi.PRUNED &&
                outcome.status != RetentionSweepStatusFfi.NO_EXPIRED_MESSAGES
            ) {
                appStateDebug {
                    val kind = outcome.failureKind?.let { " kind=$it" }.orEmpty()
                    "retention sweep ${outcome.status} group=${outcome.groupIdHex.take(8)}$kind"
                }
            }
            // Case-insensitive like every other hex-id comparison here: a
            // casing drift must not reroute the open group to the fail-closed
            // whole-slice path.
            if (handledGroupIdHex != null && outcome.groupIdHex.equals(handledGroupIdHex, ignoreCase = true)) {
                handledOutcome = outcome
                continue
            }
            processRetentionSweepOutcome(accountRef, outcome)
        }
        return handledOutcome
    }

    private suspend fun processRetentionSweepOutcome(
        accountRef: String,
        outcome: RetentionSweepGroupOutcomeFfi,
    ) {
        // When the engine actually pruned rows, clear the conversation's tray
        // card so it can't keep pointing at a now-vanished message.
        if (outcome.prunedMessages > 0uL) {
            dismissConversationNotifications(accountRef, outcome.groupIdHex)
        }
        val expiredCiphertextSha256 = outcome.mediaCiphertextSha256.toSet()
        if (expiredCiphertextSha256.isEmpty()) return
        // The engine ran gate and prune atomically, so no pre-prune media rows
        // exist to map ciphertext tags to exact cache keys. Fail closed for
        // the in-memory tier: drop the group's whole L1 slice — decrypted
        // bytes must not outlive the retention window, and a closed
        // conversation re-hydrates lazily from disk. The disk tier evicts
        // precisely by its persisted ciphertext tag, independent of load
        // state.
        withContext(Dispatchers.Main.immediate) {
            mediaMemoryCacheKeysSnapshot()
                .filter { mediaCacheKeyInGroup(it, accountRef, outcome.groupIdHex) }
                .forEach(::removeMediaMemoryCacheEntry)
        }
        withContext(Dispatchers.IO) {
            diskMediaCache.removeByCiphertextTags(expiredCiphertextSha256)
        }
    }

    fun refreshLocalNotificationPermission() {
        localNotificationPermissionGranted = localNotificationPresenter.canPostNotifications()
    }

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

    fun openZapstoreListing(context: Context = appContext) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(AppUpdateConstants.ZAPSTORE_LISTING_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            present(R.string.toast_zapstore_unavailable)
        }
    }

    fun handleAppUpdateAction(context: Context = appContext) {
        // Builds that don't self-update (the Google Play distribution) leave
        // updates entirely to the distributing store — no in-app flow and no
        // off-store listing redirect, which Play policy forbids.
        val latest = appUpdateInfo.latestVersion
        if (
            latest != null &&
            shouldStartInAppSelfUpdate(
                selfUpdateEnabled = BuildConfig.SELF_UPDATE_ENABLED,
                installedVersion = appUpdateInfo.installedVersion,
                targetVersion = latest,
            )
        ) {
            startAppSelfUpdate(latest)
            return
        }
        if (BuildConfig.SELF_UPDATE_ENABLED && latest == null) openZapstoreListing(context)
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
            notificationDeliveryModeMutex.withLock {
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
            localNotificationSettings = settings
        }
    }

    internal suspend fun sendNotificationReply(
        accountRef: String,
        groupIdHex: String,
        afterMessageIdHex: String,
        text: String,
        completionStore: NotificationReplyCompletionStore,
        completionKey: String,
        recoveryScope: String,
    ): NotificationReplySendOutcome {
        val account = accountRef.takeIf { it.isNotBlank() } ?: return NotificationReplySendOutcome.NonRetryableFailure
        val group = groupIdHex.takeIf { it.isNotBlank() } ?: return NotificationReplySendOutcome.NonRetryableFailure
        if (!ConversationController.HEX_MESSAGE_ID.matches(afterMessageIdHex)) {
            return NotificationReplySendOutcome.NonRetryableFailure
        }
        val body = text.trim().takeIf { it.isNotEmpty() } ?: return NotificationReplySendOutcome.NonRetryableFailure
        return runCatchingCancellable {
            withGroupCommitLock(account, group) {
                val recoveryLookup =
                    withContext(Dispatchers.IO) {
                        completionStore.recoveryLookup(completionKey)
                    }
                val recoverySnapshot =
                    when (recoveryLookup) {
                        NotificationReplyRecoveryLookup.NotStarted -> null
                        NotificationReplyRecoveryLookup.Indeterminate ->
                            return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
                        is NotificationReplyRecoveryLookup.Ready -> recoveryLookup.snapshot
                    }
                if (recoverySnapshot != null) {
                    when (
                        notificationReplyCommitState(
                            account = account,
                            group = group,
                            recoveryState = recoverySnapshot.recoveryState,
                            nextAttemptBoundary = recoverySnapshot.nextAttemptBoundary,
                            text = body,
                        )
                    ) {
                        NotificationReplyCommitProbe.Committed ->
                            return@withGroupCommitLock NotificationReplySendOutcome.AlreadyCommitted
                        NotificationReplyCommitProbe.Indeterminate ->
                            return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
                        NotificationReplyCommitProbe.NotCommitted -> Unit
                    }
                }

                // A synthetic max-id cursor excludes every row in this wall-clock
                // second. Wait for the next second before asking MDK to build the
                // event so its random event id cannot sort below the lower bound.
                val recoveryBoundary = notificationReplyRecoveryBoundary(System.currentTimeMillis())
                val persistedBoundary =
                    withContext(Dispatchers.IO) {
                        completionStore.markStarted(completionKey, recoveryScope, recoveryBoundary)
                    }
                if (persistedBoundary == null) return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
                while (!notificationReplySendWindowReady(persistedBoundary, System.currentTimeMillis())) {
                    currentCoroutineContext().ensureActive()
                    delay(NOTIFICATION_REPLY_SEND_WINDOW_POLL_MILLIS)
                }

                val summary = marmotIo(MarmotTraceSection.TEXT_SEND) { sendText(account, group, body) }
                // MDK assigns the app-event id before deciding whether this call
                // can publish it immediately. Persist it before returning either
                // outcome: after a process death, it is our durable proof that an
                // accepted-pending quick reply must never be sent a second time.
                val committedMessageId =
                    summary.messageIds
                        .firstOrNull()
                        ?.takeIf { ConversationController.HEX_MESSAGE_ID.matches(it) }
                        ?: return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
                val committed =
                    withContext(Dispatchers.IO) {
                        completionStore.markCommittedMessage(completionKey, committedMessageId)
                    }
                if (!committed) return@withGroupCommitLock NotificationReplySendOutcome.RetryableFailure
                if (summary.acceptDisposition == SendAcceptDispositionFfi.ACCEPTED_PENDING) {
                    NotificationReplySendOutcome.AcceptedPending
                } else {
                    NotificationReplySendOutcome.Sent
                }
            }
        }.onFailure {
            appStateDebug(it) { "notification reply failed for group=${group.take(8)}: ${it.readableMessage()}" }
        }.getOrElse(::notificationReplySendFailureOutcome)
    }

    /** Sends a notification quick reaction and reports MDK's accept disposition, not an assumed publication. */
    internal suspend fun sendNotificationReaction(
        accountRef: String,
        groupIdHex: String,
        messageIdHex: String,
        reaction: String,
    ): NotificationReactionSendOutcome {
        val emoji = normalizeNotificationReaction(reaction)
        val validTarget =
            accountRef.isNotBlank() &&
                groupIdHex.isNotBlank() &&
                ConversationController.HEX_MESSAGE_ID.matches(messageIdHex)
        if (!validTarget || emoji == null) {
            return NotificationReactionSendOutcome.NonRetryableFailure
        }
        return runCatchingCancellable {
            withGroupCommitLock(accountRef, groupIdHex) {
                val summary =
                    marmotIo(MarmotTraceSection.MESSAGE_REACT) {
                        reactToMessage(accountRef, groupIdHex, messageIdHex, emoji)
                    }
                notificationReactionOutcome(summary.acceptDisposition)
            }
        }.onFailure {
            appStateDebug(it) {
                "notification reaction failed for group=${groupIdHex.take(8)}: ${it.readableMessage()}"
            }
        }.getOrElse(::notificationReactionSendFailureOutcome)
    }

    private suspend fun notificationReplyCommitState(
        account: String,
        group: String,
        recoveryState: NotificationReplyRecoveryState,
        nextAttemptBoundary: NotificationReplyRecoveryBoundary?,
        text: String,
    ): NotificationReplyCommitProbe {
        if (!ConversationController.HEX_MESSAGE_ID.matches(recoveryState.boundary.messageIdHex)) {
            return NotificationReplyCommitProbe.Indeterminate
        }
        if (
            nextAttemptBoundary != null &&
            !ConversationController.HEX_MESSAGE_ID.matches(nextAttemptBoundary.messageIdHex)
        ) {
            return NotificationReplyCommitProbe.Indeterminate
        }
        return runCatchingCancellable {
            probeNotificationReplyCommit(
                recoveryState = recoveryState,
                nextAttemptBoundary = nextAttemptBoundary,
                text = text,
            ) { after, limit ->
                val page =
                    marmotIo {
                        timelineMessages(
                            account,
                            TimelineMessageQueryFfi(
                                groupIdHex = group,
                                search = null,
                                before = null,
                                beforeMessageId = null,
                                after = after.timelineAt,
                                afterMessageId = after.messageIdHex,
                                limit = limit,
                            ),
                        )
                    }
                NotificationReplyTimelinePage(
                    records =
                        page.messages.map { record ->
                            NotificationReplyTimelineRecord(
                                timelineAt = record.timelineAt,
                                messageIdHex = record.messageIdHex,
                                sourceMessageIdHex = record.sourceMessageIdHex,
                                direction = record.direction,
                                plaintext = record.plaintext,
                            )
                        },
                    hasMoreAfter = page.hasMoreAfter,
                )
            }
        }.onFailure {
            appStateDebug(it) { "notification reply dedupe probe failed for group=${group.take(8)}: ${it.readableMessage()}" }
        }.getOrDefault(NotificationReplyCommitProbe.Indeterminate)
    }

    suspend fun markNotificationMessageRead(
        accountRef: String,
        groupIdHex: String,
        messageIdHex: String,
    ): Boolean {
        val account = accountRef.takeIf { it.isNotBlank() } ?: return false
        val group = groupIdHex.takeIf { it.isNotBlank() } ?: return false
        val message = messageIdHex.takeIf { ConversationController.HEX_MESSAGE_ID.matches(it) } ?: return false
        return runCatchingCancellable {
            // markTimelineMessageRead advances the persisted cursor monotonically;
            // an old notification tap must not move the read marker backwards.
            val row = marmotIo { markTimelineMessageRead(account, group, message) }
            if (!applyChatListRowFromMarkRead(account, row)) {
                reconcileAccountUnreadAfterNotificationMarkRead(account, cursorAdvanced = row != null)
            }
            true
        }.onFailure {
            if (BuildConfig.DEBUG) Log.w("DMAppState", "notification mark read failed", it)
        }.getOrDefault(false)
    }

    /**
     * Reconcile a background [accountRef]'s per-account unread aggregate after
     * a successful notification-action mark-read whose authoritative row could
     * not fold into a bound [ChatsController]. Without this, the acting
     * account's avatar dot keeps presenting the stale pre-action count until an
     * unrelated refresh happens to land.
     *
     * When the read cursor provably advanced, the stored CONFIRMED value is
     * known-stale: retain the count but stop presenting it, so a failed refresh
     * degrades honestly instead of keeping a stale dot lit. Reconciliation then
     * reuses the same coalesced refresh path inbound notification updates use,
     * which publishes the authoritative post-action projection for exactly this
     * account and never touches other accounts' values.
     */
    private fun reconcileAccountUnreadAfterNotificationMarkRead(
        accountRef: String,
        cursorAdvanced: Boolean,
    ) {
        // The active account's projection is owned by its bound (or rebinding)
        // ChatsController and, on the notification-tap open path, by the
        // account-switch snapshot staging — both publish the authoritative
        // value themselves. Reconciling here would only add redundant refresh
        // traffic to the open path; the gap this closes is the account that has
        // no such owner: a background account acted on from the shade.
        if (accountRef == activeAccountRef) return
        if (cursorAdvanced) accountUnreadStore.markUnknown(accountRef)
        // Honor the same destructive-teardown suppression as inbound-update
        // maintenance: a wipe owns listener and scheduler restarts, and the
        // post-restore lifecycle refresh reconciles the retained value.
        if (networkNotificationRecoverySuppressed) return
        unreadRefreshScheduler.schedule(accountRef)
    }

    suspend fun setLocalNotificationsEnabled(enabled: Boolean): Boolean {
        val account = activeAccountRef ?: return false
        if (!enabled) nativePushFallback.invalidateAll()
        refreshLocalNotificationPermission()
        if (enabled && !localNotificationPermissionGranted) {
            present(R.string.toast_notification_permission_needed)
            return false
        }
        return runCatching {
            val settings = marmotIo { setLocalNotificationsEnabled(account, enabled) }
            localNotificationSettings = settings
            if (!enabled) {
                updateBackgroundConnectionPreference(false)
                NotificationStreamForegroundService.stop(appContext)
            }
            appStateDebug {
                "local notifications account=${account.take(8)} enabled=${settings.localNotificationsEnabled} permission=$localNotificationPermissionGranted"
            }
            presentTransient(
                if (enabled) {
                    R.string.toast_local_notifications_enabled
                } else {
                    R.string.toast_local_notifications_disabled
                },
            )
            true
        }.getOrElse {
            rethrowIfCancellation(it)
            presentFailure(R.string.toast_couldnt_update_notifications, "NOTIFICATION_SETTINGS_UPDATE", it)
            false
        }
    }

    /** Projects the single delivery choice from the existing native and global runtime truths. */
    internal fun notificationDeliveryMode(): NotificationDeliveryMode =
        notificationDeliveryMode(
            settings = localNotificationSettings,
            persistentConnectionEnabled = backgroundConnectionEnabled,
            nativePushCapability = nativePushCapability(),
        )

    /** Refreshes Android's live battery policy only on an explicit lifecycle or UI edge. */
    fun refreshNotificationBatteryPolicy() {
        notificationBatteryPolicy = readNotificationBatteryPolicy(appContext)
    }

    /**
     * Applies one latest-wins delivery choice. Replacement delivery is confirmed before the prior
     * path is disabled, and the existing native/global settings remain the only durable truth.
     */
    internal suspend fun setNotificationDeliveryMode(mode: NotificationDeliveryMode): Boolean = applyMode(mode, true)

    /** Applies the transport and enables rendering only for an explicit selection. */
    private suspend fun applyMode(
        mode: NotificationDeliveryMode,
        enableRendering: Boolean,
    ): Boolean {
        val intentGeneration = notificationDeliveryModeIntent.advance()
        if (mode == NotificationDeliveryMode.Local) nativePushFallback.invalidateAll()
        notificationDeliveryModeBusy = true
        return try {
            notificationDeliveryModeMutex.withLock {
                if (!notificationDeliveryModeIntent.isCurrent(intentGeneration)) return@withLock false
                refreshLocalNotificationPermission()
                if (!localNotificationPermissionGranted) {
                    present(R.string.toast_notification_permission_needed)
                    return@withLock false
                }
                val owner = captureNotificationDeliveryModeOwner(intentGeneration) ?: return@withLock false
                if (enableRendering && !ensureNotificationRenderingEnabled(owner)) return@withLock false
                when (mode) {
                    NotificationDeliveryMode.Fcm -> selectFcmDelivery(owner, explicit = true)
                    NotificationDeliveryMode.Local -> selectPersistentDelivery(owner)
                }
            }
        } finally {
            finishNotificationDeliveryModeTransaction(intentGeneration)
        }
    }

    /** Enables the shared rendering prerequisite while the delivery intent and account stay current. */
    private suspend fun ensureNotificationRenderingEnabled(owner: NotificationDeliveryModeOwner): Boolean {
        val alreadyEnabled = localNotificationSettings?.localNotificationsEnabled == true
        return when {
            alreadyEnabled -> ownsNotificationDeliveryMode(owner)
            else -> {
                val settings =
                    runCatchingCancellable {
                        withContext(Dispatchers.IO) {
                            owner.runtime.marmot.setLocalNotificationsEnabled(owner.activeAccountRef, true)
                        }
                    }.onFailure {
                        presentFailure(R.string.toast_couldnt_enable_notifications, "NOTIFICATION_ENABLE", it)
                    }.getOrNull()
                val stillOwned = settings != null && ownsNotificationDeliveryMode(owner)
                settings?.takeIf { stillOwned }?.let { localNotificationSettings = it }
                stillOwned && settings?.localNotificationsEnabled == true
            }
        }
    }

    /** Registers every notification-enabled account before the device-wide persistent path is stopped. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun selectFcmDelivery(
        owner: NotificationDeliveryModeOwner,
        explicit: Boolean = false,
    ): Boolean {
        val config = pushServerConfigProvider() ?: return false
        if (!nativePushCapability(config).isAvailable) return false
        if (explicit && !clearExplicitPendingDisable(owner)) return false
        val previousPersistent = backgroundConnectionEnabled
        val previous = Collections.synchronizedMap(linkedMapOf<String, NotificationSettingsFfi>())
        val persistentStopAttempted = AtomicBoolean(false)
        return try {
            if (!withContext(pushWakeStorageDispatcher) { pushTokenStore.registrationAllowed() }) return false
            val token = fetchFcmTokenOrNull() ?: return false
            val registered = enableNativeDeliveryForAccounts(owner, config, token, previous)
            if (!registered) {
                rollbackNativePushMode(owner, previous)
                return false
            }
            val preferenceStopped =
                withContext(Dispatchers.IO) {
                    nativePushFallbackPlatform.persistBackgroundConnectionEnabled(false) {
                        ownsNotificationDeliveryMode(owner)
                    }
                }
            if (!preferenceStopped || !ownsNotificationDeliveryMode(owner)) {
                settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
                return false
            }
            backgroundConnectionEnabled = false
            nativePushFallback.invalidateAll()
            val persistentStopped =
                withContext(Dispatchers.IO) {
                    persistentStopAttempted.set(true)
                    nativePushFallbackPlatform.stopBackgroundConnection()
                }
            if (!persistentStopped || !ownsNotificationDeliveryMode(owner)) {
                settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
                false
            } else {
                true
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
            }
            throw cancelled
        } catch (failure: Throwable) {
            appStateDebug(failure) { "native notification selection failed" }
            withContext(NonCancellable) {
                settleFailedFcmDelivery(owner, previousPersistent, previous, persistentStopAttempted.get())
            }
            false
        }
    }

    private suspend fun clearExplicitPendingDisable(owner: NotificationDeliveryModeOwner): Boolean {
        val cleared =
            nativePushSyncMutex.withLock {
                withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPendingDisable(owner.activeAccountRef) }
            }
        return cleared && ownsNotificationDeliveryMode(owner)
    }

    /** Registers at most three accounts together while the native sync lock fences other mutations. */
    private suspend fun enableNativeDeliveryForAccounts(
        owner: NotificationDeliveryModeOwner,
        config: PushServerConfig,
        token: String,
        previous: MutableMap<String, NotificationSettingsFfi>,
    ): Boolean =
        nativePushSyncMutex.withLock {
            val orderedAccounts =
                listOf(owner.activeAccountRef) + owner.accountRefs.filterNot { it == owner.activeAccountRef }
            orderedAccounts.chunked(FCM_REGISTRATION_BATCH_SIZE).all { batch ->
                val ready =
                    coroutineScope {
                        batch
                            .map { async { configureNativeDeliveryForAccount(owner, it, config, token, previous) } }
                            .awaitAll()
                            .all { it }
                    }
                ready && ownsNotificationDeliveryMode(owner)
            }
        }

    /** Applies one account's authoritative notification preference and confirms its registration. */
    @Suppress("ReturnCount")
    private suspend fun configureNativeDeliveryForAccount(
        owner: NotificationDeliveryModeOwner,
        account: String,
        config: PushServerConfig,
        token: String,
        previous: MutableMap<String, NotificationSettingsFfi>,
    ): Boolean {
        if (!ownsNotificationDeliveryMode(owner)) return false
        val settings = withContext(Dispatchers.IO) { owner.runtime.marmot.notificationSettings(account) }
        if (!ownsNotificationDeliveryMode(owner)) return false
        previous[account] = settings
        val desiredNative = settings.localNotificationsEnabled
        if (desiredNative && account in pushTokenStore.pendingDisables()) return false
        val updated =
            if (settings.nativePushEnabled == desiredNative) {
                settings
            } else {
                withContext(Dispatchers.IO) {
                    owner.runtime.marmot.setNativePushEnabled(account, desiredNative)
                }
            }
        if (!ownsNotificationDeliveryMode(owner) || updated.nativePushEnabled != desiredNative) return false
        if (account == owner.activeAccountRef) localNotificationSettings = updated
        return !desiredNative || syncPushForNotificationDeliveryOwner(owner, account, config, token)
    }

    /** Waits for the exact foreground-service acknowledgement before disabling native delivery. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private suspend fun selectPersistentDelivery(owner: NotificationDeliveryModeOwner): Boolean {
        val previousPersistent = backgroundConnectionEnabled
        val nativeDisableCommitted = AtomicBoolean(false)
        return try {
            val fallbackOwner =
                establishPersistentMode(owner) ?: run {
                    restorePersistentPreference(owner, previousPersistent)
                    return false
                }
            val disabled = disableNativePushForPersistentDelivery(owner, fallbackOwner, nativeDisableCommitted)
            if (!disabled && ownsNotificationDeliveryMode(owner)) {
                // Persistent delivery remains established when a per-account mutation fails; the
                // unresolved account can be retried without creating a no-transport window.
                return false
            }
            disabled
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                settleFailedPersistentDelivery(owner, previousPersistent, nativeDisableCommitted.get())
            }
            throw cancelled
        } catch (failure: Throwable) {
            appStateDebug(failure) { "persistent notification selection failed" }
            settleFailedPersistentDelivery(owner, previousPersistent, nativeDisableCommitted.get())
            false
        }
    }

    /** Returns the exact acknowledged service owner or leaves both transports unchanged on rejection. */
    private suspend fun establishPersistentMode(modeOwner: NotificationDeliveryModeOwner): NativePushFallbackOwner? {
        val owner = captureNativePushFallbackOwner() ?: return null
        val ready =
            nativePushFallback.establishPersistentDelivery(
                owner = owner,
                ownerIsCurrent = {
                    ownsNotificationDeliveryMode(modeOwner) && ownsNativePushFallback(owner)
                },
                publishEnabled = {
                    if (ownsNotificationDeliveryMode(modeOwner) && ownsNativePushFallback(owner)) {
                        backgroundConnectionEnabled = true
                    }
                },
                onFailure = { failure ->
                    appStateDebug(failure) { "persistent notification delivery failed" }
                },
            )
        return owner.takeIf {
            ready &&
                ownsNotificationDeliveryMode(modeOwner) &&
                ownsNativePushFallback(owner)
        }
    }

    /** Disables native delivery for every captured signed-in account behind the acknowledged service. */
    @Suppress("ReturnCount")
    private suspend fun disableNativePushForPersistentDelivery(
        modeOwner: NotificationDeliveryModeOwner,
        fallbackOwner: NativePushFallbackOwner,
        nativeDisableCommitted: AtomicBoolean,
    ): Boolean {
        return nativePushSyncMutex.withLock {
            for (account in modeOwner.accountRefs) {
                if (!ownsNotificationDeliveryMode(modeOwner) || !nativePushFallback.isReady(fallbackOwner)) {
                    return@withLock false
                }
                val cleanupQueued =
                    withContext(Dispatchers.IO) {
                        nativePushFallbackPlatform.recordPendingRegistrationClear(account)
                    }
                if (!cleanupQueued || !ownsNotificationDeliveryMode(modeOwner)) return@withLock false
                perAccountSyncedFingerprints.remove(account)
                val settings =
                    withContext(Dispatchers.IO) {
                        modeOwner.runtime.marmot.setNativePushEnabled(account, false).also { updated ->
                            if (!updated.nativePushEnabled) nativeDisableCommitted.set(true)
                        }
                    }
                if (!ownsNotificationDeliveryMode(modeOwner) || settings.nativePushEnabled) return@withLock false
                if (account == modeOwner.activeAccountRef) localNotificationSettings = settings
                clearPushRegistrationForNotificationDeliveryOwnerLocked(modeOwner, account)
                if (!ownsNotificationDeliveryMode(modeOwner)) return@withLock false
            }
            true
        }
    }

    /**
     * Repairs legacy dual-on and capability-loss states without changing rendering preferences.
     * Capability restoration never changes a settled persistent-delivery choice.
     */
    private suspend fun reconcileNotificationDeliveryMode(): Boolean {
        refreshLocalNotificationPermission()
        val settings = localNotificationSettings
        if (!localNotificationPermissionGranted) return pauseNotificationDeliveryReconciliation()
        return if (settings == null) {
            syncNativePushRegistrationIfEnabled()
        } else {
            val desired =
                if (settings.nativePushEnabled && !backgroundConnectionEnabled && nativePushCapability().isAvailable) {
                    NotificationDeliveryMode.Fcm
                } else {
                    NotificationDeliveryMode.Local
                }
            applyMode(desired, enableRendering = false)
        }
    }

    /** Reconciles the captured owner without changing its rendering preference or blocking activation. */
    @Suppress("ReturnCount") // Permission and owner-invalidated paths terminate before transport reconciliation.
    private suspend fun reconcileNotificationDeliveryModeAfterActivation(
        includeBackgroundAccountModes: Boolean,
    ): Boolean {
        refreshLocalNotificationPermission()
        val settings = localNotificationSettings
        if (!localNotificationPermissionGranted) return pauseNotificationDeliveryReconciliation()
        if (settings == null) return syncNativePushRegistrationIfEnabled()
        val intentGeneration = notificationDeliveryModeIntent.advance()
        var handedOff = false
        return try {
            notificationDeliveryModeMutex.withLock {
                if (!notificationDeliveryModeIntent.isCurrent(intentGeneration)) return@withLock false
                val owner = captureNotificationDeliveryModeOwner(intentGeneration) ?: return@withLock false
                val plan = notificationDeliveryActivationPlan(owner, includeBackgroundAccountModes)
                if (!ownsNotificationDeliveryMode(owner)) return@withLock false
                if (plan == null) return@withLock false
                if (!plan.requiresDeviceWideReconciliation) return@withLock true
                launchNotificationDeliveryModeReconciliation(
                    owner = owner,
                    mode = plan.mode,
                    applyDeviceWideMode = true,
                )
                handedOff = true
                true
            }
        } finally {
            if (!handedOff) finishNotificationDeliveryModeTransaction(intentGeneration)
        }
    }

    /** Resolves the device mode from authoritative account settings and identifies any incomplete invariant. */
    private suspend fun notificationDeliveryActivationPlan(
        owner: NotificationDeliveryModeOwner,
        includeBackgroundAccountModes: Boolean,
    ): NotificationDeliveryActivationPlan? {
        val settingsByAccount = readNotificationDeliverySettings(owner) ?: return null
        val anyNativeEnabled = settingsByAccount.values.any { it.nativePushEnabled }
        val mode =
            resolvedNotificationDeliveryMode(anyNativeEnabled, backgroundConnectionEnabled, nativePushCapability())
        val noTransportConfigured = !backgroundConnectionEnabled && !anyNativeEnabled
        return NotificationDeliveryActivationPlan(
            mode = mode,
            requiresDeviceWideReconciliation =
                includeBackgroundAccountModes ||
                    noTransportConfigured ||
                    !notificationDeliveryInvariantMatches(
                        mode,
                        settingsByAccount,
                        backgroundConnectionEnabled,
                        pushWakeServiceOwner != null,
                        perAccountSyncedFingerprints.keys,
                    ),
        )
    }

    /** Reads every captured account without treating an unavailable setting as disabled. */
    @Suppress("ReturnCount") // Missing settings, read failure, and owner invalidation abort the bounded scan.
    private suspend fun readNotificationDeliverySettings(
        owner: NotificationDeliveryModeOwner,
    ): Map<String, NotificationSettingsFfi>? {
        val settingsByAccount = linkedMapOf<String, NotificationSettingsFfi>()
        for (account in owner.accountRefs) {
            val settings =
                if (account == owner.activeAccountRef) {
                    localNotificationSettings
                } else {
                    runCatchingCancellable {
                        withContext(Dispatchers.IO) { owner.runtime.marmot.notificationSettings(account) }
                    }.onFailure {
                        appStateDebug(it) { "notification activation settings read failed" }
                    }.getOrNull()
                }
            if (!ownsNotificationDeliveryMode(owner)) return null
            settingsByAccount[account] = settings ?: return null
        }
        return settingsByAccount
    }

    /** Reuses safe capability sync for restoration and reserves device-wide cutover for a real account switch. */
    private fun launchNotificationDeliveryModeReconciliation(
        owner: NotificationDeliveryModeOwner,
        mode: NotificationDeliveryMode,
        applyDeviceWideMode: Boolean,
    ) {
        notificationDeliveryModeBusy = true
        notificationScope.launch {
            try {
                notificationDeliveryModeMutex.withLock {
                    if (!ownsNotificationDeliveryMode(owner)) return@withLock
                    if (applyDeviceWideMode) {
                        if (mode == NotificationDeliveryMode.Local) nativePushFallback.invalidateAll()
                        when (mode) {
                            NotificationDeliveryMode.Fcm -> selectFcmDelivery(owner)
                            NotificationDeliveryMode.Local -> selectPersistentDelivery(owner)
                        }
                    } else {
                        syncNativePushRegistrationIfEnabled()
                    }
                }
            } finally {
                finishNotificationDeliveryModeTransaction(owner.intentGeneration)
            }
        }
    }

    /** Clears the latest cutover and drains token work that arrived while it owned the mode lock. */
    private suspend fun finishNotificationDeliveryModeTransaction(epoch: Long) =
        withContext(NonCancellable) {
            if (!notificationDeliveryModeIntent.isCurrent(epoch)) return@withContext
            notificationDeliveryModeMutex.withLock {
                val latest = notificationDeliveryModeIntent.runIfCurrent(epoch) { notificationDeliveryModeBusy = false }
                val pending =
                    latest && runCatching { pushTokenStore.nativePushRegistrationSyncPending() }.getOrDefault(false)
                if (pending) syncNativePushRegistrationIfEnabled()
            }
        }

    /** Captures every lifetime that can invalidate a device-wide delivery cutover. */
    @Suppress("ReturnCount")
    private fun captureNotificationDeliveryModeOwner(intentGeneration: Long): NotificationDeliveryModeOwner? {
        val account = activeAccountRef ?: return null
        val runtime = marmotRuntime ?: return null
        val accountRefs =
            accounts
                .filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
                .map { it.label }
        if (account !in accountRefs) return null
        return NotificationDeliveryModeOwner(
            activeAccountRef = account,
            accountRefs = accountRefs,
            runtime = runtime,
            runtimeGeneration = runtimeGeneration,
            accountSwitchGeneration = accountSwitchHandoff.capture(),
            intentGeneration = intentGeneration,
        )
    }

    /** Rejects account switches (including A-B-A), sign-in changes, runtime replacement, and newer intent. */
    private fun ownsNotificationDeliveryMode(owner: NotificationDeliveryModeOwner): Boolean =
        notificationDeliveryModeIntent.isCurrent(owner.intentGeneration) &&
            activeAccountRef == owner.activeAccountRef &&
            marmotRuntime === owner.runtime &&
            runtimeGeneration == owner.runtimeGeneration &&
            accountSwitchHandoff.isCurrent(owner.accountSwitchGeneration) &&
            accounts
                .filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
                .map { it.label } == owner.accountRefs

    /** Registers one account using the runtime captured by the surrounding mode transaction. */
    @Suppress("ReturnCount")
    private suspend fun syncPushForNotificationDeliveryOwner(
        owner: NotificationDeliveryModeOwner,
        account: String,
        config: PushServerConfig,
        token: String,
    ): Boolean {
        val fingerprint =
            PushFingerprint(
                platform = PushPlatformFfi.FCM,
                token = token,
                serverPubkeyHex = config.serverPubkeyHex,
                relayHint = config.relayHint,
            )
        if (perAccountSyncedFingerprints[account] == fingerprint) return ownsNotificationDeliveryMode(owner)
        val outcome =
            withContext(Dispatchers.IO) {
                if (!pushTokenStore.registrationAllowed()) throw PushTokenStorageException()
                owner.runtime.marmot.upsertPushRegistration(
                    accountRef = account,
                    platform = PushPlatformFfi.FCM,
                    rawToken = token,
                    serverPubkeyHex = config.serverPubkeyHex,
                    relayHint = config.relayHint,
                )
            }
        logPushRegistrationShareOutcome("delivery mode upsert", account, outcome.share)
        if (!ownsNotificationDeliveryMode(owner)) return false
        val settingsAfter =
            withContext(Dispatchers.IO) {
                owner.runtime.marmot.notificationSettings(account)
            }
        if (!ownsNotificationDeliveryMode(owner) || !settingsAfter.nativePushEnabled) return false
        perAccountSyncedFingerprints[account] = fingerprint
        return true
    }

    /** Clears one registration while the caller holds [nativePushSyncMutex]. */
    private suspend fun clearPushRegistrationForNotificationDeliveryOwnerLocked(
        owner: NotificationDeliveryModeOwner,
        account: String,
    ) {
        val result =
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    owner.runtime.marmot.clearPushRegistration(account)
                }
            }
        result.onSuccess { outcome ->
            logPushRegistrationShareOutcome("delivery mode clear", account, outcome)
            if (ownsNotificationDeliveryMode(owner)) {
                withContext(Dispatchers.IO) { pushTokenStore.clearPending(account) }
            }
        }
        result.exceptionOrNull()?.let { failure ->
            appStateDebug(failure) { "delivery mode registration clear remains pending" }
        }
    }

    /** Restores only mutations made by this transaction while no newer user choice owns delivery. */
    private suspend fun rollbackNativePushMode(
        owner: NotificationDeliveryModeOwner,
        previous: Map<String, NotificationSettingsFfi>,
    ) {
        nativePushSyncMutex.withLock {
            if (!ownsNotificationDeliveryMode(owner)) return@withLock
            previous.forEach { (account, settings) ->
                if (!ownsNotificationDeliveryMode(owner)) return@withLock
                if (settings.nativePushEnabled && account in pushTokenStore.pendingDisables()) return@forEach
                val restored =
                    runCatchingCancellable {
                        withContext(Dispatchers.IO) {
                            owner.runtime.marmot.setNativePushEnabled(account, settings.nativePushEnabled)
                        }
                    }.getOrNull() ?: return@forEach
                if (account == owner.activeAccountRef && ownsNotificationDeliveryMode(owner)) {
                    localNotificationSettings = restored
                }
                if (!settings.nativePushEnabled) {
                    perAccountSyncedFingerprints.remove(account)
                    withContext(Dispatchers.IO) { pushTokenStore.recordPendingClear(account) }
                    clearPushRegistrationForNotificationDeliveryOwnerLocked(owner, account)
                }
            }
        }
    }

    /** Restores the prior global preference while the same account/runtime/intent still owns settlement. */
    private suspend fun restorePersistentPreference(
        owner: NotificationDeliveryModeOwner,
        enabled: Boolean,
    ): Boolean {
        val persisted =
            withContext(NonCancellable + Dispatchers.IO) {
                nativePushFallbackPlatform.persistBackgroundConnectionEnabled(enabled) {
                    ownsNotificationDeliveryMode(owner)
                }
            }
        if (!persisted || !ownsNotificationDeliveryMode(owner)) return false
        backgroundConnectionEnabled = enabled
        if (!enabled) {
            nativePushFallback.invalidateAll()
            nativePushFallbackPlatform.stopBackgroundConnection()
        }
        return true
    }

    /** Keeps the acknowledged Local owner once any native disable may have become durable. */
    private suspend fun settleFailedPersistentDelivery(
        owner: NotificationDeliveryModeOwner,
        previousPersistent: Boolean,
        nativeDisableCommitted: Boolean,
    ) {
        if (!ownsNotificationDeliveryMode(owner)) return
        restorePersistentPreference(owner, enabled = previousPersistent || nativeDisableCommitted)
    }

    /** Restores the previous persistent side before native registration can be rolled back. */
    private suspend fun settleFailedFcmDelivery(
        owner: NotificationDeliveryModeOwner,
        previousPersistent: Boolean,
        previousNative: Map<String, NotificationSettingsFfi>,
        persistentStopAttempted: Boolean,
    ) {
        if (!ownsNotificationDeliveryMode(owner)) return
        val persistentSettled =
            when {
                !previousPersistent -> true
                persistentStopAttempted -> establishPersistentMode(owner) != null
                else -> restorePersistentPreference(owner, enabled = true)
            }
        if (persistentSettled && ownsNotificationDeliveryMode(owner)) {
            rollbackNativePushMode(owner, previousNative)
        }
    }

    suspend fun setBackgroundConnectionEnabled(enabled: Boolean): Boolean {
        val account =
            activeAccountRef ?: run {
                present(R.string.toast_no_active_account)
                return false
            }
        refreshLocalNotificationPermission()
        if (enabled && !localNotificationPermissionGranted) {
            present(R.string.toast_notification_permission_needed)
            return false
        }
        if (enabled && localNotificationSettings?.localNotificationsEnabled != true) {
            val settings =
                runCatching {
                    marmotIo { setLocalNotificationsEnabled(account, true) }
                }.getOrElse {
                    rethrowIfCancellation(it)
                    presentFailure(R.string.toast_couldnt_enable_notifications, "NOTIFICATION_ENABLE", it)
                    return false
                }
            localNotificationSettings = settings
        }

        updateBackgroundConnectionPreference(enabled)
        val serviceUpdated =
            if (enabled) {
                startBackgroundConnectionService()
            } else {
                NotificationStreamForegroundService.stop(appContext)
            }
        if (enabled && !serviceUpdated) {
            updateBackgroundConnectionPreference(false)
            present(R.string.toast_couldnt_keep_connected, R.string.toast_android_blocked_foreground_service, copyable = true)
            return false
        }
        presentTransient(
            if (enabled) {
                R.string.toast_background_connection_enabled
            } else {
                R.string.toast_background_connection_disabled
            },
        )
        return true
    }

    /**
     * Reconcile the background-connection preference when the foreground
     * service fails to come up. `start()` returns true the moment the start
     * intent is *queued*, before `onStartCommand` runs — so an Android 14+
     * foreground-start rejection inside the service can't be seen by the
     * enable path and would otherwise leave the toggle stuck "on" while no
     * service runs. The service calls this from its failure branch so the
     * UI reflects reality and surfaces the same toast as the synchronous
     * rejection path, for every entry point (toggle, app-foreground, resume).
     * Main-thread only (invoked from `onStartCommand`). See #164.
     */
    fun onBackgroundConnectionStartRejected() {
        assertMainThread { "onBackgroundConnectionStartRejected" }
        if (!backgroundConnectionEnabled) return
        updateBackgroundConnectionPreference(false)
        present(R.string.toast_couldnt_keep_connected, R.string.toast_android_blocked_foreground_service, copyable = true)
    }

    /** Reconcile a user-owned foreground connection after supervised runtime retries exhaust. */
    fun onBackgroundConnectionRuntimeExhausted() {
        assertMainThread { "onBackgroundConnectionRuntimeExhausted" }
        if (!backgroundConnectionEnabled) return
        updateBackgroundConnectionPreference(false)
        appStateDebug { "background connection runtime retries exhausted" }
    }

    /** Returns the first unmet native-push prerequisite without reaching later SDKs. */
    @Suppress("MaxLineLength")
    internal fun nativePushCapability(config: PushServerConfig? = pushServerConfigProvider()): NativePushCapability = nativePushCapabilityResolver(config)

    /**
     * Persist the FCM token and trigger a re-sync against the runtime. Called
     * by [dev.ipf.whitenoise.android.notifications.MarmotFirebaseMessagingService] on
     * every Firebase token rotation. The sync coroutine no-ops if any
     * precondition is missing, so the call is safe at any point in the app
     * lifecycle. Returns the re-sync job, or null if no rotation was queued.
     */
    fun onPushTokenRotated(token: String): Job? {
        if (token.isBlank()) return null
        val rotation = pushTokenRotationSequence.incrementAndGet()
        val runtime = marmotRuntime
        val generation = runtimeGeneration
        val account = activeAccountRef
        val accountSwitch = accountSwitchHandoff.capture()
        val ownerIsCurrent = {
            pushTokenRotationSequence.get() == rotation &&
                marmotRuntime === runtime &&
                runtimeGeneration == generation &&
                activeAccountRef == account &&
                accountSwitchHandoff.isCurrent(accountSwitch) &&
                !signOutInProgress &&
                !wipeInProgress
        }
        val retained =
            try {
                pushTokenStore.setTokenIfCurrent(token) { pushTokenRotationSequence.get() == rotation }
            } catch (_: PushTokenStorageException) {
                android.util.Log.w("PushTokenStorage", "token_rotation_deferred")
                false
            }
        return if (retained) {
            notificationScope.launch {
                val recorded =
                    withContext(pushWakeStorageDispatcher) {
                        observePushState {
                            pushTokenStore.recordPendingNativePushRegistrationSync()
                            true
                        }
                    }
                if (recorded != true) return@launch
                notificationDeliveryModeMutex.withLock {
                    if (!notificationDeliveryModeBusy) {
                        if (ownerIsCurrent()) syncNativePushRegistration(token, ownerIsCurrent)
                    }
                }
            }
        } else {
            null
        }
    }

    /**
     * Push the current FCM token to the runtime for every signed-in account
     * that requires native delivery, including background accounts so their
     * pushes can wake the device while another account is active.
     * Idempotent per account: a successful sync caches the
     * (token, server, relay) fingerprint and skips on the next call until
     * something changes. Returns true only when every account that currently
     * requires native push is registered (or does not require registration).
     */
    suspend fun syncNativePushRegistrationIfEnabled(): Boolean = syncNativePushRegistration()

    /** A callback token is ephemeral authority for this rotation only, never a persisted-cache fallback. */
    private suspend fun syncNativePushRegistration(
        freshCallbackToken: String? = null,
        callbackOwnerIsCurrent: () -> Boolean = { true },
    ): Boolean =
        try {
            nativePushSyncMutex.withLock {
                syncNativePushRegistrationIfEnabledLocked(freshCallbackToken, callbackOwnerIsCurrent)
            }
        } catch (_: PushTokenStorageException) {
            android.util.Log.w("PushTokenStorage", "registration_deferred")
            false
        }

    /** Retains required sign-out cleanup while permission loss pauses registration and fallback mutation. */
    private suspend fun pauseNotificationDeliveryReconciliation(): Boolean {
        nativePushFallback.invalidateAll()
        return nativePushSyncMutex.withLock {
            drainPendingPushClears()
            drainPendingPushDisables()
            false
        }
    }

    private suspend fun hasConfirmedNativePushRegistration(account: String): Boolean =
        nativePushSyncMutex.withLock { perAccountSyncedFingerprints.containsKey(account) }

    /** Replays pending cleanup before registration and gates token acquisition on capability. */
    @Suppress("ReturnCount")
    private suspend fun syncNativePushRegistrationIfEnabledLocked(
        freshCallbackToken: String? = null,
        callbackOwnerIsCurrent: () -> Boolean = { true },
    ): Boolean {
        val rotation = pushTokenRotationSequence.get()
        val operationIsCurrent = { pushTokenRotationSequence.get() == rotation && callbackOwnerIsCurrent() }

        // Drain before resolving the push-server config so a clear that
        // failed earlier still retries even if the config is later blanked
        // or GMS is uninstalled — otherwise a stale server-side registration
        // would keep wrapping wake events for a device that can no longer
        // receive them. Only the upsert path is gated on config + GMS.
        drainPendingPushClears()
        drainPendingPushDisables()
        refreshLocalNotificationPermission()
        if (!localNotificationPermissionGranted) {
            nativePushFallback.invalidateAll()
            return false
        }
        var accountRefs =
            accounts
                .filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
                .map { it.label }
        if (accountRefs.isEmpty()) {
            // Only clear the durable #755 retry flag when the account list is
            // authoritative. setAppInForeground() can trigger this before
            // bootstrap/refreshAccounts() has loaded accounts; clearing then
            // would strand a signed-in device on a stale push token.
            if (marmotRuntime == null) return false
            refreshAccounts()
            accountRefs =
                accounts
                    .filter { it.isSignedInSigningAccount() && accountSetup.eligible(it) }
                    .map { it.label }
            if (accountRefs.isEmpty()) {
                return withContext(pushWakeStorageDispatcher) {
                    pushTokenStore.completeRegistrationSyncIfCurrent(ownerIsCurrent = operationIsCurrent)
                }
            }
        }
        val config = pushServerConfigProvider()
        val capability = nativePushCapability(config)
        if (!capability.isAvailable) return reconcileUnavailableNativePushFallback(capability, accountRefs)
        config ?: return false
        if (!withContext(pushWakeStorageDispatcher) { pushTokenStore.registrationAllowed() }) return false
        if (!operationIsCurrent()) return false
        return syncNativePushRegistrationForAccountsLocked(accountRefs, config, freshCallbackToken, operationIsCurrent)
    }

    /** The caller holds the sync mutex through every account and the atomic pending-marker acknowledgement. */
    private suspend fun syncNativePushRegistrationForAccountsLocked(
        accountRefs: List<String>,
        config: PushServerConfig,
        freshCallbackToken: String?,
        operationIsCurrent: () -> Boolean,
    ): Boolean {
        val token = tokenForNativePushSync(freshCallbackToken, operationIsCurrent) ?: return false
        val tokenIsCurrent: suspend () -> Boolean = {
            operationIsCurrent() &&
                (
                    freshCallbackToken == null ||
                        withContext(pushWakeStorageDispatcher) { pushTokenStore.lastToken() == token }
                ) &&
                operationIsCurrent()
        }
        return tokenIsCurrent() &&
            accountRefs.all { account -> syncPushForAccount(account, config, token, tokenIsCurrent) } &&
            tokenIsCurrent() &&
            withContext(pushWakeStorageDispatcher) {
                pushTokenStore.completeRegistrationSyncIfCurrent(token, operationIsCurrent)
            }
    }

    /** Only this invocation's fresh callback can replace a failed SDK acquisition. */
    private suspend fun tokenForNativePushSync(
        freshCallbackToken: String?,
        operationIsCurrent: () -> Boolean,
    ): String? =
        fetchFcmTokenOrNull(freshCallbackToken, operationIsCurrent)
            ?: freshCallbackToken?.takeIf {
                operationIsCurrent() &&
                    withContext(pushWakeStorageDispatcher) { pushTokenStore.lastToken() == it }
            }

    /** Migrates the active account while reporting unknown or background-owned state as incomplete. */
    private suspend fun reconcileUnavailableNativePushFallback(
        capability: NativePushCapability,
        accountRefs: List<String>,
    ): Boolean {
        val reconciled =
            nativePushFallback.reconcile(
                capability = capability,
                owner = captureNativePushFallbackOwner() ?: return false,
                accountRefs = accountRefs,
                bindings =
                    NativePushFallbackBindings(
                        ownerIsCurrent = { owner ->
                            localNotificationPermissionGranted &&
                                localNotificationPresenter.canPostNotifications() &&
                                ownsNativePushFallback(owner)
                        },
                        readSettings = { request, account -> request.runtime.marmot.notificationSettings(account) },
                        publishActiveSettings = { localNotificationSettings = it },
                        publishPersistentConnectionEnabled = { backgroundConnectionEnabled = true },
                        publishComplete = pushTokenStore::clearPendingNativePushRegistrationSync,
                        removeFingerprint = { perAccountSyncedFingerprints.remove(it.accountRef) },
                        setNativePushDisabled = { it.runtime.marmot.setNativePushEnabled(it.accountRef, false) },
                        clearRegistration = ::clearPushRegistrationForOwnerLocked,
                        onFailure = { message -> appStateDebug { message } },
                    ),
            )
        return reconciled
    }

    /** Captures the exact active account/runtime lifetime used by service readiness callbacks. */
    private fun captureNativePushFallbackOwner(): NativePushFallbackOwner? =
        nativePushFallback.owner(
            activeAccountRef,
            marmotRuntime,
            runtimeGeneration,
            accountSwitchHandoff.capture(),
        )

    /** Rejects suspended fallback work after an account switch or runtime replacement. */
    private fun ownsNativePushFallback(owner: NativePushFallbackOwner): Boolean = captureNativePushFallbackOwner()?.matches(owner) == true

    /** Accepts only the exact service generation that reached a successful supervised runtime boundary. */
    internal fun onNativePushFallbackRuntimeStarted(generation: Long): Job? {
        assertMainThread { "onNativePushFallbackRuntimeStarted" }
        refreshLocalNotificationPermission()
        val owner =
            nativePushFallback.acknowledge(generation) { candidate ->
                localNotificationPermissionGranted && ownsNativePushFallback(candidate)
            } ?: return null
        return notificationScope.launch {
            if (ownsNativePushFallback(owner) && !notificationDeliveryModeBusy) {
                syncNativePushRegistrationIfEnabled()
            }
        }
    }

    /** Drops a rejected, exhausted, recovery-fenced, or destroyed service generation. */
    internal fun onNativePushFallbackRuntimeUnavailable(generation: Long) {
        assertMainThread { "onNativePushFallbackRuntimeUnavailable" }
        nativePushFallback.invalidate(generation)
    }

    /** Clears only [owner]'s registration on its captured runtime and retains failed cleanup for retry. */
    private suspend fun clearPushRegistrationForOwnerLocked(owner: NativePushFallbackOwner) {
        if (!ownsNativePushFallback(owner)) return
        currentCoroutineContext().ensureActive()
        runCatchingCancellable {
            withContext(Dispatchers.IO) { owner.runtime.marmot.clearPushRegistration(owner.accountRef) }
        }.onSuccess { outcome ->
            logPushRegistrationShareOutcome("capability fallback clear", owner.accountRef, outcome)
            if (ownsNativePushFallback(owner)) pushTokenStore.clearPending(owner.accountRef)
        }.onFailure { failure ->
            appStateDebug { "capability fallback clear failed (queued for retry): ${failure.readableMessage()}" }
        }
    }

    /**
     * Retry every `clearPushRegistration` that previously failed (sign-out
     * disconnected from the network, runtime transient error, etc.). On
     * success the entry leaves the persisted set; on failure it stays and
     * the next sync tick will try again. Without this drain a failed
     * sign-out-time deregistration would silently leave the push server
     * holding a stale token — there's no other code path that would notice
     * because [nativePushEnabled] is already false on the runtime side.
     */
    private suspend fun drainPendingPushClears() {
        for (account in pushTokenStore.pendingClears()) {
            val cleared =
                runCatchingCancellable { marmotIo { clearPushRegistration(account) } }
                    .onSuccess { outcome ->
                        logPushRegistrationShareOutcome("pending clear", account, outcome)
                    }.onFailure {
                        appStateDebug { "pending clearPushRegistration retry failed: ${it.readableMessage()}" }
                    }.isSuccess
            if (cleared) {
                pushTokenStore.clearPending(account)
                appStateDebug { "pending clearPushRegistration drained account=${account.take(8)}" }
            }
        }
    }

    // Retry sign-out push-disables that failed; on success the account leaves the pending set.
    private suspend fun drainPendingPushDisables() {
        val runtime = marmotRuntime ?: return
        for (account in pushTokenStore.pendingDisables()) {
            val settings =
                runCatchingCancellable {
                    withContext(marmotIoDispatcher) { runtime.marmot.setNativePushEnabled(account, false) }
                }.onFailure {
                    appStateDebug { "pending setNativePushEnabled(false) retry failed: ${it.readableMessage()}" }
                }.getOrNull()
            if (marmotRuntime !== runtime) return
            if (settings?.nativePushEnabled == false) {
                perAccountSyncedFingerprints.remove(account)
                if (account == activeAccountRef) localNotificationSettings = settings
                pushTokenStore.clearPendingDisable(account)
                appStateDebug { "pending native-push disable drained account=${account.take(8)}" }
            }
        }
    }

    private suspend fun syncPushForAccount(
        account: String,
        config: PushServerConfig,
        token: String,
        tokenIsCurrent: suspend () -> Boolean = { true },
    ): Boolean {
        val settings = runCatchingCancellable { marmotIo { notificationSettings(account) } }.getOrNull() ?: return false
        if (account == activeAccountRef) localNotificationSettings = settings
        // Skip accounts with a queued sign-out disable so a stale enabled flag can't re-register them.
        if (account in pushTokenStore.pendingDisables()) return true
        if (!settings.nativePushEnabled) return true
        val fingerprint =
            PushFingerprint(
                platform = PushPlatformFfi.FCM,
                token = token,
                serverPubkeyHex = config.serverPubkeyHex,
                relayHint = config.relayHint,
            )
        if (perAccountSyncedFingerprints[account] == fingerprint) return true
        return runCatching {
            val syncResult =
                marmotIo {
                    if (!pushTokenStore.registrationAllowed() || !tokenIsCurrent()) throw PushTokenStorageException()
                    upsertPushRegistration(
                        accountRef = account,
                        platform = PushPlatformFfi.FCM,
                        rawToken = token,
                        serverPubkeyHex = config.serverPubkeyHex,
                        relayHint = config.relayHint,
                    )
                }
            logPushRegistrationShareOutcome("upsert", account, syncResult.share)
            // Re-read settings: a concurrent `setNativePushEnabled(false)` or
            // sign-out could have flipped the flag (and cleared the cache)
            // while upsertPushRegistration was suspended. If push is no
            // longer enabled, do NOT write the fingerprint back — otherwise
            // the cache restores a stale entry and the next enable
            // short-circuits without re-registering. Roll back instead.
            val settingsAfter = runCatchingCancellable { marmotIo { notificationSettings(account) } }.getOrNull()
            when {
                // Re-read failed: that's a transient error, not a disable —
                // the upsert itself succeeded, so keep the registration. Skip
                // the fingerprint write so the next sync re-verifies instead
                // of trusting a state we couldn't confirm.
                settingsAfter == null ->
                    false.also {
                        appStateDebug { "push settings re-read failed; keeping registration account=${account.take(8)}" }
                    }
                settingsAfter.nativePushEnabled && tokenIsCurrent() -> {
                    perAccountSyncedFingerprints[account] = fingerprint
                    appStateDebug { "push registration synced account=${account.take(8)}" }
                    true
                }
                settingsAfter.nativePushEnabled -> false
                else -> {
                    appStateDebug { "push registration raced disable; rolling back account=${account.take(8)}" }
                    // Shares the pending-clear bookkeeping: a failed rollback
                    // is queued for retry instead of stranding the account
                    // registered server-side.
                    clearPushRegistrationForAccountLocked(account)
                    true
                }
            }
        }.getOrElse {
            rethrowIfCancellation(it)
            // Drop the fingerprint on failure so the next sync retries
            // rather than assuming the registration is fresh.
            perAccountSyncedFingerprints.remove(account)
            appStateDebug { "push registration sync failed: ${it.readableMessage()}" }
            false
        }
    }

    /**
     * Enable or disable real push on the active account. When enabling, also
     * triggers the registration sync; when disabling, clears the runtime
     * registration so the MIP-05 server stops trying to deliver to a token
     * the device no longer wants.
     */
    suspend fun setNativePushEnabled(enabled: Boolean): Boolean {
        val requested = activeAccountRef ?: return false
        val runtime = marmotRuntime
        val epoch = runtimeGeneration
        val intent = notificationDeliveryModeIntent.capture()
        return withAccountLifetime {
            notificationDeliveryModeMutex.withLock {
                val ownerCurrent =
                    activeAccountRef == requested && marmotRuntime === runtime && runtimeGeneration == epoch
                if (!ownerCurrent ||
                    !notificationDeliveryModeIntent.isCurrent(intent)
                ) {
                    return@withLock false
                }
                setNativePushEnabledLocked(requested, enabled)
            }
        }
    }

    private suspend fun setNativePushEnabledLocked(
        account: String,
        enabled: Boolean,
    ): Boolean {
        if (enabled && !nativePushCapability().isAvailable) return false
        return runCatching {
            val settings =
                nativePushSyncMutex.withLock {
                    if (
                        enabled &&
                        !withContext(pushWakeStorageDispatcher) { pushTokenStore.clearPendingDisable(account) }
                    ) {
                        return@runCatching false
                    }
                    marmotIo { setNativePushEnabled(account, enabled) }
                }
            localNotificationSettings = settings
            if (settings.nativePushEnabled != enabled) return@runCatching false
            if (enabled) {
                // Durable supersession was confirmed before the native positive write.
                val allAccountsReady = syncNativePushRegistrationIfEnabled()
                // The runtime flag alone is not a usable delivery path. Only
                // report enablement after upsertPushRegistration succeeded
                // for every enabled account and cached the active account's
                // exact token/server fingerprint.
                val confirmed =
                    nativePushEnablementConfirmed(
                        allAccountsReady = allAccountsReady,
                        activeAccountRegistered = hasConfirmedNativePushRegistration(account),
                    )
                if (!confirmed) {
                    val rollbackSettings = marmotIo { setNativePushEnabled(account, false) }
                    localNotificationSettings = rollbackSettings
                    clearPushRegistrationForAccount(account)
                }
                confirmed
            } else {
                clearPushRegistrationForAccount(account)
                true
            }
        }.getOrElse {
            rethrowIfCancellation(it)
            presentFailure(R.string.toast_couldnt_update_notifications, "NOTIFICATION_SETTINGS_UPDATE", it)
            false
        }
    }

    /**
     * Runtime-side clear of an account's push registration plus the cached
     * fingerprint. If the FFI call fails (network hiccup, runtime mid-
     * teardown, sign-out racing with a transient error), persist the
     * account into the pending-clears set so [drainPendingPushClears]
     * retries it on the next sync — otherwise a server-side stale token
     * would stick indefinitely because `nativePushEnabled` is already
     * false locally and the sync loop would skip the account.
     */
    private suspend fun clearPushRegistrationForAccount(account: String) {
        nativePushSyncMutex.withLock {
            clearPushRegistrationForAccountLocked(account)
        }
    }

    private suspend fun clearPushRegistrationForAccountLocked(account: String) {
        perAccountSyncedFingerprints.remove(account)
        val result = runCatchingCancellable { marmotIo { clearPushRegistration(account) } }
        result.onSuccess { outcome ->
            // PENDING means MarmotKit durably retained the clear and will
            // retry group sharing; it is a successful local clear, not a
            // reason to restore or roll back the registration.
            logPushRegistrationShareOutcome("clear", account, outcome)
            pushTokenStore.clearPending(account)
        }
        result.exceptionOrNull()?.let { failure ->
            val queued = withContext(Dispatchers.IO) { pushTokenStore.recordPendingClear(account) }
            appStateDebug {
                "clearPushRegistration failed cleanupQueued=$queued: ${failure.readableMessage()}"
            }
        }
    }

    /** Fetches and retains a non-blank FCM token only when every push prerequisite is available. */
    private suspend fun fetchFcmTokenOrNull(
        expectedCallbackToken: String? = null,
        tokenOwnerIsCurrent: () -> Boolean = { true },
    ): String? {
        val rotation = pushTokenRotationSequence.get()
        if (!nativePushCapability().isAvailable) return null
        val token =
            runCatchingCancellable {
                val reader: suspend () -> String? = fcmTokenReaderOverride ?: ::readFcmTokenFromSdk
                reader()
            }.onFailure {
                appStateDebug { "FCM token fetch failed: ${it.readableMessage()}" }
            }.getOrNull()
        return if (token.isNullOrBlank()) {
            null
        } else {
            val retained =
                withContext(pushWakeStorageDispatcher) {
                    pushTokenStore.setTokenIfCurrent(token, expectedCallbackToken) {
                        pushTokenRotationSequence.get() == rotation && tokenOwnerIsCurrent()
                    }
                }
            token.takeIf { retained }
        }
    }

    private suspend fun readFcmTokenFromSdk(): String? =
        suspendCancellableCoroutine<String?> { continuation ->
            // The Firebase Task API has no cancel surface, so the
            // completion listener can fire after this coroutine is
            // cancelled. Guard the resume on isActive so a stale
            // callback doesn't try to push a value onto a closed
            // continuation; the task completes in the background and
            // its result is dropped. The outer runCatching is a
            // belt — `getInstance()` itself can throw
            // IllegalStateException if FirebaseApp isn't initialized,
            // and we'd rather drop the token fetch than crash.
            FirebaseMessaging
                .getInstance()
                .token
                .addOnCompleteListener { task ->
                    if (continuation.isActive) {
                        continuation.resume(if (task.isSuccessful) task.result else null)
                    }
                }
        }

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

    fun displayName(accountIdHex: String): String = displayNameForAccount(activeAccountRef, accountIdHex)

    fun networkDisplayName(accountIdHex: String): String {
        profileDisplayName(accountIdHex)?.let { return it }
        requestProfile(accountIdHex)
        val accountLabel = accounts.firstOrNull { it.accountIdHex == accountIdHex }?.label
        return networkDisplayNameFallback(accountLabel, accountIdHex, ::shortNpub)
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
            bumpProfileAccountRevision(accountIdHex)
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
    private fun cachedShortNpubOrUnknown(accountIdHex: String): String =
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

    /** Pure cache snapshot used at the final Android write, including a cleared private choice. */
    @Suppress("ReturnCount") // Reject ineligible or revoked owners before accessing private pixels.
    private fun contactAvatarOverride(
        account: String,
        contact: String,
    ): Pair<Boolean, android.graphics.Bitmap?> {
        if (isContactRefBeingCleared(account)) return true to null
        if (contactNicknameAccountRefForAccess(account, accounts, contact) == null ||
            !contactPictureStore.hasChoice(account, contact)
        ) {
            return false to null
        }
        if (appLockScreenVisible) return true to null
        val private = contactPictureStore.reference(account, contact)?.cacheKey?.let(AvatarImageLoader::cachedImage)
        val published = synchronized(profilePresentationLock) { profilePresentations[contact]?.avatarUrl }
        return true to (private?.asAndroidBitmap() ?: AvatarImageLoader.peekBitmap(published))
    }

    /** Bounded local file decode shares the first-post deadline; it can never acquire a public URL. */
    private suspend fun warmPrivateContactAvatar(
        account: String,
        contact: String,
    ) {
        val eligible = contactNicknameAccountRefForAccess(account, accounts, contact) != null
        if (!eligible || isContactRefBeingCleared(account)) return
        val ref = contactPictureStore.reference(account, contact) ?: return
        val lifetime = AvatarImageLoader.currentCacheLifetime()
        runCatchingCancellable {
            AvatarImageLoader.loadStored(ref.cacheKey, lifetime) {
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
                // A nickname-only Save changes no pixels, so every cached avatar stays decoded.
                if (picture !is ContactPictureChange.Keep) AvatarImageLoader.clearStoredAvatars()
                warmPrivateContactAvatar(account, contact)
                contactNicknameRevision += 1
                bumpProfileAccountRevision(contact)
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

    /**
     * Start local profile materialization and image decoding without waiting for
     * either. Chat/message projections call this before notification delivery;
     * [applyProfilePresentation] queues the image as soon as local storage (or a
     * relay refresh) yields a sanitized URL.
     */
    internal fun preWarmProfileAvatar(accountIdHex: String) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        val cachedAvatar =
            synchronized(profilePresentationLock) {
                val avatar = profilePresentations[id]?.avatarUrl
                if (avatar != null) {
                    pendingAvatarPreWarmAccountIds.remove(id)
                } else {
                    // Reinsert to keep the set in recent-conversation order; a
                    // live sender must not sit behind old avatarless profiles.
                    pendingAvatarPreWarmAccountIds.remove(id)
                    pendingAvatarPreWarmAccountIds.add(id)
                }
                avatar
            }
        AvatarImageLoader.preWarm(cachedAvatar)
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

    fun presentProfilePayload(raw: String): Boolean {
        val link = ProfileLink.parse(raw) ?: return false
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
    ) {
        pendingProfileGroupsInCommon = groupsInCommon
        pendingProfileMetadata = null
        pendingProfileFromDiscovery = false
        pendingProfileNpub = npub
    }

    fun presentDiscoveredProfile(
        npub: String,
        profile: UserProfileMetadataFfi?,
    ) {
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

    private fun applyNotificationDisplayNameHint(update: NotificationUpdateFfi) {
        if (update.accountRef != activeAccountRef) return
        val senderIdHex =
            update.sender.accountIdHex
                .trim()
                .takeIf { it.isNotEmpty() }
        val hint = notificationDisplayNameHint(update.sender.displayName)
        if (senderIdHex == null || hint == null) return
        val changed =
            synchronized(profilePresentationLock) {
                if (profilePresentations[senderIdHex]?.displayName != null) {
                    false
                } else {
                    notificationDisplayNameHints.put(senderIdHex, hint) != hint
                }
            }
        if (changed) {
            profileRevision += 1
            bumpProfileAccountRevision(senderIdHex)
        }
    }

    private suspend fun notificationMessageRecord(update: NotificationUpdateFfi) =
        update.messageIdHex?.let { messageId ->
            // A small recent tail; the just-arrived message is within it, and a
            // miss simply falls back to the generic notification body.
            runCatchingCancellable { marmotIo { messages(update.accountRef, update.groupIdHex, 30u, null) } }
                .getOrNull()
                ?.firstOrNull { it.messageIdHex.equals(messageId, ignoreCase = true) }
        }

    /**
     * Queues durable automatic downloads for every attachment a received message carries, from the receipt
     * pipeline rather than a Compose bubble, so media is locally ready before the reader returns (#3308). Each
     * slot is held to its own type's policy for the receiving account, which need not be the active one, and
     * waits in WorkManager for a network that policy could allow. The worker persists only the lookup identity
     * and re-checks the live policy against MDK's reference when it runs.
     */
    private suspend fun scheduleIncomingAttachmentDownloads(update: NotificationUpdateFfi) {
        val messageIdHex = update.messageIdHex
        if (update.trigger != NotificationTriggerFfi.NEW_MESSAGE || update.isFromSelf || messageIdHex == null) return
        scheduleReceivedMessageAttachments(update.accountRef, update.groupIdHex, messageIdHex, update.timestampMs)
    }

    /**
     * The receipt scheduling both receipt sources share, for one received message of [accountRef]'s group
     * [groupIdHex]: a bounded authoritative lookup of its attachments, each queued under its own type's policy.
     */
    @Suppress("ReturnCount") // A held account and a failed lookup each end the receipt early.
    private suspend fun scheduleReceivedMessageAttachments(
        accountRef: String,
        groupIdHex: String,
        messageIdHex: String,
        receivedAtMillis: Long,
    ) {
        val policy = automaticAttachmentPolicy(accountRef)
        val wanted = policy.receiptTypes()
        if (wanted.isEmpty()) {
            Log.i(INCOMING_ATTACHMENT_TAG, "attachment_receipt_skipped reason=${policy.receiptSkipReason().reason}")
            return
        }
        // The notification and the runtime event may both report this message; only the first runs its lookup.
        if (!receiptClaims.claim(accountRef, messageIdHex)) return
        val lookup =
            runCatchingCancellable {
                // Both receipt sources share one bound, so a catch-up burst cannot fan out unbounded history reads.
                receiptLookupGate.withPermit {
                    receivedMessageAttachments(accountRef, groupIdHex, messageIdHex, receivedAtMillis)
                }
            }.onFailure {
                // A failed lookup releases its claim, so the other source, or a later receipt, can try again.
                receiptClaims.release(accountRef, messageIdHex)
                Log.w(INCOMING_ATTACHMENT_TAG, "attachment_receipt_lookup_failed type=${it.javaClass.simpleName}")
            }.getOrNull() ?: return
        val (eligible, held) = lookup.attachments.partition { it.type in wanted }
        // A slot whose durable enqueue fails, now or when its operation completes, releases the claim, so the other
        // source or a later receipt schedules it again rather than finding the message already handled.
        val releaseClaim = { receiptClaims.release(accountRef, messageIdHex) }
        eligible.forEach {
            enqueueAttachmentDownload(
                it.request,
                networkType = policy.networkType(it.type),
                type = it.type,
                onAutomaticNotQueued = releaseClaim,
            )
        }
        // The claim only stands for a lookup that settled every slot. A held type, or a read the budget cut short,
        // leaves work a later receipt may still schedule once the policy allows it, so that receipt must look again.
        val incomplete = lookup.end == ReceiptLookupEnd.Exhausted || lookup.end == ReceiptLookupEnd.Unavailable
        if (held.isNotEmpty() || incomplete) receiptClaims.release(accountRef, messageIdHex)
        logReceiptScheduling(policy, lookup, eligible, held)
    }

    /**
     * Logs one receipt's outcome without attachment identity: queued work, the part of it waiting for an allowed
     * network, held types, and how the history read ended. A text message's ordinary read logs nothing.
     */
    private fun logReceiptScheduling(
        policy: AutomaticAttachmentPolicy,
        lookup: ReceiptAttachmentLookup,
        eligible: List<IncomingAttachment>,
        held: List<IncomingAttachment>,
    ) {
        val incomplete = lookup.end == ReceiptLookupEnd.Exhausted || lookup.end == ReceiptLookupEnd.Unavailable
        if (lookup.attachments.isEmpty() && !incomplete) return
        val waiting = eligible.count { policy.hold(it.type) != null }
        val heldTypes =
            held
                .map { it.type.preferenceKey }
                .distinct()
                .sorted()
                .joinToString(",")
        Log.i(
            INCOMING_ATTACHMENT_TAG,
            "attachment_receipt_scheduled queued=${eligible.size} waiting=$waiting held=${held.size} " +
                "held_types=$heldTypes pages=${lookup.pages} end=${lookup.end.key}",
        )
    }

    /**
     * Publishes the account list a post-wipe read produced. A failed read leaves an empty list that is not
     * authoritative, so surviving accounts' automatic work waits for the next successful read instead of ending.
     */
    internal fun publishRefreshedAccountList(result: Result<List<AccountSummaryFfi>>) {
        accounts = result.getOrDefault(emptyList())
        accountListLoaded = result.isSuccess
    }

    /**
     * [accountRef]'s automatic-download inputs right now. Receipt scheduling and durable work both decide with
     * this, and [AutomaticAttachmentPolicy.allowedNow] matches the native permission exactly.
     */

    internal fun automaticAttachmentPolicy(accountRef: String): AutomaticAttachmentPolicy {
        val account = accounts.firstOrNull { it.label == accountRef }
        return AutomaticAttachmentPolicy(
            account =
                when {
                    account == null && !accountListLoaded -> AutomaticPolicyAccount.Unloaded
                    account == null -> AutomaticPolicyAccount.Unknown
                    account.signedOut -> AutomaticPolicyAccount.SignedOut
                    else -> AutomaticPolicyAccount.SignedIn
                },
            paused = attachmentDownloadIntents.isAutomaticPaused(accountRef),
            matrix = loadMediaAutoDownloadMatrix(accountRef),
            networks = activeNetworkTypes(),
            validatedInternet = hasValidatedInternet(),
        )
    }

    private suspend fun notificationTimelineRecord(update: NotificationUpdateFfi) =
        update.messageIdHex?.let { messageId ->
            runCatchingCancellable {
                marmotIo {
                    timelineMessages(
                        update.accountRef,
                        TimelineMessageQueryFfi(
                            groupIdHex = update.groupIdHex,
                            search = null,
                            before = null,
                            beforeMessageId = null,
                            after = null,
                            afterMessageId = null,
                            limit = 30u,
                        ),
                    ).messages
                }
            }.getOrNull()
                ?.firstOrNull { it.messageIdHex.equals(messageId, ignoreCase = true) }
        }

    /**
     * Rechecks synchronous app and content fences at every platform write. A reaction's own event id cannot verify
     * its quoted target, so a known removal in that conversation also rejects a captured, still-quoted update.
     */
    private fun isNotificationGenerationPostAllowed(
        update: NotificationUpdateFfi,
        postEpoch: Long,
        accountCacheEpoch: Long,
        engineMuted: Boolean,
        requireUnlocked: Boolean,
    ): Boolean {
        val lockAllowsPost = !requireUnlocked || !appLockScreenVisible
        val runtimeAllowsPost =
            !networkNotificationRecoverySuppressed &&
                notificationPostEpoch.isCurrent(postEpoch) &&
                profileCacheLifetime.isCurrent(accountCacheEpoch)
        // A deleted or hidden message can never be written back by a queued post or late enrichment (#3323).
        val contentAvailable =
            !notificationContentInvalidations.isRemoved(update.accountRef, update.groupIdHex, update.messageIdHex)
        // Reactions name their own event, not the quoted target. Recheck removals at every platform write too.
        val quoteAvailable =
            !LocalNotificationFormatter.isReaction(update) ||
                update.reactedToPreview.isNullOrBlank() ||
                !notificationContentInvalidations.hasRemovalInConversation(update.accountRef, update.groupIdHex)
        return lockAllowsPost &&
            runtimeAllowsPost &&
            contentAvailable &&
            quoteAvailable &&
            shouldPostNotification(update, engineMuted)
    }

    private fun isNotificationEnrichmentAllowed(
        update: NotificationUpdateFfi,
        postEpoch: Long,
        accountCacheEpoch: Long,
        engineMuted: Boolean,
    ): Boolean =
        isNotificationGenerationPostAllowed(
            update = update,
            postEpoch = postEpoch,
            accountCacheEpoch = accountCacheEpoch,
            engineMuted = engineMuted,
            requireUnlocked = true,
        ) &&
            localNotificationPresenter.isNotificationUpdateCurrentForEnrichment(update)

    /** Re-checks every captured fence for one first-post correction candidate. */
    private fun isNotificationEnrichmentAllowed(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
    ): Boolean =
        isQueuedReactionQuoteCurrent(update, firstPost) &&
            isNotificationEnrichmentAllowed(
                update = update,
                postEpoch = firstPost.epoch,
                accountCacheEpoch = firstPost.accountCacheEpoch,
                engineMuted = firstPost.engineMuted,
            )

    /**
     * Identity eviction cannot make an already-captured opaque reaction quote eligible again. Any removal in the
     * same account conservatively rejects its queued quotes; no quoted-target identity is available to narrow it.
     */
    private fun isQueuedReactionQuoteCurrent(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
    ): Boolean =
        !LocalNotificationFormatter.isReaction(update) ||
            update.reactedToPreview.isNullOrBlank() ||
            notificationContentInvalidations.removalVersion(update.accountRef) == firstPost.contentRemovalVersion

    /**
     * Reads MDK's durable state for one message candidate right before it may alert or be enriched (#2065, #3323).
     * Only incoming timeline messages are governed: reactions, invites and membership events always pass. A read,
     * deleted or hidden candidate is withheld. A read that fails or exceeds its bounded time is retried once, and a
     * state that still cannot be read is [NotificationCandidateState.Unknown]: the first write is withheld, because a
     * stale alert is worse than a missed one, while enrichment of an already-posted card proceeds. The outcome is
     * logged without identifiers or content.
     */
    private suspend fun notificationCandidateGate(
        update: NotificationUpdateFfi,
        phase: String,
    ): NotificationCandidateState {
        val messageIdHex = update.messageIdHex?.takeIf(String::isNotBlank)
        val governed =
            update.trigger == NotificationTriggerFfi.NEW_MESSAGE &&
                !update.isFromSelf &&
                !LocalNotificationFormatter.isReaction(update)
        if (!governed || messageIdHex == null) return NotificationCandidateState.Unread
        val state =
            if (notificationContentInvalidations.isRemoved(update.accountRef, update.groupIdHex, messageIdHex)) {
                NotificationCandidateState.Removed
            } else {
                readNotificationCandidateStateWithinBudget(update, messageIdHex)
            }
        appStateDebug { "notification candidate phase=$phase outcome=${state.logValue}" }
        return state
    }

    /**
     * Bounds each await to [NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS], at most [NOTIFICATION_CANDIDATE_ATTEMPTS]
     * times. A synchronous binding may keep running; retries join it and its slot stays occupied until completion.
     * Different candidates fail fast behind an abandoned holder, including globally when all slots are abandoned.
     */
    private suspend fun readNotificationCandidateStateWithinBudget(
        update: NotificationUpdateFfi,
        messageIdHex: String,
    ): NotificationCandidateState {
        repeat(NOTIFICATION_CANDIDATE_ATTEMPTS) {
            runCatchingCancellable {
                notificationCandidateReads.read(
                    key =
                        NotificationCandidateReadKey(
                            update.accountRef,
                            update.groupIdHex.lowercase(),
                            messageIdHex.lowercase(),
                            notificationPostEpoch.capture(),
                            profileCacheLifetime.capture(),
                        ),
                    timeoutMillis = NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS,
                ) { readNotificationCandidateState(update, messageIdHex) }
            }.getOrNull()?.let { return it }
        }
        return NotificationCandidateState.Unknown
    }

    /**
     * Resolves one governed candidate from its hidden mark, chat-list row and timeline record, off main. The record
     * is always looked up, first in the latest page and then in the wider reconciliation page, so a deletion MDK
     * already accepted is honoured even when nothing has been read yet or the removal mark did not survive a restart.
     */
    private suspend fun readNotificationCandidateState(
        update: NotificationUpdateFfi,
        messageIdHex: String,
    ): NotificationCandidateState {
        val hidden =
            withContext(Dispatchers.IO) { hiddenMessageIdsInGroup(update.accountRef, update.groupIdHex) }
        if (hidden.any { it.equals(messageIdHex, ignoreCase = true) }) return NotificationCandidateState.Removed
        val row = marmotIo(MarmotTraceSection.CHAT_ROW_READ) { chatListRow(update.accountRef, update.groupIdHex) }
        val latest = notificationTimelinePage(update.accountRef, update.groupIdHex)
        val page =
            if (latest.any { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }) {
                latest
            } else {
                notificationTimelinePage(update.accountRef, update.groupIdHex, NOTIFICATION_RECONCILIATION_PAGE_LIMIT)
            }
        return notificationCandidateState(messageIdHex, row, page)
    }

    /** The conversation's latest bounded timeline page, the same window notification preparation reads. */
    private suspend fun notificationTimelinePage(
        accountRef: String,
        groupIdHex: String,
        limit: UInt = NOTIFICATION_CANDIDATE_PAGE_LIMIT,
    ): List<TimelineMessageRecordFfi> =
        marmotIo {
            timelineMessages(
                accountRef,
                TimelineMessageQueryFfi(
                    groupIdHex = groupIdHex,
                    search = null,
                    before = null,
                    beforeMessageId = null,
                    after = null,
                    afterMessageId = null,
                    limit = limit,
                ),
            ).messages
        }

    /**
     * Removes [message] from its conversation's cards after MDK accepted its deletion or this device hid it (#3323).
     * The removal mark lands first, so a queued post or late enrichment of that message is fenced under the card
     * lock whichever side of the rewrite it reaches. Only that entry, and any entry without an identity, leaves the
     * card; other valid entries stay. Reconciliation is silent: it only rewrites or cancels and never alerts.
     */
    internal suspend fun retractNotificationContent(message: RemovedNotificationMessage) {
        recordNotificationContentRemoval(message)
        withNotificationReconcileLock(message.accountRef, message.groupIdHex) {
            withContext(Dispatchers.Default) {
                localNotificationPresenter.reconcileConversationContent(
                    message.accountRef,
                    message.groupIdHex,
                    cancelReactionCards = true,
                ) { entryId, _ ->
                    entryId != null &&
                        !notificationContentInvalidations.isRemoved(message.accountRef, message.groupIdHex, entryId)
                }
            }
        }
    }

    /** Records an accepted removal before any platform card lock can delay its retraction. */
    internal fun recordNotificationContentRemoval(message: RemovedNotificationMessage) {
        notificationContentInvalidations.markRemoved(message)
    }

    /**
     * Serializes card reconciliation per account and conversation, so a deletion, a read change and a stream-open
     * pass for the same conversation apply in order. Fixed stripes bound memory; hash collisions may serialize
     * unrelated conversations without losing mutual exclusion for existing waiters.
     */
    private suspend fun <T> withNotificationReconcileLock(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> T,
    ): T {
        val key = "$accountRef\u0000${groupIdHex.lowercase()}"
        val stripe = (key.hashCode() and Int.MAX_VALUE) % notificationReconcileLocks.size
        return notificationReconcileLocks[stripe].withLock { block() }
    }

    /**
     * Requests cleanup for each shown conversation on stream open (#3323, #2065). Opens and read-state events
     * share the receipt loop's workers, so reconnecting never cancels or duplicates an unfinished native read.
     */
    private suspend fun requestActiveCardReconciliation(coordinator: NotificationReadReconciliationCoordinator) {
        val cards = withContext(Dispatchers.Default) { localNotificationPresenter.activeConversationCards() }
        cards.map { it.accountRef to it.groupIdHex.lowercase() }.distinct().forEach { (account, group) ->
            coordinator.request(account, group)
        }
    }

    /**
     * Keeps on one conversation's cards only the entries MDK still reports showable: not deleted, not hidden on this
     * device and not read (#3323, #2065). Only what the state snapshot covers is judged. An entry missing from the
     * snapshot that was shown after the snapshot was taken is newer than it and stays; an older missing entry
     * cannot be verified and leaves. Cards whose entries carry no identity (posted by an older build) fall back to
     * [reconcileActiveConversationCard]. When MDK state stays unavailable, cards are kept and only content this
     * process knows was removed leaves them: the reconciliation is retried at the next stream open or read change.
     */
    private suspend fun reconcileConversationCards(
        accountRef: String,
        groupIdHex: String,
        cards: List<ActiveConversationCard>,
        marmot: MarmotInterface? = null,
    ) {
        val snapshotStartedMs = System.currentTimeMillis()
        val state = readNotificationReconciliationState(accountRef, groupIdHex, marmot)
        val reconcile = { cancelReactions: Boolean, valid: (String?, Long) -> Boolean ->
            localNotificationPresenter.reconcileConversationContent(accountRef, groupIdHex, cancelReactions, valid)
        }
        val outcome =
            withNotificationReconcileLock(accountRef, groupIdHex) {
                when {
                    state == null -> {
                        withContext(Dispatchers.Default) {
                            reconcile(false) { id, _ ->
                                id == null || !notificationContentInvalidations.isRemoved(accountRef, groupIdHex, id)
                            }
                        }
                        "unavailable"
                    }
                    cards.all { card -> card.entryMessageIds.all { it == null } } ->
                        reconcileLegacyConversationCards(accountRef, groupIdHex, cards, state).logValue
                    else -> {
                        val (row, page, hidden) = state
                        val cancelReactions = hasRemovalWhileShown(page, hidden, cards.minOf { it.contentSinceMs })
                        withContext(Dispatchers.Default) {
                            reconcile(cancelReactions) { id, shownAtMs ->
                                isNotificationEntryStillShowable(
                                    accountRef = accountRef,
                                    groupIdHex = groupIdHex,
                                    messageIdHex = id,
                                    shownAtMs = shownAtMs,
                                    snapshotStartedMs = snapshotStartedMs,
                                    state = state,
                                )
                            }
                        }
                        "entries"
                    }
                }
            }
        appStateDebug { "notification reconcile outcome=$outcome" }
    }

    /** Applies the coarse decision to cards whose entries were posted without message identities. */
    private suspend fun reconcileLegacyConversationCards(
        accountRef: String,
        groupIdHex: String,
        cards: List<ActiveConversationCard>,
        state: NotificationReconciliationState,
    ): ActiveCardReconciliation {
        val messageCardId = cards.firstOrNull { it.isMessageCard }?.messageIdHex
        val outcome =
            reconcileActiveConversationCard(
                messageCardId,
                cards.minOf { it.contentSinceMs },
                state.row,
                state.page,
                state.hidden,
            )
        withContext(Dispatchers.Default) {
            when (outcome) {
                ActiveCardReconciliation.Retract ->
                    localNotificationPresenter.reconcileConversationContent(accountRef, groupIdHex, true) { _, _ ->
                        false
                    }
                ActiveCardReconciliation.CancelRead ->
                    localNotificationPresenter.cancelConversationCardIfSameGeneration(
                        LocalNotificationFormatter.conversationDismissalKey(accountRef, groupIdHex).tag,
                        LocalNotificationFormatter.MESSAGE_NOTIFICATION_ID,
                        messageCardId,
                    )
                ActiveCardReconciliation.Keep -> Unit
            }
        }
        return outcome
    }

    /**
     * Reads the hidden marks, chat-list row and bounded latest page one conversation's cards are checked against,
     * through [marmot] when the caller owns a runtime, retrying a failed read [NOTIFICATION_RECONCILIATION_ATTEMPTS]
     * times with a doubling delay. Null means the state stayed unavailable.
     */
    private suspend fun readNotificationReconciliationState(
        accountRef: String,
        groupIdHex: String,
        marmot: MarmotInterface? = null,
    ): NotificationReconciliationState? {
        var delayMillis = NOTIFICATION_RECONCILIATION_RETRY_MILLIS
        repeat(NOTIFICATION_RECONCILIATION_ATTEMPTS) { attempt ->
            runCatchingCancellable {
                val hidden = withContext(Dispatchers.IO) { hiddenMessageIdsInGroup(accountRef, groupIdHex) }
                val (row, page) =
                    if (marmot != null) {
                        withContext(Dispatchers.IO) {
                            marmot.chatListRow(accountRef, groupIdHex) to
                                marmot.timelineMessages(accountRef, notificationPageQuery(groupIdHex)).messages
                        }
                    } else {
                        marmotIo(MarmotTraceSection.CHAT_ROW_READ) { chatListRow(accountRef, groupIdHex) } to
                            notificationTimelinePage(accountRef, groupIdHex, NOTIFICATION_RECONCILIATION_PAGE_LIMIT)
                    }
                NotificationReconciliationState(row, page, hidden.mapTo(HashSet()) { it.lowercase() })
            }.onSuccess { return it }
            if (attempt < NOTIFICATION_RECONCILIATION_ATTEMPTS - 1) delay(delayMillis)
            delayMillis *= 2
        }
        return null
    }

    /** The reconciliation page query for one conversation. */
    private fun notificationPageQuery(groupIdHex: String) =
        TimelineMessageQueryFfi(
            groupIdHex = groupIdHex,
            search = null,
            before = null,
            beforeMessageId = null,
            after = null,
            afterMessageId = null,
            limit = NOTIFICATION_RECONCILIATION_PAGE_LIMIT,
        )

    /**
     * Whether a shown card entry may stay after reconciling against [state] read at [snapshotStartedMs] (#3323). An
     * entry the snapshot covers is judged by [isNotificationEntryShowable]. One it does not cover stays only if it was
     * shown after the snapshot started, so a message posted while the state was being read is never dropped, and an
     * older uncovered entry, which cannot be verified, leaves. An entry this process saw removed never stays.
     */
    private fun isNotificationEntryStillShowable(
        accountRef: String,
        groupIdHex: String,
        messageIdHex: String?,
        shownAtMs: Long,
        snapshotStartedMs: Long,
        state: NotificationReconciliationState,
    ): Boolean {
        val id = messageIdHex ?: return false
        val covered = state.page.any { it.messageIdHex.equals(id, ignoreCase = true) }
        val removedHere = notificationContentInvalidations.isRemoved(accountRef, groupIdHex, id)
        return !removedHere &&
            if (covered) {
                isNotificationEntryShowable(id, state.row, state.page, state.hidden)
            } else {
                id.lowercase() !in state.hidden && shownAtMs >= snapshotStartedMs - CARD_SNAPSHOT_SLACK_MILLIS
            }
    }

    /**
     * Durable engine mute for the update's conversation, resolved once per
     * update in [processNotificationUpdate] and threaded through the initial
     * post, optional enrichment, and the presenter's sync post-time re-check (which
     * cannot suspend). Prefers the active account's loaded projection;
     * otherwise reads the engine's notification settings directly, which also covers the
     * cold FCM process with no UI-owned controllers. Fail-open on errors: a
     * spurious notification from a muted chat beats a silently swallowed
     * real one.
     */
    private suspend fun engineNotificationMuted(update: NotificationUpdateFfi): Boolean {
        // Hex ids compare case-insensitively (projector idiom): a casing
        // drift here would silently fail open as a missed mute.
        if (activeAccountRef == update.accountRef) {
            chatsController
                ?.items
                ?.firstOrNull { it.group.groupIdHex.equals(update.groupIdHex, ignoreCase = true) }
                ?.let { return it.engineMuted() }
        }
        return runCatchingCancellable {
            chatMuteRepository.settings(update.accountRef, update.groupIdHex).getOrThrow().muted
        }.getOrDefault(false)
    }

    /** Writes one silent matching correction under the captured post generation. */
    private suspend fun postNotificationLateCorrection(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        content: NotificationFirstPostContent,
    ): Boolean {
        if (
            !isNotificationEnrichmentAllowed(withoutStaleQueuedReactionQuote(update, firstPost), firstPost) ||
            !firstPost.lateCorrectionPermit.acquire()
        ) {
            return false
        }
        var posted = false
        try {
            posted =
                withVerifiedReactionQuote(update, firstPost) { candidate, isNotDismissed ->
                    if (!isNotDismissed() || !isNotificationEnrichmentAllowed(candidate, firstPost)) {
                        return@withVerifiedReactionQuote false
                    }
                    localNotificationPresenter.show(
                        update = candidate,
                        conversationTitleOverride = content.conversationTitle,
                        senderNameOverride = content.senderName,
                        previewTextOverride = content.previewText,
                        reactedToPreviewOverride = verifiedReactionPreview(candidate, content.reactedToPreview),
                        mediaKind = content.mediaKind,
                        recipientAccountSubtext = content.recipientAccountSubtext,
                        directShareEligible = candidate.accountRef == activeAccountRef,
                        conversationAvatarBitmap = firstPost.avatars.conversationBitmap(candidate.isDm),
                        senderAvatarBitmap = firstPost.avatars.senderAvatarBitmap,
                        silentUpdate = true,
                        replaceCurrentMessage = true,
                        shortNpub = ::shortNpub,
                        isPostStillAllowed = {
                            isNotDismissed() && isNotificationEnrichmentAllowed(candidate, firstPost)
                        },
                    )
                }
        } finally {
            firstPost.lateCorrectionPermit.complete(posted)
        }
        return posted
    }

    /**
     * Publishes at most one silent text correction. A card first drawn without
     * its conversation or sender picture then gets at most one silent picture
     * rewrite once the avatar prewarm has made that picture available (#3311),
     * and a card that already showed its pictures is never repainted for imagery.
     * The prewarm runs here, and [deferPicture] receives the picture refresh it
     * enables, which the caller runs after releasing the enrichment permit.
     */
    private suspend fun enrichPostedNotificationUpdate(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        deferPicture: (LateNotificationIdentity) -> Unit,
    ): NotificationLateCorrectionOutcome {
        val candidate = withoutStaleQueuedReactionQuote(update, firstPost)
        return if (!isNotificationEnrichmentAllowed(candidate, firstPost)) {
            NotificationLateCorrectionOutcome.Stale
        } else {
            // Always run the post-deadline resolver. Even a complete local first
            // draw may contain deterministic mention/title fallbacks that need to
            // start ordinary profile hydration after the first notify has returned.
            val content =
                runCatchingCancellable {
                    notificationContentResolution.firstPost.resolve(candidate, localOnly = false)
                }.getOrNull() ?: firstPost.content
            when {
                content == null -> NotificationLateCorrectionOutcome.Failed
                !isNotificationEnrichmentAllowed(withoutStaleQueuedReactionQuote(candidate, firstPost), firstPost) ->
                    NotificationLateCorrectionOutcome.Stale
                else ->
                    enrichResolvedNotificationUpdate(
                        withoutStaleQueuedReactionQuote(candidate, firstPost),
                        firstPost,
                        content,
                        deferPicture,
                    )
            }
        }
    }

    /** Routes a stable resolved presentation to its single permitted correction type. */
    private suspend fun enrichResolvedNotificationUpdate(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        content: NotificationFirstPostContent,
        deferPicture: (LateNotificationIdentity) -> Unit,
    ): NotificationLateCorrectionOutcome {
        val resolvedPresentation =
            notificationContentPresentation(
                context = appContext,
                update = update,
                content = content.copy(reactedToPreview = verifiedReactionPreview(update, content.reactedToPreview)),
                shortNpub = ::shortNpub,
            )
        val outcome =
            if (
                notificationLateCorrectionPlan(
                    firstPresentation = firstPost.presentation,
                    resolvedPresentation = resolvedPresentation,
                ) == NotificationLateCorrectionPlan.Content
            ) {
                postNotificationContentCorrection(update, firstPost, content)
            } else {
                NotificationLateCorrectionOutcome.Unchanged
            }
        // Prime imagery only after any text correction is visible. A fallback
        // receives at most one text replacement for this message, and a stale
        // correction primes nothing.
        if (outcome != NotificationLateCorrectionOutcome.Stale) {
            prepareLateNotificationIdentity(update, firstPost, resolvedPresentation.content)?.let(deferPicture)
        }
        return outcome
    }

    /** Posts corrected text as this message's one silent late text write. */
    private suspend fun postNotificationContentCorrection(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        content: NotificationFirstPostContent,
    ): NotificationLateCorrectionOutcome =
        if (postNotificationLateCorrection(update, firstPost, content)) {
            NotificationLateCorrectionOutcome.ContentPosted
        } else {
            NotificationLateCorrectionOutcome.Stale
        }

    /**
     * #3311: runs the post-write avatar prewarm and returns the late picture
     * refresh it enables for a card first drawn without its conversation or
     * sender picture, or null when no picture can still arrive. [content] is
     * what the card shows.
     */
    private suspend fun prepareLateNotificationIdentity(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        content: LocalNotificationContent?,
    ): LateNotificationIdentity? {
        val warmed = notificationAvatarCoordinator.preWarm(update, firstPost.engineMuted)
        val urlSources = firstPost.avatars.lateIdentitySources(warmed, update.isDm)
        val sources =
            if (!update.isDm && urlSources.conversationBitmap == null && urlSources.conversationUrl.isNullOrBlank()) {
                urlSources.copy(conversationLoader = encryptedGroupIdentityLoader(update))
            } else {
                urlSources
            }
        return if (content != null && sources.hasMissingPicture) LateNotificationIdentity(content, sources) else null
    }

    /**
     * #3311: loads a group's Marmot encrypted picture, which has no URL, through the same MDK download and decoded
     * cache the group avatar uses on screen, or null when the group record carries no such picture. Nothing is
     * decrypted while the app lock shows, and a picture that finishes after the lock appears is dropped. A failed
     * read or download yields no picture, so the late refresh still applies a sender picture that did arrive, while
     * cancellation still propagates.
     */
    private suspend fun encryptedGroupIdentityLoader(update: NotificationUpdateFfi): (suspend () -> Bitmap?)? {
        val group =
            runCatchingCancellable { marmotIo { groupDetails(update.accountRef, update.groupIdHex) }.group }.getOrNull()
        val key = group?.let { encryptedGroupAvatarCacheKey(update.accountRef, it) } ?: return null
        return {
            if (appLockScreenVisible) {
                null
            } else {
                runCatchingCancellable {
                    GroupAvatarImageLoader
                        .load(key) { marmotIo { downloadGroupBlossomImage(update.accountRef, group.groupIdHex) } }
                        ?.asAndroidBitmap()
                        ?.takeUnless { appLockScreenVisible }
                }.getOrNull()
            }
        }
    }

    /**
     * #3311: gives the card the picture that has since become available, as
     * one silent rewrite of the same live card under every fence a late
     * correction obeys. It runs after the enrichment permit is released, since
     * it may wait for a download. App lock, a switched account, a newer epoch,
     * or a dismissed, read or replaced card stop it here or at the write, and
     * the presenter leaves redacted and hidden-preview cards alone.
     */
    private suspend fun refreshLateNotificationIdentity(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        late: LateNotificationIdentity,
    ) {
        // Avatar rewrites preserve live card text. Never replace its quote fence with an unquoted candidate:
        // that would permit the presenter's copy of a still-quoted live card to re-publish a removed excerpt.
        if (!isNotificationEnrichmentAllowed(update, firstPost)) return
        localNotificationPresenter.refreshLateIdentityImage(
            update = update,
            content = late.content,
            pictures = late.sources,
            directShareEligible = update.accountRef == activeAccountRef,
            isRefreshCurrent = { isNotificationEnrichmentAllowed(update, firstPost) },
        )
    }

    /** Posts either the resolved first-draw value or the existing safe fallback. */
    private suspend fun postInitialNotificationUpdate(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        receivedAtElapsedMs: Long,
    ): Boolean {
        appStateDebug {
            "notification eligibility outcome=${if (firstPost.shouldPost) "post" else "skip"} " +
                "trigger=${update.trigger} app_lock=$appLockScreenVisible engine_muted=${firstPost.engineMuted}"
        }
        val gateStartedAt = SystemClock.elapsedRealtime()
        val candidateState =
            if (firstPost.shouldPost) measureNotificationCandidateGate(update) else NotificationCandidateState.Unknown
        recordNotificationFirstPostTiming(
            stage = NotificationFirstPostTimingStage.CandidateGateComplete,
            receivedAtElapsedMs = receivedAtElapsedMs,
            stageStartedAtElapsedMs = gateStartedAt,
            outcome = candidateState.logValue,
        )
        if (!firstPost.shouldPost || candidateState != NotificationCandidateState.Unread) {
            PushWakeDiagnostics.event(PushWakeEvent.Suppressed)
            return false
        }

        val redactContent = appLockScreenVisible
        val onNotificationWritten = {
            recordNotificationFirstPostTiming(
                stage = NotificationFirstPostTimingStage.NotifyWritten,
                receivedAtElapsedMs = receivedAtElapsedMs,
                outcome = "posted",
            )
        }
        var posted = showInitialNotificationUpdate(update, firstPost, redactContent, onNotificationWritten)
        var postedRedacted = redactContent
        // Lock activation can race the suspending presenter setup. Retry only
        // as a redacted card, under the same generation and eligibility gates.
        if (!posted && !redactContent && appLockScreenVisible) {
            postedRedacted = true
            posted = showRedactedNotificationUpdate(update, firstPost, onNotificationWritten)
        }
        rememberPostedGroupInvite(update, firstPost, posted, postedRedacted)
        PushWakeDiagnostics.event(if (posted) PushWakeEvent.Posted else PushWakeEvent.Suppressed)
        return posted
    }

    /** Emits the actual admission/read wait through opt-in WNPerf, without account or message identifiers. */
    private suspend fun measureNotificationCandidateGate(update: NotificationUpdateFfi): NotificationCandidateState {
        val trace = PerformanceDiagnostics.begin(PerformanceOperation.NOTIFICATION_POST)
        val startedAt = SystemClock.elapsedRealtime()
        val state = notificationCandidateGate(update, "post")
        val elapsed = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
        PerformanceDiagnostics.record(
            trace = trace,
            phase = PerformancePhase.NOTIFICATION_CANDIDATE_GATE,
            elapsedMs = elapsed,
            durationMs = elapsed,
            result =
                when (state) {
                    NotificationCandidateState.Unread -> PerformanceResult.SUCCESS
                    NotificationCandidateState.Read, NotificationCandidateState.Removed -> PerformanceResult.DROPPED
                    NotificationCandidateState.Unknown -> PerformanceResult.FAILURE
                },
            layer = PerformanceLayer.ANDROID,
        )
        return state
    }

    /** Executes the resolved or fallback first write under the captured fences. */
    private suspend fun showInitialNotificationUpdate(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        redactContent: Boolean,
        onNotificationWritten: () -> Unit,
    ): Boolean =
        withVerifiedReactionQuote(update, firstPost) { candidate, isNotDismissed ->
            localNotificationPresenter.show(
                update = candidate,
                conversationTitleOverride = firstPost.content?.conversationTitle,
                senderNameOverride = firstPost.content?.senderName,
                previewTextOverride = firstPost.content?.previewText,
                reactedToPreviewOverride = verifiedReactionPreview(candidate, firstPost.content?.reactedToPreview),
                mediaKind = firstPost.content?.mediaKind ?: ReplyMediaKind.None,
                recipientAccountSubtext = firstPost.content?.recipientAccountSubtext,
                conversationAvatarBitmap = firstPost.avatars.conversationBitmap(candidate.isDm),
                senderAvatarBitmap = firstPost.avatars.senderAvatarBitmap,
                redactContent = redactContent,
                directShareEligible = !redactContent && candidate.accountRef == activeAccountRef,
                shortNpub = ::cachedShortNpubOrUnknown,
                onNotificationWritten = onNotificationWritten,
                isPostStillAllowed = {
                    isNotDismissed() &&
                        isQueuedReactionQuoteCurrent(candidate, firstPost) &&
                        isNotificationGenerationPostAllowed(
                            update = candidate,
                            postEpoch = firstPost.epoch,
                            accountCacheEpoch = firstPost.accountCacheEpoch,
                            engineMuted = firstPost.engineMuted,
                            requireUnlocked = !redactContent,
                        )
                },
            )
        }

    /** Retries a lock-raced initial write without carrying any decrypted content. */
    private suspend fun showRedactedNotificationUpdate(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        onNotificationWritten: () -> Unit,
    ): Boolean =
        localNotificationPresenter.show(
            update = update.copy(reactedToPreview = null),
            redactContent = true,
            shortNpub = ::cachedShortNpubOrUnknown,
            onNotificationWritten = onNotificationWritten,
            isPostStillAllowed = {
                isNotificationGenerationPostAllowed(
                    update = update.copy(reactedToPreview = null),
                    postEpoch = firstPost.epoch,
                    accountCacheEpoch = firstPost.accountCacheEpoch,
                    engineMuted = firstPost.engineMuted,
                    requireUnlocked = false,
                )
            },
        )

    /** Retains one posted invite's actual rendered name and shared correction budget. */
    private fun rememberPostedGroupInvite(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        posted: Boolean,
        redactContent: Boolean,
    ) {
        postedGroupInviteIdentity(
            update = update,
            posted = posted,
            redactContent = redactContent,
            displayedName =
                firstPost.content?.senderName
                    ?: notificationDisplayNameHint(update.sender.displayName),
        )?.let { identity ->
            inviteNotificationIdentityRefreshStore.rememberPosted(
                identity = identity.identity,
                displayedName = identity.displayedName,
                postEpoch = firstPost.epoch,
                accountCacheEpoch = firstPost.accountCacheEpoch,
                engineMuted = firstPost.engineMuted,
                lateCorrectionPermit = firstPost.lateCorrectionPermit,
            )
        }
    }

    /** Schedules generation-fenced late content or avatar correction work. */
    private fun scheduleNotificationEnrichment(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        receivedAtElapsedMs: Long,
    ) {
        notificationScope.launch(notificationDispatcher) {
            // Every successful first post re-reads the read state first, ahead of the enrichment fences and queue, so
            // a read that committed while the first write was in flight cancels the card at once (#2065). A read
            // after this point reaches a card that already exists, which its read-state change then reconciles.
            if (!isNotificationCandidateStillUnread(update, "enrich")) return@launch
            var latePicture: LateNotificationIdentity? = null
            notificationEnrichmentGate.withPermit {
                if (!isNotificationEnrichmentAllowed(withoutStaleQueuedReactionQuote(update, firstPost), firstPost)) {
                    return@withPermit
                }
                val outcome = enrichPostedNotificationUpdate(update, firstPost) { latePicture = it }
                appStateDebug {
                    "notification timing stage=late-correction " +
                        "elapsed_ms=${(SystemClock.elapsedRealtime() - receivedAtElapsedMs).coerceAtLeast(0L)} " +
                        "outcome=${outcome.timingValue}"
                }
            }
            // Reuse this enrichment pass's candidate result for its picture work. Runtime events reconcile
            // subsequent read/removal changes, and every picture write still checks the live generation fences.
            // Picture downloads run outside the permit and never delay another message's text correction.
            latePicture?.let { refreshLateNotificationIdentity(update, firstPost, it) }
        }
    }

    /**
     * Re-checks a posted candidate before detached work rewrites its card (#2065, #3323). A candidate that became
     * read since its post has that card cancelled while it still shows this message, so a newer sibling survives;
     * a deleted or hidden one was already retracted, and an unreadable state lets the rewrite proceed.
     */
    private suspend fun isNotificationCandidateStillUnread(
        update: NotificationUpdateFfi,
        phase: String,
    ): Boolean {
        val state = notificationCandidateGate(update, phase)
        if (state == NotificationCandidateState.Read) {
            withContext(Dispatchers.Default) {
                localNotificationPresenter.cancelNotificationUpdateCardIfCurrent(update)
            }
        }
        // An unreadable state never cancels or freezes a card that already posted: its enrichment proceeds.
        return state == NotificationCandidateState.Unread || state == NotificationCandidateState.Unknown
    }

    /** Runs receipt scheduling beside the first post, so queueing media never delays the notification. */
    private fun scheduleIncomingAttachmentDownloadMaintenance(update: NotificationUpdateFfi) {
        notificationScope.launch(notificationDispatcher) {
            scheduleIncomingAttachmentDownloads(update)
        }
    }

    private fun signalNotificationDrain() {
        notificationDrainSignals.tryEmit(notificationDrainSequence.incrementAndGet())
    }

    private fun startNotificationListener() {
        if (networkNotificationRecoverySuppressed) return
        notificationJob.startIfInactive(::launchNotificationListenerLoop)
    }

    private fun launchNotificationListenerLoop(): Job =
        notificationScope.launch(notificationDispatcher) {
            runNotificationListenerLoop(marmot())
        }

    /**
     * Schedules the media of every received chat message from the runtime event stream, alongside the notification
     * stream. MDK resolves an ordinary message in a muted chat to no notification update, and refuses every update
     * while an account's notifications are off, so receipt scheduling that rode only that stream would leave their
     * attachments unscheduled, although #3308 holds them to the account's media policy. Whether a notification was
     * suppressed cannot be read back later from current settings, so every message is scheduled here, idempotently,
     * and the first of the two sources runs its bounded lookup. [initial] is the subscription the startup listener
     * opened before the runtime started, so no message delivered after start is missed. A stream that ends or
     * fails, and a failed open, are retried with the capped backoff for as long as the listener runs. Lookups run
     * as supervised children of this loop, so they end with it, and a failing one is logged without cancelling the
     * loop or the notification listener above it. The same stream carries MDK's accepted deletions, so each event
     * also retracts removed notification content, and each open reconciles the cards already shown (#3323).
     */
    internal suspend fun runMutedChatReceiptLoop(
        marmot: MarmotInterface,
        initial: EventsSubscription? = null,
    ) = supervisorScope {
        var backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
        var preopened = initial
        val readReconciliations =
            NotificationReadReconciliationCoordinator(
                scope = this,
                followUpMillis = NOTIFICATION_READ_RECONCILE_FOLLOW_UP_MILLIS,
            ) { account, group ->
                runCatchingCancellable { reconcileConversationNotificationCards(account, group, marmot) }
                    .onFailure { appStateDebug(it) { "notification read reconcile failed" } }
            }
        while (isActive) {
            val subscription =
                preopened?.also { preopened = null } ?: runCatchingCancellable { marmot.subscribeEvents() }
                    .onFailure {
                        val type = it.javaClass.simpleName
                        Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_stream_unavailable type=$type")
                    }.getOrNull()
            // A failed open is retried with the capped backoff for as long as the listener runs: muted chats and
            // notifications-off accounts get no notification update, so this loop is their only receipt source.
            if (subscription != null) {
                try {
                    // Each open requests cleanup for cards left while the process or stream was down. It shares
                    // read-state workers: a newer open marks a blocked pass dirty and retains its latest follow-up.
                    runCatchingCancellable { requestActiveCardReconciliation(readReconciliations) }
                        .onFailure { appStateDebug(it) { "notification reconcile failed" } }
                    runCatchingCancellable {
                        var event = subscription.next()
                        while (event != null) {
                            backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
                            handleNotificationContentEvent(event, readReconciliations)
                            val received = (event as? MarmotEventFfi.MessageReceived)?.received
                            // Only the cheap kind and sender check runs here; the suppression reads run with the
                            // lookup, as a child of this loop, so they never delay the next event.
                            if (received != null && received.isChatFromOthers()) {
                                // A supervised child whose failure is logged, so one receipt can never cancel the
                                // loop or, through it, the notification listener.
                                launch(notificationDispatcher) {
                                    runCatchingCancellable { scheduleRuntimeEventReceipt(received) }
                                        .onFailure { Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_schedule_failed") }
                                }
                            }
                            event = subscription.next()
                        }
                    }.onFailure { Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_stream_failed") }
                } finally {
                    withContext(NonCancellable) { runCatching { subscription.destroy() } }
                }
            }
            delay(backoffMillis)
            backoffMillis = nextRetryBackoffMillis(backoffMillis, NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS)
        }
    }

    /**
     * Applies [event]'s notification consequences without delaying the receipt loop that delivered it (#3323,
     * #2065). A removal is fenced at once, so no queued post or correction can restore it, and its cards are
     * rewritten in a supervised child. Read-state changes share one worker per conversation, including a delayed
     * follow-up after its latest request so a card not yet listed by the platform is also corrected. An in-flight
     * native read finishes before a dirty worker reads again. Failures are logged without cancelling the loop.
     */
    private fun CoroutineScope.handleNotificationContentEvent(
        event: MarmotEventFfi,
        readReconciliations: NotificationReadReconciliationCoordinator,
    ) {
        removedNotificationMessages(event).forEach { message ->
            recordNotificationContentRemoval(message)
            launch {
                runCatchingCancellable { retractNotificationContent(message) }
                    .onFailure { appStateDebug(it) { "notification retraction failed" } }
            }
        }
        readStateChangedConversation(event)?.let { (accountRef, groupIdHex) ->
            readReconciliations.request(accountRef, groupIdHex)
        }
    }

    /**
     * Reconciles one conversation's shown cards after its read state changed, so a message read anywhere this
     * account's MDK state reflects leaves the tray and a newer unread sibling stays (#2065).
     */
    private suspend fun reconcileConversationNotificationCards(
        accountRef: String,
        groupIdHex: String,
        marmot: MarmotInterface? = null,
    ) {
        val cards =
            withContext(Dispatchers.Default) { localNotificationPresenter.activeConversationCards() }
                .filter { it.accountRef == accountRef && it.groupIdHex.equals(groupIdHex, ignoreCase = true) }
        if (cards.isNotEmpty()) reconcileConversationCards(accountRef, groupIdHex, cards, marmot)
    }

    /** Whether [this] is a chat message from someone else, the only received messages that carry attachments. */
    private fun RuntimeMessageReceivedFfi.isChatFromOthers(): Boolean =
        message.kind == MARMOT_CHAT_EVENT_KIND && !message.sender.equals(accountIdHex, ignoreCase = true)

    /**
     * Schedules one received chat message's media from the runtime event stream, whatever its notification did. The
     * notification outcome cannot be inferred later from current settings: a chat muted when MDK suppressed the
     * update may be unmuted by the time this runs. Scheduling is idempotent, and [receiptClaims] lets only the first
     * of the two receipt sources run the message's bounded history lookup.
     */
    private suspend fun scheduleRuntimeEventReceipt(received: RuntimeMessageReceivedFfi) {
        val message = received.message
        scheduleReceivedMessageAttachments(
            accountRef = received.accountLabel,
            groupIdHex = message.groupIdHex,
            messageIdHex = message.messageIdHex,
            receivedAtMillis = message.receivedAt.toLong() * MILLIS_PER_SECOND_LONG,
        )
    }

    /**
     * Opens the runtime event subscription the receipt loop reads, before [marmot] starts, so a message delivered
     * between start and the listener's own subscriptions still reaches a receipt scheduler. MDK's event firehose is a
     * broadcast receiver that needs no started runtime. A failure leaves the loop to open its own.
     */
    private fun openStartupReceiptSubscription(marmot: MarmotInterface) {
        val subscription = runCatching { marmot.subscribeEvents() }.getOrNull() ?: return
        synchronized(startupReceiptLock) {
            startupReceiptSubscription?.second?.let { runCatching { it.destroy() } }
            startupReceiptSubscription = marmot to subscription
        }
    }

    /** Hands the startup receipt subscription of [marmot] to its single consumer, or null when there is none. */
    private fun takeStartupReceiptSubscription(marmot: MarmotInterface): EventsSubscription? =
        synchronized(startupReceiptLock) {
            startupReceiptSubscription
                ?.takeIf { it.first === marmot }
                ?.second
                .also { if (it != null) startupReceiptSubscription = null }
        }

    internal suspend fun runNotificationListenerLoop(marmot: MarmotInterface) {
        // Restart the subscription on any failure (or clean end-of-stream)
        // with exponential backoff, so a transient relay/binding error
        // doesn't permanently silence notifications. Backoff resets after
        // each received update; cancellation propagates and stops the loop.
        // See #56.
        var backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
        var receiptLoop: Job? = null
        while (currentCoroutineContext().isActive) {
            val retryWakeGeneration = notificationReceiverRetryWake.value
            try {
                val subscription = notificationSubscriber(marmot)
                // Only once the notification subscription is open, so start's no-replay window stays unwidened, and
                // as a child of this listener on IO, so it stops with notifications without delaying them.
                // Started again whenever an earlier one ended, for example after its event stream kept failing to open.
                val listenerScope = CoroutineScope(currentCoroutineContext())
                receiptLoop =
                    receiptLoop?.takeIf { it.isActive } ?: listenerScope.launch(Dispatchers.IO) {
                        // Contained, so a receipt failure is logged and never cancels notification delivery.
                        val startup = takeStartupReceiptSubscription(marmot)
                        runCatchingCancellable { runMutedChatReceiptLoop(marmot, startup) }
                            .onFailure { Log.w(INCOMING_ATTACHMENT_TAG, "muted_receipt_loop_failed") }
                    }
                runtimeListenerTeardownOwner.withNotificationSubscription(subscription) {
                    while (currentCoroutineContext().isActive) {
                        val update = subscription.next() ?: break
                        backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
                        withContext(Dispatchers.Main.immediate) {
                            processNotificationUpdate(update)
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (throwable: Throwable) {
                appStateDebug(throwable) {
                    "notification listener error; retrying in ${backoffMillis}ms: ${throwable.readableMessage()}"
                }
            }
            if (!currentCoroutineContext().isActive) break
            awaitNotificationRetryWindow(notificationReceiverRetryWake, retryWakeGeneration, backoffMillis)
            backoffMillis = nextRetryBackoffMillis(backoffMillis, NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS)
        }
    }

    /** Posts and enriches one update under a foreground/app-lock eligibility epoch. */
    private suspend fun processNotificationUpdate(incoming: NotificationUpdateFfi) {
        val contentRemovalVersion = notificationContentInvalidations.removalVersion(incoming.accountRef)
        val update = withoutUnverifiableReactionQuote(incoming)
        PushWakeDiagnostics.event(PushWakeEvent.Candidate)
        val receivedAtElapsedMs = SystemClock.elapsedRealtime()
        recordNotificationFirstPostTiming(
            stage = NotificationFirstPostTimingStage.Received,
            receivedAtElapsedMs = receivedAtElapsedMs,
            outcome = "observed",
        )
        applyNotificationDisplayNameHint(update)
        scheduleIncomingAttachmentDownloadMaintenance(update)
        val postEpoch = notificationPostEpoch.capture()
        val accountCacheEpoch = profileCacheLifetime.capture()
        val engineMuted = engineNotificationMuted(update)
        val shouldPost = shouldPostNotification(update, engineMuted)
        recordNotificationFirstPostTiming(
            stage = NotificationFirstPostTimingStage.EligibilityComplete,
            receivedAtElapsedMs = receivedAtElapsedMs,
            outcome = "resolved",
        )
        val firstPost =
            resolveNotificationFirstPost(
                update,
                postEpoch,
                accountCacheEpoch,
                engineMuted,
                shouldPost,
                receivedAtElapsedMs,
            ).copy(contentRemovalVersion = contentRemovalVersion)
        postBeforeNotificationEnrichment(
            post = { postInitialNotificationUpdate(update, firstPost, receivedAtElapsedMs) },
            scheduleEnrichment = {
                scheduleNotificationEnrichment(update, firstPost, receivedAtElapsedMs)
            },
        )
        schedulePostNotificationMaintenance(update)
    }

    /**
     * Drops opaque reaction quotes while a removal for this chat remains in the bounded process guard (#3323).
     * Already-queued quotes retain the account-version fence across identity eviction. Persisted hides alone do
     * not suppress quotes after restart; new targets after eviction/restart await MDK #2366. Reactions still post.
     */
    private fun withoutUnverifiableReactionQuote(update: NotificationUpdateFfi): NotificationUpdateFfi {
        val removed = notificationContentInvalidations.hasRemovalInConversation(update.accountRef, update.groupIdHex)
        return if (LocalNotificationFormatter.isReaction(update) && removed) {
            update.copy(reactedToPreview = null)
        } else {
            update
        }
    }

    /** Resolved overrides must obey the same quote removal as the original typed update. */
    private fun verifiedReactionPreview(
        update: NotificationUpdateFfi,
        preview: String?,
    ): String? {
        val quoteUnavailable = LocalNotificationFormatter.isReaction(update) && update.reactedToPreview.isNullOrBlank()
        return preview.takeUnless { quoteUnavailable }
    }

    /** Drops only an unverified quote; unrelated removals must not discard the reaction itself. */
    private fun withoutStaleQueuedReactionQuote(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
    ): NotificationUpdateFfi =
        if (isQueuedReactionQuoteCurrent(update, firstPost)) {
            withoutUnverifiableReactionQuote(update)
        } else {
            update.copy(reactedToPreview = null)
        }

    /** Retries once without its quote if a removal races the final write; every other fence is checked again. */
    private suspend fun withVerifiedReactionQuote(
        update: NotificationUpdateFfi,
        firstPost: NotificationFirstPost,
        post: suspend (NotificationUpdateFfi, () -> Boolean) -> Boolean,
    ): Boolean =
        localNotificationPresenter.withShowLifetime(update) { isNotDismissed ->
            val current = withoutStaleQueuedReactionQuote(update, firstPost)
            if (!isNotDismissed()) return@withShowLifetime false
            if (post(current, isNotDismissed)) return@withShowLifetime true
            val unquoted = withoutStaleQueuedReactionQuote(current, firstPost)
            unquoted != current && isNotDismissed() && post(unquoted, isNotDismissed)
        }

    /** Exercises the exact typed-update production path from JVM or instrumentation acceptance tests. */
    internal suspend fun processNotificationUpdateForTest(update: NotificationUpdateFfi) {
        withContext(Dispatchers.Main.immediate) { processNotificationUpdate(update) }
    }

    /** Runs receipt scheduling to completion, so a test can assert that a receipt queued nothing. */
    internal suspend fun scheduleIncomingAttachmentDownloadsForTest(update: NotificationUpdateFfi) {
        scheduleIncomingAttachmentDownloads(update)
    }

    /** Resolves all first-write content under the coordinator's one absolute budget. */
    private suspend fun resolveNotificationFirstPost(
        update: NotificationUpdateFfi,
        postEpoch: Long,
        accountCacheEpoch: Long,
        engineMuted: Boolean,
        shouldPost: Boolean,
        receivedAtElapsedMs: Long,
    ): NotificationFirstPost {
        val stage = notificationFirstPostContentCoordinator.startStage()
        val result =
            if (shouldPost && !appLockScreenVisible) {
                notificationFirstPostContentCoordinator.resolve(stage) {
                    if (update.isDm || update.trigger != NotificationTriggerFfi.NEW_MESSAGE) {
                        warmPrivateContactAvatar(update.accountRef, update.sender.accountIdHex)
                    }
                    notificationContentResolution.firstPost.resolve(update, localOnly = true)
                }
            } else {
                null
            }
        result?.let {
            recordNotificationFirstPostTiming(
                stage = NotificationFirstPostTimingStage.ContentComplete,
                receivedAtElapsedMs = receivedAtElapsedMs,
                stageStartedAtElapsedMs = stage.startedAtElapsedMillis,
                outcome = it.timingOutcome(),
            )
        }
        val content =
            when (result) {
                is NotificationFirstPostContentResult.Resolved -> result.value
                else -> null
            }
        return NotificationFirstPost(
            epoch = postEpoch,
            accountCacheEpoch = accountCacheEpoch,
            engineMuted = engineMuted,
            shouldPost = shouldPost,
            content = content,
            presentation = notificationContentPresentation(appContext, update, content, ::cachedShortNpubOrUnknown),
            avatars = readyNotificationAvatars(update),
        )
    }

    /** Snapshots decoded local imagery without hydration; the existing monogram covers misses. */
    internal fun readyNotificationAvatars(update: NotificationUpdateFfi): PreWarmedNotificationAvatars {
        if (appLockScreenVisible) return PreWarmedNotificationAvatars(null, null)
        val senderUrl =
            if (update.isDm || update.trigger != NotificationTriggerFfi.NEW_MESSAGE) {
                synchronized(profilePresentationLock) {
                    profilePresentations[update.sender.accountIdHex]?.avatarUrl
                } ?: update.sender.pictureUrl
            } else {
                null
            }
        val group =
            chatsController
                ?.takeIf { it.boundAccountRef == update.accountRef && update.accountRef == activeAccountRef }
                ?.items
                ?.firstOrNull { it.id.equals(update.groupIdHex, ignoreCase = true) }
                ?.group
        val ready = readyNotificationAvatarSnapshot(senderUrl, group?.avatarUrl)
        val encryptedKey = group?.let { encryptedGroupAvatarCacheKey(update.accountRef, it) }
        return ready.copy(
            groupAvatarBitmap = ready.groupAvatarBitmap ?: GroupAvatarImageLoader.peek(encryptedKey)?.asAndroidBitmap(),
        )
    }

    /** Publishes one fixed-label timing event without notification identity or content. */
    private fun recordNotificationFirstPostTiming(
        stage: NotificationFirstPostTimingStage,
        receivedAtElapsedMs: Long,
        stageStartedAtElapsedMs: Long? = null,
        outcome: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        val event =
            NotificationFirstPostTimingEvent(
                stage = stage,
                observedAtElapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos(),
                elapsedSinceReceiptMillis = (now - receivedAtElapsedMs).coerceAtLeast(0L),
                stageElapsedMillis = stageStartedAtElapsedMs?.let { (now - it).coerceAtLeast(0L) },
                outcome = outcome,
            )
        appStateDebug {
            "notification timing stage=${stage.name} elapsed_ms=${event.elapsedSinceReceiptMillis} " +
                "stage_ms=${event.stageElapsedMillis ?: -1L} outcome=$outcome"
        }
        notificationFirstPostTimingObserver?.invoke(event)
    }

    private fun schedulePostNotificationMaintenance(update: NotificationUpdateFfi) {
        if (networkNotificationRecoverySuppressed) return
        unreadRefreshScheduler.schedule(update.accountRef)
        signalNotificationDrain()
    }

    private fun scheduleInviteNotificationIdentityRefresh(
        senderAccountIdHex: String,
        presentation: ProfilePresentation,
    ) {
        val candidates =
            inviteNotificationIdentityRefreshStore.refreshCandidates(
                senderAccountIdHex = senderAccountIdHex,
                resolvedName = presentation.displayName,
            )
        candidates.forEach(::launchInviteNotificationIdentityRefresh)
    }

    private fun resumePendingInviteNotificationIdentityRefreshes() {
        inviteNotificationIdentityRefreshStore
            .claimPendingRefreshes()
            .forEach(::launchInviteNotificationIdentityRefresh)
    }

    /** Runs one invite-name correction under the original post generations and shared write permit. */
    private fun launchInviteNotificationIdentityRefresh(initialCandidate: RefreshCandidate) {
        val update = initialCandidate.identity.asNotificationUpdate()
        profileScope.launch {
            inviteNotificationIdentityRefreshStore.runClaimedRefresh(initialCandidate) {
                refreshInviteNotificationIdentity(initialCandidate, update)
            }
        }
    }

    /** Applies stale, lock-deferred, single-writer, and active-card rules to one invite candidate. */
    private suspend fun refreshInviteNotificationIdentity(
        candidate: RefreshCandidate,
        update: NotificationUpdateFfi,
    ) {
        when {
            !isInviteNotificationRefreshCurrent(candidate, update) ->
                inviteNotificationIdentityRefreshStore.forget(candidate)

            appLockScreenVisible -> deferInviteNotificationIdentityRefresh(candidate)

            !isNotificationEnrichmentAllowed(
                update,
                candidate.postEpoch,
                candidate.accountCacheEpoch,
                candidate.engineMuted,
            ) -> inviteNotificationIdentityRefreshStore.forget(candidate)

            !candidate.lateCorrectionPermit.acquire() ->
                inviteNotificationIdentityRefreshStore.forget(candidate)

            else -> postInviteNotificationIdentityCorrection(candidate, update)
        }
    }

    /** Requires both captured generations and the exact active invite card. */
    private fun isInviteNotificationRefreshCurrent(
        candidate: RefreshCandidate,
        update: NotificationUpdateFfi,
    ): Boolean =
        inviteNotificationIdentityRefreshStore.isCurrent(candidate) &&
            isNotificationGenerationPostAllowed(
                update = update,
                postEpoch = candidate.postEpoch,
                accountCacheEpoch = candidate.accountCacheEpoch,
                engineMuted = candidate.engineMuted,
                requireUnlocked = false,
            ) &&
            localNotificationPresenter.isGroupInviteNotificationActive(update)

    /** Releases a locked candidate and closes the unlock/reclaim race. */
    private fun deferInviteNotificationIdentityRefresh(candidate: RefreshCandidate) {
        inviteNotificationIdentityRefreshStore.release(candidate)
        if (!appLockScreenVisible) resumePendingInviteNotificationIdentityRefreshes()
    }

    /** Consumes or restores the shared write permit according to the platform result. */
    private suspend fun postInviteNotificationIdentityCorrection(
        candidate: RefreshCandidate,
        update: NotificationUpdateFfi,
    ) {
        var correctionPosted = false
        try {
            if (!isInviteNotificationRefreshCurrent(candidate, update)) {
                inviteNotificationIdentityRefreshStore.forget(candidate)
                return
            }
            val result =
                refreshActiveInviteNotificationIdentity(
                    update = update,
                    resolvedProfileName = candidate.resolvedName,
                    postEpoch = candidate.postEpoch,
                    accountCacheEpoch = candidate.accountCacheEpoch,
                    engineMuted = candidate.engineMuted,
                    isCandidateCurrent = { inviteNotificationIdentityRefreshStore.isCurrent(candidate) },
                ).also { correctionPosted = it.posted }
            when {
                result.posted -> inviteNotificationIdentityRefreshStore.forget(candidate)
                !localNotificationPresenter.isGroupInviteNotificationActive(update) ->
                    inviteNotificationIdentityRefreshStore.forget(candidate)
                else -> inviteNotificationIdentityRefreshStore.release(candidate)
            }
        } finally {
            candidate.lateCorrectionPermit.complete(correctionPosted)
        }
    }

    /** Re-renders an active invite only if its captured account and notification generation remain current. */
    private suspend fun refreshActiveInviteNotificationIdentity(
        update: NotificationUpdateFfi,
        resolvedProfileName: String?,
        postEpoch: Long,
        accountCacheEpoch: Long,
        engineMuted: Boolean,
        isCandidateCurrent: () -> Boolean,
    ): InviteNotificationIdentityRefreshResult {
        val skipEnrichmentForLock = appLockScreenVisible
        val resolvedName =
            if (skipEnrichmentForLock) {
                null
            } else {
                notificationContentResolution.identity.senderName(update)
                    ?: resolvedProfileName
                    ?: notificationDisplayNameHint(update.sender.displayName)
            }
        // A lock can arrive during the suspending lookup above. Re-check after
        // enrichment so the silent update cannot reveal identity behind the lock.
        val redactContent = skipEnrichmentForLock || appLockScreenVisible
        val displayedName = resolvedName.takeUnless { redactContent }
        val posted =
            localNotificationPresenter.show(
                update = update,
                senderNameOverride = displayedName,
                recipientAccountSubtext =
                    if (redactContent) {
                        null
                    } else {
                        notificationContentResolution.firstPost.recipientAccountSubtext(
                            update,
                            localOnly = false,
                        )
                    },
                redactContent = redactContent,
                directShareEligible = !redactContent && update.accountRef == activeAccountRef,
                silentUpdate = true,
                shortNpub = ::shortNpub,
                isPostStillAllowed = {
                    isCandidateCurrent() &&
                        isNotificationEnrichmentAllowed(
                            update,
                            postEpoch,
                            accountCacheEpoch,
                            engineMuted,
                        )
                },
            )
        return InviteNotificationIdentityRefreshResult(
            posted = posted,
            displayedName = displayedName,
            contentRedacted = redactContent,
        )
    }

    private fun updateBackgroundConnectionPreference(enabled: Boolean) {
        if (!enabled) nativePushFallback.invalidateAll()
        backgroundConnectionEnabled = enabled
        BackgroundConnectionPreferences.setEnabled(appContext, enabled)
        appStateDebug { "background connection enabled=$enabled" }
    }

    private fun startBackgroundConnectionService(): Boolean {
        val started =
            NotificationStreamForegroundService.start(
                appContext,
                ForegroundStartTrigger.UserToggle,
            )
        appStateDebug { "background connection service start=$started" }
        return started
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
        bumpProfileAccountRevision(accountIdHex)
    }

    /** Read one persisted profile without mutating the active account caches. */
    private suspend fun loadAccountSwitchProfileSeed(id: String): AccountSwitchProfileSeed =
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
            AvatarImageLoader.preWarm(presentation.avatarUrl)
        }
        if (changed) {
            profileRevision += 1
            bumpProfileAccountRevision(accountIdHex)
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
        bumpAllProfileAccountRevisions()
    }

    // Keep platform callbacks at the end of instance initialization. These
    // coroutines can execute immediately on another thread and must not observe
    // fields declared later in this class before their initializers have run.
    init {
        if (startPlatformServices) {
            mutationsScope.launch { notificationPreviewSettings.recover() }
            mutationsScope.launch {
                val policy = withContext(Dispatchers.IO) { readNotificationBatteryPolicy(appContext) }
                notificationBatteryPolicy = policy
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
            mutationsScope.launch(Dispatchers.IO) { diskMediaCache.prepare() }
            mutationsScope.launch(Dispatchers.IO) { contactPictureStore.cleanOrphans() }
            // Load the persisted per-chat channel scopes before the settings UI
            // can request them, without blocking the main-thread constructor.
            mutationsScope.launch(Dispatchers.IO) { conversationNotificationRouting }
            mutationsScope.launch(Dispatchers.IO) { conversationAlertPreferences }
            if (requireAppUnlock) {
                // Pre-warm the Keystore-backed unlock timestamp off-main so the
                // first foreground lock evaluation is a cache hit. Assigned on
                // Main, and only if an unlock hasn't already stamped a newer value.
                mutationsScope.launch {
                    val warmed = withContext(Dispatchers.IO) { AppLockPreferences.readLastUnlockedAtMillis(appContext) }
                    if (lastAppUnlockAtMillisBacking == null) lastAppUnlockAtMillisBacking = warmed
                }
            }
            mutationsScope.launch { refreshTtsAvailability() }
        }
    }

    companion object {
        private const val ACTIVE_ACCOUNT_KEY = "active_account"
        private const val DEVELOPER_MODE_KEY = "developer_mode"
        private const val STREAMING_DEBUG_MODE_KEY = "streaming_debug_mode"
        private const val FORCE_INCOGNITO_KEYBOARD_KEY = "force_incognito_keyboard"
        private const val REQUIRE_APP_UNLOCK_KEY = "require_app_unlock"
        private const val APP_LOCK_DELAY_KEY = "app_lock_delay"
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
        private const val DEFAULT_NOTIFICATIONS_ENABLE_ATTEMPTED_KEY = "default_notifications_enable_attempted"
        private const val DISAPPEARING_TOOLTIP_SHOWN_KEY = "disappearing_tooltip_shown"

        private const val PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS = 60_000L
        private const val PROFILE_PRESENTATION_WARM_FANOUT = 6
        private const val PROFILE_REFRESH_FANOUT = 6
        private const val NOTIFICATION_ENRICHMENT_FANOUT = 4

        // Bulk account-unread refresh runs on cold start/account switch. Bound
        // both dimensions of the FFI fan-out: accounts and per-account rosters.
        private const val ACCOUNT_UNREAD_ACCOUNT_FANOUT = 4
        private const val ACCOUNT_UNREAD_MEMBER_FANOUT = 4
        private const val MAX_PROFILE_PRESENTATION_CACHE_ENTRIES = 4096
        private const val MAX_USER_PROFILE_CACHE_ENTRIES = 4096
        private const val MAX_PENDING_AVATAR_PREWARMS = 64
        private const val MAX_MATERIALIZING_PROFILES = 4096
        private const val MAX_GROUP_MEMBER_SNAPSHOT_CACHE_ENTRIES = 1024
        private const val MAX_ACCOUNT_SCOPED_UI_CACHE_ENTRIES = 4096
        private const val NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS = 1_000L
        private const val NOTIFICATION_CONTENT_INVALIDATION_CAPACITY = 512
        private const val NOTIFICATION_CANDIDATE_PAGE_LIMIT = 30u
        private const val NOTIFICATION_RECONCILIATION_PAGE_LIMIT = 200u
        private const val NOTIFICATION_RECONCILIATION_ATTEMPTS = 3
        private const val NOTIFICATION_CANDIDATE_ATTEMPTS = 2
        private const val NOTIFICATION_CANDIDATE_TIMEOUT_MILLIS = 1_500L
        private const val NOTIFICATION_READ_RECONCILE_FOLLOW_UP_MILLIS = 1_000L
        private const val CARD_SNAPSHOT_SLACK_MILLIS = 1_000L
        private const val NOTIFICATION_RECONCILIATION_RETRY_MILLIS = 1_000L

        /** How many receipt history lookups run at once; more wait their turn rather than fan out. */
        private const val RECEIPT_LOOKUP_CONCURRENCY = 4

        /** How many recent receipts are remembered for de-duplicating the two receipt sources. */
        private const val RECEIPT_CLAIM_CAPACITY = 512

        /** The Marmot app-event kind of a chat message, the only kind that carries attachments. */
        private const val MARMOT_CHAT_EVENT_KIND = 9uL

        /** Converts the runtime's second timestamps for receipt lookups, which work in milliseconds. */
        private const val MILLIS_PER_SECOND_LONG = 1_000L
        private const val NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS = 60_000L
        private const val NOTIFICATION_STARTUP_RECEIVER_TIMEOUT_MILLIS = 5_000L
        private const val NOTIFICATION_NETWORK_RECOVERY_RECEIVER_TIMEOUT_MILLIS = 3_000L

        private const val NOTIFICATION_PUSH_DRAIN_TIMEOUT_MILLIS = 10_000L
        private const val MAX_RETAINED_CONVERSATION_STATES = 32
    }
}
