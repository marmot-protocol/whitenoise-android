package dev.ipf.whitenoise.android.ui.conversation

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pins how a restoration runs again after a pass that left a stored body unread. */
class DraftRestorePassTest {
    /** An interrupted pass runs again, and the first finished pass's result is returned. */
    @Test
    fun interruptedPassRunsAgain() =
        runTest {
            val outcomes =
                ArrayDeque<DraftRestorePass<String>>(
                    listOf(DraftRestorePass.Interrupted, DraftRestorePass.Finished("restored")),
                )
            var passes = 0

            val restored =
                restoreRetryingInterruptions {
                    passes++
                    outcomes.removeFirst()
                }

            assertEquals("restored", restored)
            assertEquals(2, passes)
        }

    /** A restoration interrupted on every pass stops after three and restores nothing, leaving it unrecorded. */
    @Test
    fun everyPassInterruptedRestoresNothing() =
        runTest {
            var passes = 0

            val restored =
                restoreRetryingInterruptions<String> {
                    passes++
                    DraftRestorePass.Interrupted
                }

            assertNull(restored)
            assertEquals(3, passes)
        }

    /** A pass that finished with nothing to publish is not run again. */
    @Test
    fun finishedEmptyPassRunsOnce() =
        runTest {
            var passes = 0

            val restored =
                restoreRetryingInterruptions {
                    passes++
                    DraftRestorePass.Finished<String>(null)
                }

            assertNull(restored)
            assertEquals(1, passes)
        }
}
