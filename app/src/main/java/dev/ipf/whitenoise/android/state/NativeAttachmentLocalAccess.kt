package dev.ipf.whitenoise.android.state

import dev.ipf.marmotkit.AttachmentLocalAssetFfi
import dev.ipf.marmotkit.AttachmentLocalBytesFfi
import dev.ipf.marmotkit.AttachmentLocalTargetFfi
import dev.ipf.whitenoise.android.media.AttachmentPlaintext
import dev.ipf.whitenoise.android.media.DiskByteCacheLease
import dev.ipf.whitenoise.android.media.MediaCacheDirs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

internal const val NATIVE_ATTACHMENT_READ_CHUNK_BYTES = 256 * 1024
private const val NATIVE_ATTACHMENT_TARGET_LIMIT = 64
private val nativeAttachmentLeaseCounter = AtomicLong()
private val preparedNativeAttachmentRoots = mutableSetOf<String>()

/** Exact native identity for an attachment displayed in a projected timeline row. */
internal data class NativeAttachmentTarget(
    val displayMessageIdHex: String,
    val sourceMessageIdHex: String,
    val attachmentIndex: Int,
) {
    init {
        require(ATTACHMENT_MESSAGE_ID_HEX.matches(displayMessageIdHex))
        require(ATTACHMENT_MESSAGE_ID_HEX.matches(sourceMessageIdHex))
        require(attachmentIndex >= 0)
    }

    /** Converts without substituting the display id for the original source id. */
    fun toFfi(): AttachmentLocalTargetFfi =
        AttachmentLocalTargetFfi(
            messageIdHex = displayMessageIdHex,
            sourceMessageIdHex = sourceMessageIdHex,
            attachmentIndex = attachmentIndex.toUInt(),
        )
}

/**
 * Reads MarmotKit-retained plaintext into closeable owner-private leases.
 * Opaque asset references live only for this call and are never persisted.
 */
internal class NativeAttachmentLocalAccess(
    private val cacheRoot: File,
    private val queryAssets: suspend (List<AttachmentLocalTargetFfi>) -> List<AttachmentLocalAssetFfi>,
    private val readAsset: suspend (String, ULong, UInt) -> AttachmentLocalBytesFfi,
) {
    /** Returns one result for every target, preserving target order and duplicates. */
    suspend fun open(targets: List<NativeAttachmentTarget>): List<AttachmentPlaintext?> =
        withContext(Dispatchers.IO) {
            require(targets.size <= NATIVE_ATTACHMENT_TARGET_LIMIT) { "native attachment target batch exceeds 64" }
            if (targets.isEmpty()) return@withContext emptyList()
            prepareLeaseDirectory()
            val assets = queryAssets(targets.map(NativeAttachmentTarget::toFfi))
            check(assets.size == targets.size) { "native local asset response changed target cardinality" }
            assets.map { asset -> materialize(asset) }
        }

    /** Removes process-orphaned plaintext once before creating the first new lease. */
    private fun prepareLeaseDirectory() {
        val directory = File(cacheRoot, MediaCacheDirs.NATIVE_ATTACHMENT_LEASES)
        synchronized(preparedNativeAttachmentRoots) {
            if (directory.absolutePath in preparedNativeAttachmentRoots && directory.isDirectory) return
            check(directory.deleteRecursively()) { "failed to remove orphaned native attachment plaintext" }
            check(directory.mkdirs() || directory.isDirectory) { "failed to create native attachment lease directory" }
            preparedNativeAttachmentRoots.add(directory.absolutePath)
        }
    }

    /** Streams one verified native asset without allocating its full plaintext on the heap. */
    @Suppress("ReturnCount") // Unavailable chunks invalidate and delete the partial lease immediately.
    private suspend fun materialize(asset: AttachmentLocalAssetFfi): AttachmentPlaintext? {
        val reference = asset.reference ?: return null
        require(asset.byteCount <= Long.MAX_VALUE.toULong()) { "native attachment is too large for Android file APIs" }
        val directory = File(cacheRoot, MediaCacheDirs.NATIVE_ATTACHMENT_LEASES)
        check(directory.isDirectory) { "native attachment lease directory is unavailable" }
        val file = File(directory, "lease-${nativeAttachmentLeaseCounter.incrementAndGet()}.tmp")
        var completed = false
        try {
            FileOutputStream(file).use { output ->
                var offset = 0uL
                do {
                    currentCoroutineContext().ensureActive()
                    val remaining = asset.byteCount - offset
                    val limit = minOf(NATIVE_ATTACHMENT_READ_CHUNK_BYTES.toULong(), maxOf(remaining, 1uL)).toUInt()
                    val chunk = readAsset(reference, offset, limit)
                    if (!chunk.available) return null
                    if (chunk.bytes.isEmpty()) break
                    if (chunk.bytes.size > limit.toInt() || chunk.bytes.size.toULong() > remaining) {
                        throw IOException("native attachment chunk exceeds requested or declared length")
                    }
                    output.write(chunk.bytes)
                    offset += chunk.bytes.size.toULong()
                } while (offset < asset.byteCount)
                if (offset != asset.byteCount) {
                    throw IOException("native attachment ended at $offset of ${asset.byteCount} bytes")
                }
            }
            completed = true
            return AttachmentPlaintext.Lease(DiskByteCacheLease(file))
        } finally {
            if (!completed) runCatching { file.delete() }
        }
    }
}

/** Test-only access to the ephemeral file without widening the production lease API. */
internal fun AttachmentPlaintext.leaseFileForTesting(): File? = (this as? AttachmentPlaintext.Lease)?.file

/** Reads one native-retained asset without making its opaque locator durable. */
internal suspend fun WhiteNoiseAppState.openNativeAttachment(request: AttachmentTransferRequest): AttachmentPlaintext? {
    val target = resolveNativeAttachmentTarget(request) ?: return null
    return NativeAttachmentLocalAccess(
        cacheRoot = diskMediaCache.siblingCacheRoot(),
        queryAssets = { targets ->
            marmotIo { attachmentLocalAssets(request.accountRef, request.groupIdHex, targets) }
        },
        readAsset = { reference, offset, limit ->
            marmotIo { readAttachmentAsset(request.accountRef, reference, offset, limit) }
        },
    ).open(listOf(target)).single()
}

/** Returns whether MarmotKit currently owns verified readable plaintext for this exact target. */
internal suspend fun WhiteNoiseAppState.hasNativeAttachment(request: AttachmentTransferRequest): Boolean {
    val target = resolveNativeAttachmentTarget(request) ?: return false
    return marmotIo {
        attachmentLocalAssets(request.accountRef, request.groupIdHex, listOf(target.toFfi()))
    }.single().reference != null
}

/** Resolves legacy source-less requests through native history before local access. */
@Suppress("MaxLineLength") // Kept as an expression body by ktlint's formatter.
internal suspend fun WhiteNoiseAppState.resolveNativeAttachmentTarget(request: AttachmentTransferRequest): NativeAttachmentTarget? =
    request.nativeTarget()
        ?: runCatchingCancellable { findNativeAttachment(request)?.target }.getOrNull()
