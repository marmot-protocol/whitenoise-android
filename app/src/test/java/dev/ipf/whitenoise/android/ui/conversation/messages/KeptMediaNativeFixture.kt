package dev.ipf.whitenoise.android.ui.conversation.messages

import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.AttachmentTransferSubscription
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.ProductRecordResultFfi
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import dev.ipf.whitenoise.android.state.AppMarmotRuntime
import dev.ipf.whitenoise.android.state.WhiteNoiseAppState
import kotlinx.coroutines.channels.Channel
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.spec.SecretKeySpec

/** Read-only native boundary: any acquisition command is recorded and rejected. */
internal class KeptMediaNativeFixture(
    state: WhiteNoiseAppState,
    initial: AttachmentTransferStateFfi,
) {
    private val snapshots = Channel<AttachmentTransferSnapshotFfi>(Channel.UNLIMITED)
    val calls = CopyOnWriteArrayList<String>()

    init {
        val disk =
            DiskByteCache(
                Files.createTempDirectory("kept-observation").toFile(),
                maxBytes = 1024 * 1024,
                keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 4 }, "AES") },
            )
        WhiteNoiseAppState::class.java
            .getDeclaredField("diskMediaCache")
            .apply { isAccessible = true }
            .set(state, disk)
        publish(initial)
        val boundary =
            Proxy.newProxyInstance(
                MarmotInterface::class.java.classLoader,
                arrayOf(MarmotInterface::class.java),
            ) { _, method, _ ->
                val name = method.name.substringBefore('-')
                calls += name
                when (name) {
                    "displayName" -> "Fixture"
                    "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                    "attachmentLocalAssets" -> listOf(AttachmentLocalAssetFfi(null, 0u))
                    "subscribeAttachmentTransfers" -> subscription()
                    else -> error("Unexpected native call: $name")
                }
            } as MarmotInterface
        WhiteNoiseAppState::class.java
            .getDeclaredField("marmotRuntime")
            .apply { isAccessible = true }
            .set(state, AppMarmotRuntime("kept-media-test", boundary))
    }

    /** Publishes state owned by native work without starting any host transfer. */
    fun publish(phase: AttachmentTransferStateFfi) {
        check(
            snapshots
                .trySend(
                    AttachmentTransferSnapshotFfi(listOf(AttachmentTransferStatusFfi(null, phase, 1u, 0u, null, null))),
                ).isSuccess,
        )
    }

    /** Allocates a generated-handle fake without loading the native library in Robolectric. */
    private fun subscription(): AttachmentTransferSubscription {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return (unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, Feed::class.java) as Feed)
            .also { it.snapshots = snapshots }
    }

    /** Cancelling the card observer releases only its subscription, never acquisition. */
    private class Feed : AttachmentTransferSubscription(NoPointer) {
        lateinit var snapshots: Channel<AttachmentTransferSnapshotFfi>

        /** Waits for the next synthetic native replacement. */
        override suspend fun next(): AttachmentTransferSnapshotFfi? = snapshots.receiveCatching().getOrNull()

        /** Wakes the observer on disposal. */
        override fun cancel() {
            snapshots.close()
        }

        /** Releases the fake observation channel. */
        override fun close() {
            snapshots.close()
        }
    }
}
