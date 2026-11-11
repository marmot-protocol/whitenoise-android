package dev.ipf.whitenoise.android.notifications

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversation retirement never cancels a replacement written after its cutoff on a stale tray listing (#3711). */
class NotificationCutoffGenerationCoverageTest {
    /** A newer accepted app write vetoes the cancel before the opening-time listing is consulted. */
    @Test
    fun newerAppWriteVetoesTheStaleListing() {
        val dismiss =
            appModuleSource("$NOTIFICATIONS/ConversationNotificationRetirement.kt")
                .functionBody("dismissDeterministicCard")
        val veto = dismiss.indexOf("ConversationCardPostedRegistry.hasPostedAfter(key.tag, key.id, cutoffMs)")
        assertTrue("retirement checks for a newer app write", veto >= 0)
        assertTrue("the veto precedes the stale listing", veto < dismiss.indexOf("val listed ="))
        assertTrue("the veto precedes the cancel", veto < dismiss.indexOf("publisher.cancelNotification("))
    }

    private companion object {
        const val NOTIFICATIONS = "src/main/java/dev/ipf/whitenoise/android/notifications"
    }
}
