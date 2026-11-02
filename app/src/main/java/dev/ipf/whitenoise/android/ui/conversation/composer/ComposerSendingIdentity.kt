@file:Suppress("FunctionNaming")

package dev.ipf.whitenoise.android.ui.conversation.composer

import android.os.Build
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.isSignedInSigningAccount
import dev.ipf.whitenoise.android.ui.account.AccountAvatarButton
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

/** A non-focusable confirmation keeps editor selection and the visible keyboard intact. */
@Composable
internal fun ComposerSendingIdentity(
    appState: WhiteNoiseAppState,
    accountRef: String?,
    conversationKey: Any?,
    overlayBackRegistrar: ComposerOverlayBackRegistrar? = null,
) {
    val account = composerSendingAccount(appState, accountRef) ?: return
    val title = appState.accountDisplayNameCached(account.accountIdHex)
    val picture = appState.avatarUrl(account.accountIdHex)
    key(accountRef, conversationKey, account.accountIdHex, title, picture) {
        var showing by remember { mutableStateOf(false) }
        BackHandler(enabled = showing) { showing = false }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ComposerPlatformPreImeBackHandler(showing, { showing = false }, overlayBackRegistrar)
        }
        val sendingAs = stringResource(R.string.share_sending_as)
        val popupOffset = with(LocalDensity.current) { composerSendingIdentityTargetSize.roundToPx() }
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
            if (showing) {
                Popup(
                    alignment = Alignment.BottomStart,
                    offset = IntOffset(0, -popupOffset),
                    onDismissRequest = { showing = false },
                    properties = PopupProperties(focusable = false, dismissOnClickOutside = true),
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
}

/** Preserve compact visual slots while retaining platform minimum touch-target expansion. */
@Composable
internal fun ComposerToolbarTargets(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalMinimumInteractiveComponentSize provides ComposerToolbarSlotWidth,
        content = content,
    )
}
