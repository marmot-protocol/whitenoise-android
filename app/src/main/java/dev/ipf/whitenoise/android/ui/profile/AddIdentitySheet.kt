package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard
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
    val context = LocalContext.current
    var identity by remember { mutableStateOf("") }
    var inFlightAction by remember { mutableStateOf(OnboardingAction.Idle) }
    var importErrorRes by remember { mutableStateOf<Int?>(null) }
    val amberSignerAvailable = remember { appState.isAmberSignerInstalled() }

    // Every add path (create / import / external signer) ends by switching the
    // active account, so the switch is the success signal that closes the sheet.
    val activeAtOpen = remember { appState.activeAccountRef }
    LaunchedEffect(appState.activeAccountRef) {
        if (appState.activeAccountRef != activeAtOpen) onDismiss()
    }

    // One guarded entry point for import so the button and the IME Done action
    // share the same blank/busy guard.
    fun startImport() {
        if (inFlightAction != OnboardingAction.Idle || identity.isBlank()) return
        inFlightAction = OnboardingAction.Importing
        importErrorRes = null
        appState.launchMutation {
            try {
                if (appState.importIdentity(identity)) {
                    clearSensitiveClipboard(context)
                } else {
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
        AddAccountSheetContent(
            amberSignerAvailable = amberSignerAvailable,
            inFlightAction = inFlightAction,
            identity = identity,
            importErrorRes = importErrorRes,
            onCreate = {
                inFlightAction = OnboardingAction.Creating
                appState.launchMutation {
                    try {
                        appState.createIdentity()
                    } finally {
                        inFlightAction = OnboardingAction.Idle
                    }
                }
            },
            onLoginWithAmber = {
                inFlightAction = OnboardingAction.AmberLogin
                appState.launchMutation {
                    try {
                        appState.loginWithAmber()
                    } finally {
                        inFlightAction = OnboardingAction.Idle
                    }
                }
            },
            onIdentityChange = {
                identity = it
                importErrorRes = null
            },
            onErrorChange = { importErrorRes = it },
            onImport = ::startImport,
        )
    }
}

/**
 * Stateless sheet body: an expressive stacked action group — create first,
 * the external signer when one is installed, and the secret-key form behind
 * a disclosure row so the raw nsec field is only on screen when asked for.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun AddAccountSheetContent(
    amberSignerAvailable: Boolean,
    inFlightAction: OnboardingAction,
    identity: String,
    importErrorRes: Int?,
    onCreate: () -> Unit,
    onLoginWithAmber: () -> Unit,
    onIdentityChange: (String) -> Unit,
    onErrorChange: (Int?) -> Unit,
    onImport: () -> Unit,
) {
    val busy = inFlightAction != OnboardingAction.Idle
    var secretKeyExpanded by rememberSaveable { mutableStateOf(false) }
    val optionCount = if (amberSignerAvailable) 3 else 2

    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.add_account), style = MaterialTheme.typography.titleLarge)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AddAccountOptionRow(
                label = stringResource(R.string.create_new_identity),
                icon = Icons.Default.Add,
                shape = stackShape(0, optionCount),
                primary = true,
                enabled = !busy,
                inFlight = inFlightAction == OnboardingAction.Creating,
                onClick = onCreate,
            )
            if (amberSignerAvailable) {
                AddAccountOptionRow(
                    label = stringResource(R.string.onboarding_login_with_amber),
                    icon = Icons.Default.Security,
                    shape = stackShape(1, optionCount),
                    primary = false,
                    enabled = !busy,
                    inFlight = inFlightAction == OnboardingAction.AmberLogin,
                    onClick = onLoginWithAmber,
                )
            }
            val chevron by animateFloatAsState(
                targetValue = if (secretKeyExpanded) 180f else 0f,
                animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
                label = "secretKeyChevron",
            )
            AddAccountOptionRow(
                label = stringResource(R.string.import_existing_identity),
                icon = Icons.Default.Key,
                shape = stackShape(optionCount - 1, optionCount),
                primary = false,
                enabled = !busy,
                inFlight = false,
                onClick = { secretKeyExpanded = !secretKeyExpanded },
                trailing = {
                    Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.rotate(chevron))
                },
            )
        }
        AnimatedVisibility(
            visible = secretKeyExpanded,
            enter = expandVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeIn(),
            exit = shrinkVertically(animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec()) + fadeOut(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.sign_in_secret_key_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IdentityEntryForm(
                    identity = identity,
                    busy = busy,
                    errorRes = importErrorRes,
                    onIdentityChange = onIdentityChange,
                    onErrorChange = onErrorChange,
                    onSubmit = onImport,
                )
                val importingDescription = stringResource(R.string.import_existing_identity)
                Button(
                    onClick = onImport,
                    enabled = !busy && identity.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    if (inFlightAction == OnboardingAction.Importing) {
                        LoadingIndicator(
                            modifier =
                                Modifier
                                    .size(24.dp)
                                    .semantics { contentDescription = importingDescription },
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(R.string.import_existing_identity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// Grouped corners for the stacked option rows: large on the stack's outer
// edges, small between neighbours — reads as one connected group.
private fun stackShape(
    index: Int,
    count: Int,
): Shape {
    val large = 20.dp
    val small = 6.dp
    val top = if (index == 0) large else small
    val bottom = if (index == count - 1) large else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun AddAccountOptionRow(
    label: String,
    icon: ImageVector,
    shape: Shape,
    primary: Boolean,
    enabled: Boolean,
    inFlight: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
    ) {
        if (inFlight) {
            LoadingIndicator(
                modifier =
                    Modifier
                        .size(24.dp)
                        .semantics { contentDescription = label },
            )
        } else {
            Icon(icon, contentDescription = null)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}
