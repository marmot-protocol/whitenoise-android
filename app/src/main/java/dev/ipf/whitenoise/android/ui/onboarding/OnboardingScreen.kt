package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.ui.common.clearSensitiveClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Single in-flight onboarding action so the two buttons can never both read as
// busy: each button's spinner keys off its own action, and the shared value
// disables the other while one runs.
internal enum class OnboardingAction { Idle, Creating, Importing, AmberLogin }

// Adaptive cap for the onboarding + sign-in surfaces: on a phone the hero and
// actions fill the width, but on tablets / unfolded foldables / desktop windows
// they stay a readable single column centered in the window rather than
// stretching full-bleed (per the `adaptive` skill's max-content-width guidance).
internal val OnboardingMaxContentWidth = 440.dp

@Composable
internal fun OnboardingScreen(appState: WhiteNoiseAppState) {
    var identity by remember { mutableStateOf("") }
    var inFlightAction by remember { mutableStateOf(OnboardingAction.Idle) }
    var importErrorRes by remember { mutableStateOf<Int?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Signer availability can't change while onboarding is on screen, so read it
    // once (the query hits the PackageManager).
    val amberSignerAvailable = remember { appState.isAmberSignerInstalled() }

    OnboardingContent(
        identity = identity,
        creatingIdentity = inFlightAction == OnboardingAction.Creating,
        signingInBusy = inFlightAction == OnboardingAction.Importing,
        importErrorRes = importErrorRes,
        onIdentityChange = {
            identity = it
            importErrorRes = null
        },
        onImportErrorChange = { importErrorRes = it },
        onCreateIdentity = {
            inFlightAction = OnboardingAction.Creating
            scope.launch {
                try {
                    appState.createIdentity()
                } finally {
                    inFlightAction = OnboardingAction.Idle
                }
            }
        },
        onImportIdentity = { value ->
            inFlightAction = OnboardingAction.Importing
            importErrorRes = null
            scope.launch {
                try {
                    if (appState.importIdentity(value)) {
                        clearSensitiveClipboard(context)
                    } else {
                        importErrorRes = importIdentityErrorRes(value)
                    }
                } finally {
                    inFlightAction = OnboardingAction.Idle
                }
            }
        },
        loggingInWithAmber = inFlightAction == OnboardingAction.AmberLogin,
        amberSignerAvailable = amberSignerAvailable,
        onLoginWithAmber = {
            inFlightAction = OnboardingAction.AmberLogin
            scope.launch {
                try {
                    appState.loginWithAmber()
                } finally {
                    inFlightAction = OnboardingAction.Idle
                }
            }
        },
    )
}

/**
 * Inline message for a failed identity import (#795 lud16 pattern): input
 * that isn't even shaped like a key reads as "not a valid key"; a
 * well-formed key that the engine still rejected reads as a retryable
 * sign-in failure.
 */
internal fun importIdentityErrorRes(identity: String): Int =
    if (IdentityEntryInput.classify(identity) == IdentityEntryInput.Kind.Invalid) {
        R.string.identity_entry_error_invalid_key
    } else {
        R.string.identity_entry_error_import_failed
    }

@Composable
fun OnboardingContent(
    identity: String,
    creatingIdentity: Boolean,
    signingInBusy: Boolean,
    onIdentityChange: (String) -> Unit,
    onCreateIdentity: () -> Unit,
    onImportIdentity: (String) -> Unit,
    importErrorRes: Int? = null,
    onImportErrorChange: (Int?) -> Unit = {},
    loggingInWithAmber: Boolean = false,
    amberSignerAvailable: Boolean = false,
    onLoginWithAmber: () -> Unit = {},
) {
    var signingIn by remember { mutableStateOf(signingInBusy) }
    val busy = creatingIdentity || signingInBusy || loggingInWithAmber
    val creatingIdentityDescription = stringResource(R.string.creating_identity)
    val amberLoginDescription = stringResource(R.string.onboarding_login_with_amber)

    if (signingIn) {
        SignInContent(
            identity = identity,
            busy = signingInBusy,
            errorRes = importErrorRes,
            onIdentityChange = onIdentityChange,
            onErrorChange = onImportErrorChange,
            onBack = { signingIn = false },
            onSignIn = { onImportIdentity(identity.trim()) },
        )
        return
    }

    // Edge-to-edge: the landing is hosted in a Scaffold with zero content insets
    // (see WhiteNoiseApp), so it owns its own system-bar / display-cutout padding
    // via safeDrawing. Adaptive: the hero + actions are capped at a readable
    // width and centered so the column doesn't stretch full-bleed on tablets /
    // unfolded foldables / desktop windows. Layout mirrors the old app: plain
    // logo + rotating slogan centered above a bottom "slate" holding the two
    // auth actions.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxHeight()
                    .widthIn(max = OnboardingMaxContentWidth)
                    .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero: plain mark + wordmark + rotating slogan, centered in the
            // flexible space above the slate.
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                WhiteNoiseLogoLockup(size = 96.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    // Brand wordmark: always "White Noise" (see the white_noise
                    // string), never app_name, which debug builds relabel to
                    // "White Noise Dev".
                    stringResource(R.string.white_noise),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                OnboardingRotatingSlogan()
            }

            // Bottom slate: a lifted surface panel holding the auth actions,
            // echoing the old app's WnSlate. Login (tonal) sits above Sign up
            // (filled primary), matching the old auth-buttons order.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    FilledTonalButton(
                        onClick = { signingIn = true },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        Text(
                            stringResource(R.string.onboarding_login),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Button(
                        onClick = onCreateIdentity,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        if (creatingIdentity) {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .size(20.dp)
                                        .semantics { contentDescription = creatingIdentityDescription },
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            stringResource(if (creatingIdentity) R.string.creating_identity_title else R.string.onboarding_sign_up),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    // Alternative login via an installed NIP-55 external signer
                    // (Amber). Only offered when such a signer is present.
                    if (amberSignerAvailable) {
                        OutlinedButton(
                            onClick = onLoginWithAmber,
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                        ) {
                            if (loggingInWithAmber) {
                                CircularProgressIndicator(
                                    modifier =
                                        Modifier
                                            .size(20.dp)
                                            .semantics { contentDescription = amberLoginDescription },
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                stringResource(R.string.onboarding_login_with_amber),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The rotating brand slogan on the onboarding landing, mirroring the old app:
 * fades through "Decentralized" → "Uncensorable" → "Secure messaging" every 3s.
 * The index advances on a paused-clock-friendly [delay] loop, so a screenshot
 * test that doesn't advance the clock deterministically captures the first
 * slogan.
 */
@Composable
private fun OnboardingRotatingSlogan() {
    val slogans =
        listOf(
            R.string.onboarding_slogan_decentralized,
            R.string.onboarding_slogan_uncensorable,
            R.string.onboarding_slogan_secure_messaging,
        )
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            index = (index + 1) % slogans.size
        }
    }
    AnimatedContent(
        targetState = index,
        transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
        label = "onboarding-slogan",
    ) { current ->
        Text(
            stringResource(slogans[current]),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The White Noise brand mark shown on the onboarding surfaces: the bare WN "M"
 * mark, tinted with the foreground content color (white on the dark AMOLED
 * background, near-black in light theme). No container/badge — this mirrors the
 * old app's plain-logo treatment. The tint reads off the background luminance
 * (matching how the rest of this file derives light/dark) rather than
 * `isSystemInDarkTheme()`, so a forced-theme preview/test resolves correctly.
 */
@Composable
internal fun WhiteNoiseLogoLockup(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
) {
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val markColor = if (isLight) MaterialTheme.colorScheme.onBackground else Color.White
    Icon(
        painter = painterResource(R.drawable.ic_wn_mark),
        contentDescription = stringResource(R.string.white_noise_logo),
        modifier = modifier.size(size),
        tint = markColor,
    )
}
