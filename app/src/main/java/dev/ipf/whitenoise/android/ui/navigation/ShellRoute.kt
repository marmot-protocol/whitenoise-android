package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.ui.group.ChatDetailsDestination
import dev.ipf.whitenoise.android.ui.settings.settingsDetailParent

/**
 * One entry of the shell's route stack. The stack's root is always [Chats]. Above it sit, in this order, the
 * Settings section (a chain of [Settings] entries from Settings home to the open detail, optionally topped by
 * [Diagnostics]), at most one [Conversation] and, above it, at most one [ChatDetailsScreen] of that conversation.
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
     * open or else the shell's owner account, so the stack is never keyed on the active account. [item] is the live
     * chat-list row the shell renders; it is process-scoped like the stack itself and never reaches saved state.
     */
    data class Conversation(
        val accountRef: String,
        val groupIdHex: String,
        val open: ConversationOpenContext,
        val item: ChatListItem? = null,
    ) : ShellRoute

    /**
     * Settings home when [detail] is null, otherwise one Settings screen. [arg] names the one item a page shows,
     * such as a relay URL or a license id (#4095), and is null for every page that lists or shows the whole section.
     */
    data class Settings(
        val detail: SettingsDetail?,
        val arg: String? = null,
    ) : ShellRoute

    /** The diagnostics screen, reached from a Settings screen it returns to. */
    data object Diagnostics : ShellRoute

    /**
     * A chat-details screen open above the conversation it belongs to, such as its relays. Process-retained with the
     * stack: kept by Activity recreation and app lock, dropped by process death, an account change and every other
     * route change that leaves the conversation.
     */
    data class ChatDetailsScreen(
        val destination: ChatDetailsDestination,
    ) : ShellRoute
}

/**
 * Whether this conversation is the chat of [accountRef] and [groupIdHex]: the same account and the same group, in
 * whatever hex case the id arrived. Every same-chat decision about the stack goes through here, so the reducer and
 * the chat-details screens above a conversation can never disagree about which chat they belong to.
 */
internal fun ShellRoute.Conversation.isSameChat(
    accountRef: String?,
    groupIdHex: String,
): Boolean = this.accountRef == accountRef && this.groupIdHex.equals(groupIdHex, ignoreCase = true)

/** The stack that holds only the chat list. */
internal val CHATS_ROOT: List<ShellRoute> = listOf(ShellRoute.Chats)

/** Upper bound on the Settings parent chain, so a cycle in the parent table can never loop forever. */
private const val MAX_SETTINGS_DEPTH = 8

/**
 * The Settings chain that ends at [detail] with its [arg]: Settings home, then [parents], then [detail] itself.
 *
 * [parents] are the pages the person actually came through, so Back returns to the page that opened [detail] (About
 * for Developer opened from About, #4095). Without them, as for a deep link, a notification or a restore that saved
 * no chain, every parent named by [settingsDetailParent] is synthesized instead. A parent equal to [detail] cuts the
 * chain there, so returning to a page already on the stack goes back to it rather than stacking it twice.
 */
internal fun settingsChain(
    detail: SettingsDetail?,
    arg: String? = null,
    parents: List<ShellRoute.Settings>? = null,
): List<ShellRoute.Settings> {
    if (detail == null) return listOf(ShellRoute.Settings(null))
    val above =
        parents
            ?.filter { it.detail != null }
            ?.let { real -> real.indexOfFirst { it.detail == detail }.let { if (it < 0) real else real.take(it) } }
            ?.takeLast(MAX_SETTINGS_DEPTH - 1)
            ?: generateSequence(settingsDetailParent(detail), ::settingsDetailParent)
                .take(MAX_SETTINGS_DEPTH - 1)
                .toList()
                .asReversed()
                .map { ShellRoute.Settings(it) }
    return listOf(ShellRoute.Settings(null)) + above + ShellRoute.Settings(detail, arg)
}

/**
 * The section entries master's saved [section] and [detail] stand for, without the root or a conversation. A saved
 * [chain] (the Settings pages from the first below home to [detail]) is used as it is when it still ends at [detail],
 * otherwise the fixed parents are synthesized.
 */
internal fun sectionEntries(
    section: MainSection,
    detail: SettingsDetail?,
    chain: List<ShellRoute.Settings>? = null,
): List<ShellRoute> {
    val top = chain?.lastOrNull()?.takeIf { it.detail != null && it.detail == detail }
    val settings = settingsChain(detail, top?.arg, top?.let { chain.dropLast(1) })
    return when (section) {
        MainSection.Chats -> emptyList()
        MainSection.Settings -> settings
        MainSection.Diagnostics -> settings + ShellRoute.Diagnostics
    }
}

/** The Settings pages of [stack] below Diagnostics and any conversation, home first. Empty while Chats is shown. */
internal fun settingsEntries(stack: List<ShellRoute>): List<ShellRoute.Settings> {
    val section = sectionOf(stack)
    return section.filterIsInstance<ShellRoute.Settings>()
}

/**
 * The conversation root of [stack], wherever it sits. Both account-boundary guards read this entry and never the
 * literal top, so an entry above the conversation cannot hide its open context.
 */
internal fun conversationRoot(stack: List<ShellRoute>): ShellRoute.Conversation? {
    val conversations = stack.filterIsInstance<ShellRoute.Conversation>()
    return conversations.firstOrNull()
}

/** The root and the section below the conversation, everything [stack] keeps when its conversation closes. */
internal fun sectionOf(stack: List<ShellRoute>): List<ShellRoute> = stack.takeWhile { it !is ShellRoute.Conversation }

/** The conversation root and every entry above it, empty when no conversation is open. */
internal fun conversationSegment(stack: List<ShellRoute>): List<ShellRoute> {
    val root = stack.indexOfFirst { it is ShellRoute.Conversation }
    return if (root < 0) emptyList() else stack.drop(root)
}

/** The section and Settings detail that [stack] stands for, as master's saved section fields hold them. */
internal fun sectionProjection(stack: List<ShellRoute>): Pair<MainSection, SettingsDetail?> {
    val section = sectionOf(stack).drop(1)
    val main =
        when {
            section.isEmpty() -> MainSection.Chats
            section.last() == ShellRoute.Diagnostics -> MainSection.Diagnostics
            else -> MainSection.Settings
        }
    return main to section.filterIsInstance<ShellRoute.Settings>().lastOrNull()?.detail
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
