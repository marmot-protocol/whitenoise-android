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
 * Structured filters are modeled here for UI; typed date/content projection is explicit until mdk#1363.
 */
internal data class GlobalSearchState(
    val isOpen: Boolean = false,
    val query: String = "",
    val filterSheetOpen: Boolean = false,
    val accountScopeToken: String = "",
    val chatFilters: Set<GlobalSearchChatFilter> = emptySet(),
    val senderFilters: Set<GlobalSearchSenderFilter> = emptySet(),
    val dateFilterSelection: GlobalSearchDateFilterSelection = GlobalSearchDateFilterSelection.AnyTime,
    val contentFilterSelection: GlobalSearchContentFilterSelection = GlobalSearchContentFilterSelection.EMPTY,
)

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

internal enum class GlobalSearchFilterCategory {
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

internal object GlobalSearchActiveChips {
    fun from(state: GlobalSearchState): GlobalSearchActiveChipList {
        val chips =
            state.chatFilters.map {
                GlobalSearchActiveChip(it.chipId, it.displayLabel, GlobalSearchFilterCategory.Chat)
            } +
                state.senderFilters.map {
                    GlobalSearchActiveChip(it.chipId, it.displayLabel, GlobalSearchFilterCategory.Sender)
                } +
                globalSearchDateChip(state.dateFilterSelection) +
                globalSearchContentChips(state.contentFilterSelection)
        return GlobalSearchActiveChipList(chips.sortedBy(GlobalSearchActiveChip::chipId))
    }

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

    private fun globalSearchContentChips(selection: GlobalSearchContentFilterSelection): List<GlobalSearchActiveChip> =
        GlobalSearchContentKind.entries
            .filter { it in selection.selectedKinds }
            .map { kind ->
                GlobalSearchActiveChip(
                    chipId = GLOBAL_SEARCH_CONTENT_CHIP_PREFIX + kind.name,
                    displayLabel = "",
                    category = GlobalSearchFilterCategory.Content,
                )
            }
}

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
 * each category. Not wired to list filtering yet; encoded for tests and future MDK search.
 */
internal data class GlobalSearchQueryAlgebra(
    val activeCategoryCount: Int,
    private val queryText: String,
    private val chatIds: Set<String>,
    private val senderIds: Set<String>,
    private val dateCodecKey: String?,
    private val contentKinds: Set<GlobalSearchContentKind>,
) {
    fun matchesCategory(
        category: GlobalSearchFilterCategory,
        value: String,
    ): Boolean =
        when (category) {
            GlobalSearchFilterCategory.Chat -> value in chatIds
            GlobalSearchFilterCategory.Sender -> value in senderIds
            GlobalSearchFilterCategory.Date -> dateCodecKey == value
            GlobalSearchFilterCategory.Content ->
                runCatching { GlobalSearchContentKind.valueOf(value) }.getOrNull() in contentKinds
        }

    fun matches(candidate: GlobalSearchCandidate): Boolean =
        (queryText.isBlank() || candidate.textMatches) &&
            (chatIds.isEmpty() || candidate.chatId in chatIds) &&
            (senderIds.isEmpty() || candidate.senderId in senderIds) &&
            (dateCodecKey == null || candidate.dateCodecKey == dateCodecKey) &&
            (contentKinds.isEmpty() || contentKinds.intersect(candidate.contentKinds).isNotEmpty())

    companion object {
        fun from(state: GlobalSearchState): GlobalSearchQueryAlgebra {
            val dateActive = state.dateFilterSelection !is GlobalSearchDateFilterSelection.AnyTime
            val categories =
                listOf(
                    state.chatFilters.isNotEmpty(),
                    state.senderFilters.isNotEmpty(),
                    dateActive,
                    state.contentFilterSelection.isActive,
                ).count { it }
            val dateCodecKey =
                if (dateActive) {
                    globalSearchDateCodecKey(state.dateFilterSelection)
                } else {
                    null
                }
            return GlobalSearchQueryAlgebra(
                activeCategoryCount = categories,
                queryText = state.query,
                chatIds = state.chatFilters.map { it.stableId }.toSet(),
                senderIds = state.senderFilters.map { it.stableId }.toSet(),
                dateCodecKey = dateCodecKey,
                contentKinds = state.contentFilterSelection.selectedKinds,
            )
        }
    }
}

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
 * Closing search resets the transient search UI (query, filters, sheet) so the next
 * open starts clean. Opening a conversation while search remains active does not
 * invoke this transition and therefore preserves state.
 */
@Suppress("TooManyFunctions")
internal object GlobalSearchTransitions {
    fun openSearch(state: GlobalSearchState): GlobalSearchState = state.copy(isOpen = true)

    fun closeSearch(state: GlobalSearchState): GlobalSearchState =
        GlobalSearchState(
            accountScopeToken = state.accountScopeToken,
        )

    fun setQuery(
        state: GlobalSearchState,
        query: String,
    ): GlobalSearchState = state.copy(query = query)

    fun openFilterSheet(state: GlobalSearchState): GlobalSearchState = state.copy(filterSheetOpen = true)

    fun dismissFilterSheet(state: GlobalSearchState): GlobalSearchState = state.copy(filterSheetOpen = false)

    fun applyChatFilter(
        state: GlobalSearchState,
        filter: GlobalSearchChatFilter,
    ): GlobalSearchState =
        state.copy(
            chatFilters = state.chatFilters.filterNot { it.stableId == filter.stableId }.toSet() + filter,
        )

    fun applySenderFilter(
        state: GlobalSearchState,
        filter: GlobalSearchSenderFilter,
    ): GlobalSearchState =
        state.copy(
            senderFilters = state.senderFilters.filterNot { it.stableId == filter.stableId }.toSet() + filter,
        )

    fun applyDateFilter(
        state: GlobalSearchState,
        selection: GlobalSearchDateFilterSelection,
    ): GlobalSearchState = state.copy(dateFilterSelection = selection)

    fun setContentFilterSelection(
        state: GlobalSearchState,
        selection: GlobalSearchContentFilterSelection,
    ): GlobalSearchState = state.copy(contentFilterSelection = selection)

    fun removeFilter(
        state: GlobalSearchState,
        chipId: String,
    ): GlobalSearchState =
        when {
            chipId.startsWith("chat:") ->
                state.copy(chatFilters = state.chatFilters.filterNot { it.chipId == chipId }.toSet())
            chipId.startsWith("sender:") ->
                state.copy(senderFilters = state.senderFilters.filterNot { it.chipId == chipId }.toSet())
            chipId.startsWith("date:") ->
                state.copy(dateFilterSelection = GlobalSearchDateFilterSelection.AnyTime)
            chipId.startsWith("content:") -> {
                val kindName = chipId.removePrefix("content:")
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

    fun clearAllFilters(state: GlobalSearchState): GlobalSearchState =
        state.copy(
            chatFilters = emptySet(),
            senderFilters = emptySet(),
            dateFilterSelection = GlobalSearchDateFilterSelection.AnyTime,
            contentFilterSelection = GlobalSearchContentFilterSelection.EMPTY,
        )

    /**
     * Account/runtime generation changes clear account-scoped chat/sender stable ids
     * and preserve account-independent query/date/content filters plus open state.
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
                chatFilters = emptySet(),
                senderFilters = emptySet(),
            )
        }
    }
}

private const val GLOBAL_SEARCH_CODEC_VERSION = 3
private const val GLOBAL_SEARCH_CODEC_FIELD_COUNT = 9
private const val GLOBAL_SEARCH_FIELD_SEPARATOR = "\u001f"
private const val GLOBAL_SEARCH_LIST_SEPARATOR = "\u001e"
private const val GLOBAL_SEARCH_SCOPE_SEPARATOR = "\u001d"

private fun encodeGlobalSearchCodecString(value: String): String =
    Base64
        .getEncoder()
        .encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeGlobalSearchCodecString(encoded: String): String? =
    runCatching {
        String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
    }.getOrNull()

private fun encodeLabeledFilters(filters: Collection<Pair<String, String>>): String =
    filters
        .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
        .joinToString(GLOBAL_SEARCH_LIST_SEPARATOR) { (stableId, displayLabel) ->
            "${encodeGlobalSearchCodecString(stableId)}:${encodeGlobalSearchCodecString(displayLabel)}"
        }

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
        state.filterSheetOpen.toString(),
        encodeGlobalSearchCodecString(state.accountScopeToken),
        chatTokens,
        senderTokens,
        dateToken,
        contentToken,
    ).joinToString(GLOBAL_SEARCH_FIELD_SEPARATOR)
}

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

@Suppress("ReturnCount")
private fun decodeGlobalSearchStateFields(fields: List<String>): GlobalSearchState? {
    val query = decodeGlobalSearchCodecString(fields[2]) ?: return null
    val accountScopeToken = decodeGlobalSearchCodecString(fields[4]) ?: return null
    val chatFilters =
        decodeLabeledFilters(fields[5])
            ?.map { (stableId, displayLabel) ->
                GlobalSearchChatFilter(stableId, displayLabel)
            }?.toSet()
            ?: return null
    val senderFilters =
        decodeLabeledFilters(fields[6])
            ?.map { (stableId, displayLabel) ->
                GlobalSearchSenderFilter(stableId, displayLabel)
            }?.toSet()
            ?: return null
    val dateEncoded = decodeGlobalSearchCodecString(fields[7]) ?: return null
    val contentEncoded = decodeGlobalSearchCodecString(fields[8]) ?: return null
    return GlobalSearchState(
        isOpen = fields[1].toBooleanStrictOrNull() ?: return null,
        query = query,
        filterSheetOpen = fields[3].toBooleanStrictOrNull() ?: return null,
        accountScopeToken = accountScopeToken,
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
