package dev.ipf.whitenoise.android.ui.profile

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.GroupMemberSnapshot

/** Current native roster fixtures, using the existing production FFI constructor shapes. */
internal fun personTestGroup(
    groupId: String,
    name: String,
    admins: List<String> = emptyList(),
    members: List<String>?,
    pending: Boolean = false,
) = ChatListItem(
    group = group(groupId, name, admins, pending),
    latest = null,
    otherMemberAccount = null,
    memberCount = members?.size ?: 0,
    memberSnapshot = members?.let { GroupMemberSnapshot(it.map(::member)) },
)

private fun group(
    groupId: String,
    name: String,
    admins: List<String>,
    pending: Boolean,
) = AppGroupRecordFfi(
    selfMembership = SelfMembershipFfi.MEMBER,
    groupIdHex = groupId,
    protocolProfile = dev.ipf.marmotkit.AppProtocolProfileFfi.LEGACY,
    profilePresent = false,
    endpoint = "endpoint",
    name = name,
    description = "",
    admins = admins,
    relays = listOf("wss://relay.example"),
    nostrGroupIdHex = "nostr-$groupId",
    avatarUrl = null,
    avatarDim = null,
    avatarThumbhash = null,
    imageHashHex = null,
    encryptedMedia = encryptedMedia(),
    archived = false,
    pendingConfirmation = pending,
    unrecoverable = false,
    welcomerAccountIdHex = null,
    viaWelcomeMessageIdHex = null,
    disappearingMessageSecs = 0uL,
    leaveRequestPending = false,
    leaveRequestedAtMs = null,
    disbanding = false,
    disbanded = false,
    disbandRequest = null,
)

private fun member(memberId: String) =
    AppGroupMemberRecordFfi(
        memberIdHex = memberId,
        account = memberId,
        local = false,
    )

private fun encryptedMedia() =
    AppGroupEncryptedMediaComponentFfi(
        componentId = 0x8008u,
        component = "marmot.group.encrypted-media.v1",
        required = true,
        version = dev.ipf.marmotkit.EncryptedMediaVersionFfi.V1,
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
