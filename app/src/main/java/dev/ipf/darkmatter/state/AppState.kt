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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import dev.ipf.darkmatter.BuildConfig
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.AvatarImageLoader
import dev.ipf.darkmatter.core.HostSafety
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MarmotClient
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.darkmatter.notifications.BackgroundConnectionPreferences
import dev.ipf.darkmatter.notifications.LocalNotificationPolicy
import dev.ipf.darkmatter.notifications.LocalNotificationPresenter
import dev.ipf.darkmatter.notifications.NotificationStreamForegroundService
import dev.ipf.darkmatter.notifications.PushServerConfig
import dev.ipf.darkmatter.notifications.PushTokenStore
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
import dev.ipf.marmotkit.PushPlatformFfi
import dev.ipf.marmotkit.RelayTelemetryResourceFfi
import dev.ipf.marmotkit.RelayTelemetryRuntimeConfigFfi
import dev.ipf.marmotkit.RelayTelemetrySettingsFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

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

    var activeAccountRef by mutableStateOf(preferences.getString(ACTIVE_ACCOUNT_KEY, null))
        private set

    var developerMode by mutableStateOf(preferences.getBoolean(DEVELOPER_MODE_KEY, false))
        private set

    var themeMode by mutableStateOf(AppThemeMode.fromPreference(preferences.getString(THEME_MODE_KEY, null)))
        private set

    var mediaAutoDownloadPolicy by mutableStateOf(
        MediaAutoDownloadPolicy.fromPreference(preferences.getString(MEDIA_AUTO_DOWNLOAD_KEY, null)),
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
    private val profilePresentationLock = Any()
    private val groupMemberSnapshots = mutableMapOf<String, GroupMemberSnapshot>()
    private val groupMemberSnapshotLock = Any()
    private val conversationStateLock = Any()
    private val optimisticMessagesByConversation = mutableMapOf<String, SnapshotStateMap<String, TimelineMessage>>()
    private val projectedMessageIdsByConversation = mutableMapOf<String, MutableSet<String>>()
    private val timelineOrderOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()
    private val timelineTimestampOverridesByConversation = mutableMapOf<String, MutableMap<String, ULong>>()
    private val recentConversationStateKeys = LinkedHashMap<String, Unit>(16, 0.75f, true)

    val draftStore: DraftStore = DraftStore.forContext(appContext)

    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutationsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var appInForeground = false
    private var activeConversationGroupIdHex: String? = null
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

    private fun retainConversationState(
        accountRef: String?,
        groupIdHex: String,
    ): String {
        val key = conversationKey(accountRef, groupIdHex)
        recentConversationStateKeys[key] = Unit
        while (recentConversationStateKeys.size > MAX_RETAINED_CONVERSATION_STATES) {
            val staleKey = recentConversationStateKeys.entries.first().key
            recentConversationStateKeys.remove(staleKey)
            optimisticMessagesByConversation.remove(staleKey)
            projectedMessageIdsByConversation.remove(staleKey)
            timelineOrderOverridesByConversation.remove(staleKey)
            timelineTimestampOverridesByConversation.remove(staleKey)
        }
        return key
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

    suspend fun <T> marmotIo(block: suspend Marmot.() -> T): T =
        withContext(Dispatchers.IO) {
            marmot().block()
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
        accounts = marmotIo { listAccounts() }
    }

    fun setActiveAccount(label: String) {
        // Account switch: drop in-process plaintext so account A's bytes
        // aren't reachable from account B's UI loops, but keep L2 (disk)
        // intact. The disk cache key is `mediaCacheKey(account, msg)`, so
        // switching to B can never read A's files — and switching BACK to
        // A re-hydrates L1 from L2 with a single file read instead of a
        // re-download. Sign-out (signOutActiveAccount) is what actually
        // wipes disk; switching is just a UI context flip.
        // Skip the wipe when the label is unchanged (no-op tap on the
        // already-active account).
        if (label != activeAccountRef) clearInMemoryMediaCaches()
        activeAccountRef = label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, label).apply()
        accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        notificationScope.launch {
            configurePrivacyRuntime()
            refreshLocalNotificationSettings()
            syncNativePushRegistrationIfEnabled()
        }
    }

    /**
     * Wipe in-memory media caches only — L1 plaintext, decoded thumbnails,
     * and the URL-keyed avatar LRU. Used on account switch, where the L2
     * disk cache (per-account-keyed) is deliberately preserved so the user
     * doesn't re-download every image when bouncing between accounts.
     */
    private fun clearInMemoryMediaCaches() {
        mediaPlaintextCache.clear()
        mediaThumbnailCache.clear()
        // Avatars are URL-keyed and shared across accounts; wiping on switch
        // keeps the LRU bounded without per-switch growth, at the cost of a
        // re-fetch when the user returns. Cheap relative to media bytes.
        AvatarImageLoader.clear()
    }

    /**
     * Wipe in-memory caches AND the L2 disk cache. Used at sign-out, when
     * we treat the device-side decrypted-media footprint as ending with
     * the session. Re-opening a chat after the next sign-in re-downloads
     * from Blossom.
     */
    private fun clearAllMediaCaches() {
        clearInMemoryMediaCaches()
        notificationScope.launch(Dispatchers.IO) { diskMediaCache.clear() }
    }

    fun signOutActiveAccount() {
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
        clearAllMediaCaches()
        val signedOutRef = activeAccountRef
        val outcome = signOutOutcome(accounts.map { it.label }, activeAccountRef)
        val next = outcome.nextActiveRef
        activeAccountRef = next
        preferences
            .edit()
            .apply {
                if (next == null) remove(ACTIVE_ACCOUNT_KEY) else putString(ACTIVE_ACCOUNT_KEY, next)
            }.apply()
        // Signing out the last active account must leave a usable state, not a
        // MainShell with no active account. See issue #11.
        phase = outcome.phase
        next?.let { label ->
            accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        }
        notificationScope.launch {
            // Tell the runtime to forget the signed-out account's push
            // registration before refreshing visible settings. Otherwise the
            // MIP-05 server keeps wrapping wake messages for an account that
            // can no longer decrypt them on this device. Best-effort: a
            // failure here doesn't block sign-out, the next foreground sync
            // will retry.
            if (signedOutRef != null) {
                runCatching { marmotIo { setNativePushEnabled(signedOutRef, false) } }
                    .onFailure {
                        rethrowIfCancellation(it)
                        appStateDebug { "setNativePushEnabled(false) failed on sign-out: ${it.readableMessage()}" }
                    }
                clearPushRegistrationForAccount(signedOutRef)
            }
            // Drop the cached FCM token only when no accounts remain on the
            // device — other identities still need it on multi-account switch.
            if (next == null) pushTokenStore.clear()
            refreshLocalNotificationSettings()
        }
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

    fun updateMediaAutoDownloadPolicy(policy: MediaAutoDownloadPolicy) {
        mediaAutoDownloadPolicy = policy
        preferences.edit().putString(MEDIA_AUTO_DOWNLOAD_KEY, policy.preferenceValue).apply()
    }

    /**
     * Whether an incoming attachment should be fetched/decrypted automatically
     * given the current [mediaAutoDownloadPolicy] and network metering.
     */
    fun shouldAutoDownloadMedia(): Boolean =
        when (mediaAutoDownloadPolicy) {
            MediaAutoDownloadPolicy.Always -> true
            MediaAutoDownloadPolicy.Never -> false
            MediaAutoDownloadPolicy.WifiOnly -> !isActiveNetworkMetered()
        }

    private fun isActiveNetworkMetered(): Boolean {
        val cm =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return true // Unknown → treat as metered (conservative).
        return cm.isActiveNetworkMetered
    }

    fun updateLanguageTag(tag: String) {
        val normalized = tag.trim()
        languageTag = normalized
        preferences.edit().putString(LANGUAGE_TAG_KEY, normalized).apply()
        applyLanguageTag(normalized)
    }

    fun setAppInForeground(foreground: Boolean) {
        appInForeground = foreground
        if (foreground) refreshLocalNotificationPermission()
        if (foreground && backgroundConnectionEnabled) startBackgroundConnectionService()
        if (foreground) notificationScope.launch { syncNativePushRegistrationIfEnabled() }
    }

    fun setActiveConversation(groupIdHex: String?) {
        activeConversationGroupIdHex = groupIdHex
        appStateDebug { "active conversation=${groupIdHex?.take(8) ?: "<none>"}" }
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
        val config = PushServerConfig.current() ?: return
        // Drain before the GMS gate so a token clear that failed earlier
        // still retries when Play Services becomes unavailable later — the
        // push server otherwise keeps wrapping wake events for a device
        // that can no longer receive them.
        drainPendingPushClears()
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

    private suspend fun syncPushForAccount(
        account: String,
        config: PushServerConfig,
        token: String,
    ) {
        val settings = runCatching { marmotIo { notificationSettings(account) } }.getOrNull() ?: return
        if (account == activeAccountRef) localNotificationSettings = settings
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

    fun npub(accountIdHex: String): String =
        npubs.computeIfAbsent(accountIdHex) { key ->
            runCatching { marmot().npub(key) }.getOrNull() ?: key
        }

    suspend fun accountIdHex(reference: String): String? = runCatching { marmotIo { accountIdHex(reference) } }.getOrNull()

    fun userProfile(accountIdHex: String): UserProfileMetadataFfi? {
        // Observe profile cache invalidations for Compose callers.
        profileRevision
        return cachedUserProfile(accountIdHex) ?: run {
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
        profileScope.launch {
            refreshProfile(id)
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

    suspend fun refreshProfile(accountIdHex: String) {
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
            notifyProfileChanged(accountIdHex)
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

    fun clearPresentedProfile() {
        pendingProfileNpub = null
    }

    suspend fun startProfileChat(npub: String): Boolean {
        val account = activeAccountRef ?: return false
        return runCatching {
            marmotIo { createGroup(account, "", listOf(npub), null) }
            present(R.string.toast_chat_started)
            true
        }.getOrElse {
            rethrowIfCancellation(it)
            present(R.string.toast_couldnt_start_chat, AppText.Plain(it.readableMessage()))
            false
        }
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

    fun shutdown() {
        notificationJob?.cancel()
        runCatching { client?.marmot?.close() }
        profileScope.cancel()
        mutationsScope.cancel()
        notificationScope.cancel()
    }

    private suspend fun restartMarmotRuntime() {
        val previousNotificationJob = notificationJob
        notificationJob = null
        val previousClient = client
        val accountLabelForRuntime = activeAccountRef

        appStateDebug { "marmot runtime reopening" }
        previousNotificationJob?.cancelAndJoin()
        val opened =
            try {
                withContext(Dispatchers.IO) {
                    previousClient?.marmot?.close()
                    MarmotClient(appContext).also { nextClient ->
                        nextClient.marmot.configurePrivacyRuntime(accountLabelForRuntime)
                        nextClient.marmot.start()
                    }
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                client = null
                phase = AppPhase.Failed(error.readableMessage())
                throw error
            }

        client = opened
        runtimeGeneration += 1
        localNotificationPresenter.ensureChannels()
        refreshLocalNotificationPermission()
        startNotificationListener()
        refreshSecurityPrivacySettings()
        refreshAccounts()
        if (accounts.isEmpty()) {
            localNotificationSettings = null
            phase = AppPhase.Onboarding
        } else {
            if (activeAccountRef == null || accounts.none { it.label == activeAccountRef }) {
                setActiveAccount(accounts.first().label)
            }
            refreshLocalNotificationSettings()
            phase = AppPhase.Ready
            activeAccount?.accountIdHex?.let { warmProfile(it) }
        }
        appStateDebug {
            "marmot runtime reopened accounts=${accounts.size} active=${activeAccountRef?.take(8) ?: "<none>"}"
        }
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
                                    )
                                appStateDebug {
                                    "notification update key=${update.notificationKey.take(16)} trigger=${update.trigger} " +
                                        "foreground=$appInForeground active=${activeConversation?.take(8) ?: "<none>"} post=$shouldPost"
                                }
                                if (shouldPost) {
                                    localNotificationPresenter.show(update)
                                }
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

    private fun cachedUserProfile(accountIdHex: String): UserProfileMetadataFfi? = runCatching { marmot().userProfile(accountIdHex) }.getOrNull()

    private fun profilePresentation(accountIdHex: String): ProfilePresentation {
        profileRevision
        synchronized(profilePresentationLock) {
            profilePresentations[accountIdHex]?.let { return it }
        }
        val presentation = readProfilePresentation(accountIdHex)
        synchronized(profilePresentationLock) {
            profilePresentations[accountIdHex] = presentation
        }
        return presentation
    }

    private fun readProfilePresentation(accountIdHex: String): ProfilePresentation {
        val displayName =
            runCatching { marmot().displayName(accountIdHex) }
                .getOrNull()
                ?.let { ProfileSanitizer.displayName(it) }
        val avatarUrl = ProfileSanitizer.imageUrl(cachedUserProfile(accountIdHex)?.picture)
        return ProfilePresentation(displayName = displayName, avatarUrl = avatarUrl)
    }

    private fun notifyProfileChanged(accountIdHex: String) {
        val presentation = readProfilePresentation(accountIdHex)
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
        private const val THEME_MODE_KEY = "theme_mode"
        private const val MEDIA_AUTO_DOWNLOAD_KEY = "media_auto_download"
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
