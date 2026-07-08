package dev.ipf.whitenoise.android.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.ui.common.WindowSecureFlag
import dev.ipf.whitenoise.android.ui.common.primaryClipPlainText
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SignInContent(
    identity: String,
    busy: Boolean,
    errorRes: Int?,
    onIdentityChange: (String) -> Unit,
    onErrorChange: (Int?) -> Unit,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    WindowSecureFlag()
    val canSignIn = identity.isNotBlank() && !busy
    val signInDescription = stringResource(R.string.sign_in)
    // Login is nsec-only: reject a public key (npub) with a clear message
    // rather than importing it as a read-only account.
    val submit = {
        if (IdentityEntryInput.classify(identity) == IdentityEntryInput.Kind.PublicKey) {
            onErrorChange(R.string.sign_in_error_public_key)
        } else {
            onSignIn()
        }
    }
    // System back on the login screen returns to the landing (same as the
    // visible back arrow), instead of propagating to the activity and exiting
    // the app. While an import is in flight it is consumed (no-op) so back
    // can't hide the in-progress/error state behind the landing.
    BackHandler { if (!busy) onBack() }

    Scaffold(
        // Edge-to-edge: the content owns the top + horizontal safe-area insets;
        // the bottom slate owns the navigation-bar + IME insets (it applies
        // navigationBarsPadding().imePadding() itself), so the sign-in button
        // rides above the keyboard and the field stays reachable.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        bottomBar = {
            // Match the landing's bottom slate: same surface + shape, and the
            // primary button in the same 12dp shape/size as the landing's
            // buttons — no bordered, tonally-elevated action bar behind it.
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .imePadding()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                ) {
                    Button(
                        onClick = submit,
                        enabled = canSignIn,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
                    ) {
                        if (busy) {
                            LoadingIndicator(
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .semantics { contentDescription = signInDescription },
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(stringResource(R.string.sign_in), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
    ) { padding ->
        // Adaptive: cap + center the column so the field and brand don't stretch
        // full-bleed on large / foldable / desktop windows.
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier =
                    Modifier
                        .widthIn(max = OnboardingMaxContentWidth)
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack, enabled = !busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
                Spacer(Modifier.height(8.dp))
                // Smaller sibling of the landing badge to tie the two surfaces
                // together (same squircle + brand tokens, scaled down).
                WhiteNoiseLogoLockup(size = 72.dp)
                Spacer(Modifier.height(24.dp))
                Text(
                    stringResource(R.string.sign_in_headline),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.sign_in_secret_key_help),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(28.dp))
                IdentityEntryForm(
                    identity = identity,
                    busy = busy,
                    errorRes = errorRes,
                    onIdentityChange = onIdentityChange,
                    onErrorChange = onErrorChange,
                    onSubmit = submit,
                    // Login is nsec-only: no QR scan (the scanner yields
                    // npub / profile-link payloads), and the field is always
                    // masked and labelled as a secret key.
                    allowScan = false,
                    secretKeyOnly = true,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * The shared nsec/npub entry field used by the onboarding sign-in screen and
 * the add-account sheet (previously two drifted copies of the same form).
 * Owns masking, the paste/clear/QR-scan affordances, and the inline import
 * error; submission stays with the caller — the QR path only fills the field
 * so the user confirms before anything is imported.
 */
@Composable
internal fun IdentityEntryForm(
    identity: String,
    busy: Boolean,
    errorRes: Int?,
    onIdentityChange: (String) -> Unit,
    onErrorChange: (Int?) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    // Opt-in prominent "Scan QR Code" button under the field.
    // Off by default so the add-account sheet keeps the compact field-only form.
    showScanShortcut: Boolean = false,
    // Whether any QR-scan affordance is offered. The login screen sets this
    // false: logging in requires the *secret* key (nsec), and the scanner
    // yields npub / profile-link payloads, so scanning has no place there.
    allowScan: Boolean = true,
    // nsec-only mode (login): always masks the input and labels it as a secret
    // key. npub is rejected by the caller's submit validation.
    secretKeyOnly: Boolean = false,
) {
    var showScanner by remember { mutableStateOf(false) }
    val canSubmit = identity.isNotBlank() && !busy
    val context = LocalContext.current
    val clipboardManager =
        remember(context) {
            ContextCompat.getSystemService(context, android.content.ClipboardManager::class.java)
        }
    val canOfferPaste = rememberClipboardCanOfferPaste(clipboardManager)

    // Mask unless the value is unambiguously a public npub. Treats partial /
    // empty / unprefixed input as potentially secret, so a pasted nsec is
    // never rendered while the field is non-empty. Keep
    // `KeyboardType.Password` even when revealing the npub so the IME stays
    // opted out of suggestions / autofill / history.
    val maskSecret = secretKeyOnly || !identity.trim().startsWith("npub1")
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = identity,
            onValueChange = onIdentityChange,
            label = { Text(stringResource(if (secretKeyOnly) R.string.nostr_nsec else R.string.nsec_or_npub)) },
            singleLine = true,
            enabled = !busy,
            isError = errorRes != null,
            supportingText = errorRes?.let { { Text(stringResource(it)) } },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        identity.isNotEmpty() -> {
                            IconButton(onClick = { onIdentityChange("") }, enabled = !busy) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
                            }
                        }
                        else -> {
                            if (canOfferPaste) {
                                IconButton(
                                    onClick = {
                                        // Identity-specific paste: ClipboardPasteAffordance
                                        // is public-identifier-only and would reject an nsec.
                                        IdentityEntryInput
                                            .pasteValue(clipboardManager?.primaryClipPlainText(context))
                                            ?.let(onIdentityChange)
                                    },
                                    enabled = !busy,
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.paste))
                                }
                            }
                            if (allowScan) {
                                IconButton(onClick = { showScanner = true }, enabled = !busy) {
                                    Icon(Icons.Default.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr_code))
                                }
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (maskSecret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            keyboardActions =
                KeyboardActions(
                    onDone = {
                        if (canSubmit) onSubmit()
                    },
                ),
        )
        if (showScanShortcut && allowScan) {
            // Prominent secondary route to the same scanner the trailing icon
            // opens (shared `showScanner` state), for the in-person QR flow.
            OutlinedButton(
                onClick = { showScanner = true },
                enabled = !busy,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.scan_qr_code), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
    if (showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val scanned = IdentityEntryInput.scannedValue(raw)
                if (scanned == null) {
                    onErrorChange(R.string.identity_entry_error_invalid_key)
                } else {
                    // Fill only; the user reviews and taps sign in / import.
                    onIdentityChange(scanned)
                }
            },
        )
    }
}

@Composable
internal fun PublicIdentifierFieldTrailingAction(
    value: String,
    enabled: Boolean = true,
    allowHexPublicKey: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager =
        remember(context) {
            ContextCompat.getSystemService(context, android.content.ClipboardManager::class.java)
        }
    val canOfferPaste = rememberClipboardCanOfferPaste(clipboardManager)

    when {
        value.isNotEmpty() -> {
            IconButton(onClick = { onValueChange("") }, enabled = enabled) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.clear))
            }
        }
        canOfferPaste -> {
            IconButton(
                onClick = {
                    val pasteValue =
                        ClipboardPasteAffordance.pasteValue(
                            clipboardManager?.primaryClipPlainText(context),
                            allowHexPublicKey,
                        )
                    if (pasteValue != null) onValueChange(pasteValue)
                },
                enabled = enabled,
            ) {
                Icon(Icons.Default.ContentPaste, contentDescription = stringResource(R.string.paste))
            }
        }
    }
}
