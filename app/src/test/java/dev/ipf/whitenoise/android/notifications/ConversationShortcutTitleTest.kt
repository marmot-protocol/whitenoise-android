package dev.ipf.whitenoise.android.notifications

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationShortcutTitleTest {
    @Test
    fun existingHumanTitleIsNotDowngradedToNpub() {
        assertEquals(
            "Green Orca",
            preferredConversationShortcutTitle(
                candidate = "npub1jc3ut...hsq6nt96",
                existing = "Green Orca",
            ),
        )
    }

    @Test
    fun resolvedHumanTitleUpgradesExistingNpub() {
        assertEquals(
            "Green Orca",
            preferredConversationShortcutTitle(
                candidate = "Green Orca",
                existing = "npub1jc3ut...hsq6nt96",
            ),
        )
    }

    @Test
    fun latestHumanTitleCanReplaceAnOlderHumanTitle() {
        assertEquals(
            "Alice",
            preferredConversationShortcutTitle(
                candidate = "Alice",
                existing = "Green Orca",
            ),
        )
    }
}
