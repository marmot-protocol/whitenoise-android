package dev.ipf.whitenoise.android.maestro

import dev.ipf.whitenoise.android.state.ChatFolderAccountState

internal data class MaestroFolderRulesBaseline(
    val owner: String,
    val accounts: Map<String, ChatFolderAccountState>,
)
