package dev.ipf.whitenoise.android.state

import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class PendingSendDraftPresentationTest {
    @Test
    fun pendingSendHidesItsCapturedRecoveryDraftFromTheChatRow() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("sending now"))
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller = controller(appState, publishStarted, finishPublish)

            val send = async { appState.sendConversationText(controller, "sending now") }
            publishStarted.await()

            assertEquals("sending now", appState.draftFor(ACCOUNT_REF, GROUP_ID))
            assertEquals(
                null,
                appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID, OutgoingMessageIndicator.Sending),
            )
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)

            finishPublish.complete(Unit)
            send.await()

            assertEquals(null, appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun failedSendRestoresItsRecoveryDraftToTheChatRow() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("try again"))
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(),
                    initialMemberSnapshot = memberSnapshot(),
                    textPublisher = { _, _, _, _ ->
                        throw MarmotKitException.Publish("relay rejected event")
                    },
                )

            appState.sendConversationText(controller, "try again")

            assertEquals(MessageStatus.Failed, controller.timeline.single().status)
            assertEquals(
                "try again",
                appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID, OutgoingMessageIndicator.Failed),
            )
        }

    @Test
    fun newerDraftRemainsVisibleWhileTheOlderSendIsPending() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("first message"))
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller = controller(appState, publishStarted, finishPublish)

            val send = async { appState.sendConversationText(controller, "first message") }
            publishStarted.await()
            appState.setDraft(TextFieldValue("next message"))

            assertEquals(
                "next message",
                appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID, OutgoingMessageIndicator.Sending),
            )

            finishPublish.complete(Unit)
            send.await()

            assertEquals("next message", appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    @Test
    fun identicalNewerDraftIsNotMistakenForThePendingSendRecoveryDraft() =
        runTest {
            val appState = appState()
            appState.setDraft(TextFieldValue("same words"))
            val publishStarted = CompletableDeferred<Unit>()
            val finishPublish = CompletableDeferred<Unit>()
            val controller = controller(appState, publishStarted, finishPublish)

            val send = async { appState.sendConversationText(controller, "same words") }
            publishStarted.await()
            appState.setDraft(TextFieldValue("same words"))

            assertEquals(
                "same words",
                appState.chatRowDraftFor(ACCOUNT_REF, GROUP_ID, OutgoingMessageIndicator.Sending),
            )

            finishPublish.complete(Unit)
            send.await()

            assertEquals("same words", appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    private fun controller(
        appState: WhiteNoiseAppState,
        publishStarted: CompletableDeferred<Unit>,
        finishPublish: CompletableDeferred<Unit>,
    ) = ConversationController(
        appState = appState,
        initialGroup = group(),
        initialMemberSnapshot = memberSnapshot(),
        textPublisher = { _, _, _, _ ->
            publishStarted.complete(Unit)
            finishPublish.await()
            SendSummaryFfi(
                published = 1u,
                messageIds = listOf(CONFIRMED_MESSAGE_ID),
                acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
            )
        },
    )

    private fun appState() =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext(),
            draftStore = DraftStore(TestDraftPersistence()),
            accountIdHexResolver = { ACCOUNT_ID },
            accounts =
                listOf(
                    AccountSummaryFfi(
                        label = ACCOUNT_REF,
                        accountIdHex = ACCOUNT_ID,
                        localSigning = true,
                        externalSigning = false,
                        signedOut = false,
                        running = true,
                    ),
                ),
            activeAccountRef = ACCOUNT_REF,
        )

    private fun WhiteNoiseAppState.setDraft(value: TextFieldValue) = setDraft(ACCOUNT_REF, GROUP_ID, value)

    private fun memberSnapshot() =
        GroupMemberSnapshot(
            listOf(
                AppGroupMemberRecordFfi(
                    memberIdHex = ACCOUNT_ID,
                    account = ACCOUNT_REF,
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
            name = "Pending draft group",
            description = "",
            admins = listOf(ACCOUNT_ID),
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

    private class TestDraftPersistence : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}
