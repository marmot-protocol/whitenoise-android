package dev.ipf.whitenoise.android.ui.chats

import androidx.compose.runtime.saveable.Saver
import dev.ipf.whitenoise.android.search.GlobalSearchContentFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchContentKind
import dev.ipf.whitenoise.android.search.GlobalSearchDateFilterSelection
import dev.ipf.whitenoise.android.search.GlobalSearchRequestProjection
import dev.ipf.whitenoise.android.search.decodeGlobalSearchContentFilter
import dev.ipf.whitenoise.android.search.decodeGlobalSearchDateFilter
import dev.ipf.whitenoise.android.search.encodeGlobalSearchContentFilter
import dev.ipf.whitenoise.android.search.encodeGlobalSearchDateFilter
import dev.ipf.whitenoise.android.search.projectGlobalSearchRequest
import java.time.ZoneId
import java.util.Base64

/**
 * In-session global chat-list search state owned by [dev.ipf.whitenoise.android.ui.navigation.MainShell].
 * The prototype's six filter categories live here: folders and chat types scope the chat list, named
 * chats narrow it further, and senders, date and content constrain the message search.
 */
internal data class GlobalSearchState(
    val isOpen: Boolean = false,
    val query: String = "",
    val openFilterCategory: GlobalSearchFilterCategory? = null,
    val accountScopeToken: String = "",
    val folderFilters: Set<String> = emptySet(),
    val chatTypeFilters: Set<GlobalSearchChatType> = emptySet(),
    val chatFilters: Set<GlobalSearchChatFilter> = emptySet(),
    val senderFilters: Set<GlobalSearchSenderFilter> = emptySet(),
    val dateFilterSelection: GlobalSearchDateFilterSelection = GlobalSearchDateFilterSelection.AnyTime,
    val contentFilterSelection: GlobalSearchContentFilterSelection = GlobalSearchContentFilterSelection.EMPTY,
) {
    /** A category picker (dialog or sheet) is showing. */
    val filterSheetOpen: Boolean
        get() = openFilterCategory != null

    /** Senders, date and content only ever match messages, so plain chat rows hide while one is active. */
    val messageFiltersActive: Boolean
        get() =
            senderFilters.isNotEmpty() ||
                dateFilterSelection != GlobalSearchDateFilterSelection.AnyTime ||
                contentFilterSelection.isActive

    val hasActiveFilters: Boolean
        get() =
            folderFilters.isNotEmpty() ||
                chatTypeFilters.isNotEmpty() ||
                chatFilters.isNotEmpty() ||
                messageFiltersActive

    /** Whether the category holds at least one active filter. */
    fun isCategoryActive(category: GlobalSearchFilterCategory): Boolean =
        when (category) {
            GlobalSearchFilterCategory.Folder -> folderFilters.isNotEmpty()
            GlobalSearchFilterCategory.ChatType -> chatTypeFilters.isNotEmpty()
            GlobalSearchFilterCategory.Chat -> chatFilters.isNotEmpty()
            GlobalSearchFilterCategory.Sender -> senderFilters.isNotEmpty()
            GlobalSearchFilterCategory.Date -> dateFilterSelection != GlobalSearchDateFilterSelection.AnyTime
            GlobalSearchFilterCategory.Content -> contentFilterSelection.isActive
        }
}

/** The prototype's chat-type filter: a direct chat or a group. */
internal enum class GlobalSearchChatType {
    DIRECT,
    GROUPS,
}

internal data class GlobalSearchChatFilter(
    val stableId: String,
    val displayLabel: String,
) {
    val chipId: String = "chat:$stableId"
}

internal data class GlobalSearchSenderFilter(
    val stableId: String,
    val displayLabel: String,
) {
    val chipId: String = "sender:$stableId"
}

internal data class GlobalSearchAccountScope(
    val accountRef: String?,
    val runtimeGeneration: Int,
) {
    fun encodeToken(): String =
        encodeGlobalSearchCodecString(accountRef.orEmpty()) +
            GLOBAL_SEARCH_SCOPE_SEPARATOR +
            runtimeGeneration.toString()

    companion object {
        fun from(
            accountRef: String?,
            runtimeGeneration: Int,
        ): GlobalSearchAccountScope = GlobalSearchAccountScope(accountRef, runtimeGeneration)
    }
}

/** Menu order of the prototype's filter categories. */
internal enum class GlobalSearchFilterCategory {
    Folder,
    ChatType,
    Chat,
    Sender,
    Date,
    Content,
}

internal data class GlobalSearchActiveChip(
    val chipId: String,
    val displayLabel: String,
    val category: GlobalSearchFilterCategory,
)

internal data class GlobalSearchActiveChipList(
    val items: List<GlobalSearchActiveChip>,
) {
    val count: Int = items.size
}

/** Chips follow the prototype's category order, with a stable id order inside each category. */
internal object GlobalSearchActiveChips {
    /** Active chips in the prototype's category order. */
    fun from(state: GlobalSearchState): GlobalSearchActiveChipList {
        val chips =
            state.folderFilters.sorted().map {
                GlobalSearchActiveChip(GLOBAL_SEARCH_FOLDER_CHIP_PREFIX + it, "", GlobalSearchFilterCategory.Folder)
            } +
                GlobalSearchChatType.entries.filter { it in state.chatTypeFilters }.map {
                    GlobalSearchActiveChip(
                        GLOBAL_SEARCH_TYPE_CHIP_PREFIX + it.name,
                        "",
                        GlobalSearchFilterCategory.ChatType,
                    )
                } +
                state.chatFilters.sortedBy { it.chipId }.map {
                    GlobalSearchActiveChip(it.chipId, it.displayLabel, GlobalSearchFilterCategory.Chat)
                } +
                state.senderFilters.sortedBy { it.chipId }.map {
                    GlobalSearchActiveChip(it.chipId, it.displayLabel, GlobalSearchFilterCategory.Sender)
                } +
                globalSearchDateChip(state.dateFilterSelection) +
                globalSearchContentChips(state.contentFilterSelection)
        return GlobalSearchActiveChipList(chips)
    }

    /** The single date chip, or none when the selection is Any time. */
    private fun globalSearchDateChip(selection: GlobalSearchDateFilterSelection): List<GlobalSearchActiveChip> =
        when (selection) {
            GlobalSearchDateFilterSelection.AnyTime -> emptyList()
            else ->
                listOf(
                    GlobalSearchActiveChip(
                        chipId = GLOBAL_SEARCH_DATE_CHIP_PREFIX + globalSearchDateCodecKey(selection),
                        displayLabel = "",
                        category = GlobalSearchFilterCategory.Date,
                    ),
                )
        }

    /** One chip per selected content kind, in a stable order. */
    private fun globalSearchContentChips(selection: GlobalSearchContentFilterSelection): List<GlobalSearchActiveChip> =
        GlobalSearchContentKind.entries
            .filter { it in selection.selectedKinds }
            .map { kind ->
                GlobalSearchActiveChip(
                    chipId = GLOBAL_SEARCH_CONTENT_CHIP_PREFIX + kind.name,
                    displayLabel = "",
                    category = GlobalSearchFilterCategory.Content,
                )
            }.sortedBy { it.chipId }
}

private const val GLOBAL_SEARCH_FOLDER_CHIP_PREFIX = "folder:"
private const val GLOBAL_SEARCH_TYPE_CHIP_PREFIX = "type:"
private const val GLOBAL_SEARCH_DATE_CHIP_PREFIX = "date:"
private const val GLOBAL_SEARCH_CONTENT_CHIP_PREFIX = "content:"

private fun globalSearchDateCodecKey(selection: GlobalSearchDateFilterSelection): String =
    when (selection) {
        GlobalSearchDateFilterSelection.AnyTime -> "any"
        GlobalSearchDateFilterSelection.Today -> "today"
        GlobalSearchDateFilterSelection.Last7Days -> "last7"
        GlobalSearchDateFilterSelection.Last30Days -> "last30"
        is GlobalSearchDateFilterSelection.Custom -> "custom"
    }

internal data class GlobalSearchCandidate(
    val textMatches: Boolean = false,
    val chatId: String? = null,
    val senderId: String? = null,
    val dateCodecKey: String? = null,
    val contentKinds: Set<GlobalSearchContentKind> = emptySet(),
)

/**
 * Query semantics for structured filters: AND across non-empty categories, OR within
 * each category. Encoded for tests and the message-search constraints.
 */
internal data class GlobalSearchQueryAlgebra(
    val activeCategoryCount: Int,
    private val queryText: String,
    private val folderIds: Set<String>,
    private val chatTypes: Set<GlobalSearchChatType>,
    private val chatIds: Set<String>,
    private val senderIds: Set<String>,
    private val dateCodecKey: String?,
    private val contentKinds: Set<GlobalSearchContentKind>,
) {
    /** Whether the encoded value belongs to the category's active set. */
    fun matchesCategory(
        category: GlobalSearchFilterCategory,
        value: String,
    ): Boolean =
        when (category) {
            GlobalSearchFilterCategory.Folder -> value in folderIds
            GlobalSearchFilterCategory.ChatType ->
                runCatching { GlobalSearchChatType.valueOf(value) }.getOrNull() in chatTypes
            GlobalSearchFilterCategory.Chat -> value in chatIds
            GlobalSearchFilterCategory.Sender -> value in senderIds
            GlobalSearchFilterCategory.Date -> dateCodecKey == value
            GlobalSearchFilterCategory.Content ->
                runCatching { GlobalSearchContentKind.valueOf(value) }.getOrNull() in contentKinds
        }

    /** Whether a candidate satisfies the query text and every active category. */
    fun matches(candidate: GlobalSearchCandidate): Boolean =
        (queryText.isBlank() || candidate.textMatches) &&
            (chatIds.isEmpty() || candidate.chatId in chatIds) &&
            (senderIds.isEmpty() || candidate.senderId in senderIds) &&
            (dateCodecKey == null || candidate.dateCodecKey == dateCodecKey) &&
            (contentKinds.isEmpty() || contentKinds.intersect(candidate.contentKinds).isNotEmpty())

    companion object {
        /** Snapshots the state's filters into the algebra. */
        fun from(state: GlobalSearchState): GlobalSearchQueryAlgebra {
            val dateActive = state.dateFilterSelection !is GlobalSearchDateFilterSelection.AnyTime
            val categories = GlobalSearchFilterCategory.entries.count(state::isCategoryActive)
            val dateCodecKey =
                if (dateActive) {
                    globalSearchDateCodecKey(state.dateFilterSelection)
                } else {
                    null
                }
            return GlobalSearchQueryAlgebra(
                activeCategoryCount = categories,
                queryText = state.query,
                folderIds = state.folderFilters,
                chatTypes = state.chatTypeFilters,
                chatIds = state.chatFilters.map { it.stableId }.toSet(),
                senderIds = state.senderFilters.map { it.stableId }.toSet(),
                dateCodecKey = dateCodecKey,
                contentKinds = state.contentFilterSelection.selectedKinds,
            )
        }
    }
}

/** Projects the query, date and content filters into the MDK-facing request. */
internal fun GlobalSearchState.projectSearchRequest(
    zoneId: ZoneId,
    nowMillis: Long,
): GlobalSearchRequestProjection =
    projectGlobalSearchRequest(
        query = query,
        dateFilter = dateFilterSelection,
        contentFilter = contentFilterSelection,
        zoneId = zoneId,
        nowMillis = nowMillis,
    )

/**
 * Closing search resets the transient search UI (query, filters, open picker) so the next
 * open starts clean. Opening a conversation while search remains active does not
 * invoke this transition and therefore preserves state.
 */
@Suppress("TooManyFunctions")
internal object GlobalSearchTransitions {
    fun openSearch(state: GlobalSearchState): GlobalSearchState = state.copy(isOpen = true)

    /** Closes search and resets the transient UI, keeping only the account scope token. */
    fun closeSearch(state: GlobalSearchState): GlobalSearchState =
        GlobalSearchState(
            accountScopeToken = state.accountScopeToken,
        )

    /** Replaces the query text. */
    fun setQuery(
        state: GlobalSearchState,
        query: String,
    ): GlobalSearchState = state.copy(query = query)

    /** Opens the picker for one filter category. */
    fun openFilterCategory(
        state: GlobalSearchState,
        category: GlobalSearchFilterCategory,
    ): GlobalSearchState = state.copy(openFilterCategory = category)

    /** Closes the open picker. */
    fun dismissFilterSheet(state: GlobalSearchState): GlobalSearchState = state.copy(openFilterCategory = null)

    /** Adds or removes one folder filter. */
    fun toggleFolderFilter(
        state: GlobalSearchState,
        folderId: String,
    ): GlobalSearchState =
        state.copy(
            folderFilters =
                if (folderId in state.folderFilters) {
                    state.folderFilters - folderId
                } else {
                    state.folderFilters + folderId
                },
        )

    /** Adds or removes one chat type filter. */
    fun toggleChatTypeFilter(
        state: GlobalSearchState,
        type: GlobalSearchChatType,
    ): GlobalSearchState =
        state.copy(
            chatTypeFilters =
                if (type in state.chatTypeFilters) {
                    state.chatTypeFilters - type
                } else {
                    state.chatTypeFilters + type
                },
        )

    /** Adds a chat filter, replacing an earlier label for the same id. */
    fun applyChatFilter(
        state: GlobalSearchState,
        filter: GlobalSearchChatFilter,
    ): GlobalSearchState =
        state.copy(
            chatFilters = state.chatFilters.filterNot { it.stableId == filter.stableId }.toSet() + filter,
        )

    /** Picker rows toggle: a chat already chosen leaves the set, any other joins it. */
    fun toggleChatFilter(
        state: GlobalSearchState,
        filter: GlobalSearchChatFilter,
    ): GlobalSearchState =
        if (state.chatFilters.any { it.stableId == filter.stableId }) {
            removeFilter(state, filter.chipId)
        } else {
            applyChatFilter(state, filter)
        }

    /** Adds a sender filter, replacing an earlier label for the same id. */
    fun applySenderFilter(
        state: GlobalSearchState,
        filter: GlobalSearchSenderFilter,
    ): GlobalSearchState =
        state.copy(
            senderFilters = state.senderFilters.filterNot { it.stableId == filter.stableId }.toSet() + filter,
        )

    /** Picker rows toggle: a sender already chosen leaves the set, any other joins it. */
    fun toggleSenderFilter(
        state: GlobalSearchState,
        filter: GlobalSearchSenderFilter,
    ): GlobalSearchState =
        if (state.senderFilters.any { it.stableId == filter.stableId }) {
            removeFilter(state, filter.chipId)
        } else {
            applySenderFilter(state, filter)
        }

    /** Replaces the date selection. */
    fun applyDateFilter(
        state: GlobalSearchState,
        selection: GlobalSearchDateFilterSelection,
    ): GlobalSearchState = state.copy(dateFilterSelection = selection)

    /** Replaces the content kind selection. */
    fun setContentFilterSelection(
        state: GlobalSearchState,
        selection: GlobalSearchContentFilterSelection,
    ): GlobalSearchState = state.copy(contentFilterSelection = selection)

    /** Removes the filter a chip stands for, by category prefix. */
    fun removeFilter(
        state: GlobalSearchState,
        chipId: String,
    ): GlobalSearchState =
        when {
            chipId.startsWith(GLOBAL_SEARCH_FOLDER_CHIP_PREFIX) ->
                state.copy(folderFilters = state.folderFilters - chipId.removePrefix(GLOBAL_SEARCH_FOLDER_CHIP_PREFIX))
            chipId.startsWith(GLOBAL_SEARCH_TYPE_CHIP_PREFIX) -> {
                val type =
                    runCatching {
                        GlobalSearchChatType.valueOf(chipId.removePrefix(GLOBAL_SEARCH_TYPE_CHIP_PREFIX))
                    }.getOrNull()
                if (type == null) state else state.copy(chatTypeFilters = state.chatTypeFilters - type)
            }
            chipId.startsWith("chat:") ->
                state.copy(chatFilters = state.chatFilters.filterNot { it.chipId == chipId }.toSet())
            chipId.startsWith("sender:") ->
                state.copy(senderFilters = state.senderFilters.filterNot { it.chipId == chipId }.toSet())
            chipId.startsWith(GLOBAL_SEARCH_DATE_CHIP_PREFIX) ->
                state.copy(dateFilterSelection = GlobalSearchDateFilterSelection.AnyTime)
            chipId.startsWith(GLOBAL_SEARCH_CONTENT_CHIP_PREFIX) -> {
                val kindName = chipId.removePrefix(GLOBAL_SEARCH_CONTENT_CHIP_PREFIX)
                val kind = runCatching { GlobalSearchContentKind.valueOf(kindName) }.getOrNull()
                if (kind == null) {
                    state
                } else {
                    state.copy(
                        contentFilterSelection =
                            GlobalSearchContentFilterSelection(
                                state.contentFilterSelection.selectedKinds - kind,
                            ),
                    )
                }
            }
            else -> state
        }

    /** Clears every filter while keeping search open and the query. */
    fun clearAllFilters(state: GlobalSearchState): GlobalSearchState =
        state.copy(
            folderFilters = emptySet(),
            chatTypeFilters = emptySet(),
            chatFilters = emptySet(),
            senderFilters = emptySet(),
            dateFilterSelection = GlobalSearchDateFilterSelection.AnyTime,
            contentFilterSelection = GlobalSearchContentFilterSelection.EMPTY,
        )

    /**
     * Folders that were deleted and chats that left the folder / chat-type scope drop out of the
     * filters, as the prototype's `reconcile` does, so a chip never names something the list cannot show.
     */
    fun reconcileAvailable(
        state: GlobalSearchState,
        availableFolderIds: Set<String>?,
        availableChatIds: Set<String>?,
    ): GlobalSearchState {
        val folders = availableFolderIds?.let { state.folderFilters.intersect(it) } ?: state.folderFilters
        val chats =
            availableChatIds?.let { ids -> state.chatFilters.filter { it.stableId in ids }.toSet() }
                ?: state.chatFilters
        return if (folders == state.folderFilters && chats == state.chatFilters) {
            state
        } else {
            state.copy(folderFilters = folders, chatFilters = chats)
        }
    }

    /**
     * Account/runtime generation changes clear account-scoped folder/chat/sender ids
     * and preserve account-independent query/type/date/content filters plus open state.
     */
    fun reconcileAccountScope(
        state: GlobalSearchState,
        scope: GlobalSearchAccountScope,
    ): GlobalSearchState {
        val nextToken = scope.encodeToken()
        return if (state.accountScopeToken == nextToken) {
            state
        } else {
            state.copy(
                accountScopeToken = nextToken,
                folderFilters = emptySet(),
                chatFilters = emptySet(),
                senderFilters = emptySet(),
            )
        }
    }
}

private const val GLOBAL_SEARCH_CODEC_VERSION = 4
private const val GLOBAL_SEARCH_CODEC_FIELD_COUNT = 11
private const val GLOBAL_SEARCH_FIELD_SEPARATOR = ""
private const val GLOBAL_SEARCH_LIST_SEPARATOR = ""
private const val GLOBAL_SEARCH_SCOPE_SEPARATOR = ""

/** Base64 encoding that keeps separators out of the payload. */
private fun encodeGlobalSearchCodecString(value: String): String =
    Base64
        .getEncoder()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

/** Base64 decoding that yields null for malformed input. */
private fun decodeGlobalSearchCodecString(encoded: String): String? =
    runCatching {
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

/** Encodes a set of ids as a sorted, Base64-per-item list. */
private fun encodeStringList(values: Collection<String>): String =
    values.sorted().joinToString(GLOBAL_SEARCH_LIST_SEPARATOR, transform = ::encodeGlobalSearchCodecString)

/** Decodes an id list; null when any item is malformed. */
private fun decodeStringList(encoded: String): Set<String>? =
    if (encoded.isEmpty()) {
        emptySet()
    } else {
        val values = encoded.split(GLOBAL_SEARCH_LIST_SEPARATOR).map(::decodeGlobalSearchCodecString)
        if (values.any { it == null }) null else values.filterNotNull().toSet()
    }

private fun encodeLabeledFilters(filters: Collection<Pair<String, String>>): String =
    filters
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString(GLOBAL_SEARCH_LIST_SEPARATOR) { (stableId, displayLabel) ->
            "${encodeGlobalSearchCodecString(stableId)}:${encodeGlobalSearchCodecString(displayLabel)}"
        }

/** Decodes id / label pairs; null when any token is malformed. */
@Suppress("ReturnCount")
private fun decodeLabeledFilters(encoded: String): Set<Pair<String, String>>? {
    if (encoded.isEmpty()) return emptySet()
    return encoded
        .split(GLOBAL_SEARCH_LIST_SEPARATOR)
        .map { token ->
            val separatorIndex = token.indexOf(':')
            if (separatorIndex < 0 || separatorIndex != token.lastIndexOf(':')) return null
            val stableId = decodeGlobalSearchCodecString(token.substring(0, separatorIndex)) ?: return null
            val displayLabel = decodeGlobalSearchCodecString(token.substring(separatorIndex + 1)) ?: return null
            stableId to displayLabel
        }.toSet()
}

/** Encodes the state into the versioned saveable string. */
internal fun encodeGlobalSearchState(state: GlobalSearchState): String {
    val chatTokens =
        encodeLabeledFilters(state.chatFilters.map { it.stableId to it.displayLabel })
    val senderTokens =
        encodeLabeledFilters(state.senderFilters.map { it.stableId to it.displayLabel })
    val dateToken = encodeGlobalSearchCodecString(encodeGlobalSearchDateFilter(state.dateFilterSelection))
    val contentToken = encodeGlobalSearchCodecString(encodeGlobalSearchContentFilter(state.contentFilterSelection))
    return listOf(
        GLOBAL_SEARCH_CODEC_VERSION.toString(),
        state.isOpen.toString(),
        encodeGlobalSearchCodecString(state.query),
        state.openFilterCategory?.name.orEmpty(),
        encodeGlobalSearchCodecString(state.accountScopeToken),
        encodeStringList(state.folderFilters),
        encodeStringList(state.chatTypeFilters.map { it.name }),
        chatTokens,
        senderTokens,
        dateToken,
        contentToken,
    ).joinToString(GLOBAL_SEARCH_FIELD_SEPARATOR)
}

/** Decodes the saveable string, falling back to an empty state for any other version or shape. */
internal fun decodeGlobalSearchState(encoded: String): GlobalSearchState {
    val fields = encoded.takeIf { it.isNotBlank() }?.split(GLOBAL_SEARCH_FIELD_SEPARATOR)
    return if (
        fields != null &&
        fields.size == GLOBAL_SEARCH_CODEC_FIELD_COUNT &&
        fields[0].toIntOrNull() == GLOBAL_SEARCH_CODEC_VERSION
    ) {
        decodeGlobalSearchStateFields(fields) ?: GlobalSearchState()
    } else {
        GlobalSearchState()
    }
}

/** Decodes the versioned fields; null when any field is malformed. */
@Suppress("ReturnCount")
private fun decodeGlobalSearchStateFields(fields: List<String>): GlobalSearchState? {
    val query = decodeGlobalSearchCodecString(fields[2]) ?: return null
    val openCategory =
        fields[3].takeIf { it.isNotEmpty() }?.let { name ->
            runCatching { GlobalSearchFilterCategory.valueOf(name) }.getOrNull() ?: return null
        }
    val accountScopeToken = decodeGlobalSearchCodecString(fields[4]) ?: return null
    val folderFilters = decodeStringList(fields[5]) ?: return null
    val chatTypeFilters =
        decodeStringList(fields[6])
            ?.map { name -> runCatching { GlobalSearchChatType.valueOf(name) }.getOrNull() ?: return null }
            ?.toSet()
            ?: return null
    val chatFilters =
        decodeLabeledFilters(fields[7])
            ?.map { (stableId, displayLabel) ->
                GlobalSearchChatFilter(stableId, displayLabel)
            }?.toSet()
            ?: return null
    val senderFilters =
        decodeLabeledFilters(fields[8])
            ?.map { (stableId, displayLabel) ->
                GlobalSearchSenderFilter(stableId, displayLabel)
            }?.toSet()
            ?: return null
    val dateEncoded = decodeGlobalSearchCodecString(fields[9]) ?: return null
    val contentEncoded = decodeGlobalSearchCodecString(fields[10]) ?: return null
    return GlobalSearchState(
        isOpen = fields[1].toBooleanStrictOrNull() ?: return null,
        query = query,
        openFilterCategory = openCategory,
        accountScopeToken = accountScopeToken,
        folderFilters = folderFilters,
        chatTypeFilters = chatTypeFilters,
        chatFilters = chatFilters,
        senderFilters = senderFilters,
        dateFilterSelection = decodeGlobalSearchDateFilter(dateEncoded),
        contentFilterSelection = decodeGlobalSearchContentFilter(contentEncoded),
    )
}

internal val GlobalSearchStateSaver: Saver<GlobalSearchState, String> =
    Saver(
        save = { encodeGlobalSearchState(it) },
        restore = { decodeGlobalSearchState(it) },
    )
