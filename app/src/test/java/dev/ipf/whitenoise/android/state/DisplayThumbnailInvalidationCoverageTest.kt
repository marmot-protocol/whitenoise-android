package dev.ipf.whitenoise.android.state

import dev.ipf.whitenoise.android.appModuleFile
import dev.ipf.whitenoise.android.appModuleSource
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original invalidation must never bypass the paired persistent-derivative boundary. */
class DisplayThumbnailInvalidationCoverageTest {
    /** Scans every production state owner, so new cleanup call sites cannot silently omit derivatives. */
    @Test
    fun originalCleanupAlwaysUsesThePairedBoundary() {
        val root = appModuleFile("src/main/java/dev/ipf/whitenoise/android/state")
        val directRemoval = Regex("""\bdiskMediaCache\.(clear|remove|removeByCiphertextTags)\(""")
        root.walkTopDown().filter { it.extension == "kt" && it.name != "DisplayThumbnailRequest.kt" }.forEach {
            assertFalse(
                "Unpaired original-cache invalidation in ${it.name}",
                directRemoval.containsMatchIn(it.readText()),
            )
        }
        val boundary = appModuleSource("src/main/java/dev/ipf/whitenoise/android/state/DisplayThumbnailRequest.kt")
        listOf("remove", "removeByCiphertextTags", "clear").forEach { operation ->
            assertTrue("Derivative $operation missing", "displayThumbnails.$operation(" in boundary)
            assertTrue("Original $operation missing", "diskMediaCache.$operation(" in boundary)
        }
    }
}
