package dev.ipf.darkmatter.state

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ipf.darkmatter.core.IdentityFormatter
import dev.ipf.darkmatter.core.MarmotClient
import dev.ipf.darkmatter.core.ProfileLink
import dev.ipf.darkmatter.core.ProfileSanitizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import dev.ipf.marmotkit.AccountRelayListsFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.Marmot
import dev.ipf.marmotkit.UserProfileMetadataFfi

sealed interface AppPhase {
    data object Bootstrapping : AppPhase
    data object Onboarding : AppPhase
    data object Ready : AppPhase
    data class Failed(val message: String) : AppPhase
}

data class ToastMessage(
    val title: String,
    val detail: String? = null,
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

    var phase by mutableStateOf<AppPhase>(AppPhase.Bootstrapping)
        private set

    var accounts by mutableStateOf<List<AccountSummaryFfi>>(emptyList())
        private set

    var activeAccountRef by mutableStateOf(preferences.getString(ACTIVE_ACCOUNT_KEY, null))
        private set

    var developerMode by mutableStateOf(preferences.getBoolean(DEVELOPER_MODE_KEY, false))
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    var pendingProfileNpub by mutableStateOf<String?>(null)
        private set

    private val npubs = mutableStateMapOf<String, String>()
    private var profileRevision by mutableStateOf(0)
    private val profilePresentations = mutableMapOf<String, ProfilePresentation>()
    private val profilePresentationLock = Any()
    private val groupMemberSnapshots = mutableMapOf<String, GroupMemberSnapshot>()
    private val groupMemberSnapshotLock = Any()

    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestedProfiles = mutableSetOf<String>()

    val activeAccount: AccountSummaryFfi?
        get() = activeAccountRef?.let { ref -> accounts.firstOrNull { it.label == ref } }

    fun marmot(): Marmot = requireNotNull(client) { "Marmot is not initialized" }.marmot

    suspend fun bootstrap() {
        phase = AppPhase.Bootstrapping
        try {
            val opened = withContext(Dispatchers.IO) {
                client ?: MarmotClient(appContext).also { client = it }
            }
            opened.marmot.start()
            refreshAccounts()
            if (accounts.isEmpty()) {
                phase = AppPhase.Onboarding
            } else {
                if (activeAccountRef == null || accounts.none { it.label == activeAccountRef }) {
                    setActiveAccount(accounts.first().label)
                }
                phase = AppPhase.Ready
                activeAccount?.accountIdHex?.let { warmProfile(it) }
            }
        } catch (error: Throwable) {
            phase = AppPhase.Failed(error.readableMessage())
        }
    }

    suspend fun createIdentity() {
        try {
            val summary = marmot().createIdentity(MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
            refreshAccounts()
            setActiveAccount(summary.label)
            phase = AppPhase.Ready
            present("Identity created")
            warmProfile(summary.accountIdHex)
        } catch (error: Throwable) {
            present("Couldn't create identity", error.readableMessage())
        }
    }

    suspend fun importIdentity(identity: String) {
        val trimmed = identity.trim()
        if (trimmed.isEmpty()) return
        try {
            val summary = marmot().login(trimmed, MarmotClient.bootstrapRelays, MarmotClient.bootstrapRelays)
            refreshAccounts()
            setActiveAccount(summary.label)
            phase = AppPhase.Ready
            present("Identity imported")
            warmProfile(summary.accountIdHex)
        } catch (error: Throwable) {
            present("Couldn't import identity", error.readableMessage())
        }
    }

    suspend fun refreshAccounts() {
        accounts = withContext(Dispatchers.IO) {
            marmot().listAccounts()
        }
    }

    fun setActiveAccount(label: String) {
        activeAccountRef = label
        preferences.edit().putString(ACTIVE_ACCOUNT_KEY, label).apply()
        accounts.firstOrNull { it.label == label }?.accountIdHex?.let { warmProfile(it) }
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
    }

    fun accountRelayLists(): AccountRelayListsFfi? {
        val account = activeAccountRef ?: return null
        return runCatching { marmot().accountRelayLists(account) }.getOrNull()
    }

    suspend fun setAccountRelays(kind: RelayListKind, relays: List<String>): AccountRelayListsFfi? {
        val account = activeAccountRef ?: return null
        val next = normalizeRelayUrls(relays)
        if (next.isEmpty()) {
            present("Relay list can't be empty")
            return accountRelayLists()
        }
        return runCatching {
            withContext(Dispatchers.IO) {
                when (kind) {
                    RelayListKind.Nip65 -> marmot().setAccountNip65Relays(account, next, MarmotClient.bootstrapRelays)
                    RelayListKind.Inbox -> marmot().setAccountInboxRelays(account, next, MarmotClient.bootstrapRelays)
                    RelayListKind.KeyPackage -> marmot().setAccountKeyPackageRelays(account, next, MarmotClient.bootstrapRelays)
                }
            }
        }.onSuccess {
            present("Relay list updated")
        }.onFailure {
            present("Relay update failed", it.readableMessage())
        }.getOrNull()
    }

    fun bootstrapRelayCount(): Int = MarmotClient.bootstrapRelays.size

    fun updateDeveloperMode(enabled: Boolean) {
        developerMode = enabled
        preferences.edit().putBoolean(DEVELOPER_MODE_KEY, enabled).apply()
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

    fun accountIdHex(reference: String): String? {
        return runCatching { marmot().accountIdHex(reference) }.getOrNull()
    }

    fun userProfile(accountIdHex: String): UserProfileMetadataFfi? {
        return profileRevision.let {
            cachedUserProfile(accountIdHex) ?: run {
                requestProfile(accountIdHex)
                null
            }
        }
    }

    fun avatarUrl(accountIdHex: String): String? {
        val avatar = profilePresentation(accountIdHex).avatarUrl
        if (avatar == null) requestProfile(accountIdHex)
        return avatar
    }

    fun requestProfile(accountIdHex: String) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        if (cachedUserProfile(id) != null) return
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
            withContext(Dispatchers.IO) {
                val relays = activeAccountRef
                    ?.let { runCatching { marmot().accountNip65Relays(it) }.getOrNull() }
                    ?.takeIf { it.isNotEmpty() }
                    ?: MarmotClient.bootstrapRelays
                marmot().refreshProfile(accountIdHex, relays)
                marmot().userProfile(accountIdHex)
            }
        }.getOrNull()
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
            marmot().createGroup(account, "", listOf(npub), null)
            present("Chat started")
            true
        }.getOrElse {
            present("Couldn't start chat", it.readableMessage())
            false
        }
    }

    suspend fun publishProfile(profile: UserProfileMetadataFfi) {
        val account = activeAccountRef ?: return
        runCatching {
            val relayLists = marmot().accountRelayLists(account)
            val profileRelays = marmot().accountNip65Relays(account).ifEmpty {
                relayLists.defaultRelays.ifEmpty { MarmotClient.bootstrapRelays }
            }
            val bootstrapRelays = relayLists.bootstrapRelays.ifEmpty { MarmotClient.bootstrapRelays }
            marmot().publishUserProfile(account, profile, profileRelays, bootstrapRelays)
            notifyProfilesChanged()
            present("Profile published", "Your kind:0 metadata is live on ${profileRelays.size} relays.")
        }.onFailure {
            present("Couldn't publish profile", it.readableMessage())
        }
    }

    fun present(title: String, detail: String? = null) {
        toast = ToastMessage(title, detail)
    }

    fun clearToast() {
        toast = null
    }

    private fun warmProfile(accountIdHex: String) {
        userProfile(accountIdHex)
        requestProfile(accountIdHex)
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
    }
}
