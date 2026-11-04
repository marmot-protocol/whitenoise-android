@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.ui.account.AccountAvatarButton
import dev.ipf.whitenoise.android.ui.design.KeyboardSafePopup
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

internal val composerSendingIdentityTargetSize = 48.dp
private val sendingIdentityAvatarSize = 24.dp

/** Never substitutes the globally active account for the actual composer owner. */
internal fun composerSendingAccount(
    appState: WhiteNoiseAppState,
    accountRef: String?,
) = appState.accounts.filter { it.isSignedInSigningAccount() }.let { signedIn ->
    signedIn.firstOrNull { signedIn.size > 1 && it.label == accountRef }
}

/**
 * A non-focusable confirmation keeps editor selection and the visible keyboard intact. It uses the shared
 * keyboard-safe popup, whose scrim consumes the whole outside press, so a second tap on the avatar closes
 * the confirmation instead of dismissing it and reaching the avatar's toggle again (#3553).
 * [onOpenChange] reports a close action while it is open and null once it closes, so the composer can
 * route Back to the confirmation while the keyboard stays up.
 */
@Composable
internal fun ComposerSendingIdentity(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    conversationKey: Any?,
    overlayBackRegistrar: ComposerOverlayBackRegistrar? = null,
    onOpenChange: (close: (() -> Unit)?) -> Unit = {},
) {
    val account = composerSendingAccount(appState, accountRef) ?: return
    val title = appState.accountDisplayNameCached(account.accountIdHex)
    val picture = appState.avatarUrl(account.accountIdHex)
    key(accountRef, conversationKey, account.accountIdHex, title, picture) {
        var showing by remember { mutableStateOf(false) }
        val currentOnOpenChange by rememberUpdatedState(onOpenChange)
        val open = showing
        DisposableEffect(open) {
            // Report only the open span, so each open and each close reaches the composer exactly once.
            if (open) currentOnOpenChange { showing = false }
            onDispose { if (open) currentOnOpenChange(null) }
        }
        val sendingAs = stringResource(R.string.share_sending_as)
        val popupOffset = with(LocalDensity.current) { composerSendingIdentityTargetSize.roundToPx() }
        val position = remember(popupOffset) { SendingIdentityPopupPositionProvider(popupOffset) }
        val backRegistration = remember(overlayBackRegistrar) { overlayBackRegistrar?.let { it::register } }
        Box {
            ComposerToolbarTargets {
                AccountAvatarButton(
                    title = title,
                    seed = account.accountIdHex,
                    pictureUrl = picture,
                    size = sendingIdentityAvatarSize,
                    touchTargetSize = composerSendingIdentityTargetSize,
                    actionDescription = sendingAs,
                    onClick = { showing = !showing },
                    modifier =
                        Modifier
                            .width(ComposerToolbarSlotWidth)
                            .height(ComposerActionRowHeight)
                            .testTag("composer-sending-identity"),
                )
            }
            KeyboardSafePopup(
                expanded = showing,
                onDismissRequest = { showing = false },
                popupPositionProvider = position,
                modifier = Modifier.matchParentSize(),
                backRegistration = backRegistration,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    border = amoledSurfaceBorderStroke(),
                ) {
                    Column(
                        Modifier
                            .padding(dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing.Section)
                            .testTag("composer-sending-identity-popup"),
                    ) {
                        Text(sendingAs, style = MaterialTheme.typography.labelMedium)
                        Text(title, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        }
    }
}

/**
 * Places the confirmation's bottom edge [liftPx] above the avatar slot's bottom, aligned to the slot's
 * start edge — the placement the earlier `Popup(alignment = BottomStart, offset = (0, -liftPx))` used.
 */
internal class SendingIdentityPopupPositionProvider(
    private val liftPx: Int,
) : PopupPositionProvider {
    /** Resolves the start edge per [layoutDirection] and lifts the popup's bottom [liftPx] above the slot's. */
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x =
            if (layoutDirection == LayoutDirection.Ltr) {
                anchorBounds.left
            } else {
                anchorBounds.right - popupContentSize.width
            }
        return IntOffset(x, anchorBounds.bottom - popupContentSize.height - liftPx)
    }
}

/** Preserve compact visual slots while retaining platform minimum touch-target expansion. */
@Composable
internal fun ComposerToolbarTargets(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides ComposerToolbarSlotWidth,
        content = content,
    )
}
