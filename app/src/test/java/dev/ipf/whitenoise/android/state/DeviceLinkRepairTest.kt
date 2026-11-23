package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.DeviceLinkSnapshotFfi
import dev.ipf.marmotkit.MarmotKitException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A repair's outstanding KeyPackage publication survives reopening the app and stays scoped to its account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DeviceLinkRepairTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences = context.getSharedPreferences("repair-test", Context.MODE_PRIVATE)
    private val snapshot = DeviceLinkSnapshotFfi(null, emptyList(), emptyList(), false)

    /** Reopening after verified discard can only publish, even if Repair or Link is tapped several times. */
    @Test
    fun retryAfterDiscardPublishesBeforePairing() =
        runTest {
            val calls = mutableListOf<String>()
            val owner = DeviceLinkRepair(preferences, "aa")
            val error =
                runCatching {
                    owner.reset({ calls += "reset" }) {
                        calls += "offline"
                        throw MarmotKitException.TransportClosed()
                    }
                }.exceptionOrNull()
            assertTrue(error is DeviceRepairPublicationFailed)
            DeviceLinkRepair(preferences, "bb").start({ error("Wrong account") }, { snapshot })
            val reopened = DeviceLinkRepair(preferences, "aa")
            val offline = runCatching { reopened.start({ throw MarmotKitException.TransportClosed() }, { snapshot }) }
            assertTrue(offline.isFailure)
            reopened.start({ calls += "publish" }) {
                calls += "pair"
                snapshot
            }
            assertEquals(listOf("reset", "offline", "publish", "pair"), calls)
        }

    /** A lost reset response can mean either the old copy survived or the reply was lost after it was discarded. */
    @Test
    fun bothLostReplyBoundariesRequireExplicitRepair() =
        runTest {
            verifyLostReply(discardCommitted = false)
            verifyLostReply(discardCommitted = true)
        }

    private suspend fun verifyLostReply(discardCommitted: Boolean) {
        val owner = DeviceLinkRepair(preferences, "aa")
        var discarded = 0
        val error =
            runCatching {
                owner.reset(
                    discard = {
                        if (discardCommitted) discarded++
                        throw MarmotKitException.TransportClosed()
                    },
                    rotate = { error("Unknown outcome") },
                )
            }.exceptionOrNull()
        assertTrue(error is DeviceRepairConfirmationRequired)
        val reopened = DeviceLinkRepair(preferences, "aa")
        assertTrue(reopened.needsConfirmation)
        val refused =
            runCatching { reopened.start({ error("No permission to rotate") }, { error("No permission to pair") }) }
        assertTrue(refused.exceptionOrNull() is DeviceRepairConfirmationRequired)
        var publications = 0
        reopened.reset(
            discard = {
                if (discarded == 1) throw MarmotKitException.Runtime("device group refused: no_device_group")
                discarded++
            },
            rotate = { publications++ },
        )
        reopened.start({ error("Already published") }, { snapshot })
        assertEquals(1, discarded)
        assertEquals(1, publications)
    }

    /** Repeating Repair after publication failed must preserve any trusted copy subsequently established elsewhere. */
    @Test
    fun knownDiscardIsNeverReplayedOnRepair() =
        runTest {
            val owner = DeviceLinkRepair(preferences, "aa")
            val result = runCatching { owner.reset({}, { throw MarmotKitException.TransportClosed() }) }
            val error = result.exceptionOrNull()
            assertTrue(error is DeviceRepairPublicationFailed)
            var published = false
            DeviceLinkRepair(preferences, "aa").reset({ error("Already discarded") }) { published = true }
            assertTrue(published)
        }

    /** A definitive initial refusal has no outstanding discard or publication obligation. */
    @Test
    fun refusedResetDoesNotRotateOnTheNextStart() =
        runTest {
            val owner = DeviceLinkRepair(preferences, "aa")
            val busy = MarmotKitException.Runtime("device group refused: device_group_busy")
            val result = runCatching { owner.reset({ throw busy }, { error("Reset was refused") }) }
            assertEquals(busy, result.exceptionOrNull())
            DeviceLinkRepair(preferences, "aa").start({ error("No reset committed") }, { snapshot })
        }
}
