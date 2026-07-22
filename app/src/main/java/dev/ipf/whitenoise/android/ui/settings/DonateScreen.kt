package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.IdentityFormatter
import dev.ipf.whitenoise.android.ui.common.CopyableValueRow
import dev.ipf.whitenoise.android.ui.common.sectionPanelColor
import dev.ipf.whitenoise.android.ui.qr.QrCodeImage
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorder

private data class DonationMethod(
    val label: String,
    val addressLabel: String,
    val value: String,
)

// Settings -> Support the project (#285). Static, public donation addresses
// shipped as localized resources — no protocol data, no Android-owned cache.
// Wallet-style focused page: one method visible at a time via the segmented
// selector (mutually-exclusive choice), its QR inline above a tap-to-copy row.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DonateScreen(onBack: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    var selected by rememberSaveable { mutableStateOf(0) }
    val methods =
        listOf(
            DonationMethod(
                label = stringResource(R.string.donate_method_lightning),
                addressLabel = stringResource(R.string.donate_lightning_address),
                value = stringResource(R.string.donate_lightning_value),
            ),
            DonationMethod(
                label = stringResource(R.string.donate_method_bitcoin),
                addressLabel = stringResource(R.string.donate_bitcoin_silent_payment),
                value = stringResource(R.string.donate_bitcoin_silent_payment_value),
            ),
        )
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.support_the_project)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Dimens.spaceLg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Dimens.spaceLg),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    Icons.Default.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(Dimens.spaceLg).size(28.dp),
                )
            }
            Text(
                stringResource(R.string.support_the_project_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                methods.forEachIndexed { index, method ->
                    SegmentedButton(
                        selected = selected == index,
                        onClick = { selected = index },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = methods.size),
                    ) {
                        Text(method.label)
                    }
                }
            }
            ElevatedCard(
                modifier = Modifier.fillMaxWidth().amoledSurfaceBorder(RoundedCornerShape(12.dp)),
                colors = CardDefaults.elevatedCardColors(containerColor = sectionPanelColor()),
            ) {
                AnimatedContent(targetState = methods[selected], label = "donationMethod") { method ->
                    Column(
                        Modifier.fillMaxWidth().padding(Dimens.spaceLg),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
                    ) {
                        QrCodeImage(
                            content = method.value,
                            contentDescription =
                                stringResource(R.string.donate_qr_code_content_description, method.addressLabel, method.value),
                        )
                        CopyableValueRow(
                            label = method.addressLabel,
                            value = method.value,
                            clipboard = clipboard,
                            displayValue = IdentityFormatter.short(method.value, prefix = 18, suffix = 12),
                        )
                    }
                }
            }
        }
    }
}
