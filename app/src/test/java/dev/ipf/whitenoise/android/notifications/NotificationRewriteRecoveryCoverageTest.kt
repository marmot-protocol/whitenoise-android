package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rich-post failure's clean retry never requires the card its own recovery just cancelled (#3712). */
class NotificationRewriteRecoveryCoverageTest {
    /** Both recovery retries that follow an owned cancellation post without requiring a live card. */
    @Test
    fun ownedRecoveryRetriesDoNotRequireALiveCard() {
        val presenter =
            appModuleSource("src/main/java/dev/ipf/whitenoise/android/notifications/LocalNotificationPresenter.kt")
        val historyRetry =
            presenter
                .substringAfter("val cleanNotification = buildSettled(true)")
                .substringBefore("finalPostAllowed")
        assertTrue("the message-history retry owns its cancellation", "mustBeLive = false" in historyRetry)
        val reactionRetry =
            presenter
                .substringAfter("if (postResult == NotificationCardWriteResult.FAILED && reactionInlineArtwork) {")
                .substringBefore("finalPostAllowed")
        assertTrue("the reaction retry owns its cancellation", "mustBeLive = false" in reactionRetry)
    }
}
