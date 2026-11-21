package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.ui.settings.settingsDetailParent

/**
 * One entry of the shell's route stack. The stack's root is always [Chats]. Above it sit, in this order, the
 * Settings section (a chain of [Settings] entries from Settings home to the open detail, optionally topped by
 * [Diagnostics]) and at most one [Conversation].
 *
 * The order is canonical, not historical: master keeps the section and the selected conversation as two
 * independent fields, so a conversation opened while Settings is the section (beside the list at expanded width, or
 * from a profile sheet) always sits above the Settings chain, and Back from it returns to that Settings screen.
 */
internal sealed interface ShellRoute {
    /** The chat list, the root of every stack. */
    data object Chats : ShellRoute

    /**
     * The open conversation. [accountRef] is the account it is bound to, the pinned account of a notification early
     * open or else the shell's owner account, so the stack is never keyed on the active account.
     */
    data class Conversation(
        val accountRef: String,
        val groupIdHex: String,
        val open: ConversationOpenContext,
    ) : ShellRoute

    /** Settings home when [detail] is null, otherwise one Settings screen. */
    data class Settings(
        val detail: SettingsDetail?,
    ) : ShellRoute

    /** The diagnostics screen, reached from a Settings screen it returns to. */
    data object Diagnostics : ShellRoute
}

/** The stack that holds only the chat list. */
internal val CHATS_ROOT: List<ShellRoute> = listOf(ShellRoute.Chats)

/** Upper bound on the Settings parent chain, so a cycle in the parent table can never loop forever. */
private const val MAX_SETTINGS_DEPTH = 8

/**
 * The Settings chain that ends at [detail]: Settings home, then every parent named by [settingsDetailParent], then
 * [detail] itself. Parents are synthesized, never stored, exactly as master re-derives them on restore.
 */
internal fun settingsChain(detail: SettingsDetail?): List<ShellRoute.Settings> {
    val details = generateSequence(detail, ::settingsDetailParent).take(MAX_SETTINGS_DEPTH).toList().asReversed()
    return listOf(ShellRoute.Settings(null)) + details.map { ShellRoute.Settings(it) }
}

/** The section entries master's saved [section] and [detail] stand for, without the root or a conversation. */
internal fun sectionEntries(
    section: MainSection,
    detail: SettingsDetail?,
): List<ShellRoute> =
    when (section) {
        MainSection.Chats -> emptyList()
        MainSection.Settings -> settingsChain(detail)
        MainSection.Diagnostics -> settingsChain(detail) + ShellRoute.Diagnostics
    }

/**
 * The conversation root of [stack], wherever it sits. Both account-boundary guards read this entry and never the
 * literal top, so an entry above the conversation cannot hide its open context.
 */
internal fun conversationRoot(stack: List<ShellRoute>): ShellRoute.Conversation? {
    val conversations = stack.filterIsInstance<ShellRoute.Conversation>()
    return conversations.firstOrNull()
}

/** Whether a Back from [stack] has nothing left to pop and so leaves the app, as Back from the chat list does. */
internal fun backLeavesApp(stack: List<ShellRoute>): Boolean = stack.size <= 1

/** Parses a saved section name the way [MainShellSectionState] validates it, falling back to Chats. */
internal fun mainSectionOf(name: String?): MainSection {
    val section = MainSection.entries.firstOrNull { it.name == name }
    return section ?: MainSection.Chats
}

/** Parses a saved Settings detail name the way [MainShellSectionState] validates it. */
internal fun settingsDetailOf(name: String?): SettingsDetail? = SettingsDetail.entries.firstOrNull { it.name == name }

/**
 * Projects master's hand-written route fields into the comparable stack value: the section and detail (the detail is
 * ignored while the section is Chats, where master never renders it), then the selected conversation with its whole
 * open context. A conversation's account is implicit in master, its pinned account or else [ownerAccountRef].
 */
internal fun legacyShellRoute(
    sectionName: String,
    settingsDetailName: String?,
    selectedGroupIdHex: String?,
    openContext: ConversationOpenContext,
    ownerAccountRef: String?,
): List<ShellRoute> {
    val section = sectionEntries(mainSectionOf(sectionName), settingsDetailOf(settingsDetailName))
    val conversation =
        selectedGroupIdHex?.let {
            ShellRoute.Conversation(openContext.pinnedAccountRef ?: ownerAccountRef.orEmpty(), it, openContext)
        }
    return CHATS_ROOT + section + listOfNotNull(conversation)
}
