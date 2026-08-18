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
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.SendMaintenanceDispositionFfi
import dev.ipf.marmotkit.SendSummaryFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class GroupSystemRetentionControllerTest {
    @Test
    fun groupSystemHistorySurvivesPublishSweepAndControllerRecreation() =
        runTest {
            val firstController = controller()
            firstController.load(groupSystemPage())
            assertTrue(firstController.timeline.any { it.record.messageIdHex == SYSTEM_MESSAGE_ID })

            firstController.send("anchor retention state")

            assertTrue(firstController.timeline.any { it.record.messageIdHex == SYSTEM_MESSAGE_ID })
            assertFalse(SYSTEM_MESSAGE_ID in firstController.testForegroundSweepExpiryMessageIds())
            assertTrue(CONFIRMED_MESSAGE_ID in firstController.testForegroundSweepExpiryMessageIds())

            val recreatedController = controller()
            recreatedController.load(groupSystemPage())

            assertTrue(recreatedController.timeline.any { it.record.messageIdHex == SYSTEM_MESSAGE_ID })
            assertFalse(SYSTEM_MESSAGE_ID in recreatedController.testForegroundSweepExpiryMessageIds())
        }

    private fun controller(): ConversationController =
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
            textPublisher = { _, _, _, _ ->
                SendSummaryFfi(
                    published = 1u,
                    messageIds = listOf(CONFIRMED_MESSAGE_ID),
                    maintenanceDisposition = SendMaintenanceDispositionFfi.READY,
                )
            },
        )

    private suspend fun ConversationController.load(page: TimelinePageFfi) {
        testRefreshCurrentTimeline(ACCOUNT_REF) { page }
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

    private fun group() =
        AppGroupRecordFfi(
            groupIdHex = GROUP_ID,
            protocolProfile = AppProtocolProfileFfi.LEGACY,
            endpoint = "wss://relay.example",
            profilePresent = true,
            name = "Retention group",
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
            disappearingMessageSecs = 60uL,
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

    private fun groupSystemPage() =
        TimelinePageFfi(
            messages = listOf(groupSystemRecord()),
            hasMoreBefore = false,
            hasMoreAfter = false,
        )

    private fun groupSystemRecord() =
        TimelineMessageRecordFfi(
            messageIdHex = SYSTEM_MESSAGE_ID,
            sourceMessageIdHex = SOURCE_MESSAGE_ID,
            direction = "received",
            groupIdHex = GROUP_ID,
            sender = ACCOUNT_ID,
            plaintext =
                """{"v":1,"system_type":"disappearing_timer_changed","data":""" +
                    """{"old_retention_seconds":0,"new_retention_seconds":60}}""",
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 1210uL,
            tags = emptyList(),
            timelineAt = 1uL,
            receivedAt = 1uL,
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
            retentionSeconds = null,
            retentionExpiresAt = null,
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
        val SYSTEM_MESSAGE_ID = "c3".repeat(32)
        val SOURCE_MESSAGE_ID = "d4".repeat(32)
        val CONFIRMED_MESSAGE_ID = "e5".repeat(32)
    }
}
