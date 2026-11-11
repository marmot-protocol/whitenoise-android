package dev.ipf.whitenoise.android.ui.onboarding.setup

import dev.ipf.whitenoise.android.appModuleSource
import dev.ipf.whitenoise.android.functionBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Account setup suppresses later Open Chats attempts only after a real activation (#3697). */
class AccountSetupActivationReceiptCoverageTest {
    private val controller = appModuleSource("$SETUP/AccountSetupController.kt")
    private val coordinator = appModuleSource("$SETUP/AccountSetupCoordinator.kt")

    /** The controller latches readiness from the activation result, never after a bare callback return. */
    @Test
    fun openChatsLatchesOnlyAReportedActivation() {
        assertTrue("onReady reports activation", "private val onReady: suspend () -> Boolean" in controller)
        val openChats = controller.functionBody("openChats")
        assertTrue("the latch takes the activation result", "readinessDelivered = onReady()" in openChats)
        assertFalse("no unconditional latch after the callback", "readinessDelivered = true" in openChats)
    }

    /** The coordinator returns whether it activated, and every declining check returns false. */
    @Test
    fun finishReturnsTheActivationResult() {
        val finish = coordinator.functionBody("finish")
        assertTrue("finish reports activation", "return activated" in finish)
        assertFalse("no declining path returns without a result", Regex("""return\s*\n""").containsMatchIn(finish))
        assertTrue(
            "the controller is handed finish's result",
            "onReady = { finish(token, owner, client, account, isMountCurrent) }" in coordinator,
        )
    }

    private companion object {
        const val SETUP = "src/main/java/dev/ipf/whitenoise/android/ui/onboarding/setup"
    }
}
