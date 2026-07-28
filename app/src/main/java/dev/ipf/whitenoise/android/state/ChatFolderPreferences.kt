package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The three pre-folder filter chips, seeded as default folders. */
enum class SystemFolderKind { UNREAD, ARCHIVED, GROUPS }

/**
 * [systemKind] marks a folder seeded from a default template. It carries no
 * capability lock — seeded folders rename, delete, reorder, and re-rule like
 * any other — it only names which localized label an un-renamed default
 * shows, and which defaults a Restore action still needs to re-add.
 */
data class ChatFolder(
    val id: String,
    val name: String,
    val description: String,
    val order: Int,
    val systemKind: SystemFolderKind?,
)

/**
 * Optional automatic-membership rule for one folder, evaluated against the
 * loaded chat list by `chatFolderChatIds`. Absent fields in a stored rule
 * (and a folder with no stored rule at all) fall back to these defaults —
 * folders saved before rules existed stay manual-only.
 */
data class ChatFolderRule(
    val includeMemberPubkeys: Set<String> = emptySet(),
    val unreadOnly: Boolean = false,
    val includeMuted: Boolean = false,
    val keyword: String? = null,
    val groupsOnly: Boolean = false,
    val archivedOnly: Boolean = false,
)

/** One account's folder state: ordered folders, manual memberships, rules. */
data class ChatFolderAccountState(
    val folders: List<ChatFolder> = emptyList(),
    val membership: Map<String, Set<String>> = emptyMap(),
    val rules: Map<String, ChatFolderRule> = emptyMap(),
)

/**
 * Device-local chat folders, per account. Same local UI-state pattern as the
 * contact nickname/notes and mute stores: plain SharedPreferences, no engine
 * or protocol involvement — folders are private organization, never shared
 * group state. The three legacy filter chips are migrated to system folders
 * on an account's first read, so ordering and hide-when-empty become uniform
 * across system and custom folders with no user-visible behavior change.
 */
@Suppress("TooManyFunctions")
class ChatFolderPreferences(
    context: Context,
    private val preferences: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
) {
    private val mutationLock = Any()
    private val _state = MutableStateFlow<Map<String, ChatFolderAccountState>>(emptyMap())
    val state: StateFlow<Map<String, ChatFolderAccountState>> = _state.asStateFlow()

    /** Ordered folders for [accountRef], seeding the system folders on first read. */
    fun foldersFor(accountRef: String): List<ChatFolder> {
        val account = normalizedAccount(accountRef) ?: return emptyList()
        return synchronized(mutationLock) { loadAccount(account).folders }
    }

    fun membershipFor(
        accountRef: String,
        folderId: String,
    ): Set<String> {
        val account = normalizedAccount(accountRef) ?: return emptySet()
        return synchronized(mutationLock) { loadAccount(account).membership[folderId].orEmpty() }
    }

    fun createFolder(
        accountRef: String,
        name: String,
        description: String = "",
    ): ChatFolder? {
        val account = normalizedAccount(accountRef)
        val trimmedName = name.trim().takeIf { it.isNotEmpty() }
        if (account == null || trimmedName == null) return null
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            val folder =
                ChatFolder(
                    id = UUID.randomUUID().toString(),
                    name = trimmedName,
                    description = description.trim(),
                    order = (current.folders.maxOfOrNull { it.order } ?: -1) + 1,
                    systemKind = null,
                )
            persistFolders(account, current.copy(folders = current.folders + folder))
            folder
        }
    }

    fun renameFolder(
        accountRef: String,
        folderId: String,
        name: String,
    ): Boolean {
        val trimmed = name.trim().takeIf { it.isNotEmpty() } ?: return false
        return updateFolder(accountRef, folderId) { it.copy(name = trimmed) }
    }

    fun editFolderDescription(
        accountRef: String,
        folderId: String,
        description: String,
    ): Boolean = updateFolder(accountRef, folderId) { it.copy(description = description.trim()) }

    /** Deletes any folder, seeded defaults included — deletion is durable. */
    fun deleteFolder(
        accountRef: String,
        folderId: String,
    ): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            if (current.folders.none { it.id == folderId }) return@synchronized false
            // Purge the folder's own keys directly: persistFolders only visits
            // memberships still present in the map, so a deleted folder's
            // membership key would otherwise stay on disk forever.
            preferences
                .edit()
                .remove(ruleKey(account, folderId))
                .remove(membershipKey(account, folderId))
                .apply()
            persistFolders(
                account,
                ChatFolderAccountState(
                    folders = current.folders.filterNot { it.id == folderId },
                    membership = current.membership - folderId,
                    rules = current.rules - folderId,
                ),
            )
            true
        }
    }

    /** Reassigns [orderedIds]' folders their list positions; unknown ids ignored. */
    fun reorderFolders(
        accountRef: String,
        orderedIds: List<String>,
    ): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            val byId = current.folders.associateBy { it.id }
            val known = orderedIds.filter { it in byId }
            if (known.isEmpty()) return@synchronized false
            val reordered =
                known.mapIndexed { index, id -> byId.getValue(id).copy(order = index) } +
                    current.folders
                        .filterNot { it.id in known.toSet() }
                        .mapIndexed { index, folder -> folder.copy(order = known.size + index) }
            persistFolders(account, current.copy(folders = reordered.sortedBy { it.order }))
            true
        }
    }

    fun setChatInFolder(
        accountRef: String,
        folderId: String,
        chatId: String,
        included: Boolean,
    ): Boolean {
        val account = normalizedAccount(accountRef)
        val chat = chatId.trim().lowercase().takeIf { it.isNotEmpty() }
        if (account == null || chat == null) return false
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            if (current.folders.none { it.id == folderId }) return@synchronized false
            val chats = current.membership[folderId].orEmpty()
            val updated = if (included) chats + chat else chats - chat
            if (updated == chats) return@synchronized false
            persistFolders(account, current.copy(membership = current.membership + (folderId to updated)))
            true
        }
    }

    fun folderRule(
        accountRef: String,
        folderId: String,
    ): ChatFolderRule? {
        val account = normalizedAccount(accountRef) ?: return null
        return synchronized(mutationLock) { loadAccount(account).rules[folderId] }
    }

    fun setFolderRule(
        accountRef: String,
        folderId: String,
        rule: ChatFolderRule?,
    ): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            if (current.folders.none { it.id == folderId }) return@synchronized false
            val edit = preferences.edit()
            if (rule == null) {
                edit.remove(ruleKey(account, folderId))
            } else {
                edit.putString(ruleKey(account, folderId), ruleJson(rule).toString())
            }
            edit.apply()
            val rules = if (rule == null) current.rules - folderId else current.rules + (folderId to rule)
            _state.value = _state.value + (account to current.copy(rules = rules))
            true
        }
    }

    /** Sign-out/wipe hook: drops every folder key the account owns. */
    fun clearAllForAccount(accountRef: String): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            val prefix = accountKeyPrefix(account)
            val keys = preferences.all.keys.filter { it.startsWith(prefix) }
            if (keys.isEmpty() && account !in _state.value) return@synchronized false
            val edit = preferences.edit()
            keys.forEach { edit.remove(it) }
            edit.apply()
            _state.value = _state.value - account
            true
        }
    }

    private fun updateFolder(
        accountRef: String,
        folderId: String,
        transform: (ChatFolder) -> ChatFolder,
    ): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            val existing = current.folders.firstOrNull { it.id == folderId } ?: return@synchronized false
            val updated = transform(existing)
            if (updated == existing) return@synchronized false
            persistFolders(
                account,
                current.copy(folders = current.folders.map { if (it.id == folderId) updated else it }),
            )
            true
        }
    }

    /**
     * Re-adds whichever seeded defaults the account no longer has, with their
     * default rules, at the end of the current order. Explicit user action
     * only — a deliberately deleted default must never come back on its own.
     * Idempotent: defaults that still exist are left untouched.
     */
    fun restoreDefaultFolders(accountRef: String): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            val presentIds = current.folders.mapTo(HashSet()) { it.id }
            val missing = systemFolders().filterNot { it.id in presentIds }
            if (missing.isEmpty()) return@synchronized false
            val nextOrder = (current.folders.maxOfOrNull { it.order } ?: -1) + 1
            val restored = missing.mapIndexed { index, seed -> seed.copy(order = nextOrder + index) }
            val edit = preferences.edit()
            val restoredRules =
                restored.associate { folder ->
                    val rule = defaultRuleFor(folder.systemKind!!)
                    edit.putString(ruleKey(account, folder.id), ruleJson(rule).toString())
                    folder.id to rule
                }
            edit.apply()
            persistFolders(
                account,
                current.copy(folders = current.folders + restored, rules = current.rules + restoredRules),
            )
            true
        }
    }

    // Loads (and lazily migrates) one account's state into the flow snapshot.
    private fun loadAccount(account: String): ChatFolderAccountState {
        _state.value[account]?.let { return it }
        val storedFolders = preferences.getString(foldersKey(account), null)
        // A stored empty list is a real user state (every folder deleted) and
        // must survive reloads — only a never-seeded account (no key) or an
        // unparseable blob seeds the defaults.
        val folders =
            (storedFolders?.let(::parseFolders) ?: seedDefaults(account))
                .also { migrateSeededRules(account, it) }
        val membership =
            folders.associate { folder ->
                folder.id to
                    preferences
                        .getStringSet(membershipKey(account, folder.id), emptySet())
                        .orEmpty()
                        .toSet()
            }
        val rules =
            folders
                .mapNotNull { folder -> readRule(account, folder.id)?.let { folder.id to it } }
                .toMap()
        val loaded = ChatFolderAccountState(folders = folders, membership = membership, rules = rules)
        _state.value = _state.value + (account to loaded)
        return loaded
    }

    private fun seedDefaults(account: String): List<ChatFolder> {
        val seeded = systemFolders()
        persistFolderList(account, seeded)
        val edit = preferences.edit()
        seeded.forEach { folder ->
            edit.putString(ruleKey(account, folder.id), ruleJson(defaultRuleFor(folder.systemKind!!)).toString())
        }
        edit.putInt(versionKey(account), STORE_VERSION).apply()
        return seeded
    }

    /**
     * One-time upgrade for accounts written before defaults carried rules:
     * their behavior lived in hardcoded kind branches, so each seeded folder
     * without a stored rule gets its default rule persisted. Guarded by a
     * version stamp — a user later clearing a seeded folder's rule must not
     * see this resurrect it.
     */
    private fun migrateSeededRules(
        account: String,
        folders: List<ChatFolder>,
    ) {
        if (preferences.getInt(versionKey(account), 1) >= STORE_VERSION) return
        val edit = preferences.edit()
        folders
            .filter { it.systemKind != null && !preferences.contains(ruleKey(account, it.id)) }
            .forEach { folder ->
                edit.putString(ruleKey(account, folder.id), ruleJson(defaultRuleFor(folder.systemKind!!)).toString())
            }
        edit.putInt(versionKey(account), STORE_VERSION).apply()
    }

    private fun readRule(
        account: String,
        folderId: String,
    ): ChatFolderRule? {
        val raw = preferences.getString(ruleKey(account, folderId), null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            ChatFolderRule(
                includeMemberPubkeys = json.optJSONArray(RULE_MEMBERS).toStringSet(),
                unreadOnly = json.optBoolean(RULE_UNREAD_ONLY, false),
                includeMuted = json.optBoolean(RULE_INCLUDE_MUTED, false),
                keyword = json.optString(RULE_KEYWORD).takeIf { it.isNotBlank() },
                groupsOnly = json.optBoolean(RULE_GROUPS_ONLY, false),
                archivedOnly = json.optBoolean(RULE_ARCHIVED_ONLY, false),
            )
        }.getOrNull()
    }

    private fun ruleJson(rule: ChatFolderRule): JSONObject {
        val json =
            JSONObject()
                .put(RULE_MEMBERS, JSONArray(rule.includeMemberPubkeys.toList()))
                .put(RULE_UNREAD_ONLY, rule.unreadOnly)
                .put(RULE_INCLUDE_MUTED, rule.includeMuted)
                .put(RULE_GROUPS_ONLY, rule.groupsOnly)
                .put(RULE_ARCHIVED_ONLY, rule.archivedOnly)
        rule.keyword?.takeIf { it.isNotBlank() }?.let { json.put(RULE_KEYWORD, it.trim()) }
        return json
    }

    private fun persistFolders(
        account: String,
        updated: ChatFolderAccountState,
    ) {
        val normalized = updated.copy(folders = updated.folders.sortedBy { it.order })
        persistFolderList(account, normalized.folders)
        val edit = preferences.edit()
        normalized.membership.forEach { (folderId, chats) ->
            if (chats.isEmpty()) {
                edit.remove(membershipKey(account, folderId))
            } else {
                edit.putStringSet(membershipKey(account, folderId), chats)
            }
        }
        edit.apply()
        _state.value = _state.value + (account to normalized)
    }

    private fun persistFolderList(
        account: String,
        folders: List<ChatFolder>,
    ) {
        val json =
            JSONArray().apply {
                folders.forEach { folder ->
                    put(
                        JSONObject()
                            .put(FIELD_ID, folder.id)
                            .put(FIELD_NAME, folder.name)
                            .put(FIELD_DESCRIPTION, folder.description)
                            .put(FIELD_ORDER, folder.order)
                            .put(FIELD_SYSTEM_KIND, folder.systemKind?.name),
                    )
                }
            }
        preferences.edit().putString(foldersKey(account), json.toString()).apply()
    }

    // Null only when the blob is unparseable JSON — the caller then reseeds.
    // A parsed empty list is respected: deleting every folder is a valid
    // state, not corruption to repair.
    private fun parseFolders(raw: String): List<ChatFolder>? =
        runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val id = json.optString(FIELD_ID).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val kind =
                    json
                        .optString(FIELD_SYSTEM_KIND)
                        .takeIf { it.isNotEmpty() }
                        ?.let { name -> SystemFolderKind.entries.firstOrNull { it.name == name } }
                ChatFolder(
                    id = id,
                    name = json.optString(FIELD_NAME),
                    description = json.optString(FIELD_DESCRIPTION),
                    order = json.optInt(FIELD_ORDER, 0),
                    systemKind = kind,
                )
            }
        }.getOrNull()
            ?.sortedBy { it.order }

    private fun normalizedAccount(accountRef: String): String? = accountRef.trim().takeIf { it.isNotEmpty() }

    companion object {
        private const val PREFERENCES_NAME = "whitenoise.chat_folders"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_DESCRIPTION = "description"
        private const val FIELD_ORDER = "order"
        private const val FIELD_SYSTEM_KIND = "systemKind"
        private const val RULE_MEMBERS = "includeMemberPubkeys"
        private const val RULE_UNREAD_ONLY = "unreadOnly"
        private const val RULE_INCLUDE_MUTED = "includeMuted"
        private const val RULE_KEYWORD = "keyword"
        private const val RULE_GROUPS_ONLY = "groupsOnly"
        private const val RULE_ARCHIVED_ONLY = "archivedOnly"

        // Bumped when defaults became first-class folders carrying real rules,
        // so the rule backfill for older accounts runs exactly once.
        private const val STORE_VERSION = 2

        // Stable ids so the chip row and future deep links can reference the
        // absorbed system folders without a per-account lookup.
        const val SYSTEM_FOLDER_UNREAD_ID = "system:unread"
        const val SYSTEM_FOLDER_ARCHIVED_ID = "system:archived"
        const val SYSTEM_FOLDER_GROUPS_ID = "system:groups"

        internal fun systemFolders(): List<ChatFolder> =
            listOf(
                systemFolder(SYSTEM_FOLDER_UNREAD_ID, 0, SystemFolderKind.UNREAD),
                systemFolder(SYSTEM_FOLDER_ARCHIVED_ID, 1, SystemFolderKind.ARCHIVED),
                systemFolder(SYSTEM_FOLDER_GROUPS_ID, 2, SystemFolderKind.GROUPS),
            )

        private fun systemFolder(
            id: String,
            order: Int,
            kind: SystemFolderKind,
        ): ChatFolder = ChatFolder(id, name = "", description = "", order = order, systemKind = kind)

        /** The rule a default is seeded with — its old hardcoded chip behavior, expressed as a rule. */
        internal fun defaultRuleFor(kind: SystemFolderKind): ChatFolderRule =
            when (kind) {
                SystemFolderKind.UNREAD -> ChatFolderRule(unreadOnly = true, includeMuted = true)
                SystemFolderKind.ARCHIVED -> ChatFolderRule(archivedOnly = true, includeMuted = true)
                SystemFolderKind.GROUPS -> ChatFolderRule(groupsOnly = true, includeMuted = true)
            }

        private fun accountKeyPrefix(account: String): String = "cf:$account:"

        private fun versionKey(account: String): String = "${accountKeyPrefix(account)}v"

        private fun foldersKey(account: String): String = "${accountKeyPrefix(account)}folders"

        private fun membershipKey(
            account: String,
            folderId: String,
        ): String = "${accountKeyPrefix(account)}m:$folderId"

        private fun ruleKey(
            account: String,
            folderId: String,
        ): String = "${accountKeyPrefix(account)}r:$folderId"

        private fun JSONArray?.toStringSet(): Set<String> {
            if (this == null) return emptySet()
            return (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }.toSet()
        }
    }
}
