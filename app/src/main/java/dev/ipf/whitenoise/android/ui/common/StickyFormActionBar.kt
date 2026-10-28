package dev.ipf.whitenoise.android.ui.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ipf.whitenoise.android.ui.navigation.floatingDictationFooter
import dev.ipf.whitenoise.android.ui.theme.Dimens
import dev.ipf.whitenoise.android.ui.theme.amoledSurfaceBorderStroke

/**
 * Bottom action rail for form screens whose primary action must remain tappable
 * while a text field owns the IME. The scrollable content lives above this in the
 * Scaffold body; this bar follows navigation/IME insets like the composer. Temporary
 * banners sit in the screen's top region (#3312), so the bar reserves nothing for them.
 */
@Composable
internal fun StickyFormActionBar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().floatingDictationFooter(),
        border = amoledSurfaceBorderStroke(),
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(
                        start = Dimens.spaceLg,
                        top = Dimens.spaceMd,
                        end = Dimens.spaceLg,
                        bottom = Dimens.spaceMd,
                    ),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spaceMd),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
