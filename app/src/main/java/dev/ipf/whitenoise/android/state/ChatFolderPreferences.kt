package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** The three pre-folder filter chips, absorbed as non-deletable system folders. */
enum class SystemFolderKind { UNREAD, ARCHIVED, GROUPS }

data class ChatFolder(
    val id: String,
    val name: String,
    val description: String,
    val order: Int,
    val isSystem: Boolean,
    val systemKind: SystemFolderKind?,
)

/**
 * Optional automatic-membership rule for one folder. Storage only here —
 * evaluation belongs to the group-details sub-issue that introduces rules.
 */
data class ChatFolderRule(
    val includeMemberPubkeys: Set<String>,
    val unreadOnly: Boolean,
    val includeMuted: Boolean,
)

/** One account's folder state: ordered folders plus manual memberships. */
data class ChatFolderAccountState(
    val folders: List<ChatFolder> = emptyList(),
    val membership: Map<String, Set<String>> = emptyMap(),
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
                    isSystem = false,
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
    ): Boolean = updateFolder(accountRef, folderId) { it.copy(name = name.trim()) }

    fun editFolderDescription(
        accountRef: String,
        folderId: String,
        description: String,
    ): Boolean = updateFolder(accountRef, folderId) { it.copy(description = description.trim()) }

    /** System folders are not deletable: they are the absorbed filter chips. */
    fun deleteFolder(
        accountRef: String,
        folderId: String,
    ): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            val current = loadAccount(account)
            val folder = current.folders.firstOrNull { it.id == folderId } ?: return@synchronized false
            if (folder.isSystem) return@synchronized false
            preferences.edit().remove(ruleKey(account, folderId)).apply()
            persistFolders(
                account,
                ChatFolderAccountState(
                    folders = current.folders.filterNot { it.id == folderId },
                    membership = current.membership - folderId,
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
        val raw = preferences.getString(ruleKey(account, folderId), null)
        return raw?.let {
            runCatching {
                val json = JSONObject(it)
                ChatFolderRule(
                    includeMemberPubkeys = json.optJSONArray(RULE_MEMBERS).toStringSet(),
                    unreadOnly = json.optBoolean(RULE_UNREAD_ONLY, false),
                    includeMuted = json.optBoolean(RULE_INCLUDE_MUTED, true),
                )
            }.getOrNull()
        }
    }

    fun setFolderRule(
        accountRef: String,
        folderId: String,
        rule: ChatFolderRule?,
    ): Boolean {
        val account = normalizedAccount(accountRef) ?: return false
        return synchronized(mutationLock) {
            if (loadAccount(account).folders.none { it.id == folderId }) return@synchronized false
            val edit = preferences.edit()
            if (rule == null) {
                edit.remove(ruleKey(account, folderId))
            } else {
                val json =
                    JSONObject()
                        .put(RULE_MEMBERS, JSONArray(rule.includeMemberPubkeys.toList()))
                        .put(RULE_UNREAD_ONLY, rule.unreadOnly)
                        .put(RULE_INCLUDE_MUTED, rule.includeMuted)
                edit.putString(ruleKey(account, folderId), json.toString())
            }
            edit.apply()
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
            if (updated == existing || updated.name.isEmpty()) return@synchronized false
            persistFolders(
                account,
                current.copy(folders = current.folders.map { if (it.id == folderId) updated else it }),
            )
            true
        }
    }

    // Loads (and lazily migrates) one account's state into the flow snapshot.
    private fun loadAccount(account: String): ChatFolderAccountState {
        _state.value[account]?.let { return it }
        val storedFolders = preferences.getString(foldersKey(account), null)
        val folders =
            if (storedFolders == null) {
                // First read for this account: absorb the three legacy filter
                // chips as system folders, exactly once, so ordering and
                // hide-when-empty become uniform without behavior change.
                systemFolders().also { seeded -> persistFolderList(account, seeded) }
            } else {
                parseFolders(storedFolders)
            }
        val membership =
            folders.associate { folder ->
                folder.id to
                    preferences
                        .getStringSet(membershipKey(account, folder.id), emptySet())
                        .orEmpty()
                        .toSet()
            }
        val loaded = ChatFolderAccountState(folders = folders, membership = membership)
        _state.value = _state.value + (account to loaded)
        return loaded
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

    private fun parseFolders(raw: String): List<ChatFolder> =
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
                    isSystem = kind != null,
                    systemKind = kind,
                )
            }
        }.getOrDefault(emptyList())
            .sortedBy { it.order }
            .ifEmpty { systemFolders() }

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
        ): ChatFolder = ChatFolder(id, name = "", description = "", order = order, isSystem = true, systemKind = kind)

        private fun accountKeyPrefix(account: String): String = "cf:$account:"

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
