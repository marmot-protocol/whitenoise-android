package dev.ipf.whitenoise.android.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.ui.graphics.vector.ImageVector

internal fun selectionRowIcon(selected: Boolean): ImageVector =
    if (selected) {
        Icons.Default.CheckCircle
    } else {
        Icons.Default.RadioButtonUnchecked
    }
