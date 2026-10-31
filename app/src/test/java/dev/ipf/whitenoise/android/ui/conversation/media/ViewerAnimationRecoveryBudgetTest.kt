package dev.ipf.whitenoise.android.ui.conversation.media

import dev.ipf.whitenoise.android.media.AnimationTermination
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerAnimationRecoveryBudgetTest {
    @Test
    fun onlySystemConfirmedUnknownWorkerLossCanSpendTheSingleReplacement() {
        val budget = ViewerAnimationRecoveryBudget()
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
