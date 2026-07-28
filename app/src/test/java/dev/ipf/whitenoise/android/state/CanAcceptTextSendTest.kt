package dev.ipf.whitenoise.android.state

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for issue #264: a short text message could be silently
 * dropped — the input cleared but no optimistic bubble appeared and no error
 * was shown.
 *
 * Root cause: the UI cleared the composer synchronously the instant it
 * dispatched the (async) send coroutine, while `ConversationController.send()`
 * could bail at a guard before committing the optimistic record. The clear and
 * the commit disagreed, so the text vanished.
 *
 * The same predicate also owns the resume window: a current member backed by a
 * positive seed may hand text off while the fresh roster is pending, but a
 * removed, unknown, or terminal account may not. The UI clears its input only
 * after the controller confirms acceptance.
 */
class CanAcceptTextSendTest {
    @Test
    fun acceptsKnownSeededMemberWhileVerificationIsPending() {
        assertTrue(
            acceptsText(
                membersVerified = false,
                isSelfMember = true,
                seededSelfMember = true,
            ),
        )
    }

    @Test
    fun rejectsWhenCurrentMembershipRemovedDespitePositiveSeed() {
        assertFalse(
            acceptsText(
                membersVerified = true,
                isSelfMember = false,
                seededSelfMember = true,
            ),
        )
    }

    @Test
    fun acceptsWhenAccountBoundTextPresentAndCanSend() {
        assertTrue(acceptsText())
    }

    @Test
    fun rejectsWhenNoAccountBound() {
        // No active account ref yet → send() returns early. Input must be kept.
        assertFalse(acceptsText(accountRef = null))
    }

    @Test
    fun rejectsWhenTextBlank() {
        // Caller passes already-trimmed text; empty means nothing to send.
        assertFalse(acceptsText(trimmed = ""))
    }

    @Test
    fun rejectsWhenMembershipIsUnknown() {
        assertFalse(
            acceptsText(
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = false,
            ),
        )
    }

    @Test
    fun rejectsUnrecoverableGroup() {
        assertFalse(acceptsText(unrecoverable = true))
    }

    @Test
    fun rejectsDisbandingAndDisbandedGroups() {
        listOf(
            true to false,
            false to true,
        ).forEach { (disbanding, disbanded) ->
            assertFalse(
                acceptsText(
                    membersVerified = false,
                    seededSelfMember = true,
                    disbanding = disbanding,
                    disbanded = disbanded,
                ),
            )
        }
    }

    private fun acceptsText(
        accountRef: String? = "acct",
        trimmed: String = "hi",
        membersVerified: Boolean = true,
        isSelfMember: Boolean = true,
        seededSelfMember: Boolean = false,
        unrecoverable: Boolean = false,
        disbanding: Boolean = false,
        disbanded: Boolean = false,
    ): Boolean =
        canAcceptTextSend(
            accountRef = accountRef,
            trimmed = trimmed,
            membersVerified = membersVerified,
            isSelfMember = isSelfMember,
            seededSelfMember = seededSelfMember,
            unrecoverable = unrecoverable,
            disbanding = disbanding,
            disbanded = disbanded,
        )
}
