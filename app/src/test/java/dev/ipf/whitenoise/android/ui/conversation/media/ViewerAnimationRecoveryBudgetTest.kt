package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.AnimationTermination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerAnimationRecoveryBudgetTest {
    /**
     * Enumerates animation termination reasons and checks that only confirmed unknown worker loss consumes
     * automatic recovery.
     */
    @Test
    fun onlySystemConfirmedUnknownWorkerLossCanSpendTheSingleReplacement() {
        val budget = ViewerAnimationRecovery()
        listOf(
            AnimationTermination.ProtocolRejected,
            AnimationTermination.TransportUnavailable,
            AnimationTermination.CodecRejected,
            AnimationTermination.OwnerClosed,
            AnimationTermination.Preempted,
        ).forEach { assertFalse(budget.claim(it)) }
        assertTrue(budget.claim(AnimationTermination.WorkerLostUnknown))
        assertFalse(budget.claim(AnimationTermination.WorkerLostUnknown))
    }
}
