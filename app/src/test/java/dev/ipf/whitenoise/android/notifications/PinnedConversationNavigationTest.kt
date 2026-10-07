package dev.ipf.whitenoise.android.notifications

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import dev.ipf.whitenoise.android.state.PinnedShortcutLockDecision
import dev.ipf.whitenoise.android.state.pinnedShortcutLockDecision
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import javax.crypto.spec.SecretKeySpec

/** Exercises the actual launcher intent codec and durable revocation checks, without a second routing table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PinnedConversationNavigationTest {
    /**
     * Runs production encrypted persistence with a deterministic test key instead of an unavailable Android
     * Keystore.
     */
    @Before fun installTestKey() {
        setPinnedConversationTestKey(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES"))
    }

    /** Prevents the fixture's key from leaking into another sandbox or provider-failure scenario. */
    @After fun releaseTestKey() {
        setPinnedConversationTestKey(null)
    }

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

    /** A newer accepted route supersedes a cold-start pin, while harmless recreation preserves the newest tap. */
    @Test
    fun heldPinCannotOverwriteANewerRouteWhenLockEvaluationSettles() {
        var decision = PinnedShortcutLockDecision.WAIT
        val gate = PinnedShortcutTapGate({ decision }, { "account-a" }, { decision == PinnedShortcutLockDecision.WAIT })
        val first = PinnedConversationNavigation.intent(context, requireNotNull(tokens.issue("account-a", GROUP)))
        assertTrue(gate.hold(first))
        assertFalse(gate.hold(Intent(Intent.ACTION_MAIN)))
        gate.supersedeForRoute(false, false, null)
        val routes =
            listOf(
                Triple(true, false, null),
                Triple(false, true, null),
                Triple(false, false, "whitenoise:peer"),
            )
        for ((target, share, data) in routes) {
            decision = PinnedShortcutLockDecision.WAIT
            assertTrue(gate.hold(first))
            gate.supersedeForRoute(target, share, data)
            decision = PinnedShortcutLockDecision.OPEN
            assertNull(gate.release())
        }
        decision = PinnedShortcutLockDecision.WAIT
        assertTrue(gate.hold(first))
        val newest = PinnedConversationNavigation.intent(context, requireNotNull(tokens.issue("account-b", GROUP)))
        assertTrue(gate.hold(newest))
        assertFalse(gate.hold(Intent(Intent.ACTION_MAIN)))
        decision = PinnedShortcutLockDecision.OPEN
        assertTrue(newest.filterEquals(requireNotNull(gate.release())))
        assertNull(gate.release())
    }

    /** A second pin with a decided lock immediately replaces an older unresolved tap. */
    @Test
    fun readyPinDiscardsAnOlderHeldTap() {
        var decision = PinnedShortcutLockDecision.WAIT
        val gate = PinnedShortcutTapGate({ decision }, { "account-a" }, { decision == PinnedShortcutLockDecision.WAIT })
        val first = PinnedConversationNavigation.intent(context, requireNotNull(tokens.issue("account-a", GROUP)))
        assertTrue(gate.hold(first))
        decision = PinnedShortcutLockDecision.OPEN
        assertFalse(gate.hold(first))
        assertNull(gate.release())
    }

    /** Activity recreation keeps the unresolved capability in its retained task owner and consumes it only once. */
    @Test
    fun recreatedGateRetainsPendingTapUntilLockDecisionSettles() {
        val store = ViewModelStore()
        var decision = PinnedShortcutLockDecision.WAIT
        val first = gate({ decision }, retainedPinState(store))
        val intent = PinnedConversationNavigation.intent(context, requireNotNull(tokens.issue("account-a", GROUP)))
        assertTrue(first.hold(intent))
        val recreated = gate({ decision }, retainedPinState(store))
        assertFalse(recreated.hold(Intent(Intent.ACTION_MAIN)))
        decision = PinnedShortcutLockDecision.OPEN
        val replayed = requireNotNull(recreated.release())
        assertEquals(intent.data, replayed.data)
        assertEquals(GROUP, recreated.target(context, replayed)?.groupIdHex)
        assertNull(gate({ decision }, retainedPinState(store)).release())
        store.clear()
    }

    /** A newer route after recreation clears the retained tap; separate and finished tasks cannot inherit it. */
    @Test
    fun recreatedGateSupersessionAndTaskSeparationRetainOneShotOwnership() {
        val store = ViewModelStore()
        val decision = { PinnedShortcutLockDecision.WAIT }
        val first = gate(decision, retainedPinState(store))
        val intent = PinnedConversationNavigation.intent(context, requireNotNull(tokens.issue("account-a", GROUP)))
        assertTrue(first.hold(intent))
        val otherStore = ViewModelStore()
        assertNull(gate(decision, retainedPinState(otherStore)).release())
        val recreated = gate(decision, retainedPinState(store))
        recreated.supersedeForRoute(true, false, null)
        assertNull(gate(decision, retainedPinState(store)).release())
        assertTrue(recreated.hold(intent))
        val state = retainedPinState(store)
        store.clear()
        assertNull(state.held)
        otherStore.clear()
    }

    /** Recreates the Activity's actual ViewModel lookup while retaining its task store. */
    private fun retainedPinState(store: ViewModelStore): PinnedShortcutTapState =
        ViewModelProvider(
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = store
            },
            ViewModelProvider.NewInstanceFactory(),
        )[PinnedShortcutTapState::class.java]

    /** Rebinds ephemeral lock callbacks to the retained pending-tap owner, as a replacement Activity does. */
    private fun gate(
        decision: () -> PinnedShortcutLockDecision,
        state: PinnedShortcutTapState,
    ): PinnedShortcutTapGate =
        PinnedShortcutTapGate(
            lockDecision = decision,
            activeAccount = { "account-a" },
            evaluationPending = { decision() == PinnedShortcutLockDecision.WAIT },
            pending = state,
        )

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

    /** A cold-start tap keeps its capability while the lock decision loads; only a decided lock drops it. */
    @Test
    fun pendingLockEvaluationKeepsTheCapabilityUntilTheDecision() {
        assertEquals(PinnedShortcutLockDecision.WAIT, pinnedShortcutLockDecision(true, true))
        assertEquals(PinnedShortcutLockDecision.WAIT, pinnedShortcutLockDecision(false, true))
        assertEquals(PinnedShortcutLockDecision.LOCKED, pinnedShortcutLockDecision(true, false))
        assertEquals(PinnedShortcutLockDecision.OPEN, pinnedShortcutLockDecision(false, false))
        val capability = requireNotNull(tokens.issue("account-a", GROUP))
        val intent = PinnedConversationNavigation.intent(context, capability)
        val locked = pinnedShortcutLockDecision(true, true) == PinnedShortcutLockDecision.LOCKED
        val held = requireNotNull(PinnedConversationNavigation.target(context, intent, "account-a", locked))
        assertEquals(NotificationTargetKind.MESSAGE, held.kind)
        assertNotNull(held.shortcutCapability)
        // The held target still fails closed once the lock is decided and showing.
        assertFalse(PinnedConversationNavigation.isCurrent(context, held, setOf("account-a"), locked = true))
        assertTrue(PinnedConversationNavigation.isCurrent(context, held, setOf("account-a"), locked = false))
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
        /** The 32-hex shape MDK emits for a 16-byte group ID. */
        val GROUP = "ab".repeat(16)
    }
}
