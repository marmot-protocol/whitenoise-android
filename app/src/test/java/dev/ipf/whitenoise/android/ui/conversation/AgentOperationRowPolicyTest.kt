package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOperationRowPolicyTest {
    @Test
    fun liveAgentOperationUsesDedicatedRow() {
        assertTrue(
            shouldRenderDedicatedAgentOperationRow(
                projectedDeleted = false,
                optimisticallyDeleted = false,
                invalidated = false,
            ),
        )
    }

    @Test
    fun projectedDeletionUsesMessageTombstone() {
        assertFalse(
            shouldRenderDedicatedAgentOperationRow(
                projectedDeleted = true,
                optimisticallyDeleted = false,
                invalidated = false,
            ),
        )
    }

    @Test
    fun optimisticDeletionUsesMessageTombstone() {
        assertFalse(
            shouldRenderDedicatedAgentOperationRow(
                projectedDeleted = false,
                optimisticallyDeleted = true,
                invalidated = false,
            ),
        )
    }

    @Test
    fun invalidatedOperationUsesMessageTombstone() {
        assertFalse(
            shouldRenderDedicatedAgentOperationRow(
                projectedDeleted = false,
                optimisticallyDeleted = false,
                invalidated = true,
            ),
        )
    }
}
