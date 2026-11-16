package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original invalidation must never bypass the paired persistent-derivative boundary. */
class DisplayThumbnailInvalidationCoverageTest {
    /** Scans every production package, so new cleanup call sites cannot silently omit derivatives. */
    @Test
    fun originalCleanupAlwaysUsesThePairedBoundary() {
        val root = appModuleFile("src/main/java/dev/ipf/whitenoise/android")
        val directRemoval = Regex("""\bdiskMediaCache\.(clear|remove|removeByCiphertextTags)\(""")
        root.walkTopDown().filter { it.extension == "kt" && it.name != "DisplayThumbnailRequest.kt" }.forEach {
            assertFalse(
                "Unpaired original-cache invalidation in ${it.name}",
                directRemoval.containsMatchIn(it.readText()),
            )
        }
        val boundary = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/DisplayThumbnailRequest.kt")
        listOf("remove", "removeByCiphertextTags", "clear").forEach { operation ->
            assertTrue("Derivative $operation missing", "displayThumbnails.$operation(" in boundary)
            assertTrue("Original $operation missing", "diskMediaCache.$operation(" in boundary)
        }
    }

    /** Guards both mutation/event entry points and the lifecycle paths that reconcile missed native cleanup. */
    @Test
    fun deletionAndBackgroundMaintenanceReachDerivativeCleanup() {
        val root = "src/main/java/dev/ipf/whitenoise/android/state/"
        val controller = appModuleSource(root + "ConversationController.kt")
        val appState = appModuleSource(root + "AppState.kt")
        val notificationContent = appModuleSource(root + "AppStateNotificationContent.kt")
        assertTrue(
            controller
                .substringAfter("internal suspend fun deleteMessageResult(")
                .substringBefore("internal fun")
                .contains("removeMessageDisplayThumbnails("),
        )
        assertTrue(
            notificationContent
                .substringAfter("private fun WhiteNoiseAppState.handleNotificationContentEvent(")
                .substringBefore("private suspend fun WhiteNoiseAppState.reconcileConversationNotificationCards(")
                .contains("removeMessageDisplayThumbnails("),
        )
        assertTrue(
            appState
                .substringAfter("suspend fun sweepExpiredDisappearingMessages()")
                .substringBefore("internal suspend fun runRetentionSweep(")
                .contains("scheduleDisplayThumbnailReconciliation().join()"),
        )
        assertTrue(appState.contains("bootstrapCompleted = true\n            scheduleDisplayThumbnailReconciliation()"))
        assertTrue(
            appState
                .substringAfter("fun setAppInForeground(")
                .substringBefore("fun ")
                .contains("scheduleForegroundDisplayThumbnailReconciliation()"),
        )
    }

    /** A decoder may still read an original until its removal finishes, so derivatives must be fenced last. */
    @Test
    fun originalCleanupCompletesBeforeTheDerivativeFence() {
        val source = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/DisplayThumbnailRequest.kt")
        val boundaries =
            mapOf(
                "removeMediaDiskCacheEntry" to "remove(key)",
                "removeMediaDiskCacheTags" to "removeByCiphertextTags(tags)",
                "clearMediaDiskCaches" to "clear()",
            )
        boundaries.forEach { (function, operation) ->
            val body = source.substringAfter("fun WhiteNoiseAppState.$function(").substringBefore("\n}")
            val original = Regex.escape("diskMediaCache.$operation")
            val derivative = Regex.escape("displayThumbnails.$operation")
            assertTrue(
                "$function must remove originals before its final derivative invalidation",
                Regex("try\\s*\\{\\s*$original\\s*}\\s*finally\\s*\\{\\s*$derivative").containsMatchIn(body),
            )
        }
    }
}
