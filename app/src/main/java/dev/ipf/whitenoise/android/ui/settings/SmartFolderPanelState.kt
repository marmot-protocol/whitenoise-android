package dev.ipf.whitenoise.android.ui.settings

import dev.ipf.whitenoise.android.state.SmartFolderFilter

internal data class SmartFolderPanelState(
    val advanced: Boolean,
    val root: SmartFolderFilter.Group?,
    val unresolved: Int = 0,
)
