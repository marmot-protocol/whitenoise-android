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
import dev.ipf.marmotkit.MessageTagFfi
import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.marmotkit.TimelineMessageRecordFfi
import dev.ipf.marmotkit.TimelinePageFfi
import dev.ipf.marmotkit.TimelineReactionEmojiFfi
import dev.ipf.marmotkit.TimelineReactionSummaryFfi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Controller-level regression coverage for retention-policy transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "en")
class ConversationRetentionPolicyTransitionTest {
    /**
     * Exercises the real controller projection/filter boundary across enable,
     * expiry, restart, disable, and duration-widening transitions.
     */
    @Test
    fun policyEnableKeepsPrePolicyHistoryWhilePinnedRowsExpireAcrossRestartAndPolicyChanges() =
        runTest {
            var nowMillis = 1_000_000L
            val page = historyPage()
            val enabled = controller(retentionSeconds = 60uL) { nowMillis }

            enabled.load(page)
            assertEquals(PRE_POLICY_IDS + PINNED_ID, enabled.visibleIds())

            nowMillis = 1_060_000L
            enabled.load(page)
            assertEquals(PRE_POLICY_IDS, enabled.visibleIds())

            val disabledAfterRestart = controller(retentionSeconds = 0uL) { nowMillis }
            disabledAfterRestart.load(page)
            assertEquals(PRE_POLICY_IDS, disabledAfterRestart.visibleIds())

            val widenedAfterRestart = controller(retentionSeconds = 3_600uL) { nowMillis }
            widenedAfterRestart.load(page)
            assertEquals(PRE_POLICY_IDS, widenedAfterRestart.visibleIds())
        }

    /** Creates an isolated controller whose wall clock the test can advance. */
    private fun controller(
        retentionSeconds: ULong,
        clockMillis: () -> Long,
    ): ConversationController =
        ConversationController(
            appState = appState(),
            initialGroup = group(retentionSeconds),
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
            clockMillis = clockMillis,
        )

    /** Replaces the loaded window through the production refresh path. */
    private suspend fun ConversationController.load(page: TimelinePageFfi) {
        testRefreshCurrentTimeline(ACCOUNT_REF) { page }
    }

    /** Returns the exact projected rows that survived local expiry filtering. */
    private fun ConversationController.visibleIds(): Set<String> {
        val visibleIds = linkedSetOf<String>()
        timeline.mapTo(visibleIds) { it.record.messageIdHex }
        return visibleIds
    }

    /**
     * Models pre-policy text, media, reply, and reaction history plus one row
     * with an authoritative source-epoch deadline.
     */
    private fun historyPage(): TimelinePageFfi =
        TimelinePageFfi(
            messages =
                listOf(
                    record(
                        id = OLD_TEXT_ID,
                        plaintext = "retained text",
                        reactions =
                            TimelineReactionSummaryFfi(
                                byEmoji = listOf(TimelineReactionEmojiFfi("👍", 1u, listOf(ACCOUNT_ID))),
                                userReactions = emptyList(),
                            ),
                    ),
                    record(
                        id = OLD_MEDIA_ID,
                        plaintext = "retained media",
                        tags = listOf(MessageTagFfi(listOf("imeta", "m image/png", "name retained.png"))),
                    ),
                    record(
                        id = OLD_REPLY_ID,
                        plaintext = "retained reply",
                        replyToMessageIdHex = OLD_TEXT_ID,
                        retentionSeconds = 60uL,
                    ),
                    record(
                        id = PINNED_ID,
                        plaintext = "expires",
                        retentionSeconds = 60uL,
                        retentionExpiresAt = 1_060uL,
                    ),
                ),
            hasMoreBefore = false,
            hasMoreAfter = false,
        )

    /** Builds one deterministic MDK timeline projection for the integration page. */
    private fun record(
        id: String,
        plaintext: String,
        tags: List<MessageTagFfi> = emptyList(),
        replyToMessageIdHex: String? = null,
        reactions: TimelineReactionSummaryFfi = TimelineReactionSummaryFfi(emptyList(), emptyList()),
        retentionSeconds: ULong? = null,
        retentionExpiresAt: ULong? = null,
    ): TimelineMessageRecordFfi =
        TimelineMessageRecordFfi(
            messageIdHex = id,
            sourceMessageIdHex = id,
            direction = "sent",
            groupIdHex = GROUP_ID,
            sender = ACCOUNT_ID,
            plaintext = plaintext,
            contentTokens =
                MarkdownDocumentFfi(
                    truncated = false,
                    blocks = emptyList(),
                    blankLinesBefore = ByteArray(0),
                ),
            kind = 9uL,
            tags = tags,
            timelineAt = if (id == PINNED_ID) 1_000uL else 1uL,
            receivedAt = 1uL,
            replyToMessageIdHex = replyToMessageIdHex,
            replyPreview = null,
            mediaJson = null,
            media = emptyList(),
            agentTextStreamJson = null,
            groupSystem = null,
            reactions = reactions,
            deleted = false,
            deletedByMessageIdHex = null,
            invalidationStatus = null,
            sourceEpoch = null,
            retentionSeconds = retentionSeconds,
            retentionExpiresAt = retentionExpiresAt,
        )

    /** Supplies the minimum account-owned state required by the controller. */
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

    /** Creates the same conversation under a selected current group policy. */
    private fun group(retentionSeconds: ULong) =
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
                        listOf(AppBlobEndpointFfi("blossom-v1", "https://blossom.example")),
                ),
            disappearingMessageSecs = retentionSeconds,
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

    /** Keeps unrelated draft persistence deterministic and side-effect free. */
    private class TestDraftPersistence : DraftPersistence {
        /** Returns an empty initial draft snapshot. */
        override fun read(): Map<String, String> = emptyMap()

        /** Ignores draft writes because this suite never exercises the composer. */
        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    private companion object {
        const val ACCOUNT_REF = "alice"
        val ACCOUNT_ID = "a1".repeat(32)
        val GROUP_ID = "b2".repeat(32)
        val OLD_TEXT_ID = "c3".repeat(32)
        val OLD_MEDIA_ID = "d4".repeat(32)
        val OLD_REPLY_ID = "e5".repeat(32)
        val PINNED_ID = "f6".repeat(32)
        val PRE_POLICY_IDS = linkedSetOf(OLD_TEXT_ID, OLD_MEDIA_ID, OLD_REPLY_ID)
    }
}
