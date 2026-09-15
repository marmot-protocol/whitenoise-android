package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Reading and form panes stop growing past this width; larger windows centre the pane instead. */
internal val WhiteNoiseContentMaxWidth = 680.dp

/** Centres [content] in a pane bounded by [WhiteNoiseContentMaxWidth] on wide windows. */
@Suppress("FunctionNaming")
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier =
                Modifier
                    .widthIn(max = WhiteNoiseContentMaxWidth)
                    .fillMaxWidth(),
            content = content,
        )
    }
}
