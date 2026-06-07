package dev.ipf.darkmatter.state

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.R
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MarmotClient
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import dev.ipf.darkmatter.notifications.BackgroundConnectionPreferences
import dev.ipf.darkmatter.notifications.LocalNotificationPolicy
import dev.ipf.darkmatter.notifications.LocalNotificationPresenter
import dev.ipf.darkmatter.notifications.NotificationStreamForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ipf.marmotkit.AccountKeyPackageFfi
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.NotificationSettingsFfi
import dev.ipf.marmotkit.UserProfileMetadataFfi
import java.net.IDN
import java.net.URI
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

sealed interface AppPhase {
    data object Bootstrapping : AppPhase
    data object Onboarding : AppPhase
    data object Ready : AppPhase
    data class Failed(val message: String) : AppPhase
}

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
    KeyPackage,
}

internal fun normalizeRelayUrls(relays: Iterable<String>): List<String> {
    return relays
        .mapNotNull(::canonicalRelayUrl)
        .distinct()
}

internal fun isAcceptableRelayUrl(url: String): Boolean {
    return canonicalRelayUrl(url) != null
}

private fun canonicalRelayUrl(url: String): String? {
    return runCatching {
        val uri = URI(url.trim())
        if (uri.scheme?.equals("wss", ignoreCase = true) != true || uri.userInfo != null) {
            return@runCatching null
        }
        val host = uri.host ?: uri.rawAuthority?.relayHostCandidate() ?: return@runCatching null
        val hostWithoutBrackets = host.removeSurrounding("[", "]")
        if (hostWithoutBrackets.any { it.isWhitespace() }) return@runCatching null
        val asciiHost = if (hostWithoutBrackets.contains(":")) {
            hostWithoutBrackets
        } else {
            IDN.toASCII(hostWithoutBrackets)
        }
        val canonicalHost = asciiHost.lowercase(Locale.ROOT).takeIf { it.isNotBlank() }
            ?: return@runCatching null
        val authorityHost = if (canonicalHost.contains(":")) "[$canonicalHost]" else canonicalHost
        val port = uri.port.takeIf { it >= 0 }?.let { ":$it" }.orEmpty()
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

class DarkMatterAppState(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("darkmatter", Context.MODE_PRIVATE)

    /**
     * App-lifetime cache of decrypted attachment bytes, keyed by the globally
     * unique `messageIdHex`. Lives here (not on the per-conversation
     * controller) so re-opening a chat doesn't re-download media already
     * fetched this session. Bounded in bytes; see [dev.ipf.darkmatter.media.ByteSizeLruCache].
     */
    internal val mediaPlaintextCache = dev.ipf.darkmatter.media.ByteSizeLruCache<String, ByteArray>(
        maxBytes = MEDIA_PLAINTEXT_CACHE_MAX_BYTES,
        sizeOf = { it.size },
    )

    /**
     * App-lifetime cache of *decoded* attachment thumbnails (sampled bitmaps),
     * keyed identically to [mediaPlaintextCache]. Lets a bubble render its
     * image on the first frame — no decode spinner — for anything already
     * fetched/sent this session. Bounded by approximate pixel bytes.
     */
    internal val mediaThumbnailCache = dev.ipf.darkmatter.media.ByteSizeLruCache<String, android.graphics.Bitmap>(
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
    internal val diskMediaCache = dev.ipf.darkmatter.media.DiskByteCache(
        cacheDir = java.io.File(appContext.cacheDir, "decrypted-media"),
        maxBytes = DISK_MEDIA_CACHE_MAX_BYTES,
    )

    private var client: MarmotClient? = null
    private val localNotificationPresenter = LocalNotificationPresenter(appContext)

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

    var localNotificationPermissionGranted by mutableStateOf(localNotificationPresenter.canPostNotifications())
        private set

    var backgroundConnectionEnabled by mutableStateOf(BackgroundConnectionPreferences.isEnabled(appContext))
        private set

    private val npubs = ConcurrentHashMap<String, String>()
    private var profileRevision by mutableStateOf(0)
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
    fun setDraft(groupIdHex: String, text: String) {
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

    internal fun optimisticMessages(accountRef: String?, groupIdHex: String): SnapshotStateMap<String, TimelineMessage> {
        return synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            optimisticMessagesByConversation.getOrPut(key) { mutableStateMapOf() }
        }
    }

    internal fun projectedMessageIds(accountRef: String?, groupIdHex: String): MutableSet<String> {
        return synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            projectedMessageIdsByConversation.getOrPut(key) { mutableSetOf() }
        }
    }

    internal fun timelineOrderOverrides(accountRef: String?, groupIdHex: String): MutableMap<String, ULong> {
        return synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            timelineOrderOverridesByConversation.getOrPut(key) { mutableMapOf() }
        }
    }

    internal fun timelineTimestampOverrides(accountRef: String?, groupIdHex: String): MutableMap<String, ULong> {
        return synchronized(conversationStateLock) {
            val key = retainConversationState(accountRef, groupIdHex)
            timelineTimestampOverridesByConversation.getOrPut(key) { mutableMapOf() }
        }
    }

    private fun retainConversationState(accountRef: String?, groupIdHex: String): String {
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

    private fun conversationKey(accountRef: String?, groupIdHex: String): String {
        return "${accountRef.orEmpty()}\u0000$groupIdHex"
    }

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

    suspend fun <T> marmotIo(block: suspend Marmot.() -> T): T {
        return withContext(Dispatchers.IO) {
            marmot().block()
        }
    }

    suspend fun bootstrap() {
        if (client != null && phase != AppPhase.Bootstrapping) {
            ensureNotificationRuntimeStarted()
            phase = if (accounts.isEmpty()) AppPhase.Onboarding else AppPhase.Ready
            return
        }
        phase = AppPhase.Bootstrapping
        try {
            val opened = withContext(Dispatchers.IO) {
                client ?: MarmotClient(appContext).also { client = it }
            }
            appStateDebug { "bootstrap root=${opened.rootPath}" }
            withContext(Dispatchers.IO) {
                opened.marmot.start()
            }
            appStateDebug { "marmot started" }
            localNotificationPresenter.ensureChannels()
            refreshLocalNotificationPermission()
            startNotificationListener()
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
            present(R.string.toast_couldnt_import_identity, AppText.Plain(error.readableMessage()))
        }
    }

    suspend fun refreshAccounts() {
        accounts = marmotIo { listAccounts() }
    }

    fun setActiveAccount(label: String) {
        // Switching accounts (from the account picker) is a session boundary
        // identical to sign-out for media-cache purposes: account A's
        // decrypted bytes must not linger in process memory or on disk after
        // account B is active. Re-opening a chat under B simply re-downloads.
        // Skip the wipe when the label is unchanged (no-op tap on the
        // already-active account) so caches survive a redundant tap.
        if (label != activeAccountRef) clearMediaCaches()
        activeAccountRef = label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, label).apply()
        accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        notificationScope.launch {
            refreshLocalNotificationSettings()
        }
    }

    /**
     * Wipe the decrypted-media caches at a session boundary (sign-out or
     * account switch). L1 (in-memory) clears synchronously so account A's
     * plaintext is unreachable immediately; L2 (disk) is scheduled to IO
     * since many file deletes shouldn't stall the UI thread.
     */
    private fun clearMediaCaches() {
        mediaPlaintextCache.clear()
        mediaThumbnailCache.clear()
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
        // Decrypted media is the exception: it's a process-wide in-memory cache
        // (not per-account persistence), so account A's plaintext images must
        // not linger in memory after switching to account B. Re-opening a chat
        // simply re-downloads — no durable state is lost.
        // Both sign-out and account-switch wipe decrypted-media caches; the
        // helper centralises the L1-sync + L2-on-IO pattern.
        clearMediaCaches()
        val next = accounts.firstOrNull { it.label != activeAccountRef }?.label
        activeAccountRef = next
        preferences.edit().apply {
            if (next == null) remove(ACTIVE_ACCOUNT_KEY) else putString(ACTIVE_ACCOUNT_KEY, next)
        }.apply()
        next?.let { label ->
            accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        }
        notificationScope.launch {
            refreshLocalNotificationSettings()
        }
    }

    suspend fun accountRelayLists(): AccountRelayListsFfi? {
        val account = activeAccountRef ?: return null
        return runCatching { marmotIo { accountRelayLists(account) } }.getOrNull()
    }

    suspend fun setAccountRelays(kind: RelayListKind, relays: List<String>): AccountRelayListsFfi? {
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
                    RelayListKind.KeyPackage -> setAccountKeyPackageRelays(account, next, MarmotClient.bootstrapRelays)
                }
            }
        }.onSuccess {
            present(R.string.toast_relay_list_updated)
        }.onFailure {
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

    suspend fun deleteKeyPackage(eventIdHex: String, sourceRelays: List<String>): Boolean {
        val account = activeAccountRef ?: return false
        val keyPackageRelays = runCatching {
            marmotIo { accountKeyPackageRelays(account) }
        }.getOrNull().orEmpty()
        val relays = normalizeRelayUrls(
            sourceRelays +
                keyPackageRelays +
                MarmotClient.bootstrapRelays,
        )
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
    fun shouldAutoDownloadMedia(): Boolean {
        return when (mediaAutoDownloadPolicy) {
            MediaAutoDownloadPolicy.Always -> true
            MediaAutoDownloadPolicy.Never -> false
            MediaAutoDownloadPolicy.WifiOnly -> !isActiveNetworkMetered()
        }
    }

    private fun isActiveNetworkMetered(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
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
        localNotificationSettings = if (account == null) {
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
            present(R.string.toast_couldnt_update_notifications, AppText.Plain(it.readableMessage()))
            false
        }
    }

    suspend fun setBackgroundConnectionEnabled(enabled: Boolean): Boolean {
        val account = activeAccountRef ?: run {
            present(R.string.toast_no_active_account)
            return false
        }
        refreshLocalNotificationPermission()
        if (enabled && !localNotificationPermissionGranted) {
            present(R.string.toast_notification_permission_needed)
            return false
        }
        if (enabled && localNotificationSettings?.localNotificationsEnabled != true) {
            val settings = runCatching {
                marmotIo { setLocalNotificationsEnabled(account, true) }
            }.getOrElse {
                present(R.string.toast_couldnt_enable_notifications, AppText.Plain(it.readableMessage()))
                return false
            }
            localNotificationSettings = settings
        }

        updateBackgroundConnectionPreference(enabled)
        val serviceUpdated = if (enabled) {
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

    private fun profileDisplayName(accountIdHex: String): String? {
        return profilePresentation(accountIdHex).displayName
    }

    fun shortNpub(accountIdHex: String): String {
        val npub = npub(accountIdHex)
        return IdentityFormatter.short(npub, prefix = 10, suffix = 8)
    }

    fun npub(accountIdHex: String): String {
        return npubs.computeIfAbsent(accountIdHex) { key ->
            runCatching { marmot().npub(key) }.getOrNull() ?: key
        }
    }

    suspend fun accountIdHex(reference: String): String? {
        return runCatching { marmotIo { accountIdHex(reference) } }.getOrNull()
    }

    fun userProfile(accountIdHex: String): UserProfileMetadataFfi? {
        // Observe profile cache invalidations for Compose callers.
        profileRevision
        return cachedUserProfile(accountIdHex) ?: run {
            requestProfile(accountIdHex)
            null
        }
    }

    suspend fun loadUserProfile(accountIdHex: String): UserProfileMetadataFfi? {
        val profile = runCatching {
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

    fun cachedGroupMemberSnapshot(accountRef: String?, groupIdHex: String): GroupMemberSnapshot? {
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
        val profile = runCatching {
            marmotIo {
                val relays = activeAccountRef
                    ?.let { runCatching { accountNip65Relays(it) }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: MarmotClient.bootstrapRelays
                refreshProfile(accountIdHex, relays)
                userProfile(accountIdHex)
            }
        }.getOrNull()
        profileRefreshGate.finish(accountIdHex, System.currentTimeMillis())
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
            present(R.string.toast_couldnt_start_chat, AppText.Plain(it.readableMessage()))
            false
        }
    }

    suspend fun publishProfile(profile: UserProfileMetadataFfi) {
        val account = activeAccountRef ?: return
        runCatching {
            val profileRelayCount = marmotIo {
                val relayLists = accountRelayLists(account)
                val profileRelays = accountNip65Relays(account).ifEmpty {
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
            present(R.string.toast_couldnt_publish_profile, AppText.Plain(it.readableMessage()))
        }
    }

    fun present(title: String, detail: String? = null) {
        presentText(AppText.Plain(title), detail?.let { AppText.Plain(it) })
    }

    fun present(@StringRes titleRes: Int) {
        presentText(AppText.Resource(titleRes))
    }

    fun present(@StringRes titleRes: Int, @StringRes detailRes: Int) {
        presentText(AppText.Resource(titleRes), AppText.Resource(detailRes))
    }

    fun present(@StringRes titleRes: Int, detail: AppText) {
        presentText(AppText.Resource(titleRes), detail)
    }

    fun presentText(title: AppText, detail: AppText? = null) {
        toast = ToastMessage(title, detail)
    }

    fun clearToast() {
        toast = null
    }

    fun shutdown() {
        notificationJob?.cancel()
        profileScope.cancel()
        mutationsScope.cancel()
        notificationScope.cancel()
    }

    private fun warmProfile(accountIdHex: String) {
        userProfile(accountIdHex)
        requestProfile(accountIdHex)
    }

    private fun startNotificationListener() {
        if (notificationJob?.isActive == true) return
        notificationJob = notificationScope.launch {
            runCatching {
                val subscription = marmotIo { subscribeNotifications() }
                try {
                    while (isActive) {
                        val update = marmotIo { subscription.next() } ?: break
                        val activeConversation = activeConversationGroupIdHex
                        val shouldPost = LocalNotificationPolicy.shouldPost(
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
            }.onFailure {
                appStateDebug(it) { "notification listener stopped: ${it.readableMessage()}" }
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

    private fun cachedUserProfile(accountIdHex: String): UserProfileMetadataFfi? {
        return runCatching { marmot().userProfile(accountIdHex) }.getOrNull()
    }

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
        val displayName = runCatching { marmot().displayName(accountIdHex) }
            .getOrNull()
            ?.let { ProfileSanitizer.displayName(it) }
        val avatarUrl = ProfileSanitizer.imageUrl(cachedUserProfile(accountIdHex)?.picture)
        return ProfilePresentation(displayName = displayName, avatarUrl = avatarUrl)
    }

    private fun notifyProfileChanged(accountIdHex: String) {
        val presentation = readProfilePresentation(accountIdHex)
        val changed = synchronized(profilePresentationLock) {
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

    private fun groupMemberSnapshotKey(accountRef: String?, groupIdHex: String): String? {
        val account = accountRef?.takeIf { it.isNotBlank() } ?: return null
        return "$account:$groupIdHex"
    }

    private fun Throwable.readableMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }

    companion object {
        private const val ACTIVE_ACCOUNT_KEY = "active_account"
        private const val DEVELOPER_MODE_KEY = "developer_mode"
        private const val THEME_MODE_KEY = "theme_mode"
        private const val MEDIA_AUTO_DOWNLOAD_KEY = "media_auto_download"
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
        private const val MAX_RETAINED_CONVERSATION_STATES = 32
    }
}

private inline fun appStateDebug(message: () -> String) {
    Log.i("DMAppState", message())
}

private inline fun appStateDebug(error: Throwable, message: () -> String) {
    Log.e("DMAppState", message(), error)
}
