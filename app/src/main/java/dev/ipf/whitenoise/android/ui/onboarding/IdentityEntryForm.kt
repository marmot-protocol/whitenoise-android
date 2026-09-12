@file:Suppress("FunctionNaming") // Composable functions use framework naming.

package dev.ipf.whitenoise.android.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.ClipboardPasteAffordance
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.ui.common.primaryClipPlainText
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet

/**
 * The shared nsec/npub entry field used by the onboarding sign-in screen and
 * the add-account sheet (previously two drifted copies of the same form).
 * Owns masking, the paste/clear/QR-scan affordances, and the inline import
 * error; submission stays with the caller — the QR path only fills the field
 * so the user confirms before anything is imported.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // Declarative input states and affordances.
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
                                    Icon(
                                        Icons.Default.ContentPaste,
                                        contentDescription = stringResource(R.string.paste),
                                    )
                                }
                            }
                            if (allowScan) {
                                IconButton(onClick = { showScanner = true }, enabled = !busy) {
                                    Icon(
                                        Icons.Default.QrCodeScanner,
                                        contentDescription = stringResource(R.string.scan_qr_code),
                                    )
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
                val secretRejected =
                    secretKeyOnly &&
                        scanned != null &&
                        IdentityEntryInput.classify(scanned) == IdentityEntryInput.Kind.PublicKey
                when {
                    scanned == null -> onErrorChange(R.string.identity_entry_error_invalid_key)
                    // A secret-only field must reject a public identifier at
                    // scan time: filled into a masked field, an npub reads as
                    // an accepted key until submit fails much later.
                    secretRejected -> onErrorChange(R.string.sign_in_error_public_key)
                    else -> {
                        // Fill only; the user reviews and taps sign in / import.
                        onIdentityChange(scanned)
                    }
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
                Icon(
                    Icons.Default.ContentPaste,
                    contentDescription = stringResource(R.string.paste),
                )
            }
        }
    }
}
