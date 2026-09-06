package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.MediaDownloadResultFfi
import dev.ipf.whitenoise.android.media.DiskByteCache
import dev.ipf.whitenoise.android.media.DiskByteCacheKeyProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.RandomAccessFile
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn

/** Real host download/cache plumbing with a controllable, network-free native boundary. */
internal class MediaDownloadIntegrationFixture : AutoCloseable {
    private val root = Files.createTempDirectory("media-host-regression").toFile()
    private val keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    val disk = DiskByteCache(root, maxBytes = 4L * 1024 * 1024, keyProvider = keyProvider)
    val calls = CopyOnWriteArrayList<Call>()
    val entered = Channel<Call>(Channel.UNLIMITED)
    val active = AtomicInteger()
    val peak = AtomicInteger()
    var onDownload: (Call) -> Unit = {}
    val state =
        WhiteNoiseAppState(
            context = ApplicationProvider.getApplicationContext<Context>(),
            draftStore = DraftStore(DiscardedDrafts),
            accountIdHexResolver = { null },
            accounts = emptyList(),
            activeAccountRef = ACCOUNT,
        ).also {
            field("marmotRuntime").set(it, AppMarmotRuntime("test", nativeBoundary()))
            field("diskMediaCache").set(it, disk)
        }

    /** A suspended native result; each test chooses when and whether verified bytes arrive. */
    class Call(
        val account: String,
        val group: String,
        val reference: MediaAttachmentReferenceFfi,
    ) {
        val result = CompletableDeferred<ByteArray>()

        /** Completes one synthetic native operation successfully. */
        fun succeed(bytes: ByteArray) {
            result.complete(bytes)
        }

        /** Delivers a native failure without fabricating any successful plaintext. */
        fun fail(failure: Throwable) {
            result.completeExceptionally(failure)
        }
    }

    /** Replaces only the existing runtime handle; no bootstrap, worker, or socket is started. */
    private fun nativeBoundary(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "downloadMedia" -> suspendDownload(checkNotNull(args))
                "toString" -> "SyntheticMediaBoundary"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected native method: ${method.name}")
            }
        } as MarmotInterface

    /** Suspends the actual FFI call rather than blocking an IO thread or simulating host ownership. */
    @Suppress("UNCHECKED_CAST")
    private fun suspendDownload(args: Array<out Any?>): Any? {
        val call = Call(args[0] as String, args[1] as String, args[2] as MediaAttachmentReferenceFfi)
        val operation: suspend () -> MediaDownloadResultFfi = {
            val activeNow = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, activeNow) }
            calls += call
            try {
                onDownload(call)
                entered.send(call)
                val bytes = call.result.await()
                MediaDownloadResultFfi(bytes, call.reference.fileName, call.reference.mediaType, bytes.size.toULong())
            } finally {
                active.decrementAndGet()
            }
        }
        return operation.startCoroutineUninterceptedOrReturn(args.last() as Continuation<MediaDownloadResultFfi>)
    }

    /** Discards the encrypted index as after restart; an optional key callback can hold test-owned reads. */
    fun reopenDisk(onKeyRequest: () -> Unit = {}) {
        val provider =
            DiskByteCacheKeyProvider {
                onKeyRequest()
                keyProvider.getOrCreate()
            }
        field("diskMediaCache").set(state, DiskByteCache(root, maxBytes = 4L * 1024 * 1024, keyProvider = provider))
    }

    /** Damages only the payload authentication tag of this fixture's single encrypted entry. */
    fun corruptPayload(): java.io.File {
        val file = checkNotNull(root.listFiles()).single { it.extension == "enc" }
        RandomAccessFile(file, "rw").use {
            val offset = it.length() - 1
            it.seek(offset)
            val last = it.readByte().toInt()
            it.seek(offset)
            it.writeByte(last xor 1)
        }
        return file
    }

    /** Waits for real IO cache misses to register, so priority assertions cannot race test setup. */
    suspend fun awaitOwners(count: Int) =
        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                val lock = checkNotNull(field("inFlightDownloadsLock").get(state))
                while (synchronized(lock) { (field("inFlightDownloads").get(state) as Map<*, *>).size } != count) {
                    delay(1)
                }
            }
        }

    /** Cancels only fixture-owned work and deletes only its fresh temporary directory. */
    override fun close() {
        (field("mutationsScope").get(state) as CoroutineScope).cancel()
        calls.forEach { it.result.cancel() }
        entered.close()
        root.deleteRecursively()
    }

    /** Keeps synthetic drafts in memory and never opens native storage. */
    private object DiscardedDrafts : DraftPersistence {
        override fun read(): Map<String, String> = emptyMap()

        override fun write(
            key: String,
            value: String?,
        ) = Unit
    }

    companion object {
        const val ACCOUNT = "synthetic-account"
        val GROUP = "01".repeat(16)

        /** Returns distinct, account-scoped request identities without user content. */
        fun request(
            index: Int,
            account: String = ACCOUNT,
        ) = AttachmentTransferRequest(account, GROUP, "image-$index", 0)

        /** Carries distinct media descriptors; no locator can reach a network endpoint. */
        fun reference(index: Int) =
            MediaAttachmentReferenceFfi(
                locators = emptyList(),
                ciphertextSha256 = index.toString(16).padStart(64, '0'),
                plaintextSha256 = (index + 32).toString(16).padStart(64, '0'),
                nonceHex = "00".repeat(12),
                fileName = "image-$index.png",
                mediaType = "image/png",
                version = EncryptedMediaVersionFfi.V1,
                sourceEpoch = 1uL,
                dim = "128x128",
                thumbhash = null,
            )

        /** Reuses the existing test-only field injection convention without new production seams. */
        private fun field(name: String): java.lang.reflect.Field {
            val reflected = WhiteNoiseAppState::class.java.getDeclaredField(name)
            reflected.isAccessible = true
            return reflected
        }
    }
}
