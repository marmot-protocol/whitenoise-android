package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Invariant gate for #3308: every supported attachment type enters the receipt scheduling path under its own
 * policy row, from the notification pipeline and without a whole-group scan, and durable automatic work
 * re-checks that policy when it runs.
 */
class IncomingAttachmentSchedulingCoverageTest {
    /** The receipt scheduler applies the receiving account's per-type policy and singles out no type. */
    @Test
    fun receiptSchedulingExcludesNoAttachmentType() {
        val body = appState().functionBody("scheduleIncomingAttachmentDownloads")

        listOf(
            "isImageMedia",
            "isVideoMedia",
            "isAudioMedia",
            "MediaAutoDownloadType.Image",
            "MediaAutoDownloadType.Video",
            "MediaAutoDownloadType.Audio",
            "MediaAutoDownloadType.Document",
        ).forEach { singled ->
            assertFalse("receipt scheduling must not single out one type ($singled)", singled in body)
        }
        assertTrue(
            "receipt scheduling must use the receiving account's live per-type policy",
            "automaticAttachmentPolicy(update.accountRef)" in body,
        )
        assertTrue(
            "receipt scheduling must read only the received message's slots",
            "receivedMessageAttachments(update.accountRef, update.groupIdHex, messageIdHex" in body,
        )
        assertTrue("receipt scheduling must queue durable work", "enqueueAttachmentDownload(" in body)
        assertTrue(
            "queued work must wait for a network its type's policy could allow",
            "networkType = policy.networkType(it.type)" in body,
        )
        assertFalse("receipt scheduling must not scan the whole group's media", "listMedia(" in body)
        assertFalse(
            "the planner must classify by MDK's category, not re-derive and drop types by MIME",
            "MediaReferenceSupport" in scheduling().functionBody("incomingAttachments"),
        )
    }

    /** Every MDK attachment category reaches exactly one policy row, and every row is reachable. */
    @Test
    fun everyCategoryReachesItsOwnPolicyRow() {
        val rows = AttachmentCategoryFfi.entries.mapNotNull { it.autoDownloadType() }

        assertEquals("a policy row has no category feeding it", MediaAutoDownloadType.entries.toSet(), rows.toSet())
        assertEquals("two categories share a policy row", rows.size, rows.toSet().size)
        assertEquals(1, AttachmentCategoryFfi.entries.count { it.autoDownloadType() == null })
    }

    /** Scheduling starts beside the first post and before mute, so muted chats still follow media policy. */
    @Test
    fun receiptSchedulingRunsBeforeNotificationEligibility() {
        val source = appState()
        val body = source.functionBody("processNotificationUpdate")
        val schedule = body.indexOf("scheduleIncomingAttachmentDownloadMaintenance(update)")

        assertTrue("receipt processing must schedule incoming attachments", schedule >= 0)
        val muteResolution = body.indexOf("engineNotificationMuted(update")
        val postEligibility = body.indexOf("shouldPostNotification(update")
        assertTrue("scheduling must not wait for mute resolution", schedule < muteResolution)
        assertTrue("scheduling must not wait for post eligibility", schedule < postEligibility)
        assertTrue(
            "scheduling must run off the posting path",
            "notificationScope.launch" in source.functionBody("scheduleIncomingAttachmentDownloadMaintenance"),
        )
    }

    /**
     * Durable automatic work re-checks the live policy and waits for the native approval before asking MDK, and
     * a lasting pause frees its download slot and goes back through admission.
     */
    @Test
    fun durableAutomaticWorkRechecksLivePolicy() {
        val source = appState()

        assertTrue(
            "durable automatic work must go through admission",
            "downloadAutomaticAttachment(" in source.functionBody("downloadAttachmentForDurableWork"),
        )
        val attempts = source.functionBody("downloadAutomaticAttachment")
        assertTrue(
            "every automatic attempt must be admitted against the live policy",
            "awaitAutomaticAttachmentAdmission(" in attempts,
        )
        assertTrue(
            "a lasting pause must end in re-admission, not hold a download slot",
            "NativeAttachmentPausedException" in attempts,
        )
        val admission = source.functionBody("awaitAutomaticAttachmentAdmission")
        assertTrue(
            "admission must use the live per-type policy",
            "automaticAttachmentPolicy(accountRef)" in admission,
        )
        assertTrue("admission must wait for the native approval", "awaitApplied(accountRef)" in admission)
        assertTrue(
            "durable observation must bound a pause",
            "NATIVE_AUTOMATIC_PAUSE_GRACE_MILLIS.takeIf" in source("state/NativeAttachmentTransfers.kt"),
        )
    }

    /** Reads AppState from either the module or the repository working directory. */
    private fun appState(): String = source("state/AppState.kt")

    /** Reads the receipt planner from either the module or the repository working directory. */
    private fun scheduling(): String = source("state/IncomingAttachmentScheduling.kt")

    /** Reads one production source file under the app package. */
    private fun source(path: String): String =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/$path"),
            File("app/src/main/java/dev/ipf/whitenoise/android/$path"),
        ).firstOrNull(File::exists)?.readText() ?: error("Missing $path")
}
