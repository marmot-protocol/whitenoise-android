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
import org.marmotprotocol.marmotkit.AccountRelayListsFfi
import org.marmotprotocol.marmotkit.AccountSummaryFfi
import org.marmotprotocol.marmotkit.Marmot
import org.marmotprotocol.marmotkit.UserProfileMetadataFfi

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

    var defaultRelays by mutableStateOf(loadRelays())
        private set

    var developerMode by mutableStateOf(preferences.getBoolean(DEVELOPER_MODE_KEY, false))
        private set

    var toast by mutableStateOf<ToastMessage?>(null)
        private set

    var pendingProfileNpub by mutableStateOf<String?>(null)
        private set

    private val profiles = mutableStateMapOf<String, UserProfileMetadataFfi>()
    private val displayNames = mutableStateMapOf<String, String>()
    private val npubs = mutableStateMapOf<String, String>()

    private val profileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val requestedProfiles = mutableSetOf<String>()

    val activeAccount: AccountSummaryFfi?
        get() = activeAccountRef?.let { ref -> accounts.firstOrNull { it.label == ref } }

    fun marmot(): Marmot = requireNotNull(client) { "Marmot is not initialized" }.marmot

    suspend fun bootstrap() {
        phase = AppPhase.Bootstrapping
        try {
            val opened = withContext(Dispatchers.IO) {
                client ?: MarmotClient(appContext, defaultRelays).also { client = it }
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
            val summary = marmot().createIdentity(defaultRelays, defaultRelays)
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
            val summary = marmot().login(trimmed, defaultRelays, defaultRelays)
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

    fun updateRelays(text: String) {
        val next = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
        replaceDefaultRelays(next)
    }

    fun replaceDefaultRelays(relays: List<String>) {
        val next = relays
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (next.isEmpty()) return
        defaultRelays = next
        preferences.edit().putString(RELAYS_KEY, next.joinToString("\n")).apply()
    }

    suspend fun republishRelayLists() {
        val account = activeAccountRef ?: return
        runCatching {
            marmot().publishRelayLists(account, defaultRelays, defaultRelays)
            present("Relay lists republished")
        }.onFailure {
            present("Republish failed", it.readableMessage())
        }
    }

    fun accountRelayLists(): AccountRelayListsFfi? {
        val account = activeAccountRef ?: return null
        return runCatching { marmot().accountRelayLists(account) }.getOrNull()
    }

    fun updateDeveloperMode(enabled: Boolean) {
        developerMode = enabled
        preferences.edit().putBoolean(DEVELOPER_MODE_KEY, enabled).apply()
    }

    fun displayName(accountIdHex: String): String {
        profiles[accountIdHex]?.let { profile ->
            ProfileSanitizer.displayName(profile.displayName)?.let { return it }
            ProfileSanitizer.displayName(profile.name)?.let { return it }
        }
        displayNames[accountIdHex]?.let { return it }
        runCatching { marmot().displayName(accountIdHex) }
            .getOrNull()
            ?.let { ProfileSanitizer.displayName(it) }
            ?.also { displayNames[accountIdHex] = it }
            ?.let { return it }
        requestProfile(accountIdHex)
        accounts.firstOrNull { it.accountIdHex == accountIdHex }?.let {
            return it.label.ifBlank { IdentityFormatter.short(accountIdHex) }
        }
        return IdentityFormatter.short(accountIdHex)
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
        profiles[accountIdHex]?.let { return it }
        val profile = runCatching { marmot().userProfile(accountIdHex) }.getOrNull()
        if (profile != null) cacheProfile(accountIdHex, profile)
        else requestProfile(accountIdHex)
        return profile
    }

    fun avatarUrl(accountIdHex: String): String? {
        return ProfileSanitizer.imageUrl(userProfile(accountIdHex)?.picture)
    }

    fun requestProfile(accountIdHex: String) {
        val id = accountIdHex.trim().takeIf { it.isNotEmpty() } ?: return
        if (profiles.containsKey(id)) return
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

    suspend fun refreshProfile(accountIdHex: String) {
        val profile = runCatching {
            withContext(Dispatchers.IO) {
                marmot().refreshProfile(accountIdHex, defaultRelays)
                marmot().userProfile(accountIdHex)
            }
        }.getOrNull()
        if (profile != null) {
            cacheProfile(accountIdHex, profile)
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
            val published = marmot().publishUserProfile(account, profile, defaultRelays, defaultRelays)
            activeAccount?.accountIdHex?.let { cacheProfile(it, published) }
            present("Profile published", "Your kind:0 metadata is live on ${defaultRelays.size} relays.")
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

    fun cacheProfile(accountIdHex: String, profile: UserProfileMetadataFfi) {
        profiles[accountIdHex] = profile
        val name = ProfileSanitizer.displayName(profile.displayName)
            ?: ProfileSanitizer.displayName(profile.name)
        if (name != null) displayNames[accountIdHex] = name
    }

    private fun loadRelays(): List<String> {
        val stored = preferences.getString(RELAYS_KEY, null)
        return stored
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toList()
            ?.takeIf { it.isNotEmpty() }
            ?: MarmotClient.defaultRelays
    }

    private fun Throwable.readableMessage(): String {
        return message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    }

    companion object {
        private const val ACTIVE_ACCOUNT_KEY = "active_account"
        private const val RELAYS_KEY = "default_relays"
        private const val DEVELOPER_MODE_KEY = "developer_mode"
    }
}
