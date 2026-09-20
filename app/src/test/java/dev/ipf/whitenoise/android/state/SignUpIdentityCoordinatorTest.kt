package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AccountSummaryFfi
import dev.ipf.whitenoise.android.ui.onboarding.SignUpOwner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignUpIdentityCoordinatorTest {
    /** Controller replacement reuses the first receipt and preserves its original owner. */
    @Test
    fun replacementReconcilesTheCreatedReceiptBeforeAnotherCreation() =
        runTest {
            val coordinator = SignUpIdentityCoordinator()
            val originalOwner = SignUpOwner(runtime = 1, accountRef = null)
            val replacementOwner = SignUpOwner(runtime = 1, accountRef = "other")
            var creations = 0

            val first = coordinator.createOrReuse(originalOwner) { account(++creations) }
            val resumed = coordinator.createOrReuse(replacementOwner) { account(++creations) }

            assertEquals(first, resumed)
            assertEquals(1, creations)
            assertTrue(coordinator.hasPendingReceipt())
            val activated =
                coordinator.reconcile(resumed) { retainedOwner ->
                    assertEquals(originalOwner, retainedOwner)
                    SignUpIdentityReconciliation(receiptHandled = true, activated = false)
                }
            assertFalse(activated)
            assertFalse(coordinator.hasPendingReceipt())

            coordinator.createOrReuse(replacementOwner) { account(++creations) }
            assertEquals(2, creations)
        }

    /** Builds a distinct native receipt for each permitted creation. */
    private fun account(index: Int) =
        AccountSummaryFfi(
            label = "created-$index",
            accountIdHex = index.toString().padStart(64, '0'),
            localSigning = true,
            externalSigning = false,
            signedOut = false,
            running = true,
        )
}
