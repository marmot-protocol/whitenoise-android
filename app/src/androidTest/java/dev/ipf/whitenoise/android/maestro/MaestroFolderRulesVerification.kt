package dev.ipf.whitenoise.android.maestro

import android.content.Context
import dev.ipf.whitenoise.android.state.ChatFolder
import dev.ipf.whitenoise.android.state.ChatFolderAccountState
import dev.ipf.whitenoise.android.state.ChatFolderPreferences
import dev.ipf.whitenoise.android.state.ChatFolderRule
import dev.ipf.whitenoise.android.state.FolderField
import dev.ipf.whitenoise.android.state.FolderMode
import dev.ipf.whitenoise.android.state.SmartFolderCodec
import dev.ipf.whitenoise.android.state.SmartFolderFilter
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal data class MaestroFolderRulesBaseline(
    val owner: String,
    val accounts: Map<String, ChatFolderAccountState>,
)

/** Capture real existing folders, memberships, exclusions and rules before the editor can mutate them. */
internal suspend fun captureMaestroFolderRules(state: WhiteNoiseAppState): MaestroFolderRulesBaseline =
    withContext(Dispatchers.Main.immediate) {
        check(state.accounts.size == 3)
        state.accounts.forEach { state.chatFolderPreferences.foldersFor(it.label) }
        MaestroFolderRulesBaseline(
            checkNotNull(state.activeAccountRef),
            state.chatFolderPreferences.state.value.toMap(),
        )
    }

/** Require the complete local store and a fresh production reader, including unchanged other accounts. */
internal suspend fun verifyMaestroFolderRules(
    context: Context,
    state: WhiteNoiseAppState,
    baseline: MaestroFolderRulesBaseline?,
    postcondition: String?,
): Boolean {
    if (postcondition?.startsWith("smart-rule-") != true) return false
    val before = checkNotNull(baseline)
    check(before.accounts.size == 3)
    val expected = expectedMaestroFolderRule(postcondition)
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
                                maestroFolderRuleMatches(checkNotNull(current[account]), original, expected)
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
        "smart-rule-absent" -> null
        "smart-rule-read" -> read
        "smart-rule-any" -> read.copy(all = false)
        "smart-rule-excluded" -> read.copy(not = true)
        "smart-rule-mentions" ->
            SmartFolderFilter.Group(children = defaults + SmartFolderFilter.Condition(FolderField.MENTIONS))
        "smart-rule-unread" ->
            SmartFolderFilter.Group(children = defaults + SmartFolderFilter.Condition(FolderField.UNREAD))
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
): Boolean {
    if (expected == null) return actual == before
    val added = actual.folders.filter { folder -> before.folders.none { it.id == folder.id } }
    val folder = added.singleOrNull()
    val rule = folder?.let { actual.rules[it.id] }
    return if (folder != null && rule != null) {
        val expectedFolder =
            ChatFolder(
                id = folder.id,
                name = "Maestro rules folder",
                description = "Maestro rules description",
                order = (before.folders.maxOfOrNull { it.order } ?: -1) + 1,
                systemKind = null,
            )
        val expectedState =
            before.copy(
                folders = before.folders + expectedFolder,
                membership = before.membership + (folder.id to emptySet()),
                exclusions = before.exclusions + (folder.id to emptySet()),
                rules = before.rules + (folder.id to ChatFolderRule(smartFilter = rule.smartFilter)),
            )
        actual == expectedState && rule.smartFilter?.let(SmartFolderCodec::decode) == expected
    } else {
        false
    }
}
