package dev.ipf.whitenoise.android.state

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Per-member group mute storage (#2782): scope, persistence, reversal and orphan cleanup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MemberMutePreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private lateinit var preferences: SharedPreferences

    /** Starts every case from an empty preference file rather than a neighbour's leftovers. */
    @Before
    fun clearPreferences() {
        preferences = context.getSharedPreferences("member-mute-preferences-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    /** A mute applies to exactly one account, group and member, and to nothing adjacent. */
    @Test
    fun muteAppliesOnlyToItsOwnAccountGroupAndMember() {
        val store = store()
        store.setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = true)

        assertTrue(store.isMuted(ACCOUNT_A, GROUP_X, MEMBER_A))
        assertFalse("another group must stay audible", store.isMuted(ACCOUNT_A, GROUP_Y, MEMBER_A))
        assertFalse("another local account must stay audible", store.isMuted(ACCOUNT_B, GROUP_X, MEMBER_A))
        assertFalse("another member must stay audible", store.isMuted(ACCOUNT_A, GROUP_X, MEMBER_B))
    }

    /** Protocol identifiers match regardless of hex casing or surrounding whitespace. */
    @Test
    fun muteLookupNormalizesHexCasingAndWhitespace() {
        val store = store()
        store.setMuted(ACCOUNT_A, GROUP_X.uppercase(), MEMBER_A.uppercase(), muted = true)

        assertTrue(store.isMuted(ACCOUNT_A, GROUP_X, MEMBER_A))
        assertTrue(store.isMuted(ACCOUNT_A, " $GROUP_X ", " $MEMBER_A "))
        assertEquals(1, store.state.value.size)
    }

    /** An incomplete identity stores nothing and reports nothing as muted. */
    @Test
    fun incompleteIdentityNeitherStoresNorMatches() {
        val store = store()
        store.setMuted(ACCOUNT_A, GROUP_X, senderIdHex = null, muted = true)
        store.setMuted(ACCOUNT_A, groupIdHex = "  ", senderIdHex = MEMBER_A, muted = true)
        store.setMuted(accountRef = "", groupIdHex = GROUP_X, senderIdHex = MEMBER_A, muted = true)

        assertTrue(store.state.value.isEmpty())
        assertFalse(store.isMuted(ACCOUNT_A, GROUP_X, null))
        assertFalse(store.isMuted(null, GROUP_X, MEMBER_A))
    }

    /** The preference survives process death: a fresh store over the same file reads it back. */
    @Test
    fun mutedMemberSurvivesRestart() {
        store().setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = true)

        assertTrue(store().isMuted(ACCOUNT_A, GROUP_X, MEMBER_A))
    }

    /** Unmuting reverses the entry, and that reversal survives restart too. */
    @Test
    fun mutedMemberCanBeUnmutedAndTheReversalPersists() {
        val store = store()
        store.setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = true)
        store.setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = false)

        assertFalse(store.isMuted(ACCOUNT_A, GROUP_X, MEMBER_A))
        assertFalse(store().isMuted(ACCOUNT_A, GROUP_X, MEMBER_A))
    }

    /** Retaining the remaining accounts drops the removed account's entries and keeps the rest. */
    @Test
    fun retainingAccountsDropsOrphanedEntries() {
        val store = store()
        store.setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = true)
        store.setMuted(ACCOUNT_B, GROUP_X, MEMBER_A, muted = true)

        store.retainAccounts(listOf(ACCOUNT_B))

        assertFalse(store.isMuted(ACCOUNT_A, GROUP_X, MEMBER_A))
        assertTrue(store.isMuted(ACCOUNT_B, GROUP_X, MEMBER_A))
        assertFalse("orphan cleanup must persist", store().isMuted(ACCOUNT_A, GROUP_X, MEMBER_A))
    }

    /** Clearing one account drops all of its entries without inspecting the remaining accounts. */
    @Test
    fun clearingAnAccountDropsOnlyItsOwnEntries() {
        val store = store()
        store.setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = true)
        store.setMuted(ACCOUNT_A, GROUP_Y, MEMBER_B, muted = true)
        store.setMuted(ACCOUNT_B, GROUP_X, MEMBER_A, muted = true)

        store.clearAccount(ACCOUNT_A)

        assertEquals(
            setOf(ACCOUNT_B),
            store.state.value
                .mapNotNull(::accountRefOf)
                .toSet(),
        )
        assertTrue(store.isMuted(ACCOUNT_B, GROUP_X, MEMBER_A))
    }

    /** Stored keys that no longer parse are dropped rather than being treated as live mutes. */
    @Test
    fun malformedStoredKeysAreIgnored() {
        preferences.edit().putStringSet("mutedMembers", setOf("not-a-member-key", "")).commit()

        assertTrue(store().state.value.isEmpty())
    }

    /** Observers see a toggle immediately, so the profile row can flip its own copy. */
    @Test
    fun stateExposesEveryStoredKey() {
        val store = store()
        store.setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = true)
        store.setMuted(ACCOUNT_A, GROUP_Y, MEMBER_B, muted = true)

        assertEquals(2, store.state.value.size)
        store.setMuted(ACCOUNT_A, GROUP_X, MEMBER_A, muted = false)
        assertEquals(1, store.state.value.size)
    }

    /** A store backed by this case's own preference file. */
    private fun store(): MemberMutePreferences = MemberMutePreferences(context, preferences)

    /** The local account a stored key belongs to. */
    private fun accountRefOf(key: String): String? = MemberMutePreferences.accountRefOfMemberKey(key)

    private companion object {
        const val ACCOUNT_A = "account-a"
        const val ACCOUNT_B = "account-b"
        const val GROUP_X = "aa11bb22"
        const val GROUP_Y = "cc33dd44"
        const val MEMBER_A = "11aa22bb"
        const val MEMBER_B = "33cc44dd"
    }
}
