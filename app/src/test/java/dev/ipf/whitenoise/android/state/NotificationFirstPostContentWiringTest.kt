package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static ownership checks guarding the AppState integration boundaries for issue #2453. */
class NotificationFirstPostContentWiringTest {
    /** Keeps title, sender, Markdown, system text, and media under one coordinator call. */
    @Test
    fun firstPostUsesOneCoordinatorForTheCompleteLocalContentValue() {
        val source = appStateSource().readText()
        val process = source.functionBody("processNotificationUpdate")
        val resolve = source.functionBody("resolveNotificationFirstPost")
        val coordinator =
            source
                .substringAfter("private val notificationFirstPostContentCoordinator")
                .substringBefore("private val notificationContentResolution")
        val show =
            source
                .substringAfter("private suspend fun showInitialNotificationUpdate")
                .substringBefore("private suspend fun showRedactedNotificationUpdate")

        assertTrue(
            "the first draw must put the complete local projection under one deadline",
            "resolveNotificationFirstPost(" in process &&
                "SystemClock::elapsedRealtime" in coordinator &&
                "val stage = notificationFirstPostContentCoordinator.startStage()" in resolve &&
                "notificationFirstPostContentCoordinator.resolve(stage)" in resolve &&
                "stageStartedAtElapsedMs = stage.startedAtElapsedMillis" in resolve &&
                "notificationContentResolution.firstPost.resolve(update, localOnly = true)" in resolve &&
                process.indexOf("resolveNotificationFirstPost(") <
                process.indexOf("postInitialNotificationUpdate(update, firstPost, receivedAtElapsedMs)"),
        )
        assertTrue(
            "every resolved field must reach the initial presenter call",
            listOf(
                "conversationTitleOverride = firstPost.content?.conversationTitle",
                "senderNameOverride = firstPost.content?.senderName",
                "previewTextOverride = firstPost.content?.previewText",
                "reactedToPreviewOverride = firstPost.content?.reactedToPreview",
                "recipientAccountSubtext = firstPost.content?.recipientAccountSubtext",
            ).all(show::contains),
        )
    }

    /** Requires content correction to consume the shared permit before avatar work. */
    @Test
    fun lateContentCorrectionRunsBeforeAvatarWorkAndKeepsOneWrite() {
        val source = appStateSource().readText()
        val enrichment =
            source
                .substringAfter("private suspend fun enrichPostedNotificationUpdate")
                .substringBefore("private suspend fun enrichResolvedNotificationUpdate")
        val routing =
            source
                .substringAfter("private suspend fun enrichResolvedNotificationUpdate")
                .substringBefore("private suspend fun postNotificationContentCorrection")
        val contentCorrection = source.functionBody("postNotificationContentCorrection")
        val avatarCorrection = source.functionBody("postNotificationAvatarCorrection")
        val firstResolve =
            enrichment.indexOf("notificationContentResolution.firstPost.resolve(update, localOnly = false)")
        val avatarPreWarm =
            contentCorrection.indexOf("notificationAvatarCoordinator.preWarm(update, firstPost.engineMuted)")

        assertTrue("late text must resolve before routing correction work", firstResolve >= 0)
        assertTrue(
            "a changed-content correction must post before prewarming future avatars",
            contentCorrection.indexOf("postNotificationLateCorrection(update, firstPost, content)") in
                0 until avatarPreWarm,
        )
        assertTrue(
            "the correction plan must choose content over an avatar rewrite",
            "notificationLateCorrectionPlan(" in routing &&
                "NotificationLateCorrectionPlan.Content" in routing &&
                "postNotificationContentCorrection" in routing &&
                "postNotificationAvatarCorrection" in routing,
        )
        assertTrue(
            "the post-deadline content resolver must run even after a fast local first draw",
            "firstPost.content ?:" !in enrichment &&
                "notificationContentResolution.firstPost.resolve(update, localOnly = false)" in enrichment,
        )
        assertTrue(
            "avatar lookup must remain behind the content-routing decision",
            "notificationAvatarCoordinator.preWarm(update, firstPost.engineMuted)" in avatarCorrection,
        )
    }

    /** Ensures every late write carries lifecycle and account-cache generations. */
    @Test
    fun notificationWritesCarryBothLifecycleAndAccountCacheGenerations() {
        val source = appStateSource().readText()
        val process = source.functionBody("processNotificationUpdate")
        val resolve = source.functionBody("resolveNotificationFirstPost")
        val eligibility = source.functionBody("isNotificationGenerationPostAllowed")

        assertTrue(
            "typed receipt must capture both invalidation lifetimes before local resolution",
            "val postEpoch = notificationPostEpoch.capture()" in process &&
                "val accountCacheEpoch = profileCacheLifetime.capture()" in process &&
                process.indexOf("val accountCacheEpoch = profileCacheLifetime.capture()") <
                process.indexOf("resolveNotificationFirstPost(") &&
                "accountCacheEpoch = accountCacheEpoch" in resolve,
        )
        assertTrue(
            "every initial and late write must reject an account-cache lifetime change",
            "notificationPostEpoch.isCurrent(postEpoch)" in eligibility &&
                "profileCacheLifetime.isCurrent(accountCacheEpoch)" in eligibility,
        )
    }

    /** Keeps invite identity refresh inside the same correction and staleness fences. */
    @Test
    fun inviteProfileRefreshSharesThePostCorrectionPermitAndGenerationFences() {
        val source = appStateSource().readText()
        val initialPost = source.functionBody("rememberPostedGroupInvite")
        val inviteRefresh = source.functionBody("refreshInviteNotificationIdentity")
        val invitePost = source.functionBody("postInviteNotificationIdentityCorrection")

        assertTrue(
            "the invite store must retain the same correction permit and post generations",
            "lateCorrectionPermit = firstPost.lateCorrectionPermit" in initialPost &&
                "postEpoch = firstPost.epoch" in initialPost &&
                "accountCacheEpoch = firstPost.accountCacheEpoch" in initialPost,
        )
        assertTrue(
            "profile-driven invite correction must await the shared slot and re-check both generations",
            "candidate.lateCorrectionPermit.acquire()" in inviteRefresh &&
                "postEpoch = candidate.postEpoch" in invitePost &&
                "accountCacheEpoch = candidate.accountCacheEpoch" in invitePost &&
                "isNotificationEnrichmentAllowed(" in inviteRefresh,
        )
    }

    /** Requires a proven cached Bitmap to cross the presenter boundary intact. */
    @Test
    fun readyAvatarCorrectionCarriesTheProvenBitmapsIntoThePresenter() {
        val enrichment = appStateSource().readText().functionBody("postNotificationAvatarCorrection")
        val latePost = appStateSource().readText().functionBody("postNotificationLateCorrection")

        assertTrue(
            "avatar readiness must depend on decoded bitmaps",
            "hasReadyAvatar = senderAvatarBitmap != null" in enrichment,
        )
        assertTrue(
            "the exact ready bitmaps must cross the final presenter boundary",
            "conversationAvatarBitmap = conversationAvatarBitmap" in latePost &&
                "senderAvatarBitmap = senderAvatarBitmap" in latePost,
        )
    }

    /** Locates production AppState source from Gradle's module working directory. */
    private fun appStateSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/AppState.kt"),
        ).firstOrNull(File::exists)
            ?: error("Missing AppState.kt source file")
}
