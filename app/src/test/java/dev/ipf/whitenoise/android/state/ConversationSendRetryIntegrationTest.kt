package dev.ipf.whitenoise.android.state

import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AppBlobEndpointFfi
import dev.ipf.marmotkit.AppGroupEncryptedMediaComponentFfi
import dev.ipf.marmotkit.AppGroupMemberRecordFfi
import dev.ipf.marmotkit.AppGroupRecordFfi
import dev.ipf.marmotkit.AppProtocolProfileFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotKitException
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendAcceptDispositionFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageChangeFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import dev.ipf.marmotkit.TimelineUpdateTriggerFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Integration boundary for optimistic send state plus the shared relay retry policy (#2016). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationSendRetryIntegrationTest {
    @Test
    fun optimisticRetentionHintSurvivesPendingToSentWithoutStartingTheCountdown() =
        runTest {
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(disappearingMessageSecs = 30uL),
                    initialMemberSnapshot =
                        GroupMemberSnapshot(
                            listOf(
                                AppGroupMemberRecordFfi(
                                    memberIdHex = ACCOUNT_ID,
                                    account = ACCOUNT_REF,
                                    local = true,
                                ),
                            ),
                        ),
                    textPublisher = { _, _, _, _ ->
                        val pending = controller.timeline.single()
                        assertEquals(MessageStatus.Pending, pending.status)
                        assertEquals(30uL, pending.retentionAtSendSeconds)
                        assertEquals(null, pending.record.retentionSeconds)
                        assertEquals(null, pending.record.retentionExpiresAt)
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello")

            val sent = controller.timeline.single()
            assertEquals(MessageStatus.Sent, sent.status)
            assertEquals(30uL, sent.retentionAtSendSeconds)
            assertEquals(null, sent.record.retentionSeconds)
            assertEquals(null, sent.record.retentionExpiresAt)
        }

    @Test
    fun retentionHintSurvivesProjectionAndPageRefreshWhileExpiryIsPending() =
        runTest {
            val recordedAt = (System.currentTimeMillis() / 1_000L).toULong()
            val waitingProjection =
                projectedMessage(
                    recordedAt = recordedAt,
                    retentionSeconds = null,
                    retentionExpiresAt = null,
                )
            lateinit var controller: ConversationController
            controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(disappearingMessageSecs = 30uL),
                    initialMemberSnapshot =
                        GroupMemberSnapshot(
                            listOf(
                                AppGroupMemberRecordFfi(
                                    memberIdHex = ACCOUNT_ID,
                                    account = ACCOUNT_REF,
                                    local = true,
                                ),
                            ),
                        ),
                    textPublisher = { _, _, _, _ ->
                        controller.testApplyLiveTimelineChangesAndRegisterStreams(
                            listOf(
                                TimelineMessageChangeFfi.Upsert(
                                    trigger = TimelineUpdateTriggerFfi.NEW_MESSAGE,
                                    message = waitingProjection,
                                ),
                            ),
                        )
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send("hello")
            assertEquals(30uL, controller.timeline.single().retentionAtSendSeconds)

            controller.testRefreshCurrentTimeline(ACCOUNT_REF) {
                TimelinePageFfi(
                    messages = listOf(waitingProjection),
                    hasMoreBefore = false,
                    hasMoreAfter = false,
                )
            }
            assertEquals(30uL, controller.timeline.single().retentionAtSendSeconds)
        }

    @Test
    fun sendRetriesConnectFailureThenCommitsOneSentTimelineRow() =
        runTest {
            var attempts = 0
            var accepted = 0
            val controller =
                ConversationController(
                    appState = appState(),
                    initialGroup = group(),
                    initialMemberSnapshot =
                        GroupMemberSnapshot(
                            listOf(
                                AppGroupMemberRecordFfi(
                                    memberIdHex = ACCOUNT_ID,
                                    account = ACCOUNT_REF,
                                    local = true,
                                ),
                            ),
                        ),
                    textPublisher = { replyTarget, account, groupIdHex, text ->
                        attempts += 1
                        assertEquals(null, replyTarget)
                        assertEquals(ACCOUNT_REF, account)
                        assertEquals(GROUP_ID, groupIdHex)
                        assertEquals("hello", text)
                        if (attempts == 1) {
                            throw MarmotKitException.Publish("connect relay failed")
                        }
                        SendSummaryFfi(
                            published = 1u,
                            messageIds = listOf(CONFIRMED_MESSAGE_ID),
                            acceptDisposition = SendAcceptDispositionFfi.PUBLISHED,
                            maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                        )
                    },
                )

            controller.send(" hello ") { accepted += 1 }

            assertEquals(2, attempts)
            assertEquals(1, accepted)
            assertEquals(listOf(CONFIRMED_MESSAGE_ID), controller.timeline.map { it.record.messageIdHex })
            assertEquals(MessageStatus.Sent, controller.timeline.single().status)
            assertEquals(
                "hello",
                controller.timeline
                    .single()
                    .record.plaintext,
            )
            assertFalse(
                controller.timeline.any {
                    it.status == MessageStatus.Pending || it.status == MessageStatus.Failed
                },
            )
        }

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

    private fun group(disappearingMessageSecs: ULong = 0uL) =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Retry group",
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
            disappearingMessageSecs = disappearingMessageSecs,
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

    private fun projectedMessage(
        recordedAt: ULong,
        retentionSeconds: ULong?,
        retentionExpiresAt: ULong?,
    ) = TimelineMessageRecordFfi(
        messageIdHex = CONFIRMED_MESSAGE_ID,
        sourceMessageIdHex = "d4".repeat(32),
        direction = "sent",
        groupIdHex = GROUP_ID,
        sender = ACCOUNT_ID,
        plaintext = "hello",
        contentTokens =
            MarkdownDocumentFfi(
                truncated = false,
                blocks = emptyList(),
                blankLinesBefore = ByteArray(0),
            ),
        kind = 9uL,
        tags = emptyList(),
        timelineAt = recordedAt,
        receivedAt = recordedAt,
        replyToMessageIdHex = null,
        replyPreview = null,
        mediaJson = null,
        media = emptyList(),
        agentTextStreamJson = null,
        groupSystem = null,
        reactions = TimelineReactionSummaryFfi(byEmoji = emptyList(), userReactions = emptyList()),
        deleted = false,
        deletedByMessageIdHex = null,
        invalidationStatus = null,
        sourceEpoch = null,
        retentionSeconds = retentionSeconds,
        retentionExpiresAt = retentionExpiresAt,
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
