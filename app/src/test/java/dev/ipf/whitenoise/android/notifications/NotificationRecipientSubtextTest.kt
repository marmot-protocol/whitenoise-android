package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationRecipientSubtextTest {
    /** Recipient subtext appears only when multiple signed-in accounts belong to the conversation. */
    @Test
    fun recipientSubtextShownOnlyWhenMultipleAccountsAreRelevant() {
        assertNull(
            LocalNotificationFormatter.recipientAccountSubtext(
                relevantSignedInAccountCount = 1,
                recipientLabel = "Work",
            ),
        )
        assertEquals(
            "Work",
            LocalNotificationFormatter.recipientAccountSubtext(
                relevantSignedInAccountCount = 2,
                recipientLabel = "Work",
            ),
        )
        assertNull(
            LocalNotificationFormatter.recipientAccountSubtext(
                relevantSignedInAccountCount = 3,
                recipientLabel = "  ",
            ),
        )
        assertNull(
            LocalNotificationFormatter.recipientAccountSubtext(
                relevantSignedInAccountCount = 3,
                recipientLabel = null,
            ),
        )
    }
}
