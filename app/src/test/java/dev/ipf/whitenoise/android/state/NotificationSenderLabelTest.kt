package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sender labels announced by a notification. A device report on MarmotKit 0.10.0 showed a sender
 * introduced by their hexadecimal account key, so these pin the precedence: an account-scoped nickname,
 * then the sanitized profile name, and an identity never presented as if it were a name.
 */
class NotificationSenderLabelTest {
    /** An account-scoped nickname outranks the sender's own published profile name. */
    @Test
    fun nicknameWinsOverTheProfileName() {
        assertEquals("Alice at work", notificationSenderNameOverride("Alice at work", "Alice"))
    }

    /** Without a nickname the sender's published profile name is used. */
    @Test
    fun profileNameIsUsedWithoutANickname() {
        assertEquals("Alice", notificationSenderNameOverride(null, "Alice"))
    }

    /** A profile name that is really the account key resolves to no label, so the caller abbreviates. */
    @Test
    fun rawAccountKeyIsNotALabel() {
        assertNull(notificationSenderNameOverride(null, ACCOUNT_ID_HEX))
        assertNull(notificationSenderNameOverride(ACCOUNT_ID_HEX, null))
        assertNull(humanNotificationLabel(ACCOUNT_ID_HEX))
    }

    /** An npub is an identity for the same reason, in either position. */
    @Test
    fun npubIsNotALabel() {
        assertNull(notificationSenderNameOverride(null, NPUB))
        assertNull(humanNotificationLabel(NPUB))
    }

    /** A nickname that is an identity still yields to a real profile name rather than being shown. */
    @Test
    fun identityNicknameYieldsToARealName() {
        assertEquals("Alice", notificationSenderNameOverride(ACCOUNT_ID_HEX, "Alice"))
    }

    /** Ordinary names survive sanitizing untouched, including ones that merely start with an n. */
    @Test
    fun ordinaryNamesAreKept() {
        assertEquals("Nina", humanNotificationLabel(" Nina "))
        assertEquals("npub enthusiast", humanNotificationLabel("npub enthusiast"))
    }

    private companion object {
        const val ACCOUNT_ID_HEX = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val NPUB = "npub1qy352euf40x77qfrg4ncn27daufwuj22r4ttmxhstefp92xqnjgs8pjhstefp92"
    }
}
