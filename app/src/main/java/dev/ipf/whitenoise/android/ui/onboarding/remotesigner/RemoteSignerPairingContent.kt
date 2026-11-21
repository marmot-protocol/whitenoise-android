package dev.ipf.whitenoise.android.ui.onboarding.remotesigner

import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.SystemPasteIconButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButtonDefaults
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseOutlinedButton
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseSecureTextField
import dev.ipf.whitenoise.android.ui.common.copySensitiveToClipboard
import dev.ipf.whitenoise.android.ui.common.plainText
import dev.ipf.whitenoise.android.ui.common.rememberClipboardCanOfferPaste
import dev.ipf.whitenoise.android.ui.common.withPrimaryClipForPaste
import dev.ipf.whitenoise.android.ui.qr.QrCodeImage
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** The two pairing directions as quiet rows; the choice itself starts nothing on the network. */
@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerMethodChoice(
    onLink: () -> Unit,
    onCode: () -> Unit,
) {
    RemoteSignerReviewSupporting(stringResource(R.string.remote_signer_intro))
    RemoteSignerMethodRow(
        R.string.remote_signer_method_link,
        R.string.remote_signer_method_link_detail,
        "remote_signer.method.link",
        onLink,
    )
    RemoteSignerMethodRow(
        R.string.remote_signer_method_code,
        R.string.remote_signer_method_code_detail,
        "remote_signer.method.code",
        onCode,
    )
}

@Suppress("FunctionNaming")
@Composable
private fun RemoteSignerMethodRow(
    title: Int,
    detail: Int,
    tag: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag(tag).semantics { role = Role.Button },
    ) {
        Row(Modifier.padding(WhiteNoiseSpacing.Section), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
                RemoteSignerReviewSupporting(stringResource(detail))
            }
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
    }
}

/** A masked bunker-link field with paste and scan; the link is a secret and is never echoed back. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun RemoteSignerLinkField(pairing: RemoteSignerPairing) {
    val context = LocalContext.current
    val clipboard = remember(context) { ContextCompat.getSystemService(context, ClipboardManager::class.java) }
    val canPaste = rememberClipboardCanOfferPaste(clipboard)
    var scanning by remember { mutableStateOf(false) }

    fun replace(value: String) {
        pairing.link.setTextAndPlaceCursorAtEnd(value.trim())
        pairing.linkErrorRes = null
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhiteNoiseSecureTextField(
            state = pairing.link,
            textObfuscationMode = TextObfuscationMode.Hidden,
            modifier = Modifier.weight(1f).testTag("remote_signer.link"),
            label = { Text(stringResource(R.string.remote_signer_link_label)) },
            placeholder = { Text(stringResource(R.string.remote_signer_link_placeholder)) },
            errorMessage = pairing.linkErrorRes?.let { stringResource(it) },
            supportingText = { Text(stringResource(pairing.linkErrorRes ?: R.string.remote_signer_link_help)) },
            trailingIcon = {
                if (pairing.link.text.isEmpty() && canPaste) {
                    SystemPasteIconButton(
                        onPaste = {
                            clipboard.withPrimaryClipForPaste { clip -> clip.plainText(context)?.let(::replace) }
                        },
                        modifier = Modifier.padding(horizontal = 4.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_content_paste), stringResource(R.string.paste))
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
            onKeyboardAction = { pairing.submitLink() },
        )
        FilledTonalIconButton(
            onClick = { scanning = true },
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.size(WhiteNoiseButtonDefaults.TaskHeight).testTag("remote_signer.link.scan"),
        ) {
            Icon(painterResource(R.drawable.ic_qr_code_scanner), stringResource(R.string.scan_qr_code))
        }
    }
    if (scanning) {
        QrScannerSheet(
            onDismiss = { scanning = false },
            onScan = { raw ->
                scanning = false
                replace(raw)
            },
        )
    }
}

/** The generated connection code: a QR for the signer to scan, with copy as the text alternative. */
@Suppress("FunctionNaming")
@Composable
internal fun RemoteSignerConnectionCode(offer: String) {
    val context = LocalContext.current
    var copied by remember(offer) { mutableStateOf(false) }
    Text(stringResource(R.string.remote_signer_code_title), style = MaterialTheme.typography.titleMedium)
    RemoteSignerReviewSupporting(stringResource(R.string.remote_signer_code_body))
    Column(Modifier.fillMaxWidth().testTag("remote_signer.code"), horizontalAlignment = Alignment.CenterHorizontally) {
        QrCodeImage(offer, contentDescription = stringResource(R.string.remote_signer_code_description))
    }
    val copyLabel = stringResource(R.string.remote_signer_code_copy)
    WhiteNoiseOutlinedButton(
        onClick = {
            copySensitiveToClipboard(context, copyLabel, offer)
            copied = true
        },
        modifier = Modifier.fillMaxWidth().testTag("remote_signer.code.copy"),
    ) {
        Icon(painterResource(R.drawable.ic_content_copy), contentDescription = null)
        Text(copyLabel, Modifier.padding(start = WhiteNoiseSpacing.Related))
    }
    if (copied) RemoteSignerReviewSupporting(stringResource(R.string.remote_signer_code_copied))
    RemoteSignerReviewSupporting(stringResource(R.string.remote_signer_waiting))
}
