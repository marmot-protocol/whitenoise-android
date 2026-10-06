package dev.ipf.whitenoise.android.notifications

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Persisted routing survives adapter recreation without storing profile data or crossing local accounts. */
@RunWith(RobolectricTestRunner::class)
class ProfileNotificationOverridePreferencesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alice = "a".repeat(64)
    private val bob = "b".repeat(64)

    @Test fun restartAndCrossInstanceReadsPreserveOnlyTheSelectedAuthorAndAccount() {
        val background = ProfileNotificationOverridePreferences(context)
        val ui = ProfileNotificationOverridePreferences(context)
        val custom = ProfileNotificationOverride(ProfileNotificationMode.CUSTOM, ConversationVibrationPattern.DOUBLE)
        assertTrue(ui.set("personal", alice.uppercase(), custom))
        assertEquals(custom, background.get("personal", alice))
        assertEquals(custom, ProfileNotificationOverridePreferences(context).get("personal", alice))
        assertEquals(ProfileNotificationMode.DEFAULT, background.get("personal", bob).mode)
        assertEquals(ProfileNotificationMode.DEFAULT, background.get("work", alice).mode)
    }

    @Test fun muteAndResetKeepDormantWaveformAndAccountCleanupIsPrefixSafe() {
        val store = ProfileNotificationOverridePreferences(context)
        val selected = ProfileNotificationOverride(ProfileNotificationMode.CUSTOM, ConversationVibrationPattern.LONG)
        store.set("personal", alice, selected)
        store.set("personal-extra", alice, selected)
        store.set("personal", bob, selected)
        store.set("personal", alice, selected.copy(mode = ProfileNotificationMode.MUTED))
        assertEquals(ProfileNotificationMode.MUTED, store.get("personal", alice).mode)
        store.set("personal", alice, selected.copy(mode = ProfileNotificationMode.DEFAULT))
        assertEquals(ConversationVibrationPattern.LONG, store.get("personal", alice).vibration)
        assertTrue(store.clearAccount("personal"))
        assertEquals(ProfileNotificationOverride(), store.get("personal", alice))
        assertEquals(ProfileNotificationOverride(), store.get("personal", bob))
        assertEquals(selected, store.get("personal-extra", alice))
    }

    @Test fun malformedIdentitiesAndExpiredOwnersCannotWrite() {
        val store = ProfileNotificationOverridePreferences(context)
        val mute = ProfileNotificationOverride(ProfileNotificationMode.MUTED)
        listOf("", " ", "a".repeat(63), "$alice ", "npub1someone").forEach {
            assertFalse(store.set("personal", it, mute))
            assertEquals(ProfileNotificationOverride(), store.get("personal", it))
        }
        assertFalse(store.set("personal", alice, mute) { false })
        assertEquals(ProfileNotificationOverride(), store.get("personal", alice))
    }

    @Test fun persistedKeysContainOnlyBoundedHashes() {
        val key = checkNotNull(ProfileNotificationOverridePreferences.key("private-account", alice))
        assertTrue(key.matches(Regex("[0-9a-f]{64}\\.[0-9a-f]{64}")))
        assertFalse(key.contains("private-account"))
        assertFalse(key.contains(alice))
    }
}
