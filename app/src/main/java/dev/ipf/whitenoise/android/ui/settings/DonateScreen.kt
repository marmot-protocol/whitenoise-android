package dev.ipf.whitenoise.android.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.ui.common.AdaptiveContent
import dev.ipf.whitenoise.android.ui.common.LocalWhiteNoiseHeaderScroll
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseScaffold
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing
import kotlinx.coroutines.delay

/**
 * Donate: a connected Lightning / Bitcoin selector in the top bar, the heart and pitch, the QR of the selected
 * address and its tap-to-copy capsule with a caption. Addresses are the shipped public ones.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun DonateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var copiedMethod by rememberSaveable { mutableIntStateOf(-1) }
    val method = DonationMethod.entries[selected]
    val methodLabel = stringResource(method.labelRes)
    val value = stringResource(method.valueRes)
    val donationAddress = stringResource(R.string.donation_address, methodLabel)
    LaunchedEffect(copiedMethod) {
        if (copiedMethod >= 0) {
            delay(COPIED_FEEDBACK_MILLIS)
            copiedMethod = -1
        }
    }
    WhiteNoiseScaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            CenterAlignedTopAppBar(
                title = { DonationMethodSelector(selectedIndex = selected, onSelect = { selected = it }) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.back))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = LocalWhiteNoiseHeaderScroll.current,
            )
        },
    ) { innerPadding ->
        AdaptiveContent(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize().whiteNoiseVerticalScroll(rememberScrollState()),
                contentAlignment = Alignment.TopCenter,
            ) {
                val availableWidth = maxWidth
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(
                                start = WhiteNoiseSpacing.CompactScreenMargin,
                                top = DonationTopInset,
                                end = WhiteNoiseSpacing.CompactScreenMargin,
                                bottom = WhiteNoiseSpacing.Section,
                            ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(WhiteNoiseSpacing.Related),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings_favorite_border),
                            contentDescription = null,
                            modifier = Modifier.size(DonationHeartSize),
                        )
                        Text(
                            stringResource(R.string.support_white_noise),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            stringResource(R.string.support_white_noise_detail),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    IdentityQrCodeSurface(
                        value = value,
                        availableWidth = availableWidth,
                        contentDescription = stringResource(R.string.donation_qr_code, methodLabel),
                        testTag = "donate.qr_surface",
                        modifier = Modifier.padding(top = DonationTopInset),
                    )
                    IdentifierCopyCapsule(
                        value = value,
                        copied = copiedMethod == selected,
                        onCopy = {
                            copyDonationAddress(context, donationAddress, value)
                            copiedMethod = selected
                        },
                        copyContentDescription = stringResource(R.string.donation_copy_address, methodLabel),
                        copiedContentDescription = stringResource(R.string.donation_address_copied, methodLabel),
                        notCopiedStateDescription = stringResource(R.string.not_copied),
                        copiedStateDescription = stringResource(R.string.copied),
                        targetTestTag = "donate.copy_address",
                        visualTestTag = "donate.copy_address.visual",
                        modifier = Modifier.padding(top = DonationIdentityGap),
                    )
                    Text(
                        text = stringResource(method.captionRes),
                        modifier =
                            Modifier
                                .padding(top = DonationIdentityGap)
                                .offset(y = -DonationCaptionPullUp)
                                .testTag("donate.method_caption"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/** Connected toggle pair that behaves as one radio group. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Suppress("FunctionNaming")
@Composable
private fun DonationMethodSelector(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.widthIn(max = DonationSelectorMaxWidth).selectableGroup().testTag("donate.method_selector"),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DonationMethod.entries.forEachIndexed { index, candidate ->
            ToggleButton(
                checked = index == selectedIndex,
                onCheckedChange = { onSelect(index) },
                shapes =
                    if (index == 0) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ButtonGroupDefaults.connectedTrailingButtonShapes()
                    },
                modifier =
                    Modifier
                        .semantics {
                            role = Role.RadioButton
                            selected = index == selectedIndex
                        }.testTag("donate.method.$index"),
            ) { Text(stringResource(candidate.labelRes)) }
        }
    }
}

/** Copies the address as an ordinary clip; donation addresses are public. */
private fun copyDonationAddress(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** The two shipped donation methods with their label, caption and address resources. */
private enum class DonationMethod(
    val labelRes: Int,
    val captionRes: Int,
    val valueRes: Int,
) {
    Lightning(R.string.donate_method_lightning, R.string.donate_lightning_address, R.string.donate_lightning_value),
    Bitcoin(
        R.string.donate_method_bitcoin,
        R.string.donate_bitcoin_silent_payment,
        R.string.donate_bitcoin_silent_payment_value,
    ),
}

private val DonationTopInset = 40.dp
private val DonationHeartSize = 40.dp
private val DonationSelectorMaxWidth = 240.dp
private val DonationIdentityGap = 1.dp
private val DonationCaptionPullUp = 4.dp
private const val COPIED_FEEDBACK_MILLIS = 2_000L
