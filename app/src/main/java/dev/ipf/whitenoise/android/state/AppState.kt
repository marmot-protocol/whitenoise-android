package dev.ipf.whitenoise.android.state

import android.app.LocaleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.TextFieldValue
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberIdsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppMessageRecordFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.AuditLogTrackerConfigFfi
import dev.ipf.marmotkit.AuditLogUploadSourceFfi
import dev.ipf.marmotkit.ChatListMessagePreviewFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.ChatListSubscription
import dev.ipf.marmotkit.ChatsSubscription
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaUploadAttachmentRequestFfi
import dev.ipf.marmotkit.MediaUploadRequestFfi
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.NotificationTriggerFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationsSubscription
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.marmotkit.PushRegistrationShareOutcomeFfi
import dev.ipf.marmotkit.PushRegistrationShareStatusFfi
import dev.ipf.marmotkit.RelayTelemetryResourceFfi
import dev.ipf.marmotkit.RelayTelemetryRuntimeConfigFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.RetentionSweepGroupOutcomeFfi
import dev.ipf.marmotkit.RetentionSweepStatusFfi
import dev.ipf.marmotkit.TimelineMessageQueryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import dev.ipf.whitenoise.android.BuildConfig
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.amber.AmberSignerController
import dev.ipf.whitenoise.android.audio.tts.AndroidTtsSpeechEngine
import dev.ipf.whitenoise.android.audio.tts.TtsAudioFocusOwner
import dev.ipf.whitenoise.android.audio.tts.TtsController
import dev.ipf.whitenoise.android.audio.tts.TtsEngineChoice
import dev.ipf.whitenoise.android.audio.tts.TtsEngineHandle
import dev.ipf.whitenoise.android.audio.tts.TtsEngineResolver
import dev.ipf.whitenoise.android.audio.tts.TtsEngineSelectionResult
import dev.ipf.whitenoise.android.audio.tts.TtsEngineSelectionSnapshot
import dev.ipf.whitenoise.android.audio.tts.TtsHistoryPager
import dev.ipf.whitenoise.android.audio.tts.TtsHistorySession
import dev.ipf.whitenoise.android.audio.tts.TtsResolutionResult
import dev.ipf.whitenoise.android.audio.tts.TtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.adoptTtsEngineSelection
import dev.ipf.whitenoise.android.audio.tts.projectTtsSpeakableEntry
import dev.ipf.whitenoise.android.audio.tts.resolveTtsOnDispatcher
import dev.ipf.whitenoise.android.audio.tts.runtimeTrustForSelectionWarning
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.DiagnosticFormatter
import dev.ipf.whitenoise.android.core.ForwardMessagePayload
import dev.ipf.whitenoise.android.core.GroupAvatarImageLoader
import dev.ipf.whitenoise.android.core.GroupProjector
import dev.ipf.whitenoise.android.core.GroupSystemCopy
import dev.ipf.whitenoise.android.core.GroupSystemEvents
import dev.ipf.whitenoise.android.core.GroupTitleCopy
import dev.ipf.whitenoise.android.core.HostSafety
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.MarmotClient
import dev.ipf.whitenoise.android.core.MessageProjector
import dev.ipf.whitenoise.android.core.NostrProfileReference
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.core.ProfileSanitizer
import dev.ipf.whitenoise.android.core.ReplyMediaKind
import dev.ipf.whitenoise.android.core.chatListItemDisplayTitle
import dev.ipf.whitenoise.android.media.AndroidKeystoreDiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.media.AttachmentCachePublication
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.MediaInventory
import dev.ipf.whitenoise.android.media.MediaReferenceSupport
import dev.ipf.whitenoise.android.media.editor.CoalescingMessageDraftWriter
import dev.ipf.whitenoise.android.media.editor.EditorSessionStore
import dev.ipf.whitenoise.android.media.editor.EditorSourceStore
import dev.ipf.whitenoise.android.media.editor.MarmotMessageDraftGateway
import dev.ipf.whitenoise.android.media.editor.MessageDraftConditionalDeleteResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftMutationResult
import dev.ipf.whitenoise.android.media.editor.MessageDraftRepository
import dev.ipf.whitenoise.android.notifications.BackgroundConnectionPreferences
import dev.ipf.whitenoise.android.notifications.ConversationNotificationChannels
import dev.ipf.whitenoise.android.notifications.ConversationNotificationRouting
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPattern
import dev.ipf.whitenoise.android.notifications.ConversationVibrationPreferences
import dev.ipf.whitenoise.android.notifications.LocalNotificationFormatter
import dev.ipf.whitenoise.android.notifications.LocalNotificationPolicy
import dev.ipf.whitenoise.android.notifications.LocalNotificationPresenter
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
import dev.ipf.whitenoise.android.notifications.PushServerConfig
import dev.ipf.whitenoise.android.notifications.PushTokenStore
import dev.ipf.whitenoise.android.notifications.conversationShortcutId
import dev.ipf.whitenoise.android.notifications.normalizeNotificationReaction
import dev.ipf.whitenoise.android.notifications.notificationReplyRecoveryBoundary
import dev.ipf.whitenoise.android.notifications.notificationReplySendWindowReady
import dev.ipf.whitenoise.android.share.CappedShareStreamStaging
import dev.ipf.whitenoise.android.share.SHARE_STREAM_MAX_ITEMS
import dev.ipf.whitenoise.android.share.ShareInboundStager
import dev.ipf.whitenoise.android.share.SharePayload
import dev.ipf.whitenoise.android.share.ShareShortcutPublisher
import dev.ipf.whitenoise.android.share.ShareStagingStore
import dev.ipf.whitenoise.android.share.shareResolveMime
import dev.ipf.whitenoise.android.ui.chats.newchat.NewMessageDirectChatResolution
import dev.ipf.whitenoise.android.ui.chats.relaysConnectedFromHealth
import dev.ipf.whitenoise.android.ui.chats.relaysConnectedOnNetworkChange
import dev.ipf.whitenoise.android.ui.markdownDocumentMentionBech32s
import dev.ipf.whitenoise.android.ui.markdownDocumentToPreviewAnnotatedString
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import dev.ipf.whitenoise.android.notifications.notificationReplyCommitProbe as probeNotificationReplyCommit

sealed interface AppPhase {
    data object Bootstrapping : AppPhase

    data object Onboarding : AppPhase

    data object Ready : AppPhase

    data class Failed(
        val error: ErrorPresentation,
    ) : AppPhase
}

internal suspend fun resolveNotificationMentionDisplayName(
    bech32: String,
    accountIdHex: suspend (String) -> String?,
    profileDisplayName: (String) -> String?,
    readDisplayName: suspend (String) -> String?,
    requestProfile: (String) -> Unit,
): String? {
    val id = accountIdHex(bech32) ?: return null
    profileDisplayName(id)?.let { return it }
    val displayName = readDisplayName(id)?.let { ProfileSanitizer.displayName(it) }
    if (displayName == null) requestProfile(id)
    return displayName
}

internal fun notificationSenderNameOverride(
    contactNickname: String?,
    localProfileName: String?,
): String? =
    ProfileSanitizer.displayName(contactNickname)
        ?: ProfileSanitizer.displayName(localProfileName)

internal fun notificationDisplayNameHint(raw: String?): String? {
    val displayName = ProfileSanitizer.displayName(raw)
    return displayName?.takeUnless(IdentityFormatter::isNostrIdentityFallback)
}

internal fun resolvedProfileDisplayName(
    profileDisplayName: String?,
    notificationDisplayNameHint: String?,
): String? =
    ProfileSanitizer.displayName(profileDisplayName)
        ?: notificationDisplayNameHint(notificationDisplayNameHint)

internal fun profileLookupRelays(
    bootstrapRelays: List<String>,
    activeAccountRelays: List<String>,
): List<String> = (bootstrapRelays + activeAccountRelays).distinct()

internal suspend fun resolveNotificationPreviewText(
    raw: String?,
    parseMarkdown: suspend (String) -> MarkdownDocumentFfi,
    mentionDisplayName: suspend (String) -> String?,
): String? {
    val text = raw?.takeIf { it.isNotBlank() } ?: return null
    val document = parseMarkdown(text)
    if (document.blocks.isEmpty()) return null
    val mentionNames = mutableMapOf<String, String?>()
    for (bech32 in markdownDocumentMentionBech32s(document)) {
        mentionNames[bech32] = mentionDisplayName(bech32)
    }
    return markdownDocumentToPreviewAnnotatedString(
        document = document,
        codeStyle = SpanStyle(),
        mentionDisplayName = mentionNames::get,
    ).text.takeIf { it.isNotBlank() }
}

internal data class SignOutOutcome(
    val nextActiveRef: String?,
    val phase: AppPhase,
)

internal fun accountSummariesWithCreatedIdentity(
    current: List<AccountSummaryFfi>,
    created: AccountSummaryFfi,
): List<AccountSummaryFfi> {
    val existingIndex =
        current.indexOfFirst {
            it.label == created.label || it.accountIdHex.equals(created.accountIdHex, ignoreCase = true)
        }
    if (existingIndex < 0) return current + created
    return current.toMutableList().also { it[existingIndex] = created }
}

internal data class ProfileGroupInviteOutcome(
    val attempted: Int,
    val failures: Int,
    val firstFailure: AppText? = null,
) {
    val delivered: Int = attempted - failures
    val completedSuccessfully: Boolean = attempted > 0 && failures == 0
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
        preferences.edit().putBoolean(KEY_ALLOW_CHAT_SCREENSHOTS, enabled).apply()
    }
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

internal data class ProfileGroupInviteToast(
    @param:StringRes val messageRes: Int,
    val detail: AppText? = null,
    // Failure outcomes carry a diagnostic detail worth pasting into a bug
    // report; pure-success toasts stay non-copyable (#796).
    val copyable: Boolean = false,
)

internal class StartProfileChatNoActiveAccountException : IllegalStateException("No active account")

internal fun profileGroupInviteToast(outcome: ProfileGroupInviteOutcome): ProfileGroupInviteToast? {
    require(outcome.attempted >= 0) { "attempted must be non-negative" }
    require(outcome.failures in 0..outcome.attempted) { "failures must be between 0 and attempted" }
    if (outcome.attempted == 0) return null
    val failureDetail = outcome.firstFailure ?: AppText.Plain("")
    return when {
        outcome.failures == 0 && outcome.attempted == 1 ->
            ProfileGroupInviteToast(R.string.toast_invite_sent)
        outcome.failures == 0 ->
            ProfileGroupInviteToast(R.string.toast_invites_sent_to_groups)
        outcome.delivered == 0 ->
            ProfileGroupInviteToast(R.string.toast_couldnt_add_members, failureDetail, copyable = true)
        else ->
            ProfileGroupInviteToast(R.string.toast_invites_sent_to_groups_partial, failureDetail, copyable = true)
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

private fun missingKeyPackageFailureDetail(
    account: String,
    displayName: (String) -> String,
): AppText {
    val normalizedAccount = account.trim()
    return if (normalizedAccount.isEmpty()) {
        AppText.Resource(R.string.error_missing_key_package)
    } else {
        AppText.Resource(R.string.error_missing_key_package_for, listOf(displayName(normalizedAccount)))
    }
}

internal fun groupCreateFailureDetail(
    throwable: Throwable,
    displayName: (String) -> String,
): AppText =
    when (throwable) {
        is StartProfileChatNoActiveAccountException -> AppText.Resource(R.string.toast_no_active_account)
        is MarmotKitException.MissingKeyPackage -> missingKeyPackageFailureDetail(throwable.account, displayName)
        is MarmotKitException.InvalidKeyPackageEvent -> AppText.Resource(R.string.error_missing_key_package)
        is MarmotKitException.InvalidIdentity -> AppText.Resource(R.string.error_invalid_identity_reference)
        is MarmotKitException.Publish -> AppText.Resource(R.string.error_group_create_failed_retry)
        is MarmotKitException.GroupHydrationPending -> AppText.Resource(R.string.toast_chat_still_loading)
        is MarmotKitException -> AppText.Resource(R.string.error_group_create_failed_retry)
        else -> AppText.Resource(R.string.error_group_create_failed_retry)
    }

/**
 * Missing or unusable KeyPackages mean the recipient is not ready for secure
 * chat. Malformed recipient references remain `InvalidIdentity`, even after a
 * direct-chat lookup, so this classification never relies on call-site guesses
 * or error-detail strings.
 */
internal fun startProfileChatFailureIsMissingSetup(throwable: Throwable): Boolean =
    throwable is MarmotKitException.MissingKeyPackage || throwable is MarmotKitException.InvalidKeyPackageEvent

internal fun startProfileChatInviteDetail(recipientName: String?): AppText =
    recipientName?.trim()?.takeIf { it.isNotEmpty() }?.let {
        AppText.Resource(R.string.invite_to_white_noise_description, listOf(it))
    } ?: AppText.Resource(R.string.unknown_invite_to_white_noise_description)

internal fun startProfileChatFailureDetail(
    throwable: Throwable,
    displayName: (String) -> String,
): AppText = groupCreateFailureDetail(throwable, displayName)

internal fun groupCreateFailureCopyable(throwable: Throwable): Boolean =
    when (throwable) {
        is StartProfileChatNoActiveAccountException -> false
        is MarmotKitException.MissingKeyPackage -> false
        is MarmotKitException.InvalidKeyPackageEvent -> false
        is MarmotKitException.InvalidIdentity -> false
        is MarmotKitException.Publish -> true
        is MarmotKitException -> false
        else -> true
    }

internal fun startProfileChatFailureCopyable(throwable: Throwable): Boolean = groupCreateFailureCopyable(throwable)

private data class NotificationSystemText(
    val title: String?,
    val body: String,
)

internal fun shouldAcceptMediaUploadForAccount(
    conversationAccountRef: String?,
    capturedMediaUploadSessionEpoch: Int,
    activeAccountRef: String?,
    currentMediaUploadSessionEpoch: Int,
): Boolean =
    conversationAccountRef != null &&
        conversationAccountRef == activeAccountRef &&
        capturedMediaUploadSessionEpoch == currentMediaUploadSessionEpoch

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

/** Whether connectivity restoration should restart the UI-independent notification runtime. */
internal fun shouldReconnectNotificationsOnNetworkRestore(
    wasOnline: Boolean,
    isOnline: Boolean,
): Boolean = !wasOnline && isOnline

/**
 * Offline -> online notification recovery without a push-wake marker.
 * [subscribeNotifications] is a passive broadcast with no replay, so account
 * catch-up must run only after a receiver is listening.
 */
internal suspend fun runNotificationReconnectOnNetworkRestore(
    ensureNotificationReceiverActive: suspend () -> Boolean,
    catchUpAccounts: suspend () -> Unit,
) {
    if (ensureNotificationReceiverActive()) {
        catchUpAccounts()
    }
}

/**
 * Prefer the battery-efficient native-push wake path when this build/device
 * supports it. If enabling push fails, retain the persistent relay connection
 * so first-run setup never silently leaves the account without background
 * delivery. Any partial native-push enablement is rolled back before the
 * persistent fallback is restored, keeping the two delivery modes exclusive.
 */
internal suspend fun configureDefaultNotificationDelivery(
    nativePushAvailable: Boolean,
    enableNativePush: suspend () -> Boolean,
    disableNativePush: suspend () -> Boolean,
    setBackgroundConnectionEnabled: suspend (Boolean) -> Boolean,
): Boolean {
    val configured =
        if (!nativePushAvailable) {
            setBackgroundConnectionEnabled(true)
        } else {
            val nativePushReady = enableNativePush()
            if (nativePushReady && setBackgroundConnectionEnabled(false)) {
                true
            } else {
                val nativePushDisabled = disableNativePush()
                val backgroundConnectionEnabled = setBackgroundConnectionEnabled(true)
                nativePushDisabled && backgroundConnectionEnabled
            }
        }
    return configured
}

internal fun nativePushEnablementConfirmed(
    allAccountsReady: Boolean,
    activeAccountRegistered: Boolean,
): Boolean = allAccountsReady && activeAccountRegistered

internal enum class PushRegistrationSharingState {
    Complete,
    PendingDurableRetry,
}

internal fun pushRegistrationSharingState(outcome: PushRegistrationShareOutcomeFfi): PushRegistrationSharingState =
    when (outcome.status) {
        PushRegistrationShareStatusFfi.COMPLETE -> PushRegistrationSharingState.Complete
        PushRegistrationShareStatusFfi.PENDING -> PushRegistrationSharingState.PendingDurableRetry
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

internal class InFlightMediaUploads {
    private val lock = Any()
    private val jobs = mutableMapOf<String, Job>()

    fun track(
        conversationKey: String,
        uploadKey: String,
        job: Job,
    ) {
        val key = registryKey(conversationKey, uploadKey)
        synchronized(lock) {
            jobs[key] = job
        }
        job.invokeOnCompletion {
            synchronized(lock) {
                if (jobs[key] === job) {
                    jobs.remove(key)
                }
            }
        }
    }

    fun untrack(
        conversationKey: String,
        uploadKey: String,
        job: Job,
    ) {
        val key = registryKey(conversationKey, uploadKey)
        synchronized(lock) {
            if (jobs[key] === job) {
                jobs.remove(key)
            }
        }
    }

    fun cancelAll(): Int {
        val active =
            synchronized(lock) {
                jobs
                    .values
                    .toSet()
                    .also { jobs.clear() }
            }
        active.forEach { it.cancel(CancellationException("media upload cancelled by account switch")) }
        return active.size
    }

    private fun registryKey(
        conversationKey: String,
        uploadKey: String,
    ): String = "$conversationKey\u0000$uploadKey"
}

/**
 * The active-account ref and app phase after signing [activeRef] out. Sign-out
 * is a non-destructive session switch, so if another account remains we switch
 * to it and stay [AppPhase.Ready]; if the signed-out account was the last
 * active one, drop to [AppPhase.Onboarding] rather than leaving a MainShell
 * rendered with no active account.
 */
internal fun signOutOutcome(
    accountLabels: List<String>,
    activeRef: String?,
): SignOutOutcome {
    val next = accountLabels.firstOrNull { it != activeRef }
    return SignOutOutcome(next, if (next == null) AppPhase.Onboarding else AppPhase.Ready)
}

/**
 * Persisted account entries that can sign through either local key material or
 * an external signer such as Amber. This is identity/signing-method inventory,
 * not a liveness or signer-reachability check: a non-running external signer is
 * still a signed-in signing account for account switchers, background sweeps,
 * and notification/account-count projections.
 */
internal fun AccountSummaryFfi.isSignedInSigningAccount(): Boolean =
    !signedOut &&
        label.isNotBlank() &&
        (localSigning || externalSigning)

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
 * Whether the main shell should pop its in-shell navigation (Settings, an open
 * conversation, a Settings detail like Identity & Keys) back to the chat-list
 * root because the active account changed underneath it.
 *
 * The shell stays mounted whenever [AppPhase.Ready] is preserved across an
 * account change — e.g. Sign Out & Wipe of the active account while another
 * remains (issue #547), or the manual account switcher (#316). In those cases
 * the previously-rendered screen references an account that is no longer
 * active (or no longer exists), so it must be popped.
 *
 * Returns true only on a transition between two distinct non-null accounts.
 * The initial composition (and the recomposition after process death, where
 * the shell is being rebuilt from saved nav state) reports a null [previous],
 * so this returns false and the saved screen/conversation is preserved
 * (issue #386). A transition to null is the no-accounts case, which the
 * top-level phase router (AppPhase.Onboarding) already handles by tearing the
 * shell down, so it needs no in-shell reset here.
 */
internal fun shouldResetNavOnAccountChange(
    previous: String?,
    current: String?,
): Boolean = previous != null && current != null && previous != current

/**
 * The account ref the main shell should remember as "previous" after observing
 * [current], for the next [shouldResetNavOnAccountChange] comparison.
 *
 * Destructive Sign Out & Wipe drains the wiped account's live streams first,
 * which transiently sets activeAccountRef to null *before* it lands on the next
 * account (issue #610). If the shell adopted that intermediate null as its
 * previous ref, the eventual switch to the next account would look like a
 * null -> account transition — treated as a fresh composition — and the now-
 * deleted account's Identity & Keys screen would never be popped (regression of
 * #547). Keep the last real (non-null) account across the transient null so the
 * settle onto the next account is still seen as a distinct-account change. A
 * settle onto null is the no-accounts case, which AppPhase.Onboarding tears the
 * shell down for anyway, so retaining the old ref is harmless.
 */
internal fun nextNavAccountRef(
    previous: String?,
    current: String?,
): String? = current ?: previous

/**
 * Next exponential-backoff delay: double [current], clamped to [maxMillis].
 * Guards the multiply so a near-`Long.MAX_VALUE` input can't overflow to a
 * negative value below the clamp (returns [maxMillis] once at/over the cap).
 */
internal fun nextRetryBackoffMillis(
    current: Long,
    maxMillis: Long,
): Long {
    val positiveCurrent = current.coerceAtLeast(1L)
    return if (positiveCurrent >= maxMillis) {
        maxMillis
    } else if (positiveCurrent > Long.MAX_VALUE / 2) {
        maxMillis
    } else {
        (positiveCurrent * 2).coerceAtMost(maxMillis)
    }
}

data class ToastMessage(
    val title: AppText,
    val detail: AppText? = null,
    // Explicit copy-affordance gate (#796): only error/diagnostic toasts
    // should offer the snackbar Copy icon. Success confirmations and
    // transient state changes leave this false (the default) so the emit
    // site — not a message-body heuristic — decides.
    val copyable: Boolean = false,
    val tier: NoticeTier = NoticeTier.ActionableError,
    val diagnosticReport: String? = null,
)

data class TransientNotice(
    val id: Long,
    val title: AppText,
    val detail: AppText? = null,
    val conversation: ConversationNoticeDestination? = null,
)

data class ConversationNoticeDestination(
    val accountRef: String,
    val groupIdHex: String,
)

internal fun TransientNotice.isForConversation(
    accountRef: String,
    groupIdHex: String,
): Boolean =
    conversation?.let { destination ->
        destination.accountRef == accountRef &&
            destination.groupIdHex.equals(groupIdHex, ignoreCase = true)
    } == true

private data class ProfilePresentation(
    val displayName: String?,
    val avatarUrl: String?,
) {
    companion object {
        val Empty = ProfilePresentation(displayName = null, avatarUrl = null)
    }
}

private data class InviteNotificationIdentityRefreshResult(
    val posted: Boolean,
    val displayedName: String?,
    val contentRedacted: Boolean,
)

internal data class PostedGroupInviteIdentity(
    val update: NotificationUpdateFfi,
    val displayedName: String?,
)

internal fun postedGroupInviteIdentity(
    update: NotificationUpdateFfi,
    posted: Boolean,
    redactContent: Boolean,
    displayedName: String?,
): PostedGroupInviteIdentity? =
    if (posted && update.trigger == NotificationTriggerFfi.GROUP_INVITE) {
        PostedGroupInviteIdentity(
            update = update,
            displayedName = displayedName.takeUnless { redactContent },
        )
    } else {
        null
    }

internal data class NotificationAvatarPreWarmTarget(
    val senderAccountIdHex: String?,
    val senderAvatarUrl: String?,
    val resolveGroupAvatar: Boolean,
    val preWarmRemoteImages: Boolean,
)

internal fun shouldPreWarmNotificationAvatars(
    update: NotificationUpdateFfi,
    shouldPost: Boolean,
    canPost: Boolean,
): Boolean =
    shouldPost &&
        canPost &&
        !update.isFromSelf &&
        update.trigger == NotificationTriggerFfi.NEW_MESSAGE &&
        !LocalNotificationFormatter.isReaction(update)

internal fun notificationAvatarPreWarmTarget(
    update: NotificationUpdateFfi,
    appLockScreenVisible: Boolean,
): NotificationAvatarPreWarmTarget =
    NotificationAvatarPreWarmTarget(
        senderAccountIdHex =
            update.sender.accountIdHex
                .trim()
                .takeIf { it.isNotEmpty() },
        senderAvatarUrl = ProfileSanitizer.protocolImageUrl(update.sender.pictureUrl),
        resolveGroupAvatar = !update.isDm,
        preWarmRemoteImages = !appLockScreenVisible,
    )

private data class PreWarmedNotificationAvatars(
    val senderAvatarUrl: String?,
    val groupAvatarUrl: String?,
)

/** Keeps cold-wake avatar work ahead of the first notification post. */
internal suspend fun <T> postAfterNotificationAvatarPreWarm(
    preWarm: suspend () -> T,
    post: suspend (T) -> Unit,
) {
    post(preWarm())
}

enum class RelayListKind {
    Nip65,
    Inbox,
}

internal enum class RelayUrlValidationResult {
    Acceptable,
    UnsupportedHost,
    Invalid,
}

internal data class RelayListEditPlan(
    val relays: List<String>,
)

private enum class RelayPublishValidationError {
    Invalid,
    Blocked,
    Unavailable,
}

internal fun normalizeRelayUrls(
    relays: Iterable<String>,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
): List<String> =
    relays
        .mapNotNull { canonicalRelayUrl(it, allowExternalRelayHosts) }
        .distinct()

internal fun telemetryServiceVersion(
    versionName: String,
    versionCode: Int,
): String = "${versionName.trim()}+$versionCode"

internal fun telemetryDeploymentEnvironment(value: String): String =
    when (val normalized = value.trim().lowercase(Locale.ROOT)) {
        "production", "staging", "development", "test" -> normalized
        "android-release" -> "production"
        else -> "production"
    }

internal fun telemetryDeviceModelIdentifier(model: String): String? = model.trim().takeIf { it.isNotEmpty() }

internal fun isAcceptableRelayUrl(
    url: String,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
): Boolean = relayUrlValidationResult(url, allowExternalRelayHosts) == RelayUrlValidationResult.Acceptable

internal fun relayUrlValidationResult(
    url: String,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
): RelayUrlValidationResult {
    val canonical =
        canonicalRelayUrl(url, allowExternalRelayHosts = true)
            ?: return RelayUrlValidationResult.Invalid
    return if (allowExternalRelayHosts || canonicalRelayUrl(canonical, allowExternalRelayHosts = false) != null) {
        RelayUrlValidationResult.Acceptable
    } else {
        RelayUrlValidationResult.UnsupportedHost
    }
}

internal fun relayListAfterAddition(
    currentRelays: List<String>,
    relayToAdd: String,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
): RelayListEditPlan? {
    if (relayUrlValidationResult(relayToAdd, allowExternalRelayHosts) != RelayUrlValidationResult.Acceptable) {
        return null
    }
    return RelayListEditPlan(
        relays = normalizeRelayUrls(currentRelays + relayToAdd, allowExternalRelayHosts),
    )
}

internal fun relayListAfterRemoval(
    currentRelays: List<String>,
    relayToRemove: String,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
    fallbackRelays: List<String> = MarmotClient.bootstrapRelays,
): RelayListEditPlan {
    val target = relayToRemove.trim()
    val remaining =
        currentRelays
            .map(String::trim)
            .filter { it.isNotEmpty() && it != target }
            .distinct()
    val supported = normalizeRelayUrls(remaining, allowExternalRelayHosts)
    return RelayListEditPlan(
        relays = supported.ifEmpty { normalizeRelayUrls(fallbackRelays, allowExternalRelayHosts) },
    )
}

internal fun canRemoveRelay(
    currentRelays: List<String>,
    relay: String,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
): Boolean {
    if (relayUrlValidationResult(relay, allowExternalRelayHosts) != RelayUrlValidationResult.Acceptable) {
        return true
    }
    return normalizeRelayUrls(
        currentRelays.filterNot { it.trim() == relay.trim() },
        allowExternalRelayHosts,
    ).isNotEmpty()
}

internal enum class RelayResolveTimeCheckResult {
    Passed,
    Blocked,
    Unavailable,
}

/** Injectable DNS resolver for relay resolve-time SSRF checks (unit tests). */
internal typealias RelayHostResolver = (String) -> Array<InetAddress>?

/**
 * Resolve-time SSRF guard for relay URLs about to be dialed. Call from the IO
 * dispatcher (see [relayUrlsResolveTimeCheckResult]) after cheap
 * [canonicalRelayUrl] / [normalizeRelayUrls] canonicalization.
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

internal suspend fun relayUrlsResolveTimeCheckResult(
    canonicalUrls: List<String>,
    resolve: RelayHostResolver = ::resolveRelayHost,
): RelayResolveTimeCheckResult =
    withContext(Dispatchers.IO) {
        var unavailable = false
        for (canonicalUrl in canonicalUrls) {
            when (relayUrlResolveTimeCheckResult(canonicalUrl, resolve)) {
                RelayResolveTimeCheckResult.Passed -> Unit
                RelayResolveTimeCheckResult.Blocked -> return@withContext RelayResolveTimeCheckResult.Blocked
                RelayResolveTimeCheckResult.Unavailable -> unavailable = true
            }
        }
        if (unavailable) RelayResolveTimeCheckResult.Unavailable else RelayResolveTimeCheckResult.Passed
    }

internal suspend fun relayUrlsPassResolveTimeChecks(
    canonicalUrls: List<String>,
    resolve: RelayHostResolver = ::resolveRelayHost,
): Boolean = relayUrlsResolveTimeCheckResult(canonicalUrls, resolve) == RelayResolveTimeCheckResult.Passed

private const val RELAY_HOSTS_UNAVAILABLE_MESSAGE =
    "Couldn't verify relay hosts. Check your connection and try again."

private val releaseRelayHosts: Set<String> by lazy {
    MarmotClient.bootstrapRelays
        .mapNotNull { runCatching { URI(it).host?.lowercase(Locale.ROOT) }.getOrNull() }
        .toSet()
}

private fun relayHostPassesReleasePolicy(canonicalHost: String): Boolean = canonicalHost in releaseRelayHosts

private fun resolveRelayHost(host: String): Array<InetAddress>? = runCatching { InetAddress.getAllByName(host) }.getOrNull()

private fun canonicalRelayUrl(
    url: String,
    allowExternalRelayHosts: Boolean = BuildConfig.DEBUG,
): String? {
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
        // Release builds cannot pin the native Marmot/nostr-sdk WebSocket dial to
        // this app-side DNS answer, so only app-owned relay hosts are allowed to
        // cross the UniFFI boundary. Debug builds keep external relays available
        // for local/self-hosted testing.
        if (!allowExternalRelayHosts && !relayHostPassesReleasePolicy(canonicalHost)) {
            return@runCatching null
        }
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
 */
internal class ConversationStateRetention(
    private val maxEntries: Int,
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

    private fun promoteProtected(protectedKey: String?) {
        if (protectedKey != null && recentKeys.containsKey(protectedKey)) {
            recentKeys[protectedKey] = Unit
        }
    }

    private fun evictOverflow(protectedKey: String?): List<String> {
        val evicted = mutableListOf<String>()
        while (recentKeys.size > maxEntries) {
            val staleKey = recentKeys.keys.firstOrNull { it != protectedKey } ?: break
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
private const val FORWARD_BACKGROUND_RETRY_ATTEMPTS = 3
private const val FORWARD_BACKGROUND_RETRY_DELAY_MS = 1_000L
private const val FORWARD_TERMINAL_STATUS_DURATION_MS = 2_000L

internal fun appStateScopeExceptionHandler(
    report: (Throwable) -> Unit = { throwable ->
        Log.w(APP_STATE_SCOPE_LOG_TAG, "unhandled AppState scope failure", throwable)
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

/**
 * One-shot handoff of MDK's authoritative local projection across the
 * active-account composition boundary. This is deliberately not a retained
 * Android cache: [WhiteNoiseAppState] owns at most one pending value and the
 * target [ChatsController] consumes it during construction.
 */
internal data class AccountSwitchLocalSnapshot(
    val accountRef: String,
    val activeAccountIdHex: String?,
    val rows: List<ChatListRowFfi>,
    val groups: List<AppGroupRecordFfi>,
    val memberIds: List<AppGroupMemberIdsFfi>,
    internal val profiles: List<AccountSwitchProfileSeed>,
)

internal data class AccountSwitchProfileSeed(
    val accountIdHex: String,
    val profile: UserProfileMetadataFfi?,
    val displayName: String?,
    val avatarUrl: String?,
)

/** Main-confined latest-wins owner for the one-shot account-switch handoff. */
internal class AccountSwitchLocalSnapshotHandoff {
    private var generation = 0L
    private var pending: AccountSwitchLocalSnapshot? = null

    fun beginRequest(): Long {
        generation += 1L
        pending = null
        return generation
    }

    fun isCurrent(requestGeneration: Long): Boolean = generation == requestGeneration

    fun publish(
        requestGeneration: Long,
        snapshot: AccountSwitchLocalSnapshot?,
    ): Boolean {
        if (!isCurrent(requestGeneration)) return false
        pending = snapshot
        return true
    }

    fun consume(accountRef: String?): AccountSwitchLocalSnapshot? {
        val snapshot = pending
        pending = null
        return snapshot?.takeIf { accountRef != null && it.accountRef == accountRef }
    }
}

private class AccountSwitchSnapshotSuperseded : RuntimeException()

private data class StartupUnreadRefresh(
    val accounts: List<AccountSummaryFfi>,
    val accountListRevision: Long,
)

private data class AccountUnreadFoldResult(
    val accountRef: String,
    val unreadCount: ULong,
    val hasManualUnread: Boolean?,
)

class WhiteNoiseAppState private constructor(
    context: Context,
    val draftStore: DraftStore,
    startPlatformServices: Boolean,
    private val accountIdHexResolver: (suspend (String) -> String?)?,
    private val profileReader: (suspend (String) -> UserProfileMetadataFfi?)?,
    private val profileDisplayNameReader: (suspend (String) -> String?)?,
    private val profileRefreshRequest: (suspend (String) -> Unit)?,
    private val identityLoginCalls: IdentityLoginCalls?,
    private val marmotAccessObserver: (() -> Unit)?,
    private val marmotRuntimeFactory: (Context) -> AppMarmotRuntime,
    private val notificationSubscriber: suspend (MarmotInterface) -> AppNotificationSubscription,
    private val notificationDispatcher: CoroutineDispatcher,
    private val notificationReceiverTimeoutMillis: () -> Long,
    private val inboundShareTextStager: ((String, String, String) -> Unit)?,
    preferencesOverride: SharedPreferences?,
    initialAccounts: List<AccountSummaryFfi>,
    initialActiveAccountRef: String?,
) {
    constructor(context: Context) :
        this(
            context = context,
            draftStore = DraftStore.forContext(context.applicationContext),
            startPlatformServices = true,
            accountIdHexResolver = null,
            profileReader = null,
            profileDisplayNameReader = null,
            profileRefreshRequest = null,
            identityLoginCalls = null,
            marmotAccessObserver = null,
            marmotRuntimeFactory = ::openMarmotRuntime,
            notificationSubscriber = ::subscribeToNotifications,
            notificationDispatcher = Dispatchers.IO,
            notificationReceiverTimeoutMillis = { NOTIFICATION_STARTUP_RECEIVER_TIMEOUT_MILLIS },
            inboundShareTextStager = null,
            preferencesOverride = null,
            initialAccounts = emptyList(),
            initialActiveAccountRef = null,
        )

    /** JVM Compose tests inject the profile resolver/state and do not start Android platform services. */
    internal constructor(
        context: Context,
        draftStore: DraftStore,
        accountIdHexResolver: suspend (String) -> String?,
        accounts: List<AccountSummaryFfi>,
        activeAccountRef: String,
        profileReader: (suspend (String) -> UserProfileMetadataFfi?)? = null,
        profileDisplayNameReader: (suspend (String) -> String?)? = null,
        profileRefreshRequest: (suspend (String) -> Unit)? = null,
        identityLoginCalls: IdentityLoginCalls? = null,
        marmotAccessObserver: (() -> Unit)? = null,
        marmotRuntimeFactory: (Context) -> AppMarmotRuntime = ::openMarmotRuntime,
        notificationSubscriber: suspend (MarmotInterface) -> AppNotificationSubscription = ::subscribeToNotifications,
        notificationDispatcher: CoroutineDispatcher = Dispatchers.IO,
        notificationReceiverTimeoutMillis: () -> Long = { NOTIFICATION_STARTUP_RECEIVER_TIMEOUT_MILLIS },
        inboundShareTextStager: ((String, String, String) -> Unit)? = null,
        preferences: SharedPreferences? = null,
    ) : this(
        context = context,
        draftStore = draftStore,
        startPlatformServices = false,
        accountIdHexResolver = accountIdHexResolver,
        profileReader = profileReader,
        profileDisplayNameReader = profileDisplayNameReader,
        profileRefreshRequest = profileRefreshRequest,
        identityLoginCalls = identityLoginCalls,
        marmotAccessObserver = marmotAccessObserver,
        marmotRuntimeFactory = marmotRuntimeFactory,
        notificationSubscriber = notificationSubscriber,
        notificationDispatcher = notificationDispatcher,
        notificationReceiverTimeoutMillis = notificationReceiverTimeoutMillis,
        inboundShareTextStager = inboundShareTextStager,
        preferencesOverride = preferences,
        initialAccounts = accounts,
        initialActiveAccountRef = activeAccountRef,
    )

    private val appContext = context.applicationContext
    private val preferences = preferencesOverride ?: appContext.getSharedPreferences("whitenoise", Context.MODE_PRIVATE)
    private val legacyDraftMigrationSource by lazy { LegacyDraftMigrationSource(appContext) }
    internal val editorSourceStore: EditorSourceStore = EditorSourceStore.create(appContext)
    internal val editorSessionStore: EditorSessionStore = EditorSessionStore.create(appContext)
    internal val messageDraftRepository: MessageDraftRepository =
        MessageDraftRepository(
            gateway = MarmotMessageDraftGateway(::marmot),
            editorSessions = editorSessionStore,
        )
    private val chatMuteRepository = ChatMuteRepository(MarmotChatMuteGateway(::marmot))

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
     */
    private val mediaCacheRevisionState = MutableStateFlow(0L)
    internal val mediaCacheRevision: StateFlow<Long> = mediaCacheRevisionState.asStateFlow()

    private fun bumpMediaCacheRevision() {
        mediaCacheRevisionState.update { it + 1L }
    }

    private val mediaPlaintextCache =
        dev.ipf.whitenoise.android.media.ByteSizeLruCache<String, ByteArray>(
            maxBytes = MEDIA_PLAINTEXT_CACHE_MAX_BYTES,
            maxEntryBytes = MEDIA_PLAINTEXT_CACHE_MAX_BYTES,
            sizeOf = { it.size },
        )

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
        dev.ipf.whitenoise.android.media.ByteSizeLruCache<String, android.graphics.Bitmap>(
            maxBytes = MEDIA_THUMBNAIL_CACHE_MAX_BYTES,
            maxEntryBytes = MEDIA_THUMBNAIL_CACHE_MAX_BYTES,
            sizeOf = { it.allocationByteCount },
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

    internal fun cachedMediaThumbnail(cacheKey: String): android.graphics.Bitmap? {
        assertMainThread { "cachedMediaThumbnail" }
        return mediaThumbnailCache.get(cacheKey)
    }

    internal fun cacheMediaThumbnail(
        cacheKey: String,
        thumbnail: android.graphics.Bitmap,
    ) {
        assertMainThread { "cacheMediaThumbnail" }
        mediaThumbnailCache.put(cacheKey, thumbnail)
    }

    internal fun removeMediaMemoryCacheEntry(cacheKey: String) {
        assertMainThread { "removeMediaMemoryCacheEntry" }
        mediaPlaintextCache.remove(cacheKey)
        mediaThumbnailCache.remove(cacheKey)
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
            keyProvider = AndroidKeystoreDiskByteCacheKeyProvider(),
            onMutation = ::bumpMediaCacheRevision,
        )

    @Volatile
    private var marmotRuntime: AppMarmotRuntime? = null

    private val bootstrapAttempts = BootstrapAttemptCoordinator()
    private val bootstrapRuntime = BootstrapRuntimeCoordinator<AppMarmotRuntime>()
    private val startupTraceStartedAtMs = SystemClock.elapsedRealtime()
    private var startupSystemSplashHandoffRecorded = false
    private var startupLocalRowsRecorded = false
    private var startupMemberDerivedLocalRecorded = false
    private var startupFirstLocalFrameRecorded = false
    private var startupRelayCatchUpRecorded = false
    private var accountListRevision = 0L
    private var pendingStartupUnreadRefresh: StartupUnreadRefresh? = null

    @Volatile
    private var bootstrapCompleted = false
    private val nativePushSyncMutex = Mutex()
    private val ttsRefreshMutex = Mutex()
    private val auditLogSettingsMutex = Mutex()
    private val conversationVibrationChannelMutex = Mutex()

    // Treat preference I/O plus observable-state publication as one transaction;
    // otherwise an older successful hide can publish after a newer hide or wipe.
    private val hiddenMessageMutationMutex = Mutex()
    internal val conversationVibrationPreferences = ConversationVibrationPreferences(appContext)
    internal val conversationNotificationRouting by lazy { ConversationNotificationRouting(appContext) }
    private val localNotificationPresenter = LocalNotificationPresenter(appContext)
    private val inviteNotificationIdentityRefreshStore = GroupInviteNotificationIdentityRefreshStore()
    private val appUpdateRepository = AppUpdateRepository(appContext)
    private val appUpdateNotifier = AppUpdateNotifier(appContext)
    private val appSelfUpdateFlow = AppSelfUpdateFlows.create(appContext)
    internal val chatMutePreferences = ChatMutePreferences(appContext)
    private val authoritativeMuteOverrides = mutableStateMapOf<String, dev.ipf.marmotkit.ChatNotificationSettingsFfi>()
    private val pendingMuteCommands = mutableStateMapOf<String, Int>()
    internal val chatFolderPreferences = ChatFolderPreferences(appContext)
    internal val ttsWarningPreferences = TtsWarningPreferences(appContext)
    internal val ttsEnginePreferences = TtsEnginePreferences(appContext)
    internal val ttsEngineResolver = TtsEngineResolver(appContext)
    internal val ttsRatePreferences = TtsRatePreferences(appContext)

    // Process-wide read-aloud playback: survives navigation between chats and
    // back to the chat list, matching VoicePlaybackController's lifetime.
    val ttsController: TtsController =
        TtsController(
            audioFocus = TtsAudioFocusOwner(appContext),
            speechRate = { ttsRatePreferences.resolvedRate() },
        )
    var ttsResolution by mutableStateOf<TtsResolutionResult?>(null)
        private set

    // The (account, conversation) pair that owns the current auto-read
    // session, or null when speech is manual or idle. Live continuation
    // appends only for the owner: a manual Speak aloud replaces the queue and
    // ends the session; another chat's — or another ACCOUNT'S view of the
    // same group — must never extend it.
    private var ttsAutoReadSessionKey by mutableStateOf<String?>(null)

    // Manual speech deliberately has no auto-read session key, but it still
    // owns decrypted text that must stop when its account is removed.
    private var ttsSpeechAccountRef: String? = null

    fun ownsTtsAutoReadSession(groupIdHex: String): Boolean {
        val key = ttsAutoReadSessionKey ?: return false
        return key == ttsAutoReadSessionKeyFor(activeAccountRef, groupIdHex)
    }

    private fun ttsAutoReadSessionKeyFor(
        accountRef: String?,
        groupIdHex: String,
    ): String? = accountRef?.let { "$it|${groupIdHex.lowercase()}" }

    /** Starts read-aloud for one or more speakable messages. */
    fun speakAloud(
        entries: List<TtsSpeakableEntry>,
        locale: java.util.Locale,
        startSentenceIndex: Int = 0,
    ): Boolean {
        val started = ttsController.speak(entries, locale, startSentenceIndex)
        if (started) {
            // Only a speak that actually replaced the queue may end the
            // previous auto-read session: a failed start (blank text, no
            // engine) leaves the old queue playing and still owned.
            ttsSpeechAccountRef = activeAccountRef
            ttsAutoReadSessionKey = null
            ttsHistorySession.onSessionCleared()
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
        val owner = ttsAutoReadSessionKeyFor(activeAccountRef, groupIdHex) ?: return false
        val started = speakAloud(entries, locale, startSentenceIndex)
        if (started) {
            ttsAutoReadSessionKey = owner
            ttsHistorySession.onConversationSessionStarted(activeAccountRef, groupIdHex)
        }
        return started
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
                    parseMarkdown = { parseMarkdownOrEmpty(it) },
                    mentionDisplayName = ::mentionDisplayName,
                    isGroupMember =
                        if (controller.membersLoaded) {
                            { bech32 -> isRosterMember(bech32, controller.members) }
                        } else {
                            null
                        },
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

    internal fun stopOwnedTtsAutoReadSession() {
        if (ttsAutoReadSessionKey == null) return
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
    }

    /** Account removal ends any owned speech so its decrypted text stops being spoken. */
    internal fun stopTtsForRemovedAccount(accountRef: String) {
        val speechOwner = ttsSpeechAccountRef
        if (speechOwner != null && speechOwner != accountRef) return
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
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

    fun stopSpeaking() {
        ttsController.stop()
        ttsSpeechAccountRef = null
        ttsAutoReadSessionKey = null
        ttsHistorySession.onSessionCleared()
    }

    fun setTtsRateOverride(rate: Float?) {
        ttsRatePreferences.setRateOverride(rate)
        ttsController.onSpeechRateChanged()
    }

    private var attachedTtsHandle: TtsEngineHandle? = null

    private fun publishTtsResolution(resolution: TtsResolutionResult?) {
        ttsResolution = resolution
        val handle = resolution?.handle
        // A refresh that kept the same engine handle must not re-attach:
        // attachEngine treats every attach as a replacement and stops any
        // in-flight speech. Only a genuinely new (or dropped) handle swaps.
        if (handle === attachedTtsHandle) return
        attachedTtsHandle = handle
        if (handle != null) {
            ttsController.attachEngine(AndroidTtsSpeechEngine(handle.textToSpeech))
        } else {
            ttsController.detachEngine()
        }
    }

    val ttsDiscoveryComplete: Boolean
        get() = ttsResolution != null
    val ttsHasUsableEngine: Boolean
        get() = ttsResolution?.hasUsableEngine == true
    private val pushTokenStore = PushTokenStore.create(appContext)
    private val amberSigner = AmberSignerController(appContext)

    // Per-account (platform, token, server-pubkey, relay-hint) fingerprint
    // of the most recent successful `upsertPushRegistration`. Skip redundant
    // FFI calls when nothing has changed across foreground/token-rotation/
    // account-bind events. Keyed per account so multi-account devices keep a
    // working registration on every enabled account, not just the active
    // one. An entry is removed when the corresponding account disables
    // native push, signs out, or hits a sync failure that may indicate the
    // registration is stale.
    private val perAccountSyncedFingerprints = mutableMapOf<String, PushFingerprint>()

    /** Structural cache key for the push-registration dedupe map. */
    private data class PushFingerprint(
        val platform: PushPlatformFfi,
        val token: String,
        val serverPubkeyHex: String,
        val relayHint: String?,
    )

    var phase by mutableStateOf<AppPhase>(AppPhase.Bootstrapping)
        private set

    var accounts by mutableStateOf(initialAccounts)
        private set

    var accountUnreadCounts by mutableStateOf<Map<String, ULong>>(emptyMap())
        private set

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

    val notificationActionsAllowed: Boolean
        get() = notificationActionsAllowed(appLockScreenVisible)

    var appUnlockError by mutableStateOf<AppText?>(null)
        private set

    var appUnlockPromptRequestId by mutableStateOf(0)
        private set

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

    var languageTag by mutableStateOf(preferences.getString(LANGUAGE_TAG_KEY, null).orEmpty())
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    var transientNotice by mutableStateOf<TransientNotice?>(null)
        private set
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

    var relationshipRevision by mutableStateOf(0L)
        private set

    var localNotificationSettings by mutableStateOf<NotificationSettingsFfi?>(null)
        private set

    var relayTelemetrySettings by mutableStateOf<RelayTelemetrySettingsFfi?>(null)
        private set

    var auditLogSettings by mutableStateOf<AuditLogSettingsFfi?>(null)
        private set

    val redactSensitiveAuditData: Boolean
        get() = auditLogSettings?.let(AuditLogSettingsPolicy::redactsSensitiveData) ?: true

    var runtimeGeneration by mutableStateOf(0)
        private set

    var localNotificationPermissionGranted by mutableStateOf(localNotificationPresenter.canPostNotifications())
        private set

    var appUpdateInfo by mutableStateOf(appUpdateRepository.loadInfo())
        private set

    var appSelfUpdateState by mutableStateOf<AppSelfUpdateState>(AppSelfUpdateState.Idle)
        private set

    var backgroundConnectionEnabled by mutableStateOf(BackgroundConnectionPreferences.isEnabled(appContext))
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
    private var profileRevision by mutableStateOf(0)
    private var contactNicknameRevision by mutableStateOf(0)
    private var profileAccountRevisionEpoch by mutableStateOf(0)
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

    // Ids with an in-flight local materialization, so a cache miss launches at
    // most one local read per id. Distinct from the relay-refresh cooldown gate.
    private val materializingProfiles =
        ScopedSet<String>(
            registry = accountScopedCaches,
            name = "materializing-profiles",
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
    private val projectedMessageIdsByConversation = mutableMapOf<String, MutableSet<String>>()
    private val timelineOrderOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()
    private val timelineTimestampOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()
    private val optimisticSendPositionPreservesByConversation =
        mutableMapOf<String, OptimisticSendPositionPreserves>()
    private val retentionAtSendByConversation = mutableMapOf<String, MutableMap<String, ULong>>()

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
    private val conversationStateRetention = ConversationStateRetention(MAX_RETAINED_CONVERSATION_STATES)

    val shareStaging: ShareStagingStore = ShareStagingStore()
    private var draftHydrationRevision by mutableStateOf(0)

    /** Changes when content is staged so an already-open chat consumes repeat shares. */
    val inboundShareRevision: Int
        get() = shareStaging.revision + draftHydrationRevision
    private val shareInboundStager =
        ShareInboundStager(
            stageText =
                inboundShareTextStager ?: { accountRef, groupIdHex, text ->
                    mutationsScope.launch {
                        val completion = draftWriter.mergeText(accountRef, groupIdHex, text)
                        completion.contentForHydration?.let { content ->
                            draftStore.hydrate(
                                accountRef,
                                groupIdHex,
                                content,
                                completion.draftedAtMs ?: System.currentTimeMillis(),
                                replaceExisting = true,
                            )
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
    private val shareShortcutPublisher = ShareShortcutPublisher(appContext)

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
    private val mutationsScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + scopeExceptionHandler)
    private var forwardTerminalDismissGeneration = 0L
    private val forwardOperationOwner =
        ForwardOperationOwner(
            scope = mutationsScope,
            automaticRetryAttempts = FORWARD_BACKGROUND_RETRY_ATTEMPTS,
            retryDelayMillis = { attempt -> FORWARD_BACKGROUND_RETRY_DELAY_MS shl attempt },
            onTerminal = ::scheduleForwardTerminalDismiss,
        )
    internal val activeForwardOperation: StateFlow<ForwardOperationSnapshot?> = forwardOperationOwner.state
    private val draftWriter =
        CoalescingMessageDraftWriter(
            scope = mutationsScope,
            drafts = messageDraftRepository,
            onResult = { accountRef, groupIdHex, _, result ->
                when (result) {
                    is MessageDraftMutationResult.Success -> {
                        draftStore.applyAuthoritativeTimestamp(accountRef, groupIdHex, result.draft?.createdAtMs)
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
    private val notificationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + scopeExceptionHandler)
    private var accountCatchUpJob: Job? = null
    private var pendingAccountSwitchTrace: PendingAccountSwitchTrace? = null
    private val accountSwitchHandoff = AccountSwitchLocalSnapshotHandoff()

    // Bumped whenever cross-account caches are cleared (switch / sign-out). An
    // in-flight profile refresh captures it at start and discards its result if
    // the epoch moved, so a job that resolves after a switch can't write the
    // old account's data back into the just-cleared caches.
    private val profileCacheEpoch =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val mediaUploadSessionEpoch =
        java.util.concurrent.atomic
            .AtomicInteger(0)
    private val notificationJob = NotificationJobSlot()
    private val notificationReconnectJob = NotificationJobSlot()
    private val pushWakeCatchUpDrainJob = NotificationJobSlot()

    @Volatile
    private var networkNotificationRecoverySuppressed = false

    private val notificationReceiverActive = MutableStateFlow(false)
    private val notificationReceiverRetryWake = MutableStateFlow(0L)

    private val notificationDrainSequence = AtomicLong(0)
    private val notificationRuntimeRecoveryGeneration = AtomicLong(0)
    private val notificationPostEpoch = NotificationPostEpoch()
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

    private fun updateNotificationSuppression(next: NotificationSuppression) {
        if (next != suppression) notificationPostEpoch.advance()
        suppression = next
    }

    private val appInForeground: Boolean
        get() = suppression.inForeground
    private val activeConversationGroupIdHex: String?
        get() = suppression.activeConversationGroupIdHex
    private val activeConversationAccountRef: String?
        get() = suppression.activeConversationAccountRef
    private val profileRefreshGate = ProfileRefreshGate(PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS)
    private var chatsController: ChatsController? = null
    private val conversationControllerLock = Any()
    private val conversationControllers = linkedSetOf<ConversationController>()

    val activeAccount: AccountSummaryFfi?
        get() = activeAccountRef?.let { ref -> accounts.firstOrNull { it.label == ref } }

    /** Convenience: return the active account's draft for [groupIdHex], or null. */
    fun draftFor(groupIdHex: String): String? = draftSnapshotFor(groupIdHex)?.textFieldValue?.text

    /** Convenience: return the active account's restored composer draft for [groupIdHex]. */
    fun draftSnapshotFor(groupIdHex: String): ComposerDraftSnapshot? {
        val account = activeAccountRef ?: return null
        return draftStore.getDraft(account, groupIdHex)
    }

    /** Convenience: write the active account's draft for [groupIdHex]. Empty/blank clears. */
    fun setDraft(
        groupIdHex: String,
        value: TextFieldValue,
    ) {
        val account = activeAccountRef ?: return
        draftStore.set(account, groupIdHex, value)
        draftWriter.submit(account, groupIdHex, value.text)
    }

    /** Hydrates the selected composer from MDK without retaining attachment plaintext in Android state. */
    fun loadDraft(groupIdHex: String) {
        val accountRef = activeAccountRef ?: return
        val generation = draftWriter.generation(accountRef, groupIdHex)
        mutationsScope.launch {
            draftWriter
                .loadIfCurrent(accountRef, groupIdHex, generation)
                ?.onSuccess { draft ->
                    if (activeAccountRef != accountRef) return@onSuccess
                    if (!draftWriter.isCurrent(accountRef, groupIdHex, generation)) return@onSuccess
                    draftStore.replaceFromAuthoritative(
                        accountRef,
                        groupIdHex,
                        draft?.content,
                        draft?.createdAtMs,
                    )
                    draftHydrationRevision += 1
                }?.onFailure { appStateDebug(it) { "draft load failed group=${groupIdHex.take(8)}" } }
        }
    }

    fun clearDraftAfterSuccessfulSend(groupIdHex: String) {
        val accountRef = activeAccountRef ?: return
        val sentGeneration = draftWriter.generation(accountRef, groupIdHex)
        draftStore.set(accountRef, groupIdHex, TextFieldValue(""))
        mutationsScope.launch {
            when (val deletion = draftWriter.deleteIfCurrent(accountRef, groupIdHex, sentGeneration)) {
                is MessageDraftConditionalDeleteResult.Applied -> {
                    when (val result = deletion.result) {
                        is MessageDraftMutationResult.Success -> {
                            if (draftWriter.isCurrent(accountRef, groupIdHex, sentGeneration)) {
                                draftStore.applyAuthoritativeTimestamp(accountRef, groupIdHex, null)
                            }
                        }
                        is MessageDraftMutationResult.Failure ->
                            appStateDebug(result.cause) { "sent draft cleanup failed group=${groupIdHex.take(8)}" }
                        else -> Unit
                    }
                }
                MessageDraftConditionalDeleteResult.Superseded -> Unit
            }
        }
    }

    internal suspend fun deleteDraftBeforeGroupRemoval(
        accountRef: String,
        groupIdHex: String,
    ): MessageDraftMutationResult {
        draftWriter.flush()
        return messageDraftRepository.delete(accountRef, groupIdHex)
    }

    internal fun refreshDraftSummaries(accountRef: String) {
        mutationsScope.launch {
            messageDraftRepository
                .summaries(accountRef)
                .onSuccess { summaries ->
                    if (activeAccountRef != accountRef) return@onSuccess
                    draftStore.replaceSummaries(
                        accountRef,
                        summaries.associate { it.groupIdHex to it.createdAtMs },
                    )
                }.onFailure { appStateDebug(it) { "draft summaries load failed account=${accountRef.take(8)}" } }
        }
    }

    fun marmot(): MarmotInterface {
        marmotAccessObserver?.invoke()
        return requireNotNull(marmotRuntime) { "Marmot is not initialized" }.marmot
    }

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
    private class GroupCommitLockEntry {
        val mutex = Mutex()
        var users = 0
    }

    private val groupCommitLocks = mutableMapOf<String, GroupCommitLockEntry>()
    private val groupCommitLocksLock = Any()

    suspend fun <T> withGroupCommitLock(
        accountRef: String,
        groupIdHex: String,
        block: suspend () -> T,
    ): T {
        val key = "$accountRef|$groupIdHex"
        val entry =
            synchronized(groupCommitLocksLock) {
                groupCommitLocks.getOrPut(key) { GroupCommitLockEntry() }.also { it.users += 1 }
            }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            synchronized(groupCommitLocksLock) {
                entry.users -= 1
                if (entry.users == 0 && !entry.mutex.isLocked && groupCommitLocks[key] === entry) {
                    groupCommitLocks.remove(key)
                }
            }
        }
    }

    private fun pruneIdleGroupCommitLocks() {
        synchronized(groupCommitLocksLock) {
            groupCommitLocks.entries.removeAll { (_, entry) -> entry.users == 0 && !entry.mutex.isLocked }
        }
    }

    internal fun optimisticMessages(
        accountRef: String?,
        groupIdHex: String,
    ): SnapshotStateMap<String, TimelineMessage> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            optimisticMessagesByConversation.getOrPut(key) { mutableStateMapOf() }
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
                )
            }
        }

    internal fun activeUploadKeys(
        accountRef: String?,
        groupIdHex: String,
    ): MutableSet<String> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            activeUploadKeysByConversation.getOrPut(key) { mutableSetOf() }
        }

    internal fun mediaUploadSessionEpoch(): Int = mediaUploadSessionEpoch.get()

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

    internal fun untrackInFlightMediaUpload(
        accountRef: String?,
        groupIdHex: String,
        uploadKey: String,
        job: Job?,
    ) {
        if (job != null) {
            inFlightMediaUploads.untrack(conversationKey(accountRef, groupIdHex), uploadKey, job)
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

    private fun removeConversationState(staleKey: String) {
        optimisticMessagesByConversation.remove(staleKey)
        projectedMessageIdsByConversation.remove(staleKey)
        timelineOrderOverridesByConversation.remove(staleKey)
        timelineTimestampOverridesByConversation.remove(staleKey)
        optimisticSendPositionPreservesByConversation.remove(staleKey)
        retentionAtSendByConversation.remove(staleKey)
        retainedMediaUploadsByConversation.remove(staleKey)
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

    fun attachChatsController(controller: ChatsController?) {
        chatsController = controller
        // Route draft start/clear re-sorts to whichever controller is attached;
        // reads the field at call time so a later re-attach still resolves.
        draftStore.onDraftSortOrderChanged = { chatsController?.onDraftSortOrderChanged() }
    }

    fun attachConversationController(controller: ConversationController) {
        synchronized(conversationControllerLock) { conversationControllers.add(controller) }
    }

    fun detachConversationController(controller: ConversationController) {
        synchronized(conversationControllerLock) { conversationControllers.remove(controller) }
    }

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

    private fun applyDestructiveWipeRuntimeState(state: DestructiveAccountWipeRuntimeState) {
        activeAccountRef = state.activeAccountRef
        updateNotificationSuppression(
            suppression.copy(
                activeConversationAccountRef = state.activeConversationAccountRef,
                activeConversationGroupIdHex = state.activeConversationGroupIdHex,
            ),
        )
        runtimeGeneration = state.runtimeGeneration
    }

    private suspend fun prepareForDestructiveAccountWipe(accountRef: String): Boolean {
        // Fence any foreground-service retry that was captured before this
        // destructive teardown. The wipe/restore path owns listener restart;
        // a delayed service retry must not reinstall work across that boundary.
        notificationRuntimeRecoveryGeneration.incrementAndGet()
        networkNotificationRecoverySuppressed = true
        val restartNotifications =
            backgroundConnectionEnabled ||
                notificationJob.isActive() ||
                notificationReconnectJob.isActive() ||
                pushWakeCatchUpDrainJob.isActive()
        val chatsControllerForTeardown = chatsController
        val conversationControllersForTeardown = conversationControllersForAccountTeardown()
        applyDestructiveWipeRuntimeState(prepareDestructiveAccountWipeRuntimeState(destructiveWipeRuntimeState()))
        reloadMediaAutoDownloadMatrix()
        // Capture controller references before the first suspending teardown.
        // Recomposition is allowed to detach/replace them after activeAccountRef
        // changes, but the destructive wipe still has to drain the old handles.
        chatsControllerForTeardown?.closeLiveSubscriptionsForAccountTeardown(accountRef)
        conversationControllersForTeardown.forEach { it.closeLiveSubscriptionsForAccountTeardown(accountRef) }
        stopNotificationListenerForAccountTeardown()
        return restartNotifications
    }

    private suspend fun restoreAfterFailedDestructiveAccountWipe(
        accountRef: String,
        restartNotifications: Boolean,
    ) {
        applyDestructiveWipeRuntimeState(
            restoreFailedDestructiveAccountWipeRuntimeState(destructiveWipeRuntimeState(), accountRef),
        )
        reloadMediaAutoDownloadMatrix()
        configurePrivacyRuntime()
        refreshLocalNotificationSettings()
        networkNotificationRecoverySuppressed = false
        if (restartNotifications) startNotificationListener()
    }

    private suspend fun stopNotificationListenerForAccountTeardown() {
        notificationReconnectJob.cancelAndJoin()
        pushWakeCatchUpDrainJob.cancelAndJoin()
        notificationJob.cancelAndJoin()
        unreadRefreshScheduler.cancelAndClear()
    }

    // TODO(marmot): remove this UI-controller backchannel once Marmot emits a
    // ProjectionUpdated (or equivalent chat-list/group projection update) after
    // set_group_archived / accept_group_invite. Until then, the ChatsController
    // stream can lag behind local mutations and we forward the accepted/archived
    // group record so rows stop rendering stale pending/archived state.
    fun applyLocalGroupUpdate(record: AppGroupRecordFfi) {
        chatsController?.applyLocalGroupUpdate(record)
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

    internal fun applyOptimisticSentPreview(
        groupIdHex: String,
        preview: ChatListMessagePreviewFfi,
    ): Boolean = chatsController?.applyOptimisticSentPreview(groupIdHex, preview) == true

    internal fun commitOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
        confirmedMessageIdHex: String,
    ) {
        chatsController?.commitOptimisticSentPreview(groupIdHex, optimisticMessageIdHex, confirmedMessageIdHex)
    }

    /**
     * Apply the authoritative chat-list row returned by [markTimelineMessageRead].
     * Scoped to the bound [ChatsController] account so a mark-read on one
     * account cannot fold onto another after a switch.
     */
    fun applyChatListRowFromMarkRead(
        accountRef: String,
        row: ChatListRowFfi?,
    ) {
        val projected = row ?: return
        chatsController
            ?.takeIf { it.boundAccountRef == accountRef }
            ?.applyChatListRow(projected)
    }

    internal fun rollbackOptimisticSentPreview(
        groupIdHex: String,
        optimisticMessageIdHex: String,
    ) {
        chatsController?.rollbackOptimisticSentPreview(groupIdHex, optimisticMessageIdHex)
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

    internal fun cancelActiveForwardOperation(): Boolean = forwardOperationOwner.cancel()

    internal fun retryActiveForwardOperation(): Boolean = forwardOperationOwner.retry()

    internal fun dismissActiveForwardOperation(): Boolean = forwardOperationOwner.dismiss()

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
        val account =
            accounts.firstOrNull { summary ->
                summary.label == accountRef && summary.isSignedInSigningAccount()
            }
        val validGroupIds = targetGroupIds.filter(String::isNotBlank)
        if (account == null || validGroupIds.isEmpty()) return false
        shareInboundStager.stageToChats(
            context = appContext,
            accountIdHex = account.accountIdHex,
            groupIds = validGroupIds,
            payload = payload,
            draftAccountRef = accountRef,
        )
        return true
    }

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

    fun publishShareShortcuts(chats: List<ChatListItem>) {
        val accountRef = activeAccountRef ?: return
        val titleCopy = notificationGroupTitleCopy()
        shareShortcutPublisher.publish(accountRef, chats) { item ->
            chatListItemDisplayTitle(item, this, titleCopy)
        }
    }

    /**
     * Start one ordered text/media forward operation. Source attachments are
     * materialized through their original group, while every destination gets
     * a separate upload before its batch acquires the destination commit lock.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    internal fun startForwardMessages(
        targetGroupIds: List<String>,
        messages: List<ForwardMessagePayload>,
    ): Boolean {
        val sourceGroupIds =
            messages
                .map(ForwardMessagePayload::sourceGroupIdHex)
                .filter(String::isNotBlank)
        val targets =
            MessageProjector
                .normalizeForwardTargets(targetGroupIds)
                .filterNot { target -> sourceGroupIds.any { it.equals(target, ignoreCase = true) } }
        val account = activeAccountRef?.takeIf(String::isNotBlank)
        if (account == null || messages.isEmpty() || targets.isEmpty()) return false
        val sessionEpoch = mediaUploadSessionEpoch()

        fun requireCurrentAccount() {
            if (activeAccountRef != account || mediaUploadSessionEpoch() != sessionEpoch) {
                throw ForwardSessionInvalidatedException()
            }
        }

        val transport =
            object : ForwardTransport {
                override suspend fun materialize(
                    sourceGroupIdHex: String,
                    sourceMessageIdHex: String,
                    source: dev.ipf.whitenoise.android.core.ForwardAttachmentSource,
                ): PendingAttachment {
                    requireCurrentAccount()
                    val request =
                        AttachmentTransferRequest(
                            accountRef = account,
                            groupIdHex = sourceGroupIdHex,
                            messageIdHex = sourceMessageIdHex,
                            attachmentIndex = source.attachmentIndex,
                        )
                    return materializeForwardAttachment(
                        source = source,
                        resolveAuthoritativeReference = {
                            requireCurrentAccount()
                            resolveAttachmentReference(request).also { requireCurrentAccount() }
                        },
                        downloadPlaintext = { reference ->
                            requireCurrentAccount()
                            downloadAttachmentPlaintext(request, reference).also { requireCurrentAccount() }
                        },
                    )
                }

                override suspend fun upload(
                    targetGroupIdHex: String,
                    message: PreparedForwardMessage.Media,
                ): List<MediaAttachmentReferenceFfi> {
                    requireCurrentAccount()
                    val uploaded =
                        marmotIo {
                            uploadMedia(
                                account,
                                targetGroupIdHex,
                                MediaUploadRequestFfi(
                                    attachments =
                                        message.attachments.map { attachment ->
                                            MediaUploadAttachmentRequestFfi(
                                                fileName = attachment.fileName,
                                                mediaType = attachment.mediaType,
                                                plaintext = attachment.plaintextBytes,
                                                dim = attachment.dim,
                                                thumbhash = attachment.thumbhash,
                                            )
                                        },
                                    caption = message.caption,
                                    send = false,
                                    blossomServer = null,
                                ),
                            ).attachments.map { it.reference }
                        }
                    requireCurrentAccount()
                    return uploaded
                }

                private suspend fun recentForwardTimeline(
                    targetGroupIdHex: String,
                    batchMessageCount: Int,
                ): List<TimelineMessageRecordFfi> {
                    requireCurrentAccount()
                    val limit = maxOf(100u, batchMessageCount.toUInt() + 100u)
                    return marmotIo {
                        timelineMessages(
                            account,
                            TimelineMessageQueryFfi(
                                groupIdHex = targetGroupIdHex,
                                search = null,
                                before = null,
                                beforeMessageId = null,
                                after = null,
                                afterMessageId = null,
                                limit = limit,
                            ),
                        ).messages
                    }.also { requireCurrentAccount() }
                }

                private fun forwardProjectionRecords(
                    timeline: List<TimelineMessageRecordFfi>,
                    message: PreparedForwardMessage,
                    references: List<MediaAttachmentReferenceFfi>,
                ): Map<String, TimelineMessageRecordFfi> =
                    timeline
                        .asSequence()
                        .filter { record ->
                            record.direction == "sent" &&
                                record.kind == 9uL &&
                                when (message) {
                                    is PreparedForwardMessage.Text ->
                                        record.plaintext == message.text && record.media.isEmpty()
                                    is PreparedForwardMessage.Media ->
                                        record.plaintext == message.caption.orEmpty() &&
                                            record.media.map { it.ciphertextSha256 } ==
                                            references.map { it.ciphertextSha256 }
                                }
                        }.associateBy(TimelineMessageRecordFfi::messageIdHex)

                @Suppress("LongMethod", "ThrowsCount", "TooGenericExceptionCaught")
                override suspend fun publishBatch(
                    targetGroupIdHex: String,
                    messages: List<PreparedForwardMessage>,
                    uploadedReferences: Map<Int, List<MediaAttachmentReferenceFfi>>,
                    startIndex: Int,
                    onBeforeMessagePublished: (messageIndex: Int) -> Unit,
                    onMessagePublished: (messageIndex: Int) -> Unit,
                ) {
                    requireCurrentAccount()
                    withGroupCommitLock(account, targetGroupIdHex) {
                        val knownMessageIds =
                            recentForwardTimeline(targetGroupIdHex, messages.size)
                                .mapTo(mutableSetOf(), TimelineMessageRecordFfi::messageIdHex)
                        for (messageIndex in startIndex until messages.size) {
                            currentCoroutineContext().ensureActive()
                            onBeforeMessagePublished(messageIndex)
                            requireCurrentAccount()
                            val message = messages[messageIndex]
                            val references = uploadedReferences[messageIndex].orEmpty()
                            val evidenceBefore = knownMessageIds.toSet()
                            try {
                                val publishedMessageIds =
                                    when (message) {
                                        is PreparedForwardMessage.Text ->
                                            marmotIo { sendText(account, targetGroupIdHex, message.text) }.messageIds
                                        is PreparedForwardMessage.Media -> {
                                            check(references.isNotEmpty()) { "missing destination media references" }
                                            marmotIo {
                                                sendMediaAttachments(
                                                    account,
                                                    targetGroupIdHex,
                                                    references,
                                                    message.caption,
                                                )
                                            }.messageIds
                                        }
                                    }
                                knownMessageIds += publishedMessageIds
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Exception) {
                                val recoveryEvidence =
                                    ForwardPublishRecoveryEvidence(
                                        messageIndex = messageIndex,
                                        knownMessageIdsBefore = evidenceBefore,
                                        pendingMessageIdHex = null,
                                    )
                                val timelineAfter =
                                    try {
                                        recentForwardTimeline(targetGroupIdHex, messages.size)
                                    } catch (cancellation: CancellationException) {
                                        throw cancellation
                                    } catch (evidenceFailure: Exception) {
                                        failure.addSuppressed(evidenceFailure)
                                        throw ForwardPublishUncertainException(recoveryEvidence, failure)
                                    }
                                val projectionsAfter = forwardProjectionRecords(timelineAfter, message, references)
                                val newProjectionIds = projectionsAfter.keys - evidenceBefore
                                val newProjection = newProjectionIds.singleOrNull()?.let(projectionsAfter::get)
                                when {
                                    newProjection?.sourceMessageIdHex != null &&
                                        newProjection.invalidationStatus == null ->
                                        knownMessageIds += newProjection.messageIdHex
                                    newProjection != null &&
                                        !newProjection.deleted &&
                                        newProjection.invalidationStatus == null ->
                                        throw ForwardPublishUncertainException(
                                            recoveryEvidence.copy(pendingMessageIdHex = newProjection.messageIdHex),
                                            cause = failure,
                                        )
                                    newProjectionIds.isEmpty() ->
                                        throw ForwardPublishNotCommittedException(failure)
                                    else ->
                                        throw ForwardPublishUncertainException(recoveryEvidence, failure)
                                }
                            }
                            onMessagePublished(messageIndex)
                        }
                    }
                }

                @Suppress("ThrowsCount")
                override suspend fun recoverPendingPublish(
                    targetGroupIdHex: String,
                    message: PreparedForwardMessage,
                    uploadedReferences: List<MediaAttachmentReferenceFfi>,
                    evidence: ForwardPublishRecoveryEvidence,
                ): ForwardPublishRecoveryResult {
                    requireCurrentAccount()
                    val recovered =
                        withGroupCommitLock(account, targetGroupIdHex) {
                            val timeline =
                                try {
                                    recentForwardTimeline(targetGroupIdHex, messages.size)
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (invalidated: ForwardSessionInvalidatedException) {
                                    throw invalidated
                                } catch (_: Exception) {
                                    return@withGroupCommitLock ForwardPublishRecoveryResult.Unavailable
                                }
                            val newProjections =
                                forwardProjectionRecords(timeline, message, uploadedReferences)
                                    .filterKeys { it !in evidence.knownMessageIdsBefore }
                            val candidate =
                                evidence.pendingMessageIdHex
                                    ?.let { pendingId -> newProjections[pendingId] }
                                    ?: newProjections.values.singleOrNull()
                            if (candidate == null) {
                                return@withGroupCommitLock if (
                                    evidence.pendingMessageIdHex == null && newProjections.isEmpty()
                                ) {
                                    ForwardPublishRecoveryResult.NotCommitted
                                } else {
                                    ForwardPublishRecoveryResult.Unavailable
                                }
                            }
                            if (candidate.invalidationStatus != null || candidate.deleted) {
                                return@withGroupCommitLock ForwardPublishRecoveryResult.Unavailable
                            }
                            if (candidate.sourceMessageIdHex != null) {
                                return@withGroupCommitLock ForwardPublishRecoveryResult.Published
                            }
                            marmotIo { retryGroupConvergence(account, targetGroupIdHex) }
                            val delivered =
                                try {
                                    recentForwardTimeline(targetGroupIdHex, messages.size)
                                        .firstOrNull { it.messageIdHex == candidate.messageIdHex }
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (invalidated: ForwardSessionInvalidatedException) {
                                    throw invalidated
                                } catch (_: Exception) {
                                    null
                                }
                            if (delivered?.sourceMessageIdHex != null && delivered.invalidationStatus == null) {
                                ForwardPublishRecoveryResult.Published
                            } else {
                                ForwardPublishRecoveryResult.Unavailable
                            }
                        }
                    requireCurrentAccount()
                    return recovered
                }
            }

        val session =
            ForwardSession(
                scope = mutationsScope,
                messages = messages,
                targetGroupIds = targets,
                transport = transport,
                onFailure = { targetGroupIdHex, stage, throwable ->
                    Log.w(
                        "DMMessageForward",
                        "forward failed stage=$stage target=${targetGroupIdHex?.take(8).orEmpty()}",
                        throwable,
                    )
                },
            )
        // The app-scoped owner mirrors live per-target state into the global
        // non-modal activity strip and retains terminal failures for explicit
        // retry/dismiss. Its bounded retries preserve the same safe recovery
        // boundary as the session: uncertain publishes converge, never resend.
        val started = forwardOperationOwner.start(session)
        if (!started) {
            session.release()
        }
        return started
    }

    private fun scheduleForwardTerminalDismiss(snapshot: ForwardOperationSnapshot) {
        if (
            snapshot.phase != ForwardOperationPhase.Completed &&
            snapshot.phase != ForwardOperationPhase.Cancelled
        ) {
            return
        }
        val dismissGeneration = ++forwardTerminalDismissGeneration
        mutationsScope.launch {
            delay(FORWARD_TERMINAL_STATUS_DURATION_MS)
            if (
                forwardTerminalDismissGeneration == dismissGeneration &&
                activeForwardOperation.value == snapshot
            ) {
                dismissActiveForwardOperation()
            }
        }
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
                            marmotIo { sendText(account, groupIdHex, trimmed) }
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
                                marmotIo { sendText(account, groupIdHex, body) }
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

    internal fun profileAddableGroupsState(accountIdHex: String): ProfileGroupPickerState =
        chatsController?.profileAddableGroupsState(accountIdHex, activeAccount?.accountIdHex)
            ?: ProfileGroupPickerState.empty()

    internal fun profilePromotableGroupsState(accountIdHex: String): ProfileGroupPickerState =
        chatsController?.profilePromotableGroupsState(accountIdHex, activeAccount?.accountIdHex)
            ?: ProfileGroupPickerState.empty()

    internal fun requestProfileGroupMembers(
        groupIds: Iterable<String>,
        retry: Boolean = false,
    ) {
        if (retry) {
            chatsController?.retryMemberSnapshots(groupIds)
        } else {
            chatsController?.requestMemberSnapshots(groupIds)
        }
    }

    suspend fun promoteProfileInGroup(
        targetRef: String,
        groupIdHex: String,
    ): Boolean {
        val target = targetRef.trim()
        val groupId = groupIdHex.trim()
        val account = activeAccountRef
        if (target.isEmpty() || groupId.isEmpty() || account == null) return false
        return runCatchingCancellable {
            withGroupCommitLock(account, groupId) {
                val result =
                    marmotIo(MarmotTraceSection.PROMOTE_ADMIN) {
                        promoteAdminDetailed(account, groupId, target)
                    }
                chatsController?.applyProfileGroupDetails(account, result.details)
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
     * picker open with the failed groups still selected for retry.
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
        var failures = 0
        var firstFailure: AppText? = null
        var firstFailureThrowable: Throwable? = null
        for (groupIdHex in targets) {
            runCatchingCancellable {
                withGroupCommitLock(account, groupIdHex) {
                    val result =
                        marmotIo(MarmotTraceSection.INVITE_MEMBERS) {
                            inviteMembersDetailed(account, groupIdHex, listOf(ref))
                        }
                    chatsController?.applyProfileGroupDetails(account, result.details)
                }
            }.onFailure { error ->
                failures += 1
                if (firstFailure == null) {
                    firstFailureThrowable = error
                    val message = error.readableMessage()
                    firstFailure =
                        if (isDuplicateSignatureKeyError(message)) {
                            AppText.Resource(
                                R.string.toast_couldnt_add_member_duplicate_detail,
                                listOf(displayName(ref)),
                            )
                        } else {
                            AppText.Resource(R.string.error_try_again)
                        }
                }
            }
        }
        val outcome =
            ProfileGroupInviteOutcome(
                attempted = targets.size,
                failures = failures,
                firstFailure = firstFailure,
            )
        profileGroupInviteToast(outcome)?.let { toast ->
            val failure = firstFailureThrowable
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
        return outcome.completedSuccessfully
    }

    fun sharedGroupsWith(accountIdHex: String): List<ChatListItem> = chatsController?.sharedGroupsWith(accountIdHex, activeAccount?.accountIdHex).orEmpty()

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

    internal suspend fun resolveProvenanceDirectChat(
        provenanceGroupIdHex: String?,
        targetReference: String,
    ): NewMessageDirectChatResolution =
        chatsController?.resolveProvenanceDirectChat(provenanceGroupIdHex, targetReference)
            ?: NewMessageDirectChatResolution(item = null, createRequired = false)

    internal suspend fun resolveExistingDirectChat(
        targetReference: String,
        excludingGroupIdHex: String? = null,
    ): NewMessageDirectChatResolution =
        chatsController?.resolveExistingDirectChat(targetReference, excludingGroupIdHex)
            ?: NewMessageDirectChatResolution(item = null, createRequired = false)

    private fun isActiveConversation(
        accountRef: String,
        groupIdHex: String,
    ): Boolean =
        activeConversationAccountRef == accountRef &&
            activeConversationGroupIdHex?.equals(groupIdHex, ignoreCase = true) == true

    private val marmotBridgeTracer = MarmotBridgeTracer()

    suspend fun <T> marmotIo(block: suspend MarmotInterface.() -> T): T =
        withContext(Dispatchers.IO) {
            marmot().block()
        }

    suspend fun <T> marmotIo(
        traceSection: String,
        block: suspend MarmotInterface.() -> T,
    ): T =
        withContext(Dispatchers.IO) {
            marmotBridgeTracer.trace(traceSection) { marmot().block() }
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
        catchUpAccountsBestEffort()
    }

    /**
     * Starts one process-lifetime catch-up after a chat list has rendered its
     * local snapshot. A blocked relay call must not block that controller's live
     * consumers, and rapid rebinds can share the same all-account catch-up.
     */
    internal fun launchCatchUpAccounts() {
        if (accountCatchUpJob?.isActive == true) return
        accountCatchUpJob =
            notificationScope.launch {
                if (catchUpAccountsBestEffort()) recordStartupRelayCatchUpReady()
            }
    }

    private suspend fun catchUpAccountsBestEffort(): Boolean =
        runCatchingCancellable { marmotIo { catchUpAccounts() } }
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
        isForegroundCatchUpRunning = true
        try {
            val pendingGeneration = pushTokenStore.pendingPushWakeCatchUpGeneration()
            if (catchUpAccountsBestEffort()) {
                clearPendingPushWakeCatchUpIfObserved(pendingGeneration)
            }
        } finally {
            isForegroundCatchUpRunning = false
        }
    }

    /**
     * Memoize an in-flight attachment download keyed on [cacheKey] and route
     * the work through [mutationsScope] so it survives caller cancellation
     * (e.g. the user tapped a file and swiped away). Concurrent callers for
     * the same key share the same Deferred; the entry is dropped when the
     * Deferred completes so a later retry can re-attempt.
     */
    internal fun memoizedDownload(
        cacheKey: String,
        block: suspend CoroutineScope.() -> ByteArray,
    ): Deferred<ByteArray> {
        synchronized(inFlightDownloadsLock) {
            inFlightDownloads[cacheKey]?.takeIf { it.isActive }?.let { return it }
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
                    attachmentDownloadGate.withPermit { downloadScope.block() }
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
     * Resolve one attachment from MDK's authoritative media projection. This is
     * intentionally the only recovery path used by durable Android work: the
     * WorkManager request stores identity, never a duplicate media reference.
     */
    internal suspend fun resolveAttachmentReference(request: AttachmentTransferRequest): MediaAttachmentReferenceFfi? =
        marmotIo { listMedia(request.accountRef, request.groupIdHex, null) }
            .firstOrNull { record ->
                record.messageIdHex.equals(request.messageIdHex, ignoreCase = true) &&
                    record.attachmentIndex.toInt() == request.attachmentIndex
            }?.reference

    internal fun enqueueAttachmentDownload(request: AttachmentTransferRequest) {
        AttachmentDownloadWorker.enqueue(appContext, request)
    }

    /** True only when plaintext is retained in L1 or the encrypted L2 cache. */
    internal suspend fun hasCachedAttachmentAfterHydration(request: AttachmentTransferRequest): Boolean {
        val cacheKey =
            mediaCacheKey(
                request.accountRef,
                request.groupIdHex,
                request.messageIdHex,
                request.attachmentIndex,
            )
        val initialMemoryHit =
            withContext(Dispatchers.Main.immediate) { cachedMediaPlaintext(cacheKey) != null }
        val diskHit =
            initialMemoryHit ||
                withContext(Dispatchers.IO) {
                    diskMediaCache.containsAfterHydration(cacheKey)
                }
        return diskHit ||
            withContext(Dispatchers.Main.immediate) {
                cachedMediaPlaintext(cacheKey) != null
            }
    }

    /**
     * Shared download/cache implementation for UI controllers and durable work.
     * MDK owns locator failover, validation and decryption; Android owns the
     * bounded L1 plus Keystore-encrypted L2 publication.
     */
    internal suspend fun downloadAttachmentPlaintext(
        request: AttachmentTransferRequest,
        reference: MediaAttachmentReferenceFfi,
    ): ByteArray {
        val cacheKey =
            mediaCacheKey(
                request.accountRef,
                request.groupIdHex,
                request.messageIdHex,
                request.attachmentIndex,
            )
        val cached =
            withContext(Dispatchers.Main.immediate) { cachedMediaPlaintext(cacheKey) }
                ?: withContext(Dispatchers.IO) { diskMediaCache.get(cacheKey) }
                    ?.also { onDisk ->
                        withContext(Dispatchers.Main.immediate) {
                            cacheMediaPlaintext(cacheKey, onDisk)
                        }
                    }
        if (cached != null) return cached

        val deferred =
            memoizedDownload(cacheKey) {
                val publicationToken = diskMediaCache.capturePublicationToken()
                val result =
                    runCatchingCancellable {
                        marmotIo { downloadMedia(request.accountRef, request.groupIdHex, reference) }
                    }.onFailure {
                        val host =
                            reference.locators
                                .firstOrNull()
                                ?.value
                                ?.let { url ->
                                    url
                                        .substringAfter("://", "")
                                        .substringBefore('/')
                                        .substringBefore('?')
                                        .substringBefore('#')
                                }.orEmpty()
                        Log.w(
                            "DMAttachmentDownload",
                            "download failed group=${request.groupIdHex.take(8)} " +
                                "message=${request.messageIdHex.take(8)} host=$host",
                            it,
                        )
                    }.getOrThrow()
                if (result.plaintext.isNotEmpty()) {
                    cacheMediaPlaintext(cacheKey, result.plaintext)
                    val plaintext = result.plaintext
                    withContext(Dispatchers.IO) {
                        diskMediaCache.put(
                            cacheKey,
                            plaintext,
                            publicationToken,
                            reference.ciphertextSha256,
                        )
                    }
                }
                result.plaintext
            }
        return deferred.await()
    }

    internal suspend fun downloadAttachmentForDurableWork(request: AttachmentTransferRequest): Boolean {
        val reference = resolveAttachmentReference(request) ?: throw AttachmentReferenceNotReadyException()
        downloadAttachmentPlaintext(request, reference)
        return hasCachedAttachmentAfterHydration(request)
    }

    /**
     * Bootstrap belongs to the process, not the first composition that awaited
     * it. A caller gets an actionable timeout while the single underlying
     * attempt remains alive; retry re-awaits that attempt instead of starting a
     * second Marmot runtime beside a blocked native call.
     */
    suspend fun bootstrap() {
        val attempt =
            withContext(Dispatchers.Main.immediate) {
                if (phase is AppPhase.Failed) phase = AppPhase.Bootstrapping
                bootstrapAttempts.currentOrStart {
                    mutationsScope.async { bootstrapLocked() }
                }
            }
        val completed = awaitBootstrapAttempt(attempt, BOOTSTRAP_ACTIONABLE_TIMEOUT_MILLIS)
        if (!completed) {
            withContext(Dispatchers.Main.immediate) {
                if (attempt.isActive && phase == AppPhase.Bootstrapping) {
                    phase =
                        AppPhase.Failed(
                            privacySafeErrorPresentation(
                                operationCode = "APP_BOOTSTRAP_TIMEOUT",
                                throwable = IllegalStateException("bootstrap stage exceeded deadline"),
                                message = AppText.Resource(R.string.startup_taking_too_long),
                            ),
                        )
                }
            }
        }
    }

    private suspend fun bootstrapLocked() {
        phase = AppPhase.Bootstrapping
        try {
            if (resumeCompletedBootstrap()) return
            traceStartupStage("notification-platform-setup") {
                // Tray readiness must precede Marmot.start(): the passive MDK
                // broadcast has no replay once the startup receiver attaches.
                withContext(Dispatchers.Default) {
                    localNotificationPresenter.ensureChannels()
                }
                refreshLocalNotificationPermission()
            }
            startBootstrapRuntime()
            // This is deliberately the first post-start suspension. Do not let
            // logging, privacy, or account work widen the no-replay interval.
            val receiverReady = awaitNotificationReceiverForStartup()
            appStateDebug { "marmot started; notification receiver active=$receiverReady" }
            traceStartupStage("notification-privacy-setup") { refreshSecurityPrivacySettings() }
            val refreshedAccounts = traceStartupStage("account-refresh") { refreshAccountSnapshot() }
            prepareStartupUnreadRefresh(refreshedAccounts)
            migrateLegacyDrafts()
            migrateLegacyMutePreferences()
            // Resolve interrupted editor commits against MDK before any draft
            // is reopened, and reclaim encrypted sources with no live session.
            traceStartupStage("draft-reconciliation") {
                messageDraftRepository.reconcileEditorState(editorSourceStore).onFailure {
                    appStateDebug(it) { "photo editor reconciliation deferred: ${it.readableMessage()}" }
                }
            }
            appStateDebug {
                "accounts loaded count=${accounts.size} active=$activeAccountRef labels=${accounts.map { it.label.take(8) to it.running }}"
            }
            // Re-install external-signer callbacks now that accounts are known,
            // BEFORE the reconciliation below (setActiveAccount can sign in a
            // signed-out account / re-key media, warmProfile publishes) drives any
            // signing for an external-signing account.
            traceStartupStage("external-signer-registration") { reregisterExternalSigners() }
            if (accounts.isEmpty()) {
                localNotificationSettings = null
                pendingStartupUnreadRefresh = null
                phase = AppPhase.Onboarding
            } else {
                traceStartupStage("account-activation") {
                    val target =
                        activeAccountRef?.takeIf { ref -> accounts.any { it.label == ref } }
                            ?: accounts.first().label
                    // The callback is the local-ready boundary. The shell mounts
                    // before profile/notification/push warmup and before the
                    // cross-account unread roster fold.
                    setActiveAccount(
                        label = target,
                        deferUnreadRefresh = true,
                        onActivated = { phase = AppPhase.Ready },
                    )
                }
                // Preserve the old behavior when an attempted sign-in returns
                // before reaching setActiveAccount's activation callback.
                if (phase == AppPhase.Bootstrapping) phase = AppPhase.Ready
            }
            bootstrapCompleted = true
        } catch (error: Throwable) {
            if (error is CancellationException) {
                // The process-owned listener keeps retrying independently, but
                // a cancelled UI bootstrap must not strand the splash forever.
                phase = AppPhase.Failed(privacySafeErrorPresentation("APP_BOOTSTRAP", error))
                throw error
            }
            appStateDebug(error) { "bootstrap failed: ${error.readableMessage()}" }
            phase = AppPhase.Failed(privacySafeErrorPresentation("APP_BOOTSTRAP", error))
        }
    }

    private suspend fun startBootstrapRuntime(): AppMarmotRuntime {
        val opened =
            bootstrapRuntime.open(
                construct = {
                    traceStartupStage("client-construction") {
                        withContext(Dispatchers.IO) {
                            marmotRuntimeFactory(appContext).also { runtime ->
                                // Publish before start so lifecycle consumers
                                // and later listener retries can resolve Marmot.
                                marmotRuntime = runtime
                                AvatarImageLoader.attachProfileImageFetcher { url, maxBytes ->
                                    runtime.marmot.downloadProfileImage(url, maxBytes)
                                }
                            }
                        }
                    }
                },
                configure = { runtime ->
                    appStateDebug { "bootstrap root=${runtime.rootPath}" }
                    traceStartupStage("privacy-runtime-configuration") {
                        withContext(Dispatchers.IO) { runtime.marmot.configurePrivacyRuntime() }
                    }
                },
                start = { runtime ->
                    startMarmotWithNotificationListener(runtime)
                    appStateDebug { "marmot started" }
                },
                closeAfterFailure = { runtime ->
                    traceStartupStage("failed-runtime-close") {
                        withContext(Dispatchers.IO) { runtime.marmot.shutdownAndClose() }
                    }
                    if (marmotRuntime === runtime) marmotRuntime = null
                },
            )
        marmotRuntime = opened
        return opened
    }

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
                        traceStartupStage("marmot-start") { runtime.marmot.start() }
                        runtimeStartResult.complete(Result.success(Unit))
                        runNotificationListenerLoop(runtime.marmot)
                    } catch (cancel: CancellationException) {
                        runtimeStartResult.complete(Result.failure(cancel))
                        throw cancel
                    } catch (failure: Throwable) {
                        runtimeStartResult.complete(Result.failure(failure))
                    }
                }
            }
        check(listenerJob != null && installedStartupListener) {
            "notification listener unavailable before Marmot startup"
        }
        runtimeStartResult.await().getOrThrow()
    }

    private suspend fun resumeCompletedBootstrap(): Boolean {
        if (!bootstrapCompleted) return false
        val receiverReady = awaitNotificationReceiverForStartup()
        appStateDebug { "bootstrap resumed; notification receiver active=$receiverReady" }
        phase = if (accounts.isEmpty()) AppPhase.Onboarding else AppPhase.Ready
        return true
    }

    private fun receiverUnavailable() = IllegalStateException("notification receiver unavailable during bootstrap")

    private suspend fun awaitNotificationReceiverForStartup(): Boolean {
        if (networkNotificationRecoverySuppressed) return false
        return awaitNotificationReceiverForStartup(
            notificationJob = notificationJob,
            receiverActive = notificationReceiverActive,
            receiverRetryWake = notificationReceiverRetryWake,
            timeoutMillis = notificationReceiverTimeoutMillis(),
            launchListener = ::launchNotificationListenerLoop,
        )
    }

    suspend fun ensureNotificationRuntimeStarted() {
        if (!bootstrapCompleted) {
            bootstrap()
            val receiverReady = bootstrapCompleted && notificationReceiverActive.value
            if (receiverReady) {
                drainPendingNativePushRegistrationSyncIfNeeded()
                drainPendingPushWakeCatchUpIfNeeded()
            }
            if (!receiverReady) throw receiverUnavailable()
            return
        }
        localNotificationPresenter.ensureChannels()
        refreshLocalNotificationPermission()
        if (!awaitNotificationReceiverForStartup()) throw receiverUnavailable()
        if (accounts.isEmpty()) refreshAccounts()
        refreshLocalNotificationSettings()
        drainPendingNativePushRegistrationSyncIfNeeded()
        drainPendingPushWakeCatchUpIfNeeded()
    }

    internal fun notificationRuntimeRecoveryGeneration(): Long = notificationRuntimeRecoveryGeneration.get()

    internal fun notificationRuntimeRecoveryAllowed(generation: Long): Boolean =
        !networkNotificationRecoverySuppressed && notificationRuntimeRecoveryGeneration.get() == generation

    private suspend fun ensureNotificationReceiverForNetworkReconnect(): Boolean {
        if (!bootstrapCompleted) bootstrap()
        if (!bootstrapCompleted || networkNotificationRecoverySuppressed) return false

        localNotificationPresenter.ensureChannels()
        refreshLocalNotificationPermission()
        val receiverReady = awaitNotificationReceiverForStartup()
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

    suspend fun ensureNotificationRuntimeStartedAndAwaitPushDrain(timeoutMs: Long = NOTIFICATION_PUSH_DRAIN_TIMEOUT_MILLIS): Boolean =
        coroutineScope {
            val sequenceBeforeStart = notificationDrainSequence.get()
            val drain =
                async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeoutOrNull(timeoutMs) {
                        notificationDrainSignals.first { it > sequenceBeforeStart }
                    } != null
                }
            ensureNotificationRuntimeStarted()
            drain.await()
        }

    private suspend fun drainPendingNativePushRegistrationSyncIfNeeded() {
        if (pushTokenStore.nativePushRegistrationSyncPending()) {
            syncNativePushRegistrationIfEnabled()
        }
    }

    private suspend fun drainPendingPushWakeCatchUpIfNeeded() {
        val pendingGeneration = pushTokenStore.pendingPushWakeCatchUpGeneration()
        if (pendingGeneration == 0L) return
        if (catchUpAccountsBestEffort()) {
            clearPendingPushWakeCatchUpIfObserved(pendingGeneration)
        }
    }

    private fun clearPendingPushWakeCatchUpIfObserved(pendingGeneration: Long) {
        if (pendingGeneration == 0L) return
        if (pushTokenStore.clearPendingPushWakeCatchUp(pendingGeneration)) {
            appStateDebug { "pending push wake catch-up drained" }
        } else {
            appStateDebug { "newer pending push wake catch-up remains queued" }
        }
    }

    suspend fun createIdentity() {
        val startedAt = SystemClock.elapsedRealtime()
        try {
            val summary = marmotIo { createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays) }
            activateCreatedIdentity(summary)
            phase = AppPhase.Ready
            presentTransient(R.string.toast_identity_created)
            appStateDebug { "identity engine setup returned in ${SystemClock.elapsedRealtime() - startedAt}ms" }
            launchIdentityPostCreateWarmup(summary)
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            presentFailure(R.string.toast_couldnt_create_identity, "IDENTITY_CREATE", error)
        }
    }

    private fun activateCreatedIdentity(summary: AccountSummaryFfi) {
        if (summary.label != activeAccountRef) {
            clearInMemoryMediaCaches()
            clearCrossAccountCaches()
        }
        accounts = accountSummariesWithCreatedIdentity(accounts, summary)
        accountListRevision += 1L
        activeAccountRef = summary.label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, summary.label).apply()
        localNotificationSettings = null
        reloadMediaAutoDownloadMatrix()
    }

    private fun launchIdentityPostCreateWarmup(summary: AccountSummaryFfi) {
        mutationsScope.launch {
            runBestEffortPostCommitSteps(
                steps =
                    listOf(
                        "refresh-accounts" to { refreshAccounts() },
                        "configure-privacy-runtime" to {
                            if (activeAccountRef == summary.label) configurePrivacyRuntime()
                        },
                        "refresh-notification-settings" to {
                            if (activeAccountRef == summary.label) refreshLocalNotificationSettings()
                        },
                        "warm-profile" to {
                            if (activeAccountRef == summary.label) warmProfile(summary.accountIdHex)
                        },
                        "sync-push-registration" to {
                            if (activeAccountRef == summary.label) syncNativePushRegistrationIfEnabled()
                        },
                    ),
                onFailure = { step, error ->
                    appStateDebug(error) { "post-create $step failed: ${error.readableMessage()}" }
                },
            )
        }
    }

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
            val summary = engineLogin(trimmed)
            activateImportedIdentity(summary)
            IdentityImportOutcome.Success
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
            val summary = engineRecoveringLogin(trimmed)
            activateImportedIdentity(summary)
            IdentityImportOutcome.Success
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            logRedactedIdentityFailure("identity setup recovery", error)
            identityImportOutcome(error)
        }
    }

    private suspend fun engineLogin(nsec: String): AccountSummaryFfi {
        val relays = MarmotClient.bootstrapRelays
        val injected = identityLoginCalls
        if (injected != null) return injected.login(nsec, relays, relays)
        return marmotIo { login(nsec, relays, relays) }
    }

    // The acknowledgement is a constant, never derived from state: the only
    // caller is the consent prompt's confirm action.
    private suspend fun engineRecoveringLogin(nsec: String): AccountSummaryFfi {
        val relays = MarmotClient.bootstrapRelays
        val injected = identityLoginCalls
        if (injected != null) {
            return injected.loginRecoveringIncompleteSetup(
                nsec,
                relays,
                relays,
                acknowledgePossibleKeyPackageOrphan = true,
            )
        }
        return marmotIo {
            loginRecoveringIncompleteSetup(nsec, relays, relays, acknowledgePossibleKeyPackageOrphan = true)
        }
    }

    private suspend fun activateImportedIdentity(summary: AccountSummaryFfi) {
        refreshAccounts()
        setActiveAccount(summary.label)
        refreshLocalNotificationSettings()
        phase = AppPhase.Ready
        presentTransient(R.string.toast_identity_imported)
        warmProfile(summary.accountIdHex)
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
     * Log in with the NIP-55 external signer (Amber). Mirrors [importIdentity]:
     * ask the signer for its public key (foreground prompt), register the
     * external-signer callback, then create the local account via
     * `loginExternalSigner` (which signs its kind:450 identity proof through the
     * signer — so a returned summary proves the signer works, and a failure
     * leaves no account behind).
     *
     * Typed engine/protocol errors are surfaced distinctly: a user cancel/reject
     * is a gentle "cancelled" toast (the account is untouched); every other
     * failure (unavailable / mismatch / runtime) is a copyable failure toast.
     */
    suspend fun loginWithAmber() {
        try {
            amberSignInStage = 1
            val reportedPubkey = withContext(Dispatchers.IO) { amberSigner.requestPublicKey() }
            // Normalize npub/hex to the canonical hex the account is keyed by, so
            // the login-time signer and the startup re-registration signer share
            // the same current_user.
            val pubkeyHex =
                marmotIo { accountIdHex(reportedPubkey) }
                    ?: throw MarmotKitException.Runtime("signer returned an invalid public key")
            amberSignInStage = 2
            val summary =
                marmotIo {
                    loginExternalSigner(
                        pubkeyHex,
                        amberSigner.buildSigner(pubkeyHex),
                        MarmotClient.bootstrapRelays,
                        MarmotClient.bootstrapRelays,
                    )
                }
            // Mirror the cold-start restore barrier before exposing the main
            // shell. loginExternalSigner creates and reconciles the account,
            // but a fresh account's worker/relay runtime can still be replacing
            // its setup-time signer as that call returns. Re-registering through
            // the restore path performs one final reconciliation with a stable
            // callback, so an immediate createGroup cannot race that transition
            // and fail until the next process restart (issue #1551).
            marmotIo {
                registerExternalSigner(
                    summary.label,
                    amberSigner.buildSigner(pubkeyHex),
                )
            }
            refreshAccounts()
            setActiveAccount(summary.label)
            refreshLocalNotificationSettings()
            phase = AppPhase.Ready
            presentTransient(R.string.toast_identity_imported)
            warmProfile(summary.accountIdHex)
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            if (error is MarmotKitException.ExternalSignerRejected) {
                presentTransient(R.string.toast_amber_sign_in_cancelled)
            } else {
                appStateDebug(error) { "amber login failed: ${error.readableMessage()}" }
                presentFailure(R.string.toast_couldnt_login_amber, "AMBER_SIGN_IN", error)
            }
        } finally {
            amberSignInStage = null
        }
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

    private suspend fun refreshAccountSnapshot(): List<AccountSummaryFfi> {
        val refreshedAccounts = marmotIo { listAccounts() }
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
        accounts = refreshedAccounts
        accountListRevision += 1L
        releaseContactClearGuardForSignedInAccounts(refreshedAccounts)
        return refreshedAccounts
    }

    suspend fun refreshAccounts() {
        val refreshedAccounts = refreshAccountSnapshot()
        refreshAccountUnreadCounts(refreshedAccounts)
    }

    fun unreadCountForAccount(accountRef: String): ULong = accountUnreadCounts[accountRef] ?: 0uL

    /**
     * Whether [accountRef]'s avatar should light the unread dot, from that
     * account's own per-account aggregate (#805). Shared by the active avatar,
     * the secondary top-bar avatars, and the account switcher so no avatar can
     * light for another account's unread.
     */
    fun accountShowsUnreadDot(accountRef: String?): Boolean = accountShowsUnreadDot(accountRef, accountUnreadCounts) || accountRef in accountManualUnreadRefs

    internal fun updateAccountUnreadCount(
        accountRef: String?,
        unreadCount: ULong,
    ) {
        val ref = accountRef?.takeIf { it.isNotBlank() } ?: return
        accountUnreadCounts = accountUnreadCounts + (ref to unreadCount)
    }

    /**
     * Accounts with at least one manually-marked-unread chat. A boolean
     * sidecar to [accountUnreadCounts]: the numeric map stays a real message
     * count (it feeds literal count badges), while this set only lights dots.
     */
    internal var accountManualUnreadRefs by mutableStateOf<Set<String>>(emptySet())
        private set

    private val manualUnreadLock = Any()

    // Accounts whose rows have actually been folded at least once this
    // process (fold success or a live controller recompute). The cheap
    // zero-count short-circuit is only trusted for these: the manual-unread
    // flag lives in rows, so an account never yet folded — cold start, a
    // fresh sign-in, or a transiently failed fold — must take the row fold.
    private var manualUnreadFoldedRefs = setOf<String>()

    internal fun updateAccountManualUnread(
        accountRef: String?,
        hasManualUnread: Boolean,
    ) {
        val ref = accountRef?.takeIf { it.isNotBlank() } ?: return
        // Read-modify-write under a lock: writers race across the controller's
        // main-thread recompute, the notification hot path, and the four-way
        // concurrent bulk refresh.
        synchronized(manualUnreadLock) {
            manualUnreadFoldedRefs = manualUnreadFoldedRefs + ref
            accountManualUnreadRefs =
                if (hasManualUnread) accountManualUnreadRefs + ref else accountManualUnreadRefs - ref
        }
    }

    private fun manualUnreadFolded(ref: String) = synchronized(manualUnreadLock) { ref in manualUnreadFoldedRefs }

    private fun retainManualUnreadRefs(current: Set<String>) {
        synchronized(manualUnreadLock) {
            manualUnreadFoldedRefs = manualUnreadFoldedRefs.intersect(current)
            accountManualUnreadRefs = accountManualUnreadRefs.intersect(current)
        }
    }

    @Suppress(
        "ReturnCount", // Empty, stale-empty, and stale-in-flight snapshots each stop before publishing.
        "CyclomaticComplexMethod", // Raw and roster-aware paths deliberately share one guarded publication point.
    )
    private suspend fun refreshAccountUnreadCounts(
        accountSummaries: List<AccountSummaryFfi> = accounts,
        loadMemberRosters: Boolean = true,
        stillCurrent: () -> Boolean = { true },
    ) {
        val signingAccounts = accountSummaries.filter { it.isSignedInSigningAccount() }
        if (signingAccounts.isEmpty()) {
            if (!stillCurrent()) return
            accountUnreadCounts = emptyMap()
            retainManualUnreadRefs(emptySet())
            return
        }

        val previous = accountUnreadCounts
        val rawCountsByHex =
            runCatchingCancellable {
                marmotIo { accountUnreadSummary().associate { it.accountIdHex to it.unreadCount } }
            }.onFailure {
                appStateDebug(it) { "account unread summary refresh failed: ${it.readableMessage()}" }
            }.getOrNull()
        if (!loadMemberRosters) {
            if (!stillCurrent()) return
            accountUnreadCounts = rawAccountUnreadCounts(signingAccounts, rawCountsByHex, previous)
            retainManualUnreadRefs(signingAccounts.mapTo(mutableSetOf(), AccountSummaryFfi::label))
            return
        }
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
                                    previousCount = previous[summary.label],
                                    memberGate = memberGate,
                                )
                            }
                        }
                    }.awaitAll()
            }
        val refreshedCounts = linkedMapOf<String, ULong>()
        refreshedPairs.forEach { refreshedCounts[it.accountRef] = it.unreadCount }
        // A sign-out/account refresh may have completed while the cold-start
        // fold was suspended. Discard that obsolete result rather than
        // resurrecting counts for an account no longer in the authoritative
        // list; the newer lifecycle operation owns convergence.
        if (!stillCurrent()) return
        refreshedPairs.forEach { result ->
            result.hasManualUnread?.let { updateAccountManualUnread(result.accountRef, it) }
        }
        // Re-read after the FFI suspension: a single-key merge
        // (updateAccountUnreadCount / refreshAccountUnreadCount) may have landed
        // while we were suspended. Those values are fresher than our snapshot, so
        // let them win for keys we still track; a wholesale assign would clobber
        // them. Accounts absent from refreshedCounts (removed) are still dropped.
        val merged = refreshedCounts.toMutableMap()
        accountUnreadCounts.forEach { (ref, count) ->
            if (previous[ref] != count && merged.containsKey(ref)) merged[ref] = count
        }
        accountUnreadCounts = merged
        // Removed accounts drop their manual flag alongside their count.
        retainManualUnreadRefs(refreshedCounts.keys)
    }

    private suspend fun refreshAccountUnreadFold(
        summary: AccountSummaryFfi,
        rawCount: ULong?,
        previousCount: ULong?,
        memberGate: Semaphore,
    ): AccountUnreadFoldResult {
        val cheapZero =
            rawCount == 0uL &&
                manualUnreadFolded(summary.label) &&
                summary.label !in accountManualUnreadRefs
        // The cheap engine total can't see the client's manual-unread flag, so
        // an account believed to be manually flagged still takes the row fold.
        if (!cheapZero) {
            refreshEffectiveAccountUnreadCount(summary, memberGate)?.let { return it }
        }
        return AccountUnreadFoldResult(
            accountRef = summary.label,
            unreadCount = if (cheapZero) 0uL else rawCount ?: previousCount ?: 0uL,
            hasManualUnread = null,
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
                        onFailure = { groupIdHex, error ->
                            appStateDebug(error) {
                                "account unread member refresh failed " +
                                    "account=${ref.take(8)} group=${groupIdHex.take(8)}: " +
                                    error.readableMessage()
                            }
                        },
                    ) { groupIdHex ->
                        groupMembers(ref, groupIdHex)
                    }
                AccountUnreadFoldResult(
                    accountRef = ref,
                    unreadCount = accountUnreadCount(rows, summary.accountIdHex, membersByGroupId),
                    hasManualUnread = accountHasManualUnread(rows, summary.accountIdHex, membersByGroupId),
                )
            }
        }.onFailure {
            appStateDebug(it) { "account unread refresh failed for ${ref.take(8)}: ${it.readableMessage()}" }
        }.getOrNull()
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
        val result = refreshEffectiveAccountUnreadCount(summary) ?: return
        result.hasManualUnread?.let { updateAccountManualUnread(result.accountRef, it) }
        accountUnreadCounts = accountUnreadCounts + (ref to result.unreadCount)
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

    /**
     * Read the target account's local MDK projection before publishing the new
     * active account. The old account remains composed behind the selector
     * while these on-device reads run, so the first target composition can be
     * the cached list rather than a loading placeholder.
     */
    private suspend fun loadAccountSwitchLocalSnapshot(
        accountRef: String,
        generation: Long,
    ): AccountSwitchLocalSnapshot? {
        var chatListSubscription: ChatListSubscription? = null
        var chatsSubscription: ChatsSubscription? = null
        return try {
            chatListSubscription = marmotIo { subscribeChatList(accountRef, includeArchived = true) }
            ensureAccountSwitchRequestIsCurrent(generation)
            chatsSubscription = marmotIo { subscribeChats(accountRef, includeArchived = true) }
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(accountRef, "local-subscriptions-ready", 0)

            val rows = withContext(Dispatchers.IO) { chatListSubscription.snapshot() }
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(accountRef, "cached-chat-rows-ready", rows.size)

            val groups = withContext(Dispatchers.IO) { chatsSubscription.snapshot() }
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(accountRef, "cached-groups-ready", rows.size)

            val memberIds = loadAccountSwitchMemberIds(accountRef, rows)
            ensureAccountSwitchRequestIsCurrent(generation)
            val memberStage = accountSwitchMemberStage(rows, memberIds)
            recordAccountSwitchPreloadStage(accountRef, memberStage, rows.size)

            val activeAccountIdHex = accounts.firstOrNull { it.label == accountRef }?.accountIdHex
            val profiles = loadAccountSwitchProfileSeeds(rows, memberIds, activeAccountIdHex)
            ensureAccountSwitchRequestIsCurrent(generation)
            recordAccountSwitchPreloadStage(accountRef, "persisted-profiles-ready", rows.size)
            AccountSwitchLocalSnapshot(
                accountRef = accountRef,
                activeAccountIdHex = activeAccountIdHex,
                rows = rows,
                groups = groups,
                memberIds = memberIds,
                profiles = profiles,
            )
        } catch (_: AccountSwitchSnapshotSuperseded) {
            null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (throwable: Throwable) {
            appStateDebug(throwable) {
                "account-switch local snapshot failed: ${throwable.readableMessage()}"
            }
            null
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching { chatListSubscription?.close() }
                runCatching { chatsSubscription?.close() }
            }
        }
    }

    private suspend fun loadAccountSwitchMemberIds(
        accountRef: String,
        rows: List<ChatListRowFfi>,
    ): List<AppGroupMemberIdsFfi> =
        runCatchingCancellable {
            loadGroupMemberIdsPages(rows.map { it.groupIdHex }) { page ->
                marmotIo { groupMemberIdsPage(accountRef, page) }
            }
        }.onFailure { error ->
            appStateDebug(error) {
                "account-switch local member projection failed: ${error.readableMessage()}"
            }
        }.getOrDefault(emptyList())

    private fun accountSwitchMemberStage(
        rows: List<ChatListRowFfi>,
        memberIds: List<AppGroupMemberIdsFfi>,
    ): String {
        val projectedGroupIds = memberIds.mapTo(mutableSetOf()) { it.groupIdHex.lowercase(Locale.ROOT) }
        return if (rows.all { it.groupIdHex.lowercase(Locale.ROOT) in projectedGroupIds }) {
            "member-derived-local-ready"
        } else {
            "member-derived-local-deferred"
        }
    }

    private suspend fun loadAccountSwitchProfileSeeds(
        rows: List<ChatListRowFfi>,
        memberIds: List<AppGroupMemberIdsFfi>,
        activeAccountIdHex: String?,
    ): List<AccountSwitchProfileSeed> {
        val rowsByGroup = rows.associateBy { it.groupIdHex.lowercase(Locale.ROOT) }
        val peerIds =
            initialDirectPeerProfileIds(memberIds, activeAccountIdHex) { groupIdHex, memberCount ->
                rowsByGroup[groupIdHex.lowercase(Locale.ROOT)]?.let { row ->
                    GroupProjector.isDm(row.conversationKind, memberCount, row.groupName)
                } == true
            }
        return coroutineScope {
            val gate = Semaphore(PROFILE_PRESENTATION_WARM_FANOUT)
            peerIds
                .map { id -> async { gate.withPermit { loadAccountSwitchProfileSeed(id) } } }
                .awaitAll()
        }
    }

    internal fun consumeAccountSwitchLocalSnapshot(accountRef: String?) = accountSwitchHandoff.consume(accountRef)

    suspend fun setActiveAccount(
        label: String,
        deferUnreadRefresh: Boolean = false,
        shouldActivate: () -> Boolean = { true },
        onActivated: () -> Unit = {},
    ) {
        val requestGeneration = accountSwitchHandoff.beginRequest()
        val switchingAccounts = label != activeAccountRef
        if (switchingAccounts && BuildConfig.DEBUG) {
            pendingAccountSwitchTrace =
                PendingAccountSwitchTrace(
                    accountRef = label,
                    startedAtMs = SystemClock.elapsedRealtime(),
                )
        } else if (pendingAccountSwitchTrace?.accountRef != label) {
            pendingAccountSwitchTrace = null
        }
        val target = accounts.firstOrNull { it.label == label }
        if (target?.signedOut == true) {
            runCatchingCancellable {
                marmotIo { signInAccount(label) }
            }.onFailure {
                presentFailure(R.string.toast_couldnt_sign_in_account, "ACCOUNT_SIGN_IN", it)
                return
            }
            val refreshedAccounts = refreshAccountSnapshot()
            if (deferUnreadRefresh) {
                prepareStartupUnreadRefresh(refreshedAccounts)
            } else {
                refreshAccountUnreadCounts(refreshedAccounts)
            }
        }
        val activationStillWanted =
            shouldActivate() && isAccountSwitchCurrent(requestGeneration)
        val localSnapshot =
            if (switchingAccounts && activationStillWanted) {
                loadAccountSwitchLocalSnapshot(label, requestGeneration)
            } else {
                null
            }
        // A route may outlive the UI intent that requested it while a signed-out
        // account is being restored. Let request-scoped callers reject that late
        // activation without cancelling the process-lifetime sign-in work.
        if (!shouldActivate() || !isAccountSwitchCurrent(requestGeneration)) return
        // Account switch: drop in-process plaintext so account A's bytes
        // aren't reachable from account B's UI loops, but keep L2 (disk)
        // intact. The disk cache key is `mediaCacheKey(account, msg)`, so
        // switching to B can never read A's files — and switching BACK to
        // A re-hydrates L1 from L2 with a single file read instead of a
        // re-download. Sign-out (signOutActiveAccount) is what actually
        // wipes disk; switching is just a UI context flip.
        if (switchingAccounts) {
            clearInMemoryMediaCaches()
            clearCrossAccountCaches()
            clearConversationShortcutSurfaces()
            localSnapshot?.profiles?.forEach(::applyAccountSwitchProfileSeed)
            accountSwitchHandoff.publish(requestGeneration, localSnapshot)
        }
        activeAccountRef = label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, label).apply()
        reloadMediaAutoDownloadMatrix()
        // This is the local-ready boundary for account switching. UI callers can
        // dismiss/reset navigation now, while the process-lifetime mutation keeps
        // the profile/privacy/notification/push work below alive in the background.
        onActivated()
        accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        configurePrivacyRuntime()
        refreshLocalNotificationSettings()
        syncNativePushRegistrationIfEnabled()
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
            startupTiming("cached-chat-rows-ready", SystemClock.elapsedRealtime() - startupTraceStartedAtMs)
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
            startupTiming("member-derived-local-ready", SystemClock.elapsedRealtime() - startupTraceStartedAtMs)
        }
        recordPendingAccountSwitchStage(accountRef, "member-derived-local-ready", rowCount)
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

    private fun prepareStartupUnreadRefresh(accountSummaries: List<AccountSummaryFfi>) {
        pendingStartupUnreadRefresh =
            StartupUnreadRefresh(
                accounts = accountSummaries,
                accountListRevision = accountListRevision,
            )
    }

    /** Called only after the controller's authoritative SQLite snapshot drew. */
    private fun recordStartupLocalSnapshotRendered() {
        if (!startupFirstLocalFrameRecorded) {
            startupFirstLocalFrameRecorded = true
            startupTiming("first-local-frame", SystemClock.elapsedRealtime() - startupTraceStartedAtMs)
        }
        val pending = pendingStartupUnreadRefresh ?: return
        pendingStartupUnreadRefresh = null
        val revisionGuard =
            StartupUnreadRevisionGuard(pending.accountListRevision) {
                accountListRevision
            }
        mutationsScope.launch {
            traceStartupStage("unread-aggregate-refresh") {
                refreshAccountUnreadCounts(pending.accounts, stillCurrent = revisionGuard::isCurrent)
            }
            if (revisionGuard.isCurrent()) {
                startupTiming("unread-aggregate-ready", SystemClock.elapsedRealtime() - startupTraceStartedAtMs)
            }
        }
    }

    /** Records the first frame where app-owned Compose UI replaces the system splash. */
    internal fun recordStartupSystemSplashHandoff() {
        if (startupSystemSplashHandoffRecorded) return
        startupSystemSplashHandoffRecorded = true
        startupTiming("system-splash-handoff", SystemClock.elapsedRealtime() - startupTraceStartedAtMs)
    }

    private fun recordStartupRelayCatchUpReady() {
        if (startupRelayCatchUpRecorded) return
        startupRelayCatchUpRecorded = true
        startupTiming("relay-catch-up-ready", SystemClock.elapsedRealtime() - startupTraceStartedAtMs)
    }

    private suspend fun <T> traceStartupStage(
        stage: String,
        block: suspend () -> T,
    ): T {
        val startedAtMs = SystemClock.elapsedRealtime()
        return try {
            block()
        } finally {
            val nowMs = SystemClock.elapsedRealtime()
            startupTiming(
                stage = stage,
                elapsedMs = nowMs - startupTraceStartedAtMs,
                durationMs = nowMs - startedAtMs,
            )
        }
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
        mediaPlaintextCache.clear()
        mediaThumbnailCache.clear()
        bumpMediaCacheRevision()
        MediaInventory.clear()
        mediaUploadSessionEpoch.incrementAndGet()
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
        }
        // Cancel any in-flight downloads (their Deferred holds the plaintext
        // result) and drop the index so the next session starts cold.
        synchronized(inFlightDownloadsLock) {
            inFlightDownloads.values.forEach { it.cancel() }
            inFlightDownloads.clear()
        }
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
                runCatchingCancellable { java.io.File(appContext.cacheDir, dev.ipf.whitenoise.android.media.MediaCacheDirs.VOICE).deleteRecursively() }
                    .onFailure {
                        appStateDebug { "voice attachment wipe failed: ${it.readableMessage()}" }
                    }
                runCatchingCancellable { java.io.File(appContext.cacheDir, dev.ipf.whitenoise.android.media.MediaCacheDirs.VIDEO).deleteRecursively() }
                    .onFailure {
                        appStateDebug { "video attachment wipe failed: ${it.readableMessage()}" }
                    }
                runCatchingCancellable { java.io.File(appContext.cacheDir, dev.ipf.whitenoise.android.media.MediaCacheDirs.COMPOSER_PASTE).deleteRecursively() }
                    .onFailure {
                        appStateDebug { "composer paste media wipe failed: ${it.readableMessage()}" }
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
    private suspend fun clearContactPrivateDetailsForAccount(accountRef: String) {
        val normalized = accountRef.trim()
        if (normalized.isEmpty()) return
        synchronized(contactClearGuard) { contactRefsBeingCleared.add(normalized) }
        val nicknamesCleared =
            withContext(Dispatchers.IO) {
                val cleared = ContactNicknamePreferences.clearAllForAccount(preferences, normalized)
                ContactNotesPreferences.clearAllForAccount(preferences, normalized)
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

    private fun clearConversationShortcutSurfaces() {
        localNotificationPresenter.clearConversationShortcuts()
    }

    /**
     * Drop every registered account-scoped cache so account A's data isn't
     * reachable after switching to B. New caches participate by construction;
     * this boundary must never grow another hand-maintained field list.
     */
    private fun clearCrossAccountCaches() {
        assertMainThread { "clearCrossAccountCaches" }
        profileCacheEpoch.incrementAndGet()
        accountScopedCaches.clearAll()
        authoritativeMuteOverrides.clear()
        pendingMuteCommands.clear()
        GroupAvatarImageLoader.clear()
        pruneIdleGroupCommitLocks()
        profileRevision += 1
        bumpAllProfileAccountRevisions()
    }

    /**
     * Non-destructive sign-out of the active account, driven by the engine's
     * [dev.ipf.marmotkit.Marmot.signOut] (#349). The engine owns the per-step
     * teardown — account deactivation, push registration teardown, and (when
     * [deleteKeyPackages]) relay KeyPackage deletion — so this method keeps
     * only the local UI bookkeeping: media-cache wipe, active-account
     * switch/navigation, and notification settings refresh.
     *
     * An engine failure (relay unreachable, runtime error) must not strand
     * the user in a session they asked to leave: local sign-out still
     * completes and the result is [SignOutCompletion.RelayCleanupIncomplete]
     * so the caller can report that the best-effort relay cleanup did not
     * fully finish.
     * Returns null only when no account is active.
     */
    suspend fun signOutActiveAccount(deleteKeyPackages: Boolean = true): SignOutCompletion? {
        // Sign-out is a non-destructive session switch: the identity stays in
        // the device keychain and the user can switch back to it. Per-account
        // state that the user would expect to find on return (drafts, recent
        // emoji, etc.) MUST persist across this transition. The only correct
        // place to call DraftStore.clearAllForAccount is a real
        // identity-delete flow, which doesn't exist yet.
        //
        // Decrypted media is the exception: a real sign-out is the end of the
        // session, so we wipe both in-memory and disk caches. Account switch
        // (setActiveAccount) is *not* a sign-out — it keeps the L2 disk cache
        // so re-entry into the same account doesn't re-download every image.
        //
        // In-memory plaintext is dropped synchronously here; the on-disk wipe
        // is awaited in this suspend path so it isn't an orphaned background task.
        val signedOutRef = activeAccountRef ?: return null
        stopTtsForRemovedAccount(signedOutRef)
        clearInMemoryMediaCaches()
        AvatarImageLoader.clear()
        clearCrossAccountCaches()
        clearConversationShortcutSurfaces()
        // The account is signed out engine-side once this returns; no code
        // below may issue further account-scoped FFI calls for signedOutRef.
        val engineOutcome =
            runCatchingCancellable {
                marmotIo { signOut(signedOutRef, deleteKeyPackages) }
            }.onSuccess {
                // The engine deactivated the account (including its push
                // registration), so any disable retry queued by an older
                // per-step sign-out attempt is moot. Drop the local push
                // fingerprint too: server-side registration state changed
                // underneath the cache, and a stale hit would make a later
                // re-sign-in skip the re-registration it needs.
                pushTokenStore.clearPendingDisable(signedOutRef)
                nativePushSyncMutex.withLock { perAccountSyncedFingerprints.remove(signedOutRef) }
            }.onFailure {
                appStateDebug(it) { "signOut failed account=${signedOutRef.take(8)}: ${it.readableMessage()}" }
                // The engine never deactivated the account, so queue a push
                // disable for the next foreground sync. MDK's relay-side
                // KeyPackage cleanup is final for this call and is not queued.
                pushTokenStore.recordPendingDisable(signedOutRef)
            }.getOrNull()
        clearContactPrivateDetailsForAccount(signedOutRef)
        refreshAccounts()
        val outcome = signOutOutcome(accounts.map { it.label }, signedOutRef)
        val next = outcome.nextActiveRef
        if (next != null) {
            setActiveAccount(next)
        } else {
            activeAccountRef = null
            preferences.edit().remove(ACTIVE_ACCOUNT_KEY).apply()
            reloadMediaAutoDownloadMatrix()
        }
        // Signing out the last active account must leave a usable state, not a
        // MainShell with no active account. See issue #11.
        phase = outcome.phase
        wipeDecryptedMediaFromDisk()
        // Drop the cached FCM token only when no accounts remain on the
        // device — other identities still need it on multi-account switch.
        if (next == null) pushTokenStore.clear()
        refreshLocalNotificationSettings()
        return signOutCompletion(engineOutcome)
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
    @Suppress("LongMethod", "ReturnCount")
    // One cancellation-safe bracket owns wipe, editor purge, account switch, and recovery.
    suspend fun signOutAndWipeActiveAccount(): WipeOutcomeFfi? {
        val wipedRef = activeAccountRef ?: return null
        clearInMemoryMediaCaches()
        clearConversationShortcutSurfaces()
        try {
            val restartNotifications = prepareForDestructiveAccountWipe(wipedRef)
            val wipeResult =
                nativePushSyncMutex.withSerializedNativePushWipe {
                    runCatching { marmotIo { signOutAndWipe(wipedRef) } }
                        .onSuccess {
                            pushTokenStore.clearPendingDisable(wipedRef)
                            // The wipe invalidates server-side registration state for this account.
                            // withSerializedNativePushWipe already holds nativePushSyncMutex here.
                            perAccountSyncedFingerprints.remove(wipedRef)
                            pushTokenStore.clear()
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
            AvatarImageLoader.clear()
            clearCrossAccountCaches()
            stopTtsForRemovedAccount(wipedRef)
            clearContactPrivateDetailsForAccount(wipedRef)
            wipeDecryptedMediaFromDisk()
            if (!clearHiddenMessagesForAccount(wipedRef)) {
                appStateDebug { "hidden-message cleanup failed after wipe account=${wipedRef.take(8)}" }
            }
            withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    if (editorSessionStore.removeAccount(wipedRef)) {
                        editorSessionStore.sourceLeaseReferenceCounts()?.let(editorSourceStore::reconcile)
                    }
                }.onFailure {
                    appStateDebug(it) { "editor purge failed after wipe: ${it.readableMessage()}" }
                }
            }
            val refreshedAccounts = runCatchingCancellable { marmotIo { listAccounts() } }.getOrDefault(emptyList())
            accounts = refreshedAccounts
            accountListRevision += 1L
            releaseContactClearGuardForSignedInAccounts(refreshedAccounts)
            refreshAccountUnreadCounts(refreshedAccounts)
            val next = refreshedAccounts.firstOrNull()?.label
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
            refreshLocalNotificationSettings()
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
                RelayUrlValidationResult.UnsupportedHost -> R.string.error_external_relay_not_supported
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

    private suspend fun publishAccountRelays(
        account: String,
        kind: RelayListKind,
        plan: RelayListEditPlan,
    ): AccountRelayListsFfi? {
        val next = plan.relays
        val validationError = relayPublishValidationError(next)
        return if (validationError != null) {
            when (validationError) {
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
                        AppText.Plain(RELAY_HOSTS_UNAVAILABLE_MESSAGE),
                    )
            }
            null
        } else {
            runCatchingCancellable {
                marmotIo {
                    when (kind) {
                        RelayListKind.Nip65 -> setAccountNip65Relays(account, next, MarmotClient.bootstrapRelays)
                        RelayListKind.Inbox -> setAccountInboxRelays(account, next, MarmotClient.bootstrapRelays)
                    }
                }
            }.onSuccess {
                presentTransient(R.string.toast_relay_list_updated)
            }.onFailure {
                presentFailure(R.string.toast_relay_update_failed, "RELAY_LIST_UPDATE", it)
            }.getOrNull()
        }
    }

    private suspend fun loadAccountRelayLists(account: String): AccountRelayListsFfi? =
        runCatchingCancellable { marmotIo { accountRelayLists(account) } }.getOrNull()

    private suspend fun relayPublishValidationError(relays: List<String>): RelayPublishValidationError? =
        when {
            relays.isEmpty() || relays.any { !isAcceptableRelayUrl(it) } -> RelayPublishValidationError.Invalid
            else ->
                when (relayUrlsResolveTimeCheckResult(relays)) {
                    RelayResolveTimeCheckResult.Passed -> null
                    RelayResolveTimeCheckResult.Blocked -> RelayPublishValidationError.Blocked
                    RelayResolveTimeCheckResult.Unavailable -> RelayPublishValidationError.Unavailable
                }
        }

    private fun AccountRelayListsFfi.relaysFor(kind: RelayListKind): List<String> =
        when (kind) {
            RelayListKind.Nip65 -> nip65.relays
            RelayListKind.Inbox -> inbox.relays
        }

    fun bootstrapRelayCount(): Int = MarmotClient.bootstrapRelays.size

    suspend fun fetchKeyPackages(refreshFromNetwork: Boolean = false): List<AccountKeyPackageFfi> {
        val account = activeAccountRef ?: return emptyList()
        return runCatching {
            val bootstrapRelays = if (refreshFromNetwork) MarmotClient.bootstrapRelays else emptyList()
            marmotIo { accountKeyPackages(account, bootstrapRelays) }
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_load_key_packages, "KEY_PACKAGE_LOAD", it)
            emptyList()
        }
    }

    suspend fun deleteKeyPackage(
        eventIdHex: String,
        sourceRelays: List<String>,
    ): Boolean {
        val account = activeAccountRef ?: return false
        val relays = normalizeRelayUrls(sourceRelays)
        if (relays.isEmpty()) {
            present(
                R.string.toast_couldnt_delete_key_package,
                R.string.error_remove_invalid_relay_urls_first,
                copyable = true,
            )
            return false
        }
        when (relayUrlsResolveTimeCheckResult(relays)) {
            RelayResolveTimeCheckResult.Passed -> Unit
            RelayResolveTimeCheckResult.Blocked -> {
                present(
                    R.string.toast_couldnt_delete_key_package,
                    R.string.error_remove_invalid_relay_urls_first,
                    copyable = true,
                )
                return false
            }
            RelayResolveTimeCheckResult.Unavailable -> {
                present(
                    R.string.toast_couldnt_delete_key_package,
                    AppText.Plain(RELAY_HOSTS_UNAVAILABLE_MESSAGE),
                    copyable = true,
                )
                return false
            }
        }
        return runCatching {
            marmotIo { deleteAccountKeyPackage(account, eventIdHex, relays) }
            presentTransient(R.string.toast_key_package_deleted)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_delete_key_package, "KEY_PACKAGE_DELETE", it)
            false
        }
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

    fun updateDeveloperMode(enabled: Boolean) {
        developerMode = enabled
        preferences.edit().putBoolean(DEVELOPER_MODE_KEY, enabled).apply()
    }

    fun updateStreamingDebugMode(enabled: Boolean) {
        streamingDebugMode = enabled
        preferences.edit().putBoolean(STREAMING_DEBUG_MODE_KEY, enabled).apply()
    }

    fun updateForceIncognitoKeyboard(enabled: Boolean) {
        forceIncognitoKeyboard = enabled
        preferences.edit().putBoolean(FORCE_INCOGNITO_KEYBOARD_KEY, enabled).apply()
    }

    fun updateAllowChatScreenshotsInChats(enabled: Boolean) {
        allowChatScreenshotsInChats = enabled
        ChatScreenshotPreferences.writeAllowChatScreenshots(preferences, enabled)
        onAllowChatScreenshotsChanged?.invoke(enabled)
    }

    fun refreshAppLockCredentialAvailability() {
        appLockCredentialAvailable = isAppLockCredentialAvailable(appContext)
        if (!appLockCredentialAvailable) {
            appLockScreenVisible = false
            appUnlockError = null
            resumePendingInviteNotificationIdentityRefreshes()
        }
    }

    fun updateRequireAppUnlock(enabled: Boolean) {
        refreshAppLockCredentialAvailability()
        if (enabled && !appLockCredentialAvailable) {
            requireAppUnlock = false
            preferences.edit().putBoolean(REQUIRE_APP_UNLOCK_KEY, false).apply()
            present(R.string.toast_app_lock_screen_lock_required)
            return
        }
        requireAppUnlock = enabled
        preferences.edit().putBoolean(REQUIRE_APP_UNLOCK_KEY, enabled).apply()
        if (enabled) {
            requestAppUnlock()
        } else {
            appLockScreenVisible = false
            appUnlockError = null
            resumePendingInviteNotificationIdentityRefreshes()
        }
    }

    fun updateAppLockDelay(delay: AppLockDelay) {
        appLockDelay = delay
        preferences.edit().putString(APP_LOCK_DELAY_KEY, delay.preferenceValue).apply()
    }

    fun requestAppUnlock() {
        refreshAppLockCredentialAvailability()
        if (!requireAppUnlock || !appLockCredentialAvailable) return
        appLockScreenVisible = true
        appUnlockError = null
        appUnlockPromptRequestId += 1
    }

    fun markAppUnlockSucceeded(nowMillis: Long = System.currentTimeMillis()) {
        val normalizedNow = nowMillis.coerceAtLeast(0L)
        lastAppUnlockAtMillis = normalizedNow
        AppLockPreferences.writeLastUnlockedAtMillis(appContext, normalizedNow)
        appLockScreenVisible = false
        appUnlockError = null
        resumePendingInviteNotificationIdentityRefreshes()
        dismissVisibleConversationNotifications()
    }

    fun markAppUnlockFailed(message: AppText = AppText.Resource(R.string.app_lock_auth_cancelled)) {
        if (!appLockScreenVisible) return
        appUnlockError = message
    }

    // True while a foreground lock decision waits for the off-main unlock
    // timestamp: the lock scrim shows (UI secured) but the biometric prompt
    // is deferred until the REAL value decides — a 0L placeholder would read
    // the grace period as expired and over-prompt on cold starts within it.
    var appUnlockEvaluationPending by mutableStateOf(false)
        private set

    fun maybeShowAppLockForForeground(nowMillis: Long = System.currentTimeMillis()) {
        refreshAppLockCredentialAvailability()
        // Short-circuit BEFORE any timestamp read so app-lock-disabled users
        // never pay for it on foreground transitions.
        if (!requireAppUnlock || !appLockCredentialAvailable) return
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
        appLockScreenVisible = true
        mutationsScope.launch {
            val loaded = withContext(Dispatchers.IO) { AppLockPreferences.readLastUnlockedAtMillis(appContext) }
            if (lastAppUnlockAtMillisBacking == null) lastAppUnlockAtMillisBacking = loaded
            appUnlockEvaluationPending = false
            // Re-read the clock AFTER the IO hop: deciding with the entry
            // time could dismiss the lock even though the grace period
            // expired while the secure store was loading.
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
                resumePendingInviteNotificationIdentityRefreshes()
            }
        }
    }

    fun shouldSecureAppLockWindowWhileBackgrounded(): Boolean = shouldSecureAppLockWindowWhileBackgrounded(requireUnlock = requireAppUnlock)

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
        AppLockPreferences.writeLastUnlockedAtMillis(appContext, normalizedNow)
    }

    suspend fun refreshSecurityPrivacySettings() {
        relayTelemetrySettings = runCatchingCancellable { marmotIo { relayTelemetrySettings() } }.getOrNull()
        auditLogSettingsMutex.withLock {
            auditLogSettings = runCatchingCancellable { marmotIo { auditLogSettings() } }.getOrNull()
            reconcileRedactionWithEngineAuditModeLocked()
        }
    }

    // Existing installs may have FULL_DATA before the one-time safe-default
    // migration ran. If the engine update fails, leave the marker pending so
    // the next refresh retries instead of silently opting out.
    private suspend fun reconcileRedactionWithEngineAuditModeLocked() {
        val settings = auditLogSettings ?: return
        val migrated =
            runCatchingCancellable {
                executeAuditLogSettingsMigration(
                    action = AuditLogSettingsPolicy.evaluateMigration(preferences, settings),
                    persist = { updated -> marmotIo { setAuditLogSettings(updated) } },
                    complete = { AuditLogSettingsPolicy.completeMigration(preferences) },
                )
            }.getOrNull()
        if (migrated != null) {
            auditLogSettings = migrated
        }
    }

    suspend fun setTelemetryEnabled(enabled: Boolean): Boolean =
        runCatching {
            val current = relayTelemetrySettings ?: marmotIo { relayTelemetrySettings() }
            val updated =
                marmotIo {
                    setRelayTelemetrySettings(
                        RelayTelemetrySettingsFfi(
                            exportEnabled = enabled,
                            exportIntervalSeconds = current.exportIntervalSeconds,
                        ),
                    )
                }
            relayTelemetrySettings = updated
            presentTransient(R.string.toast_security_privacy_updated)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_update_security_privacy, "SECURITY_PRIVACY_UPDATE", it)
            false
        }

    suspend fun setAuditLogsEnabled(enabled: Boolean): Boolean =
        runCatching {
            // setAuditLogSettings now applies the switch to every live session
            // in place via a recorder hot-swap (enable → live recorder,
            // disable → flush + close); no session reopen or runtime restart
            // required on the host side.
            // The data mode follows the engine-backed audit settings, so the
            // choice survives toggling audit logging off and on.
            updateAuditLogSettingsSerialized(
                mutex = auditLogSettingsMutex,
                cachedSettings = { auditLogSettings },
                storeCachedSettings = { auditLogSettings = it },
                loadFromEngine = { marmotIo { auditLogSettings() } },
                transform = { AuditLogSettingsPolicy.settingsWithEnabled(it, enabled) },
                persistToEngine = { settings -> marmotIo { setAuditLogSettings(settings) } },
            )
            presentTransient(R.string.toast_security_privacy_updated)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_update_security_privacy, "SECURITY_PRIVACY_UPDATE", it)
            false
        }

    suspend fun setRedactSensitiveAuditData(redact: Boolean): Boolean =
        runCatching {
            updateAuditLogSettingsSerialized(
                mutex = auditLogSettingsMutex,
                cachedSettings = { auditLogSettings },
                storeCachedSettings = { auditLogSettings = it },
                loadFromEngine = { marmotIo { auditLogSettings() } },
                transform = { AuditLogSettingsPolicy.settingsWithRedaction(it, redact) },
                persistToEngine = { settings -> marmotIo { setAuditLogSettings(settings) } },
            )
            presentTransient(R.string.toast_security_privacy_updated)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            presentFailure(R.string.toast_couldnt_update_security_privacy, "SECURITY_PRIVACY_UPDATE", it)
            false
        }

    /**
     * Delete every local audit log file. Each delete is best-effort; the
     * runtime hot-swaps any live recorder so logging keeps running on a
     * fresh file when audit logging is currently on. Returns true if at
     * least one file was successfully removed (or rotated).
     */
    suspend fun deleteAuditLogs(): Boolean {
        val files =
            runCatching { marmotIo { auditLogFiles() } }
                .getOrElse {
                    if (it is CancellationException) throw it
                    presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
                    return false
                }
        if (files.isEmpty()) {
            presentTransient(R.string.toast_no_audit_logs_to_delete)
            return false
        }
        var anyDeleted = false
        var firstFailure: Throwable? = null
        for (file in files) {
            val outcome =
                runCatchingCancellable { marmotIo { deleteAuditLogFile(file.path) } }
                    .onFailure {
                        if (firstFailure == null) firstFailure = it
                        appStateDebug { "deleteAuditLogFile failed: ${it.readableMessage()}" }
                    }.getOrNull() ?: continue
            anyDeleted = true
            appStateDebug { "audit log deleted still_recording=${outcome.stillRecording}" }
        }
        if (anyDeleted) {
            presentTransient(R.string.toast_audit_logs_deleted)
        } else {
            firstFailure?.let {
                presentFailure(R.string.toast_couldnt_delete_audit_logs, "AUDIT_LOG_DELETE", it)
            } ?: present(R.string.toast_couldnt_delete_audit_logs)
        }
        return anyDeleted
    }

    fun updateThemeMode(mode: AppThemeMode) {
        themeMode = mode
        preferences.edit().putString(THEME_MODE_KEY, mode.preferenceValue).apply()
    }

    fun updateFontScale(scale: AppFontScale) {
        fontScale = scale
        preferences.edit().putString(FONT_SCALE_KEY, scale.preferenceValue).apply()
    }

    fun updateAppFont(font: AppFont) {
        appFont = font
        preferences.edit().putString(APP_FONT_KEY, font.preferenceValue).apply()
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

    fun setConversationMuted(
        groupIdHex: String,
        muted: Boolean,
    ) {
        val accountRef = activeAccountRef ?: return
        if (muted) submitMuteCommand(accountRef, groupIdHex, null) else submitUnmuteCommand(accountRef, groupIdHex)
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
    private fun submitMuteCommand(
        accountRef: String,
        groupIdHex: String,
        mutedUntilMs: Long?,
    ) {
        val key = ChatMutePreferences.compositeKey(accountRef, groupIdHex)
        pendingMuteCommands[key] = (pendingMuteCommands[key] ?: 0) + 1
        mutationsScope.launch {
            chatMuteRepository
                .setMuted(accountRef, groupIdHex, mutedUntilMs)
                .onSuccess { if (activeAccountRef == accountRef) authoritativeMuteOverrides[key] = it }
                .onFailure { presentFailure(R.string.toast_couldnt_update_notifications, "CHAT_MUTE_UPDATE", it) }
            finishMuteCommand(key)
        }
    }

    private fun submitUnmuteCommand(
        accountRef: String,
        groupIdHex: String,
    ) {
        val key = ChatMutePreferences.compositeKey(accountRef, groupIdHex)
        pendingMuteCommands[key] = (pendingMuteCommands[key] ?: 0) + 1
        mutationsScope.launch {
            chatMuteRepository
                .clearMuted(accountRef, groupIdHex)
                .onSuccess { if (activeAccountRef == accountRef) authoritativeMuteOverrides[key] = it }
                .onFailure { presentFailure(R.string.toast_couldnt_update_notifications, "CHAT_MUTE_UPDATE", it) }
            finishMuteCommand(key)
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

    fun ttsEngineChoice(): TtsEngineChoice = ttsResolution?.engineChoice() ?: TtsEngineChoice(null, emptyList())

    fun resolvedTtsEnginePackage(): String? =
        ttsEngineResolver.preferredEnginePackage(
            engines = ttsEngineChoice().engines,
            defaultPackage = ttsEngineChoice().defaultPackage,
            selectedOverride = ttsEnginePreferences.selectedEngine(),
        )

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

    private suspend fun selectTtsEngineLocked(enginePackage: String) {
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

    suspend fun hideMessageForMe(
        accountRef: String?,
        groupIdHex: String,
        messageIdHex: String,
    ): Boolean =
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
        preferences.edit().putString(key, updated.toPreference()).apply()
    }

    fun updateEnterKeyBehavior(behavior: EnterKeyBehavior) {
        enterKeyBehavior = behavior
        preferences.edit().putString(ENTER_KEY_BEHAVIOR_KEY, behavior.preferenceValue).apply()
    }

    /**
     * Update the outgoing-media quality ceiling (image downscale/JPEG quality
     * and voice-note bitrate). Persists immediately so the selection survives
     * process death; the next send reads [mediaQuality] directly.
     */
    fun updateMediaQuality(quality: MediaQuality) {
        mediaQuality = quality
        preferences.edit().putString(MEDIA_QUALITY_KEY, quality.preferenceValue).apply()
    }

    /**
     * Whether an incoming attachment of [type] should be fetched/decrypted
     * automatically given the active account's matrix and every network the
     * live connection currently matches (most-restrictive rule, issue #407).
     */
    fun shouldAutoDownloadMedia(type: MediaAutoDownloadType): Boolean = mediaAutoDownloadMatrix.shouldAutoDownload(type, activeNetworkTypes())

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

    // Callback-maintained mirror of the default network's state, volatile so
    // composition-time reads see the connectivity thread's latest write.
    @Volatile
    private var activeNetworkTypesSnapshot: Set<MediaAutoDownloadNetwork> = emptySet()

    @Volatile
    private var hasActiveNetworkSnapshot = false

    private val validatedInternetNetworks = ValidatedInternetNetworkTracker()

    /**
     * Reactive mirror of the two signals the chat-list connectivity banner
     * consumes: device network presence (state 1 of the banner) and whether
     * the engine's relay pool reports at least one connected relay. The pool
     * counts come from [refreshRelayConnectivity] polls — the notification
     * subscription cannot stand in for connectivity because it rides an
     * in-process event bus and stays open with every relay down.
     */
    data class ConnectivitySignals(
        val hasNetwork: Boolean = false,
        val relaysConnected: Boolean = true,
    )

    private val _connectivitySignals = MutableStateFlow(ConnectivitySignals())
    val connectivitySignals: StateFlow<ConnectivitySignals> = _connectivitySignals.asStateFlow()

    // Bumped on every hasNetwork transition (callback or seed-observed) so a
    // relay-health snapshot that was in flight across the transition can be
    // recognized and discarded in [refreshRelayConnectivity].
    private val connectivityNetworkGeneration = AtomicLong(0)

    private fun updateConnectivitySignals(
        hasNetwork: Boolean? = null,
        relaysConnected: Boolean? = null,
    ) {
        _connectivitySignals.update { current ->
            val nextHasNetwork = hasNetwork ?: current.hasNetwork
            current.copy(
                hasNetwork = nextHasNetwork,
                // Structural invariant: no active network means no connected
                // relays, whatever an optimistic seed default or a stale
                // health snapshot claims. Every writer goes through this clamp.
                relaysConnected =
                    relaysConnectedOnNetworkChange(
                        isOnline = nextHasNetwork,
                        cached = relaysConnected ?: current.relaysConnected,
                    ),
            )
        }
    }

    /**
     * Refresh [connectivitySignals] from the engine's relay-health snapshot.
     * No-op while backgrounded or offline; a failed read keeps the previous
     * value rather than flashing a guess, and a snapshot that straddled a
     * network transition is discarded via [connectivityNetworkGeneration].
     * The banner only changes state on evidence from the current network.
     */
    suspend fun refreshRelayConnectivity() {
        // Offline needs no sample: the write clamp already pinned the signal
        // false, and pool counts read while offline are stale by definition.
        if (!appInForeground || !_connectivitySignals.value.hasNetwork) return
        val generation = connectivityNetworkGeneration.get()
        val health = runCatchingCancellable { marmotIo { relayHealth() } }.getOrNull()
        // A snapshot that straddled a network transition describes the wrong
        // network; drop it — the next poll is ≤2s out.
        if (health == null || connectivityNetworkGeneration.get() != generation) return
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
            hasActiveNetworkSnapshot = network != null
            updateConnectivitySignals(hasNetwork = network != null)
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
                        noteActiveNetworkSnapshot(isOnline = true)
                    }

                    override fun onCapabilitiesChanged(
                        network: android.net.Network,
                        networkCapabilities: android.net.NetworkCapabilities,
                    ) {
                        noteActiveNetworkSnapshot(
                            isOnline = true,
                            networkTypes = networkTypesFor(networkCapabilities),
                        )
                    }

                    override fun onLost(network: android.net.Network) {
                        noteActiveNetworkSnapshot(isOnline = false)
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
                }

                override fun onLost(network: android.net.Network) {
                    validatedInternetNetworks.remove(network.networkHandle)
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
        val wasOnline = hasActiveNetworkSnapshot
        if (wasOnline != isOnline) connectivityNetworkGeneration.incrementAndGet()
        updateConnectivitySignals(hasNetwork = isOnline)
        if (!isOnline) {
            hasActiveNetworkSnapshot = false
            activeNetworkTypesSnapshot = emptySet()
            return
        }
        hasActiveNetworkSnapshot = true
        activeNetworkTypesSnapshot = networkTypes ?: emptySet()
        if (shouldReconnectNotificationsOnNetworkRestore(wasOnline, isOnline = true)) {
            scheduleNotificationReconnectOnNetworkRestore()
        }
        schedulePendingPushWakeCatchUpDrain()
    }

    private fun scheduleNotificationReconnectOnNetworkRestore() {
        if (networkNotificationRecoverySuppressed) return
        notificationReconnectJob.startIfInactive {
            val reconnectJob =
                notificationScope.launch {
                    // Re-check under the launched coroutine: a connectivity
                    // callback preempted between the outer check and this
                    // launch would otherwise survive a wipe's cancel sweep
                    // (the wipe sets the flag before sweeping the slots).
                    if (networkNotificationRecoverySuppressed) return@launch
                    runNotificationReconnectOnNetworkRestore(
                        ensureNotificationReceiverActive = ::ensureNotificationReceiverForNetworkReconnect,
                        catchUpAccounts = {
                            val pendingGeneration = pushTokenStore.pendingPushWakeCatchUpGeneration()
                            if (catchUpAccountsBestEffort()) {
                                clearPendingPushWakeCatchUpIfObserved(pendingGeneration)
                            }
                        },
                    )
                }
            reconnectJob.invokeOnCompletion { cause ->
                if (cause == null) schedulePendingPushWakeCatchUpDrain()
            }
            reconnectJob
        }
    }

    private fun schedulePendingPushWakeCatchUpDrain() {
        if (networkNotificationRecoverySuppressed || !pushTokenStore.pushWakeCatchUpPending()) return
        if (notificationReconnectJob.isActive()) return
        pushWakeCatchUpDrainJob.startIfInactive {
            notificationScope.launch { ensureNotificationRuntimeStarted() }
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
        if (stored != null) return MediaAutoDownloadMatrix.fromPreference(stored)
        // Only consume the legacy global key once a real account is bound, so
        // the migrated value lands on the user's account rather than the
        // transient pre-bootstrap "default" bucket.
        val seeded = if (account != null) migratedDefaultMatrix() else MediaAutoDownloadMatrix.DEFAULT
        // Don't seed the shared "default" bucket when the account is unresolved;
        // keep DEFAULT in memory and persist once a real account key exists.
        if (key != null) preferences.edit().putString(key, seeded.toPreference()).apply()
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
        preferences.edit().putString(LANGUAGE_TAG_KEY, normalized).apply()
        applyLanguageTag(normalized)
    }

    fun setAppInForeground(foreground: Boolean) {
        // Backgrounding flips off suppression without forgetting the still-open
        // chat; returning to the same Activity then resumes foreground
        // suppression without waiting for an unchanged Compose effect to re-run.
        // Task removal is the destructive lifecycle edge that clears the open
        // chat entirely, via onTaskRemoved(), so a foreground-service-kept
        // process cannot keep silencing that chat after the UI is gone (#821).
        updateNotificationSuppression(if (foreground) suppression.onForeground() else suppression.onBackground())
        AppUpdateForegroundState.isForeground = foreground
        if (foreground) {
            maybeShowAppLockForForeground()
            dismissVisibleConversationNotifications()
        } else {
            recordAppLockBackgrounded()
            mutationsScope.launch { draftWriter.flush() }
            // Read-aloud is foreground-only in v1 (no mediaPlayback FGS):
            // spoken private messages must not continue after an app switch.
            stopSpeaking()
        }
        if (foreground) {
            refreshLocalNotificationPermission()
            notificationScope.launch { catchUpAfterForegroundActivation() }
        }
        if (foreground && backgroundConnectionEnabled) startBackgroundConnectionService()
        if (foreground) notificationScope.launch { syncNativePushRegistrationIfEnabled() }
        if (!foreground) notificationScope.launch { refreshAppUpdateIfStale(notifyIfNewer = true) }
        if (foreground) notificationScope.launch { refreshAppUpdateIfStale(notifyIfNewer = false) }
        if (foreground) refreshAppSelfUpdateInstallPermission()
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

    /**
     * Reset the in-memory foreground/visible-conversation suppression state when
     * the app's task is swiped away from recents. A running foreground service
     * keeps the process (and this state) alive across task removal, and
     * Activity `onStop` is not guaranteed on that path, so without this an
     * open chat's suppression could persist after the UI is gone (issue #821).
     */
    fun onTaskRemoved() {
        updateNotificationSuppression(suppression.onTaskRemoved())
        mutationsScope.launch { draftWriter.flush() }
    }

    private fun applyActiveConversationTransition(groupIdHex: String?) {
        // The chat screen always runs under the active account, so capture it
        // when opening; closing (null) clears both halves via the transition.
        updateNotificationSuppression(
            suppression.onActiveConversation(groupIdHex, accountRef = if (groupIdHex != null) activeAccountRef else null),
        )
        if (groupIdHex != null) {
            synchronized(conversationStateLock) {
                promoteConversationState(activeConversationAccountRef, groupIdHex)
            }
        }
    }

    suspend fun setActiveConversation(groupIdHex: String?) {
        applyActiveConversationTransition(groupIdHex)
        if (groupIdHex != null) {
            // Clear the conversation's tray cards on the first open, regardless
            // of whether mark-read later advances the read watermark. The
            // mark-read-driven dismissal in the conversation controllers stays
            // as-is; this is additive defense against the cold-open race where
            // the read anchor isn't ready yet or the mark-read is deduped, so a
            // reaction/message notification could otherwise survive until the
            // second open (issue #803).
            dismissConversationNotificationsOnOpen(activeConversationAccountRef, groupIdHex, ::dismissConversationNotifications)
        }
        appStateDebug {
            "active conversation=${groupIdHex?.take(8) ?: "<none>"} account=${activeConversationAccountRef?.take(8) ?: "<none>"}"
        }
    }

    fun clearActiveConversation() {
        applyActiveConversationTransition(null)
        appStateDebug {
            "active conversation=<none> account=${activeConversationAccountRef?.take(8) ?: "<none>"}"
        }
    }

    suspend fun dismissConversationNotifications(
        accountRef: String,
        groupIdHex: String,
    ) {
        runCatchingCancellable {
            localNotificationPresenter.dismissConversationMessages(accountRef, groupIdHex)
        }.onFailure {
            appStateDebug { "notification dismiss failed group=${groupIdHex.take(8)}" }
        }
    }

    /**
     * Background disappearing-message sweep across every signed-in account
     * (#745). The in-conversation sweep ([ConversationController.start]) only
     * runs while a chat is open; this is the closed-conversation counterpart,
     * driven on a coarse cadence by [DisappearingMessageSweepWorker] so a
     * message that expires while its conversation is closed is still pruned,
     * its decrypted L2 media still secure-deleted, and a stale tray card still
     * cleared — without waiting for the user to reopen the chat.
     *
     * The sweep core is engine-owned: one `sweepExpiredRetention` call per
     * account covers every retention-enabled group with the same clock-skew,
     * unread-anchor, and scan-cap deferrals Android used to gate app-side,
     * run atomically with the prune on the account's serialized command
     * worker. Android keeps only what it owns per pruned group: tray-card
     * dismissal (#333) and decrypted media-cache eviction (#334).
     *
     * Best-effort and per-account isolated: a failure on one account is
     * logged (cancellation re-thrown) and the sweep moves on, so one bad
     * account can't starve the rest. Bootstraps the runtime first so the
     * worker can run after a process death with no UI attached.
     */
    suspend fun sweepExpiredDisappearingMessages() {
        ensureNotificationRuntimeStarted()
        if (marmotRuntime == null) return
        val signedInAccounts = accounts.filter { it.isSignedInSigningAccount() }
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

    suspend fun refreshAppUpdate(
        force: Boolean = false,
        notifyIfNewer: Boolean = false,
    ): AppUpdateInfo {
        if (!force && !appUpdateRepository.shouldCheck()) {
            appUpdateInfo = appUpdateRepository.loadInfo()
            maybeShowAppUpdateNotification(appUpdateInfo, notifyIfNewer)
            return appUpdateInfo
        }
        val info =
            runCatchingCancellable { appUpdateRepository.refresh() }
                .onFailure {
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
        if (!shouldStartInAppSelfUpdate(BuildConfig.SELF_UPDATE_ENABLED)) return
        val latest = appUpdateInfo.latestVersion
        if (latest != null) {
            startAppSelfUpdate(latest)
            return
        }
        openZapstoreListing(context)
    }

    fun startAppSelfUpdate(version: String? = appUpdateInfo.latestVersion) {
        val targetVersion = version ?: return
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

    suspend fun refreshLocalNotificationSettings() {
        val account = activeAccountRef
        localNotificationSettings =
            if (account == null) {
                null
            } else {
                runCatchingCancellable {
                    marmotIo { notificationSettings(account) }
                }.getOrNull()
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

                val summary = marmotIo { sendText(account, group, body) }
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
                NotificationReplySendOutcome.Sent
            }
        }.onFailure {
            appStateDebug(it) { "notification reply failed for group=${group.take(8)}: ${it.readableMessage()}" }
        }.getOrElse(::notificationReplySendFailureOutcome)
    }

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
                marmotIo { reactToMessage(accountRef, groupIdHex, messageIdHex, emoji) }
                NotificationReactionSendOutcome.Sent
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
            applyChatListRowFromMarkRead(account, row)
            true
        }.onFailure {
            Log.w(
                "DMAppState",
                "notification mark read failed for group=${group.take(8)} message=${message.take(8)}",
                it,
            )
        }.getOrDefault(false)
    }

    suspend fun setLocalNotificationsEnabled(enabled: Boolean): Boolean {
        val account = activeAccountRef ?: return false
        refreshLocalNotificationPermission()
        if (enabled && !localNotificationPermissionGranted) {
            present(R.string.toast_notification_permission_needed)
            return false
        }
        return runCatching {
            val settings = marmotIo { setLocalNotificationsEnabled(account, enabled) }
            localNotificationSettings = settings
            if (!enabled && backgroundConnectionEnabled) {
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

    /**
     * Whether real push notifications can run on this device + build. True
     * only if (1) the build is configured with a MIP-05 push server pubkey,
     * (2) Google Play Services is available on the device, AND (3) the
     * Firebase app has actually been initialized at process start. Without
     * (3), `FirebaseMessaging.getInstance()` throws `IllegalStateException`
     * deep in the FCM SDK; the gate keeps that exception out of the
     * foreground / account-switch / token-rotation paths that would
     * otherwise crash the process. False on F-Droid/Zapstore installs
     * lacking GMS, on builds without
     * [BuildConfig.WHITENOISE_PUSH_SERVER_PUBKEY_HEX], on emulators without
     * Play Services, and on builds where Firebase isn't initialized.
     */
    fun isNativePushAvailable(config: PushServerConfig? = PushServerConfig.current()): Boolean {
        if (config == null) return false
        val status = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(appContext)
        if (status != ConnectionResult.SUCCESS) return false
        return FirebaseApp.getApps(appContext).isNotEmpty()
    }

    /**
     * Persist the FCM token and trigger a re-sync against the runtime. Called
     * by [dev.ipf.whitenoise.android.notifications.MarmotFirebaseMessagingService] on
     * every Firebase token rotation. The sync coroutine no-ops if any
     * precondition is missing, so the call is safe at any point in the app
     * lifecycle.
     */
    fun onPushTokenRotated(token: String) {
        pushTokenStore.setToken(token)
        notificationScope.launch { syncNativePushRegistrationIfEnabled() }
    }

    /**
     * Push the current FCM token to the runtime for every signed-in account
     * that has `nativePushEnabled = true`. Multi-account devices keep a
     * working registration on every account, not just the active one — a
     * push for account A still wakes the device when account B is in
     * focus. Idempotent per account: a successful sync caches the
     * (token, server, relay) fingerprint and skips on the next call until
     * something changes. Returns true only when every account that currently
     * requires native push is registered (or does not require registration).
     */
    suspend fun syncNativePushRegistrationIfEnabled(): Boolean = nativePushSyncMutex.withLock { syncNativePushRegistrationIfEnabledLocked() }

    private suspend fun hasConfirmedNativePushRegistration(account: String): Boolean =
        nativePushSyncMutex.withLock { perAccountSyncedFingerprints.containsKey(account) }

    @Suppress("ReturnCount")
    private suspend fun syncNativePushRegistrationIfEnabledLocked(): Boolean {
        // Drain before resolving the push-server config so a clear that
        // failed earlier still retries even if the config is later blanked
        // or GMS is uninstalled — otherwise a stale server-side registration
        // would keep wrapping wake events for a device that can no longer
        // receive them. Only the upsert path is gated on config + GMS.
        drainPendingPushClears()
        drainPendingPushDisables()
        val config = PushServerConfig.current() ?: return false
        if (!isNativePushAvailable(config)) return false
        var accountRefs = accounts.map { it.label }
        if (accountRefs.isEmpty()) {
            // Only clear the durable #755 retry flag when the account list is
            // authoritative. setAppInForeground() can trigger this before
            // bootstrap/refreshAccounts() has loaded accounts; clearing then
            // would strand a signed-in device on a stale push token.
            if (marmotRuntime == null) return false
            refreshAccounts()
            accountRefs = accounts.map { it.label }
            if (accountRefs.isEmpty()) {
                pushTokenStore.clearPendingNativePushRegistrationSync()
                return true
            }
        }
        val token = pushTokenStore.lastToken() ?: fetchFcmTokenOrNull() ?: return false
        for (account in accountRefs) {
            val synced = syncPushForAccount(account, config, token)
            if (!synced) return false
        }
        pushTokenStore.clearPendingNativePushRegistrationSync()
        return true
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
        for (account in pushTokenStore.pendingDisables()) {
            val disabled =
                runCatchingCancellable { marmotIo { setNativePushEnabled(account, false) } }
                    .onFailure {
                        appStateDebug { "pending setNativePushEnabled(false) retry failed: ${it.readableMessage()}" }
                    }.isSuccess
            if (disabled) {
                pushTokenStore.clearPendingDisable(account)
                appStateDebug { "pending native-push disable drained account=${account.take(8)}" }
            }
        }
    }

    private suspend fun syncPushForAccount(
        account: String,
        config: PushServerConfig,
        token: String,
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
                settingsAfter.nativePushEnabled -> {
                    perAccountSyncedFingerprints[account] = fingerprint
                    appStateDebug { "push registration synced account=${account.take(8)}" }
                    true
                }
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
        val account =
            activeAccountRef ?: run {
                present(R.string.toast_no_active_account)
                return false
            }
        if (enabled && !isNativePushAvailable()) return false
        return runCatching {
            val settings = marmotIo { setNativePushEnabled(account, enabled) }
            localNotificationSettings = settings
            if (settings.nativePushEnabled != enabled) return@runCatching false
            if (enabled) {
                // Explicit enable beats a queued sign-out disable for this account.
                pushTokenStore.clearPendingDisable(account)
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
        runCatchingCancellable { marmotIo { clearPushRegistration(account) } }
            .onSuccess { outcome ->
                // PENDING means MarmotKit durably retained the clear and will
                // retry group sharing; it is a successful local clear, not a
                // reason to restore or roll back the registration.
                logPushRegistrationShareOutcome("clear", account, outcome)
                pushTokenStore.clearPending(account)
            }.onFailure {
                pushTokenStore.recordPendingClear(account)
                appStateDebug { "clearPushRegistration failed (queued for retry): ${it.readableMessage()}" }
            }
    }

    private fun logPushRegistrationShareOutcome(
        operation: String,
        account: String,
        outcome: PushRegistrationShareOutcomeFfi,
    ) {
        val state = pushRegistrationSharingState(outcome)
        appStateDebug {
            "push registration $operation sharing=$state account=${account.take(8)} " +
                "attempted=${outcome.attemptedGroups} succeeded=${outcome.succeededGroups} " +
                "failed=${outcome.failedGroups} pending=${outcome.pendingGroups}"
        }
    }

    private suspend fun fetchFcmTokenOrNull(): String? {
        if (!isNativePushAvailable()) return null
        val token =
            runCatchingCancellable {
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
            }.onFailure {
                appStateDebug { "FCM token fetch failed: ${it.readableMessage()}" }
            }.getOrNull()
        if (!token.isNullOrBlank()) pushTokenStore.setToken(token)
        return token?.takeIf { it.isNotBlank() }
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

    suspend fun enableDefaultNotificationsIfReady(): Boolean {
        if (defaultNotificationsEnableAttempted) return false
        val account = activeAccountRef ?: return false
        refreshLocalNotificationPermission()
        if (!localNotificationPermissionGranted) return false
        markDefaultNotificationsEnableAttempted()
        val settings = marmotIo { setLocalNotificationsEnabled(account, true) }
        localNotificationSettings = settings
        if (!settings.localNotificationsEnabled) return false
        return configureDefaultNotificationDelivery(
            nativePushAvailable = isNativePushAvailable(),
            enableNativePush = { setNativePushEnabled(true) },
            disableNativePush = { setNativePushEnabled(false) },
            setBackgroundConnectionEnabled = ::setBackgroundConnectionEnabled,
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
        return shortNpub(accountIdHex)
    }

    fun contactNickname(accountIdHex: String): String? = contactNicknameFor(activeAccountRef, accountIdHex)

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

    private fun contactNicknameFor(
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
        return cachedName ?: shortNpub(accountIdHex)
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
        return cachedName ?: shortNpub(accountIdHex)
    }

    internal fun contactDisplayNameCached(
        accountRef: String?,
        accountIdHex: String,
    ): String = contactDisplayNameCachedOrNull(accountRef, accountIdHex) ?: shortNpub(accountIdHex)

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
        val profile =
            runCatchingCancellable {
                marmotIo { userProfile(accountIdHex) }
            }.getOrNull()
        if (profile == null) requestProfile(accountIdHex)
        return profile
    }

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

    fun requestProfile(accountIdHex: String) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        // Always materialize the local SQLite record independently of relay freshness.
        // This only schedules off-main binding work; render/timeline callers stay non-blocking.
        ensureProfileMaterialized(id)
        if (!profileRefreshGate.tryStart(id, System.currentTimeMillis())) return
        // Snapshot the cache epoch now, before the job is queued. A switch or
        // sign-out can clear the caches in the gap before this coroutine starts,
        // so the staleness check must compare against the epoch at request time,
        // not whatever it has become by the time the body runs.
        val requestEpoch = profileCacheEpoch.get()
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
     *
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
        val uncachedIds =
            accountIdHexes
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .filterNot { id ->
                    synchronized(profilePresentationLock) { profilePresentations.containsKey(id) }
                }
        if (uncachedIds.isEmpty()) return
        val gate = Semaphore(PROFILE_PRESENTATION_WARM_FANOUT)
        coroutineScope {
            uncachedIds
                .map { id ->
                    async {
                        gate.withPermit {
                            // Another warm/lazy read may have populated the cache
                            // while this coroutine waited for a permit.
                            if (synchronized(profilePresentationLock) { profilePresentations.containsKey(id) }) {
                                return@withPermit
                            }
                            materializeProfileLocally(id)
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

    suspend fun refreshProfile(
        accountIdHex: String,
        epoch: Int = profileCacheEpoch.get(),
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
        if (profile != null) {
            // Resolve everything that needs the FFI off the main thread (this
            // completion runs on profileScope = Main.immediate), then apply the
            // in-memory caches on the main thread. The read accessors serve from
            // these caches so composition never crosses the binding. See #4, #49.
            val rawDisplayName =
                if (profileDisplayNameReader != null) {
                    runCatchingCancellable { profileDisplayNameReader.invoke(accountIdHex) }.getOrNull()
                } else {
                    runCatchingCancellable { marmotIo { displayName(accountIdHex) } }.getOrNull()
                }
            val displayName = rawDisplayName?.let { ProfileSanitizer.displayName(it) }
            val presentation =
                ProfilePresentation(
                    displayName = displayName,
                    avatarUrl = ProfileSanitizer.protocolImageUrl(profile.picture),
                )
            // Drop the result if an account switch / sign-out cleared the caches
            // while this refresh was in flight, so we don't repopulate them with
            // the previous account's data.
            withContext(Dispatchers.Main.immediate) {
                if (profileCacheEpoch.get() == epoch) {
                    applyProfilePresentation(accountIdHex, profile, presentation)
                }
            }
        }
    }

    fun presentProfilePayload(raw: String): Boolean {
        val link = ProfileLink.parse(raw) ?: return false
        pendingProfileMetadata = null
        pendingProfileFromDiscovery = false
        pendingProfileNpub = link.npub
        return true
    }

    fun presentProfile(npub: String) {
        pendingProfileMetadata = null
        pendingProfileFromDiscovery = false
        pendingProfileNpub = npub
    }

    fun presentDiscoveredProfile(
        npub: String,
        profile: UserProfileMetadataFfi?,
    ) {
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
            ?: NostrProfileReference.accountIdHex(trimmed)
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
     * message render. The message renderer uses this to reserve the "@" mention
     * treatment for real group members: a pasted npub/nprofile of a non-member
     * still resolves to a display name but renders without the "@" (#1017).
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
        pendingProfileNpub = null
        pendingProfileMetadata = null
        pendingProfileFromDiscovery = false
    }

    /**
     * Create a 1:1 DM group with [npub]. This lower-level variant leaves
     * failure presentation to the caller so the New Message flow can keep an
     * inline retry state instead of collapsing everything into a transient toast.
     */
    suspend fun createProfileChatGroup(npub: String): String {
        val account = activeAccountRef ?: throw StartProfileChatNoActiveAccountException()
        return marmotIo(MarmotTraceSection.CREATE_GROUP) { createGroup(account, "", listOf(npub), null) }
    }

    private var chatCreateOpenTiming: ChatCreateOpenTiming? = null

    fun beginChatCreateOpenTiming() {
        chatCreateOpenTiming =
            ChatCreateOpenTiming.begin().also {
                it.mark(ChatCreateOpenTiming.STAGE_CONFIRM_TAP)
            }
    }

    fun markChatCreateOpenStage(stage: String) {
        chatCreateOpenTiming?.mark(stage)
    }

    fun hasActiveChatCreateOpenTiming(): Boolean = chatCreateOpenTiming != null

    fun completeChatCreateOpenTiming(stage: String) {
        markChatCreateOpenStage(stage)
        chatCreateOpenTiming = null
    }

    fun abandonChatCreateOpenTiming(stage: String) {
        markChatCreateOpenStage(stage)
        chatCreateOpenTiming = null
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
     * for the target account's broad chat-list projection to bind (#586).
     */
    suspend fun loadNotificationChatListItem(
        accountRef: String,
        groupIdHex: String,
    ): ChatListItem {
        check(activeAccountRef == accountRef) { "notification target account is not active" }
        return loadAuthoritativeChatListItem(accountRef, groupIdHex)
    }

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
    ) {
        val safeReport = diagnosticReport?.trim()?.takeIf(String::isNotEmpty)
        toast =
            ToastMessage(
                title = title,
                detail = detail,
                // A Copy affordance is valid only when there is a deliberately
                // constructed privacy-safe report. Legacy callers that merely
                // set copyable=true must never copy visible UI text.
                copyable = copyable && safeReport != null,
                tier = tier,
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
    ) {
        presentConversationTransient(accountRef, groupIdHex, AppText.Resource(titleRes), detail)
    }

    fun presentConversationTransient(
        accountRef: String,
        groupIdHex: String,
        title: AppText,
        detail: AppText? = null,
    ) {
        setTransientNotice(
            title = title,
            detail = detail,
            conversation = ConversationNoticeDestination(accountRef, groupIdHex),
        )
    }

    private fun setTransientNotice(
        title: AppText,
        detail: AppText? = null,
        conversation: ConversationNoticeDestination? = null,
    ) {
        transientNoticeSequence += 1L
        transientNotice =
            TransientNotice(
                id = transientNoticeSequence,
                title = title,
                detail = detail,
                conversation = conversation,
            )
    }

    fun presentTransient(
        title: String,
        detail: String? = null,
    ) {
        presentTransient(AppText.Plain(title), detail?.let(AppText::Plain))
    }

    fun clearToast() {
        toast = null
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

    private suspend fun MarmotInterface.configurePrivacyRuntime() {
        val installId = runCatchingCancellable { telemetryInstallId() }.getOrNull().orEmpty()
        setRelayTelemetryRuntimeConfig(
            RelayTelemetryRuntimeConfigFfi(
                otlpEndpoint = BuildConfig.WHITENOISE_OTLP_ENDPOINT.nonBlankOrNull(),
                authorizationBearerToken = BuildConfig.WHITENOISE_OTLP_AUTH_TOKEN.nonBlankOrNull(),
                resource =
                    RelayTelemetryResourceFfi(
                        serviceVersion = telemetryServiceVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        serviceInstanceId = installId,
                        deploymentEnvironment = telemetryDeploymentEnvironment(BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT),
                        tenant = BuildConfig.WHITENOISE_TELEMETRY_TENANT.ifBlank { "whitenoise-android" },
                        osType = "linux",
                        osVersion = Build.VERSION.RELEASE.ifBlank { Build.VERSION.SDK_INT.toString() },
                        deviceModelIdentifier = telemetryDeviceModelIdentifier(Build.MODEL),
                    ),
            ),
        )
        setAuditLogTrackerConfig(
            AuditLogTrackerConfigFfi(
                endpoint = BuildConfig.WHITENOISE_AUDIT_LOG_ENDPOINT.nonBlankOrNull(),
                authorizationBearerToken = BuildConfig.WHITENOISE_AUDIT_LOG_AUTH_TOKEN.nonBlankOrNull(),
                source =
                    AuditLogUploadSourceFfi(
                        deviceLabel = Build.MODEL.nonBlankOrNull(),
                        platform = "android",
                        appVersion = BuildConfig.VERSION_NAME,
                    ),
            ),
        )
    }

    private fun warmProfile(accountIdHex: String) {
        userProfile(accountIdHex)
        requestProfile(accountIdHex)
    }

    // A notification renders once and never recomposes, so resolve the name via
    // the awaited directory lookup (it spans every signed-in account) rather than
    // the in-memory cache, which is cold right after an FCM wake.
    private suspend fun notificationSenderName(update: NotificationUpdateFfi): String? {
        val senderIdHex = update.sender.accountIdHex
        if (senderIdHex.isBlank()) return null
        val contactNickname = contactNicknameFor(update.accountRef, senderIdHex)
        val localProfileName =
            runCatchingCancellable { marmotIo { displayName(senderIdHex) } }
                .getOrNull()
        // Leave an unresolved local name null: LocalNotificationFormatter can
        // then use the sender name carried by the notification payload before
        // applying its final short-npub fallback. Returning the npub here masked
        // a usable payload name during cold profile-cache startup.
        return notificationSenderNameOverride(contactNickname, localProfileName)
            ?: notificationDisplayNameHints[senderIdHex]
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

    // The recipient (own) identity's display name for the notification subtext,
    // resolved the same way the rest of the UI labels the account.
    private fun notificationRecipientName(accountRef: String): String? = accounts.firstOrNull { it.label == accountRef }?.let { displayName(it.accountIdHex) }

    // Resolve a mention for a one-shot notification. Unlike the Compose bubble
    // path, a notification will not recompose after requestProfile() finishes,
    // so do one local display-name read before falling back to shortened bech32.
    private suspend fun notificationMentionDisplayName(bech32: String): String? =
        resolveNotificationMentionDisplayName(
            bech32 = bech32,
            accountIdHex = { accountIdHex(it) },
            profileDisplayName = { profileDisplayName(it) },
            readDisplayName = { accountIdHex ->
                runCatchingCancellable { marmotIo { displayName(accountIdHex) } }.getOrNull()
            },
            requestProfile = { requestProfile(it) },
        )

    // Flatten notification body text through the same Markdown mention path used
    // by in-app bubbles/previews. A parser failure or legitimately empty document
    // deliberately returns null so LocalNotificationFormatter falls back to the
    // raw FFI preview instead of dropping the message body.
    private suspend fun notificationPreviewText(raw: String?): String? =
        resolveNotificationPreviewText(
            raw = raw,
            parseMarkdown = { parseMarkdownOrEmpty(it) },
            mentionDisplayName = { notificationMentionDisplayName(it) },
        )

    private suspend fun notificationMessageRecord(update: NotificationUpdateFfi) =
        update.messageIdHex?.let { messageId ->
            // A small recent tail; the just-arrived message is within it, and a
            // miss simply falls back to the generic notification body.
            runCatchingCancellable { marmotIo { messages(update.accountRef, update.groupIdHex, 30u) } }
                .getOrNull()
                ?.firstOrNull { it.messageIdHex.equals(messageId, ignoreCase = true) }
        }

    /**
     * Start document policy downloads from the receipt pipeline rather than
     * waiting for a Compose bubble to become visible. The worker persists only
     * the lookup identity and resolves the authoritative reference from MDK.
     */
    private suspend fun scheduleIncomingDocumentDownloads(update: NotificationUpdateFfi) {
        val messageIdHex = update.messageIdHex
        val isIncomingMessage = update.trigger == NotificationTriggerFfi.NEW_MESSAGE && !update.isFromSelf
        if (isIncomingMessage && messageIdHex != null) {
            val matrix = loadMediaAutoDownloadMatrix(update.accountRef)
            if (matrix.shouldAutoDownload(MediaAutoDownloadType.Document, activeNetworkTypes())) {
                val records =
                    runCatchingCancellable { marmotIo { listMedia(update.accountRef, update.groupIdHex, null) } }
                        .getOrNull()
                        .orEmpty()
                records
                    .asSequence()
                    .filter { it.messageIdHex.equals(messageIdHex, ignoreCase = true) }
                    .filter { record ->
                        val reference = record.reference
                        reference.sourceEpoch != 0uL &&
                            !MediaReferenceSupport.isImageMedia(reference) &&
                            !MediaReferenceSupport.isVideoMedia(reference) &&
                            !MediaReferenceSupport.isAudioMedia(reference)
                    }.forEach { record ->
                        enqueueAttachmentDownload(
                            AttachmentTransferRequest(
                                accountRef = update.accountRef,
                                groupIdHex = update.groupIdHex,
                                messageIdHex = record.messageIdHex,
                                attachmentIndex = record.attachmentIndex.toInt(),
                            ),
                        )
                    }
            }
        }
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

    private suspend fun notificationGroupSystemText(
        update: NotificationUpdateFfi,
        senderName: String?,
    ): NotificationSystemText? {
        val record = notificationTimelineRecord(update) ?: return null
        if (!MessageProjector.isGroupSystemKind(record.kind)) return null
        val event = GroupSystemEvents.resolve(record) ?: return null
        val diff = GroupSystemEvents.renameDiffNames(event)
        val actorHex = GroupSystemEvents.actorHex(event, record.sender)
        val actorName =
            when {
                GroupSystemEvents.isSelf(update.accountIdHex, actorHex) -> appContext.getString(R.string.you)
                !senderName.isNullOrBlank() -> senderName
                !actorHex.isNullOrBlank() -> runCatchingCancellable { displayNameForAccount(update.accountRef, actorHex) }.getOrNull()
                else -> null
            } ?: appContext.getString(R.string.group_system_someone)
        val subjectHex = event.subject
        val subjectName =
            when {
                GroupSystemEvents.isSelf(update.accountIdHex, subjectHex) -> appContext.getString(R.string.you)
                !subjectHex.isNullOrBlank() -> runCatchingCancellable { displayNameForAccount(update.accountRef, subjectHex) }.getOrNull()
                else -> null
            }
        return NotificationSystemText(
            title = if (diff != null) appContext.getString(R.string.notification_group_renamed) else null,
            body =
                if (diff != null) {
                    appContext.getString(R.string.notification_group_renamed_body, actorName, diff.oldName, diff.newName)
                } else {
                    GroupSystemEvents.summary(
                        event = event,
                        actorName = actorName,
                        subjectName = subjectName,
                        actorIsSelf = GroupSystemEvents.isSelf(update.accountIdHex, actorHex),
                        subjectIsSelf = GroupSystemEvents.isSelf(update.accountIdHex, subjectHex),
                        copy = notificationGroupSystemCopy(),
                    )
                },
        )
    }

    private fun notificationGroupSystemCopy(): GroupSystemCopy =
        GroupSystemCopy(
            memberAddedFormat = appContext.getString(R.string.group_system_member_added),
            memberAddedPassiveFormat = appContext.getString(R.string.group_system_member_added_passive),
            memberRemovedFormat = appContext.getString(R.string.group_system_member_removed),
            memberRemovedPassiveFormat = appContext.getString(R.string.group_system_member_removed_passive),
            memberLeftFormat = appContext.getString(R.string.group_system_member_left),
            adminAddedFormat = appContext.getString(R.string.group_system_admin_added),
            adminAddedPassiveFormat = appContext.getString(R.string.group_system_admin_added_passive),
            adminRemovedFormat = appContext.getString(R.string.group_system_admin_removed),
            adminRemovedPassiveFormat = appContext.getString(R.string.group_system_admin_removed_passive),
            renamedFormat = appContext.getString(R.string.group_system_renamed),
            renamedPassiveFormat = appContext.getString(R.string.group_system_renamed_passive),
            renamedDiffFormat = appContext.getString(R.string.group_system_renamed_diff),
            renamedDiffPassiveFormat = appContext.getString(R.string.group_system_renamed_diff_passive),
            namedFormat = appContext.getString(R.string.group_system_named),
            namedPassiveFormat = appContext.getString(R.string.group_system_named_passive),
            avatarChangedFormat = appContext.getString(R.string.group_system_avatar_changed),
            avatarChangedPassive = appContext.getString(R.string.group_system_avatar_changed_passive),
            youMemberAddedFormat = appContext.getString(R.string.group_system_you_member_added),
            memberAddedYouFormat = appContext.getString(R.string.group_system_member_added_you),
            memberAddedYouPassive = appContext.getString(R.string.group_system_member_added_you_passive),
            youMemberRemovedFormat = appContext.getString(R.string.group_system_you_member_removed),
            memberRemovedYouFormat = appContext.getString(R.string.group_system_member_removed_you),
            memberRemovedYouPassive = appContext.getString(R.string.group_system_member_removed_you_passive),
            youMemberLeft = appContext.getString(R.string.group_system_you_member_left),
            youAdminAddedFormat = appContext.getString(R.string.group_system_you_admin_added),
            adminAddedYouFormat = appContext.getString(R.string.group_system_admin_added_you),
            adminAddedYouPassive = appContext.getString(R.string.group_system_admin_added_you_passive),
            youAdminRemovedFormat = appContext.getString(R.string.group_system_you_admin_removed),
            adminRemovedYouFormat = appContext.getString(R.string.group_system_admin_removed_you),
            adminRemovedYouPassive = appContext.getString(R.string.group_system_admin_removed_you_passive),
            youRenamedFormat = appContext.getString(R.string.group_system_you_renamed),
            youRenamedDiffFormat = appContext.getString(R.string.group_system_you_renamed_diff),
            youNamedFormat = appContext.getString(R.string.group_system_you_named),
            youAvatarChanged = appContext.getString(R.string.group_system_you_avatar_changed),
            disappearingSetFormat = appContext.getString(R.string.group_system_disappearing_set),
            disappearingSetYouFormat = appContext.getString(R.string.group_system_disappearing_set_you),
            disappearingSetPassiveFormat = appContext.getString(R.string.group_system_disappearing_set_passive),
            disappearingOffFormat = appContext.getString(R.string.group_system_disappearing_off),
            disappearingOffYou = appContext.getString(R.string.group_system_disappearing_off_you),
            disappearingOffPassive = appContext.getString(R.string.group_system_disappearing_off_passive),
            someone = appContext.getString(R.string.group_system_someone),
            fallback = appContext.getString(R.string.group_system_fallback),
        )

    // Classify a captionless incoming message so its notification body can name
    // the attachment type. The runtime payload carries no content type, so read
    // the stored record (recent history tail) and match by id; a miss or a
    // non-media record yields None and the generic "New message" body stands.
    private suspend fun notificationMediaKind(update: NotificationUpdateFfi): ReplyMediaKind =
        notificationMessageRecord(update)?.let(MessageProjector::mediaKind) ?: ReplyMediaKind.None

    // Resolve the conversation title for a notification the same way the chat
    // list does, since the runtime payload's group name is empty for unnamed
    // groups. Returns null for DMs (MessagingStyle shows the sender instead).
    private suspend fun notificationConversationTitle(update: NotificationUpdateFfi): String? {
        if (update.isDm) return null
        // Sanitize the payload name like the display surfaces do (strip
        // bidi/control chars) before trusting it as a notification title.
        update.groupName?.let { ProfileSanitizer.displayName(it) }?.let { return it }
        val members =
            runCatchingCancellable { marmotIo { groupMembers(update.accountRef, update.groupIdHex) } }
                .getOrNull()
                .orEmpty()
        if (members.isEmpty()) return null
        return GroupProjector.displayTitle(
            name = "",
            // A NEW_MESSAGE only fires for an already-joined group; pending
            // invites surface as GROUP_INVITE, so the chat list's "Invite from
            // X" title can't apply and there's no invite account to pass.
            pendingInviteAccount = null,
            groupIdHex = update.groupIdHex,
            otherMemberAccount = GroupProjector.otherMemberAccount(members, update.accountIdHex),
            memberCount = GroupProjector.uniqueMemberCount(members),
            memberTitle = { displayNameForAccount(update.accountRef, it) },
            copy = notificationGroupTitleCopy(),
        )
    }

    // A notification renders once, so await the sender's local profile instead
    // of relying on the UI presentation cache, which materializes
    // asynchronously. Fall back to the payload picture when the local profile
    // has none. Sanitize every URL before fetching it.
    private suspend fun notificationSenderAvatarUrl(update: NotificationUpdateFfi): String? =
        ProfileSanitizer.protocolImageUrl(loadUserProfile(update.sender.accountIdHex)?.picture)
            ?: ProfileSanitizer.protocolImageUrl(update.sender.pictureUrl)

    private fun shouldPostNotification(
        update: NotificationUpdateFfi,
        engineMuted: Boolean,
    ): Boolean =
        LocalNotificationPolicy.shouldPost(
            update = update,
            appInForeground = appInForeground,
            activeConversationGroupIdHex = activeConversationGroupIdHex,
            activeConversationAccountRef = activeConversationAccountRef,
            appLockScreenVisible = appLockScreenVisible,
            conversationNotifyMode = chatMutePreferences::mode,
            engineMuted = engineMuted,
        )

    /**
     * Durable engine mute for the update's conversation, resolved once per
     * update in [processNotificationUpdate] and threaded through pre-warm,
     * the post decision, and the presenter's sync post-time re-check (which
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

    /**
     * Starts sender and conversation image work as soon as the app-state
     * notification subscription receives an ingested update. This path exists in
     * a cold FCM process with no UI-owned [ChatsController], and it waits only for
     * local projections — remote image work remains detached and bounded.
     * Returns already-resolved URLs so the eventual post does not repeat FFI
     * reads. Every remote-image launch re-checks the app lock after the preceding
     * suspending local lookup.
     */
    private suspend fun preWarmNotificationAvatars(
        update: NotificationUpdateFfi,
        engineMuted: Boolean,
    ): PreWarmedNotificationAvatars {
        val eligible =
            shouldPreWarmNotificationAvatars(
                update = update,
                shouldPost = shouldPostNotification(update, engineMuted),
                canPost = localNotificationPresenter.canPostNotifications(),
            )
        if (!eligible) return PreWarmedNotificationAvatars(senderAvatarUrl = null, groupAvatarUrl = null)

        val target = notificationAvatarPreWarmTarget(update, appLockScreenVisible)
        preWarmNotificationAvatarIfUnlocked(target.senderAvatarUrl)
        val senderAvatarUrl =
            if (target.preWarmRemoteImages && target.senderAccountIdHex != null) {
                bestEffortNotificationAvatarLookup { notificationSenderAvatarUrl(update) }
            } else {
                null
            }
        preWarmNotificationAvatarIfUnlocked(senderAvatarUrl)

        val groupAvatarUrl =
            if (target.resolveGroupAvatar) {
                bestEffortNotificationAvatarLookup {
                    marmotIo { groupDetails(update.accountRef, update.groupIdHex) }.group.avatarUrl
                }?.let { ProfileSanitizer.protocolImageUrl(it) }
            } else {
                null
            }
        preWarmNotificationAvatarIfUnlocked(groupAvatarUrl)
        return PreWarmedNotificationAvatars(senderAvatarUrl, groupAvatarUrl)
    }

    private fun preWarmNotificationAvatarIfUnlocked(url: String?) {
        if (!appLockScreenVisible) AvatarImageLoader.preWarm(url)
    }

    private suspend fun bestEffortNotificationAvatarLookup(block: suspend () -> String?): String? =
        try {
            block()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Throwable) {
            null
        }

    // Conversation shortcut icon: the peer for a DM, or the group's own avatar
    // for a group chat. The sender's MessagingStyle icon is resolved separately.
    private suspend fun notificationConversationAvatarUrl(
        update: NotificationUpdateFfi,
        senderAvatarUrl: String?,
        preWarmedGroupAvatarUrl: String?,
    ): String? =
        if (update.isDm) {
            senderAvatarUrl
        } else {
            preWarmedGroupAvatarUrl
                ?: bestEffortNotificationAvatarLookup {
                    marmotIo { groupDetails(update.accountRef, update.groupIdHex) }.group.avatarUrl
                }?.let { ProfileSanitizer.protocolImageUrl(it) }
        }

    private fun notificationGroupTitleCopy(): GroupTitleCopy =
        GroupTitleCopy(
            inviteFromFormat = appContext.getString(R.string.group_title_invite_from),
            groupOfPeopleFormat = appContext.getString(R.string.group_title_people_count),
            unknownTitle = appContext.getString(R.string.unknown),
            soleMemberTitle = appContext.getString(R.string.just_you),
        )

    private suspend fun postNotificationUpdate(
        update: NotificationUpdateFfi,
        preWarmedAvatars: PreWarmedNotificationAvatars,
        postEpoch: Long,
        engineMuted: Boolean,
    ) {
        val activeConversation = activeConversationGroupIdHex
        val shouldPost = shouldPostNotification(update, engineMuted)
        appStateDebug {
            "notification update key=${update.notificationKey.take(16)} trigger=${update.trigger} " +
                "foreground=$appInForeground active=${activeConversation?.take(8) ?: "<none>"} " +
                "activeAccount=${activeConversationAccountRef?.take(8) ?: "<none>"} " +
                "updateAccount=${update.accountRef.take(8)} appLock=$appLockScreenVisible " +
                "engineMuted=$engineMuted post=$shouldPost"
        }
        if (shouldPost) {
            val skipEnrichmentForLock = appLockScreenVisible
            val senderNameOverride = if (skipEnrichmentForLock) null else notificationSenderName(update)
            val systemText = if (skipEnrichmentForLock) null else notificationGroupSystemText(update, senderNameOverride)
            val previewTextOverride =
                systemText?.body ?: if (!skipEnrichmentForLock && LocalNotificationFormatter.needsPreviewTextResolution(update)) {
                    notificationPreviewText(update.previewText)
                } else {
                    null
                }
            val reactedToPreviewOverride =
                if (!skipEnrichmentForLock && LocalNotificationFormatter.needsReactedToPreviewResolution(update)) {
                    notificationPreviewText(update.reactedToPreview)
                } else {
                    null
                }
            // Only a message with no resolvable text preview can be a captionless
            // attachment, so classify just those (a single history read) to name
            // the media type in the body. Text messages never reach the fetch.
            val mediaKind =
                if (!skipEnrichmentForLock &&
                    systemText == null &&
                    LocalNotificationFormatter.needsPreviewTextResolution(update) &&
                    previewTextOverride.isNullOrBlank()
                ) {
                    notificationMediaKind(update)
                } else {
                    ReplyMediaKind.None
                }
            val senderAvatarUrl =
                if (skipEnrichmentForLock || appLockScreenVisible) {
                    null
                } else {
                    preWarmedAvatars.senderAvatarUrl
                        ?: bestEffortNotificationAvatarLookup { notificationSenderAvatarUrl(update) }
                }
            val conversationTitle =
                if (skipEnrichmentForLock || appLockScreenVisible) {
                    null
                } else {
                    systemText?.title ?: notificationConversationTitle(update)
                }
            val conversationAvatarUrl =
                if (skipEnrichmentForLock || appLockScreenVisible) {
                    null
                } else {
                    notificationConversationAvatarUrl(update, senderAvatarUrl, preWarmedAvatars.groupAvatarUrl)
                }
            // A lock can arrive during any suspending enrichment above. Re-check
            // after all app-state lookups; if enrichment was skipped while locked,
            // keep the post redacted even if the app has since unlocked.
            val redactNotificationContent = skipEnrichmentForLock || appLockScreenVisible
            val posted =
                localNotificationPresenter.show(
                    update,
                    if (redactNotificationContent) null else conversationTitle,
                    if (redactNotificationContent) null else senderNameOverride,
                    if (redactNotificationContent) null else previewTextOverride,
                    if (redactNotificationContent) null else reactedToPreviewOverride,
                    if (redactNotificationContent) ReplyMediaKind.None else mediaKind,
                    recipientAccountSubtext =
                        if (redactNotificationContent) {
                            null
                        } else {
                            LocalNotificationFormatter.recipientAccountSubtext(
                                signedInAccountCount = accounts.count { it.isSignedInSigningAccount() },
                                recipientLabel = notificationRecipientName(update.accountRef),
                            )
                        },
                    redactContent = redactNotificationContent,
                    directShareEligible = !redactNotificationContent && update.accountRef == activeAccountRef,
                    conversationAvatarUrl = if (redactNotificationContent) null else conversationAvatarUrl,
                    senderAvatarUrl = if (redactNotificationContent) null else senderAvatarUrl,
                    shortNpub = ::shortNpub,
                    isPostStillAllowed = {
                        !networkNotificationRecoverySuppressed &&
                            notificationPostEpoch.isCurrent(postEpoch) &&
                            shouldPostNotification(update, engineMuted)
                    },
                )
            postedGroupInviteIdentity(
                update = update,
                posted = posted,
                redactContent = redactNotificationContent,
                displayedName = senderNameOverride ?: notificationDisplayNameHint(update.sender.displayName),
            )?.let { identity ->
                inviteNotificationIdentityRefreshStore.rememberPosted(
                    update = identity.update,
                    displayedName = identity.displayedName,
                )
                synchronized(profilePresentationLock) {
                    profilePresentations[update.sender.accountIdHex]
                }?.let { presentation ->
                    scheduleInviteNotificationIdentityRefresh(update.sender.accountIdHex, presentation)
                }
            }
        }
        // Coalesce the unread refresh across a burst instead of paying the
        // chat-list + per-group roster cost once per update. The scheduler
        // drains pending accounts off the subscription loop, so the loop stays
        // free to process the next update (#729).
        if (networkNotificationRecoverySuppressed) return
        unreadRefreshScheduler.schedule(update.accountRef)
        signalNotificationDrain()
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

    private suspend fun runNotificationListenerLoop(marmot: MarmotInterface) {
        // Restart the subscription on any failure (or clean end-of-stream)
        // with exponential backoff, so a transient relay/binding error
        // doesn't permanently silence notifications. Backoff resets after
        // each received update; cancellation propagates and stops the loop.
        // See #56.
        var backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
        while (currentCoroutineContext().isActive) {
            val retryWakeGeneration = notificationReceiverRetryWake.value
            try {
                val subscription = notificationSubscriber(marmot)
                notificationReceiverActive.value = true
                try {
                    while (currentCoroutineContext().isActive) {
                        val update = subscription.next() ?: break
                        backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
                        withContext(Dispatchers.Main.immediate) {
                            processNotificationUpdate(update)
                        }
                    }
                } finally {
                    notificationReceiverActive.value = false
                    runCatching {
                        withContext(NonCancellable + Dispatchers.IO) {
                            subscription.close()
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

    private suspend fun processNotificationUpdate(update: NotificationUpdateFfi) {
        applyNotificationDisplayNameHint(update)
        scheduleIncomingDocumentDownloads(update)
        val postEpoch = notificationPostEpoch.capture()
        // One durable-mute read per update: pre-warm, the post decision, and
        // the presenter's post-time re-check all reuse it, so a burst costs
        // one engine chat-list read per notification, not one per stage.
        val engineMuted = engineNotificationMuted(update)
        postAfterNotificationAvatarPreWarm(
            preWarm = { preWarmNotificationAvatars(update, engineMuted) },
            post = { avatars -> postNotificationUpdate(update, avatars, postEpoch, engineMuted) },
        )
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

    private fun launchInviteNotificationIdentityRefresh(initialCandidate: GroupInviteNotificationIdentityRefreshStore.RefreshCandidate) {
        val update = initialCandidate.update
        profileScope.launch {
            var candidate: GroupInviteNotificationIdentityRefreshStore.RefreshCandidate? = initialCandidate
            while (candidate != null) {
                val currentCandidate = candidate
                var followUp: GroupInviteNotificationIdentityRefreshStore.RefreshCandidate? = null
                candidate = null
                inviteNotificationIdentityRefreshStore.runClaimedRefresh(update.notificationKey) {
                    if (appLockScreenVisible) {
                        inviteNotificationIdentityRefreshStore.release(update.notificationKey)
                        // Unlock can race between the visibility check and the
                        // release above. Re-check after releasing so either this
                        // coroutine or the unlock hook reclaims the deferred work.
                        if (!appLockScreenVisible) {
                            resumePendingInviteNotificationIdentityRefreshes()
                        }
                        return@runClaimedRefresh
                    }
                    if (!localNotificationPresenter.isGroupInviteNotificationActive(update)) {
                        inviteNotificationIdentityRefreshStore.forget(update.notificationKey)
                        return@runClaimedRefresh
                    }
                    val refreshResult =
                        refreshActiveInviteNotificationIdentity(
                            update = update,
                            resolvedProfileName = currentCandidate.resolvedName,
                        )
                    if (refreshResult.posted) {
                        followUp =
                            inviteNotificationIdentityRefreshStore.completeRefresh(
                                update = update,
                                displayedName = refreshResult.displayedName,
                                contentRedacted = refreshResult.contentRedacted,
                            )
                        if (refreshResult.contentRedacted && !appLockScreenVisible) {
                            // Unlock can race the release in completeRefresh().
                            resumePendingInviteNotificationIdentityRefreshes()
                        }
                    } else if (!localNotificationPresenter.isGroupInviteNotificationActive(update)) {
                        inviteNotificationIdentityRefreshStore.forget(update.notificationKey)
                    } else {
                        inviteNotificationIdentityRefreshStore.release(update.notificationKey)
                    }
                }
                candidate = followUp
            }
        }
    }

    private suspend fun refreshActiveInviteNotificationIdentity(
        update: NotificationUpdateFfi,
        resolvedProfileName: String?,
    ): InviteNotificationIdentityRefreshResult {
        val skipEnrichmentForLock = appLockScreenVisible
        val resolvedName =
            if (skipEnrichmentForLock) {
                null
            } else {
                notificationSenderName(update)
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
                        LocalNotificationFormatter.recipientAccountSubtext(
                            signedInAccountCount = accounts.count { it.isSignedInSigningAccount() },
                            recipientLabel = notificationRecipientName(update.accountRef),
                        )
                    },
                redactContent = redactContent,
                directShareEligible = !redactContent && update.accountRef == activeAccountRef,
                silentUpdate = true,
                shortNpub = ::shortNpub,
                isPostStillAllowed = {
                    !networkNotificationRecoverySuppressed &&
                        localNotificationPresenter.isGroupInviteNotificationActive(update)
                },
            )
        return InviteNotificationIdentityRefreshResult(
            posted = posted,
            displayedName = displayedName,
            contentRedacted = redactContent,
        )
    }

    private fun updateBackgroundConnectionPreference(enabled: Boolean) {
        backgroundConnectionEnabled = enabled
        BackgroundConnectionPreferences.setEnabled(appContext, enabled)
        appStateDebug { "background connection enabled=$enabled" }
    }

    private fun startBackgroundConnectionService(): Boolean {
        val started = NotificationStreamForegroundService.start(appContext)
        appStateDebug { "background connection service start=$started" }
        return started
    }

    private fun applyLanguageTag(tag: String) {
        appContext
            .getSystemService(LocaleManager::class.java)
            .applicationLocales = LocaleList.forLanguageTags(tag)
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
    private fun ensureProfileMaterialized(accountIdHex: String) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        // Dedup concurrent lazy triggers: only launch if not already cached and
        // not already in flight. The blocking warm path does not use this set —
        // it reads and applies idempotently (see [warmProfilePresentationsBlocking]).
        if (!claimProfileMaterialization(id)) return
        profileScope.launch {
            try {
                materializeProfileLocally(id)
            } finally {
                synchronized(profilePresentationLock) { materializingProfiles.remove(id) }
            }
        }
    }

    /**
     * Reserve [id] for an in-flight lazy materialization. Returns false (already
     * cached or another lazy materialization in flight) so [ensureProfileMaterialized]
     * skips a redundant launch. The reservation is released in that launch's
     * `finally`, so a reserved id is always freed even on failure.
     */
    private fun claimProfileMaterialization(id: String): Boolean =
        synchronized(profilePresentationLock) {
            if (profilePresentations.containsKey(id)) {
                false
            } else {
                materializingProfiles.add(id)
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
     * It is pure read + idempotent apply (no `materializingProfiles` bookkeeping),
     * so it is safe to call directly from the blocking warm without a prior claim.
     */
    private suspend fun materializeProfileLocally(id: String) {
        val epoch = profileCacheEpoch.get()
        val seed = loadAccountSwitchProfileSeed(id)
        withContext(Dispatchers.Main.immediate) {
            if (profileCacheEpoch.get() == epoch) {
                applyAccountSwitchProfileSeed(seed)
            }
        }
    }

    /** Read one persisted profile without mutating the active account caches. */
    private suspend fun loadAccountSwitchProfileSeed(id: String): AccountSwitchProfileSeed {
        val profile =
            if (profileReader != null) {
                runCatchingCancellable { profileReader.invoke(id) }.getOrNull()
            } else {
                runCatchingCancellable { marmotIo { userProfile(id) } }.getOrNull()
            }
        val rawDisplayName =
            if (profileDisplayNameReader != null) {
                runCatchingCancellable { profileDisplayNameReader.invoke(id) }.getOrNull()
            } else {
                runCatchingCancellable { marmotIo { displayName(id) } }.getOrNull()
            }
        val displayName = rawDisplayName?.let { ProfileSanitizer.displayName(it) }
        val presentation =
            ProfilePresentation(
                displayName = displayName,
                avatarUrl = ProfileSanitizer.protocolImageUrl(profile?.picture),
            )
        return AccountSwitchProfileSeed(
            accountIdHex = id,
            profile = profile,
            displayName = presentation.displayName,
            avatarUrl = presentation.avatarUrl,
        )
    }

    private fun applyAccountSwitchProfileSeed(seed: AccountSwitchProfileSeed) {
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
        }
    }

    private fun notifyProfilesChanged() {
        assertMainThread { "notifyProfilesChanged" }
        synchronized(profilePresentationLock) {
            profilePresentations.clear()
            userProfiles.clear()
            pendingAvatarPreWarmAccountIds.clear()
            materializingProfiles.clear()
        }
        profileRevision += 1
        bumpAllProfileAccountRevisions()
    }

    private fun groupMemberSnapshotKey(
        accountRef: String?,
        groupIdHex: String,
    ): String? {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
        return "$account:$groupIdHex"
    }

    // Keep platform callbacks at the end of instance initialization. These
    // coroutines can execute immediately on another thread and must not observe
    // fields declared later in this class before their initializers have run.
    init {
        applyLanguageTag(languageTag)
        if (startPlatformServices) {
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
            // Load the persisted per-chat channel scopes before the settings UI
            // can request them, without blocking the main-thread constructor.
            mutationsScope.launch(Dispatchers.IO) { conversationNotificationRouting }
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

        // 24 MiB cap on decrypted attachment bytes resident in memory —
        // roughly ten 1920px JPEGs. Persists across conversation re-entry.
        private const val MEDIA_PLAINTEXT_CACHE_MAX_BYTES: Long = 24L * 1024L * 1024L

        // ~48 MiB of decoded thumbnails (sampled to <=1280px). Enough to keep
        // visible bubbles spinner-free; bounded so it can't grow unbounded.
        private const val MEDIA_THUMBNAIL_CACHE_MAX_BYTES: Long = 48L * 1024L * 1024L

        // ~256 MiB of persistent decrypted media on disk. Big enough to keep
        // typical chat history through OS cache reaps; OS may still trim
        // earlier if device-wide cache pressure hits.
        private const val DISK_MEDIA_CACHE_MAX_BYTES: Long = 256L * 1024L * 1024L

        // Match MDK's current encrypted receive ceiling. L1 remains capped at
        // 24 MiB so large documents are durable without being retained on the
        // JVM heap after the active open/download operation finishes.
        private const val DISK_MEDIA_CACHE_MAX_ENTRY_BYTES: Long = 64L * 1024L * 1024L
        private const val LANGUAGE_TAG_KEY = "language_tag"
        private const val PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS = 60_000L
        private const val PROFILE_PRESENTATION_WARM_FANOUT = 6
        private const val PROFILE_REFRESH_FANOUT = 6

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
        private const val NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS = 60_000L
        private const val NOTIFICATION_STARTUP_RECEIVER_TIMEOUT_MILLIS = 5_000L

        private const val NOTIFICATION_PUSH_DRAIN_TIMEOUT_MILLIS = 10_000L
        private const val MAX_RETAINED_CONVERSATION_STATES = 32
    }
}

private inline fun appStateDebug(message: () -> String) {
    // Debug-only: these INFO lines are operational/diagnostic and some carry
    // sender/group context, so they must not ship in release logcat. See #39.
    if (BuildConfig.DEBUG) Log.i("DMAppState", message())
}

private inline fun appStateDebug(
    error: Throwable,
    message: () -> String,
) {
    if (BuildConfig.DEBUG) {
        Log.e("DMAppState", message(), error)
    } else {
        Log.e("DMAppState", "operation failed: ${error.javaClass.simpleName}")
    }
}

internal suspend fun awaitBootstrapAttempt(
    attempt: Deferred<Unit>,
    timeoutMillis: Long,
): Boolean =
    withTimeoutOrNull(timeoutMillis) {
        attempt.await()
        true
    } ?: false

internal fun startupUnreadRefreshIsCurrent(
    expectedRevision: Long,
    currentRevision: Long,
): Boolean = expectedRevision == currentRevision

private fun startupTiming(
    stage: String,
    elapsedMs: Long,
    durationMs: Long? = null,
) {
    if (
        BuildConfig.DEBUG ||
        BuildConfig.WHITENOISE_DEPLOYMENT_ENVIRONMENT == "staging" ||
        BuildConfig.ENABLE_PERFORMANCE_TEST_SELECTORS
    ) {
        val duration = durationMs?.let { " duration_ms=${it.coerceAtLeast(0L)}" }.orEmpty()
        Log.i(
            "WNStartup",
            "stage=$stage elapsed_ms=${elapsedMs.coerceAtLeast(0L)} " +
                "uptime_ms=${SystemClock.elapsedRealtime()}$duration",
        )
    }
}

private const val BOOTSTRAP_ACTIONABLE_TIMEOUT_MILLIS = 15_000L

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Whether [WhiteNoiseAppState.importIdentity] may call the engine — direct import is nsec-only. */
internal fun permitsDirectIdentityImport(trimmed: String): Boolean = IdentityEntryInput.classify(trimmed) == IdentityEntryInput.Kind.SecretKey

/**
 * How a direct nsec sign-in ended. The engine's account-setup states are kept
 * apart because each one calls for a different thing from the user: two are
 * resumable by signing in again, one says the account was never in the state
 * recovery applies to, and one needs explicit consent before anything rotates.
 */
internal sealed interface IdentityImportOutcome {
    data object Success : IdentityImportOutcome

    /** Input the engine was never asked about, or a failure with no typed meaning. */
    data object Failed : IdentityImportOutcome

    /** Durable account setup can be resumed by retrying the same sign-in. */
    data object SetupRetryRequired : IdentityImportOutcome

    /** A recoverable KeyPackage setup state exists, so retry rather than reset. */
    data object SetupKeyPackageRecoveryAvailable : IdentityImportOutcome

    /** The account was not in the incomplete-setup state the reset applies to. */
    data object SetupResetNotApplicable : IdentityImportOutcome

    /**
     * Local evidence cannot prove a previously signed KeyPackage was never
     * exposed, so the engine forbids rotation until the host passes an explicit
     * acknowledgement.
     */
    data object SetupRecoveryRequired : IdentityImportOutcome
}

/**
 * The two engine login entry points behind a direct nsec sign-in. Injectable so
 * a test can count them: which binding a sign-in reaches is the consent
 * guarantee, and a source-text guard cannot see a bypass routed through some
 * other wrapper.
 */
internal interface IdentityLoginCalls {
    suspend fun login(
        nsec: String,
        relays: List<String>,
        keyPackageRelays: List<String>,
    ): AccountSummaryFfi

    suspend fun loginRecoveringIncompleteSetup(
        nsec: String,
        relays: List<String>,
        keyPackageRelays: List<String>,
        acknowledgePossibleKeyPackageOrphan: Boolean,
    ): AccountSummaryFfi
}

internal fun identityImportOutcome(error: Throwable): IdentityImportOutcome =
    when (error) {
        is MarmotKitException.AccountSetupRetryRequired -> IdentityImportOutcome.SetupRetryRequired
        is MarmotKitException.AccountSetupKeyPackageRecoveryAvailable ->
            IdentityImportOutcome.SetupKeyPackageRecoveryAvailable
        is MarmotKitException.AccountSetupResetNotApplicable -> IdentityImportOutcome.SetupResetNotApplicable
        is MarmotKitException.AccountSetupRecoveryRequired -> IdentityImportOutcome.SetupRecoveryRequired
        else -> IdentityImportOutcome.Failed
    }

internal fun notificationActionsAllowed(appLockScreenVisible: Boolean): Boolean = !appLockScreenVisible
