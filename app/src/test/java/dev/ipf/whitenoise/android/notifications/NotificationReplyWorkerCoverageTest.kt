package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationReplyWorkerCoverageTest {
    @Test
    fun workerShortCircuitsCompletedOrAlreadyCommittedReplies() {
        val body = notificationReplyWorkerSource().readText().functionBody("doWork")

        assertTrue("worker should use its stable request id as the durable completion key", "val completionKey = notificationReplyCompletionKey(id)" in body)
        assertTrue("worker should skip re-sending a completed reply", "completionStore.isCompleted(completionKey)" in body)
        assertTrue(
            "worker should only probe committed timeline replies after an earlier persisted send attempt",
            "completionStore.hasStarted(completionKey) && alreadyCommitted(application, action, reply)" in body,
        )
        assertTrue("fresh attempts should persist the started marker immediately before sending", "completionStore.markStarted(completionKey)" in body)
        assertTrue("successful sends should persist the completion marker before returning", "completionStore.markCompleted(completionKey)" in body)
    }

    @Test
    fun appStateDedupeProbeSearchesSentTimelineRowsAfterTargetMessage() {
        val body = appStateSource().readText().functionBody("notificationReplyAlreadyCommitted")

        assertTrue("probe should scope the query to the notification target", "afterMessageId = afterMessage" in body)
        assertTrue("probe should only accept local sent rows", "record.direction.equals(\"sent\", ignoreCase = true)" in body)
        assertTrue("probe should match the committed reply body", "record.plaintext.trim() == body" in body)
    }

    private fun notificationReplyWorkerSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/notifications/NotificationReplyWorker.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/notifications/NotificationReplyWorker.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing NotificationReplyWorker.kt source file")

    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull { it.exists() }
            ?: error("Missing AppState.kt source file")
}
