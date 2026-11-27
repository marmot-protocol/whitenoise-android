package dev.ipf.whitenoise.android.share

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.state.AppText
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.isCanonicalNpub

/**
 * Names the sender and carries their npub so an invited friend can message them right after installing. Without a
 * visible name or a complete npub the generic invite stays, since a half-personal invite cannot be acted on.
 */
internal fun inviteMessageText(
    senderName: String?,
    senderNpub: String?,
): AppText.Resource {
    val name = senderName?.trim()?.takeIf(String::isNotEmpty)
    val npub = senderNpub?.trim()?.takeIf(::isCanonicalNpub)
    return if (name != null && npub != null) {
        AppText.Resource(R.string.invite_message_personal, listOf(name, npub))
    } else {
        AppText.Resource(R.string.invite_message)
    }
}

/** Localized share-sheet title and the active account's invite text, resolved in composition. */
internal data class WhiteNoiseInvite(
    val title: String,
    val message: String,
)

/** The active account's invite, shared from every invite entry point. */
@Composable
internal fun activeAccountInvite(appState: WhiteNoiseAppState): WhiteNoiseInvite {
    val accountIdHex = appState.activeAccount?.accountIdHex
    val text =
        inviteMessageText(
            senderName = accountIdHex?.let(appState::networkDisplayNameOrNull),
            senderNpub = accountIdHex?.let(appState::npubForDisplay),
        )
    return WhiteNoiseInvite(
        title = stringResource(R.string.invite_to_white_noise),
        message = stringResource(text.resId, *text.args.toTypedArray()),
    )
}

/**
 * Shares a branded download QR card with the invite text, falling back to the text alone when the card cannot be
 * staged or shared. Callers keep their own busy guards; [isCurrent] rejects a late share after their screen or account
 * changed.
 */
internal suspend fun WhiteNoiseAppState.shareAppInvite(
    context: Context,
    invite: WhiteNoiseInvite,
    operationCode: String,
    isCurrent: () -> Boolean,
) {
    val (title, message) = invite
    val pictureResult =
        runCatchingCancellable {
            val staged = QrShareCardRenderer.stage(context, QrShareCardSpec(title, WhiteNoiseUrls.DOWNLOAD))
            if (isCurrent()) {
                launchOutboundShare(context, outboundShareIntent(message, listOf(staged.stream)), title).getOrThrow()
            }
        }
    if (pictureResult.isFailure && isCurrent()) {
        launchInviteShare(context, message, title).onFailure { presentOutboundShareFailure(operationCode, it) }
    }
}
