package dev.ipf.whitenoise.android.ui.theme

import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Semantic opacity tokens for black scrims drawn over media. */
object ScrimAlpha {
    const val Light = 0.35f
    const val LightEmphasis = 0.4f
    const val Medium = 0.45f
    const val MediumEmphasis = 0.5f
    const val Strong = 0.55f
    const val Gradient = 0.6f
    const val Heavy = 0.62f
}

/** App-wide horizontal separator using Material's divider-specific color role. */
@Composable
fun AppDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
