package dev.ipf.whitenoise.android.ui.onboarding

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityEntryInput
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseAlertDialog
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButtonDefaults
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSecureTextField
import dev.ipf.whitenoise.android.ui.common.primaryClipPlainText
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/**
 * Paste and the real CameraX scanner only fill a fully masked native field; import always needs the explicit
 * action.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming", "LongMethod", "LongParameterList", "CyclomaticComplexMethod")
@Composable
internal fun SignInKeyField(
    key: TextFieldState,
    busy: Boolean,
    errorRes: Int?,
    onIdentityChange: (String) -> Unit,
    onErrorChange: (Int?) -> Unit,
    onSubmit: () -> Unit,
    scannerContent: @Composable (() -> Unit, (String) -> Unit) -> Unit = { dismiss, scan ->
        QrScannerSheet(
            onDismiss = dismiss,
            onScan = scan,
            permissionDetailRes = R.string.qr_private_key_permission_detail,
        )
    },
) {
    var scannerSession by remember { mutableStateOf<Long?>(null) }
    var nextScannerSession by remember { mutableStateOf(0L) }
    var active by remember { mutableStateOf(true) }
    val currentBusy by rememberUpdatedState(busy)
    DisposableEffect(Unit) {
        onDispose {
            active = false
            scannerSession = null
        }
    }
    var scanError by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(busy) {
        if (busy) {
            scannerSession = null
            scanError = null
        }
    }
    val context = LocalContext.current
    val clipboard = remember(context) { ContextCompat.getSystemService(context, ClipboardManager::class.java) }
    val canPaste = rememberClipboardCanOfferPaste(clipboard)

    fun replaceKey(value: String) {
        if (currentBusy || !active) return
        key.setTextAndPlaceCursorAtEnd(value)
        onIdentityChange(value)
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhiteNoiseSecureTextField(
            state = key,
            enabled = !busy,
            textObfuscationMode = TextObfuscationMode.Hidden,
            modifier = Modifier.weight(1f).testTag("onboarding.sign_in.private_key"),
            label = { Text(stringResource(R.string.private_key)) },
            placeholder = { Text(stringResource(R.string.onboarding_enter_private_key)) },
            errorMessage = errorRes?.let { stringResource(it) },
            supportingText = { Text(stringResource(errorRes ?: R.string.sign_in_secret_key_help)) },
            trailingIcon = {
                if (!busy && (key.text.isNotEmpty() || canPaste)) {
                    val empty = key.text.isEmpty()
                    IconButton(
                        onClick = {
                            if (empty) {
                                IdentityEntryInput
                                    .pasteValue(clipboard?.primaryClipPlainText(context))
                                    ?.let(::replaceKey)
                            } else {
                                replaceKey("")
                            }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Icon(
                            painterResource(if (empty) R.drawable.ic_content_paste else R.drawable.ic_close),
                            stringResource(if (empty) R.string.paste else R.string.clear),
                        )
                    }
                }
            },
            keyboardOptions =
                KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
            onKeyboardAction = { if (!busy && key.text.isNotBlank()) onSubmit() },
        )
        FilledTonalIconButton(
            onClick = {
                if (!currentBusy && active) scannerSession = ++nextScannerSession
            },
            enabled = !busy,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.size(WhiteNoiseButtonDefaults.TaskHeight).testTag("onboarding.sign_in.scan"),
        ) {
            Icon(painterResource(R.drawable.ic_qr_code_scanner), stringResource(R.string.scan_qr_code))
        }
    }
    val session = scannerSession
    if (session != null && !busy) {
        scannerContent(
            { if (scannerSession == session) scannerSession = null },
            { raw ->
                // Only the currently open scanner may deliver once; dismiss/reopen invalidates old callbacks.
                if (!currentBusy && active && scannerSession == session) {
                    scannerSession = null
                    val scanned = IdentityEntryInput.scannedValue(raw)
                    val error =
                        when {
                            scanned == null -> R.string.identity_entry_error_invalid_key
                            IdentityEntryInput.classify(scanned) == IdentityEntryInput.Kind.PublicKey ->
                                R.string.sign_in_error_public_key
                            else -> null
                        }
                    if (error != null) {
                        scanError = error
                        onErrorChange(error)
                    } else if (scanned != null) {
                        replaceKey(scanned)
                    }
                }
            },
        )
    }
    scanError?.let { message ->
        WhiteNoiseAlertDialog(
            onDismissRequest = { scanError = null },
            title = { Text(stringResource(R.string.scan_qr_code)) },
            text = { Text(stringResource(message)) },
            confirmButton = { TextButton(onClick = { scanError = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}
