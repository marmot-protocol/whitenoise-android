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

    /** Failed disk writes mutate SharedPreferences memory first; all adapters must retain the prior mute. */
    @Test fun failedSaveAndCleanupRestoreRoutingAcrossAdapters() {
        val delegate = context.getSharedPreferences("failing-profile-policy", android.content.Context.MODE_PRIVATE)
        val stable = ProfileNotificationOverridePreferences(context, delegate)
        val author = "a".repeat(64)
        stable.set("personal", author, ProfileNotificationOverride(ProfileNotificationMode.MUTED))
        val failing =
            ProfileNotificationOverridePreferences(
                context,
                object : android.content.SharedPreferences by delegate {
                    override fun edit(): android.content.SharedPreferences.Editor = FailingEditor(delegate.edit())
                },
            )
        assertFalse(failing.set("personal", author, ProfileNotificationOverride(ProfileNotificationMode.DEFAULT)))
        assertEquals(ProfileNotificationMode.MUTED, stable.get("personal", author).mode)
        assertEquals(ProfileNotificationMode.MUTED, failing.get("personal", author).mode)
        assertFalse(failing.clearAccount("personal"))
        assertEquals(ProfileNotificationMode.MUTED, stable.get("personal", author).mode)
        assertFalse(failing.set("other", author, ProfileNotificationOverride(ProfileNotificationMode.CUSTOM)))
        assertEquals(ProfileNotificationMode.DEFAULT, stable.get("other", author).mode)
        assertFalse(stable.hasChoice("other", author))
    }

    /** Emulates Android's memory-first commit and an unavailable disk, including failed rollback flushes. */
    private class FailingEditor(
        private val delegate: android.content.SharedPreferences.Editor,
    ) : android.content.SharedPreferences.Editor by delegate {
        override fun putString(
            key: String?,
            value: String?,
        ): android.content.SharedPreferences.Editor {
            delegate.putString(key, value)
            return this
        }

        override fun remove(key: String?): android.content.SharedPreferences.Editor {
            delegate.remove(key)
            return this
        }

        override fun commit(): Boolean {
            delegate.commit()
            return false
        }
    }

    /**
     * A recreated UI/background adapter reads the saved choice without applying it to another author or local
     * identity.
     */
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

    /**
     * Mute/default transitions preserve a chosen waveform; removing an account clears only its own hashed
     * namespace.
     */
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

    /** Authoritative account retention removes obsolete records while preserving every retained owner's choice. */
    @Test fun retentionRetriesFailedCleanupWithoutTouchingAnotherAccount() {
        val store = ProfileNotificationOverridePreferences(context)
        val choice = ProfileNotificationOverride(ProfileNotificationMode.MUTED)
        store.set("removed", alice, choice)
        store.set("retained", alice, choice)
        store.retainAccounts(listOf("retained"))
        val restarted = ProfileNotificationOverridePreferences(context)
        assertEquals(ProfileNotificationOverride(), restarted.get("removed", alice))
        assertEquals(choice, restarted.get("retained", alice))
    }

    /** Invalid public keys and revoked editor owners must not create even an inert preference entry. */
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

    /** The persisted routing index contains no raw account label or author key. */
    @Test fun persistedKeysContainOnlyBoundedHashes() {
        val key = checkNotNull(ProfileNotificationOverridePreferences.key("private-account", alice))
        assertTrue(key.matches(Regex("[0-9a-f]{64}\\.[0-9a-f]{64}")))
        assertFalse(key.contains("private-account"))
        assertFalse(key.contains(alice))
    }
}
