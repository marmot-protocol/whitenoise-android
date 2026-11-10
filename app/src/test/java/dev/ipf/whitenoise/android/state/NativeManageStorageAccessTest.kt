package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.lang.reflect.Proxy

/** The MarmotKit-backed storage access: partial removals, their cache cleanup, and owner admission. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NativeManageStorageAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** A failure part way returns what was removed and still clears playback copies for it. */
    @Test fun partialFailureCountsAndCleansWhatWasRemoved() =
        runBlocking {
            val native = FakeRemovalNative(failOn = "m2")
            val playback = playbackCopy()
            val removed = access(native).removeAttachments(listOf(attachment("m1", 10), attachment("m2", 20)))
            assertEquals(1L, removed)
            assertEquals(listOf("m1", "m2"), native.removals)
            assertFalse(playback.exists())
        }

    /** A failure before anything was removed fails the action and leaves every copy alone. */
    @Test fun failureBeforeAnyRemovalRethrowsWithoutCleanup() =
        runBlocking {
            val playback = playbackCopy()
            try {
                access(FakeRemovalNative(failOn = "m1")).removeAttachments(listOf(attachment("m1", 10)))
                fail("expected the removal to fail")
            } catch (expected: IllegalStateException) {
                assertEquals("remove m1", expected.message)
            }
            assertTrue(playback.exists())
        }

    /** A retired owner is refused before any native call. */
    @Test fun retiredOwnerMakesNoNativeCall() =
        runBlocking {
            val native = FakeRemovalNative(failOn = null)
            try {
                access(native, owns = false).removeAttachments(listOf(attachment("m1", 10)))
                fail("expected the retired owner to be refused")
            } catch (_: CancellationException) {
                // Expected, the screen drops the late action.
            }
            assertTrue(native.removals.isEmpty())
        }

    /** Access over [native] for one account, owned while [owns] holds. */
    private fun access(
        native: FakeRemovalNative,
        owns: Boolean = true,
    ): NativeManageStorageAccess {
        val runtime = AppMarmotRuntime(rootPath = "test", marmot = native.marmot)
        val state =
            WhiteNoiseAppState(
                context = context,
                draftStore = DraftStore.forContext(context),
                accountIdHexResolver = { null },
                accounts = emptyList(),
                activeAccountRef = ACCOUNT,
                initialMarmotRuntime = runtime,
            )
        return NativeManageStorageAccess(ACCOUNT, runtime, state, owns = { owns })
    }

    /** An unheld voice playback copy, which a successful removal clears. */
    private fun playbackCopy(): File {
        val directory = File(context.cacheDir, MediaCacheDirs.VOICE).apply { mkdirs() }
        return directory.resolve("copy.ogg").apply { writeText("x") }
    }

    /** Resolves every attachment to a local reference and removes it, failing on [failOn]. */
    private class FakeRemovalNative(
        private val failOn: String?,
    ) {
        val removals = mutableListOf<String>()
        val marmot =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "attachmentLocalAssets" -> {
                        val targets = requireNotNull(arguments)[2] as List<*>
                        targets.map { AttachmentLocalAssetFfi("ref:${messageId(it)}", 10uL) }
                    }
                    "controlAttachment" -> remove((requireNotNull(arguments)[1] as String).removePrefix("ref:"))
                    "toString" -> "FakeRemovalNative"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> error("Unexpected Marmot call: ${method.name}")
                }
            } as MarmotInterface

        /** Records the removal of [message], failing when it is [failOn]. */
        private fun remove(message: String): Boolean {
            removals += message
            check(message != failOn) { "remove $message" }
            return true
        }

        /** The message ID of one local target. */
        private fun messageId(target: Any?): String = (target as AttachmentLocalTargetFfi).messageIdHex
    }

    private companion object {
        const val ACCOUNT = "account"
    }
}
