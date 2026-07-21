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
    fun blankContactNameFallsBackToProfileName() {
        assertEquals(
            "Alice",
            notificationSenderNameOverride(
                contactNickname = "   ",
                localProfileName = "Alice",
            ),
        )
    }

    @Test
    fun blankNamesResolveToNull() {
        assertNull(
            notificationSenderNameOverride(
                contactNickname = "   ",
                localProfileName = "\t\n",
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

    @Test
    fun authenticatedPayloadNameCanTemporarilyFillAColdProfileCache() {
        assertEquals("Green Orca", notificationDisplayNameHint("  Green Orca  "))
        assertEquals(
            "Green Orca",
            resolvedProfileDisplayName(
                profileDisplayName = null,
                notificationDisplayNameHint = "Green Orca",
            ),
        )
    }

    @Test
    fun identityFallbacksNeverBecomeProfileHints() {
        assertNull(notificationDisplayNameHint("npub1jc3ut4ehf7n0example"))
        assertNull(notificationDisplayNameHint("npub1jc3ut...hsq6nt96"))
        assertNull(notificationDisplayNameHint("a".repeat(64)))
    }

    @Test
    fun authoritativeProfileNameWinsOverNotificationHint() {
        assertEquals(
            "Alice",
            resolvedProfileDisplayName(
                profileDisplayName = "Alice",
                notificationDisplayNameHint = "Green Orca",
            ),
        )
    }
}
