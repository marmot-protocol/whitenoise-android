package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A notification-routed conversation can open before its account switch lands
 * (#586). The controller must then bind every account-explicit operation to
 * the pinned target account, never the still-active previous one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationControllerAccountPinningTest {
    @Test
    fun pinnedControllerSendsWithTheTargetAccountWhileAnotherAccountIsActive() =
        runTest {
            var sendAccount: String? = null
            val controller =
                ConversationController(
                    appState = appState(activeAccountRef = PREVIOUS_REF),
                    initialGroup = group(),
                    initialMemberSnapshot = targetMemberSnapshot(),
                    accountRefOverride = TARGET_REF,
                    textPublisher = { _, account, _, _ ->
                        sendAccount = account
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(MESSAGE_ID),
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello from the pinned account")

            assertEquals(TARGET_REF, sendAccount)
            assertEquals(TARGET_REF, controller.boundAccountRef)
        }

    @Test
    fun pinnedControllerResolvesSelfIdentityFromTheTargetAccount() {
        val controller =
            ConversationController(
                appState = appState(activeAccountRef = PREVIOUS_REF),
                initialGroup = group(),
                initialMemberSnapshot = targetMemberSnapshot(),
                accountRefOverride = TARGET_REF,
            )

        assertEquals(TARGET_ID, controller.boundAccountIdHex)
    }

    @Test
    fun pinnedControllerWithMissingAccountLabelFailsClosedInsteadOfBorrowingTheActiveIdentity() {
        val controller =
            ConversationController(
                appState = appState(activeAccountRef = PREVIOUS_REF),
                initialGroup = group(),
                accountRefOverride = "vanished-account",
            )

        assertEquals("vanished-account", controller.boundAccountRef)
        assertEquals(null, controller.boundAccountIdHex)
    }

    @Test
    fun unpinnedControllerKeepsBindingToTheActiveAccount() {
        val controller =
            ConversationController(
                appState = appState(activeAccountRef = PREVIOUS_REF),
                initialGroup = group(),
            )

        assertEquals(PREVIOUS_REF, controller.boundAccountRef)
        assertEquals(PREVIOUS_ID, controller.boundAccountIdHex)
    }

    private fun appState(activeAccountRef: String) =
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
                    ),
                    AccountSummaryFfi(
                        label = TARGET_REF,
                        accountIdHex = TARGET_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = activeAccountRef,
        )

    private fun targetMemberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = TARGET_ID,
                    account = TARGET_REF,
                    local = true,
                ),
            ),
        )

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
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private class NoopDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val PREVIOUS_REF = "alice"
        const val TARGET_REF = "bob"
        val PREVIOUS_ID = "a1".repeat(32)
        val TARGET_ID = "e5".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val MESSAGE_ID = "c3".repeat(32)
    }
}
