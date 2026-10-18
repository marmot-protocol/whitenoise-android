package dev.ipf.whitenoise.android.state

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentRoleFfi
import dev.ipf.marmotkit.ChatNotificationSettingsFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarkdownDocumentFfi
import dev.ipf.marmotkit.MarmotEventFfi
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionFfi
import dev.ipf.marmotkit.MediaAttachmentRejectionKindFfi
import dev.ipf.marmotkit.NotificationUpdateFfi
import dev.ipf.marmotkit.NotificationUserFfi
import dev.ipf.marmotkit.ReceivedMessageFfi
import dev.ipf.marmotkit.RuntimeMessageReceivedFfi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
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
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.atomic.AtomicInteger

/**
 * Receipt-side scheduling for every attachment type (#3308): an incoming message queues durable automatic
 * downloads for each slot its receiving account's policy allows on the live network, from the notification
 * pipeline and with the conversation never opened.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Both receipt sources share one notification fixture and its history helpers, so they stay in one class.
@Suppress("LargeClass")
class IncomingAttachmentAutoDownloadTest {
    private lateinit var context: Application

    /**
     * Each test observes real WorkManager enqueues through the test driver; constraints keep work queued. The
     * narrowed-network registry is volatile here, since the test JVM has no Android Keystore.
     */
    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AutomaticAttachmentNetworkWaits.installForTest(
            AutomaticAttachmentNetworkWaits(VolatileAttachmentInstallerHandoffRecordStore()),
        )
    }

    /** Restores the process registry so later tests never see this one. */
    @After
    fun tearDown() {
        AutomaticAttachmentNetworkWaits.installForTest(null)
    }

    /**
     * An image received on metered mobile under the default settings waits for an unmetered network. Turning on
     * metered image downloads widens that same queued work to a network the new policy allows, with no new receipt
     * and no Wi-Fi, and the worker's live admission then allows it on the metered network.
     */
    @Test
    fun widerPolicyWidensWaitingWorkWithoutANewReceipt() =
        runBlocking {
            persistMatrix(ACCOUNT_A, MediaAutoDownloadMatrix.DEFAULT)
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                val metered = setOf(MediaAutoDownloadNetwork.Mobile, MediaAutoDownloadNetwork.Metered)
                setNetwork(fixture.appState, metered)
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)
                val image = request(IMAGE_INDEX)
                assertTrue(awaitQueued(listOf(image)))
                val queued = workInfos(image).single { !it.state.isFinished }
                assertEquals(NetworkType.UNMETERED, queued.constraints.requiredNetworkType)
                val policy = { fixture.appState.automaticAttachmentPolicy(ACCOUNT_A) }
                assertEquals(AutomaticAttachmentHold.Network, policy().hold(MediaAutoDownloadType.Image))

                fixture.runWithMainLooperPumping {
                    fixture.appState.setMediaAutoDownload(
                        MediaAutoDownloadType.Image,
                        MediaAutoDownloadNetwork.Metered,
                        enabled = true,
                    )
                }

                // The sweep runs on the state's main-thread scope, so the wait keeps the main looper moving.
                assertTrue(
                    "constraint=${constraints(image).requiredNetworkType}",
                    fixture.runWithMainLooperPumping { awaitConstraint(image, NetworkType.NOT_ROAMING) },
                )
                val widened = workInfos(image).single { !it.state.isFinished }
                assertEquals("the same queued work widens", queued.id, widened.id)
                assertEquals(WorkInfo.State.ENQUEUED, widened.state)
                assertNull("admitted on the metered network", policy().hold(MediaAutoDownloadType.Image))
            } finally {
                fixture.close()
            }
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

    /**
     * A muted chat's media is scheduled although MDK sends no notification update for it: MarmotKit 0.13.0 drops
     * ordinary traffic in a muted chat from the notification stream, so the receipt arrives only as the runtime's
     * message event, which is all this test delivers.
     */
    @Test
    fun mutedChatMediaIsScheduledFromTheRuntimeEventWithoutANotification() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), muted = true)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.ensureNotificationRuntimeStarted()

                fixture.emitRuntimeEvent(receivedChatMessage())

                val expected = targetSlots().map { request(it.index) }
                assertTrue(
                    "queued=${expected.map(::queued)}",
                    fixture.runWithMainLooperPumping { awaitQueued(expected) },
                )
                assertEquals("no notification update was delivered", 0, fixture.consumedUpdateCount())
            } finally {
                fixture.close()
            }
        }

    /**
     * With the account's local notifications off, MDK delivers no notification update for any message, so an
     * unmuted chat's media is scheduled from the runtime event as a muted chat's is.
     */
    @Test
    fun notificationsOffMediaIsScheduledFromTheRuntimeEvent() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), muted = false)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.ensureNotificationRuntimeStarted()
                assertTrue("notifications turned off", fixture.appState.setLocalNotificationsEnabled(false))

                fixture.emitRuntimeEvent(receivedChatMessage())

                val expected = targetSlots().map { request(it.index) }
                assertTrue(
                    "queued=${expected.map(::queued)}",
                    fixture.runWithMainLooperPumping { awaitQueued(expected) },
                )
                assertEquals("no notification update was delivered", 0, fixture.consumedUpdateCount())
            } finally {
                fixture.close()
            }
        }

    /** A runtime event stream that fails to open is retried, so a later message in a muted chat is still scheduled. */
    @Test
    fun aFailedEventStreamOpenIsRetried() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), muted = true)
            try {
                fixture.eventOpenFailures.set(1)
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.ensureNotificationRuntimeStarted()

                fixture.emitRuntimeEvent(receivedChatMessage())

                val expected = targetSlots().map { request(it.index) }
                assertTrue(
                    "queued=${expected.map(::queued)}",
                    fixture.runWithMainLooperPumping { awaitQueued(expected) },
                )
                assertEquals("the failed open was consumed", 0, fixture.eventOpenFailures.get())
            } finally {
                fixture.close()
            }
        }

    /**
     * The receipt event subscription is opened before the runtime starts and is the one the receipt loop reads, so
     * a muted message delivered between start and the listener's subscriptions still reaches a receipt scheduler.
     */
    @Test
    fun theReceiptSubscriptionOpensBeforeTheRuntimeStarts() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), muted = true)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.ensureNotificationRuntimeStarted()

                fixture.emitRuntimeEvent(receivedChatMessage())
                val expected = targetSlots().map { request(it.index) }
                assertTrue(
                    "queued=${expected.map(::queued)}",
                    fixture.runWithMainLooperPumping { awaitQueued(expected) },
                )
                assertEquals("opened before start", true, fixture.firstEventsSubscriptionBeforeStart)
                assertEquals("the receipt loop reads that same subscription", 1, fixture.eventsSubscriptions.get())
            } finally {
                fixture.close()
            }
        }

    /**
     * A chat unmuted after MDK suppressed its message is still scheduled: the runtime event schedules every received
     * chat message, rather than inferring the earlier notification outcome from the settings as they are now.
     */
    @Test
    fun aMessageSuppressedBeforeAnUnmuteIsStillScheduled() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), muted = false)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.ensureNotificationRuntimeStarted()

                fixture.emitRuntimeEvent(receivedChatMessage())

                val expected = targetSlots().map { request(it.index) }
                assertTrue(
                    "queued=${expected.map(::queued)}",
                    fixture.runWithMainLooperPumping { awaitQueued(expected) },
                )
                assertEquals("no notification update was delivered", 0, fixture.consumedUpdateCount())
            } finally {
                fixture.close()
            }
        }

    /** A message both receipt sources report runs its bounded history lookup once, whichever source is first. */
    @Test
    fun bothReceiptSourcesLookUpAMessageOnce() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), muted = false)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.ensureNotificationRuntimeStarted()

                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)
                val pagesAfterNotification = fixture.attachmentHistoryPageCalls.get()
                fixture.emitRuntimeEvent(receivedChatMessage(fixture.update.messageIdHex!!))
                fixture.runWithMainLooperPumping { delay(300L) }

                assertTrue("the notification looked the message up", pagesAfterNotification > 0)
                assertEquals(
                    "the event did not look it up again",
                    pagesAfterNotification,
                    fixture.attachmentHistoryPageCalls.get(),
                )
                targetSlots().forEach { assertEquals(1, workInfos(request(it.index)).size) }
            } finally {
                fixture.close()
            }
        }

    /**
     * A receipt that held a type does not stand as settled: once the policy allows that type, a later receipt for
     * the same message looks it up again and queues the held slot, as the controlled fixture's replay does.
     */
    @Test
    fun aHeldTypeIsQueuedByALaterReceiptOnceThePolicyAllowsIt() =
        runBlocking {
            val imagesOnly =
                MediaAutoDownloadMatrix(emptySet()).withToggle(
                    MediaAutoDownloadType.Image,
                    MediaAutoDownloadNetwork.WiFi,
                    on = true,
                )
            persistMatrix(ACCOUNT_A, imagesOnly)
            val fixture = fixture(history = mixedHistory(), muted = false)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))

                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)
                assertEquals(1, queued(request(IMAGE_INDEX)))
                assertEquals("video held by policy", 0, queued(request(VIDEO_INDEX)))

                persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                assertEquals("the replayed receipt queues the held video", 1, queued(request(VIDEO_INDEX)))
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

    /**
     * A type no network enables queues nothing, a type the live network refuses still queues and waits for a
     * network it is allowed on, and a settings change applies to the next receipt.
     */
    @Test
    fun disabledTypesAreNotQueuedAndRefusedOnesWaitForTheirNetwork() =
        runBlocking {
            persistMatrix(ACCOUNT_A, MediaAutoDownloadMatrix.DEFAULT)
            val fixture = fixture(history = mixedHistory() + mixedHistory(message = SECOND_MESSAGE))
            try {
                fixture.bootstrap()
                // The defaults allow nothing on metered mobile data, and enable documents on no network at all.
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.Mobile, MediaAutoDownloadNetwork.Metered))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)
                val waiting = listOf(IMAGE_INDEX, VIDEO_INDEX, AUDIO_INDEX).map { request(it) }
                waiting.forEach {
                    assertEquals(1, queued(it))
                    assertEquals(NetworkType.UNMETERED, constraints(it).requiredNetworkType)
                }
                assertEquals(0, queued(request(FILE_INDEX)))

                // Turning images off everywhere is applied to the next receipt.
                persistMatrix(
                    ACCOUNT_A,
                    MediaAutoDownloadMatrix.DEFAULT
                        .withToggle(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.WiFi, on = false)
                        .withToggle(MediaAutoDownloadType.Image, MediaAutoDownloadNetwork.Mobile, on = false),
                )
                val next = fixture.update.copy(notificationKey = "second", messageIdHex = SECOND_MESSAGE)
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(next)
                assertEquals(0, queued(request(IMAGE_INDEX, message = SECOND_MESSAGE)))
                assertEquals(1, queued(request(AUDIO_INDEX, message = SECOND_MESSAGE)))
            } finally {
                fixture.close()
            }
        }

    /** Each slot's work runs only on the narrowest network its type's policy allows, and never on low storage. */
    @Test
    fun queuedWorkWaitsForTheNarrowestNetworkItsPolicyAllows() =
        runBlocking {
            val matrix =
                MediaAutoDownloadMatrix(
                    setOf(
                        MediaAutoDownloadType.Image to MediaAutoDownloadNetwork.WiFi,
                        MediaAutoDownloadType.Image to MediaAutoDownloadNetwork.Mobile,
                        MediaAutoDownloadType.Image to MediaAutoDownloadNetwork.Metered,
                        MediaAutoDownloadType.Video to MediaAutoDownloadNetwork.WiFi,
                        MediaAutoDownloadType.Document to MediaAutoDownloadNetwork.WiFi,
                    ) + MediaAutoDownloadNetwork.entries.map { MediaAutoDownloadType.Audio to it },
                )
            persistMatrix(ACCOUNT_A, matrix)
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                val expected =
                    mapOf(
                        IMAGE_INDEX to NetworkType.NOT_ROAMING,
                        VIDEO_INDEX to NetworkType.UNMETERED,
                        AUDIO_INDEX to NetworkType.CONNECTED,
                        FILE_INDEX to NetworkType.UNMETERED,
                    )
                expected.forEach { (index, network) ->
                    val constraints = constraints(request(index))
                    assertEquals("slot $index", network, constraints.requiredNetworkType)
                    assertTrue("slot $index", constraints.requiresStorageNotLow())
                }
            } finally {
                fixture.close()
            }
        }

    /** A stopped backlog or a signed-out account queues nothing and reads no history. */
    @Test
    fun stoppedBacklogAndSignedOutAccountQueueNothing() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            persistMatrix(ACCOUNT_B, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory(), signedOutB = true)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update.forAccount(ACCOUNT_B))

                fixture.appState.stopAutomaticAttachmentDownloads()
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                targetSlots().forEach {
                    assertEquals(0, queued(request(it.index)))
                    assertEquals(0, queued(request(it.index, account = ACCOUNT_B)))
                }
                assertEquals(0, fixture.attachmentHistoryPageCalls.get())
                val skipped = receiptLogs().filter { "attachment_receipt_skipped" in it }
                assertEquals(
                    listOf("reason=signed_out", "reason=paused"),
                    skipped.map { it.substringAfter("attachment_receipt_skipped ") },
                )
            } finally {
                fixture.close()
            }
        }

    /** A connection without validated internet still queues the message's media, to run once one validates. */
    @Test
    fun unvalidatedNetworkQueuesWorkThatWaits() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi), validated = false)
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                targetSlots().forEach { assertEquals(1, queued(request(it.index))) }
                assertTrue(receiptLogs().any { "queued=4 waiting=4 held=0" in it })
            } finally {
                fixture.close()
            }
        }

    /** A receipt that arrives before the account list loads queues every slot, and the worker decides later. */
    @Test
    fun unknownReceivingAccountQueuesEverySlotForTheWorker() =
        runBlocking {
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update.forAccount(ACCOUNT_C))

                targetSlots().forEach {
                    val request = request(it.index, account = ACCOUNT_C)
                    assertEquals(1, queued(request))
                    assertEquals(NetworkType.CONNECTED, constraints(request).requiredNetworkType)
                }
            } finally {
                fixture.close()
            }
        }

    /** A slot the reader cancelled is not queued again when the same message is received again. */
    @Test
    fun cancelledAttachmentIsNotQueuedAgain() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                intents(fixture.appState).suppressAutomatic(request(IMAGE_INDEX))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                assertEquals(0, queued(request(IMAGE_INDEX)))
                listOf(VIDEO_INDEX, AUDIO_INDEX, FILE_INDEX).forEach { assertEquals(1, queued(request(it))) }
            } finally {
                fixture.close()
            }
        }

    /** A text message in a group whose attachments were all received long before costs one page read. */
    @Test
    fun textReceiptReadsOnePage() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val older =
                (0 until RECEIPT_ATTACHMENT_PAGE_LIMIT.toInt() * 2).map { index ->
                    entry("%064x".format(index + 1_000), 0, AttachmentCategoryFfi.IMAGE)
                }
            val fixture = fixture(history = older)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                val text = fixture.update.copy(messageIdHex = TEXT_MESSAGE, timestampMs = DAY_LATER_MILLIS)
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(text)

                assertEquals(1, fixture.attachmentHistoryPageCalls.get())
                assertTrue("a text receipt logged scheduling", receiptLogs().isEmpty())
            } finally {
                fixture.close()
            }
        }

    /** A history change under the cursor restarts the read from the newest page once, without losing the budget. */
    @Test
    fun historyChangeRestartsTheReadOnce() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val newer =
                (0 until RECEIPT_ATTACHMENT_PAGE_LIMIT.toInt()).map { index ->
                    entry("%064x".format(index + 1_000), 0, AttachmentCategoryFfi.IMAGE)
                }
            val fixture = fixture(history = newer + mixedHistory(), historyRestarts = 1)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                targetSlots().forEach { assertEquals(1, queued(request(it.index))) }
                assertEquals(4, fixture.attachmentHistoryPageCalls.get())
                // The restarted read counts its own pages: the page read before the restart is not spent.
                assertTrue(receiptLogs().any { "pages=2 end=passed" in it })
            } finally {
                fixture.close()
            }
        }

    /**
     * The restarted read gets the whole page budget again: a target on its last permitted page is queued, even
     * though a page was already read before the restart.
     */
    @Test
    fun theRestartedReadReachesItsLastPermittedPage() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = newerPages(2) + mixedHistory(), historyRestarts = 1)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                targetSlots().forEach { assertEquals(1, queued(request(it.index))) }
                assertTrue(receiptLogs().any { "pages=3 end=passed" in it })
            } finally {
                fixture.close()
            }
        }

    /** The restarted read still stops at its budget: a target one page beyond it is not queued. */
    @Test
    fun theRestartedReadStopsAtItsBudget() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = newerPages(3) + mixedHistory(), historyRestarts = 1)
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                targetSlots().forEach { assertEquals(0, queued(request(it.index))) }
                assertTrue(receiptLogs().any { "pages=3 end=exhausted" in it })
            } finally {
                fixture.close()
            }
        }

    /** [count] full pages of newer image slots from other messages, ahead of the target in newest-first order. */
    private fun newerPages(count: Int): List<AttachmentEntryFfi> =
        (0 until RECEIPT_ATTACHMENT_PAGE_LIMIT.toInt() * count).map { index ->
            entry("%064x".format(index + 1_000), 0, AttachmentCategoryFfi.IMAGE)
        }

    /** Receipt logs carry counts and types only, never the account, group or message they describe. */
    @Test
    fun receiptLogsCarryNoAttachmentIdentity() =
        runBlocking {
            persistMatrix(ACCOUNT_A, everyTypeOn(MediaAutoDownloadNetwork.WiFi))
            val fixture = fixture(history = mixedHistory())
            try {
                fixture.bootstrap()
                setNetwork(fixture.appState, setOf(MediaAutoDownloadNetwork.WiFi))
                fixture.appState.scheduleIncomingAttachmentDownloadsForTest(fixture.update)

                val logs = receiptLogs()
                assertTrue(logs.toString(), logs.any { "attachment_receipt_scheduled queued=4 waiting=0 held=0" in it })
                logs.forEach { line ->
                    listOf(ACCOUNT_A, GROUP, MESSAGE, OTHER_MESSAGE).forEach { identity ->
                        assertFalse("log line leaked an identity: $line", identity in line)
                    }
                }
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
    }

    /** The read stops after the message's run, at the next page when the run ends on a boundary, or on a stale page. */
    @Test
    fun pageEndStopsAfterTheRunOrOnAStalePage() {
        val history = mixedHistory()
        val run = history.take(targetSlots().size)
        val older = history.drop(targetSlots().size)

        assertEquals(true to ReceiptLookupEnd.Passed, history.receiptPageEnd(MESSAGE, false, 0L))
        assertEquals(true to null, run.receiptPageEnd(MESSAGE, false, 0L))
        assertEquals(true to ReceiptLookupEnd.Passed, older.receiptPageEnd(MESSAGE, true, 0L))
        assertEquals(false to null, history.receiptPageEnd(SECOND_MESSAGE, false, 0L))
        assertEquals(false to ReceiptLookupEnd.Stale, history.receiptPageEnd(SECOND_MESSAGE, false, 2L))
        assertEquals("a seen run is never stale", true to null, run.receiptPageEnd(MESSAGE, false, 2L))
    }

    /**
     * Builds the bootstrap fixture with two local accounts, either of which can be the receiver; [onMuteRead] runs
     * on every chat mute read.
     */
    private fun fixture(
        history: List<AttachmentEntryFfi>,
        muted: Boolean = false,
        signedOutB: Boolean = false,
        historyRestarts: Int = 0,
        onMuteRead: () -> Unit = {},
        failSuppressionReads: () -> Boolean = { false },
        failNotificationSettingsReads: () -> Boolean = { false },
    ): NotificationBootstrapTestFixture {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        return NotificationBootstrapTestFixture(
            context = context,
            emitStartupNotification = false,
            accounts = listOf(account(ACCOUNT_A), account(ACCOUNT_B, signedOut = signedOutB)),
            messageIdHex = MESSAGE,
            groupIdHex = GROUP,
            attachmentHistory = history,
            attachmentHistoryRestarts = historyRestarts,
            onChatNotificationSettings = { accountRef, groupIdHex ->
                onMuteRead()
                check(!failSuppressionReads()) { "fixture mute read failed" }
                ChatNotificationSettingsFfi(accountRef, accountRef, groupIdHex, muted, null, 0L)
            },
            onNotificationSettings = {
                check(!failNotificationSettingsReads()) { "fixture notification settings read failed" }
                null
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

    /** The constraints of the one live work spec queued for [request]. */
    private fun constraints(request: AttachmentTransferRequest): Constraints {
        val live = workInfos(request).single { !it.state.isFinished }
        return live.constraints
    }

    /** Receipt scheduling's log lines, which must stay free of attachment identity. */
    private fun receiptLogs(): List<String> = ShadowLog.getLogsForTag("DMAttachmentReceipt").map { it.msg }

    /** The state's durable intent store, where a reader's cancel is recorded. */
    private fun intents(state: WhiteNoiseAppState): AttachmentDownloadIntentStore =
        WhiteNoiseAppState::class.java
            .getDeclaredField("attachmentDownloadIntents")
            .apply { isAccessible = true }
            .get(state) as AttachmentDownloadIntentStore

    /** Non-finished work queued for [request]'s unique identity. */
    private fun queued(request: AttachmentTransferRequest): Int = workInfos(request).count { !it.state.isFinished }

    /** Every WorkManager row recorded under [request]'s unique identity. */
    private fun workInfos(request: AttachmentTransferRequest) =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(attachmentDownloadWorkName(request)).get()

    /** The runtime event MDK emits for a chat message [message] from a peer in the target group. */
    private fun receivedChatMessage(message: String = MESSAGE) =
        MarmotEventFfi.MessageReceived(
            RuntimeMessageReceivedFfi(
                accountIdHex = ACCOUNT_A,
                accountLabel = ACCOUNT_A,
                message =
                    ReceivedMessageFfi(
                        messageIdHex = message,
                        groupIdHex = GROUP,
                        sender = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        senderDisplayName = null,
                        plaintext = "",
                        contentTokens = MarkdownDocumentFfi(emptyList(), false, ByteArray(0)),
                        kind = 9uL,
                        tags = emptyList(),
                        sourceEpoch = 1uL,
                        retentionSeconds = null,
                        retentionExpiresAt = null,
                        recordedAt = System.currentTimeMillis().toULong() / 1_000uL,
                        receivedAt = System.currentTimeMillis().toULong() / 1_000uL,
                    ),
            ),
        )

    /** Waits until [counter] reaches at least [atLeast]. */
    private suspend fun awaitCount(
        counter: AtomicInteger,
        atLeast: Int,
    ): Boolean =
        withTimeoutOrNull(5_000L) {
            while (counter.get() < atLeast) delay(10L)
            true
        } == true

    /** Waits for [request]'s live work to require [networkType]. */
    private suspend fun awaitConstraint(
        request: AttachmentTransferRequest,
        networkType: NetworkType,
    ): Boolean =
        withTimeoutOrNull(5_000L) {
            while (constraints(request).requiredNetworkType != networkType) delay(10L)
            true
        } == true

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

        // A receiving account the device has not loaded yet, as on a cold start.
        const val ACCOUNT_C = "account-c"
        val GROUP = "0a".repeat(16)
        val MESSAGE = "1b".repeat(32)
        val SECOND_MESSAGE = "3d".repeat(32)
        val OTHER_MESSAGE = "2c".repeat(32)
        val TEXT_MESSAGE = "4e".repeat(32)

        // A receipt a day after the history's slots were received, so every page of it is stale.
        const val DAY_LATER_MILLIS = 86_400_000L
        const val IMAGE_INDEX = 0
        const val VIDEO_INDEX = 1
        const val AUDIO_INDEX = 2
        const val FILE_INDEX = 3
    }
}
