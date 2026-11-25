package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi

/** The demo agent's name in the demo chat. */
internal const val AGENT_DEMO_NAME = "Demo agent"
internal val AGENT_DEMO_PEER_ID = "b4".repeat(32)
internal const val AGENT_DEMO_PEER_NAME = "Maya"

/**
 * A conversation controller for the on-device agent demo, so its rows draw exactly as a direct chat
 * with an agent. It is never started: it reads nothing from MDK and its roster comes from the
 * snapshot, so the demo chat never appears in the chat list.
 */
internal fun agentDemoController(
    appState: WhiteNoiseAppState,
    viewerId: String,
    mixed: Boolean = false,
): ConversationController {
    val members =
        listOf(
            AppGroupMemberRecordFfi(viewerId, appState.activeAccountRef, true),
            AppGroupMemberRecordFfi(AgentDemoRecords.AGENT_ID, null, false),
        ) + listOfNotNull(AppGroupMemberRecordFfi(AGENT_DEMO_PEER_ID, null, false).takeIf { mixed })
    return ConversationController(
        appState = appState,
        initialGroup = agentDemoGroup(viewerId),
        initialMemberSnapshot = GroupMemberSnapshot(members),
        initialIsDm = !mixed,
    )
}

/** Names the demo agent for this account's session, the way a notification names a sender before its profile loads. */
internal fun WhiteNoiseAppState.nameAgentDemo() {
    val names = mapOf(AgentDemoRecords.AGENT_ID to AGENT_DEMO_NAME, AGENT_DEMO_PEER_ID to AGENT_DEMO_PEER_NAME)
    val changed =
        synchronized(profilePresentationLock) {
            names.filter { (id, name) -> notificationDisplayNameHints.put(id, name) != name }.keys
        }
    if (changed.isNotEmpty()) {
        profileRevision += 1
        changed.forEach(::bumpProfileAccountRevision)
    }
}

private fun agentDemoGroup(viewerId: String) =
    AppGroupRecordFfi(
        groupIdHex = AgentDemoRecords.GROUP_ID,
        protocolProfile = AppProtocolProfileFfi.LEGACY,
        endpoint = "",
        profilePresent = true,
        name = AGENT_DEMO_NAME,
        description = "",
        admins = listOf(viewerId),
        relays = emptyList(),
        nostrGroupIdHex = AgentDemoRecords.GROUP_ID,
        avatarUrl = null,
        avatarDim = null,
        avatarThumbhash = null,
        imageHashHex = null,
        encryptedMedia =
            AppGroupEncryptedMediaComponentFfi(
                componentId = ENCRYPTED_MEDIA_COMPONENT_ID,
                component = "marmot.group.encrypted-media.v1",
                required = true,
                version = EncryptedMediaVersionFfi.V1,
                mediaFormat = "encrypted-media-v1",
                allowedLocatorKinds = emptyList(),
                defaultBlobEndpoints = emptyList<AppBlobEndpointFfi>(),
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

private const val ENCRYPTED_MEDIA_COMPONENT_ID: UInt = 0x8008u
