package dev.ipf.whitenoise.android.share

import android.net.Uri
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImportedShareShelfBehaviorTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun failedComposerPersistenceReportsRecoveryWithoutCrashingOrDeletingSources() {
        val context = RuntimeEnvironment.getApplication()
        val files = PrivateShareFiles(context)
        val root = File(context.noBackupFilesDir, PRIVATE_SHARE_DIRECTORY)
        val sources =
            List(2) {
                val (uri, file) = files.newFile()
                file.writeBytes(byteArrayOf(1, 2, 3))
                files.finish(uri, "file-$it.bin", "application/octet-stream", 3)
                uri
            }
        files.leases.holdRequest("storage-failure", sources)
        files.leases.saveShelf("account", "chat", sources.take(1))
        val shelf = root.listFiles()!!.single { it.name.startsWith("shelf-") }
        val blockedWrite = File("${shelf.path}.new")
        val visible = mutableStateOf<List<Uri>>(sources.take(1))
        val outcomes = mutableListOf<Result<ShareStreamStaging>>()
        try {
            composeRule.setContent {
                ImportedShareShelf("account", "chat", visible.value, 0) { outcomes += it }
                Text("Items: ${visible.value.size}")
            }
            composeRule.waitUntil(10_000) { outcomes.any { it.isSuccess } }
            composeRule.waitForIdle()
            assertEquals(sources.take(1), outcomes.first().getOrThrow().documentUris)
            synchronized(privateShareLock) {
                assertTrue(blockedWrite.mkdir())
                File(blockedWrite, "blocked").writeText("storage fixture")
                assertThrows(java.io.IOException::class.java) {
                    files.leases.saveShelf("account", "chat", sources)
                }
            }
            composeRule.runOnIdle { visible.value = sources }
            composeRule.onNodeWithText("Items: 2").assertExists()
            try {
                composeRule.waitUntil(10_000) { outcomes.any { it.isFailure } }
            } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError(
                    "Recovery outcomes=${outcomes.map { it.isSuccess }}; " +
                        "blocked=${blockedWrite.isDirectory}; " +
                        "durableCount=${files.leases.loadShelf("account", "chat").size}",
                    failure,
                )
            }
            assertEquals(sources.take(1), files.leases.loadShelf("account", "chat"))
            sources.forEach { assertNotNull(files.metadata(it)) }
        } finally {
            blockedWrite.deleteRecursively()
            files.leases.saveShelf("account", "chat", emptyList())
            files.leases.releaseRequest("storage-failure")
        }
    }
}
