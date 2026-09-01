package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownBlockFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The accepted plain-text send contract: the optimistic bubble is published —
 * and acceptance handed back to the composer — before the Markdown parse hop,
 * the group commit lock, and the network publish can suspend the mutation.
 * Delivery progress belongs on the bubble; nothing here may wait on I/O to
 * paint it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationSendOptimisticPublicationTest {
    @Test
    fun bubblePublishesAndAcceptsBeforeTheMarkdownParseHopCompletes() =
        runBlocking {
            val releaseParse = CompletableDeferred<MarkdownDocumentFfi>()
            var accepted = 0
            val controller =
                controller(
                    markdownParser = { releaseParse.await() },
                    textPublisher = { _, _, _, _ -> sentSummary() },
                )

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("hello *world*", onAccepted = { accepted += 1 })
                }

            assertEquals("the bubble must be visible while the parse hop is still pending", 1, controller.timeline.size)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertEquals(
                "hello *world*",
                controller.timeline
                    .single()
                    .record.plaintext,
            )
            assertEquals("acceptance must not wait on the parse hop", 1, accepted)
            assertTrue(
                controller.timeline
                    .single()
                    .record.contentTokens.blocks
                    .isEmpty(),
            )

            releaseParse.complete(styledDocument())
            send.await()

            assertTrue(
                "the parsed document must rebind onto the published bubble",
                controller.timeline
                    .single()
                    .record.contentTokens.blocks
                    .isNotEmpty(),
            )
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun bubbleIsPublishedBeforeTheNetworkPublishRuns() =
        runBlocking {
            var bubbleVisibleAtPublish = false
            val controller =
                controller(
                    textPublisher = { _, _, _, _ ->
                        bubbleVisibleAtPublish =
                            controller().timeline.singleOrNull()?.status == MessageStatus.Pending
                        sentSummary()
                    },
                )

            controller.send("hello")

            assertTrue("the optimistic bubble must precede the first publish attempt", bubbleVisibleAtPublish)
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun bubblePublishesWhileTheGroupCommitLockIsHeldElsewhere() =
        runBlocking {
            val appState = testAppState()
            val releaseLock = CompletableDeferred<Unit>()
            val lockHeld = CompletableDeferred<Unit>()
            var accepted = 0
            val controller =
                controller(
                    appState = appState,
                    textPublisher = { _, _, _, _ -> sentSummary() },
                )
            val holder =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    appState.withGroupCommitLock(ACCOUNT_REF, GROUP_ID) {
                        lockHeld.complete(Unit)
                        releaseLock.await()
                    }
                }
            lockHeld.await()

            val send =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("hello", onAccepted = { accepted += 1 })
                }

            assertEquals("a held commit lock must not delay the bubble", 1, controller.timeline.size)
            assertEquals(MessageStatus.Pending, controller.timeline.single().status)
            assertEquals(1, accepted)

            releaseLock.complete(Unit)
            holder.join()
            send.await()

            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
        }

    @Test
    fun backToBackSendsEachPublishTheirBubbleImmediately() =
        runBlocking {
            val releaseFirstPublish = CompletableDeferred<Unit>()
            var publishes = 0
            val controller =
                controller(
                    textPublisher = { _, _, _, _ ->
                        publishes += 1
                        if (publishes == 1) releaseFirstPublish.await()
                        sentSummary("c$publishes".padEnd(2, '0').repeat(32).take(64))
                    },
                )

            val first =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("first")
                }
            val second =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.send("second")
                }

            assertEquals(
                "the second bubble must not wait for the first send's round-trip",
                listOf("first", "second"),
                controller.timeline.map { it.record.plaintext },
            )
            assertTrue(controller.timeline.all { it.status == MessageStatus.Pending })

            releaseFirstPublish.complete(Unit)
            first.await()
            second.await()

            assertTrue(controller.timeline.all { it.status == MessageStatus.Sent })
        }

    @Test
    fun rejectedHandoffKeepsTheDraftAndPublishesNothing() =
        runBlocking {
            val appState = testAppState()
            appState.setDraft(ACCOUNT_REF, GROUP_ID, TextFieldValue("kept draft"))
            var accepted = 0
            // A projected non-member cannot hand a send off; the guard must
            // fake nothing and leave the draft in place.
            val controller =
                ConversationController(
                    appState = appState,
                    initialGroup = group(selfMembership = SelfMembershipFfi.REMOVED),
                    textPublisher = { _, _, _, _ -> error("must not publish") },
                )

            appState.sendConversationText(controller, "kept draft") { accepted += 1 }

            assertEquals(0, accepted)
            assertTrue(controller.timeline.isEmpty())
            assertEquals("kept draft", appState.draftFor(ACCOUNT_REF, GROUP_ID))
        }

    private var builtController: ConversationController? = null

    private fun controller(): ConversationController = requireNotNull(builtController)

    private fun controller(
        appState: WhiteNoiseAppState = testAppState(),
        markdownParser: suspend (String) -> MarkdownDocumentFfi = {
            MarkdownDocumentFfi(truncated = false, blocks = emptyList(), blankLinesBefore = ByteArray(0))
        },
        textPublisher: suspend (String?, String, String, String) -> SendSummaryFfi,
    ): ConversationController =
        ConversationController(
            appState = appState,
            initialGroup = group(),
            initialMemberSnapshot = memberSnapshot(),
            textPublisher = textPublisher,
            markdownParser = markdownParser,
        ).also { builtController = it }

    private fun styledDocument() =
        MarkdownDocumentFfi(
            truncated = false,
            blocks = listOf(MarkdownBlockFfi.Paragraph(inlines = emptyList())),
            blankLinesBefore = ByteArray(1),
        )

    private fun sentSummary(messageId: String = CONFIRMED_MESSAGE_ID) =
        SendSummaryFfi(
            published = 1u,
            messageIds = listOf(messageId),
            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
        )

    private fun testAppState(): WhiteNoiseAppState =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(sendTestDraftPersistence()),
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

    private fun group(selfMembership: SelfMembershipFfi = SelfMembershipFfi.MEMBER) =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Send group",
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
            selfMembership = selfMembership,
            leaveRequestPending = false,
            leaveRequestedAtMs = null,
            disbanding = false,
            disbandRequest = null,
            disbanded = false,
            welcomerAccountIdHex = null,
            viaWelcomeMessageIdHex = null,
        )

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val CONFIRMED_MESSAGE_ID = "c3".repeat(32)
    }
}

private fun sendTestDraftPersistence(): DraftPersistence =
    object : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }
