package dev.ipf.whitenoise.android.state

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.ipf.marmotkit.AttachmentCategoryFfi
import dev.ipf.marmotkit.AttachmentEntryFfi
import dev.ipf.marmotkit.AttachmentHistoryChangeFfi
import dev.ipf.marmotkit.AttachmentHistoryVersion
import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentLocalBytesFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.marmotkit.AttachmentPageFfi
import dev.ipf.marmotkit.AttachmentPageReadFfi
import dev.ipf.marmotkit.AttachmentTransferSnapshotFfi
import dev.ipf.marmotkit.AttachmentTransferStateFfi
import dev.ipf.marmotkit.AttachmentTransferStatusFfi
import dev.ipf.marmotkit.AttachmentTransferSubscription
import dev.ipf.marmotkit.AutomaticAttachmentRequestFfi
import dev.ipf.marmotkit.EncryptedMediaVersionFfi
import dev.ipf.marmotkit.MarmotInterface
import dev.ipf.marmotkit.MediaAttachmentOutcomeFfi
import dev.ipf.marmotkit.MediaAttachmentReferenceFfi
import dev.ipf.marmotkit.NoPointer
import dev.ipf.marmotkit.ProductRecordResultFfi
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.spec.SecretKeySpec

/** Real host download/cache plumbing with a controllable, network-free native boundary. */
internal class MediaDownloadIntegrationFixture : AutoCloseable {
    private val root = Files.createTempDirectory("media-host-regression").toFile()
    private val keyProvider = DiskByteCacheKeyProvider { SecretKeySpec(ByteArray(32) { 7 }, "AES") }
    private val diskRoot = java.io.File(root, "encrypted")
    val disk = DiskByteCache(diskRoot, maxBytes = 4L * 1024 * 1024, keyProvider = keyProvider)
    private val jobs = ConcurrentHashMap<String, Call>()
    val calls = CopyOnWriteArrayList<Call>()
    val entered = Channel<Call>(Channel.UNLIMITED)
    val cancellationObserved = CompletableDeferred<Unit>()
    val automaticDemands = AtomicInteger()
    val explicitDemands = AtomicInteger()
    val active = AtomicInteger()
    val peak = AtomicInteger()
    var onDownload: (Call) -> Unit = {}
    var explicitDemandFailure: Throwable? = null
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

        @Volatile var bytes: ByteArray? = null

        @Volatile var failure: Throwable? = null

        /** Completes one synthetic native operation successfully. */
        fun succeed(bytes: ByteArray) {
            this.bytes = bytes
            result.complete(bytes)
        }

        /** Delivers a native failure without fabricating any successful plaintext. */
        fun fail(failure: Throwable) {
            this.failure = failure
            result.completeExceptionally(failure)
        }
    }

    /** Replaces only the existing runtime handle; no bootstrap, worker, or socket is started. */
    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
    ) // One exhaustive proxy dispatch keeps unexpected native calls fatal.
    private fun nativeBoundary(): MarmotInterface =
        Proxy.newProxyInstance(
            MarmotInterface::class.java.classLoader,
            arrayOf(MarmotInterface::class.java),
        ) { proxy, method, args ->
            when (method.name.substringBefore('-')) {
                "recordHostTiming" -> ProductRecordResultFfi.IGNORED_DISABLED
                "attachmentHistoryVersion" -> historyVersion()
                "attachmentHistoryPage" ->
                    AttachmentPageReadFfi.Page(
                        AttachmentPageFfi(
                            (0 until 32).map { index ->
                                val request = qualifiedRequest(index)
                                AttachmentEntryFfi(
                                    request.messageIdHex,
                                    checkNotNull(request.sourceMessageIdHex),
                                    "sender",
                                    1u,
                                    1u,
                                    1u,
                                    AttachmentCategoryFfi.IMAGE,
                                    MediaAttachmentOutcomeFfi.Accepted(0u, reference(index)),
                                )
                            },
                            historyVersion(),
                            null,
                            false,
                        ),
                    )
                "attachmentLocalAssets" ->
                    targets(args!!).map { target ->
                        val key = key(args[0] as String, args[1] as String, target)
                        jobs[key]?.bytes?.let { AttachmentLocalAssetFfi(key, it.size.toULong()) }
                            ?: AttachmentLocalAssetFfi(null, 0u)
                    }
                "readAttachmentAsset" -> {
                    val bytes = jobs[args!![1] as String]?.bytes
                    val offset = (args[2] as Number).toLong().toULong()
                    val limit = (args[3] as Number).toInt().toUInt()
                    if (bytes == null) {
                        AttachmentLocalBytesFfi(false, byteArrayOf())
                    } else {
                        AttachmentLocalBytesFfi(
                            true,
                            bytes.copyOfRange(
                                offset.toInt(),
                                minOf(bytes.size, offset.toInt() + limit.toInt()),
                            ),
                        )
                    }
                }
                "attachmentTransferSnapshot" ->
                    AttachmentTransferSnapshotFfi(
                        targets(args!!).map {
                            status(key(args[0] as String, args[1] as String, it))
                        },
                    )
                "subscribeAttachmentTransfers" ->
                    subscription(
                        key(args!![0] as String, args[1] as String, targets(args).single()),
                    )
                "requestAutomaticAttachment", "downloadAttachmentAgain" -> {
                    val key = key(args!![0] as String, args[1] as String, args[2] as AttachmentLocalTargetFfi)
                    val automatic = method.name.substringBefore('-') == "requestAutomaticAttachment"
                    if (automatic) automaticDemands.incrementAndGet() else explicitDemands.incrementAndGet()
                    if (!automatic && jobs[key] != null) explicitDemandFailure?.let { throw it }
                    admit(args, key, explicit = !automatic)
                    if (automatic) {
                        AutomaticAttachmentRequestFfi(status(key), true)
                    } else {
                        key
                    }
                }
                "controlAttachment" -> {
                    jobs[args!![1] as String]?.result?.cancel()
                    cancellationObserved.complete(Unit)
                    true
                }
                "beginAttachmentPermissionUpdate" -> "fixture-generation"
                "setAttachmentAutomaticPermission" -> true
                "downloadMedia" -> error("Legacy network fallback is forbidden")
                "toString" -> "SyntheticMediaBoundary"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected native method: ${method.name}")
            }
        } as MarmotInterface

    /** Extracts ordered targets without silently replacing source identity. */
    private fun targets(args: Array<out Any?>): List<AttachmentLocalTargetFfi> {
        @Suppress("UNCHECKED_CAST")
        return args[2] as List<AttachmentLocalTargetFfi>
    }

    /** This fixture has one immutable history generation, including across final-page checks. */
    private fun historyVersion(): AttachmentHistoryVersion = nativeStub(FixedHistoryVersion::class.java)

    /** Avoids creating a real JNI cleaner or owning any native resource. */
    private class FixedHistoryVersion : AttachmentHistoryVersion(NoPointer) {
        /** Every local history read observes this fixture's immutable generation. */
        override fun changeSince(previous: AttachmentHistoryVersion) = AttachmentHistoryChangeFfi.UNCHANGED

        /** The test version owns no native handle. */
        override fun close() = Unit
    }

    /** Isolates native jobs by account, group, and original source slot. */
    private fun key(
        account: String,
        group: String,
        target: AttachmentLocalTargetFfi,
    ) = "$account/$group/${target.messageIdHex}/${target.sourceMessageIdHex}/${target.attachmentIndex}"

    /** Models durable admission separately from observer lifetime and local plaintext access. */
    private fun admit(
        args: Array<out Any?>,
        key: String,
        explicit: Boolean,
    ) {
        val existing = jobs[key]
        if (existing != null && !explicit) return
        if (existing != null && !existing.result.isCancelled && existing.failure == null) return
        val target = args[2] as AttachmentLocalTargetFfi
        val index =
            target.messageIdHex
                .takeLast(8)
                .toLong(16)
                .toInt() - 17
        val call = Call(args[0] as String, args[1] as String, reference(index))
        jobs[key] = call
        calls += call
        val activeNow = active.incrementAndGet()
        peak.updateAndGet { maxOf(it, activeNow) }
        call.result.invokeOnCompletion { active.decrementAndGet() }
        onDownload(call)
        check(entered.trySend(call).isSuccess)
    }

    /** Returns local-only transfer metadata, never creating a job from a read. */
    private fun status(key: String): AttachmentTransferStatusFfi {
        val call = jobs[key]
        val state =
            when {
                call == null -> AttachmentTransferStateFfi.NOT_REQUESTED
                call.bytes != null -> AttachmentTransferStateFfi.READY
                call.failure != null -> AttachmentTransferStateFfi.FAILED
                call.result.isCancelled -> AttachmentTransferStateFfi.CANCELLED
                else -> AttachmentTransferStateFfi.DOWNLOADING
            }
        return AttachmentTransferStatusFfi(if (call == null) null else key, state, 0u, 0u, null, null)
    }

    /** Emits an initial snapshot; later observation awaits native work without owning or cancelling it. */
    private fun subscription(key: String): AttachmentTransferSubscription {
        val initialSnapshot = AttachmentTransferSnapshotFfi(listOf(status(key)))
        return nativeStub(FixtureTransferFeed::class.java).also {
            var initial = true
            it.nextSnapshot = {
                if (initial) {
                    initial = false
                    initialSnapshot
                } else {
                    checkNotNull(jobs[key]).result.await()
                    AttachmentTransferSnapshotFfi(listOf(status(key)))
                }
            }
        }
    }

    /** Scripted feed allocated without running UniFFI's Android native-cleaner constructor. */
    private class FixtureTransferFeed : AttachmentTransferSubscription(NoPointer) {
        lateinit var nextSnapshot: suspend () -> AttachmentTransferSnapshotFfi?
        private var closed = false

        /** Observes completion without acquiring or cancelling native work. */
        override suspend fun next(): AttachmentTransferSnapshotFfi? {
            if (closed) return null
            return nextSnapshot()
        }

        /** Cancels observation only, preserving the fixture's native job. */
        override fun cancel() {
            closed = true
        }

        /** Releases only the observation lifetime. */
        override fun close() {
            closed = true
        }
    }

    /** Uses the repository's established constructor-free stub pattern for generated native handles. */
    private fun <T> nativeStub(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = field.get(null)
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    /** Discards the encrypted index as after restart; an optional key callback can hold test-owned reads. */
    fun reopenDisk(onKeyRequest: () -> Unit = {}) {
        val provider =
            DiskByteCacheKeyProvider {
                onKeyRequest()
                keyProvider.getOrCreate()
            }
        field("diskMediaCache").set(state, DiskByteCache(diskRoot, maxBytes = 4L * 1024 * 1024, keyProvider = provider))
    }

    /** Damages only the payload authentication tag of this fixture's single encrypted entry. */
    fun corruptPayload(): java.io.File {
        val file = checkNotNull(diskRoot.listFiles()).single { it.extension == "enc" }
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
                val owners = field("inFlightAttachmentAcquisitions").get(state)
                val lockField = owners.javaClass.getDeclaredField("lock").apply { isAccessible = true }
                val entriesField = owners.javaClass.getDeclaredField("entries").apply { isAccessible = true }
                val lock = checkNotNull(lockField.get(owners))
                while (synchronized(lock) { (entriesField.get(owners) as Map<*, *>).size } != count) {
                    delay(1)
                }
            }
        }

    /** Waits for an explicit promotion to cross the real IO cache probe before completing native work. */
    suspend fun awaitExplicitDemands(count: Int) =
        withContext(Dispatchers.IO) {
            withTimeout(10_000) {
                while (explicitDemands.get() != count) delay(1)
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
        ) = qualifiedRequest(index).copy(accountRef = account)

        /** Supplies a complete native identity while keeping the synthetic target network-free. */
        fun qualifiedRequest(index: Int = 0) =
            AttachmentTransferRequest(
                accountRef = ACCOUNT,
                groupIdHex = GROUP,
                messageIdHex = (index + 17).toString(16).padStart(64, '0'),
                attachmentIndex = 0,
                sourceMessageIdHex = (index + 33).toString(16).padStart(64, '0'),
            )

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
