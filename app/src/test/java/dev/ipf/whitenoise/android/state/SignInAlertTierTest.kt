package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.marmotkit.CompromiseWarningItemFfi
import dev.ipf.marmotkit.CompromiseWarningReasonFfi
import dev.ipf.marmotkit.UnrecognizedSignInFfi
import dev.ipf.marmotkit.UnrecognizedSignInStateFfi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which sign-ins are announced, how loudly, and when a notification is posted, updated or withdrawn. */
class SignInAlertTierTest {
    /** Only new, signed-in, waiting sign-ins not marked as the user's ask for review; the rest stay passive. */
    @Test
    fun onlyNewWaitingSignInsAskForReview() {
        val signIns =
            listOf(
                alertSignIn("new"),
                alertSignIn("before", beforeThisDevice = true),
                alertSignIn("gone", signedIn = false),
                alertSignIn("later", state = UnrecognizedSignInStateFfi.NOT_NOW),
                alertSignIn("linked", state = UnrecognizedSignInStateFfi.LINKED_ELSEWHERE),
                alertSignIn("rejected", state = UnrecognizedSignInStateFfi.REJECTED_ELSEWHERE),
                alertSignIn("mine"),
            )

        assertEquals(listOf("new"), signInsToReview(signIns, mine = setOf("mine")).map { it.slotIdHex })
    }

    /** MDK's warning alone raises the compromise tier; an unknown sign-in alone is review; nothing else is passive. */
    @Test
    fun onlyTheWarningRaisesTheCompromiseTier() {
        assertEquals(SignInAlertTier.Compromise, signInAlertTier(emptyList(), listOf(alertWarning("a"))))
        assertEquals(SignInAlertTier.Compromise, signInAlertTier(listOf(alertSignIn("a")), listOf(alertWarning("a"))))
        assertEquals(SignInAlertTier.Review, signInAlertTier(listOf(alertSignIn("a")), emptyList()))
        assertEquals(SignInAlertTier.Passive, signInAlertTier(emptyList(), emptyList()))
    }

    /** A sign-in that has waited a long time is still only review: time never escalates. */
    @Test
    fun timeNeverEscalates() {
        val old = alertSignIn("old").copy(appearedAt = 1u, lastSeenAt = 1u)

        assertEquals(SignInAlertTier.Review, signInAlertTier(signInsToReview(listOf(old), emptySet()), emptyList()))
    }

    /** Local-key accounts get alerts; external-signer, signed-out and unlinked accounts do not yet. */
    @Test
    fun alertsApplyToSignedInLocalKeyAccounts() {
        assertTrue(signInAlertsApply(account()))
        assertFalse(signInAlertsApply(account(localSigning = false, externalSigning = true)))
        assertFalse(signInAlertsApply(account(signedOut = true)))
        assertFalse(signInAlertsApply(account(unlinked = true)))
    }

    /** A new sign-in is announced with an alert; the same one read again posts nothing. */
    @Test
    fun aSignInIsAnnouncedOnce() {
        val first = plan(listOf(alertSignIn("a")))

        assertEquals(1, first.reviewCount)
        assertTrue(first.reviewAlerts)
        assertEquals(setOf("a"), first.announcedSignIns)

        val again = plan(listOf(alertSignIn("a")), announcedSignIns = first.announcedSignIns, shown = 1)

        assertNull(again.reviewCount)
        assertFalse(again.withdrawReview)
    }

    /** Several new sign-ins at once share one alert with their count. */
    @Test
    fun severalNewSignInsShareOneAlert() {
        val plan = plan(listOf(alertSignIn("a"), alertSignIn("b"), alertSignIn("c")))

        assertEquals(3, plan.reviewCount)
        assertTrue(plan.reviewAlerts)
    }

    /** A new sign-in beside one already announced alerts again, with the full count. */
    @Test
    fun aNewSignInBesideAnAnnouncedOneAlerts() {
        val plan = plan(listOf(alertSignIn("a"), alertSignIn("b")), announcedSignIns = setOf("a"), shown = 1)

        assertEquals(2, plan.reviewCount)
        assertTrue(plan.reviewAlerts)
    }

    /** One of two answered updates the shown count quietly. */
    @Test
    fun aSmallerCountIsUpdatedQuietly() {
        val plan = plan(listOf(alertSignIn("b")), announcedSignIns = setOf("a", "b"), shown = 2)

        assertEquals(1, plan.reviewCount)
        assertFalse(plan.reviewAlerts)
    }

    /** Nothing left to review withdraws the notification, and what was announced stays announced. */
    @Test
    fun nothingToReviewWithdraws() {
        val plan = plan(emptyList(), announcedSignIns = setOf("a"), shown = 1)

        assertNull(plan.reviewCount)
        assertTrue(plan.withdrawReview)
        assertEquals(setOf("a"), plan.announcedSignIns)
    }

    /** A sign-in marked Mine, don't link is never announced. */
    @Test
    fun aSignInMarkedMineIsNeverAnnounced() {
        val plan = plan(signInsToReview(listOf(alertSignIn("a")), mine = setOf("a")))

        assertNull(plan.reviewCount)
        assertTrue(plan.withdrawReview)
    }

    /** Each warning item is announced once; a new item announces again; no warning withdraws. */
    @Test
    fun eachWarningItemIsAnnouncedOnce() {
        val first = plan(emptyList(), warning = listOf(alertWarning("a")))
        assertTrue(first.postCompromise)
        assertFalse(first.withdrawCompromise)

        val again = plan(emptyList(), warning = listOf(alertWarning("a")), announcedWarning = first.announcedWarning)
        assertFalse(again.postCompromise)

        val more =
            plan(emptyList(), warning = listOf(alertWarning("a"), alertWarning("b")), announcedWarning = first.announcedWarning)
        assertTrue(more.postCompromise)

        assertTrue(plan(emptyList(), announcedWarning = more.announcedWarning).withdrawCompromise)
    }

    /** The same slot and reason at another time is a new item. */
    @Test
    fun aWarningItemIsKeyedByReasonSlotAndTime() {
        val first = alertWarning("a")
        val later = first.copy(occurredAt = 9u)
        val other = first.copy(reason = CompromiseWarningReasonFfi.FOREIGN_OWN_SLOT_KEY_PACKAGE)

        assertEquals(3, setOf(first, later, other).map(::compromiseWarningKey).toSet().size)
    }

    private fun plan(
        toReview: List<UnrecognizedSignInFfi>,
        warning: List<CompromiseWarningItemFfi> = emptyList(),
        announcedSignIns: Set<String> = emptySet(),
        announcedWarning: Set<String> = emptySet(),
        shown: Int = 0,
    ) = planSignInNotifications(toReview, warning, announcedSignIns, announcedWarning, shown)

    private fun account(
        localSigning: Boolean = true,
        externalSigning: Boolean = false,
        signedOut: Boolean = false,
        unlinked: Boolean = false,
    ) = AccountSummaryFfi(
        label = "account",
        accountIdHex = "id",
        localSigning = localSigning,
        externalSigning = externalSigning,
        signedOut = signedOut,
        running = true,
        unlinked = unlinked,
    )
}

/** A new sign-in waiting for an answer in [slot]. */
internal fun alertSignIn(
    slot: String,
    beforeThisDevice: Boolean = false,
    signedIn: Boolean = true,
    state: UnrecognizedSignInStateFfi = UnrecognizedSignInStateFfi.PROMPT,
) = UnrecognizedSignInFfi(
    slotIdHex = slot,
    client = "White Noise",
    appearedAt = 1_800_000_000u,
    lastSeenAt = 1_800_000_000u,
    beforeThisDevice = beforeThisDevice,
    signedIn = signedIn,
    state = state,
    linkedBySlotIdHex = null,
    previouslyLinkedHere = false,
)

/** A rejected-sign-in warning item for [slot]. */
internal fun alertWarning(slot: String) = CompromiseWarningItemFfi(CompromiseWarningReasonFfi.REJECTED_SIGN_IN, slot, null, 1u)
