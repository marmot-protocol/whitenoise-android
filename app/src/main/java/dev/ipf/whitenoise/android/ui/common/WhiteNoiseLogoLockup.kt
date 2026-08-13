@file:Suppress("FunctionNaming") // Compose functions use PascalCase by convention.

package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.R

/** Shared White Noise brand mark for startup and onboarding surfaces. */
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
