package dev.ipf.whitenoise.android.ui.conversation

import android.content.Context
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

/** Builds an app state with [accountRef] active and signed in locally, backed by an empty draft store. */
internal fun topBarTestAppState(
    context: Context,
    accountRef: String,
    accountId: String,
): WhiteNoiseAppState =
    WhiteNoiseAppState(
        context = context,
        draftStore = DraftStore(EmptyDraftPersistence()),
        accountIdHexResolver = { accountId },
        accounts =
            listOf(
                AccountSummaryFfi(
                    label = accountRef,
                    accountIdHex = accountId,
                    localSigning = true,
                    externalSigning = false,
                    signedOut = false,
                    running = true,
                    unlinked = false,
                ),
            ),
        activeAccountRef = accountRef,
    )

/** Builds the "Benchmark group" record [groupId], member and joined, with [adminId] as its only admin. */
internal fun topBarTestGroup(
    groupId: String,
    adminId: String,
): AppGroupRecordFfi =
    AppGroupRecordFfi(
        groupIdHex = groupId,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "wss://relay.example",
        profilePresent = true,
        name = "Benchmark group",
        description = "",
        admins = listOf(adminId),
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
        leaveBlocked = false,
        disbanding = false,
        disbandRequest = null,
        disbanded = false,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
    )

private class EmptyDraftPersistence : DraftPersistence {
    override fun read(): Map<String, String> = emptyMap()

    override fun write(
        key: String,
        value: String?,
    ) = Unit
}
