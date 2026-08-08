package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.audio.kotlinFunctionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Regression coverage for #1835 and #1840's reaction window during roster hydration. */
class CanAcceptReactionTest {
    @Test
    fun acceptsPositiveMembershipSeedBeforeLiveRosterHydrates() {
        assertTrue(
            acceptsReaction(
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = true,
            ),
        )
    }

    @Test
    fun rejectsVerifiedRemovalDespitePositiveSeed() {
        assertFalse(
            acceptsReaction(
                membersVerified = true,
                isSelfMember = false,
                seededSelfMember = true,
            ),
        )
    }

    @Test
    fun rejectsLocalSelfLeaveDespitePositiveSeed() {
        assertFalse(
            acceptsReaction(
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = true,
                selfLeft = true,
            ),
        )
    }

    @Test
    fun rejectsUnknownMembershipAndMissingAccount() {
        assertFalse(
            acceptsReaction(
                membersVerified = false,
                isSelfMember = false,
                seededSelfMember = false,
            ),
        )
        assertFalse(acceptsReaction(accountRef = null))
    }

    @Test
    fun rejectsTerminalGroupStates() {
        assertFalse(acceptsReaction(unrecoverable = true))
        assertFalse(acceptsReaction(disbanding = true))
        assertFalse(acceptsReaction(disbanded = true))
    }

    @Test
    fun reactionPathUsesSeededAcceptanceAndVisibleRejection() {
        val source = controllersSource().readText()
        val body = source.kotlinFunctionBody("toggleReaction")
        val acceptance = source.kotlinFunctionBody("reactionAccountIfAccepted")

        assertTrue("the reaction path must run its acceptance gate", "reactionAccountIfAccepted()" in body)
        assertTrue("reaction acceptance must use the seed-safe membership policy", "canAcceptReaction(" in acceptance)
        assertTrue("a rejected reaction must not fail silently", "R.string.toast_reaction_failed" in acceptance)
        assertFalse("the verified-only send gate must not reject a positive seed", "canSendMessages" in body)
    }

    @Test
    fun reactionRendersOptimisticallyBeforeCommitAndReconcilesConfirmedEcho() {
        val source = controllersSource().readText()
        val toggle = source.kotlinFunctionBody("toggleReaction")
        val optimisticInsert = toggle.indexOf("optimisticReactionChanges[optimisticId]")
        val optimisticRender = toggle.indexOf("recomputeReactions()", startIndex = optimisticInsert)
        val engineCommit = toggle.indexOf("withGroupCommitLock", startIndex = optimisticRender)

        assertTrue("the optimistic reaction must be recorded", optimisticInsert >= 0)
        assertTrue("the reaction chip must render before the engine commit", optimisticRender > optimisticInsert)
        assertTrue("the engine commit must follow the optimistic render", engineCommit > optimisticRender)

        val liveUpdate = source.kotlinFunctionBody("applyTimelineChanges")
        val pruneConfirmed = liveUpdate.indexOf("pruneConfirmedOptimisticReactions()")
        val recomputeConfirmed = liveUpdate.indexOf("recomputeReactions(reactionTargets)")
        assertTrue("a confirmed reaction echo must prune its matching overlay", pruneConfirmed >= 0)
        assertTrue("confirmed tallies must render after overlay reconciliation", recomputeConfirmed > pruneConfirmed)
    }

    private fun acceptsReaction(
        accountRef: String? = "acct",
        membersVerified: Boolean = true,
        isSelfMember: Boolean = true,
        seededSelfMember: Boolean = false,
        selfLeft: Boolean = false,
        unrecoverable: Boolean = false,
        disbanding: Boolean = false,
        disbanded: Boolean = false,
    ): Boolean =
        canAcceptReaction(
            accountRef = accountRef,
            membersVerified = membersVerified,
            isSelfMember = isSelfMember,
            seededSelfMember = seededSelfMember,
            selfLeft = selfLeft,
            unrecoverable = unrecoverable,
            disbanding = disbanding,
            disbanded = disbanded,
        )

    private fun controllersSource(): File =
        listOf(
            File("src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
            File("app/src/main/java/dev/ipf/whitenoise/android/state/Controllers.kt"),
        ).firstOrNull(File::exists) ?: error("Missing Controllers.kt")
}
