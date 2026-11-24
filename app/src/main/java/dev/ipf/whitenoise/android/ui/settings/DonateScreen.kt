package dev.ipf.whitenoise.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R
import dev.ipf.whitenoise.android.core.WhiteNoiseUrls
import dev.ipf.whitenoise.android.ui.common.Explanation
import dev.ipf.whitenoise.android.ui.common.WhiteNoiseButton
import dev.ipf.whitenoise.android.ui.common.explainOnLongPress
import dev.ipf.whitenoise.android.ui.common.whiteNoiseVerticalScroll
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Opens the foundation donation page from one prominent call to action. */
@Suppress("FunctionNaming", "LongMethod")
@Composable
internal fun DonateScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var openFailed by rememberSaveable { mutableStateOf(false) }
    SettingsScaffold(
        title = stringResource(R.string.settings_donate),
        onBack = onBack,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().whiteNoiseVerticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
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
                val donateLabel = stringResource(R.string.settings_donate)
                WhiteNoiseButton(
                    onClick = {
                        openFailed = runCatching { uriHandler.openUri(WhiteNoiseUrls.DONATE) }.isFailure
                    },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = DonationTopInset)
                            .testTag("donate.open")
                            .explainOnLongPress(
                                Explanation(
                                    donateLabel,
                                    stringResource(R.string.explanation_organize_donate_open),
                                ),
                            ),
                ) {
                    Text(donateLabel)
                }
                if (openFailed) {
                    Text(
                        stringResource(R.string.donate_open_failed),
                        modifier =
                            Modifier
                                .padding(top = WhiteNoiseSpacing.Related)
                                .testTag("donate.open_failed")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private val DonationTopInset = 40.dp
private val DonationHeartSize = 40.dp
