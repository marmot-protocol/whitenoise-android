package dev.ipf.whitenoise.android.notifications

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dev.ipf.whitenoise.android.WhiteNoiseApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Safe platform qualification: no identity, message injection, settings mutation or app-data clearing. */
@RunWith(AndroidJUnit4::class)
class PushWakeRecoveryDeviceTest {
    /** The platform worker exits without bootstrapping native state if another owner already drained its marker. */
    @Test
    fun staleWorkerCompletesWithoutNativeBootstrap() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val app = context.applicationContext as WhiteNoiseApplication
            assumeFalse(
                "A real pending wake belongs to the user and must not be changed",
                PushTokenStore.create(context).pushWakeCatchUpPending(),
            )
            val before = app.initializedAppState()
            val manager = WorkManager.getInstance(context)
            val request = pushWakeWorkRequest(false, Build.VERSION.SDK_INT)
            manager.enqueue(request).result.get()
            withTimeout(15_000L) {
                while (manager
                        .getWorkInfoById(request.id)
                        .get()
                        ?.state
                        ?.isFinished != true
                ) {
                    delay(50L)
                }
            }
            assertEquals(WorkInfo.State.SUCCEEDED, manager.getWorkInfoById(request.id).get()?.state)
            assertSame(before, app.initializedAppState())
        }

    /** Pre-31 recovery requests cannot accidentally create a second foreground-service path. */
    @Test
    fun api30RequestNeverRequiresExpeditedForegroundEmulation() {
        assertFalse(pushWakeWorkRequest(true, 30).workSpec.expedited)
    }
}
