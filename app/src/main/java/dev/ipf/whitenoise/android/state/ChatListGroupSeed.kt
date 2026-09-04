package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.ChatListRowFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi

/**
 * Builds a conservative group seed from an authoritative chat-list row while
 * the full group projection is unavailable. Capability-relevant lifecycle
 * fields are retained and fields the row cannot prove default to fail-closed.
 */
internal fun emptyGroupRecord(row: ChatListRowFfi): AppGroupRecordFfi =
    AppGroupRecordFfi(
        groupIdHex = row.groupIdHex,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "",
        profilePresent = false,
        name = row.groupName,
        description = "",
        admins = emptyList(),
        relays = emptyList(),
        nostrGroupIdHex = "",
        avatarUrl = row.avatarUrl,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = row.avatar?.imageHashHex,
        encryptedMedia = defaultEncryptedMediaComponent(),
        archived = row.archived,
        pendingConfirmation = row.pendingConfirmation,
        // Preserve the row's terminal lifecycle while the full group record is
        // deferred. This keeps send and management capabilities fail-closed on
        // the account-switch first frame.
        unrecoverable = row.lifecycleState == GroupLifecycleStateFfi.UNRECOVERABLE,
        selfMembership = row.selfMembership,
        welcomerAccountIdHex = null,
        viaWelcomeMessageIdHex = null,
        disappearingMessageSecs = 0uL,
        leaveRequestPending = false,
        leaveRequestedAtMs = null,
        // The row projects the authoritative lifecycle; a cold open of a
        // disbanding/disbanded chat must not flash an active composer while
        // the full group record is still loading.
        disbanding = row.disbanding,
        disbanded = row.lifecycleState == GroupLifecycleStateFfi.DISBANDED,
        disbandRequest = row.disbandRequest,
    )

private fun defaultEncryptedMediaComponent(): AppGroupEncryptedMediaComponentFfi =
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
                    baseUrl = "https://blossom.primal.net",
                ),
            ),
    )
