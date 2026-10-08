package dev.ipf.whitenoise.android.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Keep new or indirectly named scroll viewports inside the reviewed app-wide visual contract. */
class ScrollEdgeFadeCoverageTest {
    /** The repository inventory checker must pass as part of the regular unit suite. */
    @Test
    fun everyAppOwnedViewportUsesItsReviewedFadePolicy() {
        val root = File("..").canonicalFile
        val output = File.createTempFile("scroll-edge-inventory-", ".log")
        val process =
            ProcessBuilder("python3", "scripts/check_scroll_edge_fades.py")
                .directory(root)
                .redirectErrorStream(true)
                .redirectOutput(output)
                .start()
        try {
            assertTrue("Viewport inventory check exceeded its bound", process.waitFor(60, TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
            output.delete()
        }
    }
}
