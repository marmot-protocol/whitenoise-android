package dev.ipf.whitenoise.android.maestro

import dev.ipf.marmotkit.AccountRelayListsFfi

internal data class MaestroRelayListsBaseline(
    val owner: String,
    val accounts: Map<String, AccountRelayListsFfi>,
)
