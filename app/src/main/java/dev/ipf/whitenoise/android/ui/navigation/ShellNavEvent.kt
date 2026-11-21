package dev.ipf.whitenoise.android.ui.navigation

import dev.ipf.whitenoise.android.state.ChatListItem

/**
 * The only way to change the shell's route stack, which [ShellNavigator] owns. Each event names a user or system
 * intent, and [reduceShellRoute] applies it.
 *
 * Not carried by any event, because they are not route state: the Settings search origin and the header-photo
 * profile origin (screen-local `rememberSaveable` markers in the Settings screen that change where Back goes), the
 * just-created and DM-hint flags, conversation surface state such as Group Details itself, and every transition
 * layer (pending open, exiting content, displaced controllers, routing overlays).
 */
internal sealed interface ShellNavEvent {
    /** Events that only move within the Settings section, which master ignores while no account owns the shell. */
    sealed interface SectionOnly : ShellNavEvent

    /** The user opens a conversation where they are: a list tap, a profile sheet, or a group they just created. */
    data class OpenConversation(
        val conversation: ShellRoute.Conversation,
    ) : ShellNavEvent

    /** A route jumps into a conversation and leaves Settings: a share, a playback return or the support chat. */
    data class JumpToConversation(
        val conversation: ShellRoute.Conversation,
    ) : ShellNavEvent

    /** A notification tap commits its conversation in one atomic replace and leaves Settings. */
    data class CommitNotificationRoute(
        val conversation: ShellRoute.Conversation,
    ) : ShellNavEvent

    /**
     * A notification re-lands in the conversation that is already open, with a fresh open context and, when the
     * route read it again, the fresh chat-list [item].
     */
    data class JumpWithin(
        val open: ConversationOpenContext,
        val item: ChatListItem? = null,
    ) : ShellNavEvent

    /** A provisional open is upgraded to its authoritative chat-list row [item], without navigating. */
    data class ConversationRowReconciled(
        val groupIdHex: String,
        val item: ChatListItem? = null,
    ) : ShellNavEvent

    /** The conversation saved before process death reopens once its account's local snapshot is authoritative. */
    data class RestoreSavedConversation(
        val conversation: ShellRoute.Conversation,
    ) : ShellNavEvent

    /** The open conversation closes and whatever section lies below it shows again. */
    data object CloseConversation : ShellNavEvent

    /** A system route drops everything for the chat list, as an app-update tap does. */
    data object ReturnToChats : ShellNavEvent

    /** The user opens Settings, at home or straight at [detail] with its parents synthesized. */
    data class OpenSettings(
        val detail: SettingsDetail?,
    ) : SectionOnly

    /**
     * The Settings screen moves to [detail], by a tap, a search jump or its own Back. The parents are re-synthesized,
     * so a Back that skips a parent (search origin, header-photo profile) is expressed as this event, not as [Back].
     */
    data class ChangeSettingsDetail(
        val detail: SettingsDetail?,
    ) : SectionOnly

    /** Settings is left for the chat list. */
    data object ExitSettings : SectionOnly

    /** Diagnostics opens above the Settings screen it was reached from. */
    data object OpenDiagnostics : SectionOnly

    /** A plain pop of the top entry. From the root nothing is popped and the app is left, see [backLeavesApp]. */
    data object Back : ShellNavEvent

    /**
     * System Back delivered to one pane. On a phone and in the content pane it is a plain [Back]. In the list pane
     * beside an open conversation it pops the top of the section under that conversation, which stays open, as
     * Back from a Settings screen beside a conversation does on master.
     */
    data class BackFrom(
        val pane: ShellPane,
    ) : ShellNavEvent

    /** The Activity's saved section and detail come back, as on a fresh holder or after process death. */
    data class SectionRestored(
        val section: MainSection,
        val detail: SettingsDetail?,
    ) : ShellNavEvent

    /**
     * The shell's account-transition guard saw a real switch between two distinct accounts. The navigator never
     * decides whether a change counts as one. [earlyOpenRequestId] is 0 when no notification early open is active.
     */
    data class AccountSwitched(
        val previous: String,
        val current: String,
        val earlyOpenRequestId: Long,
    ) : ShellNavEvent

    /**
     * The route owner was rebound to another account or runtime. Only an exact notification-pinned route survives an
     * account replacement within the same runtime, as on master.
     */
    data class OwnerReplaced(
        val previousAccountRef: String?,
        val previousRuntimeGeneration: Int?,
        val accountRef: String,
        val runtimeGeneration: Int,
    ) : ShellNavEvent

    /** The active account became null. Nothing is kept and nothing comes back if the same account returns. */
    data class OwnerLost(
        val runtimeGeneration: Int,
    ) : ShellNavEvent

    /** The Android task was removed while the process stays, which drops only the conversation. */
    data object TaskRemoved : ShellNavEvent

    /** The shell's process state was released: onboarding, a failed phase or an isolated test holder. */
    data object ShellReleased : ShellNavEvent
}

/** The two panes a Back can be delivered to. On a phone everything is in [Content] except the chat list itself. */
internal enum class ShellPane {
    /** The chat list and the Settings section that replaces it. */
    List,

    /** The conversation and every screen opened above it. */
    Content,
}

/** The event's stable name for shadow records and debug logs, with no field values. */
internal val ShellNavEvent.eventName: String
    get() = javaClass.simpleName
