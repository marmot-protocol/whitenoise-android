package dev.ipf.whitenoise.android.ui.common

import androidx.compose.ui.graphics.vector.ImageVector
import dev.ipf.whitenoise.android.ui.icons.Icons
import dev.ipf.whitenoise.android.ui.icons.filled.CheckCircle
import dev.ipf.whitenoise.android.ui.icons.filled.RadioButtonUnchecked

internal fun selectionRowIcon(selected: Boolean): ImageVector =
    if (selected) {
        Icons.Default.CheckCircle
    } else {
        Icons.Default.RadioButtonUnchecked
    }
