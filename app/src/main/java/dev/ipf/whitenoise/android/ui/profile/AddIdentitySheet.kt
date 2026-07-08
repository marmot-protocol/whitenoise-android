package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.onboarding.IdentityEntryForm
import dev.ipf.whitenoise.android.ui.onboarding.OnboardingAction
import dev.ipf.whitenoise.android.ui.onboarding.importIdentityErrorRes
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddIdentitySheet(
    appState: WhiteNoiseAppState,
    onDismiss: () -> Unit,
) {
    WindowSecureFlag()
    var identity by remember { mutableStateOf("") }
    var inFlightAction by remember { mutableStateOf(OnboardingAction.Idle) }
    var importErrorRes by remember { mutableStateOf<Int?>(null) }
    val busy = inFlightAction != OnboardingAction.Idle
    val creatingIdentityDescription = stringResource(R.string.creating_identity)
    val importingDescription = stringResource(R.string.import_existing_identity)

    // One guarded entry point for import so the button and the IME Done action
    // share the same blank/busy guard.
    fun startImport() {
        if (inFlightAction != OnboardingAction.Idle || identity.isBlank()) return
        inFlightAction = OnboardingAction.Importing
        importErrorRes = null
        appState.launchMutation {
            try {
                if (!appState.importIdentity(identity)) {
                    importErrorRes = importIdentityErrorRes(identity)
                }
            } finally {
                inFlightAction = OnboardingAction.Idle
            }
        }
    }

    // ModalBottomSheet renders in its own window on Android, separate from
    // the host activity window — `WindowSecureFlag()` (which flags the
    // activity window) doesn't reach it. Set the sheet's own securePolicy
    // so the nsec field inside is also protected from Recents/screenshot
    // capture.
    ModalBottomSheet(
        containerColor = amoledSheetContainerColor(),
        onDismissRequest = onDismiss,
        properties = ModalBottomSheetProperties(securePolicy = SecureFlagPolicy.SecureOn),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.add_account), style = MaterialTheme.typography.titleLarge)
            Button(
                onClick = {
                    inFlightAction = OnboardingAction.Creating
                    appState.launchMutation {
                        try {
                            appState.createIdentity()
                        } finally {
                            inFlightAction = OnboardingAction.Idle
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (inFlightAction == OnboardingAction.Creating) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .semantics { contentDescription = creatingIdentityDescription },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Key, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (inFlightAction == OnboardingAction.Creating) R.string.creating_identity_title else R.string.create_new_identity))
            }
            IdentityEntryForm(
                identity = identity,
                busy = busy,
                errorRes = importErrorRes,
                onIdentityChange = {
                    identity = it
                    importErrorRes = null
                },
                onErrorChange = { importErrorRes = it },
                onSubmit = { startImport() },
            )
            FilledTonalButton(
                onClick = { startImport() },
                enabled = !busy && identity.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (inFlightAction == OnboardingAction.Importing) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(18.dp)
                                .semantics { contentDescription = importingDescription },
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Person, contentDescription = null)
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_existing_identity))
            }
        }
    }
}
