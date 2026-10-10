package dev.ipf.whitenoise.android.maestro

internal data class MaestroInboundShareBaseline(
    val owner: String,
    val accountIds: Map<String, String>,
    val group: String,
    val requestId: String,
    val messages: Map<String, List<MaestroSharedMessageSnapshot>>,
    val text: String?,
    val files: List<MaestroImportedFileSnapshot>,
)
