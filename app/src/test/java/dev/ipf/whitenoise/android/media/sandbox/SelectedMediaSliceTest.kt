package dev.ipf.whitenoise.android.media.sandbox

import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SelectedMediaSliceTest {
    @Test
    fun selectedAssetCannotExposeItsProviderContainerPrefixOrSuffix() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("slice-", ".bin", context.cacheDir)
        try {
            file.writeBytes(byteArrayOf(9, 9, 1, 2, 3, 8, 8))
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { input ->
                val output = ByteArrayOutputStream()
                assertTrue(copySelectedMediaSlice(input.fileDescriptor, 2L, 3L, output))
                assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
                assertFalse(copySelectedMediaSlice(input.fileDescriptor, 2L, 100L, ByteArrayOutputStream()))
            }
        } finally {
            file.delete()
        }
    }
}
