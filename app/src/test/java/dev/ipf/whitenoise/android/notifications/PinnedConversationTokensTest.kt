package dev.ipf.whitenoise.android.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Launcher credentials survive process recreation while account/group removal revokes old generations. */
@RunWith(RobolectricTestRunner::class)
class PinnedConversationTokensTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("pin-credential-test", Context.MODE_PRIVATE)
    private val store = PinnedConversationTokens(preferences)
    private val group = "ab".repeat(32)

    /** Each test starts with a private synthetic credential namespace. */
    @Before
    fun reset() {
        check(preferences.edit().clear().commit())
    }

    /** Repeated requests and a new store instance retain one stable platform identity until revocation. */
    @Test
    fun duplicateRequestAndProcessRecreationPreserveIdentity() {
        val first = store.issue("personal", group)!!
        val recreated = PinnedConversationTokens(preferences)
        assertTrue(recreated.isValid(first))
        assertEquals(first.shortcutId, recreated.issue("personal", group.uppercase())!!.shortcutId)
    }

    /** A launcher request captured before deletion cannot become valid when the same group ID is re-created. */
    @Test
    fun groupDeletionRevokesPendingAndExistingInstances() {
        val old = store.issue("personal", group)!!
        val other = store.issue("personal", "cd".repeat(32))!!
        store.revokeGroup("personal", group)
        val recreated = PinnedConversationTokens(preferences)
        assertFalse(recreated.isValid(old))
        assertTrue(recreated.isValid(other))
        val replacement = recreated.issue("personal", group)!!
        assertNotEquals(old.shortcutId, replacement.shortcutId)
        assertFalse(recreated.isValid(old))
    }

    /** Account-name prefixes cannot revoke each other; a signed-out label receives a new incarnation. */
    @Test
    fun signOutRevokesOnlyExactAccountAndCannotResurrectOldPins() {
        val old = store.issue("work", group)!!
        val other = store.issue("work-team", group)!!
        store.revokeAccount("work")
        assertFalse(store.isValid(old))
        assertTrue(store.isValid(other))
        assertNotEquals(old.shortcutId, store.issue("work", group)!!.shortcutId)
    }

    /** A queued background credential write cannot mint fresh authority after its foreground owner was deleted. */
    @Test
    fun queuedIssuanceIsRejectedAfterRevocation() {
        val beforeGroupRemoval = PinnedConversationTokens.captureRequest()
        store.revokeGroup("personal", group)
        assertNull(store.issue("personal", group, beforeGroupRemoval))
        val current = store.issue("personal", group)!!
        val beforeSignOut = PinnedConversationTokens.captureRequest()
        store.revokeAccount("personal")
        assertNull(store.issue("personal", group, beforeSignOut))
        assertFalse(store.isValid(current))
        assertTrue(store.isValid(store.issue("personal", group)!!))
    }

    /** Possessing a valid pin for one account/group does not authorize modifying its destination. */
    @Test
    fun forgedAccountOrGroupIsRejected() {
        val original = store.issue("personal", group)!!
        val other = store.issue("work", group)!!
        val forged = PinnedConversationCapability("work", group, original.accountToken, original.groupToken)
        assertFalse(store.isValid(forged))
        assertTrue(store.isValid(other))
        val wrongGroup =
            PinnedConversationCapability(
                "personal",
                "ef".repeat(32),
                original.accountToken,
                original.groupToken,
            )
        assertFalse(store.isValid(wrongGroup))
    }

    /** Malformed routes and corrupted persisted credentials fail closed without creating a routing map. */
    @Test
    fun invalidIdentityAndCorruptCredentialsFailClosed() {
        assertNull(store.issue(" personal ", group))
        assertNull(store.issue("personal", "invalid"))
        val original = store.issue("private-account-name", group)!!
        assertFalse(preferences.all.keys.any { it.contains("private-account-name") || it.contains(group) })
        preferences.all.keys.forEach { preferences.edit().putString(it, "invalid").commit() }
        assertFalse(store.isValid(original))
        assertFalse(original.toString().contains(original.accountToken))
    }
}
