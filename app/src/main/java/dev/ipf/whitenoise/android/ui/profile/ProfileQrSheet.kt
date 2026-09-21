package dev.ipf.whitenoise.android.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.AvatarImageLoader
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.core.ProfileLink
import dev.ipf.whitenoise.android.share.QrShareCardRenderer
import dev.ipf.whitenoise.android.share.QrShareCardSpec
import dev.ipf.whitenoise.android.share.launchOutboundShare
import dev.ipf.whitenoise.android.share.outboundShareIntent
import dev.ipf.whitenoise.android.share.presentOutboundShareFailure
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import dev.ipf.whitenoise.android.state.runCatchingCancellable
import dev.ipf.whitenoise.android.ui.common.Avatar
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseDropdownMenu
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseMenuItem
import dev.ipf.whitenoise.android.ui.qr.QrCodeImage
import dev.ipf.whitenoise.android.ui.qr.QrScanOutcome
import dev.ipf.whitenoise.android.ui.qr.QrScanResult
import dev.ipf.whitenoise.android.ui.qr.QrScanUseCase
import dev.ipf.whitenoise.android.ui.qr.QrScannerSheet
import dev.ipf.whitenoise.android.ui.theme.amoledSheetContainerColor

internal fun profileQrContentForNpub(npub: String): String? = ProfileLink.parse(npub)?.qrUri

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod") // One account-owned sheet and scanner receipt.
@Composable
internal fun ProfileQrSheet(
    appState: WhiteNoiseAppState,
    accountIdHex: String,
    onDismiss: () -> Unit,
    showScan: Boolean = true,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val npub = appState.npubForDisplay(accountIdHex)
    val link = remember(npub) { ProfileLink.parse(npub) }
    var copied by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var shareMenuExpanded by remember { mutableStateOf(false) }
    var pictureShareInProgress by remember { mutableStateOf(false) }
    var sheetActive by remember { mutableStateOf(true) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contentScrollState = rememberScrollState()
    val shareProfileTitle = stringResource(R.string.share_profile)
    val profileCardHeadline = stringResource(R.string.profile_share_card_headline)
    val notWhiteNoiseProfileQrError = stringResource(R.string.error_not_white_noise_profile_qr)
    val runtimeGeneration = remember { appState.runtimeGeneration }
    val accountRef = remember { appState.activeAccountRef }
    DisposableEffect(Unit) { onDispose { sheetActive = false } }

    /** Revoke captured callbacks before asking the host to remove this sheet. */
    fun dismissSheet() {
        sheetActive = false
        onDismiss()
    }

    /** Fence asynchronous rendering from a replaced account/runtime or dismissed sheet. */
    fun ownsSheet(): Boolean =
        sheetActive &&
            appState.activeAccountRef == accountRef &&
            appState.runtimeGeneration == runtimeGeneration &&
            !appState.signOutInProgress &&
            !appState.wipeInProgress

    ModalBottomSheet(
        onDismissRequest = ::dismissSheet,
        sheetState = sheetState,
        containerColor = amoledSheetContainerColor(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(
                    state = contentScrollState,
                    enabled = contentScrollState.maxValue > 0,
                ).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Avatar(
                title = appState.displayName(accountIdHex),
                seed = accountIdHex,
                size = 120.dp,
                pictureUrl = appState.avatarUrl(accountIdHex),
            )
            Text(appState.displayName(accountIdHex), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (npub.isNotBlank()) {
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(npub))
                        copied = true
                    },
                ) {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (copied) stringResource(R.string.copied) else IdentityFormatter.short(npub, prefix = 16, suffix = 14))
                }
            }
            link?.let { QrCodeImage(content = it.qrUri) }
            scanError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { shareMenuExpanded = true },
                        enabled = npub.isNotBlank() && !pictureShareInProgress,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (pictureShareInProgress) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.QrCode, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.share))
                    }
                    WhiteNoiseDropdownMenu(
                        expanded = shareMenuExpanded,
                        onDismissRequest = { shareMenuExpanded = false },
                        items =
                            listOf(
                                WhiteNoiseMenuItem(
                                    stringResource(R.string.share_profile_url),
                                    onClick = {
                                        launchOutboundShare(
                                            context,
                                            outboundShareIntent(link?.uri ?: npub, emptyList()),
                                            shareProfileTitle,
                                        ).onFailure { appState.presentOutboundShareFailure("PROFILE_URL_SHARE", it) }
                                    },
                                    icon = R.drawable.ic_link,
                                ),
                                WhiteNoiseMenuItem(
                                    stringResource(R.string.share_profile_picture),
                                    onClick = {
                                        if (ownsSheet() && !pictureShareInProgress && link != null) {
                                            pictureShareInProgress = true
                                            appState.launchMutation {
                                                runCatchingCancellable {
                                                    val staged =
                                                        QrShareCardRenderer.stage(
                                                            context,
                                                            QrShareCardSpec(
                                                                headline = profileCardHeadline,
                                                                qrPayload = link.qrUri,
                                                                displayName = appState.displayName(accountIdHex),
                                                                avatar =
                                                                    AvatarImageLoader.peekBitmap(
                                                                        appState.avatarUrl(accountIdHex),
                                                                    ),
                                                            ),
                                                        )
                                                    if (ownsSheet()) {
                                                        launchOutboundShare(
                                                            context,
                                                            outboundShareIntent(link.uri, listOf(staged.stream)),
                                                            shareProfileTitle,
                                                        ).getOrThrow()
                                                    }
                                                }.onFailure {
                                                    if (ownsSheet()) {
                                                        appState.presentOutboundShareFailure(
                                                            "PROFILE_PICTURE_SHARE",
                                                            it,
                                                        )
                                                    }
                                                }
                                                if (ownsSheet()) pictureShareInProgress = false
                                            }
                                        }
                                    },
                                    icon = R.drawable.ic_image,
                                ),
                            ),
                    )
                }
                if (showScan) {
                    Button(
                        onClick = {
                            scanError = null
                            showScanner = true
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.scan))
                    }
                }
            }
        }
    }

    if (showScan && showScanner) {
        QrScannerSheet(
            onDismiss = { showScanner = false },
            onScan = { raw ->
                showScanner = false
                val accountIdHex = appState::accountIdHexForMention
                when (val outcome = QrScanResult.resolve(raw, QrScanUseCase.ViewProfile, accountIdHex)) {
                    is QrScanOutcome.OpenProfileNpub -> {
                        dismissSheet()
                        appState.presentProfile(outcome.npub)
                    }
                    is QrScanOutcome.OpenProfileNprofile -> {
                        dismissSheet()
                        appState.presentNostrProfile(outcome.nprofile)
                    }
                    QrScanOutcome.Invalid -> scanError = notWhiteNoiseProfileQrError
                    is QrScanOutcome.FillRecipientQuery -> scanError = notWhiteNoiseProfileQrError
                }
            },
        )
    }
}
