package dev.ipf.whitenoise.android.ui.conversation

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState

internal fun paintTestAppState(
    context: android.content.Context,
    accountRef: String,
    accountId: String,
) = WhiteNoiseAppState(
    context = context,
    draftStore = DraftStore(EmptyPaintDraftPersistence()),
    accountIdHexResolver = { null },
    accounts =
        listOf(
            AccountSummaryFfi(
                label = accountRef,
                accountIdHex = accountId,
                localSigning = true,
                externalSigning = false,
                signedOut = false,
                running = true,
            ),
        ),
    activeAccountRef = accountRef,
)

internal fun paintTestGroup(
    groupId: String,
    accountId: String,
) = AppGroupRecordFfi(
    groupIdHex = groupId,
    protocolProfile = AppProtocolProfileFfi.LEGACY,
    endpoint = "wss://relay.example",
    profilePresent = true,
    name = "Read-aloud paint group",
    description = "",
    admins = listOf(accountId),
    relays = emptyList(),
    nostrGroupIdHex = "03".repeat(32),
    avatarUrl = null,
    avatarDim = null,
    avatarThumbhash = null,
    imageHashHex = null,
    encryptedMedia =
        AppGroupEncryptedMediaComponentFfi(
            componentId = 0x8008u,
            component = "marmot.group.encrypted-media.v1",
            required = true,
            version = EncryptedMediaVersionFfi.V1,
            mediaFormat = "encrypted-media-v1",
            allowedLocatorKinds = listOf("blossom-v1"),
            defaultBlobEndpoints =
                listOf(
                    AppBlobEndpointFfi(
                        locatorKind = "blossom-v1",
                        baseUrl = "https://blossom.example",
                    ),
                ),
        ),
    disappearingMessageSecs = 0uL,
    archived = false,
    pendingConfirmation = false,
    unrecoverable = false,
    selfMembership = SelfMembershipFfi.MEMBER,
    leaveRequestPending = false,
    leaveRequestedAtMs = null,
    disbanding = false,
    disbandRequest = null,
    disbanded = false,
    welcomerAccountIdHex = null,
    viaWelcomeMessageIdHex = null,
)

private class EmptyPaintDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
