package dev.ipf.darkmatter.state

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.text.SpanStyle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.AvatarImageLoader
import dev.ipf.darkmatter.core.GroupProjector
import dev.ipf.darkmatter.core.GroupTitleCopy
import dev.ipf.darkmatter.core.HostSafety
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MarmotClient
import dev.ipf.darkmatter.core.MessageProjector
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.darkmatter.notifications.BackgroundConnectionPreferences
import dev.ipf.darkmatter.notifications.LocalNotificationPolicy
import dev.ipf.darkmatter.notifications.LocalNotificationPresenter
import dev.ipf.darkmatter.notifications.NotificationStreamForegroundService
import dev.ipf.darkmatter.notifications.PushServerConfig
import dev.ipf.darkmatter.notifications.PushTokenStore
import dev.ipf.darkmatter.ui.markdownDocumentMentionBech32s
import dev.ipf.darkmatter.ui.markdownDocumentToPreviewAnnotatedString
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AuditLogSettingsFfi
import dev.ipf.marmotkit.AuditLogTrackerConfigFfi
import dev.ipf.marmotkit.AuditLogUploadSourceFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.marmotkit.RelayTelemetryResourceFfi
import dev.ipf.marmotkit.RelayTelemetryRuntimeConfigFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.SignOutOutcomeFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import dev.ipf.marmotkit.WipeOutcomeFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

sealed interface AppPhase {
    data object Bootstrapping : AppPhase

    data object Onboarding : AppPhase

    data object Ready : AppPhase

    data class Failed(
        val message: String,
    ) : AppPhase
}

internal data class SignOutOutcome(
    val nextActiveRef: String?,
    val phase: AppPhase,
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
 * Next exponential-backoff delay: double [current], clamped to [maxMillis].
 * Guards the multiply so a near-`Long.MAX_VALUE` input can't overflow to a
 * negative value below the clamp (returns [maxMillis] once at/over the cap).
 */
internal fun nextRetryBackoffMillis(
    current: Long,
    maxMillis: Long,
): Long = if (current >= maxMillis) maxMillis else (current * 2).coerceAtMost(maxMillis)

data class ToastMessage(
    val title: AppText,
    val detail: AppText? = null,
)

private data class ProfilePresentation(
    val displayName: String?,
    val avatarUrl: String?,
) {
    companion object {
        val Empty = ProfilePresentation(displayName = null, avatarUrl = null)
    }
}

enum class RelayListKind {
    Nip65,
    Inbox,
}

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
        else -> "production"
    }

internal fun telemetryDeviceModelIdentifier(model: String): String? = model.trim().takeIf { it.isNotEmpty() }

internal fun isAcceptableRelayUrl(url: String): Boolean = canonicalRelayUrl(url) != null

private fun canonicalRelayUrl(url: String): String? {
    return runCatching {
        val uri = URI(url.trim())
        if (uri.scheme?.equals("wss", ignoreCase = true) != true || uri.userInfo != null) {
            return@runCatching null
        }
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

class DarkMatterAppState(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("darkmatter", Context.MODE_PRIVATE)

    /**
     * App-lifetime cache of decrypted attachment bytes, keyed by the globally
     * unique `messageIdHex`. Lives here (not on the per-conversation
     * controller) so re-opening a chat doesn't re-download media already
     * fetched this session. Bounded in bytes; see [dev.ipf.darkmatter.media.ByteSizeLruCache].
     */
    internal val mediaPlaintextCache =
        dev.ipf.darkmatter.media.ByteSizeLruCache<String, ByteArray>(
            maxBytes = MEDIA_PLAINTEXT_CACHE_MAX_BYTES,
            sizeOf = { it.size },
        )

    /**
     * App-lifetime cache of *decoded* attachment thumbnails (sampled bitmaps),
     * keyed identically to [mediaPlaintextCache]. Lets a bubble render its
     * image on the first frame — no decode spinner — for anything already
     * fetched/sent this session. Bounded by approximate pixel bytes.
     */
    internal val mediaThumbnailCache =
        dev.ipf.darkmatter.media.ByteSizeLruCache<String, android.graphics.Bitmap>(
            maxBytes = MEDIA_THUMBNAIL_CACHE_MAX_BYTES,
            sizeOf = { it.allocationByteCount },
        )

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
     * surface. Sign-out wipes it alongside L1 so account A's plaintext
     * doesn't linger on disk after switching to account B.
     */
    internal val diskMediaCache =
        dev.ipf.darkmatter.media.DiskByteCache(
            cacheDir = java.io.File(appContext.cacheDir, "decrypted-media"),
            maxBytes = DISK_MEDIA_CACHE_MAX_BYTES,
        )

    private var client: MarmotClient? = null

    // Serializes bootstrap so two concurrent callers can't both pass the
    // null-client check and each construct a MarmotClient (TOCTOU). See #33.
    private val bootstrapMutex = Mutex()
    private val localNotificationPresenter = LocalNotificationPresenter(appContext)
    private val pushTokenStore = PushTokenStore.create(appContext)

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

    var accounts by mutableStateOf<List<AccountSummaryFfi>>(emptyList())
        private set

    var accountUnreadCounts by mutableStateOf<Map<String, ULong>>(emptyMap())
        private set

    var activeAccountRef by mutableStateOf(preferences.getString(ACTIVE_ACCOUNT_KEY, null))
        private set

    var developerMode by mutableStateOf(preferences.getBoolean(DEVELOPER_MODE_KEY, false))
        private set

    /**
     * Force the IME into incognito mode for every text field in the app (#405).
     * Default ON to match the app's privacy positioning: messages typed in an
     * E2EE chat must not leak back out through keyboard learning / cloud sync.
     * This is an Android platform preference (UI behavior), not Marmot protocol
     * data, so SharedPreferences is the correct home per AGENTS.md.
     */
    var forceIncognitoKeyboard by mutableStateOf(preferences.getBoolean(FORCE_INCOGNITO_KEYBOARD_KEY, true))
        private set

    var themeMode by mutableStateOf(AppThemeMode.fromPreference(preferences.getString(THEME_MODE_KEY, null)))
        private set

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

    var pendingProfileNpub by mutableStateOf<String?>(null)
        private set

    var localNotificationSettings by mutableStateOf<NotificationSettingsFfi?>(null)
        private set

    var relayTelemetrySettings by mutableStateOf<RelayTelemetrySettingsFfi?>(null)
        private set

    var auditLogSettings by mutableStateOf<AuditLogSettingsFfi?>(null)
        private set

    var runtimeGeneration by mutableStateOf(0)
        private set

    var localNotificationPermissionGranted by mutableStateOf(localNotificationPresenter.canPostNotifications())
        private set

    var backgroundConnectionEnabled by mutableStateOf(BackgroundConnectionPreferences.isEnabled(appContext))
        private set

    private var defaultNotificationsEnableAttempted by mutableStateOf(
        preferences.getBoolean(DEFAULT_NOTIFICATIONS_ENABLE_ATTEMPTED_KEY, false),
    )
    private var defaultNotificationPermissionPromptInFlight by mutableStateOf(false)

    private val npubs = ConcurrentHashMap<String, String>()
    private var profileRevision by mutableStateOf(0)

    /**
     * Read-only Compose-tracked snapshot of [profileRevision] so callers
     * outside this file can subscribe to profile-cache invalidations
     * without exposing the mutable backing field. Includes this in a
     * `remember(...)` key list to re-fire a derivation when a profile
     * update lands (e.g. the chat-list search filter must re-evaluate
     * its title projection when a DM peer's display name resolves).
     */
    val profileRevisionForCompose: Int
        get() = profileRevision
    private val profilePresentations = mutableMapOf<String, ProfilePresentation>()

    // Materialized profile metadata, populated off-main by [refreshProfile].
    // Read accessors serve from here so composition never crosses the FFI.
    private val userProfiles = mutableMapOf<String, UserProfileMetadataFfi>()

    // Ids with an in-flight local materialization, so a cache miss launches at
    // most one local read per id. Distinct from the relay-refresh cooldown gate.
    private val materializingProfiles = mutableSetOf<String>()
    private val profilePresentationLock = Any()
    private val groupMemberSnapshots = mutableMapOf<String, GroupMemberSnapshot>()
    private val groupMemberSnapshotLock = Any()
    private val conversationStateLock = Any()
    private val optimisticMessagesByConversation = mutableMapOf<String, SnapshotStateMap<String, TimelineMessage>>()
    private val projectedMessageIdsByConversation = mutableMapOf<String, MutableSet<String>>()
    private val timelineOrderOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()
    private val timelineTimestampOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()

    // Retained-upload bytes survive screen disposal so a user who navigates
    // out of a chat mid-send and returns sees the pending bubble still carry
    // its preview/filename instead of an empty placeholder. Cap (and sizeOf
    // policy) match the controller-local version they replace.
    private val retainedMediaUploadsByConversation = mutableMapOf<String, dev.ipf.darkmatter.media.ByteSizeLruCache<String, RetainedMediaUpload>>()
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

    val draftStore: DraftStore = DraftStore.forContext(appContext)

    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutationsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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
    private var notificationJob: Job? = null
    private var appInForeground = false

    @Volatile
    private var isForegroundCatchUpRunning = false
    private var activeConversationGroupIdHex: String? = null

    // The account that has the active conversation on screen, captured when the
    // chat opens. Notification suppression is scoped to this account so viewing
    // a shared group under one account doesn't mute the other account's alerts.
    private var activeConversationAccountRef: String? = null
    private val profileRefreshGate = ProfileRefreshGate(PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS)
    private var chatsController: ChatsController? = null

    init {
        applyLanguageTag(languageTag)
    }

    val activeAccount: AccountSummaryFfi?
        get() = activeAccountRef?.let { ref -> accounts.firstOrNull { it.label == ref } }

    /** Convenience: return the active account's draft for [groupIdHex], or null. */
    fun draftFor(groupIdHex: String): String? {
        val account = activeAccount?.accountIdHex ?: return null
        return draftStore.get(account, groupIdHex)
    }

    /** Convenience: write the active account's draft for [groupIdHex]. Empty/blank clears. */
    fun setDraft(
        groupIdHex: String,
        text: String,
    ) {
        val account = activeAccount?.accountIdHex ?: return
        draftStore.set(account, groupIdHex, text)
    }

    fun marmot(): Marmot = requireNotNull(client) { "Marmot is not initialized" }.marmot

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

    internal fun retainedMediaUploads(
        accountRef: String?,
        groupIdHex: String,
    ): dev.ipf.darkmatter.media.ByteSizeLruCache<String, RetainedMediaUpload> =
        synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            retainedMediaUploadsByConversation.getOrPut(key) {
                dev.ipf.darkmatter.media.ByteSizeLruCache(
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
    }

    // TODO(marmot): remove this UI-controller backchannel once Marmot emits a
    // ProjectionUpdated (or equivalent chat-list/group projection update) after
    // set_group_archived. Until then, the ChatsController stream never sees the
    // archived-flag change and we forward it locally.
    fun applyLocalGroupUpdate(record: AppGroupRecordFfi) {
        chatsController?.applyLocalGroupUpdate(record)
    }

    /**
     * Confirmed chats the active account can forward a message into, recent
     * first. Empty when no chats controller is attached yet (the chat-list
     * stream hasn't bound) — the forward picker then shows its empty state.
     */
    fun forwardTargets(): List<ChatListItem> = chatsController?.forwardTargets().orEmpty()

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
        val account = activeAccount?.accountIdHex ?: return
        launchMutation {
            var failures = 0
            for (groupIdHex in targets) {
                val ok =
                    runCatching {
                        marmotIo { sendText(account, groupIdHex, trimmed) }
                    }.isSuccess
                if (!ok) failures += 1
            }
            val delivered = targets.size - failures
            when {
                failures == 0 ->
                    present(R.string.toast_forwarded_to_chats, AppText.Plain(delivered.toString()))
                delivered == 0 ->
                    present(R.string.toast_forward_failed)
                else ->
                    present(
                        R.string.toast_forwarded_partial,
                        AppText.Plain("$delivered/${targets.size}"),
                    )
            }
        }
    }

    fun sharedGroupsWith(accountIdHex: String): List<ChatListItem> = chatsController?.sharedGroupsWith(accountIdHex, activeAccount?.accountIdHex).orEmpty()

    fun existingDirectChat(reference: String): ChatListItem? = chatsController?.existingDirectChat(reference)

    suspend fun <T> marmotIo(block: suspend Marmot.() -> T): T =
        withContext(Dispatchers.IO) {
            marmot().block()
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
        runCatching { marmotIo { catchUpAccounts() } }
            .onFailure {
                rethrowIfCancellation(it)
                appStateDebug(it) { "catchUpAccounts failed: ${it.readableMessage()}" }
            }
    }

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
            catchUpAccounts()
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
                    // stack, and retry short-lived per-tile failures before
                    // surfacing a manual retry state. The gate is acquired
                    // inside the Deferred so callers only suspend at `await()`.
                    val downloadScope = this
                    attachmentDownloadGate.withRetryingPermit { downloadScope.block() }
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

    suspend fun bootstrap() = bootstrapMutex.withLock { bootstrapLocked() }

    private suspend fun bootstrapLocked() {
        if (client != null && phase != AppPhase.Bootstrapping) {
            ensureNotificationRuntimeStarted()
            phase = if (accounts.isEmpty()) AppPhase.Onboarding else AppPhase.Ready
            return
        }
        phase = AppPhase.Bootstrapping
        try {
            val opened =
                withContext(Dispatchers.IO) {
                    client ?: MarmotClient(appContext).also { client = it }
                }
            appStateDebug { "bootstrap root=${opened.rootPath}" }
            val accountLabelForRuntime = activeAccountRef
            withContext(Dispatchers.IO) {
                opened.marmot.configurePrivacyRuntime(accountLabelForRuntime)
                opened.marmot.start()
            }
            appStateDebug { "marmot started" }
            localNotificationPresenter.ensureChannels()
            refreshLocalNotificationPermission()
            startNotificationListener()
            refreshSecurityPrivacySettings()
            refreshAccounts()
            appStateDebug {
                "accounts loaded count=${accounts.size} active=$activeAccountRef labels=${accounts.map { it.label.take(8) to it.running }}"
            }
            if (accounts.isEmpty()) {
                localNotificationSettings = null
                phase = AppPhase.Onboarding
            } else {
                if (activeAccountRef == null || accounts.none { it.label == activeAccountRef }) {
                    setActiveAccount(accounts.first().label)
                } else {
                    // Reconcile the persisted active ref with the engine — e.g.
                    // sign in if the account was non-destructively signed out, and
                    // re-key the media matrix that was seeded before accounts loaded.
                    setActiveAccount(activeAccountRef!!)
                }
                refreshLocalNotificationSettings()
                phase = AppPhase.Ready
                activeAccount?.accountIdHex?.let { warmProfile(it) }
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            appStateDebug(error) { "bootstrap failed: ${error.readableMessage()}" }
            phase = AppPhase.Failed(error.readableMessage())
        }
    }

    suspend fun ensureNotificationRuntimeStarted() {
        if (client == null) {
            bootstrap()
            return
        }
        localNotificationPresenter.ensureChannels()
        refreshLocalNotificationPermission()
        startNotificationListener()
        if (accounts.isEmpty()) refreshAccounts()
        refreshLocalNotificationSettings()
    }

    suspend fun createIdentity() {
        try {
            val summary = marmotIo { createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays) }
            refreshAccounts()
            setActiveAccount(summary.label)
            refreshLocalNotificationSettings()
            phase = AppPhase.Ready
            present(R.string.toast_identity_created)
            warmProfile(summary.accountIdHex)
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            present(R.string.toast_couldnt_create_identity, AppText.Plain(error.readableMessage()))
        }
    }

    suspend fun importIdentity(identity: String) {
        val trimmed = identity.trim()
        if (trimmed.isEmpty()) return
        try {
            val summary = marmotIo { login(trimmed, MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays) }
            refreshAccounts()
            setActiveAccount(summary.label)
            refreshLocalNotificationSettings()
            phase = AppPhase.Ready
            present(R.string.toast_identity_imported)
            warmProfile(summary.accountIdHex)
        } catch (error: Throwable) {
            rethrowIfCancellation(error)
            present(R.string.toast_couldnt_import_identity, AppText.Plain(error.readableMessage()))
        }
    }

    suspend fun refreshAccounts() {
        val refreshedAccounts = marmotIo { listAccounts() }
        accounts = refreshedAccounts
        refreshAccountUnreadCounts(refreshedAccounts)
    }

    fun unreadCountForAccount(accountRef: String): ULong = accountUnreadCounts[accountRef] ?: 0uL

    internal fun updateAccountUnreadCount(
        accountRef: String?,
        unreadCount: ULong,
    ) {
        val ref = accountRef?.takeIf { it.isNotBlank() } ?: return
        accountUnreadCounts = accountUnreadCounts + (ref to unreadCount)
    }

    private suspend fun refreshAccountUnreadCounts(accountSummaries: List<AccountSummaryFfi> = accounts) {
        val localSigning = accountSummaries.filter { it.localSigning && it.label.isNotBlank() }
        if (localSigning.isEmpty()) {
            accountUnreadCounts = emptyMap()
            return
        }
        val previous = accountUnreadCounts
        val refreshedCounts =
            runCatching {
                marmotIo {
                    val byHex = accountUnreadSummary().associateBy { it.accountIdHex }
                    localSigning.associate { summary ->
                        summary.label to (byHex[summary.accountIdHex]?.unreadCount ?: previous[summary.label] ?: 0uL)
                    }
                }
            }.onFailure {
                appStateDebug(it) { "account unread refresh failed: ${it.readableMessage()}" }
            }.getOrNull()
                ?: return
        accountUnreadCounts = refreshedCounts
    }

    /**
     * Refresh the unread count for a single account, merging the result into
     * [accountUnreadCounts] without disturbing the other accounts' counts.
     *
     * On a per-notification hot path we only ever need the one account that
     * changed, so we read that account's durable chat-list rows directly via
     * [Marmot.chatList] and fold them with [accountUnreadCount] (which excludes
     * archived chats, matching `account_unread_total`). This avoids fanning out
     * an all-account [Marmot.accountUnreadSummary] scan on every update (#473).
     */
    private suspend fun refreshAccountUnreadCount(accountRef: String) {
        val ref = accountRef.takeIf { it.isNotBlank() } ?: return
        // Only local-signing accounts are tracked in accountUnreadCounts; skip
        // refs we don't know about (matches refreshAccountUnreadCounts' filter).
        if (accounts.none { it.localSigning && it.label == ref }) return
        val unreadCount =
            runCatching {
                marmotIo { accountUnreadCount(chatList(ref, includeArchived = true)) }
            }.onFailure {
                appStateDebug(it) { "account unread refresh failed for ${ref.take(8)}: ${it.readableMessage()}" }
            }.getOrNull()
                ?: return
        accountUnreadCounts = accountUnreadCounts + (ref to unreadCount)
    }

    suspend fun setActiveAccount(label: String) {
        // Account switch: drop in-process plaintext so account A's bytes
        // aren't reachable from account B's UI loops, but keep L2 (disk)
        // intact. The disk cache key is `mediaCacheKey(account, msg)`, so
        // switching to B can never read A's files — and switching BACK to
        // A re-hydrates L1 from L2 with a single file read instead of a
        // re-download. Sign-out (signOutActiveAccount) is what actually
        // wipes disk; switching is just a UI context flip.
        // Skip the wipe when the label is unchanged (no-op tap on the
        // already-active account).
        if (label != activeAccountRef) {
            clearInMemoryMediaCaches()
            clearCrossAccountCaches()
        }
        val target = accounts.firstOrNull { it.label == label }
        if (target?.signedOut == true) {
            runCatching {
                marmotIo { signInAccount(label) }
            }.onFailure {
                rethrowIfCancellation(it)
                present(R.string.toast_couldnt_sign_in_account, AppText.Plain(it.readableMessage()))
                return
            }
            refreshAccounts()
        }
        activeAccountRef = label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, label).apply()
        reloadMediaAutoDownloadMatrix()
        accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        configurePrivacyRuntime()
        refreshLocalNotificationSettings()
        syncNativePushRegistrationIfEnabled()
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
        mediaPlaintextCache.clear()
        mediaThumbnailCache.clear()
        mediaUploadSessionEpoch.incrementAndGet()
        // Uploads run on the app-lifetime mutation scope so they can survive
        // conversation-screen disposal. Account switch/sign-out is different:
        // cancel those old-account sends before dropping the retained bytes so
        // a cancelled upload cannot resume against an emptied retained-upload
        // map and falsely mark the bubble Failed (or publish after the switch).
        inFlightMediaUploads.cancelAll()
        // The four per-conversation maps below hold (or potentially hold)
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
     * decrypted voice/video plaintext the conversation UI materializes under
     * cacheDir. Used at sign-out, when we treat that footprint as ending with
     * the session. Re-opening a chat after the next sign-in re-downloads from
     * Blossom.
     *
     * Suspends so the sign-out flow can await completion rather than racing a
     * fast re-sign-in against an unfinished wipe. `shared_media` is left to its
     * age-based janitor on purpose: those files can back a live external
     * "open with"/share reader and deleting them out from under it would break
     * an in-progress share.
     */
    private suspend fun wipeDecryptedMediaFromDisk() {
        withContext(Dispatchers.IO) {
            // Each target holds decrypted plaintext, so wipe them independently
            // and best-effort: a failure in one (IO error, locked file) must not
            // skip the others, and a swallowed failure should still be visible.
            runCatching { diskMediaCache.clear() }
                .onFailure {
                    rethrowIfCancellation(it)
                    appStateDebug { "disk media cache wipe failed: ${it.readableMessage()}" }
                }
            runCatching { java.io.File(appContext.cacheDir, dev.ipf.darkmatter.media.MediaCacheDirs.VOICE).deleteRecursively() }
                .onFailure {
                    rethrowIfCancellation(it)
                    appStateDebug { "voice attachment wipe failed: ${it.readableMessage()}" }
                }
            runCatching { java.io.File(appContext.cacheDir, dev.ipf.darkmatter.media.MediaCacheDirs.VIDEO).deleteRecursively() }
                .onFailure {
                    rethrowIfCancellation(it)
                    appStateDebug { "video attachment wipe failed: ${it.readableMessage()}" }
                }
        }
    }

    /**
     * Drop per-account identity/metadata caches so account A's data isn't
     * reachable after switching to B, and so they don't grow unbounded across
     * many account switches: the npub cache, resolved profile presentations,
     * and group-member snapshots. Bumps [profileRevision] so any visible
     * profile re-resolves for the now-active account. Called on account switch
     * and sign-out.
     */
    private fun clearCrossAccountCaches() {
        profileCacheEpoch.incrementAndGet()
        npubs.clear()
        synchronized(profilePresentationLock) {
            profilePresentations.clear()
            userProfiles.clear()
            materializingProfiles.clear()
        }
        synchronized(groupMemberSnapshotLock) { groupMemberSnapshots.clear() }
        profileRevision += 1
    }

    suspend fun signOutActiveAccount(): SignOutOutcomeFfi? {
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
        clearInMemoryMediaCaches()
        AvatarImageLoader.clear()
        clearCrossAccountCaches()
        val engineOutcome =
            runCatching {
                marmotIo { signOut(signedOutRef, deleteKeyPackages = true) }
            }.onFailure {
                rethrowIfCancellation(it)
                appStateDebug(it) { "signOut failed account=${signedOutRef.take(8)}: ${it.readableMessage()}" }
                present(R.string.toast_couldnt_sign_out, AppText.Plain(it.readableMessage()))
            }.getOrNull()
                ?: return null
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
        // Tell the runtime to forget the signed-out account's push
        // registration before refreshing visible settings. Otherwise the
        // MIP-05 server keeps wrapping wake messages for an account that
        // can no longer decrypt them on this device. Best-effort: a
        // failure here doesn't block sign-out, the next foreground sync
        // will retry.
        runCatching { marmotIo { setNativePushEnabled(signedOutRef, false) } }
            .onSuccess { pushTokenStore.clearPendingDisable(signedOutRef) }
            .onFailure {
                rethrowIfCancellation(it)
                // Runtime flag stays enabled; queue the disable so the sync skips this account and retries it.
                pushTokenStore.recordPendingDisable(signedOutRef)
                appStateDebug { "setNativePushEnabled(false) failed on sign-out; queued disable retry: ${it.readableMessage()}" }
            }
        clearPushRegistrationForAccount(signedOutRef)
        // Drop the cached FCM token only when no accounts remain on the
        // device — other identities still need it on multi-account switch.
        if (next == null) pushTokenStore.clear()
        refreshLocalNotificationSettings()
        return engineOutcome
    }

    suspend fun exportActiveAccountNsec(): String? {
        val accountRef = activeAccountRef ?: return null
        return runCatching {
            marmotIo { revealNsec(accountRef) }
        }.onFailure {
            rethrowIfCancellation(it)
            present(R.string.toast_couldnt_export_nsec, AppText.Plain(it.readableMessage()))
        }.getOrNull()
    }

    suspend fun exportActiveAccountEncryptedSecretKey(passphrase: String): String? {
        val accountRef = activeAccountRef ?: return null
        return runCatching {
            marmotIo { exportEncryptedSecretKey(accountRef, passphrase) }
        }.onFailure {
            rethrowIfCancellation(it)
            present(R.string.toast_couldnt_export_encrypted_secret, AppText.Plain(it.readableMessage()))
        }.getOrNull()
    }

    /**
     * Destructive sign-out: leave MLS groups, delete relay KeyPackages, and
     * wipe all local state for the active account via Marmot's
     * [dev.ipf.marmotkit.Marmot.signOutAndWipe]. Returns the structured
     * outcome so the UI can surface partial failures.
     */
    suspend fun signOutAndWipeActiveAccount(): WipeOutcomeFfi? {
        val wipedRef = activeAccountRef ?: return null
        clearInMemoryMediaCaches()
        AvatarImageLoader.clear()
        clearCrossAccountCaches()
        val outcome =
            runCatching {
                marmotIo { signOutAndWipe(wipedRef) }
            }.onFailure {
                rethrowIfCancellation(it)
                appStateDebug(it) { "signOutAndWipe failed account=${wipedRef.take(8)}: ${it.readableMessage()}" }
            }.getOrNull()
                ?: return null
        wipeDecryptedMediaFromDisk()
        pushTokenStore.clearPendingDisable(wipedRef)
        pushTokenStore.clear()
        val refreshedAccounts = runCatching { marmotIo { listAccounts() } }.getOrDefault(emptyList())
        accounts = refreshedAccounts
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
        refreshLocalNotificationSettings()
        return outcome
    }

    suspend fun accountRelayLists(): AccountRelayListsFfi? {
        val account = activeAccountRef ?: return null
        return runCatching { marmotIo { accountRelayLists(account) } }.getOrNull()
    }

    suspend fun setAccountRelays(
        kind: RelayListKind,
        relays: List<String>,
    ): AccountRelayListsFfi? {
        val account = activeAccountRef ?: return null
        val candidates = relays.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (candidates.any { !isAcceptableRelayUrl(it) }) {
            present(R.string.toast_relay_update_failed, R.string.error_remove_invalid_relay_urls_first)
            return accountRelayLists()
        }
        val next = normalizeRelayUrls(candidates)
        if (next.isEmpty()) {
            present(R.string.toast_relay_list_empty)
            return accountRelayLists()
        }
        return runCatching {
            marmotIo {
                when (kind) {
                    RelayListKind.Nip65 -> setAccountNip65Relays(account, next, MarmotClient.bootstrapRelays)
                    RelayListKind.Inbox -> setAccountInboxRelays(account, next, MarmotClient.bootstrapRelays)
                }
            }
        }.onSuccess {
            present(R.string.toast_relay_list_updated)
        }.onFailure {
            rethrowIfCancellation(it)
            present(R.string.toast_relay_update_failed, AppText.Plain(it.readableMessage()))
        }.getOrNull()
    }

    fun bootstrapRelayCount(): Int = MarmotClient.bootstrapRelays.size

    suspend fun fetchKeyPackages(refreshFromNetwork: Boolean = false): List<AccountKeyPackageFfi> {
        val account = activeAccountRef ?: return emptyList()
        return runCatching {
            val bootstrapRelays = if (refreshFromNetwork) MarmotClient.bootstrapRelays else emptyList()
            marmotIo { accountKeyPackages(account, bootstrapRelays) }
        }.getOrElse {
            if (it is CancellationException) throw it
            present(R.string.toast_couldnt_load_key_packages, AppText.Plain(it.readableMessage()))
            emptyList()
        }
    }

    suspend fun deleteKeyPackage(
        eventIdHex: String,
        sourceRelays: List<String>,
    ): Boolean {
        val account = activeAccountRef ?: return false
        val relays = normalizeRelayUrls(sourceRelays)
        return runCatching {
            marmotIo { deleteAccountKeyPackage(account, eventIdHex, relays) }
            present(R.string.toast_key_package_deleted)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            present(R.string.toast_couldnt_delete_key_package, AppText.Plain(it.readableMessage()))
            false
        }
    }

    suspend fun publishNewKeyPackage(): Boolean {
        val account = activeAccountRef ?: return false
        return runCatching {
            marmotIo { publishNewKeyPackage(account) }
            present(R.string.toast_new_key_package_published)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            present(R.string.toast_couldnt_publish_key_package, AppText.Plain(it.readableMessage()))
            false
        }
    }

    suspend fun republishKeyPackage(): Boolean {
        val account = activeAccountRef ?: return false
        return runCatching {
            marmotIo { republishKeyPackage(account) }
            present(R.string.toast_key_package_republished)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            present(R.string.toast_couldnt_republish_key_package, AppText.Plain(it.readableMessage()))
            false
        }
    }

    fun updateDeveloperMode(enabled: Boolean) {
        developerMode = enabled
        preferences.edit().putBoolean(DEVELOPER_MODE_KEY, enabled).apply()
    }

    fun updateForceIncognitoKeyboard(enabled: Boolean) {
        forceIncognitoKeyboard = enabled
        preferences.edit().putBoolean(FORCE_INCOGNITO_KEYBOARD_KEY, enabled).apply()
    }

    suspend fun refreshSecurityPrivacySettings() {
        relayTelemetrySettings = runCatching { marmotIo { relayTelemetrySettings() } }.getOrNull()
        auditLogSettings = runCatching { marmotIo { auditLogSettings() } }.getOrNull()
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
            present(R.string.toast_security_privacy_updated)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            present(R.string.toast_couldnt_update_security_privacy, AppText.Plain(it.readableMessage()))
            false
        }

    suspend fun setAuditLogsEnabled(enabled: Boolean): Boolean =
        runCatching {
            // setAuditLogSettings now applies the switch to every live session
            // in place via a recorder hot-swap (enable → live recorder,
            // disable → flush + close); no session reopen or runtime restart
            // required on the host side.
            val updated = marmotIo { setAuditLogSettings(AuditLogSettingsFfi(enabled = enabled)) }
            auditLogSettings = updated
            present(R.string.toast_security_privacy_updated)
            true
        }.getOrElse {
            if (it is CancellationException) throw it
            present(R.string.toast_couldnt_update_security_privacy, AppText.Plain(it.readableMessage()))
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
                    present(R.string.toast_couldnt_delete_audit_logs, AppText.Plain(it.readableMessage()))
                    return false
                }
        if (files.isEmpty()) {
            present(R.string.toast_no_audit_logs_to_delete)
            return false
        }
        var anyDeleted = false
        for (file in files) {
            val outcome =
                runCatching { marmotIo { deleteAuditLogFile(file.path) } }
                    .onFailure {
                        if (it is CancellationException) throw it
                        appStateDebug { "deleteAuditLogFile failed: ${it.readableMessage()}" }
                    }.getOrNull() ?: continue
            anyDeleted = true
            appStateDebug { "audit log deleted still_recording=${outcome.stillRecording}" }
        }
        present(
            if (anyDeleted) R.string.toast_audit_logs_deleted else R.string.toast_couldnt_delete_audit_logs,
        )
        return anyDeleted
    }

    fun updateThemeMode(mode: AppThemeMode) {
        themeMode = mode
        preferences.edit().putString(THEME_MODE_KEY, mode.preferenceValue).apply()
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
        preferences.edit().putString(mediaAutoDownloadPrefKey(activeAccountRef), updated.toPreference()).apply()
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
     * Every [MediaAutoDownloadNetwork] the active connection currently matches.
     * A single connection can match several at once (e.g. cellular that is both
     * roaming and metered). An empty set (no/unknown connection) makes the
     * decision conservatively fall to "do not auto-download".
     */
    private fun activeNetworkTypes(): Set<MediaAutoDownloadNetwork> {
        val cm =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return emptySet()
        val network = cm.activeNetwork ?: return emptySet()
        val caps = cm.getNetworkCapabilities(network) ?: return emptySet()
        val types = mutableSetOf<MediaAutoDownloadNetwork>()
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
            types += MediaAutoDownloadNetwork.WiFi
        }
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
            types += MediaAutoDownloadNetwork.Mobile
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                types += MediaAutoDownloadNetwork.Roaming
            }
        }
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            types += MediaAutoDownloadNetwork.Metered
        }
        return types
    }

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
        val key = mediaAutoDownloadPrefKey(accountRef)
        val stored = preferences.getString(key, null)
        if (stored != null) return MediaAutoDownloadMatrix.fromPreference(stored)
        // Only consume the legacy global key once a real account is bound, so
        // the migrated value lands on the user's account rather than the
        // transient pre-bootstrap "default" bucket.
        val seeded = if (account != null) migratedDefaultMatrix() else MediaAutoDownloadMatrix.DEFAULT
        preferences.edit().putString(key, seeded.toPreference()).apply()
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

    private fun mediaAutoDownloadPrefKey(accountRef: String?): String {
        val account = accountRef?.let { ref -> accounts.firstOrNull { it.label == ref }?.accountIdHex }
        return "$MEDIA_AUTO_DOWNLOAD_MATRIX_KEY_PREFIX${account ?: "default"}"
    }

    fun updateLanguageTag(tag: String) {
        val normalized = tag.trim()
        languageTag = normalized
        preferences.edit().putString(LANGUAGE_TAG_KEY, normalized).apply()
        applyLanguageTag(normalized)
    }

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
        if (foreground) {
            refreshLocalNotificationPermission()
            notificationScope.launch { catchUpAfterForegroundActivation() }
        }
        if (foreground && backgroundConnectionEnabled) startBackgroundConnectionService()
        if (foreground) notificationScope.launch { syncNativePushRegistrationIfEnabled() }
    }

    fun setActiveConversation(groupIdHex: String?) {
        activeConversationGroupIdHex = groupIdHex
        // The chat screen always runs under the active account, so capture it
        // now; clear it when the conversation closes.
        activeConversationAccountRef = if (groupIdHex != null) activeAccountRef else null
        if (groupIdHex != null) {
            synchronized(conversationStateLock) {
                promoteConversationState(activeConversationAccountRef, groupIdHex)
            }
        }
        appStateDebug {
            "active conversation=${groupIdHex?.take(8) ?: "<none>"} account=${activeConversationAccountRef?.take(8) ?: "<none>"}"
        }
    }

    fun dismissConversationNotifications(
        accountRef: String,
        groupIdHex: String,
    ) {
        runCatching {
            localNotificationPresenter.dismissConversationMessages(accountRef, groupIdHex)
        }.onFailure {
            appStateDebug { "notification dismiss failed group=${groupIdHex.take(8)}" }
        }
    }

    fun refreshLocalNotificationPermission() {
        localNotificationPermissionGranted = localNotificationPresenter.canPostNotifications()
    }

    suspend fun refreshLocalNotificationSettings() {
        val account = activeAccountRef
        localNotificationSettings =
            if (account == null) {
                null
            } else {
                runCatching {
                    marmotIo { notificationSettings(account) }
                }.getOrNull()
            }
    }

    suspend fun sendNotificationReply(
        accountRef: String,
        groupIdHex: String,
        text: String,
    ): Boolean {
        val account = accountRef.takeIf { it.isNotBlank() } ?: return false
        val group = groupIdHex.takeIf { it.isNotBlank() } ?: return false
        val body = text.trim().takeIf { it.isNotEmpty() } ?: return false
        return runCatching {
            marmotIo { sendText(account, group, body) }
            true
        }.onFailure {
            rethrowIfCancellation(it)
            Log.w("DMAppState", "notification reply failed for group=${group.take(8)}", it)
        }.getOrDefault(false)
    }

    suspend fun markNotificationMessageRead(
        accountRef: String,
        groupIdHex: String,
        messageIdHex: String,
    ): Boolean {
        val account = accountRef.takeIf { it.isNotBlank() } ?: return false
        val group = groupIdHex.takeIf { it.isNotBlank() } ?: return false
        val message = messageIdHex.takeIf { ConversationController.HEX_MESSAGE_ID.matches(it) } ?: return false
        return runCatching {
            marmotIo { markTimelineMessageRead(account, group, message) }
            true
        }.onFailure {
            rethrowIfCancellation(it)
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
            present(if (enabled) R.string.toast_local_notifications_enabled else R.string.toast_local_notifications_disabled)
            true
        }.getOrElse {
            rethrowIfCancellation(it)
            present(R.string.toast_couldnt_update_notifications, AppText.Plain(it.readableMessage()))
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
                    present(R.string.toast_couldnt_enable_notifications, AppText.Plain(it.readableMessage()))
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
            present(R.string.toast_couldnt_keep_connected, R.string.toast_android_blocked_foreground_service)
            return false
        }
        present(if (enabled) R.string.toast_background_connection_enabled else R.string.toast_background_connection_disabled)
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
        if (!backgroundConnectionEnabled) return
        updateBackgroundConnectionPreference(false)
        present(R.string.toast_couldnt_keep_connected, R.string.toast_android_blocked_foreground_service)
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
     * [BuildConfig.DARKMATTER_PUSH_SERVER_PUBKEY_HEX], on emulators without
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
     * by [dev.ipf.darkmatter.notifications.MarmotFirebaseMessagingService] on
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
     * something changes.
     */
    suspend fun syncNativePushRegistrationIfEnabled() {
        // Drain before resolving the push-server config so a clear that
        // failed earlier still retries even if the config is later blanked
        // or GMS is uninstalled — otherwise a stale server-side registration
        // would keep wrapping wake events for a device that can no longer
        // receive them. Only the upsert path is gated on config + GMS.
        drainPendingPushClears()
        drainPendingPushDisables()
        val config = PushServerConfig.current() ?: return
        if (!isNativePushAvailable(config)) return
        val accountRefs = accounts.map { it.label }
        if (accountRefs.isEmpty()) return
        val token = pushTokenStore.lastToken() ?: fetchFcmTokenOrNull() ?: return
        for (account in accountRefs) {
            syncPushForAccount(account, config, token)
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
                runCatching { marmotIo { clearPushRegistration(account) } }
                    .onFailure {
                        rethrowIfCancellation(it)
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
                runCatching { marmotIo { setNativePushEnabled(account, false) } }
                    .onFailure {
                        rethrowIfCancellation(it)
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
    ) {
        val settings = runCatching { marmotIo { notificationSettings(account) } }.getOrNull() ?: return
        if (account == activeAccountRef) localNotificationSettings = settings
        // Skip accounts with a queued sign-out disable so a stale enabled flag can't re-register them.
        if (account in pushTokenStore.pendingDisables()) return
        if (!settings.nativePushEnabled) return
        val fingerprint =
            PushFingerprint(
                platform = PushPlatformFfi.FCM,
                token = token,
                serverPubkeyHex = config.serverPubkeyHex,
                relayHint = config.relayHint,
            )
        if (perAccountSyncedFingerprints[account] == fingerprint) return
        runCatching {
            marmotIo {
                upsertPushRegistration(
                    accountRef = account,
                    platform = PushPlatformFfi.FCM,
                    rawToken = token,
                    serverPubkeyHex = config.serverPubkeyHex,
                    relayHint = config.relayHint,
                )
            }
            // Re-read settings: a concurrent `setNativePushEnabled(false)` or
            // sign-out could have flipped the flag (and cleared the cache)
            // while upsertPushRegistration was suspended. If push is no
            // longer enabled, do NOT write the fingerprint back — otherwise
            // the cache restores a stale entry and the next enable
            // short-circuits without re-registering. Roll back instead.
            val settingsAfter = runCatching { marmotIo { notificationSettings(account) } }.getOrNull()
            when {
                // Re-read failed: that's a transient error, not a disable —
                // the upsert itself succeeded, so keep the registration. Skip
                // the fingerprint write so the next sync re-verifies instead
                // of trusting a state we couldn't confirm.
                settingsAfter == null ->
                    appStateDebug { "push settings re-read failed; keeping registration account=${account.take(8)}" }
                settingsAfter.nativePushEnabled -> {
                    perAccountSyncedFingerprints[account] = fingerprint
                    appStateDebug { "push registration synced account=${account.take(8)}" }
                }
                else -> {
                    appStateDebug { "push registration raced disable; rolling back account=${account.take(8)}" }
                    // Shares the pending-clear bookkeeping: a failed rollback
                    // is queued for retry instead of stranding the account
                    // registered server-side.
                    clearPushRegistrationForAccount(account)
                }
            }
        }.onFailure {
            rethrowIfCancellation(it)
            // Drop the fingerprint on failure so the next sync retries
            // rather than assuming the registration is fresh.
            perAccountSyncedFingerprints.remove(account)
            appStateDebug { "push registration sync failed: ${it.readableMessage()}" }
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
            if (enabled) {
                // Explicit enable beats a queued sign-out disable for this account.
                pushTokenStore.clearPendingDisable(account)
                syncNativePushRegistrationIfEnabled()
            } else {
                clearPushRegistrationForAccount(account)
            }
            true
        }.getOrElse {
            rethrowIfCancellation(it)
            present(R.string.toast_couldnt_update_notifications, AppText.Plain(it.readableMessage()))
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
        perAccountSyncedFingerprints.remove(account)
        runCatching { marmotIo { clearPushRegistration(account) } }
            .onSuccess { pushTokenStore.clearPending(account) }
            .onFailure {
                rethrowIfCancellation(it)
                pushTokenStore.recordPendingClear(account)
                appStateDebug { "clearPushRegistration failed (queued for retry): ${it.readableMessage()}" }
            }
    }

    private suspend fun fetchFcmTokenOrNull(): String? {
        if (!isNativePushAvailable()) return null
        val token =
            runCatching {
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
                rethrowIfCancellation(it)
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

    suspend fun enableDefaultNotificationsIfReady(): Boolean {
        if (defaultNotificationsEnableAttempted) return false
        val account = activeAccountRef ?: return false
        refreshLocalNotificationPermission()
        if (!localNotificationPermissionGranted) return false
        markDefaultNotificationsEnableAttempted()
        if (backgroundConnectionEnabled) {
            return setBackgroundConnectionEnabled(true)
        }
        val settings = marmotIo { setLocalNotificationsEnabled(account, true) }
        localNotificationSettings = settings
        return settings.localNotificationsEnabled
    }

    fun displayName(accountIdHex: String): String {
        profileDisplayName(accountIdHex)?.let { return it }
        requestProfile(accountIdHex)
        accounts.firstOrNull { it.accountIdHex == accountIdHex }?.let {
            return it.label.ifBlank { IdentityFormatter.short(accountIdHex) }
        }
        return IdentityFormatter.short(accountIdHex)
    }

    fun chatMemberTitle(accountIdHex: String): String {
        profileDisplayName(accountIdHex)?.let { return it }
        requestProfile(accountIdHex)
        return shortNpub(accountIdHex)
    }

    private fun profileDisplayName(accountIdHex: String): String? = profilePresentation(accountIdHex).displayName

    fun shortNpub(accountIdHex: String): String {
        val npub = npub(accountIdHex)
        return IdentityFormatter.short(npub, prefix = 10, suffix = 8)
    }

    fun npub(accountIdHex: String): String {
        npubs[accountIdHex]?.let { return it }
        // npub is a pure hex→bech32 encoding of the pubkey — no storage read, so
        // no DB-lock contention, and safe to resolve inline (unlike displayName /
        // userProfile, which this change moves off the composition thread).
        // Resolving here also keeps it independent of whether a published profile
        // exists — an account with no profile metadata still gets a real npub.
        val resolved = runCatching { marmot().npub(accountIdHex) }.getOrNull() ?: return accountIdHex
        npubs[accountIdHex] = resolved
        return resolved
    }

    suspend fun accountIdHex(reference: String): String? = runCatching { marmotIo { accountIdHex(reference) } }.getOrNull()

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
            runCatching {
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

    fun requestProfile(accountIdHex: String) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        // This is called from render/timeline projection paths, so do not synchronously
        // probe the Rust profile cache here. The refresh job owns the binding work.
        if (!profileRefreshGate.tryStart(id, System.currentTimeMillis())) return
        // Snapshot the cache epoch now, before the job is queued. A switch or
        // sign-out can clear the caches in the gap before this coroutine starts,
        // so the staleness check must compare against the epoch at request time,
        // not whatever it has become by the time the body runs.
        val requestEpoch = profileCacheEpoch.get()
        profileScope.launch {
            refreshProfile(id, requestEpoch)
        }
    }

    fun requestProfiles(accountIdHexes: Iterable<String>) {
        accountIdHexes.forEach { requestProfile(it) }
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
            groupMemberSnapshots[key] = snapshot
        }
        return snapshot
    }

    suspend fun refreshProfile(
        accountIdHex: String,
        epoch: Int = profileCacheEpoch.get(),
    ) {
        val profile =
            try {
                val result =
                    runCatching {
                        marmotIo {
                            val relays =
                                activeAccountRef
                                    ?.let { runCatching { accountNip65Relays(it) }.getOrNull() }
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: MarmotClient.bootstrapRelays
                            refreshProfile(accountIdHex, relays)
                            userProfile(accountIdHex)
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
            val displayName =
                marmotIo { runCatching { marmot().displayName(accountIdHex) }.getOrNull() }
                    ?.let { ProfileSanitizer.displayName(it) }
            val presentation =
                ProfilePresentation(
                    displayName = displayName,
                    avatarUrl = ProfileSanitizer.imageUrl(profile.picture),
                )
            // Drop the result if an account switch / sign-out cleared the caches
            // while this refresh was in flight, so we don't repopulate them with
            // the previous account's data.
            if (profileCacheEpoch.get() == epoch) {
                synchronized(profilePresentationLock) { userProfiles[accountIdHex] = profile }
                applyProfilePresentation(accountIdHex, presentation)
            }
        }
    }

    fun presentProfilePayload(raw: String): Boolean {
        val link = ProfileLink.parse(raw) ?: return false
        pendingProfileNpub = link.npub
        return true
    }

    fun presentProfile(npub: String) {
        pendingProfileNpub = npub
    }

    /**
     * Display name for a markdown mention entity (its bare bech32, npub form).
     * Null when the reference doesn't normalize to a pubkey or the profile is
     * unknown, so the renderer keeps its shortened-bech32 fallback. A miss
     * schedules a relay profile fetch; the profile-revision read inside
     * [profilePresentation] re-renders observers when the name lands.
     * (nprofile mentions return null for now: the Rust `accountIdHex`
     * normalizer accepts npub/hex but not nprofile TLVs.)
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

    private fun nostrEntityAccountIdHex(bech32: String): String? = runCatching { marmot().accountIdHex(bech32.trim()) }.getOrNull()

    /**
     * Public bech32 (npub/nprofile) → hex pubkey resolver for the renderer's
     * self-mention detection (#414). Pure FFI encoding (no storage read), so
     * it's safe to call from the receiver-bubble path; returns null when the
     * reference doesn't normalize to a pubkey.
     */
    fun accountIdHexForMention(bech32: String): String? = nostrEntityAccountIdHex(bech32)

    fun clearPresentedProfile() {
        pendingProfileNpub = null
    }

    /**
     * Create a 1:1 DM group with [npub] and return its group id hex, or null on
     * failure (a toast explains why). Caller can open the new chat once the
     * chat-list projection surfaces it — see [awaitChatListItem].
     */
    suspend fun startProfileChat(npub: String): String? {
        val account = activeAccountRef ?: return null
        return runCatching {
            marmotIo { createGroup(account, "", listOf(npub), null) }
        }.getOrElse {
            rethrowIfCancellation(it)
            present(R.string.toast_couldnt_start_chat, AppText.Plain(it.readableMessage()))
            null
        }
    }

    /**
     * Suspend until the chat list materializes [groupIdHex] (a freshly created
     * group surfaces a beat after `createGroup` returns, via the worker's
     * recompute), or null if it doesn't within [timeoutMs].
     */
    suspend fun awaitChatListItem(
        groupIdHex: String,
        timeoutMs: Long = 5000,
    ): ChatListItem? =
        withTimeoutOrNull(timeoutMs) {
            // Poll the cheap membership probe; only project the row into a
            // ChatListItem once it's actually present, instead of re-projecting
            // the entire chat list on every tick.
            while (chatsController?.containsGroup(groupIdHex) != true) {
                delay(50)
            }
            chatsController?.chatItemForGroup(groupIdHex)
        }

    suspend fun publishProfile(profile: UserProfileMetadataFfi) {
        val account = activeAccountRef ?: return
        runCatching {
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
            presentText(
                AppText.Resource(R.string.toast_profile_published),
                AppText.Resource(R.string.toast_profile_published_detail, listOf(profileRelayCount)),
            )
        }.onFailure {
            rethrowIfCancellation(it)
            present(R.string.toast_couldnt_publish_profile, AppText.Plain(it.readableMessage()))
        }
    }

    fun present(
        title: String,
        detail: String? = null,
    ) {
        presentText(AppText.Plain(title), detail?.let { AppText.Plain(it) })
    }

    fun present(
        @StringRes titleRes: Int,
    ) {
        presentText(AppText.Resource(titleRes))
    }

    fun present(
        @StringRes titleRes: Int,
        @StringRes detailRes: Int,
    ) {
        presentText(AppText.Resource(titleRes), AppText.Resource(detailRes))
    }

    fun present(
        @StringRes titleRes: Int,
        detail: AppText,
    ) {
        presentText(AppText.Resource(titleRes), detail)
    }

    fun presentText(
        title: AppText,
        detail: AppText? = null,
    ) {
        toast = ToastMessage(title, detail)
    }

    fun clearToast() {
        toast = null
    }

    private suspend fun configurePrivacyRuntime() {
        val accountLabelForRuntime = activeAccountRef
        runCatching {
            withContext(Dispatchers.IO) {
                marmot().configurePrivacyRuntime(accountLabelForRuntime)
            }
        }.onFailure {
            if (it is CancellationException) throw it
            appStateDebug(it) { "privacy runtime config failed: ${it.readableMessage()}" }
        }
    }

    private suspend fun Marmot.configurePrivacyRuntime(accountLabel: String?) {
        val installId = runCatching { telemetryInstallId() }.getOrNull().orEmpty()
        setRelayTelemetryRuntimeConfig(
            RelayTelemetryRuntimeConfigFfi(
                otlpEndpoint = BuildConfig.DARKMATTER_OTLP_ENDPOINT.nonBlankOrNull(),
                authorizationBearerToken = BuildConfig.DARKMATTER_OTLP_AUTH_TOKEN.nonBlankOrNull(),
                resource =
                    RelayTelemetryResourceFfi(
                        serviceVersion = telemetryServiceVersion(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                        serviceInstanceId = installId,
                        deploymentEnvironment = telemetryDeploymentEnvironment(BuildConfig.DARKMATTER_DEPLOYMENT_ENVIRONMENT),
                        tenant = BuildConfig.DARKMATTER_TELEMETRY_TENANT.ifBlank { "darkmatter-android" },
                        osType = "linux",
                        osVersion = Build.VERSION.RELEASE.ifBlank { Build.VERSION.SDK_INT.toString() },
                        deviceModelIdentifier = telemetryDeviceModelIdentifier(Build.MODEL),
                    ),
            ),
        )
        setAuditLogTrackerConfig(
            AuditLogTrackerConfigFfi(
                endpoint = BuildConfig.DARKMATTER_AUDIT_LOG_ENDPOINT.nonBlankOrNull(),
                authorizationBearerToken = BuildConfig.DARKMATTER_AUDIT_LOG_AUTH_TOKEN.nonBlankOrNull(),
                source =
                    AuditLogUploadSourceFfi(
                        accountLabel = accountLabel.nonBlankOrNull(),
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

    // Resolve the incoming sender's name the same way chat surfaces do:
    // cached profile / contact display name first, then an npub. The FFI
    // notification payload's own displayName is frequently null even when
    // the app already has a name cached for that pubkey, which is what made
    // notifications fall back to a raw hex key (#206). chatMemberTitle never
    // returns raw hex — it ends at shortNpub — so a hex key can no longer
    // leak into a notification when any name or npub is resolvable.
    private fun notificationSenderName(update: NotificationUpdateFfi): String? {
        val senderIdHex = update.sender.accountIdHex
        if (senderIdHex.isBlank()) return null
        return runCatching { chatMemberTitle(senderIdHex) }.getOrNull()
    }

    // Resolve a mention for a one-shot notification. Unlike the Compose bubble
    // path, a notification will not recompose after requestProfile() finishes,
    // so do one local display-name read before falling back to shortened bech32.
    private suspend fun notificationMentionDisplayName(bech32: String): String? {
        val accountIdHex = accountIdHex(bech32) ?: return null
        profileDisplayName(accountIdHex)?.let { return it }
        val displayName =
            marmotIo { runCatching { marmot().displayName(accountIdHex) }.getOrNull() }
                ?.let { ProfileSanitizer.displayName(it) }
        if (displayName == null) requestProfile(accountIdHex)
        return displayName
    }

    // Flatten notification body text through the same Markdown mention path used
    // by in-app bubbles/previews. A parser failure (empty document) deliberately
    // returns null so LocalNotificationFormatter falls back to the raw FFI
    // preview instead of dropping the message body.
    private suspend fun notificationPreviewText(raw: String?): String? {
        val text = raw?.takeIf { it.isNotBlank() } ?: return null
        val document = parseMarkdownOrEmpty(text)
        if (document.blocks.isEmpty()) return null
        val mentionNames = mutableMapOf<String, String?>()
        for (bech32 in markdownDocumentMentionBech32s(document)) {
            mentionNames[bech32] = notificationMentionDisplayName(bech32)
        }
        return markdownDocumentToPreviewAnnotatedString(
            document = document,
            codeStyle = SpanStyle(),
            mentionDisplayName = mentionNames::get,
        ).text.takeIf { it.isNotBlank() }
    }

    // Resolve the conversation title for a notification the same way the chat
    // list does, since the runtime payload's group name is empty for unnamed
    // groups. Returns null for DMs (MessagingStyle shows the sender instead).
    private suspend fun notificationConversationTitle(update: NotificationUpdateFfi): String? {
        if (update.isDm) return null
        // Sanitize the payload name like the display surfaces do (strip
        // bidi/control chars) before trusting it as a notification title.
        update.groupName?.let { ProfileSanitizer.displayName(it) }?.let { return it }
        val members =
            runCatching { marmotIo { groupMembers(update.accountRef, update.groupIdHex) } }
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
            memberCount = members.size,
            memberTitle = { chatMemberTitle(it) },
            copy = notificationGroupTitleCopy(),
        )
    }

    private fun notificationGroupTitleCopy(): GroupTitleCopy =
        GroupTitleCopy(
            inviteFromFormat = appContext.getString(R.string.group_title_invite_from),
            groupOfPeopleFormat = appContext.getString(R.string.group_title_people_count),
        )

    private fun startNotificationListener() {
        if (notificationJob?.isActive == true) return
        notificationJob =
            notificationScope.launch {
                // Restart the subscription on any failure (or clean end-of-stream)
                // with exponential backoff, so a transient relay/binding error
                // doesn't permanently silence notifications. Backoff resets after
                // each received update; cancellation propagates and stops the loop.
                // See #56.
                var backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
                while (isActive) {
                    try {
                        val subscription = marmotIo { subscribeNotifications() }
                        try {
                            while (isActive) {
                                val update = marmotIo { subscription.next() } ?: break
                                backoffMillis = NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS
                                val activeConversation = activeConversationGroupIdHex
                                val shouldPost =
                                    LocalNotificationPolicy.shouldPost(
                                        update = update,
                                        appInForeground = appInForeground,
                                        activeConversationGroupIdHex = activeConversation,
                                        activeConversationAccountRef = activeConversationAccountRef,
                                    )
                                appStateDebug {
                                    "notification update key=${update.notificationKey.take(16)} trigger=${update.trigger} " +
                                        "foreground=$appInForeground active=${activeConversation?.take(8) ?: "<none>"} " +
                                        "activeAccount=${activeConversationAccountRef?.take(8) ?: "<none>"} " +
                                        "updateAccount=${update.accountRef.take(8)} post=$shouldPost"
                                }
                                if (shouldPost) {
                                    localNotificationPresenter.show(
                                        update,
                                        notificationConversationTitle(update),
                                        notificationSenderName(update),
                                        notificationPreviewText(update.previewText),
                                        notificationPreviewText(update.reactedToPreview),
                                    )
                                }
                                refreshAccountUnreadCount(update.accountRef)
                            }
                        } finally {
                            runCatching {
                                withContext(Dispatchers.IO) {
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
                    if (!isActive) break
                    delay(backoffMillis)
                    backoffMillis = nextRetryBackoffMillis(backoffMillis, NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS)
                }
            }
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
        synchronized(profilePresentationLock) {
            if (profilePresentations.containsKey(id)) return
            if (!materializingProfiles.add(id)) return
        }
        val epoch = profileCacheEpoch.get()
        profileScope.launch {
            try {
                val profile = marmotIo { runCatching { marmot().userProfile(id) }.getOrNull() }
                val displayName =
                    marmotIo { runCatching { marmot().displayName(id) }.getOrNull() }
                        ?.let { ProfileSanitizer.displayName(it) }
                val presentation =
                    ProfilePresentation(
                        displayName = displayName,
                        avatarUrl = ProfileSanitizer.imageUrl(profile?.picture),
                    )
                if (profileCacheEpoch.get() == epoch) {
                    synchronized(profilePresentationLock) { profile?.let { userProfiles[id] = it } }
                    applyProfilePresentation(id, presentation)
                }
            } finally {
                synchronized(profilePresentationLock) { materializingProfiles.remove(id) }
            }
        }
    }

    /**
     * Store a freshly-resolved [presentation] and bump [profileRevision] if it
     * changed. Pure in-memory state work, no FFI — safe on the main thread.
     * The blocking FFI reads are the caller's job to run off-main (see
     * [refreshProfile]).
     */
    private fun applyProfilePresentation(
        accountIdHex: String,
        presentation: ProfilePresentation,
    ) {
        val changed =
            synchronized(profilePresentationLock) {
                profilePresentations.put(accountIdHex, presentation) != presentation
            }
        if (changed) {
            profileRevision += 1
        }
    }

    private fun notifyProfilesChanged() {
        synchronized(profilePresentationLock) {
            profilePresentations.clear()
            userProfiles.clear()
            materializingProfiles.clear()
        }
        profileRevision += 1
    }

    private fun groupMemberSnapshotKey(
        accountRef: String?,
        groupIdHex: String,
    ): String? {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
        return "$account:$groupIdHex"
    }

    private fun Throwable.readableMessage(): String = message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName

    companion object {
        private const val ACTIVE_ACCOUNT_KEY = "active_account"
        private const val DEVELOPER_MODE_KEY = "developer_mode"
        private const val FORCE_INCOGNITO_KEYBOARD_KEY = "force_incognito_keyboard"
        private const val THEME_MODE_KEY = "theme_mode"
        private const val MEDIA_AUTO_DOWNLOAD_KEY = "media_auto_download"

        // Per-account matrix prefs (issue #407), keyed by accountIdHex (or a
        // "default" bucket when no account is bound). Distinct from the legacy
        // 3-state key, which this migrates from on first per-account load.
        private const val MEDIA_AUTO_DOWNLOAD_MATRIX_KEY_PREFIX = "media_auto_download_matrix:"
        private const val MEDIA_QUALITY_KEY = "media_quality"
        private const val ENTER_KEY_BEHAVIOR_KEY = "enter_key_behavior"
        private const val DEFAULT_NOTIFICATIONS_ENABLE_ATTEMPTED_KEY = "default_notifications_enable_attempted"

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
        private const val LANGUAGE_TAG_KEY = "language_tag"
        private const val PROFILE_REFRESH_RETRY_COOLDOWN_MILLIS = 60_000L
        private const val NOTIFICATION_RETRY_INITIAL_BACKOFF_MILLIS = 1_000L
        private const val NOTIFICATION_RETRY_MAX_BACKOFF_MILLIS = 60_000L
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
    Log.e("DMAppState", message(), error)
}

private fun String?.nonBlankOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
