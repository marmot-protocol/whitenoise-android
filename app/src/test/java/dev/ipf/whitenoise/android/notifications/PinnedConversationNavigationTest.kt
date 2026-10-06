package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the actual launcher intent codec and durable revocation checks, without a second routing table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PinnedConversationNavigationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val tokens get() = PinnedConversationTokens.create(context)

    /** A launcher tap carries its exact non-active account; a currently active same-ID group cannot replace it. */
    @Test
    fun intentRetainsExactAccountAndUsesExistingMissingAccountResolution() {
        val capability = requireNotNull(tokens.issue("account-b", GROUP))
        val intent = PinnedConversationNavigation.intent(context, capability)
        val target = requireNotNull(PinnedConversationNavigation.target(context, intent, "account-a", false))
        assertEquals("account-b", target.accountRef)
        assertEquals(GROUP, target.groupIdHex)
        assertEquals(NotificationTargetKind.MESSAGE, target.kind)
        assertNotNull(target.shortcutCapability)
        assertEquals(
            NotificationNavStep.MissingAccount,
            resolveNotificationNav(
                target = target,
                knownAccountRefs = setOf("account-a"),
                activeAccountRef = "account-a",
                chatListReady = true,
                availableGroupIds = setOf(GROUP),
            ),
        )
    }

    /** Changing an account while retaining a valid pair of tokens invalidates both the URI identity and credentials. */
    @Test
    fun mismatchedAccountOpensSafeRoot() {
        val capability = requireNotNull(tokens.issue("account-a", GROUP))
        val forged = PinnedConversationCapability("account-b", GROUP, capability.accountToken, capability.groupToken)
        val target =
            PinnedConversationNavigation.target(
                context,
                PinnedConversationNavigation.intent(context, forged),
                "account-a",
                false,
            )
        assertEquals(NotificationTargetKind.CHAT_LIST, target?.kind)
        assertEquals("account-a", target?.accountRef)
        assertEquals("", target?.groupIdHex)
    }

    /** Lock evaluation never queues a conversation to open silently after a later unlock. */
    @Test
    fun lockedTapReturnsToRoot() {
        val capability = requireNotNull(tokens.issue("account-a", GROUP))
        val target =
            PinnedConversationNavigation.target(
                context,
                PinnedConversationNavigation.intent(context, capability),
                "account-a",
                true,
            )
        assertEquals(NotificationTargetKind.CHAT_LIST, target?.kind)
        assertNull(target?.shortcutCapability)
    }

    /** The queued target loses authority after group removal even if the same ID is recreated before routing. */
    @Test
    fun queuedTargetCannotInheritRecreatedGroup() {
        val capability = requireNotNull(tokens.issue("account-a", GROUP))
        val target =
            requireNotNull(
                PinnedConversationNavigation.target(
                    context,
                    PinnedConversationNavigation.intent(context, capability),
                    "account-a",
                    false,
                ),
            )
        assertTrue(PinnedConversationNavigation.isCurrent(context, target, setOf("account-a"), false))
        tokens.revokeGroup("account-a", GROUP)
        assertNotNull(tokens.issue("account-a", GROUP))
        assertFalse(PinnedConversationNavigation.isCurrent(context, target, setOf("account-a"), false))
    }

    /** Process recreation reads the same durable authority; sign-out and a reused label revoke the old intent. */
    @Test
    fun recreatedProcessValidatesCredentialsAndReusedAccountLabelDoesNot() {
        val capability = requireNotNull(tokens.issue("account-a", GROUP))
        val intent = PinnedConversationNavigation.intent(context, capability)
        assertEquals(
            NotificationTargetKind.MESSAGE,
            PinnedConversationNavigation.target(context, Intent(intent), "account-b", false)?.kind,
        )
        PinnedConversationTokens.create(context).revokeAccount("account-a")
        tokens.issue("account-a", GROUP)
        assertEquals(
            NotificationTargetKind.CHAT_LIST,
            PinnedConversationNavigation.target(context, Intent(intent), "account-b", false)?.kind,
        )
    }

    /** Accounts and lock state are checked again at the final route boundary. */
    @Test
    fun queuedTargetRejectsSignedOutOwnerAndLaterLock() {
        val capability = requireNotNull(tokens.issue("account-a", GROUP))
        val target =
            requireNotNull(
                PinnedConversationNavigation.target(
                    context,
                    PinnedConversationNavigation.intent(context, capability),
                    "account-b",
                    false,
                ),
            )
        assertFalse(PinnedConversationNavigation.isCurrent(context, target, setOf("account-b"), false))
        assertFalse(PinnedConversationNavigation.isCurrent(context, target, setOf("account-a"), true))
    }

    /** Malformed recognized actions actively route to root instead of leaving a previous conversation visible. */
    @Test
    fun malformedPinIntentIsSafeRootWhileOrdinaryIntentIsUntouched() {
        val uri = Uri.parse("whitenoise-pinned://conversation/bad")
        val malformed = Intent(PinnedConversationNavigation.ACTION_OPEN, uri)
        assertEquals(
            NotificationTargetKind.CHAT_LIST,
            PinnedConversationNavigation.target(context, malformed, "account-a", false)?.kind,
        )
        assertNull(PinnedConversationNavigation.target(context, Intent(Intent.ACTION_MAIN), "account-a", false))
    }

    private companion object {
        val GROUP = "ab".repeat(32)
    }
}
