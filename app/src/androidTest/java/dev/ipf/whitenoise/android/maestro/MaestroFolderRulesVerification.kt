package dev.ipf.whitenoise.android.maestro

import android.content.Context
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatFolderAccountState
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.ChatFolderSort
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Capture real existing folders, memberships, exclusions and rules before the editor can mutate them. */
internal suspend fun captureMaestroFolderRules(state: WhiteNoiseAppState): MaestroFolderRulesBaseline =
    withContext(Dispatchers.Main.immediate) {
        check(state.accounts.size == 3)
        state.accounts.forEach { state.chatFolderPreferences.foldersFor(it.label) }
        MaestroFolderRulesBaseline(
            checkNotNull(state.activeAccountRef),
            state.chatFolderPreferences.state.value
                .toMap(),
        )
    }

/** Require the complete local store and a fresh production reader, including unchanged other accounts. */
internal suspend fun verifyMaestroFolderRules(
    context: Context,
    state: WhiteNoiseAppState,
    baseline: MaestroFolderRulesBaseline?,
    postcondition: String?,
): Boolean {
    if (
        postcondition == null ||
        (!postcondition.startsWith("smart-rule-") && !postcondition.startsWith("folder-details-"))
    ) return false
    val before = checkNotNull(baseline)
    check(before.accounts.size == 3)
    val expected = expectedMaestroFolderRule(postcondition)
    val details = expectedMaestroFolderDetails(postcondition)
    return withTimeoutOrNull(15_000L) {
        var matches = false
        while (!matches) {
            matches =
                withContext(Dispatchers.Main.immediate) {
                    val current = state.chatFolderPreferences.state.value
                    val fresh = ChatFolderPreferences(context)
                    before.accounts.keys.forEach { fresh.foldersFor(it) }
                    state.activeAccountRef == before.owner &&
                        current == fresh.state.value &&
                        current.keys == before.accounts.keys &&
                        before.accounts.all { (account, original) ->
                            if (account == before.owner) {
                                maestroFolderRuleMatches(checkNotNull(current[account]), original, expected, details)
                            } else {
                                current[account] == original
                            }
                        }
                }
            if (!matches) delay(100L)
        }
        true
    } ?: false
}

private fun expectedMaestroFolderRule(postcondition: String): SmartFolderFilter.Group? {
    val defaults =
        listOf(
            SmartFolderFilter.Condition(FolderField.ARCHIVED, FolderMode.NONE),
            SmartFolderFilter.Condition(FolderField.ACCEPTED),
        )
    val readCondition = SmartFolderFilter.Condition(FolderField.UNREAD, FolderMode.NONE)
    val read = SmartFolderFilter.Group(children = defaults + readCondition)
    return when (postcondition) {
        "smart-rule-absent", "folder-details-absent" -> null
        "folder-details-saved", "folder-details-cleared", "folder-details-unread" -> null
        "smart-rule-read" -> read
        "smart-rule-any" -> read.copy(all = false)
        "smart-rule-excluded" -> read.copy(not = true)
        "smart-rule-mentions" ->
            SmartFolderFilter.Group(children = defaults + SmartFolderFilter.Condition(FolderField.MENTIONS))
        "smart-rule-unread" ->
            SmartFolderFilter.Group(
                children = defaults + SmartFolderFilter.Condition(FolderField.UNREAD, FolderMode.PRESENT),
            )
        "smart-rule-defaults" -> SmartFolderFilter.Group(children = defaults)
        "smart-rule-title" ->
            SmartFolderFilter.Group(
                children =
                    defaults + SmartFolderFilter.Condition(FolderField.TITLE, FolderMode.CONTAINS, setOf("Maestro")),
            )
        else -> error("Unknown smart-folder rule postcondition")
    }
}

private fun maestroFolderRuleMatches(
    actual: ChatFolderAccountState,
    before: ChatFolderAccountState,
    expected: SmartFolderFilter.Group?,
    details: ExpectedMaestroFolderDetails?,
): Boolean {
    if (expected == null && details == null) return actual == before
    val added = actual.folders.filter { folder -> before.folders.none { it.id == folder.id } }
    val folder = added.singleOrNull()
    val rule = folder?.let { actual.rules[it.id] }
    return if (folder != null && (details != null || rule != null)) {
        val expectedFolder = expectedMaestroFolder(folder.id, before, details)
        val expectedState =
            before.copy(
                folders = before.folders + expectedFolder,
                membership = before.membership + (folder.id to emptySet()),
                exclusions = before.exclusions + (folder.id to emptySet()),
                rules =
                    if (rule == null) {
                        before.rules
                    } else {
                        before.rules + (folder.id to ChatFolderRule(smartFilter = rule.smartFilter))
                    },
            )
        val ruleMatches =
            if (details != null) {
                rule == null
            } else {
                rule?.smartFilter?.let(SmartFolderCodec::decode) == expected
            }
        actual == expectedState && ruleMatches
    } else {
        false
    }
}

private fun expectedMaestroFolder(
    id: String,
    before: ChatFolderAccountState,
    details: ExpectedMaestroFolderDetails?,
): ChatFolder =
    ChatFolder(
        id = id,
        name = if (details != null) "Maestro saved folder" else "Maestro rules folder",
        description = details?.description ?: "Maestro rules description",
        order = (before.folders.maxOfOrNull { it.order } ?: -1) + 1,
        systemKind = null,
        showWhenEmpty = details?.showWhenEmpty ?: false,
        sort = details?.sort ?: ChatFolderSort.RECENT,
    )

internal fun maestroFolderReceiptFlag(
    postcondition: String?,
    prefix: String,
    storeVerified: Boolean,
): Boolean = postcondition?.startsWith(prefix) == true && storeVerified

private data class ExpectedMaestroFolderDetails(
    val description: String,
    val showWhenEmpty: Boolean,
    val sort: ChatFolderSort,
)

private fun expectedMaestroFolderDetails(postcondition: String): ExpectedMaestroFolderDetails? =
    when (postcondition) {
        "folder-details-saved" -> ExpectedMaestroFolderDetails("Maestro folder note", true, ChatFolderSort.NAME)
        "folder-details-cleared" -> ExpectedMaestroFolderDetails("", false, ChatFolderSort.RECENT)
        "folder-details-unread" -> ExpectedMaestroFolderDetails("", false, ChatFolderSort.UNREAD)
        else -> null
    }
