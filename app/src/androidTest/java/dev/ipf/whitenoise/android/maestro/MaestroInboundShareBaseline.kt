package dev.ipf.whitenoise.android.maestro

internal data class MaestroInboundShareBaseline(
    val owner: String,
    val accountIds: Set<String>,
    val group: String,
    val requestId: String,
    val messages: Map<String, List<MaestroSharedMessageSnapshot>>,
)
