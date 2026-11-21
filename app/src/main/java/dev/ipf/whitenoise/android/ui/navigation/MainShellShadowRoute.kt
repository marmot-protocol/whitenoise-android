package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.state.ChatListItem

/**
 * Sends [event] to the shadow navigator and compares the result with the hand-written route fields. A Settings-only
 * event is dropped while no account owns the section, because master's section setters ignore writes then.
 */
internal fun MainShellStateHolder.dispatchShellNav(event: ShellNavEvent) {
    if (event !is ShellNavEvent.SectionOnly || sectionState.hasOwner) shellNavigator.dispatch(event)
    checkShellShadow(event.eventName)
}

/** Compares the navigator's whole stack with the projection of the hand-written route fields. */
internal fun MainShellStateHolder.checkShellShadow(origin: String) {
    ShellShadowLog.compare(
        origin = origin,
        navigator = shellNavigator.stack,
        legacy =
            legacyShellRoute(
                sectionName = sectionState.sectionName,
                settingsDetailName = sectionState.settingsDetailName,
                selectedGroupIdHex = selectedChat.value?.group?.groupIdHex,
                openContext = selectedChatOpenContext.value,
                ownerAccountRef = routeOwnerAccountRef,
            ),
    )
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
    )

/**
 * Hands this holder's saved section to the process-retained navigator once, at the holder's first owner bind, after
 * the section state has applied its account, runtime and process-epoch rules to what it restored.
 */
internal fun MainShellStateHolder.restoreShadowSectionOnce() {
    if (shadowSectionRestored) return
    shadowSectionRestored = true
    shellNavigator.dispatch(
        ShellNavEvent.SectionRestored(
            mainSectionOf(sectionState.sectionName),
            settingsDetailOf(sectionState.settingsDetailName),
        ),
    )
}
