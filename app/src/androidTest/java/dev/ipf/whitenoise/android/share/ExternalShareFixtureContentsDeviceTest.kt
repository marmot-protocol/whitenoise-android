package dev.ipf.whitenoise.android.share

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ipf.whitenoise.android.PullRequestDeviceSmoke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Reused fixture names must never grant contents left by a previous cache file. */
@PullRequestDeviceSmoke
@RunWith(AndroidJUnit4::class)
class ExternalShareFixtureContentsDeviceTest {
    @Test
    fun resizingAnExistingFixtureReplacesItsPrefixAndTail() {
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File.createTempFile("external-share-fixture-", ".bin", directory)
        val cases =
            listOf(
                0L to byteArrayOf(),
                1L to byteArrayOf(0),
                2L to byteArrayOf(0, 0),
                3L to byteArrayOf(1, 2, 3),
                5L to byteArrayOf(80, 75, 3, 4, 0),
                8L to byteArrayOf(80, 75, 3, 4, 0, 0, 0, 0),
            )
        try {
            for ((size, expected) in cases) {
                source.writeBytes("previous-private-cache-content".toByteArray())
                ExternalShareDispatchActivity.writeExternalFixture(source, false, size)
                assertArrayEquals("fixture size $size", expected, source.readBytes())
            }
            source.writeBytes("previous-private-cache-content".toByteArray())
            ExternalShareDispatchActivity.writeExternalFixture(source, true, 8L)
            assertArrayEquals(byteArrayOf(), source.readBytes())
        } finally {
            assertTrue("test-owned fixture removed", source.delete())
        }
    }
}
