package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Waiting automatic work queued on a narrowed network widens in place when a receipt or the policy allows more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AutomaticAttachmentNetworkWaitsTest {
    private lateinit var context: Context
    private val waits = AutomaticAttachmentNetworkWaits(VolatileAttachmentInstallerHandoffRecordStore())

    /** Starts from a clean test WorkManager, whose constraints keep enqueued work waiting, and a volatile registry. */
    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AutomaticAttachmentNetworkWaits.installForTest(waits)
    }

    /** Restores the process registry so later tests never see this one. */
    @After
    fun tearDown() {
        AutomaticAttachmentNetworkWaits.installForTest(null)
    }

    /** A duplicate receipt asking for a wider network widens the waiting spec in place, keeping its id. */
    @Test
    fun duplicateReceiptWidensTheWaitingSpecInPlace() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        val queued = single()
        assertEquals(NetworkType.UNMETERED, queued.constraints.requiredNetworkType)

        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.NOT_ROAMING, type = IMAGE)

        val widened = single()
        assertEquals(queued.id, widened.id)
        assertEquals(WorkInfo.State.ENQUEUED, widened.state)
        assertEquals(NetworkType.NOT_ROAMING, widened.constraints.requiredNetworkType)
    }

    /** A duplicate receipt never narrows a spec: a narrower request leaves the wider one waiting. */
    @Test
    fun duplicateReceiptNeverNarrowsTheWaitingSpec() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.CONNECTED, type = IMAGE)

        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)

        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
    }

    /** A policy that now allows a wider network widens the recorded spec without any new receipt. */
    @Test
    fun widerPolicyWidensTheRecordedSpecWithoutANewReceipt() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        val queued = single()
        assertEquals(listOf(NetworkType.UNMETERED), waits.waiting(ACCOUNT).map { it.networkType })

        val widened = waits.widen(manager(), ACCOUNT) { NetworkType.NOT_ROAMING }

        assertEquals(1, widened)
        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.NOT_ROAMING, single().constraints.requiredNetworkType)
        assertEquals(listOf(NetworkType.NOT_ROAMING), waits.waiting(ACCOUNT).map { it.networkType })

        waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED }
        assertEquals(NetworkType.CONNECTED, single().constraints.requiredNetworkType)
        assertTrue("work on any network is no longer recorded", waits.waiting(ACCOUNT).isEmpty())
    }

    /** A not-roaming wait takes an unmetered policy or receipt, which admits the unmetered roaming it refused. */
    @Test
    fun notRoamingWaitTakesAnUnmeteredPolicyOrReceipt() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.NOT_ROAMING, type = IMAGE)
        val queued = single()

        assertEquals(1, waits.widen(manager(), ACCOUNT) { NetworkType.UNMETERED })
        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)

        waits.widen(manager(), ACCOUNT) { NetworkType.NOT_ROAMING }
        assertEquals(NetworkType.NOT_ROAMING, single().constraints.requiredNetworkType)
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)

        assertEquals(queued.id, single().id)
        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)
    }

    /** Another account's policy, and a policy that is no wider, leave the spec as it is. */
    @Test
    fun onlyAWiderPolicyForTheSameAccountWidens() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)

        assertEquals(0, waits.widen(manager(), "account-b") { NetworkType.CONNECTED })
        assertEquals(0, waits.widen(manager(), ACCOUNT) { NetworkType.UNMETERED })

        assertEquals(NetworkType.UNMETERED, single().constraints.requiredNetworkType)
    }

    /** Retired work is never widened or resurrected, and its record is pruned once nothing waits. */
    @Test
    fun retiredWorkIsNeverWidenedAndItsRecordIsPruned() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.UNMETERED, type = IMAGE)
        AttachmentDownloadWorker.retireWork(context, request)
        manager().cancelUniqueWork(attachmentDownloadWorkName(request)).result.get()

        assertEquals(0, waits.widen(manager(), ACCOUNT) { NetworkType.CONNECTED })

        assertTrue(infos().all { it.state.isFinished })
        assertTrue(waits.waiting(ACCOUNT).isEmpty())
    }

    /** The registry keeps narrowed waits only, so work queued on any network leaves nothing behind. */
    @Test
    fun workQueuedOnAnyNetworkIsNotRecorded() {
        AttachmentDownloadWorker.enqueue(context, request, networkType = NetworkType.CONNECTED, type = IMAGE)

        assertTrue(waits.waiting(ACCOUNT).isEmpty())
    }

    /** The test WorkManager. */
    private fun manager(): WorkManager = WorkManager.getInstance(context)

    /** Every spec under the request's unique name. */
    private fun infos(): List<WorkInfo> = manager().getWorkInfosForUniqueWork(attachmentDownloadWorkName(request)).get()

    /** The one spec under the request's unique name. */
    private fun single(): WorkInfo = infos().single()

    private companion object {
        const val ACCOUNT = "account-a"
        val IMAGE = MediaAutoDownloadType.Image
        val request =
            AttachmentTransferRequest(
                accountRef = ACCOUNT,
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
    }
}
