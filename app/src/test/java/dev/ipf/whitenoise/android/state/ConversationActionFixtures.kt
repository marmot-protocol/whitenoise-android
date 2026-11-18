package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.GroupLifecycleStateFfi
import dev.ipf.marmotkit.GroupMemberDetailsFfi
import dev.ipf.marmotkit.GroupRosterFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import org.junit.Assert.assertTrue
import java.lang.reflect.Proxy

/**
 * A group conversation whose bound account is not the active one, on a scripted native engine. Poll and
 * sticker action tests use it to prove sends pin the conversation's account and reach MDK unchanged.
 */
internal object ConversationActionFixtures {
    const val PREVIOUS_REF = "alice"
    const val TARGET_REF = "bob"
    val PREVIOUS_ID = "a1".repeat(32)
    val TARGET_ID = "e5".repeat(32)
    val GROUP_ID = "b2".repeat(32)
    val MESSAGE_ID = "c3".repeat(32)

    /** Opens the pinned group conversation and confirms it can send before the test acts. */
    suspend fun controller(state: WhiteNoiseAppState) =
        ConversationController(
            state,
            group(),
            initialMemberSnapshot = memberSnapshot(),
            accountRefOverride = TARGET_REF,
            groupRosterReader = { _, _ ->
                GroupRosterFfi(
                    GROUP_ID,
                    listOf(GroupMemberDetailsFfi(TARGET_ID, TARGET_REF, true, true, true, "npub-$TARGET_ID", null)),
                    1uL,
                    1uL,
                    SelfMembershipFfi.MEMBER,
                    1u,
                    GroupLifecycleStateFfi.STABLE,
                )
            },
        ).also {
            it.retryMembers()
            assertTrue(it.canSendMessages)
        }

    /** Routes every native call except object identity to [call], with mangled inline-class names trimmed. */
    fun native(call: (String, Array<out Any?>) -> Any?): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (val name = method.name.substringBefore('-')) {
                "toString" -> "conversation-action-test-native"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> call(name, args.orEmpty())
            }
        } as MarmotInterface

    /** A one-event send summary with the given acceptance outcome. */
    fun summary(disposition: SendAcceptDispositionFfi = SendAcceptDispositionFfi.PUBLISHED) =
        SendSummaryFfi(1u, listOf(MESSAGE_ID), disposition, SendMaintenanceDispositionFfi.READY)

    /** Builds an app state on the given native interface with the previous account active. */
    fun appState(native: MarmotInterface) =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(NoopDraftPersistence()),
            accountIdHexResolver = { PREVIOUS_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = PREVIOUS_REF,
                        accountIdHex = PREVIOUS_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                    AccountSummaryFfi(
                        label = TARGET_REF,
                        accountIdHex = TARGET_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                        unlinked = false,
                    ),
                ),
            activeAccountRef = PREVIOUS_REF,
            initialMarmotRuntime = AppMarmotRuntime("test", native),
        )

    /** The bound account as the group's only local member. */
    private fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = TARGET_ID,
                    account = TARGET_REF,
                    local = true,
                ),
            ),
        )

    /** A stable named group, not a direct chat, administered by the bound account. */
    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Pinned group",
            description = "",
            admins = listOf(TARGET_ID),
            relays = listOf("wss://relay.example"),
            nostrGroupIdHex = "04".repeat(32),
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

    /** Draft storage that keeps nothing, so each test starts with an empty composer. */
    private class NoopDraftPersistence : DraftPersistence {
        /** Starts every test with no saved drafts. */
        override fun read(): Map<String, String> = emptyMap()

        /** Discards every draft write. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
}
