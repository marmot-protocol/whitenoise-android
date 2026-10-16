package dev.ipf.whitenoise.android.ui.chats

import dev.ipf.marmotkit.SelfMembershipFfi
import dev.ipf.whitenoise.android.state.ChatListItem
import dev.ipf.whitenoise.android.state.offersLocalAbandonment
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #3310: one rule decides where abandonment is offered. Only MDK's confirmation that the account left, was removed
 * or the group is disbanded withdraws it, so a leave that stays queued or a disband that never converges keeps the
 * escape path on the row menu, the bulk bar and Group Details alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatAbandonmentEligibilityTest {
    /** A queued leave the engine keeps retrying still offers abandonment on the row menu and the bulk bar. */
    @Test
    fun queuedLeaveStillOffersAbandonmentOnTheRowMenuAndTheBulkBar() {
        val queued = leftScopeRow("queued", leavePending = true)
        assertTrue("the row menu keeps the escape path", queued.offersLocalAbandonment())
        assertTrue("the bulk bar keeps it", listOf(queued, leftScopeRow("member")).offerLocalAbandonment())
        assertTrue("Details keeps it", queued.group.offersLocalAbandonment())
    }

    /** A disband that has not converged is not confirmation either, so the action stays. */
    @Test
    fun convergingDisbandStillOffersAbandonment() {
        val member = leftScopeRow("disbanding")
        val disbanding = member.copy(projection = member.projection?.copy(disbanding = true))
        assertTrue(disbanding.offersLocalAbandonment())
        assertTrue(listOf(disbanding).offerLocalAbandonment())
    }

    /** A confirmed left, removed or disbanded chat offers neither the row action nor the bulk action. */
    @Test
    fun confirmedNonMembershipOffersNeither() {
        val confirmed =
            listOf(
                leftScopeRow("left", membership = SelfMembershipFfi.LEFT),
                leftScopeRow("removed", membership = SelfMembershipFfi.REMOVED),
                leftScopeRow("disbanded", disbanded = true),
            )
        confirmed.forEach { item ->
            assertFalse("${item.group.groupIdHex} has nothing to abandon", item.offersLocalAbandonment())
            val selection = listOf(leftScopeRow("member"), item)
            assertFalse("one such chat withholds the bulk action", selection.offerLocalAbandonment())
        }
        assertFalse("Details reads the same group rule", confirmed.first().group.offersLocalAbandonment())
        assertFalse("disbanded groups too", leftScopeRow("gone").group.copy(disbanded = true).offersLocalAbandonment())
    }

    /** A read-only invite declines instead, on both surfaces, and an empty selection offers nothing. */
    @Test
    fun readOnlyInviteAndEmptySelectionOfferNothing() {
        val invite = ChatRowPortFixtures.item(pending = true)
        assertFalse(invite.offersLocalAbandonment())
        assertFalse(invite.group.offersLocalAbandonment())
        assertFalse(emptyList<ChatListItem>().offerLocalAbandonment())
    }
}
