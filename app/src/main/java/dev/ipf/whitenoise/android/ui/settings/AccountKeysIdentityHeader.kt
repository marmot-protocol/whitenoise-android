package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.chats.ConnectivityBannerTarget
import dev.ipf.whitenoise.android.ui.chats.connectivityBannerTarget
import dev.ipf.whitenoise.android.ui.profile.ProfileAvatar
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

private val AccountKeysAvatarSize = 72.dp

internal data class AccountKeysIdentity(
    val name: String,
    val publicKey: String,
    val picture: String?,
    val localSigning: Boolean,
    val running: Boolean,
    val connectionLabel: Int,
)

/** Public identity only. Runtime status is explicitly a session state, never evidence of relay connectivity. */
@Composable
@Suppress("FunctionNaming")
internal fun AccountKeysIdentityHeader(identity: AccountKeysIdentity) {
    Column(
        Modifier.padding(WhiteNoiseSpacing.Section).testTag("profile_keys.identity"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.size(AccountKeysAvatarSize)) {
            ProfileAvatar(identity.name, identity.publicKey, identity.picture)
        }
        Text(
            identity.name,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(
                if (identity.localSigning) {
                    R.string.account_keys_local_signing
                } else {
                    R.string.account_keys_external_signing
                },
            ),
        )
        Text(
            stringResource(
                if (identity.running) R.string.account_keys_session_running else R.string.account_keys_session_stopped,
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("profile_keys.session_status"),
        )
        Text(stringResource(identity.connectionLabel), Modifier.testTag("profile_keys.connection_status"))
    }
}

/** Reuse account-scoped readiness evidence; a running process or another account's relay is insufficient. */
@Composable
internal fun accountKeysConnectionLabel(appState: WhiteNoiseAppState): Int {
    val signals by appState.connectivitySignals.collectAsState()
    return when (
        connectivityBannerTarget(
            signals.hasValidatedInternet,
            appState.activeAccountRef,
            appState.runtimeGeneration,
            appState.chats.connectionState,
        )
    ) {
        ConnectivityBannerTarget.Offline -> R.string.connectivity_offline
        ConnectivityBannerTarget.Connecting -> R.string.connecting
        ConnectivityBannerTarget.Connected -> R.string.connected
        ConnectivityBannerTarget.NoAttempt -> R.string.disconnected
    }
}
