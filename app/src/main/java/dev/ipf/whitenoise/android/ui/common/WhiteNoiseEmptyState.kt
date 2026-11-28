package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.ipf.whitenoise.android.ui.theme.WhiteNoiseSpacing

/** Centred title and optional detail for a list with nothing in it. */
@Suppress("FunctionNaming")
@Composable
fun WhiteNoiseEmptyState(
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
) {
    StateContent(
        modifier = modifier.padding(WhiteNoiseSpacing.Section),
        title = title,
        message = detail,
        verticalSpacing = WhiteNoiseSpacing.Related,
    )
}
