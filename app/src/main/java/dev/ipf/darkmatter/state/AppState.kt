package dev.ipf.darkmatter.state

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
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
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
}

class DarkMatterAppState(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("darkmatter", Context.MODE_PRIVATE)
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

    private val npubs = mutableStateMapOf<String, String>()
    private var profileRevision by mutableStateOf(0)
    private val profilePresentations = mutableMapOf<String, ProfilePresentation>()
    private val profilePresentationLock = Any()
    private val groupMemberSnapshots = mutableMapOf<String, GroupMemberSnapshot>()
    private val groupMemberSnapshotLock = Any()

    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutationsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notificationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null
    private var appInForeground = false
    private var activeConversationGroupIdHex: String? = null
    private val requestedProfiles = mutableSetOf<String>()
    private var chatsController: ChatsController? = null

    init {
        applyLanguageTag(languageTag)
    }

    val activeAccount: AccountSummaryFfi?
        get() = activeAccountRef?.let { ref -> accounts.firstOrNull { it.label == ref } }

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
        activeAccountRef = label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, label).apply()
        accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
        notificationScope.launch {
            refreshLocalNotificationSettings()
        }
    }

    fun signOutActiveAccount() {
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
        val next = normalizeRelayUrls(relays)
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

    suspend fun fetchKeyPackages(): List<AccountKeyPackageFfi> {
        val account = activeAccountRef ?: return emptyList()
        return runCatching {
            marmotIo { accountKeyPackages(account, MarmotClient.bootstrapRelays) }
        }.getOrElse {
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
        return npubs.getOrPut(accountIdHex) {
            runCatching { marmot().npub(accountIdHex) }.getOrNull() ?: accountIdHex
        }
    }

    suspend fun accountIdHex(reference: String): String? {
        return runCatching { marmotIo { accountIdHex(reference) } }.getOrNull()
    }

    fun userProfile(accountIdHex: String): UserProfileMetadataFfi? {
        return profileRevision.let {
            cachedUserProfile(accountIdHex) ?: run {
                requestProfile(accountIdHex)
                null
            }
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
        val shouldFetch = synchronized(requestedProfiles) {
            requestedProfiles.add(id)
        }
        if (!shouldFetch) return
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
        // Always release the in-flight marker, even on fetch failure, so a
        // later requestProfile() for the same id can try again. Without this
        // (issue #9), a profile that fails to resolve once stays stuck on the
        // hex fallback forever — most visible on the pending-invite screen.
        synchronized(requestedProfiles) {
            requestedProfiles.remove(accountIdHex)
        }
        if (profile != null) {
            notifyProfilesChanged()
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
        val displayName = runCatching { marmot().displayName(accountIdHex) }
            .getOrNull()
            ?.let { ProfileSanitizer.displayName(it) }
        val avatarUrl = ProfileSanitizer.imageUrl(cachedUserProfile(accountIdHex)?.picture)
        val presentation = ProfilePresentation(displayName = displayName, avatarUrl = avatarUrl)
        synchronized(profilePresentationLock) {
            profilePresentations[accountIdHex] = presentation
        }
        return presentation
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
        private const val LANGUAGE_TAG_KEY = "language_tag"
    }
}

private inline fun appStateDebug(message: () -> String) {
    Log.i("DMAppState", message())
}

private inline fun appStateDebug(error: Throwable, message: () -> String) {
    Log.e("DMAppState", message(), error)
}
