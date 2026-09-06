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

    @Test
    fun navigationAndProfileOverlaysReleaseComposerControlOwnership() {
        assertFalse(originVisible(appInForeground = false))
        assertFalse(originVisible(pendingProfileNpub = "profile-overlay"))
        assertFalse(originVisible(activeAccountRef = "account-b"))
        assertFalse(originVisible(activeGroupIdHex = "group-b"))
        assertFalse(originVisible(activeAccountRef = null))
        assertFalse(originVisible(activeGroupIdHex = null))
    }

    private fun originVisible(
        appLockScreenVisible: Boolean = false,
        appInForeground: Boolean = true,
        pendingProfileNpub: String? = null,
        activeAccountRef: String? = "account-a",
        activeGroupIdHex: String? = "group-a",
    ): Boolean =
        conversationDictationOriginVisible(
            appInForeground = appInForeground,
            appLockScreenVisible = appLockScreenVisible,
            pendingProfileNpub = pendingProfileNpub,
            activeAccountRef = activeAccountRef,
            activeGroupIdHex = activeGroupIdHex,
            accountRef = "ACCOUNT-A",
            groupIdHex = "GROUP-A",
        )
}
