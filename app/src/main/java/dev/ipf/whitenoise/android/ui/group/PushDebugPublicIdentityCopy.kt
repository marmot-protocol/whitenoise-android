package dev.ipf.whitenoise.android.ui.group

internal data class PushDebugPublicIdentityCopy(
    val memberNpub: String?,
    val serverNpub: String?,
)

internal fun pushDebugPublicIdentityCopy(
    memberIdHex: String,
    serverPubkeyHex: String,
    npubForDisplay: (String) -> String,
): PushDebugPublicIdentityCopy =
    PushDebugPublicIdentityCopy(
        memberNpub = npubForDisplay(memberIdHex).takeIf { it.isNotBlank() },
        serverNpub = npubForDisplay(serverPubkeyHex).takeIf { it.isNotBlank() },
    )
