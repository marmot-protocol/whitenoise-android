package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.state.ChatListItem

/**
 * Sends [event] to the route owner and audits the result. A Settings-only event is dropped while no account owns
 * the section, because master's section writes are ignored then.
 */
internal fun MainShellStateHolder.dispatchShellNav(event: ShellNavEvent) {
    if (event !is ShellNavEvent.SectionOnly || sectionState.hasOwner) shellNavigator.dispatch(event)
    auditShellRoute(event.eventName)
}

/** Audits the route owner's stack and the section this holder would save for it. */
internal fun MainShellStateHolder.auditShellRoute(origin: String) {
    ShellRouteAudit.check(origin, shellNavigator.stack, sectionState.persistenceProblem())
}

/** The route entry for [item] opened with [open], bound to its pinned account or else to the route owner. */
internal fun MainShellStateHolder.conversationRoute(
    item: ChatListItem,
    open: ConversationOpenContext,
): ShellRoute.Conversation =
    ShellRoute.Conversation(
        accountRef = open.pinnedAccountRef ?: routeOwnerAccountRef.orEmpty(),
        groupIdHex = item.group.groupIdHex,
        open = open,
        item = item,
    )

/**
 * The route for [item] restored after process death, owned by [accountRef], the account its saved key was just
 * validated against. It does not read the route owner, which a fresh holder may not have bound yet.
 */
internal fun restoredConversationRoute(
    item: ChatListItem,
    accountRef: String,
): ShellRoute.Conversation =
    ShellRoute.Conversation(
        accountRef = accountRef,
        groupIdHex = item.group.groupIdHex,
        open = ConversationOpenContext(),
        item = item,
    )
