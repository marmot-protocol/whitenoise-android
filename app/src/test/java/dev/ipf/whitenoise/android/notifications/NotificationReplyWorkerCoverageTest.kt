package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NotificationReplyWorkerCoverageTest {
    @Test
    fun workerUsesInvocationSpecificRecoveryBoundary() {
        val body = notificationReplyWorkerSource().readText().functionBody("doWork")

        assertTrue("worker should use its stable request id as the durable completion key", "val completionKey = notificationReplyCompletionKey(id)" in body)
        assertTrue("worker should skip re-sending a completed reply", "completionStore.isCompleted(completionKey)" in body)
        assertTrue(
            "worker should load the recovery boundary persisted for this WorkRequest",
            "completionStore.startedRecoveryBoundary(completionKey)" in body,
        )
        assertTrue(
            "fresh attempts should persist their boundary while holding the send lock",
            "completionStore.markStarted(completionKey, boundary)" in body,
        )
        assertTrue("successful sends should persist the completion marker before returning", "completionStore.markCompleted(completionKey)" in body)
    }

    @Test
    fun appStateDedupeProbeSearchesSentTimelineRowsAfterInvocationBoundary() {
        val body = appStateSource().readText().functionBody("notificationReplyAlreadyCommitted")

        assertTrue("probe should scope the query to the invocation boundary", "afterMessageId = recoveryBoundary.messageIdHex" in body)
        assertTrue("probe should include the paired timeline cursor", "after = recoveryBoundary.timelineAt" in body)
        assertTrue("probe should only accept local sent rows", "record.direction.equals(\"sent\", ignoreCase = true)" in body)
        assertTrue("probe should match the committed reply body", "record.plaintext.trim() == body" in body)
    }

    @Test
    fun notificationReplySendPersistsLatestTimelineBoundaryBeforeSending() {
        val body = appStateSource().readText().functionBody("sendNotificationReply")

        assertTrue("boundary capture and send should share the group commit lock", "withGroupCommitLock(account, group)" in body)
        assertTrue("the newest row should become the per-invocation boundary", ".messages.lastOrNull()" in body)
        val persistIndex = body.indexOf("persistRecoveryBoundary(recoveryBoundary)")
        val sendIndex = body.indexOf("sendText(account, group, body)")
        assertTrue("the boundary persistence call should exist", persistIndex >= 0)
        assertTrue("the send call should exist", sendIndex >= 0)
        assertTrue("the boundary must be durable before sendText can commit", persistIndex < sendIndex)
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
