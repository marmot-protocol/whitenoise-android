package dev.ipf.whitenoise.android.updates

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppSelfUpdateStorageTest {
    @Test
    fun sweepStaleApksDeletesOldFilesAndPartials() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = AppSelfUpdateStorage.updatesDirectory(context)
        val stale = File(directory, "darkmatter-old.apk")
        val fresh = File(directory, "darkmatter-new.apk")
        val partial = File(directory, "darkmatter-new.apk.part")
        stale.writeText("stale")
        fresh.writeText("fresh")
        partial.writeText("partial")
        val now = System.currentTimeMillis()
        stale.setLastModified(now - AppSelfUpdateStorage.STALE_MAX_AGE_MS - 1_000L)
        fresh.setLastModified(now)
        partial.setLastModified(now)

        AppSelfUpdateStorage.sweepStaleApks(context, nowMillis = now)

        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertFalse(partial.exists())
    }

    @Test
    fun deleteFileRemovesVerifiedAndPartialDownloadsOnFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val apk = AppSelfUpdateStorage.apkFileForVersion(context, "2026.6.99")
        apk.writeText("apk")
        val partial = File(apk.parentFile, "${apk.name}.part")
        partial.writeText("partial")

        AppSelfUpdateStorage.deleteFile(apk)
        AppSelfUpdateStorage.deleteFile(partial)

        assertFalse(apk.exists())
        assertFalse(partial.exists())
    }
}
