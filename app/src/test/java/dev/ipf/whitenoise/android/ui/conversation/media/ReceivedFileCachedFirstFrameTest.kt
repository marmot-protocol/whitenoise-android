package dev.ipf.whitenoise.android.ui.conversation.media

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.state.AttachmentTransferCoordinator
import dev.ipf.whitenoise.android.state.AttachmentTransferState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReceivedFileCachedFirstFrameTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var cacheDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var coordinator: AttachmentTransferCoordinator
    private val keyProvider =
        DiskByteCacheKeyProvider {
            SecretKeySpec(ByteArray(32) { index -> (index + 1).toByte() }, "AES")
        }

    @Before
    fun setUp() {
        cacheDir = Files.createTempDirectory("received-file-first-frame").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        coordinator = AttachmentTransferCoordinator(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        cacheDir.deleteRecursively()
    }

    @Test
    fun coldEncryptedL2HitIsAvailableOnFirstCapturedFrameForEitherDownloadPolicy() {
        val writer = DiskByteCache(cacheDir, maxBytes = 1024, keyProvider = keyProvider)
        val keys = listOf("documents-off", "documents-on")
        keys.forEachIndexed { index, key -> writer.put(key, ByteArray(40) { (index + 3).toByte() }) }
        val cold = DiskByteCache(cacheDir, maxBytes = 1024, keyProvider = keyProvider)
        val remoteCalls = AtomicInteger(0)
        val firstFrames = mutableMapOf<String, MutableList<AttachmentTransferState>>()
        val l1 = mutableMapOf<String, ByteArray>()
        val showConversation = mutableStateOf(true)

        composeRule.setContent {
            if (showConversation.value) {
                Column {
                    CachedReceivedFile(
                        key = keys[0],
                        autoDownloadAllowed = false,
                        cold = cold,
                        l1 = l1,
                        firstFrames = firstFrames,
                        remoteCalls = remoteCalls,
                    )
                    CachedReceivedFile(
                        key = keys[1],
                        autoDownloadAllowed = true,
                        cold = cold,
                        l1 = l1,
                        firstFrames = firstFrames,
                        remoteCalls = remoteCalls,
                    )
                }
            }
        }

        awaitVisible(keys)
        composeRule.runOnIdle {
            keys.forEach { key ->
                assertEquals(AttachmentTransferState.Available, firstFrames.getValue(key).first())
                assertTrue("an L2 hit must hydrate L1", l1.getValue(key).isNotEmpty())
            }
            assertEquals(0, remoteCalls.get())
            showConversation.value = false
        }
        composeRule.runOnIdle { showConversation.value = true }
        awaitVisible(keys)
        composeRule.runOnIdle {
            keys.forEach { key ->
                assertTrue(firstFrames.getValue(key).all { it == AttachmentTransferState.Available })
            }
            assertEquals("reopening must not start remote work", 0, remoteCalls.get())
        }
    }

    @Test
    fun initiallyResolvedCardStillReconcilesItsCacheState() {
        val resolveCalls = AtomicInteger(0)
        val state = mutableStateOf(AttachmentTransferState.Available)

        composeRule.setContent {
            val resolved =
                rememberAttachmentFirstFrameCacheResolution(
                    owner = coordinator,
                    key = "outgoing-file",
                    initiallyResolved = true,
                ) {
                    resolveCalls.incrementAndGet()
                    state.value = AttachmentTransferState.Remote
                }
            if (resolved) Text(state.value.name, Modifier.testTag("outgoing-file"))
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("outgoing-file").fetchSemanticsNodes().size == 1 &&
                state.value == AttachmentTransferState.Remote
        }
        composeRule.runOnIdle { assertEquals(1, resolveCalls.get()) }
    }

    @Composable
    private fun CachedReceivedFile(
        key: String,
        autoDownloadAllowed: Boolean,
        cold: DiskByteCache,
        l1: MutableMap<String, ByteArray>,
        firstFrames: MutableMap<String, MutableList<AttachmentTransferState>>,
        remoteCalls: AtomicInteger,
    ) {
        val initiallyAvailable = l1.containsKey(key)
        val stateFlow = remember(key) { coordinator.acquireState(key, initiallyAvailable) }
        DisposableEffect(key) {
            onDispose { coordinator.releaseState(key) }
        }
        val transferState by stateFlow.collectAsState()
        val resolved =
            rememberAttachmentFirstFrameCacheResolution(
                owner = coordinator,
                key = key,
                initiallyResolved = initiallyAvailable,
            ) {
                coordinator.refresh(key) {
                    val bytes = withContext(Dispatchers.IO) { cold.get(key) }
                    if (bytes != null) l1[key] = bytes
                    bytes != null
                }
            }
        if (resolved) {
            if (
                shouldStartAttachmentDownload(
                    transferState = transferState,
                    policyAllowsDownload = autoDownloadAllowed,
                    sourceEpoch = 1uL,
                    mine = false,
                )
            ) {
                SideEffect { remoteCalls.incrementAndGet() }
            }
            SideEffect { firstFrames.getOrPut(key, ::mutableListOf).add(transferState) }
            Text(transferState.name, Modifier.testTag(key))
        }
    }

    private fun awaitVisible(keys: List<String>) {
        composeRule.waitUntil(timeoutMillis = 5_000) {
            keys.all { key -> composeRule.onAllNodesWithTag(key).fetchSemanticsNodes().size == 1 }
        }
        keys.forEach { key -> composeRule.onAllNodesWithTag(key).assertCountEquals(1) }
    }
}
