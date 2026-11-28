package dev.ipf.whitenoise.android.ui.navigation

/**
 * The pure route reducer of the single route owner. It never reads the active account, a clock or any other state:
 * everything it needs is in [stack] and [event], so the same sequence always gives the same stack.
 *
 * The result keeps the canonical order described on [ShellRoute]: the root, the Settings section, the conversation
 * and the screens opened above it.
 */
internal fun reduceShellRoute(
    stack: List<ShellRoute>,
    event: ShellNavEvent,
): List<ShellRoute> =
    when (event) {
        is ShellNavEvent.SectionOnly -> reduceSectionEvent(stack, event)
        is ShellNavEvent.AccountSwitched,
        is ShellNavEvent.OwnerReplaced,
        is ShellNavEvent.OwnerLost,
        ShellNavEvent.TaskRemoved,
        ShellNavEvent.ShellReleased,
        -> reduceBoundaryEvent(stack, event)
        else -> reduceConversationEvent(stack, event)
    }

/** Applies an event that opens, re-lands, restores or closes the conversation. */
private fun reduceConversationEvent(
    stack: List<ShellRoute>,
    event: ShellNavEvent,
): List<ShellRoute> =
    when (event) {
        is ShellNavEvent.OpenConversation -> sectionOf(stack) + opened(stack, event.conversation)
        is ShellNavEvent.RestoreSavedConversation -> sectionOf(stack) + opened(stack, event.conversation)
        is ShellNavEvent.JumpToConversation -> CHATS_ROOT + opened(stack, event.conversation)
        is ShellNavEvent.CommitNotificationRoute -> CHATS_ROOT + opened(stack, event.conversation)
        is ShellNavEvent.ConversationRowReconciled ->
            conversationRoot(stack)?.takeIf { it.isSameChat(event.accountRef, event.groupIdHex) }?.let { root ->
                sectionOf(stack) + root.copy(item = event.item ?: root.item) + conversationSegment(stack).drop(1)
            } ?: stack
        else -> reduceStackEvent(stack, event)
    }

/**
 * [conversation] and the screens it keeps above it: reselecting the conversation that is already open keeps them,
 * as master keeps its chat-details screens while the same chat stays selected, and any other conversation starts
 * with none.
 */
private fun opened(
    stack: List<ShellRoute>,
    conversation: ShellRoute.Conversation,
): List<ShellRoute> {
    val root = conversationRoot(stack)
    val same = root?.isSameChat(conversation.accountRef, conversation.groupIdHex) ?: false
    return listOf(conversation) + if (same) conversationSegment(stack).drop(1) else emptyList()
}

/** Applies a close, a return to the list, a Back or a restored section. */
private fun reduceStackEvent(
    stack: List<ShellRoute>,
    event: ShellNavEvent,
): List<ShellRoute> =
    when (event) {
        ShellNavEvent.CloseConversation -> sectionOf(stack)
        is ShellNavEvent.OpenChatDetailsScreen ->
            conversationRoot(stack)?.let { sectionOf(stack) + it + ShellRoute.ChatDetailsScreen(event.destination) }
                ?: stack
        ShellNavEvent.CloseChatDetailsScreens -> sectionOf(stack) + listOfNotNull(conversationRoot(stack))
        ShellNavEvent.ReturnToChats -> CHATS_ROOT
        ShellNavEvent.Back -> popTop(stack)
        is ShellNavEvent.BackFrom -> reduceBackFrom(stack, event.pane)
        is ShellNavEvent.SectionRestored ->
            CHATS_ROOT + sectionEntries(event.section, event.detail) + conversationSegment(stack)
        else -> stack
    }

/** Drops the top entry, or nothing from the root, which leaves the app instead. */
private fun popTop(stack: List<ShellRoute>): List<ShellRoute> = if (backLeavesApp(stack)) stack else stack.dropLast(1)

/**
 * Back in [pane]. Only the list pane beside an open conversation differs from a plain pop: it pops the section's
 * top entry and keeps the conversation, and with no section open it has nothing of its own to pop.
 */
private fun reduceBackFrom(
    stack: List<ShellRoute>,
    pane: ShellPane,
): List<ShellRoute> {
    val conversation = conversationSegment(stack)
    if (pane == ShellPane.Content || conversation.isEmpty()) return popTop(stack)
    val section = sectionOf(stack)
    return if (backLeavesApp(section)) stack else section.dropLast(1) + conversation
}

/** Applies an event that only moves within the Settings section, keeping any conversation above it. */
private fun reduceSectionEvent(
    stack: List<ShellRoute>,
    event: ShellNavEvent.SectionOnly,
): List<ShellRoute> {
    val section = sectionOf(stack).drop(1)
    val nextSection =
        when (event) {
            is ShellNavEvent.OpenSettings -> settingsChain(event.detail)
            ShellNavEvent.ExitSettings -> emptyList()
            // Master keeps the detail while Chats is the section and never renders it there.
            is ShellNavEvent.ChangeSettingsDetail ->
                when {
                    section.isEmpty() -> section
                    section.last() == ShellRoute.Diagnostics -> settingsChain(event.detail) + ShellRoute.Diagnostics
                    else -> settingsChain(event.detail)
                }
            ShellNavEvent.OpenDiagnostics ->
                section.filterIsInstance<ShellRoute.Settings>().ifEmpty { settingsChain(null) } +
                    ShellRoute.Diagnostics
        }
    return CHATS_ROOT + nextSection + conversationSegment(stack)
}

/**
 * Applies an account, owner or process boundary. Each clears synchronously to the chat list, except the two exact
 * notification routes master carries across a boundary, and then only the conversation root itself survives: every
 * screen opened above it is dropped, and so is the Settings section below it.
 *
 * Dropping the section there is intentional and differs from master, which left the section as it was when the
 * early open landed. It cannot be seen: while a notification routes, the list pane is covered, so Settings cannot be
 * opened between the early open and its landing, and an owner rebind clears the section anyway.
 */
private fun reduceBoundaryEvent(
    stack: List<ShellRoute>,
    event: ShellNavEvent,
): List<ShellRoute> {
    val root = conversationRoot(stack)
    return when (event) {
        is ShellNavEvent.AccountSwitched ->
            root
                ?.takeIf { earlyOpenLandsPinnedAccount(it, event.current, event.earlyOpenRequestId) }
                ?.let { listOf(ShellRoute.Chats, it) } ?: CHATS_ROOT
        is ShellNavEvent.OwnerReplaced ->
            root
                ?.takeIf { ownerReplacementKeepsRoute(it, event) }
                ?.let { listOf(ShellRoute.Chats, it) } ?: CHATS_ROOT
        ShellNavEvent.TaskRemoved -> sectionOf(stack)
        else -> CHATS_ROOT
    }
}

/**
 * The account-switch exception (contract 1): the conversation is pinned to the arriving account and carries exactly
 * the route's current early-open request id, which is never 0.
 */
internal fun earlyOpenLandsPinnedAccount(
    root: ShellRoute.Conversation,
    current: String,
    earlyOpenRequestId: Long,
): Boolean =
    earlyOpenRequestId != 0L &&
        root.open.pinnedAccountRef == current &&
        root.open.notificationRouteTraceRequestId == earlyOpenRequestId

/**
 * The owner-rebind exception, as master's process state carries it: another account in the same runtime, with the
 * conversation pinned to that account and owned by a notification route.
 */
private fun ownerReplacementKeepsRoute(
    root: ShellRoute.Conversation,
    event: ShellNavEvent.OwnerReplaced,
): Boolean =
    event.previousAccountRef != event.accountRef &&
        event.previousRuntimeGeneration == event.runtimeGeneration &&
        root.open.pinnedAccountRef == event.accountRef &&
        root.open.notificationRouteTraceRequestId != null
