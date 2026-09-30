package dev.ipf.whitenoise.android.state

import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import dev.ipf.whitenoise.android.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies Android accepts the job only while a real app Activity is visible. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
class AttachmentUserInitiatedDownloadDeviceTest {
    @Test
    fun visibleTapCanScheduleAndCancelUserInitiatedTransfer() {
        val request =
            AttachmentTransferRequest(
                accountRef = "instrumentation-probe",
                groupIdHex = "ab".repeat(16),
                messageIdHex = "cd".repeat(32),
                attachmentIndex = 0,
            )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(AttachmentUserInitiatedDownloads.schedule(activity, request))
                AttachmentUserInitiatedDownloads.cancel(activity, request)
            }
        }
    }
}
