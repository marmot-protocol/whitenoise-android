package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentRoleFfi
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionKindFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Receipt-side scheduling for every attachment type (#3308): an incoming message queues durable automatic
 * downloads for each slot its receiving account's policy allows on the live network, from the notification
 * pipeline and with the conversation never opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingAttachmentAutoDownloadTest {
    private lateinit var context: Application

    /** Each test observes real WorkManager enqueues through the test driver; constraints keep work queued. */
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    /** Images, video, audio and files each queue their own durable work, and no history scan is made. */
    @Test
    fun everyEnabledTypeIsQueuedFromTheReceiptWithoutAWholeGroupScan() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.runWithMainLooperPumping { fixture.appState.processNotificationUpdateForTest(fixture.update) }

                val expected = targetSlots().map { request(it.index) }
                assertTrue("queued=${expected.map(::queued)}", awaitQueued(expected))
                assertEquals(0, queued(request(0, message = OTHER_MESSAGE)))
                assertEquals("receipt scanned the whole group", 0, fixture.listMediaCalls.get())
                assertTrue(fixture.attachmentHistoryPageCalls.get() <= RECEIPT_ATTACHMENT_PAGE_BUDGET)
            } finally {
                fixture.close()
            }
        }

    /** A muted conversation keeps its notification quiet but still follows the account's media policy. */
    @Test
    fun mutedConversationStillFollowsMediaPolicy() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), muted = true)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.runWithMainLooperPumping { fixture.appState.processNotificationUpdateForTest(fixture.update) }

                assertTrue(awaitQueued(targetSlots().map { request(it.index) }))
            } finally {
                fixture.close()
            }
        }

    /** Repeated receipts of one message join the same durable identity instead of stacking work. */
    @Test
    fun duplicateReceiptsJoinOneDurableIdentity() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                repeat(3) {
                    fixture.runWithMainLooperPumping {
                        fixture.appState.processNotificationUpdateForTest(fixture.update)
                    }
                }
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                val expected = targetSlots().map { request(it.index) }
                assertTrue(awaitQueued(expected))
                expected.forEach { assertEquals(1, workInfos(it).size) }
            } finally {
                fixture.close()
            }
        }

    /** The receiving account's own policy applies even while another account is active. */
    @Test
    fun receivingAccountPolicyAppliesWhenItIsNotActive() =
        runBlocking {
            persistMatrix(ACCOUNT_A, MediaAutoDownloadMatrix(emptySet()))
            persistMatrix(
                ACCOUNT_B,
                MediaAutoDownloadMatrix(emptySet()).withToggle(
                    MediaAutoDownloadType.Document,
                    MediaAutoDownloadNetwork.WiFi,
                    on = true,
                ),
            )
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                val received = fixture.update.forAccount(ACCOUNT_B)
                fixture.runWithMainLooperPumping { fixture.appState.processNotificationUpdateForTest(received) }

                val document = request(FILE_INDEX, account = ACCOUNT_B)
                assertTrue(awaitQueued(listOf(document)))
                targetSlots()
                    .filter { it.index != FILE_INDEX }
                    .forEach { assertEquals(0, queued(request(it.index, account = ACCOUNT_B))) }
                targetSlots().forEach { assertEquals(0, queued(request(it.index))) }
            } finally {
                fixture.close()
            }
        }

    /** A type disabled for the live network, or turned off between receipts, queues nothing for that type. */
    @Test
    fun disabledTypesAndBlockedNetworksAreNotQueued() =
        runBlocking {
            persistMatrix(ACCOUNT_A, MediaAutoDownloadMatrix.DEFAULT)
            val fixture = fixture(history = mixedHistory() + mixedHistory(message = SECOND_MESSAGE))
            try {
                fixture.bootstrap()
                // Defaults on mobile data: images and audio only.
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.Mobile))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)
                assertEquals(1, queued(request(IMAGE_INDEX)))
                assertEquals(1, queued(request(AUDIO_INDEX)))
                assertEquals(0, queued(request(VIDEO_INDEX)))
                assertEquals(0, queued(request(FILE_INDEX)))

                // Turning images off is applied to the next receipt.
                persistMatrix(
                    ACCOUNT_A,
                    MediaAutoDownloadMatrix.DEFAULT.withToggle(
                        MediaAutoDownloadType.Image,
                        MediaAutoDownloadNetwork.Mobile,
                        on = false,
                    ),
                )
                val next = fixture.update.copy(notificationKey = "second", messageIdHex = SECOND_MESSAGE)
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(next)
                assertEquals(0, queued(request(IMAGE_INDEX, message = SECOND_MESSAGE)))
                assertEquals(1, queued(request(AUDIO_INDEX, message = SECOND_MESSAGE)))
            } finally {
                fixture.close()
            }
        }

    /** A stopped backlog, a signed-out account, or no validated network queue nothing and read no history. */
    @Test
    fun stoppedBacklogSignedOutAccountAndUnvalidatedNetworkQueueNothing() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            persistMatrix(ACCOUNT_B, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), signedOutB = true)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi), validated = false)
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update.forAccount(ACCOUNT_B))

                fixture.appState.stopAutomaticAttachmentDownloads()
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                targetSlots().forEach {
                    assertEquals(0, queued(request(it.index)))
                    assertEquals(0, queued(request(it.index, account = ACCOUNT_B)))
                }
                assertEquals(0, fixture.attachmentHistoryPageCalls.get())
            } finally {
                fixture.close()
            }
        }

    /** A message buried under more newer slots than the page budget covers is left to its bubbles. */
    @Test
    fun receiptLookupStaysWithinThePageBudget() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val newer =
                (0 until RECEIPT_ATTACHMENT_PAGE_LIMIT.toInt() * RECEIPT_ATTACHMENT_PAGE_BUDGET).map { index ->
                    entry("%064x".format(index + 1_000), 0, AttachmentCategoryFfi.IMAGE)
                }
            val fixture = fixture(history = newer + mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                assertEquals(RECEIPT_ATTACHMENT_PAGE_BUDGET, fixture.attachmentHistoryPageCalls.get())
                targetSlots().forEach { assertEquals(0, queued(request(it.index))) }
                assertEquals(0, fixture.listMediaCalls.get())
            } finally {
                fixture.close()
            }
        }

    /** Every MDK category maps to its own policy row, and only rejected slots map to none. */
    @Test
    fun everyCategoryMapsToItsOwnPolicyRow() {
        assertEquals(MediaAutoDownloadType.Image, AttachmentCategoryFfi.IMAGE.autoDownloadType())
        assertEquals(MediaAutoDownloadType.Video, AttachmentCategoryFfi.VIDEO.autoDownloadType())
        assertEquals(MediaAutoDownloadType.Audio, AttachmentCategoryFfi.AUDIO.autoDownloadType())
        assertEquals(MediaAutoDownloadType.Document, AttachmentCategoryFfi.FILE.autoDownloadType())
        assertNull(AttachmentCategoryFfi.REJECTED.autoDownloadType())
        val mapped = AttachmentCategoryFfi.entries.mapNotNull { it.autoDownloadType() }
        assertEquals(MediaAutoDownloadType.entries.toSet(), mapped.toSet())
    }

    /** The planner keeps only the message's accepted, encrypted slots, with their source identity. */
    @Test
    fun plannerKeepsOnlyTheMessagesAcceptedEncryptedSlots() {
        val history =
            mixedHistory() +
                listOf(
                    entry(MESSAGE, 7, AttachmentCategoryFfi.REJECTED),
                    entry(MESSAGE, 8, AttachmentCategoryFfi.IMAGE, sourceEpoch = 0uL),
                    entry(MESSAGE, 9, AttachmentCategoryFfi.IMAGE).copy(attachment = rejectedOutcome()),
                )
        val planned = incomingAttachments(ACCOUNT_A, GROUP, MESSAGE.uppercase(), history)

        assertEquals(targetSlots().map { request(it.index) }, planned.map { it.request })
        assertEquals(targetSlots().map { it.type }, planned.map { it.type })
        assertTrue(mixedHistory().passedMessage(MESSAGE))
        assertFalse(mixedHistory().take(targetSlots().size).passedMessage(MESSAGE))
        assertFalse(mixedHistory().passedMessage(SECOND_MESSAGE))
    }

    /** Builds the bootstrap fixture with two local accounts, either of which can be the receiver. */
    private fun fixture(
        history: List<AttachmentEntryFfi>,
        muted: Boolean = false,
        signedOutB: Boolean = false,
    ): NotificationBootstrapTestFixture {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        return NotificationBootstrapTestFixture(
            context = context,
            emitStartupNotification = false,
            accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B, signedOut = signedOutB)),
            messageIdHex = MESSAGE,
            groupIdHex = GROUP,
            attachmentHistory = history,
            onChatNotificationSettings = { accountRef, groupIdHex ->
                ChatNotificationSettingsFfi(accountRef, accountRef, groupIdHex, muted, null, 0L)
            },
        )
    }

    /** The target message's slots, one per policy row, in their original album order. */
    private fun targetSlots() =
        listOf(
            Slot(IMAGE_INDEX, AttachmentCategoryFfi.IMAGE, MediaAutoDownloadType.Image),
            Slot(VIDEO_INDEX, AttachmentCategoryFfi.VIDEO, MediaAutoDownloadType.Video),
            Slot(AUDIO_INDEX, AttachmentCategoryFfi.AUDIO, MediaAutoDownloadType.Audio),
            Slot(FILE_INDEX, AttachmentCategoryFfi.FILE, MediaAutoDownloadType.Document),
        )

    /** Newest-first history: [message]'s slots, then an older message's image. */
    private fun mixedHistory(message: String = MESSAGE): List<AttachmentEntryFfi> =
        targetSlots().map { entry(message, it.index, it.category) } +
            entry(OTHER_MESSAGE, 0, AttachmentCategoryFfi.IMAGE)

    /** One accepted history slot with a generated encrypted reference. */
    private fun entry(
        message: String,
        index: Int,
        category: AttachmentCategoryFfi,
        sourceEpoch: ULong = 1uL,
    ) = AttachmentEntryFfi(
        messageIdHex = message,
        sourceMessageIdHex = message,
        sender = "sender",
        timelineAt = 1uL,
        receivedAt = 1uL,
        sourceEpoch = sourceEpoch,
        category = category,
        role = AttachmentRoleFfi.SHARED,
        attachment = MediaAttachmentOutcomeFfi.Accepted(index.toUInt(), reference(index, sourceEpoch)),
    )

    /** A generated reference; only its encryption epoch matters to scheduling. */
    private fun reference(
        index: Int,
        sourceEpoch: ULong,
    ) = MediaAttachmentReferenceFfi(
        locators = emptyList(),
        ciphertextSha256 = index.toString(16).padStart(64, '0'),
        plaintextSha256 = (index + 32).toString(16).padStart(64, '0'),
        nonceHex = "00".repeat(12),
        fileName = "slot-$index.bin",
        mediaType = "application/octet-stream",
        version = EncryptedMediaVersionFfi.V1,
        sourceEpoch = sourceEpoch,
        dim = null,
        thumbhash = null,
    )

    /** A slot MDK's parser refused, which no automatic work may target. */
    private fun rejectedOutcome(): MediaAttachmentOutcomeFfi =
        MediaAttachmentOutcomeFfi.Rejected(
            9u,
            MediaAttachmentRejectionFfi(MediaAttachmentRejectionKindFfi.UNSUPPORTED_FORMAT, "unknown"),
        )

    /** The durable identity the receipt path queues for one slot. */
    private fun request(
        index: Int,
        account: String = ACCOUNT_A,
        message: String = MESSAGE,
    ) = AttachmentTransferRequest(account, GROUP, message, index, message)

    /** Non-finished work queued for [request]'s unique identity. */
    private fun queued(request: AttachmentTransferRequest): Int = workInfos(request).count { !it.state.isFinished }

    /** Every WorkManager row recorded under [request]'s unique identity. */
    private fun workInfos(request: AttachmentTransferRequest) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(attachmentDownloadWorkName(request)).get()

    /** Waits for asynchronous receipt scheduling to queue every one of [requests]. */
    private suspend fun awaitQueued(requests: List<AttachmentTransferRequest>): Boolean =
        withTimeoutOrNull(5_000L) {
            while (requests.any { queued(it) == 0 }) delay(10L)
            true
        } == true

    /** Seeds one account's automatic-download matrix before AppState reads it. */
    private fun persistMatrix(
        accountId: String,
        matrix: MediaAutoDownloadMatrix,
    ) {
        persistMediaAutoDownloadMatrix(
            context.getSharedPreferences("whitenoise", Context.MODE_PRIVATE),
            "media_auto_download_matrix:$accountId",
            matrix,
        )
    }

    /** Every type enabled on [network]. */
    private fun everyTypeOn(network: MediaAutoDownloadNetwork) =
        MediaAutoDownloadType.entries.fold(MediaAutoDownloadMatrix(emptySet())) { matrix, type ->
            matrix.withToggle(type, network, on = true)
        }

    /** Drives the live network inputs the platform callbacks would otherwise maintain. */
    private fun setNetwork(
        state: WhiteNoiseAppState,
        types: Set<MediaAutoDownloadNetwork>,
        validated: Boolean = true,
    ) {
        setField(state, "hasActiveNetworkSnapshot", true)
        setField(state, "activeNetworkTypesSnapshot", types)
        val tracker =
            WhiteNoiseAppState::class.java
                .getDeclaredField("validatedInternetNetworks")
                .apply { isAccessible = true }
                .get(state) as ValidatedInternetNetworkTracker
        tracker.update(1L, available = validated)
    }

    /** Writes one private AppState field. */
    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        WhiteNoiseAppState::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(target, value)
    }

    /** The same receipt, delivered to [account]. */
    private fun NotificationUpdateFfi.forAccount(account: String) =
        copy(
            notificationKey = "message:$account:$messageIdHex",
            accountRef = account,
            accountIdHex = account,
            receiver = NotificationUserFfi(account, account, null),
        )

    /** A local account summary that can receive notifications. */
    private fun account(
        label: String,
        signedOut: Boolean = false,
    ) = AccountSummaryFfi(
        label = label,
        accountIdHex = label,
        localSigning = true,
        externalSigning = false,
        signedOut = signedOut,
        running = !signedOut,
    )

    /** One target slot with its MDK category and the policy row it should land in. */
    private data class Slot(
        val index: Int,
        val category: AttachmentCategoryFfi,
        val type: MediaAutoDownloadType,
    )

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        val GROUP = "0a".repeat(16)
        val MESSAGE = "1b".repeat(32)
        val SECOND_MESSAGE = "3d".repeat(32)
        val OTHER_MESSAGE = "2c".repeat(32)
        const val IMAGE_INDEX = 0
        const val VIDEO_INDEX = 1
        const val AUDIO_INDEX = 2
        const val FILE_INDEX = 3
    }
}
