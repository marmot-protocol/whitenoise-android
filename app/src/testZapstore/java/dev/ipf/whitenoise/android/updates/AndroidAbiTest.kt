package dev.ipf.whitenoise.android.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAbiTest {
    @Test
    fun mapsPrimaryAbiToZapstorePlatformId() {
        assertEquals("android-arm64-v8a", AndroidAbi.platformIdForPrimaryAbi("arm64-v8a"))
        assertEquals("android-armeabi-v7a", AndroidAbi.platformIdForPrimaryAbi("armeabi-v7a"))
    }

    @Test
    fun acceptsSupportedPrimaryAbisOnly() {
        assertTrue(AndroidAbi.isSupportedPrimaryAbi("arm64-v8a"))
        assertFalse(AndroidAbi.isSupportedPrimaryAbi("riscv64"))
    }
}
