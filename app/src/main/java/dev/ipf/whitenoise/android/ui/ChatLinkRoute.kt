package dev.ipf.whitenoise.android.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ChatLink
import dev.ipf.whitenoise.android.core.ChatLinkDestination
import dev.ipf.whitenoise.android.core.ChatLinkParse
import dev.ipf.whitenoise.android.core.resolveChatLinkDestination
import dev.ipf.whitenoise.android.notifications.NotificationTarget
import dev.ipf.whitenoise.android.notifications.NotificationTargetKind
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.ChoiceDialog

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
 * Resolves an inbound link payload as a chat link (#3658). Returns null when the payload is not a chat link, so the
 * caller keeps its profile-link handling. A malformed or unknown id never produces an [ChatLinkOutcome.Open].
 */
internal suspend fun resolveChatLinkOutcome(
    payload: String,
    appState: WhiteNoiseAppState,
): ChatLinkOutcome? =
    when (val link = ChatLink.parse(payload)) {
        ChatLinkParse.NotChatLink -> null
        ChatLinkParse.Malformed -> ChatLinkOutcome.Notice(R.string.chat_link_invalid)
        is ChatLinkParse.Valid ->
            when (
                val destination =
                    resolveChatLinkDestination(appState.accountsHoldingChat(link.groupIdHex), appState.activeAccountRef)
            ) {
                ChatLinkDestination.NotFound -> ChatLinkOutcome.Notice(R.string.chat_link_not_found)
                is ChatLinkDestination.Open ->
                    ChatLinkOutcome.Open(chatLinkTarget(destination.accountRef, link.groupIdHex))
                is ChatLinkDestination.Choose ->
                    ChatLinkOutcome.Choose(ChatLinkAccountChoice(link.groupIdHex, destination.accountRefs))
            }
    }

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
