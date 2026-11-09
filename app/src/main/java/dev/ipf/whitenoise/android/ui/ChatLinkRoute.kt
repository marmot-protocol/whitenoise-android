package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatLinkDestination
import dev.ipf.whitenoise.android.core.ChatLinkParse
import dev.ipf.whitenoise.android.core.resolveChatLinkDestination
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog

/**
 * Opens a `marmot://` link tapped inside the app, and names the chats such links point at (#3658).
 *
 * Message text reaches this instead of the system so no other app can claim the tap, and it opens without
 * the external-link dialog because it only ever reaches the viewer's own chats or a profile sheet.
 */
internal class InAppLinkHandler(
    val open: (link: String) -> Unit,
    val chatTitle: (groupIdHex: String) -> String?,
)

internal val LocalInAppLinkHandler = staticCompositionLocalOf<InAppLinkHandler?> { null }

/** A chat link held by more than one signed-in account, none of them active, waiting for the user to pick one. */
internal data class ChatLinkAccountChoice(
    val groupIdHex: String,
    val accountRefs: List<String>,
)

/** What the app root does with an inbound `marmot://chat/<group-id>` link once it is resolved. */
internal sealed interface ChatLinkOutcome {
    data class Open(
        val target: NotificationTarget,
    ) : ChatLinkOutcome

    data class Choose(
        val choice: ChatLinkAccountChoice,
    ) : ChatLinkOutcome

    data class Notice(
        val messageRes: Int,
    ) : ChatLinkOutcome
}

/** The conversation target a chat link opens: the chat itself, with no message to land on. */
internal fun chatLinkTarget(
    accountRef: String,
    groupIdHex: String,
): NotificationTarget =
    NotificationTarget(
        accountRef = accountRef,
        groupIdHex = groupIdHex,
        messageIdHex = null,
        kind = NotificationTargetKind.MESSAGE,
    )

/**
 * Resolves a parsed inbound link as a chat link (#3658). Returns null when the payload is not a chat link, so the
 * caller keeps its profile-link handling. A malformed or unknown id never produces an [ChatLinkOutcome.Open].
 */
internal suspend fun resolveChatLinkOutcome(
    link: ChatLinkParse,
    appState: WhiteNoiseAppState,
): ChatLinkOutcome? =
    when (link) {
        ChatLinkParse.NotChatLink -> null
        ChatLinkParse.Malformed -> ChatLinkOutcome.Notice(R.string.chat_link_invalid)
        is ChatLinkParse.Valid ->
            chatLinkOutcome(link.groupIdHex, appState.accountsHoldingChat(link.groupIdHex), appState.activeAccountRef)
    }

/** Maps the accounts holding a linked chat to what the root shows; null holders means the lookup failed. */
internal fun chatLinkOutcome(
    groupIdHex: String,
    holders: List<String>?,
    activeAccountRef: String?,
): ChatLinkOutcome {
    holders ?: return ChatLinkOutcome.Notice(R.string.error_try_again)
    return when (val destination = resolveChatLinkDestination(holders, activeAccountRef)) {
        ChatLinkDestination.NotFound -> ChatLinkOutcome.Notice(R.string.chat_link_not_found)
        is ChatLinkDestination.Open -> ChatLinkOutcome.Open(chatLinkTarget(destination.accountRef, groupIdHex))
        is ChatLinkDestination.Choose ->
            ChatLinkOutcome.Choose(ChatLinkAccountChoice(groupIdHex, destination.accountRefs))
    }
}

/** Keeps a pending account choice across activity recreation, after the link intent itself has been consumed. */
internal val ChatLinkAccountChoiceSaver: Saver<ChatLinkAccountChoice?, ArrayList<String>> =
    Saver(
        save = { choice -> choice?.let { arrayListOf(it.groupIdHex).apply { addAll(it.accountRefs) } } },
        restore = { saved -> ChatLinkAccountChoice(saved.first(), saved.drop(1)) },
    )

/** Asks which account should open a chat that several signed-in accounts hold. */
@Suppress("FunctionNaming")
@Composable
internal fun ChatLinkAccountChooserDialog(
    choice: ChatLinkAccountChoice,
    accountLabel: (String) -> String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    ChoiceDialog(
        title = stringResource(R.string.chat_link_choose_account_title),
        values = choice.accountRefs,
        selected = null,
        label = accountLabel,
        onDismiss = onDismiss,
        onSelect = onSelect,
    )
}
