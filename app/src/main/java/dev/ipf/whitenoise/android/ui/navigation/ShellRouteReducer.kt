package dev.ipf.whitenoise.android.ui.navigation

/**
 * The pure route reducer of the future single route owner. It never reads the active account, a clock or any other
 * state: everything it needs is in [stack] and [event], so the same sequence always gives the same stack.
 *
 * The result keeps the canonical order described on [ShellRoute]: the root, the Settings section, the conversation.
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
        is ShellNavEvent.OpenConversation -> sectionOf(stack) + event.conversation
        is ShellNavEvent.RestoreSavedConversation -> sectionOf(stack) + event.conversation
        is ShellNavEvent.JumpToConversation -> listOf(ShellRoute.Chats, event.conversation)
        is ShellNavEvent.CommitNotificationRoute -> listOf(ShellRoute.Chats, event.conversation)
        is ShellNavEvent.JumpWithin ->
            conversationRoot(stack)?.let { listOf(ShellRoute.Chats, it.copy(open = event.open)) } ?: stack
        is ShellNavEvent.ConversationRowReconciled ->
            conversationRoot(stack)?.let { sectionOf(stack) + it.copy(groupIdHex = event.groupIdHex) } ?: stack
        else -> reduceStackEvent(stack, event)
    }

/** Applies a close, a return to the list, a plain Back or a restored section. */
private fun reduceStackEvent(
    stack: List<ShellRoute>,
    event: ShellNavEvent,
): List<ShellRoute> =
    when (event) {
        ShellNavEvent.CloseConversation -> sectionOf(stack)
        ShellNavEvent.ReturnToChats -> CHATS_ROOT
        ShellNavEvent.Back -> if (backLeavesApp(stack)) stack else stack.dropLast(1)
        is ShellNavEvent.SectionRestored ->
            CHATS_ROOT + sectionEntries(event.section, event.detail) + listOfNotNull(conversationRoot(stack))
        else -> stack
    }

/** Applies an event that only moves within the Settings section, keeping any conversation above it. */
private fun reduceSectionEvent(
    stack: List<ShellRoute>,
    event: ShellNavEvent.SectionOnly,
): List<ShellRoute> {
    val section = sectionOf(stack).drop(1)
    val conversation = listOfNotNull(conversationRoot(stack))
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
    return CHATS_ROOT + nextSection + conversation
}

/**
 * Applies an account, owner or process boundary. Each clears synchronously to the chat list, except the two exact
 * notification routes master carries across a boundary, and then only the conversation root itself survives.
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

/** The root and the Settings section of [stack], everything below the conversation. */
private fun sectionOf(stack: List<ShellRoute>): List<ShellRoute> = stack.filterNot { it is ShellRoute.Conversation }
