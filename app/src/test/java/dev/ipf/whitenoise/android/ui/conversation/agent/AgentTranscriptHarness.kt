package dev.ipf.whitenoise.android.ui.conversation.agent

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ConversationController
import dev.ipf.whitenoise.android.state.DraftPersistence
import dev.ipf.whitenoise.android.state.DraftStore
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.runBlocking

/** An account, the agent's profile name and a verified roster for transcript screenshots. */
internal class AgentTranscriptHarness {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    fun appState(agentName: String): WhiteNoiseAppState {
        val names = mapOf(AgentRecordingFixtures.AGENT_ID to agentName, OTHER_ID to "Jeff")
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore(EmptyDraftPersistence()),
                accountIdHexResolver = { it },
                accounts =
                    listOf(
                        AccountSummaryFfi(
                            label = ACCOUNT_REF,
                            accountIdHex = AgentRecordingFixtures.VIEWER_ID,
                            localSigning = true,
                            externalSigning = false,
                            signedOut = false,
                            running = true,
                            unlinked = false,
                        ),
                    ),
                activeAccountRef = ACCOUNT_REF,
                profileDisplayNameReader = { id -> names[id] },
            )
        runBlocking { state.warmProfilePresentationsBlocking(names.keys.toList()) }
        return state
    }

    /** A chat with the agent alone, or a group where another person is present too. */
    fun controller(
        appState: WhiteNoiseAppState,
        group: Boolean,
    ): ConversationController {
        val memberIds =
            listOfNotNull(AgentRecordingFixtures.VIEWER_ID, AgentRecordingFixtures.AGENT_ID, OTHER_ID.takeIf { group })
        val controller =
            ConversationController(
                appState = appState,
                initialGroup = group(if (group) "whitenoise-android" else "Agent"),
                groupRosterReader = { _, _ -> roster(memberIds.map(::member)) },
            )
        runBlocking { controller.retryMembers() }
        return controller
    }

    private fun roster(members: List<GroupMemberDetailsFfi>) =
        GroupRosterFfi(
            groupIdHex = AgentRecordingFixtures.GROUP_ID,
            members = members,
            epoch = 1uL,
            rosterRevision = members.size.toULong(),
            selfMembership = SelfMembershipFfi.MEMBER,
            memberCount = members.size.toUInt(),
            lifecycleState = GroupLifecycleStateFfi.STABLE,
        )

    private fun member(id: String): GroupMemberDetailsFfi {
        val local = id == AgentRecordingFixtures.VIEWER_ID
        return GroupMemberDetailsFfi(
            memberIdHex = id,
            account = ACCOUNT_REF.takeIf { local },
            local = local,
            isAdmin = local,
            isSelf = local,
            npub = "npub-$id",
            displayName = null,
        )
    }

    private fun group(name: String) =
        AppGroupRecordFfi(
            groupIdHex = AgentRecordingFixtures.GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = name,
            description = "",
            admins = listOf(AgentRecordingFixtures.VIEWER_ID),
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
                        listOf(AppBlobEndpointFfi(locatorKind = "blossom-v1", baseUrl = "https://blossom.example")),
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

    private companion object {
        const val ACCOUNT_REF = "alice"
        val OTHER_ID = "33".repeat(32)
    }
}
