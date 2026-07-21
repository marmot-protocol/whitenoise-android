package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationSenderNameResolutionTest {
    @Test
    fun localContactNameWinsOverProfileName() {
        assertEquals(
            "Alice (work)",
            notificationSenderNameOverride(
                contactNickname = "Alice (work)",
                localProfileName = "Alice",
            ),
        )
    }

    @Test
    fun localProfileNameIsUsedWithoutContactName() {
        assertEquals(
            "Alice",
            notificationSenderNameOverride(
                contactNickname = null,
                localProfileName = "Alice",
            ),
        )
    }

    @Test
    fun unresolvedLocalNameLeavesPayloadAndFormatterFallbackAvailable() {
        assertNull(
            notificationSenderNameOverride(
                contactNickname = null,
                localProfileName = null,
            ),
        )
    }
}
