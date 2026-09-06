package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationDictationOriginVisibilityTest {
    @Test
    fun appLockObscuresTheForegroundDictationOrigin() {
        assertTrue(originVisible(appLockScreenVisible = false))
        assertFalse(originVisible(appLockScreenVisible = true))
    }

    private fun originVisible(appLockScreenVisible: Boolean): Boolean =
        conversationDictationOriginVisible(
            appInForeground = true,
            appLockScreenVisible = appLockScreenVisible,
            pendingProfileNpub = null,
            activeAccountRef = "account-a",
            activeGroupIdHex = "group-a",
            accountRef = "ACCOUNT-A",
            groupIdHex = "GROUP-A",
        )
}
