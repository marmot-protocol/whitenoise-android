package dev.ipf.whitenoise.android.ui.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    @Test
    fun localAndRemoteDeleteShareOneInFlightGuard() {
        val source =
            File("src/main/java/dev/ipf/whitenoise/android/ui/conversation/AgentOperationRow.kt")
                .readText()
                .substringAfter("private fun AgentOperationDeleteDialog(")
                .substringBefore("internal fun AgentOperationRow(")

        assertTrue(
            "dialog must expose one busy state",
            "var deleteInFlight by remember" in source,
        )
        assertTrue(
            "both delete callbacks must reject re-entry",
            source.split("if (!deleteInFlight)").size - 1 == 2,
        )
        assertTrue(
            "the dialog must disable every delete action while busy",
            "deleteInFlight = deleteInFlight" in source,
        )
        assertTrue(
            "both mutations must release the guard",
            source.split("deleteInFlight = false").size - 1 == 2,
        )
    }
}
